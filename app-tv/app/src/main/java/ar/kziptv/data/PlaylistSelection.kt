package ar.kziptv.data

import ar.kziptv.core.model.Channel

/**
 * Logica pura del selector de lista: que playlists estan activas, que canales
 * entran al catalogo y cuando se puede apagar una lista.
 *
 * Vive fuera del controller para poder probarla sin Android.
 */
internal object PlaylistSelection {

    /** Las playlists activas (las que el usuario mira). */
    fun enabledSources(sources: List<PersistedSource>): List<PersistedSource> =
        sources.filter { it.enabled }

    /** Los ids de las playlists activas, en el orden en que estan guardadas. */
    fun enabledIds(sources: List<PersistedSource>): Set<String> =
        enabledSources(sources).map { it.id }.toSet()

    /** Cuantas playlists estan activas. */
    fun activeCount(sources: List<PersistedSource>): Int = enabledSources(sources).size

    /** Activa o desactiva una playlist. La que no existe no cambia nada. */
    fun setEnabled(
        sources: List<PersistedSource>,
        id: String,
        enabled: Boolean,
    ): List<PersistedSource> =
        sources.map { if (it.id == id) it.copy(enabled = enabled) else it }

    /** Da vuelta el interruptor de una playlist. */
    fun toggle(sources: List<PersistedSource>, id: String): List<PersistedSource> {
        val source = sources.firstOrNull { it.id == id } ?: return sources
        return setEnabled(sources, id, !source.enabled)
    }

    /**
     * Se puede apagar esta lista?
     *
     * No: si es la ultima lista activa la TV se queda sin nada que mostrar.
     * Una lista ya apagada se puede "apagar" otra vez (no hace nada).
     */
    fun canDeactivate(sources: List<PersistedSource>, id: String): Boolean {
        val source = sources.firstOrNull { it.id == id } ?: return false
        if (!source.enabled) return true
        return activeCount(sources) > 1
    }

    /**
     * Los canales de las playlists activas: lo unico que entra al catalogo.
     *
     * Una lista apagada no aporta canales ni feeds (tampoco variantes de
     * respaldo de otras senales).
     */
    fun enabledChannels(
        channelsBySource: Map<String, List<Channel>>,
        enabledIds: Set<String>,
    ): List<Channel> =
        enabledIds.flatMap { id -> channelsBySource[id].orEmpty() }
}
