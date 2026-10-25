package ar.kziptv.core.epg

import ar.kziptv.core.model.Channel
import ar.kziptv.core.model.EpgProgram
import java.util.Calendar
import java.util.TimeZone

/**
 * Parser XMLTV sin dependencias de Android (testeable en JVM).
 *
 * OJO: el XMLTV completo son ~2 MB. Con regex tipo
 *   Regex("<programme\\s+([^>]*)>(.*?)</programme>", DOT_MATCHES_ALL)
 * el backtracking es tan brutal que en una TV de gama baja bloqueaba el hilo
 * principal mas de 20 s -> ANR. Por eso los bloques se buscan con indexOf
 * (lineal, sin backtracking) y dentro de cada bloque, que es chico, se usa regex.
 */
object EpgParser {

    fun parse(xml: String): List<EpgProgram> {
        val programs = mutableListOf<EpgProgram>()
        forEachBlock(xml, "programme") { block ->
            val headerEnd = block.indexOf('>')
            if (headerEnd <= 0) return@forEachBlock
            val attrs = parseAttributes(block.substring(0, headerEnd))
            val channelId = attrs["channel"]?.takeIf { it.isNotBlank() } ?: return@forEachBlock
            val start = parseXmltvTime(attrs["start"].orEmpty()) ?: return@forEachBlock
            val stop = parseXmltvTime(attrs["stop"].orEmpty()) ?: (start + 3_600_000L)
            val body = block.substring(headerEnd + 1)
            programs += EpgProgram(
                channelId = channelId,
                start = start,
                stop = stop,
                title = extractTag(body, "title"),
                description = extractTag(body, "desc"),
                category = extractTag(body, "category")
            )
        }
        return programs
    }

    /** Recorre los bloques <tag ...>...</tag> con indexOf: O(n) y sin backtracking. */
    private inline fun forEachBlock(xml: String, tag: String, action: (String) -> Unit) {
        val open = "<$tag"
        val close = "</$tag>"
        var i = 0
        while (i < xml.length) {
            val start = xml.indexOf(open, i)
            if (start < 0) return
            val after = xml.getOrNull(start + open.length)
            if (after != null && after != '>' && !after.isWhitespace()) {
                i = start + open.length
                continue
            }
            val headerEnd = xml.indexOf('>', start)
            if (headerEnd < 0) return
            val end = xml.indexOf(close, headerEnd)
            if (end < 0) return
            action(xml.substring(start, end + close.length))
            i = end + close.length
        }
    }

    /** Lee los atributos de la cabecera del tag (sin regex, para no depender del backtracking). */
    private fun parseAttributes(header: String): Map<String, String> {
        val out = HashMap<String, String>()
        val attrs = header.substringAfter(' ', "")
        var i = 0
        while (i < attrs.length) {
            val eq = attrs.indexOf('=', i)
            if (eq < 0) break
            val name = attrs.substring(i, eq).trim()
            val q1 = attrs.indexOf('"', eq)
            if (q1 < 0) break
            val q2 = attrs.indexOf('"', q1 + 1)
            if (q2 < 0) break
            if (name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' || it == ':' }) {
                out[name.lowercase()] = attrs.substring(q1 + 1, q2)
            }
            i = q2 + 1
        }
        return out
    }

    /** Texto de un tag interno (<title lang="es">...</title>). */
    private fun extractTag(body: String, tag: String): String {
        val start = body.indexOf("<$tag")
        if (start < 0) return ""
        val headerEnd = body.indexOf('>', start)
        if (headerEnd < 0) return ""
        val end = body.indexOf("</$tag>", headerEnd)
        if (end < 0) return ""
        return body.substring(headerEnd + 1, end).trim()
    }

    /** "20261025183000 +0300" -> epoch ms */
    fun parseXmltvTime(value: String): Long? {
        val m = Regex("""^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})""").find(value.trim()) ?: return null
        val y = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toInt()
        val d = m.groupValues[3].toInt()
        val h = m.groupValues[4].toInt()
        val mi = m.groupValues[5].toInt()
        val se = m.groupValues[6].toInt()
        val offset = Regex("""([+-])(\d{2})(\d{2})""").find(value)
        val offsetMinutes = offset?.let {
            val sign = if (it.groupValues[1] == "-") -1 else 1
            sign * (it.groupValues[2].toInt() * 60 + it.groupValues[3].toInt())
        } ?: 0
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(y, mo - 1, d, h, mi, se)
        return calendar.timeInMillis - offsetMinutes * 60_000L
    }
}

/** Indice de EPG con match exacto y normalizado de tvg-id (A24.ar@SD -> a24.ar). */
class EpgIndex(val programs: List<EpgProgram>) {

    private val byExact: Map<String, List<EpgProgram>> = programs.groupBy { it.channelId.lowercase() }
    private val byNormalized: Map<String, List<EpgProgram>> = programs.groupBy { normalizeTvgId(it.channelId) }

    fun forChannel(channel: Channel): List<EpgProgram> {
        if (channel.tvgId.isNotBlank()) {
            byExact[channel.tvgId.lowercase()]?.let { return it.sortedBy { p -> p.start } }
            val normalized = normalizeTvgId(channel.tvgId)
            if (normalized.isNotBlank()) byNormalized[normalized]?.let { return it.sortedBy { p -> p.start } }
        }
        return emptyList()
    }

    fun nowNext(channel: Channel, now: Long = System.currentTimeMillis()): Pair<EpgProgram?, EpgProgram?> {
        val list = forChannel(channel)
        val current = list.firstOrNull { it.start <= now && it.stop > now }
        val next = list.firstOrNull { it.start > now }
        return current to next
    }

    companion object {
        fun normalizeTvgId(id: String): String =
            id.trim().lowercase().substringBefore("@").replace(Regex("[^a-z0-9]"), "")
    }
}
