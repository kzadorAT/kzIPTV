package ar.kziptv

import ar.kziptv.core.epg.EpgCache
import ar.kziptv.core.epg.EpgSource
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgSourceTest {

    private val xml = "<tv><programme start=\"20261025183000 +0300\" " +
        "stop=\"20261025190000 +0300\" channel=\"A24.ar\">" +
        "<title>Noticias</title></programme></tv>"

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    @Test
    fun leeXmlTvPlano() {
        assertEquals(xml, EpgSource.decode(xml.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun leeXmlTvComprimido() {
        assertEquals(xml, EpgSource.decode(gzip(xml)))
    }

    @Test
    fun rechazaLoQueNoEsUnaGuia() {
        assertNull(EpgSource.decode(ByteArray(0)))
        assertNull(EpgSource.decode("hola".toByteArray(Charsets.UTF_8)))
        assertNull(EpgSource.decode(byteArrayOf(0x1f, 0x8b.toByte(), 0x00)))
    }

    @Test
    fun elFingerprintNoDependeDeLaCompresion() {
        val plain = EpgSource.decode(xml.toByteArray(Charsets.UTF_8))
        val compressed = EpgSource.decode(gzip(xml))
        assertEquals(EpgCache.fingerprint(plain!!), EpgCache.fingerprint(compressed!!))
    }

    @Test
    fun soloAceptaHttp() {
        assertTrue(EpgSource.isRemoteUrl("https://raw.githubusercontent.com/x/y/main/epg/ar.xml.gz"))
        assertTrue(EpgSource.isRemoteUrl("http://192.168.1.17:8099/ar.xml.gz"))
        assertFalse(EpgSource.isRemoteUrl("ftp://ejemplo/ar.xml.gz"))
        assertFalse(EpgSource.isRemoteUrl(""))
        assertFalse(EpgSource.isRemoteUrl("ar.xml.gz"))
    }

    @Test
    fun laGuiaVenceALas24Horas() {
        val now = 1_000_000_000_000L
        assertTrue(EpgSource.needsRefresh(0L, now))
        assertFalse(EpgSource.needsRefresh(now - 60_000L, now))
        assertFalse(EpgSource.needsRefresh(now - 23 * 3_600_000L, now))
        assertTrue(EpgSource.needsRefresh(now - 25 * 3_600_000L, now))
    }
}
