package ar.kziptv.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import ar.kziptv.core.model.Channel

class PlayerManager(context: Context) {

    /** Headers del canal en curso (User-Agent / Referer / Origin que trae el m3u). */
    @Volatile
    private var requestHeaders: Map<String, String> = emptyMap()

    /**
     * Se crea un DataSource por request para poder inyectar los headers
     * del canal actual sin recrear el ExoPlayer (zapping instantaneo).
     */
    private val dataSourceFactory: DataSource.Factory = DataSource.Factory {
        DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(requestHeaders)
            .createDataSource()
    }

    val exo: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .build()

    /** Estado del player ("buffering", "playing", "error"...). */
    var onStateChanged: ((String) -> Unit)? = null

    /** Error de reproduccion, ya en texto legible ("Response code: 403"). */
    var onError: ((String) -> Unit)? = null

    /** Resolucion real del video decodificado (0 si todavia no hay frames). */
    var onVideoSize: ((Int, Int) -> Unit)? = null

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val name = when (playbackState) {
                    Player.STATE_BUFFERING -> "buffering"
                    Player.STATE_READY -> "ready"
                    Player.STATE_ENDED -> "ended"
                    else -> "idle"
                }
                onStateChanged?.invoke(name)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) onStateChanged?.invoke("playing")
            }

            override fun onPlayerError(error: PlaybackException) {
                // Log completo: la cadena de causas dice si fue 403, 404 o un problema de red.
                android.util.Log.e("KzIPTV", "PlayerManager.onPlayerError", error)
                onError?.invoke(describe(error))
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                onVideoSize?.invoke(videoSize.width, videoSize.height)
            }
        })
    }

    private fun describe(error: PlaybackException): String {
        val cause = generateSequence(error.cause) { it.cause }.lastOrNull()
        val detail = cause?.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return detail.ifBlank { error.errorCodeName }
    }

    fun play(channel: Channel) {
        requestHeaders = channel.headers
        val builder = MediaItem.Builder()
            .setUri(channel.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(channel.name).build())
        exo.setMediaItem(builder.build())
        exo.prepare()
        exo.playWhenReady = true
    }

    /**
     * Suelta el decoder de video sin perder la playlist.
     *
     * En una TV hay 1 o 2 decodificadores por hardware: si el player principal
     * sigue sosteniendo uno, los players del mosaico no pueden ni inicializar.
     * `stop()` libera el codec (a diferencia de `pause()`, que lo conserva).
     */
    fun suspendVideo() {
        exo.stop()
        exo.clearVideoSurface()
    }

    fun release() {
        exo.release()
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
