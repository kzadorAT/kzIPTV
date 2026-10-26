package ar.kziptv

import ar.kziptv.core.catalog.ChannelCatalog
import ar.kziptv.core.nav.NumberEntry
import ar.kziptv.core.parse.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberEntryTest {

    private fun catalog(): ChannelCatalog {
        val content = javaClass.classLoader!!.getResourceAsStream("ar.m3u")!!.bufferedReader().readText()
        return ChannelCatalog(M3uParser.parse(content, "test"))
    }

    @Test
    fun bufferAcumulaDigitos() {
        assertEquals("1", NumberEntry.append("", 1))
        assertEquals("12", NumberEntry.append("1", 2))
        assertEquals("123", NumberEntry.append("12", 3))
    }

    @Test
    fun bufferIgnoraCerosIniciales() {
        // un 0 solo se ve (feedback), pero al escribir el siguiente digito se descarta
        assertEquals("0", NumberEntry.append("", 0))
        assertEquals("7", NumberEntry.append("0", 7))
        assertEquals("70", NumberEntry.append("7", 0))
        // "07" + 7 -> el 0 se descarta y quedan los dos 7 (canal 77)
        assertEquals("77", NumberEntry.append("07", 7))
    }

    @Test
    fun bufferSeLimitaATresDigitos() {
        // el digito nuevo desplaza al mas viejo
        assertEquals("234", NumberEntry.append("123", 4))
        assertEquals("345", NumberEntry.append("1234", 5))
    }

    @Test
    fun bufferIgnoraDigitosInvalidos() {
        assertEquals("12", NumberEntry.append("12", 99))
    }

    @Test
    fun resuelveElCanalPorNumero() {
        val cat = catalog()
        val target = NumberEntry.resolve(cat, "1")
        assertNotNull(target)
        assertEquals(cat.groups.first(), target)
    }

    @Test
    fun numeroInexistenteNoResuelve() {
        assertNull(NumberEntry.resolve(catalog(), "999"))
        assertNull(NumberEntry.resolve(catalog(), "0"))
        assertNull(NumberEntry.resolve(catalog(), ""))
    }

    @Test
    fun busquedaPorNombreEnMayusculas() {
        val results = NumberEntry.search(catalog(), "CARTOON NETWORK")
        assertTrue("encuentra el canal", results.isNotEmpty())
        assertTrue("primero es Cartoon", results.first().name.contains("Cartoon"))
    }

    @Test
    fun busquedaPorNumeroDeCanal() {
        val cat = catalog()
        val results = NumberEntry.search(cat, "5")
        assertNotNull(results)
        assertEquals(cat.groups[4], results.first())
    }

    @Test
    fun busquedaPorCategoria() {
        val results = NumberEntry.search(catalog(), "Sports")
        assertTrue("hay canales de deportes", results.isNotEmpty())
    }

    @Test
    fun busquedaVaciaDevuelveTodos() {
        assertEquals(catalog().groups.size, NumberEntry.search(catalog(), "").size)
    }

    @Test
    fun busquedaSinResultadosDevuelveVacio() {
        assertTrue(NumberEntry.search(catalog(), "zzzqqqxxx").isEmpty())
    }

    @Test
    fun normalizaAcentos() {
        assertEquals("nino", NumberEntry.normalize("NIÑO"))
        assertEquals("corazon", NumberEntry.normalize("Corazón"))
    }
}
