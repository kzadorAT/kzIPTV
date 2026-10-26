package ar.kziptv.data

import android.content.Context
import ar.kziptv.core.epg.EpgSource
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Descarga de la guia EPG remota y cache en disco.
 *
 * - El archivo crudo (gzip o plano) queda en cacheDir: sobrevive al reinicio y
 *   evita volver a bajar lo mismo cada 24 h.
 * - Los programas parseados los guarda Room (KzStore), que es el cache que
 *   evita parsear los 2 MB del XMLTV en cada arranque.
 */
class EpgRepository(context: Context) {

    private val appContext = context.applicationContext

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Archivo con la ultima guia descargada. */
    val cacheFile: File = File(File(appContext.cacheDir, "epg"), "ar.xml.gz")

    /** Baja la guia cruda (gzip o XML plano). */
    suspend fun fetchBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("Respuesta vacia")
            val declared = body.contentLength()
            if (declared > EpgSource.MAX_BYTES) {
                error("La guia es demasiado grande (${declared} bytes)")
            }
            val bytes = body.bytes()
            if (bytes.size > EpgSource.MAX_BYTES) {
                error("La guia es demasiado grande (${bytes.size} bytes)")
            }
            bytes
        }
    }

    /** Bytes del cache en disco (null si todavia no se descargo nada). */
    fun readCachedBytes(): ByteArray? {
        if (!cacheFile.exists()) return null
        return try {
            cacheFile.readBytes()
        } catch (e: Exception) {
            null
        }
    }

    /** Escribe el cache en disco de forma atomica (tmp + rename). */
    fun writeCachedBytes(bytes: ByteArray) {
        cacheFile.parentFile?.mkdirs()
        val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(cacheFile)) {
            cacheFile.writeBytes(bytes)
            tmp.delete()
        }
    }

    /** Cuando se descargo la guia por ultima vez (0 = nunca). */
    fun cachedAt(): Long = if (cacheFile.exists()) cacheFile.lastModified() else 0L

    /** Borra el cache en disco (se usa cuando cambia la URL de la guia). */
    fun clearCache() {
        cacheFile.delete()
    }
}
