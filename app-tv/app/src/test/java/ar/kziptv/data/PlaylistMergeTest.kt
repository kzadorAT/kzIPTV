package ar.kziptv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistMergeTest {

    @Test
    fun conservaLaSourceAgregadaDuranteElBootstrap() {
        val concurrent = PersistedSource(id = "file-2", name = "Nueva", content = "#EXTM3U")
        val bundled = PersistedSource(id = "bundled-ar", name = "Argentina", bundled = true)

        val merged = PlaylistMerge.merge(listOf(concurrent), listOf(bundled))

        assertEquals(listOf("file-2", "bundled-ar"), merged.map { it.id })
    }

    @Test
    fun noDuplicaLaPlaylistYPrefiereLaVersionMasReciente() {
        val current = PersistedSource(id = "url-1", name = "Actual", content = "nuevo")
        val loaded = PersistedSource(id = "url-1", name = "Viejo", content = "viejo")

        val merged = PlaylistMerge.merge(listOf(current), listOf(loaded))

        assertEquals(1, merged.size)
        assertEquals("nuevo", merged.single().content)
    }
}
