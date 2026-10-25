package ar.kziptv.core.parse

import ar.kziptv.core.model.Channel
import java.security.MessageDigest

/**
 * Parser M3U/M3U8 pensado para las listas de iptv-org:
 *  - #EXTINF:-1 tvg-id="..." tvg-logo="..." group-title="A;B",Nombre visible
 *  - #EXTVLCOPT:http-user-agent=... / http-referrer=...
 *  - #KODIPROP:inputstream.adaptive.stream_headers=...
 *  - #EXTGRP:...
 * La URL es la linea siguiente al #EXTINF.
 */
object M3uParser {

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(content: String, sourceId: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        var attrs: Map<String, String> = emptyMap()
        var extinfLine: String = ""
        var headers = mutableMapOf<String, String>()
        var groups: List<String> = emptyList()

        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTINF") -> {
                    extinfLine = line
                    attrs = parseAttrs(line)
                    headers = mutableMapOf()
                    groups = emptyList()
                }

                line.startsWith("#EXTVLCOPT:") -> {
                    val body = line.removePrefix("#EXTVLCOPT:")
                    val idx = body.indexOf('=')
                    if (idx > 0) {
                        val key = body.substring(0, idx).trim()
                        val value = body.substring(idx + 1).trim()
                        when (key.lowercase()) {
                            "http-user-agent" -> headers["User-Agent"] = value
                            "http-referrer" -> headers["Referer"] = value
                            "http-origin" -> headers["Origin"] = value
                            else -> headers[key] = value
                        }
                    }
                }

                line.startsWith("#KODIPROP:") -> {
                    val body = line.removePrefix("#KODIPROP:")
                    if (body.startsWith("inputstream.adaptive.stream_headers=")) {
                        val rawHeaders = body.substringAfter('=').replace("\r\n", "\n")
                        rawHeaders.split("\n").forEach { h ->
                            val idx = h.indexOf(':')
                            if (idx > 0) headers[h.substring(0, idx).trim()] = h.substring(idx + 1).trim()
                        }
                    }
                }

                line.startsWith("#EXTGRP:") -> {
                    groups = line.removePrefix("#EXTGRP:").split(";").map { it.trim() }.filter { it.isNotEmpty() }
                }

                line.startsWith("#") -> Unit

                else -> {
                    val tvgId = attrs["tvg-id"].orEmpty()
                    val name = displayName(extinfLine, attrs)
                    val channelGroups = groups.ifEmpty {
                        attrs["group-title"].orEmpty().split(";").map { it.trim() }.filter { it.isNotEmpty() }
                    }.ifEmpty { listOf("Sin categoria") }

                    channels += Channel(
                        id = stableId(sourceId, tvgId, name, line),
                        sourceId = sourceId,
                        tvgId = tvgId,
                        name = name,
                        logo = attrs["tvg-logo"].orEmpty(),
                        groups = channelGroups,
                        url = line,
                        headers = headers.toMap(),
                        kind = kindOf(line)
                    )
                    attrs = emptyMap()
                    headers = mutableMapOf()
                    groups = emptyList()
                }
            }
        }
        return channels
    }

    private fun parseAttrs(line: String): Map<String, String> =
        attrRegex.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }

    /** El nombre visible es lo que va despues de la PRIMERA coma fuera de comillas. */
    private fun displayName(line: String, attrs: Map<String, String>): String {
        var inQuotes = false
        var commaIndex = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') {
                inQuotes = !inQuotes
            } else if (c == ',' && !inQuotes) {
                commaIndex = i
                break
            }
        }
        val visible = if (commaIndex >= 0) line.substring(commaIndex + 1) else ""
        val fallback = attrs["tvg-name"].orEmpty()
        return visible.trim().ifEmpty { fallback.trim() }.ifEmpty { "Canal" }
    }

    fun kindOf(url: String): String {
        val clean = url.substringBefore('?').lowercase()
        return when {
            clean.contains(".m3u8") -> Channel.KIND_HLS
            clean.contains(".mpd") -> Channel.KIND_DASH
            clean.contains(".flv") -> Channel.KIND_UNSUPPORTED
            clean.contains(".mp4") || clean.contains(".ts") -> Channel.KIND_PROGRESSIVE
            else -> Channel.KIND_HLS
        }
    }

    private fun stableId(sourceId: String, tvgId: String, name: String, url: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$sourceId|$tvgId|$name|$url".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
