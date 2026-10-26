package ar.kziptv

import ar.kziptv.core.nav.RetryPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {

    @Test
    fun behindLiveWindowEsTransitorio() {
        assertTrue(RetryPolicy.isTransient("ERROR_CODE_BEHIND_LIVE_WINDOW"))
    }

    @Test
    fun ioReadPositionEsTransitorio() {
        assertTrue(RetryPolicy.isTransient("ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE"))
    }

    @Test
    fun http403NoEsTransitorio() {
        assertFalse(RetryPolicy.isTransient("Response code: 403"))
    }

    @Test
    fun http404NoEsTransitorio() {
        assertFalse(RetryPolicy.isTransient("Response code: 404"))
    }

    @Test
    fun reintentaElPrimerIntento() {
        assertTrue(
            RetryPolicy.shouldRetry("ERROR_CODE_BEHIND_LIVE_WINDOW", 0, 2, 0, 6)
        )
    }

    @Test
    fun reintentaElSegundoIntento() {
        assertTrue(
            RetryPolicy.shouldRetry("ERROR_CODE_BEHIND_LIVE_WINDOW", 1, 2, 1, 6)
        )
    }

    @Test
    fun noReintentaPasadoElTopePorFeed() {
        assertFalse(
            RetryPolicy.shouldRetry("ERROR_CODE_BEHIND_LIVE_WINDOW", 2, 2, 2, 6)
        )
    }

    @Test
    fun noReintentaPasadoElTopeGlobal() {
        // El tope global corta el bucle aunque el feed tenga intentos disponibles.
        assertFalse(
            RetryPolicy.shouldRetry("ERROR_CODE_BEHIND_LIVE_WINDOW", 0, 2, 6, 6)
        )
    }

    @Test
    fun noReintentaErroresPermanentes() {
        assertFalse(
            RetryPolicy.shouldRetry("Response code: 403", 0, 2, 0, 6)
        )
    }

    @Test
    fun elTopeGlobalSeRespetaAlReiniciarElContadorPorFeed() {
        // Este es el bug que se arreglo: el contador por feed se reiniciaba con
        // cada "playing" y el feed reintentaba para siempre.
        val attemptsPorFeed = 0
        val total = 6
        assertFalse(
            RetryPolicy.shouldRetry(
                "ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE",
                attemptsPorFeed, 2, total, 6
            )
        )
    }
}
