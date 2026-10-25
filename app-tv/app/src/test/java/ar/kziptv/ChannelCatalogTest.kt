package ar.kziptv

import ar.kziptv.core.catalog.ChannelCatalog
import ar.kziptv.core.parse.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelCatalogTest {

    private fun catalog(): ChannelCatalog {
        val content = javaClass.classLoader!!.getResourceAsStream("ar.m3u")!!.bufferedReader().readText()
        return ChannelCatalog(M3uParser.parse(content, "test"))
    }

    @Test
    fun numeracionCorrelativa() {
        val groups = catalog().groups
        assertEquals(201, groups.size)
        assertEquals(1, groups.first().number)
        assertEquals(201, groups.last().number)
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

    @Test
    fun agrupaFeedsDelMismoTvgId() {
        val cnl = catalog().groups.firstOrNull { it.tvgId == "CartoonNetworkLatinAmerica.us" }
        assertNotNull("el grupo de Cartoon existe", cnl)
        assertEquals("agrupa los 2 feeds", 2, cnl!!.variants.size)
        assertEquals("nombre limpio", "Cartoon Network Latin America", cnl!!.name)
    }

    @Test
    fun laVariantePreferidaEsLaDeMayorCalidad() {
        val disney = catalog().groups.firstOrNull { it.tvgId == "DisneyChannelLatinAmerica.ar" }
        assertNotNull(disney)
        assertEquals("3 feeds de Disney", 3, disney!!.variants.size)
        assertEquals("1080p", disney!!.variants.first().quality)
    }

    @Test
    fun resuelveFeedPorIdDeCanalLogico() {
        val cat = catalog()
        val cnl = cat.groups.first { it.tvgId == "CartoonNetworkLatinAmerica.us" }
        val variantId = cnl.variants.first().channelId
        assertEquals(cnl.id, cat.resolve(variantId)?.id)
        assertEquals(cnl.id, cat.resolve(cnl.id)?.id)
    }
}
