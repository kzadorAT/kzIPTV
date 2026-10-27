package ar.kziptv.core.epg

import java.util.zip.GZIPInputStream

/**
 * Utilidades puras de la guia EPG remota: validacion de la URL, vencimiento del
 * cache y lectura del XMLTV (comprimido o plano).
 *
 * Sin dependencias de Android: se testea en la JVM (EpgSourceTest).
 */
object EpgSource {

    /**
     * Guia publica por defecto: la que publica el repo (el pipeline semanal
     * commitea epg/ar.xml.gz). Es la que usa la app si no se configuro otra.
     */
    const val DEFAULT_PUBLIC_URL =
        "https://raw.githubusercontent.com/kzadorAT/kzIPTV/main/epg/ar.xml.gz"

    /** La guia se considera vieja a las 24 h (el pipeline publica una vez por semana). */
    const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /** Tope de seguridad: un XMLTV de 2 MB comprimido entra de sobra. */
    const val MAX_BYTES = 32 * 1024 * 1024

    /** Solo http/https: la app no habla con otros esquemas. */
    fun isRemoteUrl(url: String): Boolean {
        val clean = url.trim()
        return clean.startsWith("http://") || clean.startsWith("https://")
    }

    /** true si hay que volver a bajar la guia (nunca se bajo, o ya paso el TTL). */
    fun needsRefresh(lastFetchedAt: Long, now: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean {
        if (lastFetchedAt <= 0L) return true
        return now - lastFetchedAt >= maxAgeMs
    }

    /**
     * Lee el XMLTV: si viene gzip lo descomprime. Devuelve null si los bytes no
     * son una guia valida (no es un XMLTV o esta corrupto).
     */
    fun decode(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val text = if (isGzip(bytes)) {
            try {
                GZIPInputStream(bytes.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) {
                return null
            }
        } else {
            bytes.toString(Charsets.UTF_8)
        }
        if (!text.contains("<programme")) return null
        return text
    }

    /** Magic bytes del gzip (1f 8b). */
    fun isGzip(bytes: ByteArray): Boolean =
        bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
}
