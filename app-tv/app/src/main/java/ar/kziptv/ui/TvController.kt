package ar.kziptv.ui

import android.content.Context
import android.graphics.Bitmap
import ar.kziptv.core.catalog.ChannelCatalog
import ar.kziptv.core.catalog.ChannelGrouper
import ar.kziptv.core.epg.EpgIndex
import ar.kziptv.core.epg.EpgParser
import ar.kziptv.core.model.Channel
import ar.kziptv.core.model.ChannelGroup
import ar.kziptv.core.model.ChannelVariant
import ar.kziptv.core.model.toChannel
import ar.kziptv.core.parse.M3uParser
import ar.kziptv.data.KzStore
import ar.kziptv.data.PersistedSource
import ar.kziptv.data.SourceRepository
import ar.kziptv.core.nav.GridNavigator
import ar.kziptv.core.nav.NumberEntry
import ar.kziptv.core.nav.SurfPlanner
import ar.kziptv.core.nav.RetryPolicy
import ar.kziptv.input.RemoteKeys
import ar.kziptv.playback.ThumbnailManager
import ar.kziptv.playback.PlayerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.zip.GZIPInputStream

enum class Screen { PLAYER, GRID, SETUP, QUALITY, SEARCH, MOSAIC }

/** Una variante (feed/calidad) de un canal logico. */
@Serializable
data class VariantDto(
    val id: String,
    val name: String,
    val label: String = "",
    val quality: String = "",
    val region: String = "",
    val host: String = "",
    val status: String = ChannelVariant.STATUS_UNKNOWN,
    val measured: String = "",
    val active: Boolean = false
)

/** Un canal logico: lo que ve el usuario en la grilla. */
@Serializable
data class ChannelDto(
    val id: String,
    val name: String,
    val logo: String = "",
    val number: Int = 0,
    val groups: List<String> = emptyList(),
    val favorite: Boolean = false,
    val kind: String = Channel.KIND_HLS,
    val tvgId: String = "",
    val now: String = "",
    val next: String = "",
    val variants: List<VariantDto> = emptyList(),
    val activeVariantId: String = ""
)

@Serializable
data class StateDto(
    val pairingCode: String = "",
    val serverUrl: String = "",
    val status: String = "",
    val screen: String = "PLAYER",
    val focusedId: String? = null,
    val focusedName: String = "",
    val focusedCategory: String? = null,
    val currentId: String? = null,
    val currentVariantId: String? = null,
    val currentVariantLabel: String = "",
    val zapFavoritesOnly: Boolean = false,
    val channelCount: Int = 0,
    val groupCount: Int = 0,
    val variantCount: Int = 0,
    val categories: List<String> = emptyList(),
    val channels: List<ChannelDto> = emptyList(),
    val playbackState: String = "idle",
    val playbackError: String = "",
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** Numero de canal escrito con las teclas 0-9 (vacio = no se escribe). */
    val numberBuffer: String = "",
    /** Texto de la busqueda en curso. */
    val searchQuery: String = "",
    /** Errores por tile del mosaico (indice -> mensaje). */
    val mosaicErrors: Map<String, String> = emptyMap(),
    /** Id del resultado de busqueda enfocado (vacio = foco en el campo). */
    val focusedSearchId: String? = null,
    /** Nombre del resultado de busqueda enfocado. */
    val focusedSearchName: String = ""
)

class TvController(
    private val context: Context,
    private val store: KzStore,
    private val repo: SourceRepository,
    val player: PlayerManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val catalog = MutableStateFlow(ChannelCatalog(emptyList()))
    val favorites = MutableStateFlow(store.state.value.favorites.toSet())
    val currentGroup = MutableStateFlow<ChannelGroup?>(null)
    val currentVariant = MutableStateFlow<ChannelVariant?>(null)
    val bannerVisible = MutableStateFlow(false)
    val screen = MutableStateFlow(Screen.PLAYER)
        .also { flow ->
            // traza de cambios de pantalla (depuracion remoto/TV)
            scope.launch {
                flow.collect { android.util.Log.d("KzIPTV", "pantalla -> " + it.name) }
            }
        }
    val category = MutableStateFlow<String?>(null)
    val query = MutableStateFlow("")
    val epg = MutableStateFlow(EpgIndex(emptyList()))
    val status = MutableStateFlow("Cargando…")
    val serverUrl = MutableStateFlow("")
    val pairingCode = MutableStateFlow(store.state.value.pairingCode.ifBlank { generateCode() })
    val zapFavoritesOnly = MutableStateFlow(store.state.value.zapFavoritesOnly)
    val focusedGroupId = MutableStateFlow<String?>(null)
    val focusedVariantId = MutableStateFlow<String?>(null)

    /** Categoria enfocada en la fila de chips (null = el foco esta en la grilla). */
    val focusedCategory = MutableStateFlow<String?>(null)

    val qualityTarget = MutableStateFlow<ChannelGroup?>(null)

    /**
     * Numero escrito con las teclas 0-9 para saltar directo a un canal.
     * Vacio = no se esta escribiendo.
     */
    val numberBuffer = MutableStateFlow("")

    /** Resultados de la busqueda por texto (pantalla SEARCH). */
    val searchResults = MutableStateFlow<List<ChannelGroup>>(emptyList())

    /** Canales que se comparan en el mosaico (uno por tile). */
    val mosaicChannels = MutableStateFlow<List<ChannelGroup>>(emptyList())

    /** Tile enfocado del mosaico. */
    val mosaicFocus = MutableStateFlow(0)

    /** Errores por tile del mosaico (indice -> mensaje). Un tile caido muestra el logo. */
    val mosaicTileErrors = MutableStateFlow<Map<Int, String>>(emptyMap())

    /** El preview del mosaico (canal enfocado) esta reproduciendo? */
    val mosaicPlaying = MutableStateFlow(false)

    /** Miniaturas capturadas: indice del tile -> frame congelado. */
    val mosaicThumbs = MutableStateFlow<Map<Int, Bitmap>>(emptyMap())

    /** Tile que se esta capturando ahora (-1 = ninguno). Es el unico con video. */
    val mosaicLiveIndex = MutableStateFlow(-1)

    /** Canal enfocado dentro de los resultados de busqueda. */
    val focusedSearchId = MutableStateFlow<String?>(null)

    /**
     * Token para pedirle a la UI que enfoque el campo de texto nativo.
     * Cada pedido incrementa el token; la UI lo observa y hace requestFocus().
     */
    val searchFieldRequest = MutableStateFlow(0)

    /**
     * true cuando el campo de texto NATIVO tiene el foco de plataforma y el IME
     * del sistema esta abierto. En ese estado el sistema maneja las teclas.
     */
    val searchFieldFocused = MutableStateFlow(false)

    /**
     * Mosaico de miniaturas: N canales a la vista, uno por tile.
     *
     * Cada tile se reproduce unos segundos, se congela un frame y se sigue con
     * el siguiente. El decoder de hardware de la TV es UNICO: mantener cuatro
     * streams a la vez lleva al 86% de CPU y cuelga la app.
     *
     * En Android TV no hay PiP del sistema, asi que la vista de captura se hace
     * con un TextureView propio.
     */
    val mosaic = ThumbnailManager(context)

    /** El foco cambio: el loop de surf tiene que capturar ese tile ya. */
    private var mosaicFocusDirty = false

    /** Reintentos de la captura por errores transitorios (tope de la sesion). */
    private var mosaicRetries = 0

    /** Trabajo de barrido: va tomando un frame de cada tile, uno por uno. */
    private var mosaicSweepJob: Job? = null

    /** Captura puntual del tile enfocado (corta el barrido). */
    private var mosaicCaptureJob: Job? = null

    /** A donde vuelve BACK cuando el canal se eligio desde una lista. */
    private var playerBackScreen: Screen = Screen.PLAYER

    val playbackState = MutableStateFlow("idle")
    val playbackError = MutableStateFlow("")
    val videoWidth = MutableStateFlow(0)
    val videoHeight = MutableStateFlow(0)

    private var bannerJob: Job? = null
    private var numberJob: Job? = null
    private var autoSkipJob: Job? = null
    private var consecutiveFailures = 0

    /** Reintentos por feed para errores transitorios de live. */
    private val retryCount = mutableMapOf<String, Int>()

    /**
     * Feeds confirmados como ESTABLES: siguen reproduciendo despues de
     * [STABLE_PLAYING_MS] sin caerse.
     *
     * Un feed que levanta y se cae al segundo dispara "playing" un instante antes
     * de morir. Si al ver ese "playing" limpiaramos el contador de reintentos, el
     * feed fallaria para siempre en bucle. Por eso el contador de reintentos solo
     * se limpia cuando el canal aguanta lo suficiente reproduciendo de verdad.
     */
    private val stableVariants = mutableSetOf<String>()

    /** Cuando se vio "playing" por ultima vez (para medir que el canal se mantiene). */
    private var playingSince = 0L

    /** Job que verifica que el feed aguante lo suficiente. */
    private var stableJob: Job? = null

    /** Reintentos transitorios totales: corte de seguridad global. */
    private var transientRetries = 0

    /** Arranque del controller: sirve para medir fases (KzStartup). */
    private val bootAt = android.os.SystemClock.elapsedRealtime()

    init {
        // El player avisa por aca: los errores se muestran en pantalla y se salta al
        // canal siguiente, para no dejar al usuario frente a una pantalla negra.
        player.onStateChanged = { state ->
            if (state == "playing") {
                val id = currentVariant.value?.channelId
                playbackError.value = ""
                // Un "playing" instantaneo NO cuenta como canal sano: hay feeds que
                // levantan y se caen al segundo. El feed se confirma solo si aguanta
                // STABLE_PLAYING_MS reproduciendo (se verifica con un job).
                if (id != null) scheduleStableCheck(id)
            }
            playbackState.value = state
        }
        player.onError = { message -> onPlaybackError(message) }
        player.onVideoSize = { w, h ->
            videoWidth.value = w
            videoHeight.value = h
            // La resolución REAL del stream: el nombre del playlist suele mentir
            // (dice 1080p y entrega 480p).
            val id = currentVariant.value?.channelId
            if (id != null && w > 0) {
                val value = w.toString() + "x" + h
                if (store.state.value.measuredResolution[id] != value) {
                    store.update { it.copy(measuredResolution = it.measuredResolution + (id to value)) }
                }
            }
        }
    }

    /**
     * Programa la verificacion de estabilidad del feed que esta reproduciendo.
     *
     * No sirve con un evento "playing": un feed que levanta y se cae al segundo
     * dispara "playing" un instante antes de morir. Hay que esperar a que el
     * MISMO feed siga en reproduccion dentro de [STABLE_PLAYING_MS].
     */
    private fun scheduleStableCheck(id: String) {
        if (playingSince == 0L) playingSince = android.os.SystemClock.elapsedRealtime()
        stableJob?.cancel()
        stableJob = scope.launch {
            delay(STABLE_PLAYING_MS)
            // Solo se confirma si sigue siendo el mismo feed y sigue reproduciendo.
            if (currentVariant.value?.channelId != id) return@launch
            if (playbackState.value != "playing") return@launch
            confirmStablePlaying(id)
        }
    }

    /**
     * El feed lleva [STABLE_PLAYING_MS] reproduciendo: ahora si es un canal sano.
     *
     * Ahi se limpian los contadores de reintento y se resetea el de fallas seguidas.
     */
    private fun confirmStablePlaying(id: String) {
        val firstTime = stableVariants.add(id)
        consecutiveFailures = 0
        transientRetries = 0
        retryCount.remove(id)
        markVariant(id, ChannelVariant.STATUS_OK)
        if (firstTime) android.util.Log.i("KzIPTV", "canal estable: " + id)
    }

    /**
     * Un feed fallo. Primero se prueban las OTRAS VARIANTES de la misma señal
     * (mismo canal, otro proveedor/calidad); si no queda ninguna, se zaponea.
     */
    private fun onPlaybackError(message: String) {
        playbackState.value = "error"
        playbackError.value = message
        val group = currentGroup.value
        val variant = currentVariant.value
        // El feed se cayo: deja de ser estable hasta que vuelva a aguantar.
        stableJob?.cancel()
        if (variant != null) {
            stableVariants.remove(variant.channelId)
            playingSince = 0L
        }

        // Algunos feeds en vivo rotan la playlist antes de que el player la lea
        // (BEHIND_LIVE_WINDOW / IO_READ_POSITION_OUT_OF_RANGE): no son feeds muertos,
        // son reintentos. Re-preparar pide la playlist nueva y funciona.
        if (variant != null && group != null) {
            val attempts = retryCount[variant.channelId] ?: 0
            // Tope global: ningun feed puede reintentar para siempre.
            if (RetryPolicy.shouldRetry(
                    message, attempts, MAX_VARIANT_RETRIES,
                    transientRetries, MAX_TRANSIENT_RETRIES
                )
            ) {
                transientRetries++
                retryCount[variant.channelId] = attempts + 1
                android.util.Log.w(
                    "KzIPTV",
                    "reintento " + attempts + " de " + variant.name + " (error transitorio: " + message + ")"
                )
                status.value = "Reintentando ${group.name}\u2026"
                autoSkipJob?.cancel()
                autoSkipJob = scope.launch {
                    delay(1500)
                    play(group, variant, showBanner = false, switchToPlayer = false)
                }
                return
            }
        }

        // Aca el feed quedo muerto de verdad: recien ahora cuenta para el corte.
        consecutiveFailures++
        if (variant != null) markVariant(variant.channelId, ChannelVariant.STATUS_DEAD)
        val name = group?.name ?: "ese canal"
        android.util.Log.e(
            "KzIPTV",
            "error de reproduccion: " + name + " / " + (variant?.name ?: "-") + " -> " + message
        )
        status.value = "\u26A0 $name no se reproduce ($message)"
        autoSkipJob?.cancel()
        if (consecutiveFailures > MAX_AUTO_SKIP) {
            status.value = "\u26A0 Ningun canal responde. Revisa la conexion o la playlist."
            return
        }
        val alternative = group?.variants?.firstOrNull {
            it.channelId != variant?.channelId && variantStatus(it.channelId) != ChannelVariant.STATUS_DEAD
        }
        autoSkipJob = scope.launch {
            if (alternative != null && group != null) {
                // misma señal, otro feed: entra mas rapido que saltar de canal
                delay(700)
                play(group, alternative, showBanner = false, switchToPlayer = false)
            } else {
                delay(1800)
                zap(1, switchToPlayer = false)
            }
        }
    }

    companion object {
        const val MAX_AUTO_SKIP = 6
        /** Reintentos del MISMO feed ante errores transitorios de live. */
        const val MAX_VARIANT_RETRIES = 2
        /** Cuanto debe durar el playback para dar el feed por estable. */
        const val STABLE_PLAYING_MS = 8_000L
        /** Tope global de reintentos transitorios antes de cortar. */
        const val MAX_TRANSIENT_RETRIES = 6
        /** Intentos por variante en la captura del mosaico. */
        const val MAX_VARIANT_ATTEMPTS = 2
        /** Canales por pagina del mosaico (grilla de 4 x 4 = 16). */
        const val MOSAIC_TILES = 16
        /** Cada cuanto se refresca el tile ENFOCADO (el que mira el usuario). */
        const val SURF_FOCUS_MS = SurfPlanner.FOCUS_MS
        /** Cada cuanto se refresca la pagina completa (rolling). */
        const val SURF_PAGE_MS = SurfPlanner.PAGE_MS
        /** Resolution del loop de surf (reactividad ante cambios de foco). */
        const val SURF_TICK_MS = 250L
        /** Columnas del mosaico. */
        const val MOSAIC_COLUMNS = 4
        /** Un feed marcado como muerto se reintenta pasado este tiempo. */
        const val DEAD_TTL_MS = 6 * 60 * 60 * 1000L
        const val BUNDLED_ID = "bundled-ar"
        const val BUNDLED_URL = "https://iptv-org.github.io/iptv/countries/ar.m3u"
    }

    fun bootstrap() {
        scope.launch {
            val persisted = store.state.value.sources
            val sources = persisted.ifEmpty {
                listOf(PersistedSource(id = BUNDLED_ID, name = "iptv-org \u00b7 Argentina", url = BUNDLED_URL, bundled = true))
            }
            val all = mutableListOf<Channel>()
            sources.forEach { source ->
                val content = try {
                    if (source.bundled) repo.bundledAr() else repo.fetch(source.url)
                } catch (e: Exception) {
                    status.value = "No pude leer ${source.name}: ${e.message}"
                    null
                } ?: return@forEach
                all += withContext(Dispatchers.IO) { M3uParser.parse(content, source.id) }
            }
            applyChannels(all)
            store.update { it.copy(sources = sources, pairingCode = pairingCode.value) }
            android.util.Log.i(
                "KzStartup",
                "playlist lista en " + (android.os.SystemClock.elapsedRealtime() - bootAt) +
                    " ms (" + all.size + " feeds / " + catalog.value.groups.size + " canales)"
            )
            loadBundledEpg()
            if (all.isEmpty()) {
                screen.value = Screen.SETUP
            } else {
                // Retoma el ultimo canal que se estaba viendo; si no, el primero disponible.
                val list = catalog.value.groups
                val lastGroupId = store.state.value.lastGroupId
                val target = list.firstOrNull { it.id == lastGroupId && it.variants.any { v -> v.playable } }
                    ?: list.firstOrNull { it.variants.any { v -> v.playable } }
                if (target != null) play(target, showBanner = false)
            }
        }
    }

    /**
     * Construye el catalogo. El agrupado (regex sobre 200+ nombres) tarda ~1,5 s en la TV:
     * va en IO para no congelar la UI en el arranque.
     */
    private suspend fun applyChannels(channels: List<Channel>) {
        catalog.value = withContext(Dispatchers.IO) { ChannelCatalog(channels) }
        favorites.value = store.state.value.favorites.toSet()
        status.value = "${catalog.value.groups.size} canales" +
            (if (catalog.value.variantCount > catalog.value.groups.size) " (${catalog.value.variantCount} feeds)" else "") +
            " cargados"
    }

    private fun loadBundledEpg() {
        scope.launch {
            // Todo el parseo del XMLTV (~2 MB) va en IO: en el hilo principal provocaba ANR.
            val programs = withContext(Dispatchers.IO) {
                val text = readBundledEpgText() ?: return@withContext emptyList()
                EpgParser.parse(text)
            }
            if (programs.isNotEmpty()) {
                epg.value = EpgIndex(programs)
                status.value = "${catalog.value.groups.size} canales \u00b7 ${programs.size} programas EPG"
            }
            android.util.Log.i("KzStartup", "EPG listo en " + (android.os.SystemClock.elapsedRealtime() - bootAt) + " ms")
        }
    }

    /**
     * El EPG puede llegar como .gz (build local) o ya descomprimido por AGP al empaquetar:
     * dentro del APK el asset "epg/ar.xml.gz" queda como "assets/epg/ar.xml".
     * Se prueban las dos variantes y se detecta el gzip por sus magic bytes.
     */
    private fun readBundledEpgText(): String? {
        for (name in listOf("epg/ar.xml", "epg/ar.xml.gz")) {
            try {
                context.assets.open(name).use { input ->
                    val bytes = input.readBytes()
                    val gzip = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
                    val text = if (gzip) {
                        GZIPInputStream(bytes.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
                    } else {
                        bytes.toString(Charsets.UTF_8)
                    }
                    if (text.contains("<programme")) return text
                }
            } catch (e: Exception) {
                // siguiente candidato
            }
        }
        return null
    }

    /**
     * Ejecuta en el hilo principal. El ExoPlayer de Media3 exige que se lo use desde
     * el hilo que lo creo, y el server Ktor atiende los comandos en hilos de Netty:
     * sin este salto, /api/command devolvia 500 (IllegalStateException del player).
     */
    fun onMain(block: () -> Unit) {
        scope.launch { block() }
    }

    /** Reproduce un canal logico con la variante elegida (o la mejor disponible). */
    fun play(
        group: ChannelGroup,
        variant: ChannelVariant? = null,
        showBanner: Boolean = true,
        switchToPlayer: Boolean = true
    ) {
        val chosen = variant ?: chooseVariant(group)
        if (!chosen.playable) {
            status.value = "\u00ab${group.name}\u00bb no es reproducible en Android TV"
            return
        }
        player.play(chosen.toChannel())
        currentGroup.value = group
        currentVariant.value = chosen
        // Feed nuevo: arranca de cero la medicion de estabilidad.
        stableJob?.cancel()
        playingSince = 0L
        // Un reintento o fallback NO debe sacar al usuario de la grilla o del picker.
        if (switchToPlayer) screen.value = Screen.PLAYER
        playbackState.value = "buffering"
        playbackError.value = ""
        store.update { it.copy(lastGroupId = group.id, lastChannelId = chosen.channelId) }
        if (showBanner) showBanner()
    }

    /** Elige la variante: la preferencia del usuario si sigue viva, si no la de mayor calidad. */
    fun chooseVariant(group: ChannelGroup): ChannelVariant {
        val preference = store.state.value.qualityPreference[group.id]
        val alive = group.variants.filter { variantStatus(it.channelId) != ChannelVariant.STATUS_DEAD }
        val pool = if (alive.isNotEmpty()) alive else group.variants
        return pool.firstOrNull { it.channelId == preference }
            ?: pool.maxByOrNull { ChannelGrouper.qualityRank(it.quality) }
            ?: group.variants.first()
    }

    fun zap(delta: Int, switchToPlayer: Boolean = true) {
        val list = activeList()
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == currentGroup.value?.id }
        val nextIndex = if (index < 0) 0 else ((index + delta) % list.size + list.size) % list.size
        play(list[nextIndex], switchToPlayer = switchToPlayer)
    }

    fun activeList(): List<ChannelGroup> {
        val base = if (zapFavoritesOnly.value) favoritesList() else catalog.value.groups.map { enrich(it) }
        val selected = category.value
        return if (selected != null) base.filter { selected in it.groups } else base
    }

    fun favoritesList(): List<ChannelGroup> =
        favorites.value.mapNotNull { id ->
            catalog.value.groupById[id] ?: catalog.value.groupOf(id)
        }.distinctBy { it.id }.sortedBy { it.number }.map { enrich(it) }

    /** Enriquece el grupo con la salud real de cada feed y la resolución medida. */
    private fun enrich(group: ChannelGroup): ChannelGroup {
        val measured = store.state.value.measuredResolution
        return group.copy(
            variants = group.variants.map { v ->
                v.copy(
                    status = variantStatus(v.channelId),
                    measured = measured[v.channelId].orEmpty()
                )
            }
        )
    }

    fun isFavorite(group: ChannelGroup): Boolean =
        favorites.value.contains(group.id) || group.variants.any { favorites.value.contains(it.channelId) }

    fun toggleFavorite(groupId: String? = null) {
        val id = groupId ?: focusedGroupId.value ?: currentGroup.value?.id ?: return
        val group = catalog.value.groupById[id] ?: return
        val wasFavorite = isFavorite(group)
        val updated = favorites.value.toMutableSet()
        if (wasFavorite) {
            updated.remove(group.id)
            group.variants.forEach { updated.remove(it.channelId) }
        } else {
            updated.add(group.id)
        }
        favorites.value = updated
        store.update { it.copy(favorites = updated.toList()) }
        status.value = if (!wasFavorite) "\u2605 ${group.name} agregado a favoritos" else "${group.name} quitado de favoritos"
    }

    fun toggleZapMode() {
        zapFavoritesOnly.value = !zapFavoritesOnly.value
        store.update { it.copy(zapFavoritesOnly = zapFavoritesOnly.value) }
        status.value = if (zapFavoritesOnly.value) "Zap: solo FAVORITOS" else "Zap: todos los canales"
    }

    fun openGrid() {
        // El foco arranca en el canal que estas mirando: sin esto el OK no hace nada.
        val target = currentGroup.value?.id ?: catalog.value.groups.firstOrNull()?.id
        if (target != null) focusedGroupId.value = target
        // Al reabrir la grilla el foco vuelve a estar en las tarjetas, no en un chip.
        focusedCategory.value = null
        screen.value = Screen.GRID
    }

    fun back() {
        focusedCategory.value = null
        when (screen.value) {
            Screen.SEARCH -> screen.value = Screen.GRID
            Screen.MOSAIC -> closeMosaic()
            // Si el canal se eligio desde una lista (grilla, busqueda o mosaico),
            // BACK devuelve a esa lista. Si se estaba zapeando, vuelve al video.
            Screen.PLAYER -> screen.value = playerBackScreen
            else -> screen.value = Screen.PLAYER
        }
    }

    /**
     * Abre el mosaico: una grilla de canales con un frame congelado de cada uno.
     *
     * Cada tile se captura reproduciendo el canal unos segundos: el decoder de
     * hardware de la TV es UNICO, asi que se van haciendo de a uno y el grid se
     * llena solo. Los tiles son los canales que siguen al que estas mirando
     * (y los favoritos van primero).
     */
    fun openMosaic() {
        val list = mosaicSource()
        if (list.isEmpty()) {
            status.value = "No hay canales para comparar"
            return
        }
        mosaicChannels.value = list
        mosaicFocus.value = 0
        mosaicTileErrors.value = emptyMap()
        mosaicThumbs.value = emptyMap()
        mosaicPlaying.value = false
        mosaicLiveIndex.value = -1
        mosaicRetries = 0
        mosaicFocusDirty = false
        mosaicCaptureJob?.cancel()
        mosaicCaptureJob = null
        screen.value = Screen.MOSAIC
        // El player principal suelta el decoder: en la TV hay UNO por hardware
        // y lo necesita la captura.
        player.suspendVideo()
        // El player de captura arranca limpio (puede quedar una captura a medias
        // de la apertura anterior).
        mosaic.stop()
        // El grid se va llenando solo, canal por canal.
        startMosaicSweep()
    }

    /**
     * Cierra el mosaico.
     *
     * OJO: el player de captura NO se libera, solo se detiene. Si se liberara,
     * la segunda vez que se abre el mosaico en la misma sesion el player queda
     * muerto ("Handler on a dead thread") y no se captura ningun canal.
     */
    fun closeMosaic() {
        mosaicSweepJob?.cancel()
        mosaicSweepJob = null
        mosaicCaptureJob?.cancel()
        mosaicCaptureJob = null
        mosaic.stop()
        mosaicFocusDirty = false
        mosaicPlaying.value = false
        mosaicLiveIndex.value = -1
        mosaicChannels.value = emptyList()
        mosaicTileErrors.value = emptyMap()
        mosaicThumbs.value = emptyMap()
        mosaicFocus.value = 0
        screen.value = Screen.GRID
    }

    /**
     * Los canales a comparar.
     *
     * Empieza por los favoritos (son los que el usuario ya eligio) y completa con
     * los canales que siguen al que esta mirando, hasta llenar los tiles.
     */
    private fun mosaicSource(): List<ChannelGroup> {
        val active = activeList()
        if (active.isEmpty()) return emptyList()
        val current = currentGroup.value
        val start = active.indexOfFirst { it.id == current?.id }
        // Rotacion: el mosaico arranca por el canal que estas mirando.
        val rotated = if (start >= 0) {
            active.subList(start, active.size) + active.subList(0, start)
        } else {
            active
        }
        val favs = favoritesList()
        val picked = LinkedHashMap<String, ChannelGroup>()
        // Primero los favoritos, despues los que siguen al actual. Pero solo
        // los que tienen AL MENOS un feed vivo: un mosaico lleno de canales
        // caidos no sirve para comparar nada.
        favs.filter { hasLiveFeed(it) }.forEach { picked.putIfAbsent(it.id, it) }
        rotated.filter { hasLiveFeed(it) }.forEach { picked.putIfAbsent(it.id, it) }
        // Si no alcanzan, se completa con los que quedan (se veran sin senal).
        favs.forEach { picked.putIfAbsent(it.id, it) }
        rotated.forEach { picked.putIfAbsent(it.id, it) }
        return picked.values.take(MOSAIC_TILES)
    }

    /** El canal tiene algun feed que no sabemos que este muerto? */
    private fun hasLiveFeed(group: ChannelGroup): Boolean =
        group.variants.any {
            it.playable && variantStatus(it.channelId) != ChannelVariant.STATUS_DEAD
        }

    /**
     * Captura el frame de un canal y lo deja congelado en su tile.
     *
     * Es una operacion en cadena: reproduce el canal, espera a que llegue el
     * primer frame, lo congela y libera el decoder para el siguiente canal.
     *
     * Prioridad de variantes: primero las que ya se saben sanas (estado "ok"),
     * despues las desconocidas. Las marcadas como muertas no se prueban: se sabe
     * que no funcionan y el decoder de la TV es el recurso escaso.
     */
    private suspend fun captureTile(index: Int) {
        val group = mosaicChannels.value.getOrNull(index) ?: return
        val variants = group.variants
            .filter { it.playable }
            .filter { variantStatus(it.channelId) != ChannelVariant.STATUS_DEAD }
            .sortedWith(
                compareBy<ChannelVariant> {
                    if (variantStatus(it.channelId) == ChannelVariant.STATUS_OK) 0 else 1
                }.thenBy { ChannelGrouper.qualityRank(it.quality) }
            )
        if (variants.isEmpty()) {
            // Todos los feeds del canal estan caidos: no se gasta el decoder.
            // El tile conserva la miniatura anterior (si tenia).
            return
        }

        var lastError = "sin senal"
        for (variant in variants) {
            var attempt = 0
            while (true) {
                // La UI crea el TextureView de este tile (y solo de este).
                mosaicPlaying.value = false
                mosaicLiveIndex.value = index
                // Se espera a que la vista exista y a que el decoder entregue un frame.
                delay(350)
                val result = mosaic.capture(variant.toChannel())
                if (result.played) {
                    // El feed arranco: queda marcado como bueno para las proximas.
                    markVariant(variant.channelId, ChannelVariant.STATUS_OK)
                    mosaicPlaying.value = true
                }
                if (result.bitmap != null) {
                    mosaicThumbs.value = mosaicThumbs.value + (index to result.bitmap)
                    mosaicTileErrors.value = mosaicTileErrors.value - index
                    mosaicLiveIndex.value = -1
                    mosaicPlaying.value = false
                    // El presupuesto de reintentos se repone: algo anda bien.
                    mosaicRetries = 0
                    return
                }
                lastError = result.error ?: "sin senal"
                // Un error transitorio (datos viejos, timeout) se reintenta: el
                // feed puede volver. Uno permanente (403/404) se marca como muerto
                // y se pasa a la siguiente variante del canal.
                if (RetryPolicy.isTransient(lastError) &&
                    attempt < MAX_VARIANT_ATTEMPTS &&
                    mosaicRetries < MAX_TRANSIENT_RETRIES
                ) {
                    attempt++
                    mosaicRetries++
                    // El feed suele estar en el borde "live": reconectar
                    // demasiado rapido vuelve a fallar.
                    delay(1_500)
                    continue
                }
                break
            }
            // Un feed permanentemente roto no se vuelve a elegir como preview.
            if (!RetryPolicy.isTransient(lastError)) {
                markVariant(variant.channelId, ChannelVariant.STATUS_DEAD)
            }
        }

        // No hay mas variantes: el tile conserva la miniatura anterior (si
        // habia) y queda marcado como caido.
        mosaicLiveIndex.value = -1
        mosaicPlaying.value = false
        mosaicTileErrors.value = mosaicTileErrors.value + (index to lastError)
    }

    /**
     * El "surf": el grid se llena y despues se refresca solo.
     *
     * - El tile ENFOCADO (el que esta mirando el usuario) se vuelve a capturar
     *   cada SURF_FOCUS_MS: el canal se despierta unos segundos y vuelve a
     *   congelarse.
     * - El resto de la pagina se refresca en rolling: una vuelta completa cada
     *   SURF_PAGE_MS.
     *
     * En la TV hay UN solo decoder de video: las capturas son SIEMPRE de a una.
     * Si el usuario mueve el foco, la captura en curso se corta y se hace la del
     * canal enfocado.
     */
    private fun startMosaicSweep() {
        mosaicSweepJob?.cancel()
        mosaicCaptureJob?.cancel()
        mosaicSweepJob = scope.launch {
            val total = mosaicChannels.value.size
            if (total == 0) return@launch
            // El ritmo lo decide SurfPlanner (logica pura, testeada).
            var plan = SurfPlanner.Plan(-1, 0L, 0L, 0, false)

            while (isActive && screen.value == Screen.MOSAIC) {
                val focus = mosaicFocus.value.coerceIn(0, total - 1)
                plan = SurfPlanner.plan(
                    total = total,
                    focus = focus,
                    now = System.currentTimeMillis(),
                    focusDirty = mosaicFocusDirty,
                    lastFocusAt = plan.lastFocusAt,
                    lastPageAt = plan.lastPageAt,
                    nextIndex = plan.nextIndex,
                    pagePending = plan.pagePending
                )
                val target = plan.target
                if (target < 0) {
                    // Nada vencido todavia: se espera un toque.
                    delay(SURF_TICK_MS)
                    continue
                }
                if (target == focus) mosaicFocusDirty = false

                val job = scope.launch {
                    try {
                        captureTile(target)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e(
                            "KzIPTV",
                            "fallo la captura del tile " + target,
                            e
                        )
                    }
                }
                mosaicCaptureJob = job
                job.join()
                if (target == focus) {
                    plan = plan.copy(lastFocusAt = System.currentTimeMillis())
                }
            }
        }
    }

    /** Mueve el foco entre los tiles del mosaico (grilla de 2 columnas). */
    private fun moveMosaicFocus(code: Int) {
        val n = mosaicChannels.value.size
        if (n == 0) return
        val columns = MOSAIC_COLUMNS
        var index = mosaicFocus.value
        when (code) {
            RemoteKeys.DPAD_LEFT -> if (index % columns > 0) index--
            RemoteKeys.DPAD_RIGHT -> if (index % columns < columns - 1 && index + 1 < n) index++
            RemoteKeys.DPAD_UP -> if (index >= columns) index -= columns
            RemoteKeys.DPAD_DOWN -> if (index + columns < n) index += columns
        }
        index = index.coerceIn(0, n - 1)
        if (index != mosaicFocus.value) {
            mosaicFocus.value = index
            // La captura en curso se corta: ahora interesa el canal enfocado.
            mosaicCaptureJob?.cancel()
            mosaicCaptureJob = null
            // El loop de surf captura este tile apenas puede (no hay que esperar
            // a que termine la vuelta de la pagina).
            mosaicFocusDirty = true
        }
    }
    /**
     * Libera los recursos del controller (cuando la activity se destruye).
     *
     * El player de captura se suelta recien aca: durante la sesion se reutiliza
     * porque el mosaico se abre y se cierra varias veces.
     */
    fun release() {
        mosaicSweepJob?.cancel()
        mosaicCaptureJob?.cancel()
        mosaic.release()
    }

    fun openPairing() {
        screen.value = Screen.SETUP
    }

    /** Abre el selector de calidad de un canal (o del canal enfocado). */
    fun openQuality(group: ChannelGroup? = null) {
        val target = group
            ?: focusedGroupId.value?.let { catalog.value.groupById[it] }
            ?: currentGroup.value
        if (target == null) {
            status.value = "No hay canal seleccionado"
            return
        }
        qualityTarget.value = enrich(target)
        val active = currentVariant.value?.channelId
        focusedVariantId.value = if (active != null && target.variants.any { it.channelId == active }) {
            active
        } else {
            target.variants.firstOrNull()?.channelId
        }
        screen.value = Screen.QUALITY
    }

    /** Reproduce una variante puntual (desde el companion) y la deja como preferencia. */
    fun playVariant(groupId: String, variantId: String) {
        val group = catalog.value.resolve(groupId) ?: return
        val variant = group.variants.firstOrNull { it.channelId == variantId } ?: return
        store.update { it.copy(qualityPreference = it.qualityPreference + (group.id to variantId)) }
        markVariant(variantId, ChannelVariant.STATUS_UNKNOWN)
        screen.value = Screen.PLAYER
        play(group, variant)
    }

    /** Elige una variante: se reproduce y queda como preferencia para ese canal. */
    fun selectVariant(variantId: String) {
        val target = qualityTarget.value ?: currentGroup.value ?: return
        val variant = target.variants.firstOrNull { it.channelId == variantId } ?: return
        store.update { it.copy(qualityPreference = it.qualityPreference + (target.id to variantId)) }
        markVariant(variantId, ChannelVariant.STATUS_UNKNOWN)
        screen.value = Screen.PLAYER
        play(target, variant)
    }

    fun onOk(longPress: Boolean) {
        if (longPress) {
            when (screen.value) {
                Screen.PLAYER, Screen.GRID -> openQuality()
                else -> toggleFavorite()
            }
            return
        }
        when (screen.value) {
            Screen.GRID -> {
                val chip = focusedCategory.value
                if (chip != null) {
                    setCategory(if (chip.isEmpty()) null else chip)
                    focusGrid()
                } else {
                    focusedGroupId.value?.let { id ->
                        catalog.value.groupById[id]?.let {
                            playerBackScreen = Screen.GRID
                            play(it)
                        }
                    }
                }
            }
            Screen.PLAYER -> openGrid()
            Screen.QUALITY -> focusedVariantId.value?.let { selectVariant(it) }
            Screen.SEARCH ->
                if (searchFieldFocused.value) {
                    // El IME del sistema tiene el foco: no hacemos nada aca.
                } else if (focusedSearchId.value == null) {
                    // Foco en el campo: abrimos el teclado DEL SISTEMA.
                    searchFieldRequest.value = searchFieldRequest.value + 1
                } else {
                    playFocusedSearchResult()
                }
            Screen.MOSAIC -> {
                val group = mosaicChannels.value.getOrNull(mosaicFocus.value)
                if (group != null) {
                    closeMosaic()
                    // BACK desde el canal vuelve a la grilla.
                    playerBackScreen = Screen.GRID
                    play(group)
                }
            }
            Screen.SETUP -> Unit
        }
    }

    fun setCategory(category: String?) {
        this.category.value = category
        // El foco tiene que seguir apuntando a algo visible en la grilla.
        val list = catalog.value.inCategory(category)
        val currentId = focusedGroupId.value
        if (currentId == null || list.none { it.id == currentId }) {
            focusedGroupId.value = list.firstOrNull()?.id
        }
    }

    fun setQuery(query: String) {
        this.query.value = query
        if (screen.value == Screen.SEARCH) applySearch(query)
    }

    // ---------------------------------------------------------------------
    // Entrada directa por numero (teclas 0-9)
    // ---------------------------------------------------------------------

    /**
     * Escribe un digito del numero de canal. El buffer se confirma solo a los
     * 2,5 s, o al instante con OK.
     */
    fun onDigit(digit: Int) {
        val buffer = NumberEntry.append(numberBuffer.value, digit)
        numberBuffer.value = buffer
        numberJob?.cancel()
        numberJob = scope.launch {
            delay(NumberEntry.TIMEOUT_MS)
            confirmNumber()
        }
        status.value = "Canal $buffer"
    }

    /** Confirma el numero escrito: busca el canal y lo reproduce. */
    fun confirmNumber() {
        numberJob?.cancel()
        numberJob = null
        val buffer = numberBuffer.value
        if (buffer.isBlank()) return
        numberBuffer.value = ""
        val target = NumberEntry.resolve(catalog.value, buffer)
        if (target == null) {
            status.value = "No hay un canal $buffer"
            return
        }
        play(target)
    }

    /** Descarta el numero escrito (BACK). */
    fun cancelNumber() {
        numberJob?.cancel()
        numberJob = null
        numberBuffer.value = ""
    }

    // ---------------------------------------------------------------------
    // Busqueda por texto
    // ---------------------------------------------------------------------

    /** Abre la pantalla de busqueda (con el texto actual, si habia). */
    fun openSearch() {
        screen.value = Screen.SEARCH
        // El foco arranca en el campo de texto (se ve el cursor).
        focusedSearchId.value = null
        searchFieldFocused.value = false
        applySearch(query.value)
    }

    /**
     * Recalcula los resultados de la busqueda y ancla el foco.
     *
     * Si el foco estaba en el campo de texto se mantiene ahi (null); si estaba
     * en un resultado y ese sigue existiendo, se mantiene.
     */
    fun applySearch(text: String) {
        val results = NumberEntry.search(catalog.value, text)
        searchResults.value = results
        val current = focusedSearchId.value
        focusedSearchId.value = when {
            current == null -> null
            results.any { it.id == current } -> current
            else -> results.firstOrNull()?.id
        }
    }

    /**
     * Mueve el foco en la pantalla de busqueda.
     *
     * El campo de texto y la lista de resultados se recorren con arriba/abajo.
     * Cuando el IME del sistema esta abierto, el sistema maneja las teclas.
     */
    fun moveSearchFocus(code: Int) {
        if (searchFieldFocused.value) return
        val list = searchResults.value
        val onField = focusedSearchId.value == null
        when (code) {
            RemoteKeys.DPAD_DOWN -> {
                if (onField) {
                    focusedSearchId.value = list.firstOrNull()?.id
                } else {
                    val index = list.indexOfFirst { it.id == focusedSearchId.value }
                    if (index >= 0 && index + 1 < list.size) {
                        focusedSearchId.value = list[index + 1].id
                    }
                }
            }
            RemoteKeys.DPAD_UP -> {
                if (onField) {
                    // Ya estamos arriba del todo.
                } else {
                    val index = list.indexOfFirst { it.id == focusedSearchId.value }
                    if (index <= 0) {
                        focusedSearchId.value = null
                    } else {
                        focusedSearchId.value = list[index - 1].id
                    }
                }
            }
        }
    }

    /** El usuario toco el campo de texto: abre el IME del sistema. */
    fun requestSearchFieldFocus() {
        searchFieldRequest.value = searchFieldRequest.value + 1
    }

    /** El campo nativo gano o perdio el foco de plataforma. */
    fun setSearchFieldFocused(focused: Boolean) {
        searchFieldFocused.value = focused
    }

    /** Limpia la busqueda. */
    fun clearSearch() {
        setQuery("")
    }

    /**
     * Suelta el foco del campo de texto (para cerrar el IME del sistema).
     * La UI observa [searchFieldFocused] y hace clearFocus() en el EditText.
     */
    fun clearSearchFieldFocus() {
        searchFieldFocused.value = false
    }

    /** Reproduce el canal enfocado en la busqueda. */
    fun playFocusedSearchResult() {
        val id = focusedSearchId.value ?: return
        val target = catalog.value.groupById[id] ?: return
        // BACK desde el canal vuelve a la busqueda.
        playerBackScreen = Screen.SEARCH
        play(target)
    }

    fun setFocused(id: String?) {
        focusedGroupId.value = id
        // Si el foco vuelve a una tarjeta, el chip deja de estar enfocado.
        focusedCategory.value = null
    }

    /** Categoria enfocada en la fila de chips ("" = Todos, null = ninguna). */
    fun setFocusedCategory(category: String?) {
        focusedCategory.value = category
    }

    /** Devuelve el foco a la grilla de canales (tras elegir una categoria). */
    fun focusGrid() {
        focusedCategory.value = null
    }

    fun setFocusedVariant(id: String?) {
        focusedVariantId.value = id
    }

    /** Columnas de la grilla: lo calcula la UI segun el ancho de pantalla. */
    private val gridColumns = MutableStateFlow(1)

    fun setGridColumns(columns: Int) {
        if (columns > 0) gridColumns.value = columns
    }

    /**
     * D-pad con navegacion determinista: el controller es el dueno del foco y la UI
     * solo dibuja el borde y hace scroll. Asi el control remoto y el companion se
     * comportan EXACTAMENTE igual (el foco interno de Compose no enganchaba en la TV).
     */
    fun onDpad(code: Int) {
        when (screen.value) {
            Screen.GRID -> moveGridFocus(code)
            Screen.QUALITY -> moveQualityFocus(code)
            Screen.SEARCH -> moveSearchFocus(code)
            Screen.MOSAIC -> moveMosaicFocus(code)
            else -> Unit
        }
    }

    private fun moveGridFocus(code: Int) {
        val chip = focusedCategory.value
        if (chip != null) {
            // El foco esta en la fila de categorias.
            val chips = listOf("") + catalog.value.categories
            val index = chips.indexOf(chip).let { if (it < 0) 0 else it }
            val move = GridNavigator.moveChip(index, chips.size, code)
            if (move.toGrid) {
                focusedCategory.value = null
            } else {
                focusedCategory.value = chips[move.index]
            }
            android.util.Log.d(
                "KzNav",
                "chips code=$code -> '${focusedCategory.value ?: "grid"}'"
            )
            return
        }
        val list = catalog.value.inCategory(category.value)
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == focusedGroupId.value }.let { if (it < 0) 0 else it }
        val columns = gridColumns.value.coerceAtLeast(1)
        val move = GridNavigator.move(index, list.size, columns, code)
        if (move.toChips) {
            // Primera fila: sube a la fila de categorias, arrancando por la categoria
            // que esta activa.
            focusedCategory.value = category.value ?: ""
            android.util.Log.d(
                "KzNav",
                "grid code=$code -> chips ('${focusedCategory.value ?: ""}')"
            )
            return
        }
        focusedGroupId.value = list[move.index].id
        android.util.Log.d(
            "KzNav",
            "grid code=$code -> ${list[move.index].name} (idx ${move.index}, cols $columns)"
        )
    }

    private fun moveQualityFocus(code: Int) {
        val variants = qualityTarget.value?.variants ?: return
        if (variants.isEmpty()) return
        val index = variants.indexOfFirst { it.channelId == focusedVariantId.value }.let { if (it < 0) 0 else it }
        val next = GridNavigator.moveVertical(index, variants.size, code)
        focusedVariantId.value = variants[next].channelId
        android.util.Log.d(
            "KzNav",
            "quality code=$code -> ${variants[next].label} (idx $next)"
        )
    }

    suspend fun addSourceByUrl(url: String): Int = withContext(Dispatchers.IO) {
        val content = repo.fetch(url)
        val channels = M3uParser.parse(content, "url-${System.currentTimeMillis()}")
        if (channels.isEmpty()) error("La lista no tiene canales")
        val source = PersistedSource(
            id = "url-${System.currentTimeMillis()}",
            name = url.substringAfterLast('/').ifBlank { "Playlist" },
            url = url,
            channelCount = channels.size
        )
        store.update { it.copy(sources = it.sources + source) }
        applyChannels(catalog.value.all + channels)
        channels.size
    }

    suspend fun addSourceByContent(name: String, content: String): Int = withContext(Dispatchers.IO) {
        val channels = M3uParser.parse(content, "file-${System.currentTimeMillis()}")
        if (channels.isEmpty()) error("El archivo no tiene canales")
        val source = PersistedSource(
            id = "file-${System.currentTimeMillis()}",
            name = name.ifBlank { "Playlist" },
            bundled = false,
            channelCount = channels.size
        )
        store.update { it.copy(sources = it.sources + source) }
        applyChannels(catalog.value.all + channels)
        channels.size
    }

    fun snapshot(): StateDto {
        val now = System.currentTimeMillis()
        val activeVariantId = currentVariant.value?.channelId
        return StateDto(
            pairingCode = pairingCode.value,
            serverUrl = serverUrl.value,
            status = status.value,
            screen = screen.value.name,
            focusedId = focusedGroupId.value,
            focusedName = focusedGroupId.value?.let { catalog.value.groupById[it]?.name }.orEmpty(),
            focusedCategory = focusedCategory.value,
            currentId = currentGroup.value?.id,
            currentVariantId = activeVariantId,
            currentVariantLabel = currentVariant.value?.label.orEmpty(),
            zapFavoritesOnly = zapFavoritesOnly.value,
            playbackState = playbackState.value,
            playbackError = playbackError.value,
            videoWidth = videoWidth.value,
            videoHeight = videoHeight.value,
            numberBuffer = numberBuffer.value,
            searchQuery = query.value,
            mosaicErrors = mosaicTileErrors.value.mapKeys { it.key.toString() },
            focusedSearchId = focusedSearchId.value,
            focusedSearchName = focusedSearchId.value?.let { id ->
                catalog.value.groupById[id]?.name
            }.orEmpty(),
            channelCount = catalog.value.groups.size,
            groupCount = catalog.value.groups.size,
            variantCount = catalog.value.variantCount,
            categories = catalog.value.categories,
            channels = catalog.value.groups.map { group ->
                val (currentProgram, nextProgram) = epg.value.nowNextByTvgId(group.tvgId, now)
                ChannelDto(
                    id = group.id,
                    name = group.name,
                    logo = group.logo,
                    number = group.number,
                    groups = group.groups,
                    favorite = isFavorite(group),
                    kind = group.variants.firstOrNull()?.kind ?: Channel.KIND_HLS,
                    tvgId = group.tvgId,
                    now = currentProgram?.title.orEmpty(),
                    next = nextProgram?.title.orEmpty(),
                    activeVariantId = activeVariantId?.takeIf { id -> group.variants.any { it.channelId == id } }.orEmpty(),
                    variants = group.variants.map { variant ->
                        VariantDto(
                            id = variant.channelId,
                            name = variant.name,
                            label = variant.label,
                            quality = variant.quality,
                            region = variant.region,
                            host = variant.host,
                            status = variantStatus(variant.channelId),
                            measured = store.state.value.measuredResolution[variant.channelId].orEmpty(),
                            active = variant.channelId == activeVariantId
                        )
                    }
                )
            }
        )
    }

    /**
     * Estado de un feed. "dead" caduca a las pocas horas: un fallo de red puntual
     * no debe dejar un canal inutilizado para siempre.
     */
    private fun variantStatus(variantId: String): String {
        val raw = store.state.value.variantStatus[variantId] ?: return ChannelVariant.STATUS_UNKNOWN
        if (!raw.startsWith(ChannelVariant.STATUS_DEAD)) return raw
        // Los estados guardados antes del TTL no tienen timestamp: caducan de una.
        val stamp = raw.substringAfter('#', "").toLongOrNull() ?: return ChannelVariant.STATUS_UNKNOWN
        val age = System.currentTimeMillis() - stamp
        return if (age > DEAD_TTL_MS) ChannelVariant.STATUS_UNKNOWN else ChannelVariant.STATUS_DEAD
    }

    private fun markVariant(variantId: String?, status: String) {
        if (variantId.isNullOrBlank()) return
        val value = if (status == ChannelVariant.STATUS_DEAD) {
            status + "#" + System.currentTimeMillis()
        } else {
            status
        }
        if (store.state.value.variantStatus[variantId] == value) return
        store.update { it.copy(variantStatus = it.variantStatus + (variantId to value)) }
    }

    private fun showBanner() {
        bannerJob?.cancel()
        bannerVisible.value = true
        bannerJob = scope.launch {
            delay(4000)
            bannerVisible.value = false
        }
    }

    private fun generateCode(): String = (100000..999999).random().toString()

    fun setServerUrl(url: String) {
        serverUrl.value = url
    }
}
