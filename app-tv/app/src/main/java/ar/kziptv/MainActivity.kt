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

    /** Momento del ultimo BACK en la pantalla raiz (para el BACK doble). */
    private var lastBackAt = 0L

    /** La app ya se esta cerrando: no se sueltan los recursos dos veces. */
    private var closing = false

    companion object {
        /** Ventana para el segundo BACK que cierra la app. */
        private const val EXIT_WINDOW_MS = 3_000L

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
            // Si el campo de busqueda tiene el foco de plataforma, el IME del
            // sistema esta abierto: las teclas las maneja el, no nosotros.
            val imeOpen = screen == Screen.SEARCH && controller.searchFieldFocused.value
            if (!imeOpen &&
                (
                    screen == Screen.GRID || screen == Screen.QUALITY ||
                        screen == Screen.SEARCH || screen == Screen.MOSAIC
                    )
            ) {
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
            RemoteKeys.PROG_GREEN -> {
                if (event.action == KeyEvent.ACTION_DOWN) controller.openSearch()
                return true
            }
            RemoteKeys.MENU, RemoteKeys.INFO -> {
                // MENU/INFO: mosaico para comparar canales.
                if (event.action == KeyEvent.ACTION_DOWN) controller.openMosaic()
                return true
            }
            RemoteKeys.PROG_BLUE -> {
                if (event.action == KeyEvent.ACTION_DOWN) controller.openPairing()
                return true
            }
            in RemoteKeys.DIGIT_0..RemoteKeys.DIGIT_9,
            in RemoteKeys.NUMPAD_0..RemoteKeys.NUMPAD_9 -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    RemoteKeys.digitOf(event.keyCode)?.let { controller.onDigit(it) }
                }
                return true
            }
            RemoteKeys.OK, RemoteKeys.ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    okDownAt = event.eventTime
                    return true
                }
                val longPress = event.eventTime - okDownAt >= 600
                // Si hay un numero de canal escrito, el OK lo confirma.
                if (controller.numberBuffer.value.isNotBlank()) {
                    controller.confirmNumber()
                } else if (controller.searchFieldFocused.value) {
                    // El IME del sistema esta abierto: OK le pertenece a el.
                    return false
                } else {
                    controller.onOk(longPress)
                }
                return true
            }
            RemoteKeys.BACK -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (controller.numberBuffer.value.isNotBlank()) {
                        controller.cancelNumber()
                    } else if (controller.searchFieldFocused.value) {
                        // Cierra el IME del sistema y deja el foco en la pantalla.
                        controller.setSearchFieldFocused(false)
                        controller.clearSearchFieldFocus()
                    } else if (!controller.back()) {
                        // Estamos en la pantalla raiz (la grilla): no hay a donde
                        // volver. Un BACK mas dentro de la ventana cierra la app.
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastBackAt <= EXIT_WINDOW_MS) {
                            closeApp()
                        } else {
                            lastBackAt = now
                            controller.showExitHint()
                        }
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Cierra la app de verdad: se sueltan el server embebido, el player de
     * reproduccion y el de captura, y se borra la tarea de la lista de apps
     * recientes (vuelve al launcher, como en cualquier Android TV).
     */
    private fun closeApp() {
        if (closing) return
        closing = true
        android.util.Log.i("KzIPTV", "salida por BACK doble")
        controller.hideExitHint()
        server?.stop()
        controller.release()
        controller.player.release()
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        current = null
        if (!closing) {
            server?.stop()
            controller.release()
            controller.player.release()
        }
        super.onDestroy()
    }
}
