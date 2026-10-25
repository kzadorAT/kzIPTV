package ar.kziptv.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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

    fun play(channel: Channel) {
        requestHeaders = channel.headers
        val builder = MediaItem.Builder()
            .setUri(channel.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(channel.name).build())
        exo.setMediaItem(builder.build())
        exo.prepare()
        exo.playWhenReady = true
    }

    fun release() {
        exo.release()
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
