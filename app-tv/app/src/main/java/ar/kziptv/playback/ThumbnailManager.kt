package ar.kziptv.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.TextureView
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * Resultado de intentar capturar un canal.
 *
 * `bitmap == null` significa "no hay frame": el feed fallo o no arranco. Es
 * importante que el manager NO devuelva un frame negro cuando el feed no
 * renderiza nada, porque ese negro queda congelado en el mosaico y el usuario
 * no puede distinguir "canal sin señal" de "captura fallida".
 */
data class CaptureResult(
    val bitmap: Bitmap? = null,
    val error: String? = null,
    val played: Boolean = false
)

/**
 * Captura de miniaturas: UN player que va tomando un frame de cada canal.
 *
 * Por que NO cuatro streams a la vez: en la TCL Smart TV Pro (Realtek RTD2880)
 * el decoder de video por hardware es una UNICA instancia
 * (`OMX.realtek.video.decoder`). Con cuatro players los que no entran en el
 * decoder caen a software, el proceso llega al 86% de CPU y la app se cuelga
 * (ANR: "Input dispatching timed out").
 *
 * La solucion es la misma que usan Plex o TiviMate en TV: el canal se reproduce
 * unos segundos, se congela un frame y se sigue con el siguiente. El usuario
 * ve muchos canales a la vez y el decoder se usa de a uno.
 *
 * OJO: Media3 exige que el player se use desde el hilo que lo creo (el main).
 */
class ThumbnailManager(context: Context) {

    private val appContext = context.applicationContext

    private val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory()))
        .build()

    /** Headers del canal en curso: el m3u puede pedir User-Agent o Referer. */
    @Volatile
    private var headers: Map<String, String> = emptyMap()

    /** La vista donde se dibuja el video mientras se captura. */
    private var view: TextureView? = null

    /** Se completa cuando la UI engancha el TextureView del tile en captura. */
    private var viewReady: CompletableDeferred<TextureView> = CompletableDeferred()

    /** Se completa cuando el decoder entrega el primer frame. */
    private var firstFrame: CompletableDeferred<Unit> = CompletableDeferred()

    /** Ultimo tamaño real del video (para corregir la proporcion del frame). */
    private var videoSize: VideoSize = VideoSize.UNKNOWN

    /** Id del canal que se esta capturando: filtra eventos del canal anterior. */
    private var expectedMediaId: String? = null

    /** true cuando el decoder entrego al menos un frame del canal actual. */
    private var frameRendered = false

    /** true cuando el player llego a reproducir (feed vivo). */
    private var playedFlag = false

    /** Ultimo error del feed actual. */
    private var lastError: String? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) playedFlag = true
            }

            override fun onRenderedFirstFrame() {
                // Un frame del canal anterior no cuenta: se filtra por mediaId.
                if (expectedMediaId != null &&
                    player.currentMediaItem?.mediaId != expectedMediaId
                ) {
                    return
                }
                frameRendered = true
                if (!firstFrame.isCompleted) firstFrame.complete(Unit)
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                videoSize = size
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e(
                    "KzIPTV",
                    "captura fallo: " + error.errorCodeName + " / " + error.message
                )
                frameRendered = false
                lastError = error.errorCodeName
                if (!firstFrame.isCompleted) firstFrame.complete(Unit)
            }
        })
    }

    /** La UI engancha el TextureView del tile que se esta capturando. */
    fun attach(textureView: TextureView) {
        view = textureView
        player.setVideoTextureView(textureView)
        if (!viewReady.isCompleted) viewReady.complete(textureView)
    }

    /** La UI suelta el TextureView (el tile dejo de estar en captura). */
    fun detach(textureView: TextureView) {
        if (view === textureView) {
            player.clearVideoTextureView(textureView)
            view = null
        }
    }

    /**
     * Captura el frame de un canal.
     *
     * @param channel canal a capturar
     * @param settleMs milisegundos extra de espera despues del primer frame
     *   (para que la imagen no salga a medio decodificar)
     * @return el resultado: frame congelado o el error del feed. Nunca devuelve
     *   un bitmap negro por feed caido.
     */
    suspend fun capture(channel: Channel, settleMs: Long = 1_200): CaptureResult {
        viewReady = CompletableDeferred()
        firstFrame = CompletableDeferred()
        videoSize = VideoSize.UNKNOWN
        headers = channel.headers
        expectedMediaId = channel.id
        frameRendered = false
        playedFlag = false
        lastError = null

        val item = MediaItem.Builder()
            .setMediaId(channel.id)
            .setUri(channel.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(channel.name).build())
            .build()
        try {
            player.setMediaItem(item)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            // El player se libero por error (o quedo en mal estado): el tile
            // queda sin senal en vez de romper el barrido entero.
            android.util.Log.e("KzIPTV", "no se pudo iniciar la captura", e)
            return CaptureResult(bitmap = null, error = "player no disponible")
        }

        try {
            // El primer frame puede tardar: el feed tiene que resolver, negociar
            // y descargar el primer segmento.
            withTimeoutFirstFrame()
            delay(settleMs)

            val rendered = frameRendered
            val error = lastError
            val played = playedFlag
            val raw = if (rendered) {
                try {
                    view?.getBitmap()
                } catch (e: Exception) {
                    android.util.Log.w("KzIPTV", "no se pudo capturar el frame", e)
                    null
                }
            } else {
                // El feed no renderizo nada: NO se congela el frame negro.
                null
            }

            if (raw == null) {
                return CaptureResult(bitmap = null, error = error ?: "sin senal", played = played)
            }
            return CaptureResult(bitmap = correctAspect(raw, videoSize), error = null, played = played)
        } finally {
            // Aunque se cancele la captura (el usuario movio el foco), el player
            // se detiene: el decoder es UNICO y no puede quedar ocupado.
            player.stop()
            frameRendered = false
            playedFlag = false
            expectedMediaId = null
        }
    }

    /** Espera el primer frame, con un tope de tiempo. */
    private suspend fun withTimeoutFirstFrame() {
        val done = CompletableDeferred<Unit>()
        firstFrame.invokeOnCompletion { done.complete(Unit) }
        kotlinx.coroutines.withTimeoutOrNull(8_000) { done.await() }
    }

    /**
     * Corrige la proporcion del frame capturado.
     *
     * Un TextureView estira el video a los limites de la vista, asi que una
     * señal 4:3 dentro de un tile 16:9 sale deformada. Se deshace el estirado
     * con una escala en un eje y se deja el fondo negro (letterbox).
     */
    private fun correctAspect(source: Bitmap, size: VideoSize): Bitmap {
        val nativeW = size.width
        val nativeH = size.height
        if (nativeW <= 0 || nativeH <= 0) return source
        val bw = source.width
        val bh = source.height
        if (bw <= 0 || bh <= 0) return source

        val nativeAspect = nativeW.toFloat() / nativeH.toFloat()
        val tileAspect = bw.toFloat() / bh.toFloat()
        if (kotlin.math.abs(nativeAspect - tileAspect) < 0.03f) return source

        val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val matrix = Matrix()
        if (nativeAspect > tileAspect) {
            // La fuente es mas ancha: se achica en horizontal y se centra.
            val factor = tileAspect / nativeAspect
            matrix.setScale(factor, 1f, bw / 2f, bh / 2f)
        } else {
            // La fuente es mas alta: se achica en vertical y se centra.
            val factor = nativeAspect / tileAspect
            matrix.setScale(1f, factor, bw / 2f, bh / 2f)
        }
        canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        source.recycle()
        return out
    }

    /**
     * Detiene la captura SIN liberar el player.
     *
     * El mosaico se abre y se cierra muchas veces en una sesion: si el player
     * se liberara al cerrar, la segunda apertura se quedaria sin decoder
     * ("sending message to a Handler on a dead thread") y ningun canal se
     * capturaria. El player se reutiliza; el decoder se suelta al stopping.
     */
    fun stop() {
        view?.let { player.clearVideoTextureView(it) }
        view = null
        player.stop()
    }

    /** Libera el player (los decodificadores son el recurso mas escaso). */
    fun release() {
        view?.let { player.clearVideoTextureView(it) }
        view = null
        player.release()
    }

    /** DataSource propio: cada canal puede pedir headers distintos. */
    private fun dataSourceFactory(): DataSource.Factory = DataSource.Factory {
        DefaultHttpDataSource.Factory()
            .setUserAgent(PlayerManager.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(headers)
            .createDataSource()
    }
}
