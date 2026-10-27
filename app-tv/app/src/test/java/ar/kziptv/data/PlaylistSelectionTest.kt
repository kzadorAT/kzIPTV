package ar.kziptv.data

import ar.kziptv.core.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El selector de lista: que playlists estan activas, que canales entran al
 * catalogo y cuando se puede apagar una lista.
 */
class PlaylistSelectionTest {

    private fun fuente(id: String, enabled: Boolean = true, bundled: Boolean = false) =
        PersistedSource(
            id = id,
            name = id,
            url = "https://example.test/" + id + ".m3u",
            bundled = bundled,
            enabled = enabled,
        )

    private fun canal(id: String) =
        Channel(id = id, sourceId = "s", name = id, url = "http://x/" + id)

    @Test
    fun soloLasListasActivasAportanCanales() {
        val porFuente = mapOf(
            "a" to listOf(canal("a1"), canal("a2")),
            "b" to listOf(canal("b1")),
        )

        val canales = PlaylistSelection.enabledChannels(porFuente, setOf("a"))

        assertEquals(2, canales.size)
        assertTrue(canales.all { it.id.startsWith("a") })
    }

    @Test
    fun unaListaApagadaNoAportaVariantesDeRespaldo() {
        val porFuente = mapOf(
            "a" to listOf(canal("a1")),
            "b" to listOf(canal("b1")),
        )

        assertEquals(2, PlaylistSelection.enabledChannels(porFuente, setOf("a", "b")).size)
        assertEquals(1, PlaylistSelection.enabledChannels(porFuente, setOf("b")).size)
    }

    @Test
    fun losCanalesConservanElOrdenDeLasListas() {
        val porFuente = mapOf(
            "a" to listOf(canal("a1")),
            "b" to listOf(canal("b1")),
        )

        val canales = PlaylistSelection.enabledChannels(porFuente, setOf("b", "a"))

        assertEquals(listOf("b1", "a1"), canales.map { it.id })
    }

    @Test
    fun elToggleDaVueltaSoloLaListaElegida() {
        val fuentes = listOf(fuente("a"), fuente("b", enabled = false))

        val dado = PlaylistSelection.toggle(fuentes, "b")

        assertTrue(dado.first { it.id == "b" }.enabled)
        assertTrue(dado.first { it.id == "a" }.enabled)
        assertEquals(2, PlaylistSelection.activeCount(dado))
    }

    @Test
    fun setEnabledFijaElValorSinTocarLasOtras() {
        val fuentes = listOf(fuente("a"), fuente("b"))

        val dado = PlaylistSelection.setEnabled(fuentes, "a", false)

        assertFalse(dado.first { it.id == "a" }.enabled)
        assertTrue(dado.first { it.id == "b" }.enabled)
    }

    @Test
    fun unaListaQueNoExisteNoCambiaNada() {
        val fuentes = listOf(fuente("a"))

        assertEquals(fuentes, PlaylistSelection.toggle(fuentes, "zzz"))
        assertEquals(fuentes, PlaylistSelection.setEnabled(fuentes, "zzz", false))
    }

    @Test
    fun noSePuedeApagarLaUltimaListaActiva() {
        val una = listOf(fuente("a"))

        assertFalse(PlaylistSelection.canDeactivate(una, "a"))

        // Con la segunda lista apagada sigue habiendo una sola activa: tampoco se puede.
        val dosConUnaApagada = listOf(fuente("a"), fuente("b", enabled = false))

        assertFalse(PlaylistSelection.canDeactivate(dosConUnaApagada, "a"))
    }

    @Test
    fun conDosListasActivasSePuedeApagarCualquiera() {
        val dos = listOf(fuente("a"), fuente("b"))

        assertTrue(PlaylistSelection.canDeactivate(dos, "a"))
        assertTrue(PlaylistSelection.canDeactivate(dos, "b"))
    }

    @Test
    fun unaListaYaApagadaSePuedeApagarOtraVez() {
        val fuentes = listOf(fuente("a"), fuente("b", enabled = false))

        assertTrue(PlaylistSelection.canDeactivate(fuentes, "b"))
    }

    @Test
    fun porDefectoTodasLasListasEstanActivas() {
        val fuentes = listOf(fuente("a"), fuente("b"))

        assertEquals(listOf("a", "b"), PlaylistSelection.enabledSources(fuentes).map { it.id })
        assertEquals(setOf("a", "b"), PlaylistSelection.enabledIds(fuentes))
    }

    @Test
    fun instalacionNuevaLaListaEmbebidaEntraAlCatalogo() {
        // El arranque arma el catalogo con las fuentes de esa vuelta (la embebida entra
        // aunque la base todavia este vacia): si se filtrara por el store, que en una
        // instalacion nueva aun no tiene ninguna lista, la grilla quedaria vacia.
        val embebida = fuente("bundled-ar", bundled = true)
        val porFuente = mapOf("bundled-ar" to listOf(canal("ar1"), canal("ar2")))

        val canales = PlaylistSelection.enabledChannels(
            porFuente,
            PlaylistSelection.enabledIds(listOf(embebida)),
        )

        assertEquals(2, canales.size)
    }
}
