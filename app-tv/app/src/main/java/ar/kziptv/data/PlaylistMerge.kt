package ar.kziptv.data

/**
 * Combina la foto de playlists tomada al iniciar con las que pudieron agregarse
 * mientras se cargaba la red. Conserva siempre las fuentes mas nuevas.
 */
internal object PlaylistMerge {

    fun merge(
        current: List<PersistedSource>,
        loaded: List<PersistedSource>,
    ): List<PersistedSource> {
        val merged = current.toMutableList()
        val indexById = mutableMapOf<String, Int>()
        current.forEachIndexed { index, source -> indexById[source.id] = index }
        loaded.forEach { source ->
            val index = indexById[source.id]
            if (index == null) {
                indexById[source.id] = merged.size
                merged += source
            } else {
                val existing = merged[index]
                if (source.lastRefreshAt > existing.lastRefreshAt) {
                    merged[index] = source.copy(
                        content = source.content.ifBlank { existing.content },
                    )
                }
            }
        }
        return merged
    }
}
