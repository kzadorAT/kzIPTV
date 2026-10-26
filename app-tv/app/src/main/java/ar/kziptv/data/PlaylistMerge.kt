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
        val knownIds = current.mapTo(mutableSetOf()) { it.id }
        loaded.forEach { source ->
            if (knownIds.add(source.id)) merged += source
        }
        return merged
    }
}
