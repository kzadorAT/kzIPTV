package ar.kziptv.core.catalog

import ar.kziptv.core.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistDiffTest {

    private fun channel(
        id: String,
        tvgId: String,
        name: String = "Canal Uno",
        url: String = "http://example.com/$id.m3u8",
        sourceId: String = "src",
        stateKey: String = ChannelIdentity.variantKey(sourceId, tvgId, name)
    ) = Channel(
        id = id,
        sourceId = sourceId,
        tvgId = tvgId,
        name = name,
        url = url,
        stateKey = stateKey
    )

    @Test
    fun playlistSinCambiosNoProduceDiferencias() {
        val channel = channel("uno", "Canal.ar")

        val diff = PlaylistDiffer.diff(listOf(channel), listOf(channel.copy()))

        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
        assertTrue(diff.changed.isEmpty())
        assertTrue(diff.rekeyed.isEmpty())
    }

    @Test
    fun reportaCanalesAgregadosYEliminados() {
        val oldChannel = channel("viejo", "Viejo.ar", name = "Canal Viejo")
        val newChannel = channel("nuevo", "Nuevo.ar", name = "Canal Nuevo")

        val diff = PlaylistDiffer.diff(listOf(oldChannel), listOf(newChannel))

        assertEquals(1, diff.added.size)
        assertEquals(1, diff.removed.size)
        assertTrue(diff.changed.isEmpty())
        assertTrue(diff.rekeyed.isEmpty())
        assertEquals("nuevo.ar", diff.added.first().stateKey.removePrefix("c:tvg:"))
        assertEquals("viejo.ar", diff.removed.first().stateKey.removePrefix("c:tvg:"))
    }

    @Test
    fun mismoTvgIdConUrlDistintaMarcaElGrupoComoCambiado() {
        val oldChannel = channel("viejo", "Canal.ar", url = "http://example.com/old.m3u8")
        val newChannel = channel("nuevo", "Canal.ar", url = "http://example.com/new.m3u8")

        val diff = PlaylistDiffer.diff(listOf(oldChannel), listOf(newChannel))

        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
        assertEquals(1, diff.changed.size)
        assertEquals(newChannel.url, diff.changed.first().variants.first().url)
    }

    @Test
    fun rekeyUsaUnaVarianteConservadaComoFallback() {
        val stableKey = "v:stable"
        val oldChannel = channel(
            id = "viejo",
            tvgId = "Viejo.ar",
            name = "Canal Viejo",
            stateKey = stableKey
        )
        val newChannel = channel(
            id = "nuevo",
            tvgId = "Nuevo.ar",
            name = "Canal Nuevo",
            stateKey = stableKey
        )

        val diff = PlaylistDiffer.diff(listOf(oldChannel), listOf(newChannel))

        assertEquals(1, diff.removed.size)
        assertEquals(1, diff.added.size)
        assertEquals(
            mapOf("c:tvg:viejo.ar" to "c:tvg:nuevo.ar"),
            diff.rekeyed
        )
    }
}
