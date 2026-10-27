package ar.kziptv.core.catalog

import ar.kziptv.core.model.Channel
import ar.kziptv.core.parse.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los numeros de canal son estables: un refresh no reordena la grilla. */
class ChannelCatalogNumberingTest {

    private fun playlist(vararg tvgIds: String): List<Channel> {
        val body = tvgIds.joinToString("\n") { id ->
            "#EXTINF:-1 tvg-id=\"$id\",Canal $id\nhttp://example.com/$id.m3u8"
        }
        return M3uParser.parse("#EXTM3U\n$body", "src-1")
    }

    private fun ChannelCatalog.numberOf(tvgId: String): Int =
        groups.first { it.tvgId == tvgId }.number

    @Test
    fun numeraSecuencialmenteCuandoNoHayNumerosGuardados() {
        val catalog = ChannelCatalog(playlist("uno.ar", "dos.ar"))

        assertEquals(listOf(1, 2), catalog.groups.map { it.number }.sorted())
    }

    @Test
    fun conservaLosNumerosExistentesYAgregaLosNuevosAlFinal() {
        val first = playlist("uno.ar", "dos.ar")
        val saved = ChannelCatalog(first).numberAssignments

        val second = ChannelCatalog(playlist("uno.ar", "dos.ar", "tres.ar"), saved)

        assertEquals(saved["c:tvg:uno.ar"], second.numberOf("uno.ar"))
        assertEquals(saved["c:tvg:dos.ar"], second.numberOf("dos.ar"))
        assertEquals(3, second.numberOf("tres.ar"))
    }

    @Test
    fun reservaElNumeroDeUnCanalEliminado() {
        val first = playlist("uno.ar", "dos.ar")
        val saved = ChannelCatalog(first).numberAssignments
        val reserved = saved["c:tvg:dos.ar"]!!

        val second = ChannelCatalog(playlist("uno.ar", "tres.ar"), saved)

        // "dos.ar" sigue reservado: el canal nuevo se agrega al final, sin reciclar numeros.
        assertEquals(saved["c:tvg:uno.ar"], second.numberOf("uno.ar"))
        assertTrue(second.numberAssignments.containsValue(reserved))
        assertEquals(3, second.numberOf("tres.ar"))
    }
}
