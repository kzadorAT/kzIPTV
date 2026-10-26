package ar.kziptv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EpgCacheTest {

    @Test
    fun fingerprintEsEstableYCambiaConElContenido() {
        val first = EpgCache.fingerprint("<tv>programmes</tv>")
        val second = EpgCache.fingerprint("<tv>programmes</tv>")
        val changed = EpgCache.fingerprint("<tv>otro</tv>")

        assertEquals(first, second)
        assertNotEquals(first, changed)
        assertEquals(64, first.length)
    }
}
