package ar.kziptv.core.catalog

/** Claves estables para reconocer canales entre actualizaciones de la playlist. */
object ChannelIdentity {
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]")

    /** Identifica el canal logico; el tvg-id base tiene prioridad sobre el nombre. */
    fun groupKey(tvgId: String, name: String): String {
        val base = tvgId.trim().substringBefore('@').trim().lowercase()
        return if (base.isNotBlank()) "c:tvg:$base" else "c:name:${normalizeName(name)}"
    }

    /** Identifica un feed concreto dentro de una fuente. */
    fun variantKey(sourceId: String, tvgId: String, name: String): String {
        val full = tvgId.trim().lowercase()
        return if (full.isNotBlank()) {
            "v:tvg:$sourceId|$full"
        } else {
            "v:name:$sourceId|${normalizeName(name)}"
        }
    }

    /** Normalizacion compatible con la limpieza usada para agrupar. */
    fun normalizeName(name: String): String =
        name.trim().lowercase().replace(NON_ALPHANUMERIC, "")
}
