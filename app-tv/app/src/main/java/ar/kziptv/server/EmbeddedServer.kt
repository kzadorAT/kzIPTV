package ar.kziptv.server

import android.content.Context
import android.util.Log
import ar.kziptv.data.KzStore
import ar.kziptv.ui.StateDto
import ar.kziptv.ui.TvController
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

@Serializable
data class PairRequest(val code: String = "")

@Serializable
data class SourceRequest(val url: String = "", val name: String = "", val content: String = "", val token: String? = null)

@Serializable
data class FavoriteRequest(val channelId: String = "", val enabled: Boolean? = null, val token: String? = null)

@Serializable
data class CommandRequest(
    val type: String = "",
    val code: Int? = null,
    val delta: Int? = null,
    val channelId: String? = null,
    val variantId: String? = null,
    val enabled: Boolean? = null,
    val text: String? = null,
    val category: String? = null,
    val token: String? = null
)

@Serializable
data class PairResponse(val ok: Boolean, val token: String = "", val message: String = "")

@Serializable
data class SourceResponse(val ok: Boolean, val count: Int = 0, val message: String = "")

class EmbeddedServer(
    private val context: Context,
    private val controller: TvController,
    private val store: KzStore
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var running: EmbeddedServer<*, *>? = null

    fun start(): Int {
        for (port in 8080..8090) {
            try {
                val server = embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }
                server.start(wait = false)
                running = server
                controller.setServerUrl("http://${localIp()}:${port}")
                return port
            } catch (e: Exception) {
                // puerto ocupado -> siguiente
            }
        }
        controller.setServerUrl("")
        return -1
    }

    fun stop() {
        running?.stop(100, 500)
        running = null
    }

    private fun Application.module() {
        install(WebSockets)
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }

        routing {
            get("/") { call.serveCompanion("") }
            get("/pair") { call.serveCompanion("") }

            get("/api/state") {
                call.respond(controller.snapshot())
            }

            get("/api/qr.svg") {
                val data = controller.serverUrl.value + "/pair#" + controller.pairingCode.value
                call.respondBytes(qrSvg(data).toByteArray(), ContentType.parse("image/svg+xml"))
            }

            post("/api/pair") {
                val request = call.receive<PairRequest>()
                if (request.code.trim() == controller.pairingCode.value) {
                    val token = UUID.randomUUID().toString()
                    store.update { it.copy(tokens = it.tokens + token) }
                    call.respond(PairResponse(ok = true, token = token, message = "Emparejado"))
                } else {
                    call.respond(HttpStatusCode.Unauthorized, PairResponse(ok = false, message = "Codigo incorrecto"))
                }
            }

            post("/api/source") {
                val request = call.receive<SourceRequest>()
                if (!validToken(request.token)) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "token invalido"))
                    return@post
                }
                try {
                    val count = if (request.url.isNotBlank()) {
                        controller.addSourceByUrl(request.url)
                    } else {
                        controller.addSourceByContent(request.name, request.content)
                    }
                    call.respond(SourceResponse(ok = true, count = count, message = "OK"))
                } catch (e: Exception) {
                    call.respond(SourceResponse(ok = false, message = e.message ?: "Error"))
                }
            }

            post("/api/favorite") {
                val request = call.receive<FavoriteRequest>()
                if (!validToken(request.token)) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "token invalido"))
                    return@post
                }
                applyFavorite(request.channelId, request.enabled)
                call.respond(controller.snapshot())
            }

            post("/api/command") {
                val command = call.receive<CommandRequest>()
                if (!validToken(command.token)) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "token invalido"))
                    return@post
                }
                handleCommand(json.encodeToString(CommandRequest.serializer(), command))
                call.respond(controller.snapshot())
            }

            webSocket("/ws") {
                val token = call.request.queryParameters["token"]
                if (token != null && token !in store.state.value.tokens) {
                    send(Frame.Text(json.encodeToString(PairResponse.serializer(), PairResponse(ok = false, message = "token invalido"))))
                    return@webSocket
                }
                send(Frame.Text(json.encodeToString(StateDto.serializer(), controller.snapshot())))
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        handleCommand(frame.readText())
                    }
                }
            }

            get("/{path...}") {
                val path = call.parameters.getAll("path")?.joinToString("/") ?: ""
                call.serveCompanion(path)
            }
        }
    }

    /** Token valido = el que devolvio /api/pair (queda guardado en la TV). */
    private fun validToken(token: String?): Boolean =
        !token.isNullOrBlank() && token in store.state.value.tokens

    private fun applyFavorite(channelId: String, enabled: Boolean?) {
        if (channelId.isBlank()) return
        controller.onMain {
            val isFavorite = controller.favorites.value.contains(channelId)
            if (enabled == null || enabled != isFavorite) {
                controller.toggleFavorite(channelId)
            }
        }
    }

    private fun handleCommand(raw: String) {
        val command = try {
            json.decodeFromString(CommandRequest.serializer(), raw)
        } catch (e: Exception) {
            return
        }
        // Todo lo que toca el player o la UI tiene que ir al hilo principal.
        controller.onMain {
            try {
                android.util.Log.d("KzIPTV", "comando remoto: " + command.type + " " + command.code)
                when (command.type) {
                    "key" -> when (command.code) {
                        166 -> controller.zap(1)
                        167 -> controller.zap(-1)
                        185 -> controller.toggleFavorite()
                        183 -> controller.toggleZapMode()
                        23, 66 -> controller.onOk(false)
                        4 -> controller.back()
                        82, 186 -> controller.openPairing()
                        // Flechas: se reinyectan como tecla real para que Compose mueva el foco.
                        19, 20, 21, 22 -> ar.kziptv.MainActivity.injectKey(command.code)
                        else -> Unit
                    }
                    "zap" -> controller.zap(command.delta ?: 1)
                    "play" -> command.channelId?.let { id ->
                        // acepta id de canal logico o de feed concreto
                        controller.catalog.value.resolve(id)?.let { controller.play(it) }
                    }
                    "variant" -> {
                        val groupId = command.channelId.orEmpty()
                        val variantId = command.variantId.orEmpty()
                        if (groupId.isNotBlank() && variantId.isNotBlank()) {
                            controller.playVariant(groupId, variantId)
                        }
                    }
                    "quality" -> controller.openQuality()
                    "favorite" -> command.channelId?.let { applyFavorite(it, command.enabled) }
                    "text" -> controller.setQuery(command.text.orEmpty())
                    "search" -> {
                        controller.setQuery(command.text.orEmpty())
                        controller.openSearch()
                    }
                    "category" -> controller.setCategory(command.category)
                    "grid" -> controller.openGrid()
                    "mosaic" -> controller.openMosaic()
                    else -> Unit
                }
            } catch (e: Exception) {
                Log.e("KzIPTV", "error aplicando comando ${command.type}", e)
            }
        }
    }

    private suspend fun ApplicationCall.serveCompanion(path: String) {
        val normalized = path.trimStart('/')
        val candidates = if (normalized.isEmpty()) {
            listOf("companion/index.html")
        } else {
            listOf("companion/${normalized}", "companion/index.html")
        }
        for (candidate in candidates) {
            val bytes = try {
                context.assets.open(candidate).use { it.readBytes() }
            } catch (e: IOException) {
                null
            } ?: continue
            respondBytes(bytes, ContentType.parse(contentTypeOf(candidate)))
            return
        }
        respondText("No encontrado", status = HttpStatusCode.NotFound)
    }

    private fun contentTypeOf(path: String): String = when {
        path.endsWith(".html") -> "text/html; charset=UTF-8"
        path.endsWith(".js") -> "text/javascript; charset=UTF-8"
        path.endsWith(".css") -> "text/css; charset=UTF-8"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".ico") -> "image/x-icon"
        else -> "application/octet-stream"
    }

    private fun qrSvg(data: String): String {
        val matrix = com.google.zxing.qrcode.QRCodeWriter()
            .encode(data, com.google.zxing.BarcodeFormat.QR_CODE, 320, 320)
        val builder = StringBuilder()
        builder.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${matrix.width} ${matrix.height}\" shape-rendering=\"crispEdges\">")
        builder.append("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>")
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    builder.append("<rect x=\"$x\" y=\"$y\" width=\"1\" height=\"1\" fill=\"black\"/>")
                }
            }
        }
        builder.append("</svg>")
        return builder.toString()
    }

    private fun localIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress ?: "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }
}
