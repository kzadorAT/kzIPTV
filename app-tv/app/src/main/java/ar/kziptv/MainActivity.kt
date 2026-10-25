package ar.kziptv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ar.kziptv.data.KzStore
import ar.kziptv.data.SourceRepository
import ar.kziptv.input.RemoteKeys
import ar.kziptv.playback.PlayerManager
import ar.kziptv.server.EmbeddedServer
import ar.kziptv.ui.KzTheme
import ar.kziptv.ui.Screen
import ar.kziptv.ui.TvController
import ar.kziptv.ui.TvRoot

class MainActivity : ComponentActivity() {

    private lateinit var controller: TvController
    private var server: EmbeddedServer? = null
    private var okDownAt = 0L

    companion object {
        @Volatile
        private var current: MainActivity? = null

        /**
         * Inyecta una tecla desde el companion (por HTTP). Se reinyecta por el mismo
         * camino que el control remoto físico, asi el foco de Compose se mueve igual.
         */
        fun injectKey(code: Int): Boolean {
            val activity = current ?: return false
            val now = android.os.SystemClock.uptimeMillis()
            activity.runOnUiThread {
                activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
                activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            }
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        val t0 = android.os.SystemClock.elapsedRealtime()

        val store = KzStore(this)
        val repository = SourceRepository(this)
        val player = PlayerManager(this)

        controller = TvController(this, store, repository, player)
        android.util.Log.i("KzStartup", "player+controller: " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms")
        controller.bootstrap()

        // Netty carga muchas clases al arrancar: si eso corre en el main thread, la TV
        // tira ANR por el timeout de input. Se levanta en un hilo propio.
        val srv = EmbeddedServer(this, controller, store)
        server = srv
        Thread({ srv.start() }, "kziptv-server").apply { isDaemon = true }.start()

        setContent {
            KzTheme {
                TvRoot(controller)
            }
        }
        window.decorView.post {
            android.util.Log.i("KzStartup", "primer frame: " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms")
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // El D-pad lo maneja el controller (navegacion determinista): el mismo camino
        // para el control remoto fisico y para las teclas inyectadas por el companion.
        if (event.keyCode == RemoteKeys.DPAD_UP || event.keyCode == RemoteKeys.DPAD_DOWN ||
            event.keyCode == RemoteKeys.DPAD_LEFT || event.keyCode == RemoteKeys.DPAD_RIGHT
        ) {
            val screen = controller.screen.value
            if (screen == Screen.GRID || screen == Screen.QUALITY) {
                if (event.action == KeyEvent.ACTION_DOWN) controller.onDpad(event.keyCode)
                return true
            }
        }
        when (event.keyCode) {
            RemoteKeys.CHANNEL_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) controller.zap(1)
                return true
            }
            RemoteKeys.CHANNEL_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) controller.zap(-1)
                return true
            }
            RemoteKeys.PROG_RED -> {
                if (event.action == KeyEvent.ACTION_DOWN) controller.toggleZapMode()
                return true
            }
            RemoteKeys.PROG_YELLOW -> {
                if (event.action == KeyEvent.ACTION_DOWN) controller.toggleFavorite()
                return true
            }
            RemoteKeys.PROG_BLUE, RemoteKeys.MENU -> {
                if (event.action == KeyEvent.ACTION_DOWN) controller.openPairing()
                return true
            }
            RemoteKeys.OK, RemoteKeys.ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    okDownAt = event.eventTime
                    return true
                }
                val longPress = event.eventTime - okDownAt >= 600
                controller.onOk(longPress)
                return true
            }
            RemoteKeys.BACK -> {
                if (event.action == KeyEvent.ACTION_UP) controller.back()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        current = null
        server?.stop()
        controller.player.release()
        super.onDestroy()
    }
}
