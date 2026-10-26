package ar.kziptv.core.nav

/**
 * Politica de reintentos ante errores de live.
 *
 * Se extrae del controller para poder testearla sin Android. El bug que motivo
 * esto: el contador se reiniciaba con cada evento "playing" (que un feed moribundo
 * dispara un instante antes de caerse), y el feed reintentaba para siempre.
 */
object RetryPolicy {

    /** Errores que se resuelven reintentando el mismo feed. */
    private val TRANSIENT_MARKERS = listOf(
        "BEHIND_LIVE_WINDOW",
        "IO_READ_POSITION_OUT_OF_RANGE",
        "TIMEOUT",
        "CONNECTION_RESET",
        "HOST_NOT_FOUND",
        "UNEXPECTED_EOF"
    )

    /** El mensaje corresponde a un error transitorio de live. */
    fun isTransient(message: String): Boolean {
        val m = message.uppercase()
        return TRANSIENT_MARKERS.any { m.contains(it) }
    }

    /**
     * Decide si se reintenta el mismo feed.
     *
     * @param attempts reintentos ya hechos para ese feed.
     * @param maxAttempts tope por feed.
     * @param totalAttempts reintentos transitorios hechos en total (tope global).
     * @param maxTotal tope global.
     */
    fun shouldRetry(
        message: String,
        attempts: Int,
        maxAttempts: Int,
        totalAttempts: Int,
        maxTotal: Int
    ): Boolean {
        if (!isTransient(message)) return false
        if (attempts >= maxAttempts) return false
        return totalAttempts < maxTotal
    }
}
