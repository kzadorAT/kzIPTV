package ar.kziptv.core.epg

import ar.kziptv.core.model.Channel
import ar.kziptv.core.model.EpgProgram
import java.util.Calendar
import java.util.TimeZone

/** Parser XMLTV por expresiones regulares (sin dependencias de Android → testeable en JVM). */
object EpgParser {

    private val channelTag = Regex("""<channel\s+([^>]*)>(.*?)</channel>""", RegexOption.DOT_MATCHES_ALL)
    private val programmeTag = Regex("""<programme\s+([^>]*)>(.*?)</programme>""", RegexOption.DOT_MATCHES_ALL)
    private val attrRegex = Regex("""([A-Za-z0-9_:]+)\s*=\s*"([^"]*)"""")
    private val titleTag = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
    private val descTag = Regex("""<desc[^>]*>(.*?)</desc>""", RegexOption.DOT_MATCHES_ALL)
    private val categoryTag = Regex("""<category[^>]*>(.*?)</category>""", RegexOption.DOT_MATCHES_ALL)

    fun parse(xml: String): List<EpgProgram> {
        val programs = mutableListOf<EpgProgram>()
        programmeTag.findAll(xml).forEach { m ->
            val attrs = attrRegex.findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
            val channelId = attrs["channel"].orEmpty()
            val start = parseXmltvTime(attrs["start"].orEmpty()) ?: return@forEach
            val stop = parseXmltvTime(attrs["stop"].orEmpty()) ?: (start + 3_600_000L)
            val body = m.groupValues[2]
            programs += EpgProgram(
                channelId = channelId,
                start = start,
                stop = stop,
                title = titleTag.find(body)?.groupValues?.get(1)?.trim().orEmpty(),
                description = descTag.find(body)?.groupValues?.get(1)?.trim().orEmpty(),
                category = categoryTag.find(body)?.groupValues?.get(1)?.trim().orEmpty()
            )
        }
        return programs
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

/** Índice de EPG con match exacto y normalizado de tvg-id (A24.ar@SD -> a24.ar). */
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
