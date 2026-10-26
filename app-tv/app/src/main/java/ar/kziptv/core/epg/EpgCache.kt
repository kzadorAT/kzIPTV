package ar.kziptv.core.epg

import java.security.MessageDigest

/** Utilidades puras para invalidar el cache EPG cuando cambia el origen. */
object EpgCache {

    /** SHA-256 estable del texto XMLTV. */
    fun fingerprint(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
