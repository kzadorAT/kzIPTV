package ar.kziptv.core.catalog

import ar.kziptv.core.model.Channel
import ar.kziptv.core.model.ChannelGroup
import ar.kziptv.core.model.ChannelVariant
import java.security.MessageDigest

/**
 * Agrupa los feeds de una misma señal en un canal lógico.
 *
 * Reglas, en orden:
 *  1. Clave primaria: el tvg-id sin calificador. En iptv-org la misma señal se publica
 *     con el mismo id y distinto sufijo:
 *     "CartoonNetworkLatinAmerica.us@SouthAtlanticHD" vs "CartoonNetworkLatinAmerica.us@SouthAtlantic".
 *  2. Clave secundaria: el nombre limpio (sin calidad ni región). Dos feeds con el mismo
 *     nombre limpio se unen aunque tengan tvg-id distintos (típico al sumar otra playlist).
 *
 * La calidad sale del nombre ("(1080p)") y, si no está, del calificador (@HD / @SD).
 * La región sale del calificador ("@SouthAtlantic" -> "South Atlantic").
 */
object ChannelGrouper {

    private val QUALITY_TOKENS = listOf(
        "4k", "uhd", "1080p", "720p", "576p", "576i", "540p", "480p", "fhd", "hd", "sd"
    )

    private val REGION_TOKENS = listOf(
        "Argentina", "Argentine", "Panregional", "South Atlantic", "South",
        "North America", "Hispanic America", "LatAm"
    )

    private val PAREN_RE = Regex("\\(([^)]*)\\)")
    private val BRACKET_RE = Regex("\\[[^\\]]*\\]")
    private val SPACES_RE = Regex("\\s{2,}")

    fun group(channels: List<Channel>): List<ChannelGroup> {
        if (channels.isEmpty()) return emptyList()

        // cleanName es lo mas caro (regex): se calcula UNA vez por canal.
        val prepared = channels.map { channel ->
            val qualifier = qualifierOf(channel.tvgId)
            Prepared(
                channel = channel,
                qualifier = qualifier,
                clean = cleanName(channel.name, qualifier),
                quality = qualityOf(channel.name, qualifier),
                region = regionOf(qualifier)
            )
        }

        // 1) bucket por tvg-id base
        val buckets = LinkedHashMap<String, MutableList<Prepared>>()
        for (item in prepared) {
            val key = baseTvgId(item.channel.tvgId).ifBlank { "name:" + normalizeName(item.clean) }
            buckets.getOrPut(key) { mutableListOf() }.add(item)
        }

        // 2) merge de los buckets que comparten el nombre limpio exacto
        val merged = mergeByCleanName(buckets)

        return merged
            .map { buildGroup(it) }
            .sortedWith(compareBy({ it.groups.firstOrNull() ?: "" }, { it.name.lowercase() }))
    }

    private data class Prepared(
        val channel: Channel,
        val qualifier: String,
        val clean: String,
        val quality: String,
        val region: String
    )

    /** tvg-id sin el calificador: "Canal.ar@SD" -> "Canal.ar". */
    fun baseTvgId(tvgId: String): String = tvgId.trim().substringBefore('@').trim()

    /** Calificador: "Canal.ar@SouthAtlanticHD" -> "SouthAtlanticHD". */
    fun qualifierOf(tvgId: String): String {
        val at = tvgId.indexOf('@')
        return if (at < 0) "" else tvgId.substring(at + 1).trim()
    }

    /**
     * Nombre sin ruido: saca "(1080p)", "(Argentina)", "[Not 24/7]" y la palabra de región
     * del final ("... South Atlantic" -> "...").
     */
    fun cleanName(name: String, qualifier: String): String {
        var s = name
        s = s.replace(PAREN_RE) { m ->
            val token = m.groupValues[1].trim().lowercase()
            val isNoise = QUALITY_TOKENS.contains(token) ||
                REGION_TOKENS.any { it.lowercase() == token }
            if (isNoise) "" else m.value
        }
        s = s.replace(BRACKET_RE, "")
        s = s.trim()
        // Se repite hasta que no cambie mas: "Panregional HD" -> "Panregional" -> "",
        // por eso el loop tiene que volver a probar TODAS las palabras tras cada corte.
        var changed = true
        while (changed) {
            changed = false
            for (word in regionWords(qualifier)) {
                val re = regionRegex(word)
                val next = s.replace(re, "").trim()
                if (next != s) {
                    s = next
                    changed = true
                }
            }
        }
        return s.replace(SPACES_RE, " ").trim()
    }

    /** Calidad declarada: del parentesis o del calificador. */
    fun qualityOf(name: String, qualifier: String): String {
        val fromName = PAREN_RE.findAll(name)
            .map { it.groupValues[1].trim().lowercase() }
            .firstOrNull { QUALITY_TOKENS.contains(it) }
        if (fromName != null) return fromName
        val q = qualifier.lowercase()
        if (q.endsWith("hd")) return "hd"
        if (q.endsWith("sd")) return "sd"
        return ""
    }

    /** Region del calificador: "SouthAtlanticHD" -> "South Atlantic". */
    fun regionOf(qualifier: String): String {
        var r = qualifier.trim()
        if (r.endsWith("HD", ignoreCase = true)) r = r.dropLast(2)
        if (r.endsWith("SD", ignoreCase = true)) r = r.dropLast(2)
        return r.replace(Regex("([a-z])([A-Z])"), "$1 $2").trim()
    }

    /** Etiqueta para la UI: "1080p · South Atlantic". */
    fun labelOf(quality: String, region: String): String {
        val parts = mutableListOf<String>()
        if (quality.isNotBlank()) parts += quality.uppercase()
        if (region.isNotBlank()) parts += region
        return parts.joinToString(" · ")
    }

    /** Orden de preferencia: mas resolución primero. */
    fun qualityRank(quality: String): Int = when (quality.lowercase()) {
        "4k", "uhd" -> 9
        "1080p", "fhd" -> 8
        "720p" -> 7
        "576p", "576i" -> 6
        "540p" -> 5
        "480p" -> 5
        "hd" -> 4
        "sd" -> 3
        else -> 0
    }

    fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').trim()

    fun normalizeName(name: String): String = name.trim().lowercase().replace(Regex("[^a-z0-9]"), "")

    /** Las regex se compilan una sola vez por palabra: compilar 200+ veces froze la TV. */
    private val regionRegexCache = HashMap<String, Regex>()

    private fun regionRegex(word: String): Regex = regionRegexCache.getOrPut(word) {
        Regex("\\s+" + Regex.escape(word) + "$", RegexOption.IGNORE_CASE)
    }

    private fun regionWords(qualifier: String): List<String> {
        val flat = qualifier.lowercase()
        val words = mutableListOf<String>()
        for (word in REGION_TOKENS) {
            if (flat.contains(word.lowercase().replace(" ", ""))) words += word
        }
        if (flat.endsWith("hd")) words += "HD"
        if (flat.endsWith("sd")) words += "SD"
        // "South Atlantic" tiene que ir antes que "South"
        return words.sortedByDescending { it.length }
    }

    private fun mergeByCleanName(buckets: Map<String, MutableList<Prepared>>): List<List<Prepared>> {
        val result = mutableListOf<MutableList<Prepared>>()
        val indexByCleanName = HashMap<String, Int>()
        for (bucket in buckets.values) {
            for (item in bucket) {
                val clean = normalizeName(item.clean)
                if (clean.isBlank()) {
                    result += mutableListOf(item)
                    continue
                }
                val index = indexByCleanName[clean]
                if (index == null) {
                    indexByCleanName[clean] = result.size
                    result += mutableListOf(item)
                } else {
                    result[index] += item
                }
            }
        }
        return result
    }

    private fun buildGroup(items: List<Prepared>): ChannelGroup {
        val variants = items.map { item ->
            ChannelVariant(
                channelId = item.channel.id,
                name = item.channel.name,
                label = labelOf(item.quality, item.region),
                quality = item.quality,
                region = item.region,
                host = hostOf(item.channel.url),
                url = item.channel.url,
                headers = item.channel.headers,
                kind = item.channel.kind
            )
        }.sortedWith(
            compareByDescending<ChannelVariant> { qualityRank(it.quality) }
                .thenBy { it.region.lowercase() }
                .thenBy { it.name.lowercase() }
        )

        val displayName = items
            .map { it.clean }
            .filter { it.isNotBlank() }
            .minByOrNull { it.length }
            ?: items.first().channel.name

        return ChannelGroup(
            id = "g:" + hash(normalizeName(displayName)),
            name = displayName,
            logo = items.firstOrNull { it.channel.logo.isNotBlank() }?.channel?.logo.orEmpty(),
            groups = items.flatMap { it.channel.groups }.distinct(),
            tvgId = items.firstOrNull { it.channel.tvgId.isNotBlank() }?.let { baseTvgId(it.channel.tvgId) }.orEmpty(),
            variants = variants
        )
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }
}
