package ar.kziptv

import ar.kziptv.core.catalog.ChannelCatalog
import ar.kziptv.core.parse.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelCatalogTest {

    private fun catalog(): ChannelCatalog {
        val content = javaClass.classLoader!!.getResourceAsStream("ar.m3u")!!.bufferedReader().readText()
        return ChannelCatalog(M3uParser.parse(content, "test"))
    }

    @Test
    fun numeracionCorrelativa() {
        val numbered = catalog().numbered
        assertEquals(206, numbered.size)
        assertEquals(1, numbered.first().number)
        assertEquals(206, numbered.last().number)
    }

    @Test
    fun categoriasDisponibles() {
        val categories = catalog().categories
        assertTrue("incluye General", categories.contains("General"))
        assertTrue("tiene al menos 15 grupos", categories.size >= 15)
    }

    @Test
    fun busquedaPorNombre() {
        val results = catalog().search("noticias")
        assertTrue("encuentra canales", results.isNotEmpty())
    }
}
