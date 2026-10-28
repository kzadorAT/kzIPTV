package ar.kziptv.ui

import android.content.Context
import android.graphics.Bitmap
import ar.kziptv.core.catalog.ChannelCatalog
import ar.kziptv.core.catalog.ChannelGrouper
import ar.kziptv.core.catalog.PlaylistDiffer
import ar.kziptv.core.epg.EpgCache
import ar.kziptv.core.epg.EpgIndex
import ar.kziptv.core.epg.EpgParser
import ar.kziptv.core.epg.EpgSource
import ar.kziptv.core.model.Channel
import ar.kziptv.core.model.ChannelGroup
import ar.kziptv.core.model.ChannelVariant
import ar.kziptv.core.model.toChannel
import ar.kziptv.core.parse.M3uParser
import ar.kziptv.data.ChannelStateMigration
import ar.kziptv.data.EpgRepository
import ar.kziptv.data.KzStore
import ar.kziptv.data.PlaylistMerge
import ar.kziptv.data.PlaylistSelection
import ar.kziptv.data.PersistedSource
import ar.kziptv.data.SourceRepository
import ar.kziptv.core.nav.GridNavigator
import ar.kziptv.core.nav.NumberEntry
import ar.kziptv.core.nav.NumberPad
import ar.kziptv.core.nav.SurfPlanner
import ar.kziptv.core.nav.RetryPolicy
import ar.kziptv.input.RemoteKeys
import ar.kziptv.playback.ThumbnailManager
import ar.kziptv.playback.PlayerManager
import ar.kziptv.server.EmbeddedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.zip.GZIPInputStream

enum class Screen { PLAYER, GRID, SETUP, QUALITY, SEARCH, MOSAIC, SETTINGS, LISTS, CHANNEL_MENU, NUMBER_PAD }

/** Filas de la pantalla de ajustes (el orden es el de navegacion). */
enum class Setting { SERVER, PAIRING, ZAP, VIDEO, EPG, PLAYLIST, LISTS }

/**
 * Secciones de la barra lateral: la columna fija de la izquierda de la grilla.
 *
 * Es la casa de todo lo que antes solo se llegaba con las teclas de color
 * (ROJO/VERDE/AZUL/MENU), porque hay controles remotos que no las tienen.
 */
enum class Rail { CANALES, FAVORITOS, ZAP, BUSCAR, LISTAS, COMPARAR, AJUSTES, IR_A_CANAL }

/** Acciones del menu contextual de un canal (OK largo). */
enum class ChannelAction { REPRODUCIR, FAVORITO, CALIDAD, COMPARAR }

/** Una variante (feed/calidad) de un canal logico. */
@Serializable
data class VariantDto(
    val id: String,
    val name: String,
    val label: String = "",
    val quality: String = "",
    val region: String = "",
    val host: String = "",
    val status: String = ChannelVariant.STATUS_UNKNOWN,
    val measured: String = "",
    val active: Boolean = false
)

/** Un canal logico: lo que ve el usuario en la grilla. */
@Serializable
data class ChannelDto(
    val id: String,
    val name: String,
    val logo: String = "",
    val number: Int = 0,
    val groups: List<String> = emptyList(),
    val favorite: Boolean = false,
    val kind: String = Channel.KIND_HLS,
    val tvgId: String = "",
    val now: String = "",
    val next: String = "",
    val variants: List<VariantDto> = emptyList(),
    val activeVariantId: String = ""
)

/** Una playlist (lista de canales): se puede activar o apagar desde la TV. */
@Serializable
data class PlaylistDto(
    val id: String,
    val name: String = "",
    val url: String = "",
    val channelCount: Int = 0,
    /** true si el usuario mira esta lista (solo las activas entran al catalogo). */
    val active: Boolean = true,
    /** true si viene con la app (no se puede quitar, solo apagar). */
    val bundled: Boolean = false,
    /** Ultima descarga exitosa (epoch ms). */
    val lastRefreshAt: Long = 0,
)

/** Resultado de una actualizacion manual de playlists. */
@Serializable
data class PlaylistRefreshResult(
    val ok: Boolean = false,
    val added: Int = 0,
    val removed: Int = 0,
    val changed: Int = 0,
    val sourcesRefreshed: Int = 0,
    val sourcesFailed: Int = 0,
    /** Playlists apagadas: no se descargan (el refresh solo toca las activas). */
    val sourcesSkipped: Int = 0,
    val message: String = "",
)

@Serializable
data class StateDto(
    val pairingCode: String = "",
    val serverUrl: String = "",
    val status: String = "",
    val screen: String = "PLAYER",
    val focusedId: String? = null,
    val focusedName: String = "",
    val focusedCategory: String? = null,
    val currentId: String? = null,
    val currentVariantId: String? = null,
    val currentVariantLabel: String = "",
    val zapFavoritesOnly: Boolean = false,
    val channelCount: Int = 0,
    val groupCount: Int = 0,
    val variantCount: Int = 0,
    val categories: List<String> = emptyList(),
    val channels: List<ChannelDto> = emptyList(),
    val playbackState: String = "idle",
    val playbackError: String = "",
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** Numero de canal escrito con las teclas 0-9 (vacio = no se escribe). */
    val numberBuffer: String = "",
    /** Texto de la busqueda en curso. */
    val searchQuery: String = "",
    /** Errores por tile del mosaico (indice -> mensaje). */
    val mosaicErrors: Map<String, String> = emptyMap(),
    /** Id del resultado de busqueda enfocado (vacio = foco en el campo). */
    val focusedSearchId: String? = null,
    /** Nombre del resultado de busqueda enfocado. */
    val focusedSearchName: String = "",
    /** URL configurada de la guia EPG remota (vacio = se usa la embebida). */
    val epgUrl: String = "",
    /** De donde sale la guia: "embebido" o "remoto". */
    val epgSource: String = "",
    /** Cuando se cargo la guia actual (epoch ms). */
    val epgUpdatedAt: Long = 0,
    /** true mientras se esta descargando o parseando la guia. */
    val epgRefreshing: Boolean = false,
    /** true mientras se actualizan las playlists. */
    val playlistRefreshing: Boolean = false,
    /** Las playlists guardadas con su estado real (lo que ve el selector). */
    val lists: List<PlaylistDto> = emptyList(),
)

/** El contenido crudo de una playlist y sus canales ya parseados. */
private data class LoadedSource(val content: String, val channels: List<Channel>)

class TvController(
    private val context: Context,
    private val store: KzStore,
    private val repo: SourceRepository,
    val player: PlayerManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val catalog = MutableStateFlow(ChannelCatalog(emptyList()))
    val favorites = MutableStateFlow<Set<String>>(emptySet())
    val playlistRefreshing = MutableStateFlow(false)
    val currentGroup = MutableStateFlow<ChannelGroup?>(null)
    val currentVariant = MutableStateFlow<ChannelVariant?>(null)
    val bannerVisible = MutableStateFlow(false)
    val screen = MutableStateFlow(Screen.PLAYER)
        .also { flow ->
            // traza de cambios de pantalla (depuracion remoto/TV)
            scope.launch {
                flow.collect { android.util.Log.d("KzIPTV", "pantalla -> " + it.name) }
            }
        }
    val category = MutableStateFlow<String?>(null)
    val query = MutableStateFlow("")
    val epg = MutableStateFlow(EpgIndex(emptyList()))

    /** Descarga de la guia EPG remota y cache en disco. */
    private val epgRepo = EpgRepository(context)

    /** URL configurada de la guia EPG (espejo del store para la UI). */
    val epgUrl = MutableStateFlow(EpgSource.DEFAULT_PUBLIC_URL)

    /** De donde sale la guia actual: "embebido" o "remoto". */
    val epgSource = MutableStateFlow("embebido")

    /** Cuando se cargo la guia actual (epoch ms). */
    val epgUpdatedAt = MutableStateFlow(0L)

    /** true mientras se esta descargando o parseando la guia. */
    val epgRefreshing = MutableStateFlow(false)
    /** Ultimo mensaje de estado (lo que ve el companion en /api/state). */
    val status = MutableStateFlow("Cargando…")

    /** true mientras el cartel de estado debe verse en pantalla. */
    val statusVisible = MutableStateFlow(true)

    private var statusJob: Job? = null
    val serverUrl = MutableStateFlow("")
    val pairingCode = MutableStateFlow("")

    /** El server web no arranca solo: se enciende desde AJUSTES. */
    val serverRunning = MutableStateFlow(false)

    /** true mientras el server esta arrancando (Netty tarda un instante). */
    val serverBusy = MutableStateFlow(false)

    /** Foco de la pantalla de ajustes. */
    val settingsFocus = MutableStateFlow(0)

    /** Las playlists guardadas con su estado real (lo que ve el selector de lista). */
    val playlists = MutableStateFlow<List<PlaylistDto>>(emptyList())

    /** Foco de la pantalla del selector de lista. */
    val listsFocus = MutableStateFlow(0)

    /** Playlist que espera confirmacion para quitarse (null = ninguna). */
    val listsRemovingId = MutableStateFlow<String?>(null)

    /** true mientras se carga una lista o se rearma el catalogo. */
    val listsBusy = MutableStateFlow(false)

    /** Encuadre del video: 0 = ajustar, 1 = rellenar, 2 = ampliar. */
    val videoFitMode = MutableStateFlow(0)
    val zapFavoritesOnly = MutableStateFlow(false)
    val focusedGroupId = MutableStateFlow<String?>(null)
    val focusedVariantId = MutableStateFlow<String?>(null)

    /** Categoria enfocada en la fila de chips (null = el foco esta en la grilla). */
    val focusedCategory = MutableStateFlow<String?>(null)

    /** La barra lateral tiene el foco (LEFT la enfoca, RIGHT/BACK la dejan). */
    val railActive = MutableStateFlow(false)

    /** Seccion enfocada de la barra lateral. */
    val railFocus = MutableStateFlow(0)

    /** Foco dentro del menu contextual del canal. */
    val channelMenuFocus = MutableStateFlow(0)

    /** El canal del menu contextual (id del grupo logico). */
    val channelMenuTarget = MutableStateFlow<String?>(null)

    /** El menu contextual muestra "Reproducir"? (false cuando se abre desde el video). */
    val channelMenuCanPlay = MutableStateFlow(true)

    /** El menu contextual se abrio desde el video (el video queda de fondo). */
    val channelMenuFromPlayer = MutableStateFlow(false)

    /** Foco dentro del teclado numerico en pantalla. */
    val keypadFocus = MutableStateFlow(0)

    /** El numero escrito en el teclado en pantalla. */
    val keypadBuffer = MutableStateFlow("")

    /** Mostrar solo los favoritos en la grilla (seccion FAVORITOS de la barra). */
    val favoritesOnly = MutableStateFlow(false)

    val qualityTarget = MutableStateFlow<ChannelGroup?>(null)

    /**
     * Numero escrito con las teclas 0-9 para saltar directo a un canal.
     * Vacio = no se esta escribiendo.
     */
    val numberBuffer = MutableStateFlow("")

    /** Resultados de la busqueda por texto (pantalla SEARCH). */
    val searchResults = MutableStateFlow<List<ChannelGroup>>(emptyList())

    /** Canales que se comparan en el mosaico (uno por tile). */
    val mosaicChannels = MutableStateFlow<List<ChannelGroup>>(emptyList())

    /** Tile enfocado del mosaico. */
    val mosaicFocus = MutableStateFlow(0)

    /** Errores por tile del mosaico (indice -> mensaje). Un tile caido muestra el logo. */
    val mosaicTileErrors = MutableStateFlow<Map<Int, String>>(emptyMap())

    /** El preview del mosaico (canal enfocado) esta reproduciendo? */
    val mosaicPlaying = MutableStateFlow(false)

    /** Miniaturas capturadas: indice del tile -> frame congelado. */
    val mosaicThumbs = MutableStateFlow<Map<Int, Bitmap>>(emptyMap())

    /** Tile que se esta capturando ahora (-1 = ninguno). Es el unico con video. */
    val mosaicLiveIndex = MutableStateFlow(-1)

    /** Canal enfocado dentro de los resultados de busqueda. */
    val focusedSearchId = MutableStateFlow<String?>(null)

    /**
     * Token para pedirle a la UI que enfoque el campo de texto nativo.
     * Cada pedido incrementa el token; la UI lo observa y hace requestFocus().
     */
    val searchFieldRequest = MutableStateFlow(0)

    /**
     * true cuando el campo de texto NATIVO tiene el foco de plataforma y el IME
     * del sistema esta abierto. En ese estado el sistema maneja las teclas.
     */
    val searchFieldFocused = MutableStateFlow(false)

    /**
     * Mosaico de miniaturas: N canales a la vista, uno por tile.
     *
     * Cada tile se reproduce unos segundos, se congela un frame y se sigue con
     * el siguiente. El decoder de hardware de la TV es UNICO: mantener cuatro
     * streams a la vez lleva al 86% de CPU y cuelga la app.
     *
     * En Android TV no hay PiP del sistema, asi que la vista de captura se hace
     * con un TextureView propio.
     */
    val mosaic = ThumbnailManager(context)

    /** El foco cambio: el loop de surf tiene que capturar ese tile ya. */
    private var mosaicFocusDirty = false

    /** Reintentos de la captura por errores transitorios (tope de la sesion). */
    private var mosaicRetries = 0

    /** Trabajo de barrido: va tomando un frame de cada tile, uno por uno. */
    private var mosaicSweepJob: Job? = null

    /** Captura puntual del tile enfocado (corta el barrido). */
    private var mosaicCaptureJob: Job? = null

    /** A donde vuelve BACK cuando el canal se eligio desde una lista. */
    private var playerBackScreen: Screen = Screen.GRID

    /** Pantalla de la que se abrio AJUSTES (BACK vuelve ahi). */
    private var settingsBackScreen: Screen = Screen.PLAYER

    /** Pantalla de la que se abrio el emparejamiento (BACK vuelve ahi). */
    private var setupBackScreen: Screen = Screen.GRID

    /** Pantalla de la que se abrio el selector de calidad (BACK vuelve ahi). */
    private var qualityBackScreen: Screen = Screen.PLAYER

    /** Pantalla de la que se abrio el selector de listas (BACK vuelve ahi). */
    private var listsBackScreen: Screen = Screen.SETTINGS

    /** Pantalla de la que se abrio el menu contextual (BACK vuelve ahi). */
    private var menuBackScreen: Screen = Screen.GRID

    /** Server web embebido: lo enciende y apaga el usuario. */
    private var webServer: EmbeddedServer? = null

    /** Aviso de "presiona BACK otra vez para salir". */
    val exitHintVisible = MutableStateFlow(false)

    /** Job que oculta solo el aviso de salida. */
    private var exitHintJob: Job? = null

    val playbackState = MutableStateFlow("idle")
    val playbackError = MutableStateFlow("")
    val videoWidth = MutableStateFlow(0)
    val videoHeight = MutableStateFlow(0)

    private var bannerJob: Job? = null
    private var numberJob: Job? = null
    private var autoSkipJob: Job? = null
    private var consecutiveFailures = 0

    /** Reintentos por feed para errores transitorios de live. */
    private val retryCount = mutableMapOf<String, Int>()

    /** Canales por fuente: permite refrescar una playlist sin perder las demas. */
    private val channelsBySource = mutableMapOf<String, List<Channel>>()

    /** Serializa los cambios de catalogo para que dos refresh no se pisen. */
    private val refreshMutex = Mutex()

    /**
     * Feeds confirmados como ESTABLES: siguen reproduciendo despues de
     * [STABLE_PLAYING_MS] sin caerse.
     *
     * Un feed que levanta y se cae al segundo dispara "playing" un instante antes
     * de morir. Si al ver ese "playing" limpiaramos el contador de reintentos, el
     * feed fallaria para siempre en bucle. Por eso el contador de reintentos solo
     * se limpia cuando el canal aguanta lo suficiente reproduciendo de verdad.
     */
    private val stableVariants = mutableSetOf<String>()

    /** Cuando se vio "playing" por ultima vez (para medir que el canal se mantiene). */
    private var playingSince = 0L

    /** Job que verifica que el feed aguante lo suficiente. */
    private var stableJob: Job? = null

    /** Reintentos transitorios totales: corte de seguridad global. */
    private var transientRetries = 0

    /** Arranque del controller: sirve para medir fases (KzStartup). */
    private val bootAt = android.os.SystemClock.elapsedRealtime()

    init {
        // El player avisa por aca: los errores se muestran en pantalla y se salta al
        // canal siguiente, para no dejar al usuario frente a una pantalla negra.
        player.onStateChanged = { state ->
            if (state == "playing") {
                val id = currentVariant.value?.channelId
                playbackError.value = ""
                // Un "playing" instantaneo NO cuenta como canal sano: hay feeds que
                // levantan y se caen al segundo. El feed se confirma solo si aguanta
                // STABLE_PLAYING_MS reproduciendo (se verifica con un job).
                if (id != null) scheduleStableCheck(id)
            }
            playbackState.value = state
        }
        player.onError = { message -> onPlaybackError(message) }
        player.onVideoSize = { w, h ->
            videoWidth.value = w
            videoHeight.value = h
            // La resolución REAL del stream: el nombre del playlist suele mentir
            // (dice 1080p y entrega 480p).
            val id = currentVariant.value?.stateKey
            if (id != null && w > 0) {
                val value = w.toString() + "x" + h
                if (store.state.value.measuredResolution[id] != value) {
                    scope.launch {
                        store.update { it.copy(measuredResolution = it.measuredResolution + (id to value)) }
                    }
                }
            }
        }
    }

    /**
     * Programa la verificacion de estabilidad del feed que esta reproduciendo.
     *
     * No sirve con un evento "playing": un feed que levanta y se cae al segundo
     * dispara "playing" un instante antes de morir. Hay que esperar a que el
     * MISMO feed siga en reproduccion dentro de [STABLE_PLAYING_MS].
     */
    private fun scheduleStableCheck(id: String) {
        if (playingSince == 0L) playingSince = android.os.SystemClock.elapsedRealtime()
        stableJob?.cancel()
        stableJob = scope.launch {
            delay(STABLE_PLAYING_MS)
            // Solo se confirma si sigue siendo el mismo feed y sigue reproduciendo.
            if (currentVariant.value?.channelId != id) return@launch
            if (playbackState.value != "playing") return@launch
            confirmStablePlaying(id)
        }
    }

    /**
     * El feed lleva [STABLE_PLAYING_MS] reproduciendo: ahora si es un canal sano.
     *
     * Ahi se limpian los contadores de reintento y se resetea el de fallas seguidas.
     */
    private fun confirmStablePlaying(id: String) {
        val firstTime = stableVariants.add(id)
        consecutiveFailures = 0
        transientRetries = 0
        retryCount.remove(id)
        markVariant(id, ChannelVariant.STATUS_OK)
        if (firstTime) android.util.Log.i("KzIPTV", "canal estable: " + id)
    }

    /**
     * Un feed fallo. Primero se prueban las OTRAS VARIANTES de la misma señal
     * (mismo canal, otro proveedor/calidad); si no queda ninguna, se zaponea.
     */
    private fun onPlaybackError(message: String) {
        playbackState.value = "error"
        playbackError.value = message
        val group = currentGroup.value
        val variant = currentVariant.value
        // El feed se cayo: deja de ser estable hasta que vuelva a aguantar.
        stableJob?.cancel()
        if (variant != null) {
            stableVariants.remove(variant.channelId)
            playingSince = 0L
        }

        // Algunos feeds en vivo rotan la playlist antes de que el player la lea
        // (BEHIND_LIVE_WINDOW / IO_READ_POSITION_OUT_OF_RANGE): no son feeds muertos,
        // son reintentos. Re-preparar pide la playlist nueva y funciona.
        if (variant != null && group != null) {
            val attempts = retryCount[variant.channelId] ?: 0
            // Tope global: ningun feed puede reintentar para siempre.
            if (RetryPolicy.shouldRetry(
                    message, attempts, MAX_VARIANT_RETRIES,
                    transientRetries, MAX_TRANSIENT_RETRIES
                )
            ) {
                transientRetries++
                retryCount[variant.channelId] = attempts + 1
                android.util.Log.w(
                    "KzIPTV",
                    "reintento " + attempts + " de " + variant.name + " (error transitorio: " + message + ")"
                )
                setStatus("Reintentando ${group.name}\u2026")
                autoSkipJob?.cancel()
                autoSkipJob = scope.launch {
                    delay(1500)
                    play(group, variant, showBanner = false, switchToPlayer = false)
                }
                return
            }
        }

        // Aca el feed quedo muerto de verdad: recien ahora cuenta para el corte.
        consecutiveFailures++
        if (variant != null) markVariant(variant.channelId, ChannelVariant.STATUS_DEAD)
        val name = group?.name ?: "ese canal"
        android.util.Log.e(
            "KzIPTV",
            "error de reproduccion: " + name + " / " + (variant?.name ?: "-") + " -> " + message
        )
        setStatus("\u26A0 $name no se reproduce ($message)", sticky = true)
        autoSkipJob?.cancel()
        if (consecutiveFailures > MAX_AUTO_SKIP) {
            setStatus("\u26A0 Ningun canal responde. Revisa la conexion o la playlist.", sticky = true)
            return
        }
        val alternative = group?.variants?.firstOrNull {
            it.channelId != variant?.channelId && variantStatus(it.channelId) != ChannelVariant.STATUS_DEAD
        }
        autoSkipJob = scope.launch {
            if (alternative != null && group != null) {
                // misma señal, otro feed: entra mas rapido que saltar de canal
                delay(700)
                play(group, alternative, showBanner = false, switchToPlayer = false)
            } else {
                delay(1800)
                zap(1, switchToPlayer = false)
            }
        }
    }

    companion object {
        const val MAX_AUTO_SKIP = 6
        /** Reintentos del MISMO feed ante errores transitorios de live. */
        const val MAX_VARIANT_RETRIES = 2
        /** Cuanto debe durar el playback para dar el feed por estable. */
        const val STABLE_PLAYING_MS = 8_000L
        /** Tope global de reintentos transitorios antes de cortar. */
        const val MAX_TRANSIENT_RETRIES = 6
        /** Intentos por variante en la captura del mosaico. */
        const val MAX_VARIANT_ATTEMPTS = 2
        /** Canales por pagina del mosaico (grilla de 4 x 4 = 16). */
        const val MOSAIC_TILES = 16
        /** Cada cuanto se refresca el tile ENFOCADO (el que mira el usuario). */
        const val SURF_FOCUS_MS = SurfPlanner.FOCUS_MS
        /** Cada cuanto se refresca la pagina completa (rolling). */
        const val SURF_PAGE_MS = SurfPlanner.PAGE_MS
        /** Cuanto dura el aviso "presiona BACK otra vez para salir". */
        const val EXIT_HINT_MS = 3_000L
        /** Resolution del loop de surf (reactividad ante cambios de foco). */
        const val SURF_TICK_MS = 250L
        /** Columnas del mosaico. */
        const val MOSAIC_COLUMNS = 4
        /** Un feed marcado como muerto se reintenta pasado este tiempo. */
        const val DEAD_TTL_MS = 6 * 60 * 60 * 1000L
        const val BUNDLED_ID = "bundled-ar"
        const val BUNDLED_URL = "https://iptv-org.github.io/iptv/countries/ar.m3u"
    }

    fun bootstrap() {
        scope.launch {
            val persistedState = store.initialize()
            favorites.value = persistedState.favorites.toSet()
            val resolvedPairingCode = persistedState.pairingCode.ifBlank { generateCode() }
            pairingCode.value = resolvedPairingCode
            zapFavoritesOnly.value = persistedState.zapFavoritesOnly
            // Sin URL guardada se usa la guia publica del repo.
            epgUrl.value = persistedState.epgUrl.ifBlank { EpgSource.DEFAULT_PUBLIC_URL }
            val sources = persistedState.sources.ifEmpty {
                listOf(PersistedSource(id = BUNDLED_ID, name = "iptv-org \u00b7 Argentina", url = BUNDLED_URL, bundled = true))
            }

            // Cada fuente conserva su contenido anterior: una descarga fallida nunca vacia el catalogo.
            channelsBySource.clear()
            val loadedSources = mutableListOf<PersistedSource>()
            for (source in sources) {
                // Las listas apagadas no se descargan: se cargan cuando el usuario las activa.
                if (!source.enabled) continue
                val loaded = loadSourceChannels(source) ?: continue
                if (loaded.channels.isEmpty()) {
                    setStatus("La lista ${source.name} no tiene canales", sticky = true)
                    continue
                }
                channelsBySource[source.id] = loaded.channels
                loadedSources += if (!source.bundled && source.content.isBlank()) {
                    source.copy(
                        content = loaded.content,
                        channelCount = loaded.channels.size,
                        lastRefreshAt = System.currentTimeMillis(),
                    )
                } else {
                    source.copy(channelCount = loaded.channels.size)
                }
            }

            // El catalogo se arma solo con las playlists activas.
            // El filtro usa las fuentes de ESTA vuelta (que incluyen la embebida cuando
            // la base esta vacia): en una instalacion nueva el store todavia no la tiene.
            val all = activeChannels(PlaylistSelection.enabledIds(sources))
            applyChannels(all)
            store.update { current ->
                current.copy(
                    sources = PlaylistMerge.merge(current.sources, loadedSources),
                    pairingCode = resolvedPairingCode,
                )
            }
            refreshPlaylistItems()
            android.util.Log.i(
                "KzStartup",
                "playlist lista en " + (android.os.SystemClock.elapsedRealtime() - bootAt) +
                    " ms (" + all.size + " feeds / " + catalog.value.groups.size + " canales)"
            )
            loadEpgAsync()
            if (all.isEmpty()) {
                screen.value = Screen.SETUP
            } else {
                // Retoma el ultimo canal que se estaba viendo; si no, el primero disponible.
                val list = catalog.value.groups
                val lastGroupKey = store.state.value.lastGroupId
                val target = list.firstOrNull {
                    it.stateKey == lastGroupKey && it.variants.any { v -> v.playable }
                } ?: list.firstOrNull { it.variants.any { v -> v.playable } }
                if (target != null) play(target, showBanner = false)
            }
        }
    }

    /**
     * Construye el catalogo y conserva el estado por clave canonica. El agrupado (regex sobre
     * 200+ nombres) tarda ~1,5 s en la TV: va en IO para no congelar la UI.
     */
    private suspend fun applyChannels(channels: List<Channel>) {
        val next = withContext(Dispatchers.IO) {
            ChannelCatalog(channels, store.state.value.channelNumbers)
        }
        catalog.value = next
        store.update { current ->
            val migrated = ChannelStateMigration.migrate(current, next)
            current.copy(
                favorites = migrated.favorites,
                // Lo que no esta en el catalogo no se borra: puede ser una lista apagada
                // (vuelve intacta al activarla) o un canal que todavia no volvio.
                qualityPreference = current.qualityPreference + migrated.qualityPreference,
                variantStatus = current.variantStatus + migrated.variantStatus,
                measuredResolution = current.measuredResolution + migrated.measuredResolution,
                channelNumbers = current.channelNumbers + next.numberAssignments,
                lastGroupId = migrated.lastGroupId,
                lastChannelId = migrated.lastChannelId,
            )
        }
        favorites.value = store.state.value.favorites.toSet()
        setStatus("${catalog.value.groups.size} canales" +
            (if (catalog.value.variantCount > catalog.value.groups.size) " (${catalog.value.variantCount} feeds)" else "") +
            " cargados")
    }

    /**
     * Carga una playlist: del contenido cacheado si lo hay, del asset embebido
     * o de la URL. Devuelve null si no se pudo leer.
     */
    private suspend fun loadSourceChannels(source: PersistedSource): LoadedSource? =
        withContext(Dispatchers.IO) {
            val content = try {
                when {
                    source.content.isNotBlank() -> source.content
                    source.bundled -> repo.bundledAr()
                    source.url.isNotBlank() -> repo.fetch(source.url)
                    else -> null
                }
            } catch (e: Exception) {
                setStatus("No pude leer ${source.name}: ${e.message}", sticky = true)
                null
            } ?: return@withContext null
            LoadedSource(content, M3uParser.parse(content, source.id))
        }

    /** Los canales de las playlists activas: lo unico que entra al catalogo. */
    private fun activeChannels(
        enabledIds: Set<String> = PlaylistSelection.enabledIds(store.state.value.sources),
    ): List<Channel> = PlaylistSelection.enabledChannels(channelsBySource, enabledIds)

    /** Arma la vista de playlists del selector (con el conteo real de canales). */
    private fun refreshPlaylistItems() {
        playlists.value = store.state.value.sources.map { source ->
            PlaylistDto(
                id = source.id,
                name = source.name,
                url = source.url,
                channelCount = channelsBySource[source.id]?.size ?: source.channelCount,
                active = source.enabled,
                bundled = source.bundled,
                lastRefreshAt = source.lastRefreshAt,
            )
        }
    }

    /**
     * Actualiza todas las playlists que tienen URL.
     *
     * Cada fuente se descarga y parsea por separado: si una falla, conserva su catalogo
     * anterior. El catalogo nuevo se aplica de una sola vez y el estado del usuario
     * (favoritos, calidad, salud y numeros) se traduce a las claves canonicas.
     */
    suspend fun refreshPlaylists(): PlaylistRefreshResult {
        if (!refreshMutex.tryLock()) {
            return PlaylistRefreshResult(
                ok = false,
                message = "Ya hay una actualizacion de playlists en curso",
            )
        }
        playlistRefreshing.value = true
        try {
            val sources = store.state.value.sources
            // Solo se actualizan las playlists activas: lo apagado no se descarga.
            val refreshable = sources.filter { it.enabled && it.url.isNotBlank() }
            val skipped = sources.count { !it.enabled && it.url.isNotBlank() }
            if (refreshable.isEmpty()) {
                val message = if (skipped > 0) {
                    "No hay playlists activas por URL para actualizar ($skipped apagadas)"
                } else {
                    "No hay playlists por URL para actualizar"
                }
                setStatus(message, sticky = true)
                return PlaylistRefreshResult(ok = false, sourcesSkipped = skipped, message = message)
            }

            val oldCatalog = catalog.value
            val nextBySource = channelsBySource.toMutableMap()
            val updatedSources = sources.toMutableList()
            var refreshed = 0
            var failed = 0
            val errors = mutableListOf<String>()

            for (source in refreshable) {
                try {
                    val content = withContext(Dispatchers.IO) { repo.fetch(source.url) }
                    val channels = withContext(Dispatchers.IO) { M3uParser.parse(content, source.id) }
                    if (channels.isEmpty()) error("La lista no tiene canales")
                    nextBySource[source.id] = channels
                    val index = updatedSources.indexOfFirst { it.id == source.id }
                    val updated = source.copy(
                        content = content,
                        channelCount = channels.size,
                        lastRefreshAt = System.currentTimeMillis(),
                    )
                    if (index >= 0) updatedSources[index] = updated else updatedSources += updated
                    refreshed++
                } catch (e: Exception) {
                    failed++
                    errors += "${source.name}: ${e.message ?: "error"}"
                    android.util.Log.w("KzRefresh", "fallo al refrescar ${source.name}", e)
                }
            }

            if (refreshed == 0) {
                val message = if (errors.isEmpty()) {
                    "No se pudo actualizar ninguna playlist"
                } else {
                    errors.joinToString("; ")
                }
                setStatus(message, sticky = true)
                return PlaylistRefreshResult(
                    ok = false,
                    sourcesFailed = failed,
                    message = message,
                )
            }

            // El catalogo nuevo usa solo las playlists activas.
            val activeIds = PlaylistSelection.enabledIds(store.state.value.sources)
            val newChannels = nextBySource.filterKeys { it in activeIds }.values.flatten()
            val diff = PlaylistDiffer.diff(oldCatalog.all, newChannels)
            val newCatalog = withContext(Dispatchers.IO) {
                ChannelCatalog(newChannels, store.state.value.channelNumbers)
            }
            val urlChanges = changedVariantUrls(oldCatalog.all, newChannels)

            store.update { current ->
                val migrated = ChannelStateMigration.migrate(
                    state = current,
                    newCatalog = newCatalog,
                    oldCatalog = oldCatalog,
                    rekeyed = diff.rekeyed,
                )
                current.copy(
                    sources = PlaylistMerge.merge(current.sources, updatedSources),
                    favorites = migrated.favorites,
                    // El estado de lo que no aparece en el catalogo se conserva igual:
                    // puede ser una lista apagada o un canal que vuelve otro dia.
                    qualityPreference = current.qualityPreference + migrated.qualityPreference,
                    variantStatus = current.variantStatus + migrated.variantStatus - urlChanges,
                    measuredResolution = current.measuredResolution + migrated.measuredResolution - urlChanges,
                    channelNumbers = current.channelNumbers + newCatalog.numberAssignments,
                    lastGroupId = migrated.lastGroupId,
                    lastChannelId = migrated.lastChannelId,
                )
            }

            channelsBySource.clear()
            channelsBySource.putAll(nextBySource)
            catalog.value = newCatalog
            favorites.value = store.state.value.favorites.toSet()
            reconcileAfterRefresh(oldCatalog, newCatalog)
            retryCount.clear()
            stableVariants.clear()

            val message = buildString {
                append("Playlists actualizadas: +${diff.added.size} / -${diff.removed.size} / ~${diff.changed.size}")
                if (failed > 0) append(" (${failed} fallaron)")
                if (skipped > 0) append(" ($skipped apagadas omitidas)")
            }
            setStatus(message)
            refreshPlaylistItems()
            return PlaylistRefreshResult(
                ok = true,
                added = diff.added.size,
                removed = diff.removed.size,
                changed = diff.changed.size,
                sourcesRefreshed = refreshed,
                sourcesFailed = failed,
                sourcesSkipped = skipped,
                message = message,
            )
        } finally {
            playlistRefreshing.value = false
            refreshMutex.unlock()
        }
    }

    /** Variantes cuya URL cambio: su estado de salud anterior ya no aplica. */
    private fun changedVariantUrls(old: List<Channel>, new: List<Channel>): Set<String> {
        val oldUrls = old.associate { it.stateKey to it.url }
        return new.mapNotNull { channel ->
            val previous = oldUrls[channel.stateKey]
            if (previous != null && previous != channel.url) channel.stateKey else null
        }.toSet()
    }

    /** Reapunta foco y reproduccion actual al canal canonico equivalente. */
    private fun reconcileAfterRefresh(oldCatalog: ChannelCatalog, newCatalog: ChannelCatalog) {
        currentGroup.value = currentGroup.value?.stateKey?.let { newCatalog.groupByStateKey[it] }
        currentVariant.value = currentVariant.value?.stateKey?.let { key ->
            newCatalog.groups.asSequence()
                .flatMap { it.variants.asSequence() }
                .firstOrNull { it.stateKey == key }
        }
        focusedGroupId.value = focusedGroupId.value?.let { oldId ->
            val key = oldCatalog.groupById[oldId]?.stateKey
                ?: oldCatalog.variantStateKeyById[oldId]?.let { variantKey ->
                    oldCatalog.groupByVariantStateKey[variantKey]?.stateKey
                }
            key?.let { newCatalog.groupByStateKey[it]?.id } ?: oldId
        }
        focusedVariantId.value = focusedVariantId.value?.let { oldId ->
            val key = oldCatalog.variantStateKeyById[oldId]
            key?.let { variantKey ->
                newCatalog.groups.asSequence()
                    .flatMap { it.variants.asSequence() }
                    .firstOrNull { it.stateKey == variantKey }
                    ?.channelId
            } ?: oldId
        }
        searchResults.value = emptyList()
    }

    /**
     * Carga la guia EPG en segundo plano (no frena el arranque: el playback ya empezo).
     *
     * Si hay una URL remota configurada (la que publica el pipeline semanal) esa
     * manda: se usa el archivo de disco mientras no venza (24 h) y los programas
     * parseados que Room tiene cacheados. Sin URL, se usa el asset embebido.
     */
    private fun loadEpgAsync() {
        scope.launch { loadEpg() }
    }

    private suspend fun loadEpg() {
        // Sin URL configurada se usa la guia publica del repo; la embebida queda
        // como respaldo si la red falla (loadRemoteEpg ya la usa).
        val remoteUrl = store.state.value.epgUrl.trim().ifBlank { EpgSource.DEFAULT_PUBLIC_URL }
        loadRemoteEpg(remoteUrl, forceDownload = false)
    }

    /** Guia embebida en el APK: la que se usa si no hay URL remota configurada. */
    private suspend fun loadBundledEpg() {
        // Todo el parseo del XMLTV (~2 MB) va en IO: en el hilo principal provocaba ANR.
        var fromCache = false
        val programs = withContext(Dispatchers.IO) {
            val text = readBundledEpgText() ?: return@withContext emptyList()
            val fingerprint = EpgCache.fingerprint(text)
            val cached = store.cachedEpgPrograms(fingerprint)
            if (cached != null) {
                fromCache = true
                cached
            } else {
                EpgParser.parse(text).also { parsed ->
                    store.cacheEpgPrograms(fingerprint, parsed)
                }
            }
        }
        if (programs.isNotEmpty()) {
            epg.value = EpgIndex(programs)
            epgSource.value = "embebido"
            epgUpdatedAt.value = System.currentTimeMillis()
            setStatus("${catalog.value.groups.size} canales \u00b7 ${programs.size} programas EPG")
        }
        android.util.Log.i(
            "KzStartup",
            "EPG listo en " + (android.os.SystemClock.elapsedRealtime() - bootAt) +
                " ms (" + (if (fromCache) "cache" else "parse") + ")",
        )
    }

    /**
     * Guia remota: descarga (con TTL de 24 h), gunzip, parseo y cache en Room.
     *
     * Si algo falla se sigue con la guia que haya en pantalla y, si no hay ninguna,
     * con el asset embebido: un error de red no puede dejar la TV sin guia.
     */
    private suspend fun loadRemoteEpg(url: String, forceDownload: Boolean) {
        epgRefreshing.value = true
        val startedAt = android.os.SystemClock.elapsedRealtime()
        try {
            var fromDisk = false
            var fromRoom = false
            val programs = withContext(Dispatchers.IO) {
                val cachedBytes = if (forceDownload) null else epgRepo.readCachedBytes()
                val usableCache = cachedBytes?.takeIf {
                    !EpgSource.needsRefresh(epgRepo.cachedAt(), System.currentTimeMillis())
                }
                val data = if (usableCache != null) {
                    fromDisk = true
                    usableCache
                } else {
                    epgRepo.fetchBytes(url).also { epgRepo.writeCachedBytes(it) }
                }
                val text = EpgSource.decode(data) ?: error("el archivo no es un XMLTV valido")
                val fingerprint = EpgCache.fingerprint(text)
                val cachedPrograms = store.cachedEpgPrograms(fingerprint)
                if (cachedPrograms != null) {
                    fromRoom = true
                    cachedPrograms
                } else {
                    EpgParser.parse(text).also { parsed ->
                        store.cacheEpgPrograms(fingerprint, parsed)
                    }
                }
            }
            if (programs.isEmpty()) error("la guia no tiene programas")
            epg.value = EpgIndex(programs)
            epgSource.value = "remoto"
            epgUpdatedAt.value = System.currentTimeMillis()
            setStatus("Guia EPG actualizada: ${programs.size} programas")
            android.util.Log.i(
                "KzStartup",
                "EPG remoto listo en " + (android.os.SystemClock.elapsedRealtime() - startedAt) +
                    " ms (" + (if (fromDisk) "cache disco" else "descarga") +
                    (if (fromRoom) " + cache Room" else " + parseo") +
                    ", " + programs.size + " programas)",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(
                "KzStartup",
                "No se pudo actualizar la guia EPG remota: " + (e.message ?: e::class.java.simpleName),
            )
            if (epg.value.programs.isEmpty()) loadBundledEpg()
        } finally {
            epgRefreshing.value = false
        }
    }

    /**
     * Configura la URL de la guia EPG remota (la que publica el pipeline).
     * La llama el companion o la API: la guia se recarga en el acto.
     */
    fun setEpgUrl(url: String): Boolean {
        val clean = url.trim()
        if (clean.isNotBlank() && !EpgSource.isRemoteUrl(clean)) {
            setStatus("La URL de la guia EPG no es valida", sticky = true)
            return false
        }
        epgUrl.value = clean.ifBlank { EpgSource.DEFAULT_PUBLIC_URL }
        scope.launch {
            store.update { it.copy(epgUrl = clean) }
            epgRepo.clearCache()
            loadEpg()
        }
        return true
    }

    /** Fuerza la recarga de la guia (fila "Guia EPG" de Ajustes o comando del companion). */
    fun refreshEpg() {
        scope.launch {
            val url = store.state.value.epgUrl.trim().ifBlank { EpgSource.DEFAULT_PUBLIC_URL }
            loadRemoteEpg(url, forceDownload = true)
        }
    }

    /** Texto de la fila "Guia EPG" de Ajustes. */
    fun epgLabel(): String {
        if (epgRefreshing.value) return "Actualizando…"
        val programs = epg.value.programs.size
        return if (epgSource.value == "remoto") {
            "Remota · ${programs} programas"
        } else {
            "Del repo · ${programs} programas"
        }
    }

    /**
     * El EPG puede llegar como .gz (build local) o ya descomprimido por AGP al empaquetar:
     * dentro del APK el asset "epg/ar.xml.gz" queda como "assets/epg/ar.xml".
     * Se prueban las dos variantes y se detecta el gzip por sus magic bytes.
     */
    private fun readBundledEpgText(): String? {
        for (name in listOf("epg/ar.xml", "epg/ar.xml.gz")) {
            try {
                context.assets.open(name).use { input ->
                    val bytes = input.readBytes()
                    val gzip = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
                    val text = if (gzip) {
                        GZIPInputStream(bytes.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
                    } else {
                        bytes.toString(Charsets.UTF_8)
                    }
                    if (text.contains("<programme")) return text
                }
            } catch (e: Exception) {
                // siguiente candidato
            }
        }
        return null
    }

    /**
     * Ejecuta en el hilo principal. El ExoPlayer de Media3 exige que se lo use desde
     * el hilo que lo creo, y el server Ktor atiende los comandos en hilos de Netty:
     * sin este salto, /api/command devolvia 500 (IllegalStateException del player).
     */
    fun onMain(block: () -> Unit) {
        scope.launch { block() }
    }

    /** Reproduce un canal logico con la variante elegida (o la mejor disponible). */
    fun play(
        group: ChannelGroup,
        variant: ChannelVariant? = null,
        showBanner: Boolean = true,
        switchToPlayer: Boolean = true
    ) {
        val chosen = variant ?: chooseVariant(group)
        if (!chosen.playable) {
            setStatus("\u00ab${group.name}\u00bb no es reproducible en Android TV", sticky = true)
            return
        }
        player.play(chosen.toChannel())
        currentGroup.value = group
        currentVariant.value = chosen
        // Feed nuevo: arranca de cero la medicion de estabilidad.
        stableJob?.cancel()
        playingSince = 0L
        // Un reintento o fallback NO debe sacar al usuario de la grilla o del picker.
        if (switchToPlayer) screen.value = Screen.PLAYER
        playbackState.value = "buffering"
        playbackError.value = ""
        scope.launch {
            store.update { it.copy(lastGroupId = group.stateKey, lastChannelId = chosen.stateKey) }
            store.recordWatch(group.id, group.id, chosen.channelId)
        }
        if (showBanner) showBanner()
    }

    /** Elige la variante: la preferencia del usuario si sigue viva, si no la de mayor calidad. */
    fun chooseVariant(group: ChannelGroup): ChannelVariant {
        val preference = store.state.value.qualityPreference[group.stateKey]
        val alive = group.variants.filter { variantStatus(it.channelId) != ChannelVariant.STATUS_DEAD }
        val pool = if (alive.isNotEmpty()) alive else group.variants
        return pool.firstOrNull { it.stateKey == preference }
            ?: pool.maxByOrNull { ChannelGrouper.qualityRank(it.quality) }
            ?: group.variants.first()
    }

    fun zap(delta: Int, switchToPlayer: Boolean = true) {
        val list = activeList()
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == currentGroup.value?.id }
        val nextIndex = if (index < 0) 0 else ((index + delta) % list.size + list.size) % list.size
        play(list[nextIndex], switchToPlayer = switchToPlayer)
    }

    fun activeList(): List<ChannelGroup> {
        val base = if (zapFavoritesOnly.value) favoritesList() else catalog.value.groups.map { enrich(it) }
        val selected = category.value
        return if (selected != null) base.filter { selected in it.groups } else base
    }

    fun favoritesList(): List<ChannelGroup> =
        favorites.value.mapNotNull { id ->
            catalog.value.groupByStateKey[id]
                ?: catalog.value.groupByVariantStateKey[id]
                ?: catalog.value.groupById[id]
                ?: catalog.value.groupOf(id)
        }.distinctBy { it.stateKey }.sortedBy { it.number }.map { enrich(it) }

    /** Enriquece el grupo con la salud real de cada feed y la resolución medida. */
    private fun enrich(group: ChannelGroup): ChannelGroup {
        val measured = store.state.value.measuredResolution
        return group.copy(
            variants = group.variants.map { v ->
                v.copy(
                    status = variantStatus(v.channelId),
                    measured = measured[v.stateKey].orEmpty()
                )
            }
        )
    }

    fun isFavorite(group: ChannelGroup): Boolean = isFavorite(group, favorites.value)

    /**
     * Igual que [isFavorite] pero con el set ya leido: la grilla lo usa para
     * derivar el estado de cada tarjeta sin releer el flow por tarjeta.
     */
    fun isFavorite(group: ChannelGroup, favorites: Set<String>): Boolean =
        favorites.contains(group.stateKey) ||
            favorites.contains(group.id) ||
            group.variants.any { favorites.contains(it.stateKey) || favorites.contains(it.channelId) }

    /**
     * Los canales que muestra la grilla: la categoria elegida mas el filtro de
     * favoritos. La UI y el controller usan la MISMA lista: si no, el foco
     * apuntaria a un canal que no es el que se ve en pantalla.
     */
    fun visibleChannels(): List<ChannelGroup> {
        val list = catalog.value.inCategory(category.value)
        if (!favoritesOnly.value) return list
        val favs = favorites.value
        return list.filter { isFavorite(it, favs) }
    }

    fun toggleFavorite(groupId: String? = null) {
        val id = groupId ?: focusedGroupId.value ?: currentGroup.value?.id ?: return
        val group = catalog.value.groupByStateKey[id]
            ?: catalog.value.groupById[id]
            ?: catalog.value.groupOf(id)
            ?: catalog.value.groupByVariantStateKey[id]
            ?: return
        val wasFavorite = isFavorite(group)
        val updated = favorites.value.toMutableSet()
        if (wasFavorite) {
            updated.remove(group.stateKey)
            updated.remove(group.id)
            group.variants.forEach {
                updated.remove(it.stateKey)
                updated.remove(it.channelId)
            }
        } else {
            updated.add(group.stateKey)
        }
        favorites.value = updated
        scope.launch {
            store.update { it.copy(favorites = updated.toList()) }
        }
        setStatus(if (!wasFavorite) "\u2605 ${group.name} agregado a favoritos" else "${group.name} quitado de favoritos")
    }

    fun toggleZapMode() {
        zapFavoritesOnly.value = !zapFavoritesOnly.value
        scope.launch {
            store.update { it.copy(zapFavoritesOnly = zapFavoritesOnly.value) }
        }
        setStatus(if (zapFavoritesOnly.value) "Zap: solo FAVORITOS" else "Zap: todos los canales")
    }

    fun openGrid() {
        lastScreenChangeAt = android.os.SystemClock.elapsedRealtime()
        // El foco arranca en el canal que estas mirando: sin esto el OK no hace nada.
        val target = currentGroup.value?.id ?: catalog.value.groups.firstOrNull()?.id
        if (target != null) focusedGroupId.value = target
        // Al reabrir la grilla el foco vuelve a estar en las tarjetas, no en un chip.
        focusedCategory.value = null
        ensureGridFocus()
        screen.value = Screen.GRID
    }

    /**
     * BACK: un paso para atras.
     *
     * @return false si ya estamos en la pantalla raiz (la grilla): ahi no hay
     *         a donde volver y el BACK doble cierra la app.
     */
    fun back(): Boolean {
        android.util.Log.d(
            "KzGrid",
            "back en " + android.os.SystemClock.elapsedRealtime() +
                " (pantalla " + screen.value + ")"
        )
        focusedCategory.value = null
        val handled = when (screen.value) {
            Screen.SEARCH -> { screen.value = Screen.GRID; true }
            Screen.MOSAIC -> { closeMosaic(); true }
            Screen.QUALITY -> { screen.value = qualityBackScreen; true }
            Screen.SETUP -> { screen.value = setupBackScreen; true }
            Screen.SETTINGS -> { screen.value = settingsBackScreen; true }
            Screen.LISTS -> {
                listsRemovingId.value = null
                screen.value = listsBackScreen
                true
            }
            Screen.CHANNEL_MENU -> {
                screen.value = menuBackScreen
                true
            }
            Screen.NUMBER_PAD -> {
                // BACK borra el numero escrito; si no hay nada, vuelve a la grilla.
                if (keypadBuffer.value.isNotEmpty()) {
                    keypadBuffer.value = NumberPad.backspace(keypadBuffer.value)
                } else {
                    screen.value = Screen.GRID
                }
                true
            }
            // Si el canal se eligio desde una lista (grilla, busqueda o mosaico),
            // BACK devuelve a esa lista. Si se estaba zapeando, vuelve al video.
            Screen.PLAYER -> {
                android.util.Log.d(
                    "KzGrid",
                    "cambio a grilla en " + android.os.SystemClock.elapsedRealtime()
                )
                lastScreenChangeAt = android.os.SystemClock.elapsedRealtime()
                screen.value = playerBackScreen
                true
            }
            Screen.GRID -> {
                // Con la barra lateral abierta, BACK la cierra (no sale de la app).
                if (railActive.value) {
                    closeRail()
                    true
                } else {
                    false
                }
            }
            else -> { screen.value = Screen.GRID; true }
        }
        // Al volver a la grilla el foco tiene que estar en un canal real:
        // si no, el OK (y el OK largo) no tendrian a que accion.
        if (screen.value == Screen.GRID) ensureGridFocus()
        return handled
    }

    /**
     * Deja el foco de la grilla en un canal visible.
     *
     * Si el foco estaba vacio (por ejemplo al volver de Ajustes) o en un canal
     * que el filtro actual esconde, se mueve al primer canal visible.
     */
    private fun ensureGridFocus() {
        val visible = visibleChannels()
        if (visible.isEmpty()) return
        val current = focusedGroupId.value
        if (current == null || visible.none { it.id == current }) {
            focusedGroupId.value = visible.first().id
        }
    }

    /** Muestra el aviso "presiona BACK otra vez para salir". */
    fun showExitHint() {
        exitHintJob?.cancel()
        exitHintVisible.value = true
        exitHintJob = scope.launch {
            delay(EXIT_HINT_MS)
            exitHintVisible.value = false
        }
    }

    /** Oculta el aviso de salida (o se agota solo). */
    fun hideExitHint() {
        exitHintJob?.cancel()
        exitHintVisible.value = false
    }

    /**
     * Abre el mosaico: una grilla de canales con un frame congelado de cada uno.
     *
     * Cada tile se captura reproduciendo el canal unos segundos: el decoder de
     * hardware de la TV es UNICO, asi que se van haciendo de a uno y el grid se
     * llena solo. Los tiles son los canales que siguen al que estas mirando
     * (y los favoritos van primero).
     */
    fun openMosaic(startAt: ChannelGroup? = null) {
        val list = mosaicSource(startAt)
        if (list.isEmpty()) {
            setStatus("No hay canales para comparar")
            return
        }
        mosaicChannels.value = list
        mosaicFocus.value = 0
        mosaicTileErrors.value = emptyMap()
        mosaicThumbs.value = emptyMap()
        mosaicPlaying.value = false
        mosaicLiveIndex.value = -1
        mosaicRetries = 0
        mosaicFocusDirty = false
        mosaicCaptureJob?.cancel()
        mosaicCaptureJob = null
        screen.value = Screen.MOSAIC
        // El player principal suelta el decoder: en la TV hay UNO por hardware
        // y lo necesita la captura.
        player.suspendVideo()
        // El player de captura arranca limpio (puede quedar una captura a medias
        // de la apertura anterior).
        mosaic.stop()
        // El grid se va llenando solo, canal por canal.
        startMosaicSweep()
    }

    /**
     * Cierra el mosaico.
     *
     * OJO: el player de captura NO se libera, solo se detiene. Si se liberara,
     * la segunda vez que se abre el mosaico en la misma sesion el player queda
     * muerto ("Handler on a dead thread") y no se captura ningun canal.
     */
    fun closeMosaic() {
        mosaicSweepJob?.cancel()
        mosaicSweepJob = null
        mosaicCaptureJob?.cancel()
        mosaicCaptureJob = null
        mosaic.stop()
        mosaicFocusDirty = false
        mosaicPlaying.value = false
        mosaicLiveIndex.value = -1
        mosaicChannels.value = emptyList()
        mosaicTileErrors.value = emptyMap()
        mosaicThumbs.value = emptyMap()
        mosaicFocus.value = 0
        screen.value = Screen.GRID
    }

    /**
     * Los canales a comparar.
     *
     * Empieza por los favoritos (son los que el usuario ya eligio) y completa con
     * los canales que siguen al que esta mirando, hasta llenar los tiles.
     */
    private fun mosaicSource(startAt: ChannelGroup? = null): List<ChannelGroup> {
        val active = activeList()
        if (active.isEmpty()) return emptyList()
        val current = startAt ?: currentGroup.value
        val start = active.indexOfFirst { it.id == current?.id }
        // Rotacion: el mosaico arranca por el canal que estas mirando.
        val rotated = if (start >= 0) {
            active.subList(start, active.size) + active.subList(0, start)
        } else {
            active
        }
        val favs = favoritesList()
        val picked = LinkedHashMap<String, ChannelGroup>()
        // Primero los favoritos, despues los que siguen al actual. Pero solo
        // los que tienen AL MENOS un feed vivo: un mosaico lleno de canales
        // caidos no sirve para comparar nada.
        favs.filter { hasLiveFeed(it) }.forEach { picked.putIfAbsent(it.id, it) }
        rotated.filter { hasLiveFeed(it) }.forEach { picked.putIfAbsent(it.id, it) }
        // Si no alcanzan, se completa con los que quedan (se veran sin senal).
        favs.forEach { picked.putIfAbsent(it.id, it) }
        rotated.forEach { picked.putIfAbsent(it.id, it) }
        return picked.values.take(MOSAIC_TILES)
    }

    /** El canal tiene algun feed que no sabemos que este muerto? */
    private fun hasLiveFeed(group: ChannelGroup): Boolean =
        group.variants.any {
            it.playable && variantStatus(it.channelId) != ChannelVariant.STATUS_DEAD
        }

    /**
     * Captura el frame de un canal y lo deja congelado en su tile.
     *
     * Es una operacion en cadena: reproduce el canal, espera a que llegue el
     * primer frame, lo congela y libera el decoder para el siguiente canal.
     *
     * Prioridad de variantes: primero las que ya se saben sanas (estado "ok"),
     * despues las desconocidas. Las marcadas como muertas no se prueban: se sabe
     * que no funcionan y el decoder de la TV es el recurso escaso.
     */
    private suspend fun captureTile(index: Int) {
        val group = mosaicChannels.value.getOrNull(index) ?: return
        val variants = group.variants
            .filter { it.playable }
            .filter { variantStatus(it.channelId) != ChannelVariant.STATUS_DEAD }
            .sortedWith(
                compareBy<ChannelVariant> {
                    if (variantStatus(it.channelId) == ChannelVariant.STATUS_OK) 0 else 1
                }.thenBy { ChannelGrouper.qualityRank(it.quality) }
            )
        if (variants.isEmpty()) {
            // Todos los feeds del canal estan caidos: no se gasta el decoder.
            // El tile conserva la miniatura anterior (si tenia).
            return
        }

        var lastError = "sin senal"
        for (variant in variants) {
            var attempt = 0
            while (true) {
                // La UI crea el TextureView de este tile (y solo de este).
                mosaicPlaying.value = false
                mosaicLiveIndex.value = index
                // Se espera a que la vista exista y a que el decoder entregue un frame.
                delay(350)
                val result = mosaic.capture(variant.toChannel())
                if (result.played) {
                    // El feed arranco: queda marcado como bueno para las proximas.
                    markVariant(variant.channelId, ChannelVariant.STATUS_OK)
                    mosaicPlaying.value = true
                }
                if (result.bitmap != null) {
                    mosaicThumbs.value = mosaicThumbs.value + (index to result.bitmap)
                    mosaicTileErrors.value = mosaicTileErrors.value - index
                    mosaicLiveIndex.value = -1
                    mosaicPlaying.value = false
                    // El presupuesto de reintentos se repone: algo anda bien.
                    mosaicRetries = 0
                    return
                }
                lastError = result.error ?: "sin senal"
                // Un error transitorio (datos viejos, timeout) se reintenta: el
                // feed puede volver. Uno permanente (403/404) se marca como muerto
                // y se pasa a la siguiente variante del canal.
                if (RetryPolicy.isTransient(lastError) &&
                    attempt < MAX_VARIANT_ATTEMPTS &&
                    mosaicRetries < MAX_TRANSIENT_RETRIES
                ) {
                    attempt++
                    mosaicRetries++
                    // El feed suele estar en el borde "live": reconectar
                    // demasiado rapido vuelve a fallar.
                    delay(1_500)
                    continue
                }
                break
            }
            // Un feed permanentemente roto no se vuelve a elegir como preview.
            if (!RetryPolicy.isTransient(lastError)) {
                markVariant(variant.channelId, ChannelVariant.STATUS_DEAD)
            }
        }

        // No hay mas variantes: el tile conserva la miniatura anterior (si
        // habia) y queda marcado como caido.
        mosaicLiveIndex.value = -1
        mosaicPlaying.value = false
        mosaicTileErrors.value = mosaicTileErrors.value + (index to lastError)
    }

    /**
     * El "surf": el grid se llena y despues se refresca solo.
     *
     * - El tile ENFOCADO (el que esta mirando el usuario) se vuelve a capturar
     *   cada SURF_FOCUS_MS: el canal se despierta unos segundos y vuelve a
     *   congelarse.
     * - El resto de la pagina se refresca en rolling: una vuelta completa cada
     *   SURF_PAGE_MS.
     *
     * En la TV hay UN solo decoder de video: las capturas son SIEMPRE de a una.
     * Si el usuario mueve el foco, la captura en curso se corta y se hace la del
     * canal enfocado.
     */
    private fun startMosaicSweep() {
        mosaicSweepJob?.cancel()
        mosaicCaptureJob?.cancel()
        mosaicSweepJob = scope.launch {
            val total = mosaicChannels.value.size
            if (total == 0) return@launch
            // El ritmo lo decide SurfPlanner (logica pura, testeada).
            var plan = SurfPlanner.Plan(-1, 0L, 0L, 0, false)

            while (isActive && screen.value == Screen.MOSAIC) {
                val focus = mosaicFocus.value.coerceIn(0, total - 1)
                plan = SurfPlanner.plan(
                    total = total,
                    focus = focus,
                    now = System.currentTimeMillis(),
                    focusDirty = mosaicFocusDirty,
                    lastFocusAt = plan.lastFocusAt,
                    lastPageAt = plan.lastPageAt,
                    nextIndex = plan.nextIndex,
                    pagePending = plan.pagePending
                )
                val target = plan.target
                if (target < 0) {
                    // Nada vencido todavia: se espera un toque.
                    delay(SURF_TICK_MS)
                    continue
                }
                if (target == focus) mosaicFocusDirty = false

                val job = scope.launch {
                    try {
                        captureTile(target)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e(
                            "KzIPTV",
                            "fallo la captura del tile " + target,
                            e
                        )
                    }
                }
                mosaicCaptureJob = job
                job.join()
                if (target == focus) {
                    plan = plan.copy(lastFocusAt = System.currentTimeMillis())
                }
            }
        }
    }

    /** Mueve el foco entre los tiles del mosaico (grilla de 2 columnas). */
    private fun moveMosaicFocus(code: Int) {
        val n = mosaicChannels.value.size
        if (n == 0) return
        val columns = MOSAIC_COLUMNS
        var index = mosaicFocus.value
        when (code) {
            RemoteKeys.DPAD_LEFT -> if (index % columns > 0) index--
            RemoteKeys.DPAD_RIGHT -> if (index % columns < columns - 1 && index + 1 < n) index++
            RemoteKeys.DPAD_UP -> if (index >= columns) index -= columns
            RemoteKeys.DPAD_DOWN -> if (index + columns < n) index += columns
        }
        index = index.coerceIn(0, n - 1)
        if (index != mosaicFocus.value) {
            mosaicFocus.value = index
            // La captura en curso se corta: ahora interesa el canal enfocado.
            mosaicCaptureJob?.cancel()
            mosaicCaptureJob = null
            // El loop de surf captura este tile apenas puede (no hay que esperar
            // a que termine la vuelta de la pagina).
            mosaicFocusDirty = true
        }
    }
    /**
     * Libera los recursos del controller (cuando la activity se destruye).
     *
     * El player de captura se suelta recien aca: durante la sesion se reutiliza
     * porque el mosaico se abre y se cierra varias veces.
     */
    fun release() {
        mosaicSweepJob?.cancel()
        mosaicCaptureJob?.cancel()
        exitHintJob?.cancel()
        mosaic.release()
        stopWebServer(announce = false)
    }

    // ---------------------------------------------------------------------
    // Server web: apagado por defecto, se enciende desde AJUSTES
    // ---------------------------------------------------------------------

    /**
     * Enciende el server web. No arranca solo: el usuario lo pide desde
     * AJUSTES (o con OK en la pantalla de emparejamiento).
     */
    fun startWebServer() {
        if (serverRunning.value || serverBusy.value) return
        serverBusy.value = true
        Thread({
            try {
                // Netty carga muchas clases al arrancar: si eso corre en el main
                // thread, la TV tira ANR por timeout de input.
                runBlocking { store.initialize() }
                val srv = EmbeddedServer(context, this, store)
                val port = srv.start()
                if (port > 0) {
                    webServer = srv
                    serverRunning.value = true
                    setStatus("Servidor web encendido en ${serverUrl.value}")
                } else {
                    setStatus("No se pudo prender el servidor web", sticky = true)
                }
            } catch (e: Exception) {
                android.util.Log.e("KzServer", "no se pudo prender el server", e)
                setStatus("No se pudo prender el servidor web", sticky = true)
            } finally {
                serverBusy.value = false
            }
        }, "kziptv-server").apply { isDaemon = true }.start()
    }

    /** Apaga el server web (deja de estar accesible desde el celular). */
    fun stopWebServer(announce: Boolean = true) {
        webServer?.stop()
        webServer = null
        serverRunning.value = false
        setServerUrl("")
        if (announce) setStatus("Servidor web apagado")
    }

    /** Prende/apaga el server web. */
    fun toggleWebServer() {
        if (serverRunning.value) stopWebServer() else startWebServer()
    }

    // ---------------------------------------------------------------------
    // Pantalla de ajustes
    // ---------------------------------------------------------------------

    /** Abre la pantalla de ajustes (BACK vuelve a la pantalla de la que se salio). */
    fun openSettings() {
        settingsBackScreen = screen.value
        settingsFocus.value = 0
        screen.value = Screen.SETTINGS
    }

    /** Mueve el foco en la pantalla de ajustes. */
    private fun moveSettingsFocus(code: Int) {
        val count = Setting.entries.size
        val index = settingsFocus.value.coerceIn(0, count - 1)
        when (code) {
            RemoteKeys.DPAD_DOWN -> settingsFocus.value = (index + 1) % count
            RemoteKeys.DPAD_UP -> settingsFocus.value = (index - 1 + count) % count
        }
    }

    /** Activa la fila enfocada de la pantalla de ajustes. */
    private fun activateSettingsRow() {
        val row = Setting.entries[settingsFocus.value.coerceIn(0, Setting.entries.size - 1)]
        when (row) {
            Setting.SERVER -> toggleWebServer()
            Setting.PAIRING -> openPairing()
            Setting.ZAP -> toggleZapMode()
            Setting.VIDEO -> cycleVideoFit()
            Setting.EPG -> refreshEpg()
            Setting.PLAYLIST -> scope.launch { refreshPlaylists() }
            Setting.LISTS -> openLists()
        }
    }
    // ---------------------------------------------------------------------
    // Selector de lista
    // ---------------------------------------------------------------------

    /** Abre el selector de playlists (BACK vuelve a [from]). */
    fun openLists(from: Screen = Screen.SETTINGS) {
        listsBackScreen = from
        listsFocus.value = 0
        listsRemovingId.value = null
        screen.value = Screen.LISTS
    }

    /** Mueve el foco en la pantalla del selector de lista. */
    private fun moveListsFocus(code: Int) {
        val count = playlists.value.size
        if (count == 0) return
        val index = listsFocus.value.coerceIn(0, count - 1)
        when (code) {
            RemoteKeys.DPAD_DOWN -> listsFocus.value = (index + 1) % count
            RemoteKeys.DPAD_UP -> listsFocus.value = (index - 1 + count) % count
        }
    }

    /** Activa o desactiva la lista enfocada (OK corto). */
    private fun activateListsRow() {
        val item = playlists.value.getOrNull(listsFocus.value) ?: return
        listsRemovingId.value = null
        togglePlaylist(item.id)
    }

    /** Pide confirmacion para quitar la lista enfocada (OK largo, dos veces). */
    private fun requestRemoveList() {
        val item = playlists.value.getOrNull(listsFocus.value) ?: return
        if (item.bundled) {
            setStatus("La lista embebida no se puede quitar", sticky = true)
            return
        }
        if (listsRemovingId.value == item.id) {
            listsRemovingId.value = null
            removePlaylist(item.id)
            return
        }
        listsRemovingId.value = item.id
        setStatus("OK largo otra vez para quitar ${item.name}")
    }

    // ---------------------------------------------------------------------
    // Barra lateral y menu contextual (control remoto sin teclas de color)
    // ---------------------------------------------------------------------

    /** Abre la barra lateral (LEFT en la primera columna de la grilla). */
    fun openRail() {
        railActive.value = true
    }

    /** Cierra la barra lateral (RIGHT o BACK). */
    fun closeRail() {
        railActive.value = false
    }

    /** Mueve el foco dentro de la barra lateral. */
    private fun moveRailFocus(code: Int) {
        val count = Rail.entries.size
        when (code) {
            RemoteKeys.DPAD_UP -> railFocus.value = (railFocus.value - 1 + count) % count
            RemoteKeys.DPAD_DOWN -> railFocus.value = (railFocus.value + 1) % count
            // RIGHT: de vuelta a la grilla.
            RemoteKeys.DPAD_RIGHT -> closeRail()
        }
    }

    /** Ejecuta la seccion enfocada de la barra lateral. */
    private fun activateRailItem() {
        when (Rail.entries[railFocus.value.coerceIn(0, Rail.entries.size - 1)]) {
            Rail.CANALES -> {
                favoritesOnly.value = false
                category.value = null
                focusedCategory.value = null
                closeRail()
                setStatus("Todos los canales")
            }
            Rail.FAVORITOS -> {
                favoritesOnly.value = !favoritesOnly.value
                // El foco tiene que seguir apuntando a un canal visible.
                val visible = visibleChannels()
                val current = focusedGroupId.value
                if (visible.isNotEmpty() && visible.none { it.id == current }) {
                    focusedGroupId.value = visible.first().id
                }
                // La barra se cierra: el filtro se ve en la grilla de al lado.
                closeRail()
                setStatus(if (favoritesOnly.value) "Solo favoritos" else "Todos los canales")
            }
            Rail.ZAP -> {
                closeRail()
                toggleZapMode()
            }
            Rail.BUSCAR -> {
                closeRail()
                openSearch()
            }
            Rail.LISTAS -> {
                closeRail()
                openLists(Screen.GRID)
            }
            Rail.COMPARAR -> {
                closeRail()
                openMosaic()
            }
            Rail.AJUSTES -> {
                closeRail()
                openSettings()
            }
            Rail.IR_A_CANAL -> {
                closeRail()
                openNumberPad()
            }
        }
    }

    /** Abre el menu contextual de un canal (OK largo). */
    fun openChannelMenu(groupId: String?, from: Screen) {
        if (groupId == null) return
        channelMenuTarget.value = groupId
        channelMenuCanPlay.value = from != Screen.PLAYER
        channelMenuFromPlayer.value = from == Screen.PLAYER
        channelMenuFocus.value = 0
        menuBackScreen = from
        screen.value = Screen.CHANNEL_MENU
    }

    /** Las acciones del menu contextual (sin "Reproducir" si ya se esta viendo). */
    fun channelMenuItems(): List<ChannelAction> =
        if (channelMenuCanPlay.value) {
            listOf(
                ChannelAction.REPRODUCIR,
                ChannelAction.FAVORITO,
                ChannelAction.CALIDAD,
                ChannelAction.COMPARAR
            )
        } else {
            listOf(ChannelAction.FAVORITO, ChannelAction.CALIDAD, ChannelAction.COMPARAR)
        }

    /** El canal del menu contextual (con sus datos). */
    fun channelMenuGroup(): ChannelGroup? {
        val id = channelMenuTarget.value ?: return null
        return catalog.value.groupById[id] ?: catalog.value.groupOf(id)
    }

    /** Mueve el foco dentro del menu contextual. */
    private fun moveChannelMenuFocus(code: Int) {
        val count = channelMenuItems().size
        if (count == 0) return
        val index = channelMenuFocus.value.coerceIn(0, count - 1)
        when (code) {
            RemoteKeys.DPAD_DOWN -> channelMenuFocus.value = (index + 1) % count
            RemoteKeys.DPAD_UP -> channelMenuFocus.value = (index - 1 + count) % count
        }
    }

    /** Ejecuta la accion enfocada del menu contextual. */
    private fun activateChannelMenuItem() {
        val group = channelMenuGroup() ?: return
        val items = channelMenuItems()
        when (items[channelMenuFocus.value.coerceIn(0, items.size - 1)]) {
            ChannelAction.REPRODUCIR -> {
                closeChannelMenu()
                playerBackScreen = Screen.GRID
                play(group)
            }
            // El menu queda abierto: se ve como cambio el favorito.
            ChannelAction.FAVORITO -> toggleFavorite(group.id)
            ChannelAction.CALIDAD -> {
                closeChannelMenu()
                openQuality(group)
            }
            ChannelAction.COMPARAR -> {
                closeChannelMenu()
                openMosaic(group)
            }
        }
    }

    /** Cierra el menu contextual y vuelve a donde se abrio. */
    private fun closeChannelMenu() {
        channelMenuTarget.value = null
        screen.value = menuBackScreen
    }

    // ---------------------------------------------------------------------
    // Teclado numerico en pantalla (ir a canal # sin teclas de numeros)
    // ---------------------------------------------------------------------

    /** Abre el teclado numerico en pantalla. */
    fun openNumberPad() {
        keypadFocus.value = 0
        keypadBuffer.value = ""
        screen.value = Screen.NUMBER_PAD
    }

    /** Mueve el foco dentro del teclado (3 columnas x 4 filas). */
    private fun moveKeypadFocus(code: Int) {
        val move = GridNavigator.move(keypadFocus.value, NumberPad.CELLS, NumberPad.COLUMNS, code)
        keypadFocus.value = move.index
    }

    /** Presiona la celda enfocada del teclado. */
    private fun pressKeypad() {
        val index = keypadFocus.value
        when {
            NumberPad.isDelete(index) ->
                keypadBuffer.value = NumberPad.backspace(keypadBuffer.value)
            NumberPad.isGo(index) -> confirmKeypadNumber()
            else -> NumberPad.digitAt(index)?.let {
                keypadBuffer.value = NumberPad.append(keypadBuffer.value, it)
            }
        }
    }

    /** El canal que corresponde al numero escrito (null si no existe). */
    fun keypadMatch(): ChannelGroup? {
        val buffer = keypadBuffer.value
        if (buffer.isBlank()) return null
        return NumberEntry.resolve(catalog.value, buffer)
    }

    /** Reproduce el canal del numero escrito (la celda "ir"). */
    private fun confirmKeypadNumber() {
        val buffer = keypadBuffer.value
        if (buffer.isBlank()) {
            setStatus("Escribi un numero de canal", sticky = true)
            return
        }
        val group = NumberEntry.resolve(catalog.value, buffer)
        keypadBuffer.value = ""
        screen.value = Screen.GRID
        if (group == null) {
            setStatus("No hay ningun canal con el numero $buffer", sticky = true)
            return
        }
        focusedGroupId.value = group.id
        playerBackScreen = Screen.GRID
        play(group)
    }

    /**
     * Activa o desactiva una playlist y rearma el catalogo con las activas.
     *
     * Apagar NO borra el estado del usuario (favoritos, calidad elegida, salud de
     * cada feed y numeros de canal): todo eso vive por clave canonica y vuelve
     * intacto al reactivarla.
     *
     * @return un mensaje para la pantalla o para la API.
     */
    suspend fun setPlaylistEnabled(id: String, enabled: Boolean): String = refreshMutex.withLock {
        val source = store.state.value.sources.firstOrNull { it.id == id }
        if (source == null) {
            setStatus("No existe esa lista", sticky = true)
            return@withLock "No existe esa lista"
        }
        if (source.enabled == enabled) {
            val message = source.name + " ya est\u00e1 " + if (enabled) "activa" else "apagada"
            setStatus(message)
            return@withLock message
        }
        if (!enabled && !PlaylistSelection.canDeactivate(store.state.value.sources, id)) {
            // La ultima lista activa no se puede apagar: la TV se quedaria sin nada.
            setStatus("No pod\u00e9s apagar la \u00faltima lista activa", sticky = true)
            return@withLock "No pod\u00e9s apagar la \u00faltima lista activa"
        }
        listsBusy.value = true
        try {
            var loaded: LoadedSource? = null
            if (enabled) {
                // Una lista apagada no se descarga al arrancar: se carga ahora.
                if (!channelsBySource.containsKey(id)) {
                    setStatus("Descargando ${source.name}…")
                    loaded = loadSourceChannels(source)
                    if (loaded == null || loaded.channels.isEmpty()) {
                        setStatus("No se pudo cargar ${source.name}", sticky = true)
                        return@withLock "No se pudo cargar ${source.name}"
                    }
                    channelsBySource[id] = loaded.channels
                }
            }
            // El contenido recien bajado se guarda: la proxima vez no se descarga.
            val fresh = loaded
            if (fresh != null) {
                store.update { current ->
                    current.copy(
                        sources = current.sources.map {
                            if (it.id == id) {
                                it.copy(
                                    content = fresh.content,
                                    channelCount = fresh.channels.size,
                                    lastRefreshAt = System.currentTimeMillis(),
                                )
                            } else {
                                it
                            }
                        },
                    )
                }
            }
            store.update { current ->
                current.copy(
                    sources = PlaylistSelection.setEnabled(current.sources, id, enabled),
                )
            }
            rebuildCatalogFromActiveLists()
            val count = catalog.value.groups.size
            val message = (if (enabled) "Lista activada: " else "Lista apagada: ") +
                source.name + " (" + count + " canales)"
            setStatus(message)
            message
        } finally {
            listsBusy.value = false
            refreshPlaylistItems()
        }
    }

    /** Activa o desactiva una lista desde la API (no espera el resultado). */
    fun requestPlaylistEnabled(id: String, enabled: Boolean) {
        scope.launch { setPlaylistEnabled(id, enabled) }
    }

    /** Da vuelta el interruptor de una lista (lo que hace OK en el selector). */
    fun togglePlaylist(id: String) {
        scope.launch {
            val source = store.state.value.sources.firstOrNull { it.id == id } ?: return@launch
            setPlaylistEnabled(id, !source.enabled)
        }
    }

    /**
     * Rearma el catalogo con las playlists activas SIN tocar el estado del usuario.
     *
     * A diferencia del refresh (donde un canal puede desaparecer de verdad), aca
     * solo se enciende o apaga una lista.
     */
    private suspend fun rebuildCatalogFromActiveLists() {
        val oldCatalog = catalog.value
        val channels = activeChannels()
        val next = withContext(Dispatchers.IO) {
            ChannelCatalog(channels, store.state.value.channelNumbers)
        }
        catalog.value = next
        store.update { current ->
            current.copy(channelNumbers = current.channelNumbers + next.numberAssignments)
        }
        favorites.value = store.state.value.favorites.toSet()
        reconcileAfterRefresh(oldCatalog, next)
        // Si la categoria elegida ya no existe, se vuelve a "Todos".
        if (category.value != null && next.categories.none { it == category.value }) {
            category.value = null
        }
        // El foco tiene que seguir apuntando a un canal que exista.
        val focused = focusedGroupId.value
        if (focused == null || next.groupById[focused] == null) {
            focusedGroupId.value = next.groups.firstOrNull()?.id
        }
    }

    /** Quita una playlist (la embebida no se puede quitar). */
    fun removePlaylist(id: String) {
        scope.launch {
            refreshMutex.withLock {
                val source = store.state.value.sources.firstOrNull { it.id == id } ?: return@withLock
                if (source.bundled) {
                    setStatus("La lista embebida no se puede quitar", sticky = true)
                    return@withLock
                }
                if (source.enabled && !PlaylistSelection.canDeactivate(store.state.value.sources, id)) {
                    setStatus("No pod\u00e9s quitar la \u00faltima lista activa", sticky = true)
                    return@withLock
                }
                channelsBySource.remove(id)
                store.update { current ->
                    current.copy(sources = current.sources.filterNot { it.id == id })
                }
                rebuildCatalogFromActiveLists()
                refreshPlaylistItems()
                setStatus("Lista quitada: ${source.name}")
            }
        }
    }

    /** Texto de la fila "Listas de canales" de Ajustes. */
    fun listsLabel(): String {
        val total = playlists.value.size
        val active = playlists.value.count { it.active }
        return "$active de $total activas"
    }


    /** Cambia el encuadre del video: ajustar -> rellenar -> ampliar. */
    fun cycleVideoFit() {
        videoFitMode.value = (videoFitMode.value + 1) % 3
        setStatus("Video: " + videoFitLabel(videoFitMode.value))
    }

    /** Nombre del modo de encuadre del video. */
    fun videoFitLabel(mode: Int = videoFitMode.value): String = when (mode) {
        0 -> "Ajustar"
        1 -> "Rellenar"
        else -> "Ampliar"
    }

    fun openPairing() {
        // Si el emparejamiento se abrio desde AJUSTES, BACK vuelve a AJUSTES.
        setupBackScreen = screen.value
        screen.value = Screen.SETUP
    }

    /** Abre el selector de calidad de un canal (o del canal enfocado). */
    fun openQuality(group: ChannelGroup? = null) {
        val target = group
            ?: focusedGroupId.value?.let { catalog.value.groupById[it] }
            ?: currentGroup.value
        if (target == null) {
            setStatus("No hay canal seleccionado")
            return
        }
        qualityBackScreen = screen.value
        qualityTarget.value = enrich(target)
        val active = currentVariant.value?.channelId
        focusedVariantId.value = if (active != null && target.variants.any { it.channelId == active }) {
            active
        } else {
            target.variants.firstOrNull()?.channelId
        }
        screen.value = Screen.QUALITY
    }

    /** Reproduce una variante puntual (desde el companion) y la deja como preferencia. */
    fun playVariant(groupId: String, variantId: String) {
        val group = catalog.value.resolve(groupId) ?: return
        val variant = group.variants.firstOrNull {
            it.channelId == variantId || it.stateKey == variantId
        } ?: return
        scope.launch {
            store.update {
                it.copy(qualityPreference = it.qualityPreference + (group.stateKey to variant.stateKey))
            }
        }
        markVariant(variantId, ChannelVariant.STATUS_UNKNOWN)
        screen.value = Screen.PLAYER
        play(group, variant)
    }

    /** Elige una variante: se reproduce y queda como preferencia para ese canal. */
    fun selectVariant(variantId: String) {
        val target = qualityTarget.value ?: currentGroup.value ?: return
        val variant = target.variants.firstOrNull {
            it.channelId == variantId || it.stateKey == variantId
        } ?: return
        scope.launch {
            store.update {
                it.copy(qualityPreference = it.qualityPreference + (target.stateKey to variant.stateKey))
            }
        }
        markVariant(variantId, ChannelVariant.STATUS_UNKNOWN)
        screen.value = Screen.PLAYER
        play(target, variant)
    }

    fun onOk(longPress: Boolean) {
        if (longPress) {
            when (screen.value) {
                // OK largo: menu contextual del canal (favorito, calidad, comparar).
                Screen.GRID ->
                    // Con la barra abierta el OK largo no hace nada: la seccion
                    // enfocada se activa con un OK corto.
                    if (!railActive.value && focusedCategory.value == null) {
                        openChannelMenu(focusedGroupId.value, Screen.GRID)
                    }
                Screen.PLAYER -> openChannelMenu(currentGroup.value?.id, Screen.PLAYER)
                // En el selector de lista el OK largo quita la lista (dos veces).
                Screen.LISTS -> requestRemoveList()
                else -> toggleFavorite()
            }
            return
        }
        when (screen.value) {
            Screen.GRID -> {
                if (railActive.value) {
                    activateRailItem()
                    return
                }
                val chip = focusedCategory.value
                if (chip != null) {
                    setCategory(if (chip.isEmpty()) null else chip)
                    focusGrid()
                } else {
                    focusedGroupId.value?.let { id ->
                        catalog.value.groupById[id]?.let {
                            playerBackScreen = Screen.GRID
                            play(it)
                        }
                    }
                }
            }
            Screen.PLAYER -> openGrid()
            Screen.QUALITY -> focusedVariantId.value?.let { selectVariant(it) }
            Screen.SEARCH ->
                if (searchFieldFocused.value) {
                    // El IME del sistema tiene el foco: no hacemos nada aca.
                } else if (focusedSearchId.value == null) {
                    // Foco en el campo: abrimos el teclado DEL SISTEMA.
                    searchFieldRequest.value = searchFieldRequest.value + 1
                } else {
                    playFocusedSearchResult()
                }
            Screen.MOSAIC -> {
                val group = mosaicChannels.value.getOrNull(mosaicFocus.value)
                if (group != null) {
                    closeMosaic()
                    // BACK desde el canal vuelve a la grilla.
                    playerBackScreen = Screen.GRID
                    play(group)
                }
            }
            Screen.SETUP -> {
                // Con el server apagado el QR no sirve: OK lo enciende.
                if (!serverRunning.value) startWebServer()
            }
            Screen.SETTINGS -> activateSettingsRow()
            Screen.LISTS -> activateListsRow()
            Screen.CHANNEL_MENU -> activateChannelMenuItem()
            Screen.NUMBER_PAD -> pressKeypad()
        }
    }

    fun setCategory(category: String?) {
        this.category.value = category
        // El foco tiene que seguir apuntando a algo visible en la grilla.
        val list = catalog.value.inCategory(category)
        val currentId = focusedGroupId.value
        if (currentId == null || list.none { it.id == currentId }) {
            focusedGroupId.value = list.firstOrNull()?.id
        }
    }

    fun setQuery(query: String) {
        this.query.value = query
        if (screen.value == Screen.SEARCH) applySearch(query)
    }

    // ---------------------------------------------------------------------
    // Entrada directa por numero (teclas 0-9)
    // ---------------------------------------------------------------------

    /**
     * Escribe un digito del numero de canal. El buffer se confirma solo a los
     * 2,5 s, o al instante con OK.
     */
    fun onDigit(digit: Int) {
        val buffer = NumberEntry.append(numberBuffer.value, digit)
        numberBuffer.value = buffer
        numberJob?.cancel()
        numberJob = scope.launch {
            delay(NumberEntry.TIMEOUT_MS)
            confirmNumber()
        }
        setStatus("Canal $buffer")
    }

    /** Confirma el numero escrito: busca el canal y lo reproduce. */
    fun confirmNumber() {
        numberJob?.cancel()
        numberJob = null
        val buffer = numberBuffer.value
        if (buffer.isBlank()) return
        numberBuffer.value = ""
        val target = NumberEntry.resolve(catalog.value, buffer)
        if (target == null) {
            setStatus("No hay un canal $buffer")
            return
        }
        play(target)
    }

    /** Descarta el numero escrito (BACK). */
    fun cancelNumber() {
        numberJob?.cancel()
        numberJob = null
        numberBuffer.value = ""
    }

    // ---------------------------------------------------------------------
    // Busqueda por texto
    // ---------------------------------------------------------------------

    /** Abre la pantalla de busqueda (con el texto actual, si habia). */
    fun openSearch() {
        screen.value = Screen.SEARCH
        // El foco arranca en el campo de texto (se ve el cursor).
        focusedSearchId.value = null
        searchFieldFocused.value = false
        applySearch(query.value)
    }

    /**
     * Recalcula los resultados de la busqueda y ancla el foco.
     *
     * Si el foco estaba en el campo de texto se mantiene ahi (null); si estaba
     * en un resultado y ese sigue existiendo, se mantiene.
     */
    fun applySearch(text: String) {
        val results = NumberEntry.search(catalog.value, text)
        searchResults.value = results
        val current = focusedSearchId.value
        focusedSearchId.value = when {
            current == null -> null
            results.any { it.id == current } -> current
            else -> results.firstOrNull()?.id
        }
    }

    /**
     * Mueve el foco en la pantalla de busqueda.
     *
     * El campo de texto y la lista de resultados se recorren con arriba/abajo.
     * Cuando el IME del sistema esta abierto, el sistema maneja las teclas.
     */
    fun moveSearchFocus(code: Int) {
        if (searchFieldFocused.value) return
        val list = searchResults.value
        val onField = focusedSearchId.value == null
        when (code) {
            RemoteKeys.DPAD_DOWN -> {
                if (onField) {
                    focusedSearchId.value = list.firstOrNull()?.id
                } else {
                    val index = list.indexOfFirst { it.id == focusedSearchId.value }
                    if (index >= 0 && index + 1 < list.size) {
                        focusedSearchId.value = list[index + 1].id
                    }
                }
            }
            RemoteKeys.DPAD_UP -> {
                if (onField) {
                    // Ya estamos arriba del todo.
                } else {
                    val index = list.indexOfFirst { it.id == focusedSearchId.value }
                    if (index <= 0) {
                        focusedSearchId.value = null
                    } else {
                        focusedSearchId.value = list[index - 1].id
                    }
                }
            }
        }
    }

    /** El usuario toco el campo de texto: abre el IME del sistema. */
    fun requestSearchFieldFocus() {
        searchFieldRequest.value = searchFieldRequest.value + 1
    }

    /** El campo nativo gano o perdio el foco de plataforma. */
    fun setSearchFieldFocused(focused: Boolean) {
        searchFieldFocused.value = focused
    }

    /** Limpia la busqueda. */
    fun clearSearch() {
        setQuery("")
    }

    /**
     * Suelta el foco del campo de texto (para cerrar el IME del sistema).
     * La UI observa [searchFieldFocused] y hace clearFocus() en el EditText.
     */
    fun clearSearchFieldFocus() {
        searchFieldFocused.value = false
    }

    /** Reproduce el canal enfocado en la busqueda. */
    fun playFocusedSearchResult() {
        val id = focusedSearchId.value ?: return
        val target = catalog.value.groupById[id] ?: return
        // BACK desde el canal vuelve a la busqueda.
        playerBackScreen = Screen.SEARCH
        play(target)
    }

    fun setFocused(id: String?) {
        focusedGroupId.value = id
        // Si el foco vuelve a una tarjeta, el chip deja de estar enfocado.
        focusedCategory.value = null
    }

    /** Categoria enfocada en la fila de chips ("" = Todos, null = ninguna). */
    fun setFocusedCategory(category: String?) {
        focusedCategory.value = category
    }

    /** Devuelve el foco a la grilla de canales (tras elegir una categoria). */
    fun focusGrid() {
        focusedCategory.value = null
    }

    fun setFocusedVariant(id: String?) {
        focusedVariantId.value = id
    }

    /** Columnas de la grilla: lo calcula la UI segun el ancho de pantalla. */
    private val gridColumns = MutableStateFlow(1)

    fun setGridColumns(columns: Int) {
        if (columns > 0) gridColumns.value = columns
    }

    /**
     * D-pad con navegacion determinista: el controller es el dueno del foco y la UI
     * solo dibuja el borde y hace scroll. Asi el control remoto y el companion se
     * comportan EXACTAMENTE igual (el foco interno de Compose no enganchaba en la TV).
     */
    fun onDpad(code: Int) {
        when (screen.value) {
            Screen.GRID -> moveGridFocus(code)
            Screen.QUALITY -> moveQualityFocus(code)
            Screen.SEARCH -> moveSearchFocus(code)
            Screen.MOSAIC -> moveMosaicFocus(code)
            Screen.SETTINGS -> moveSettingsFocus(code)
            Screen.LISTS -> moveListsFocus(code)
            Screen.CHANNEL_MENU -> moveChannelMenuFocus(code)
            Screen.NUMBER_PAD -> moveKeypadFocus(code)
            else -> Unit
        }
    }

    /**
     * Contador de recomposiciones de tarjeta: lo usa el diagnostico "KzGrid"
     * para ver cuantas tarjetas se redibujan por cada tecla del D-pad.
     */
    @Volatile
    private var gridCardCompositions = 0

    /** Lo llama cada tarjeta al componerse. */
    fun countCardComposition() {
        gridCardCompositions++
    }

    /** Momento del ultimo cambio de foco (elapsedRealtime). */
    @Volatile
    var lastFocusChangeAt: Long = 0L
        private set

    /** Momento del ultimo cambio de pantalla (elapsedRealtime). */
    @Volatile
    var lastScreenChangeAt: Long = 0L
        private set

    private fun moveGridFocus(code: Int) {
        // La barra lateral tiene el foco: el D-pad se mueve adentro de ella.
        if (railActive.value) {
            moveRailFocus(code)
            return
        }
        val chip = focusedCategory.value
        if (chip != null) {
            // El foco esta en la fila de categorias.
            val chips = listOf("") + catalog.value.categories
            val index = chips.indexOf(chip).let { if (it < 0) 0 else it }
            // LEFT en el primer chip: la barra lateral.
            if (code == RemoteKeys.DPAD_LEFT && index == 0) {
                openRail()
                return
            }
            val move = GridNavigator.moveChip(index, chips.size, code)
            if (move.toGrid) {
                focusedCategory.value = null
            } else {
                focusedCategory.value = chips[move.index]
            }
            android.util.Log.d(
                "KzNav",
                "chips code=$code -> '${focusedCategory.value ?: "grid"}'"
            )
            return
        }
        val list = visibleChannels()
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == focusedGroupId.value }.let { if (it < 0) 0 else it }
        val columns = gridColumns.value.coerceAtLeast(1)
        // LEFT en la primera columna: la barra lateral.
        if (code == RemoteKeys.DPAD_LEFT && index % columns == 0) {
            openRail()
            return
        }
        val move = GridNavigator.move(index, list.size, columns, code)
        if (move.toChips) {
            // Primera fila: sube a la fila de categorias, arrancando por la categoria
            // que esta activa.
            focusedCategory.value = category.value ?: ""
            android.util.Log.d(
                "KzNav",
                "grid code=$code -> chips ('${focusedCategory.value ?: ""}')"
            )
            return
        }
        focusedGroupId.value = list[move.index].id
        lastFocusChangeAt = android.os.SystemClock.elapsedRealtime()
        gridCardCompositions = 0
        android.util.Log.d(
            "KzNav",
            "grid code=$code -> ${list[move.index].name} (idx ${move.index}, cols $columns)"
        )
        // Diagnostico de fluidez: cuantas tarjetas se recomponen por tecla.
        scope.launch {
            delay(300)
            android.util.Log.d(
                "KzGrid",
                "tras la tecla: $gridCardCompositions tarjetas recompuestas"
            )
        }
    }

    private fun moveQualityFocus(code: Int) {
        val variants = qualityTarget.value?.variants ?: return
        if (variants.isEmpty()) return
        val index = variants.indexOfFirst {
            it.channelId == focusedVariantId.value || it.stateKey == focusedVariantId.value
        }.let { if (it < 0) 0 else it }
        val next = GridNavigator.moveVertical(index, variants.size, code)
        focusedVariantId.value = variants[next].channelId
        android.util.Log.d(
            "KzNav",
            "quality code=$code -> ${variants[next].label} (idx $next)"
        )
    }

    suspend fun addSourceByUrl(url: String): Int = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val content = repo.fetch(url)
            val sourceId = "url-${System.currentTimeMillis()}"
            val channels = M3uParser.parse(content, sourceId)
            if (channels.isEmpty()) error("La lista no tiene canales")
            val source = PersistedSource(
                id = sourceId,
                name = url.substringAfterLast('/').ifBlank { "Playlist" },
                url = url,
                channelCount = channels.size,
                content = content,
                lastRefreshAt = System.currentTimeMillis(),
            )
            store.update { it.copy(sources = it.sources + source) }
            channelsBySource[sourceId] = channels
            applyChannels(activeChannels())
            refreshPlaylistItems()
            channels.size
        }
    }

    suspend fun addSourceByContent(name: String, content: String): Int = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val sourceId = "file-${System.currentTimeMillis()}"
            val channels = M3uParser.parse(content, sourceId)
            if (channels.isEmpty()) error("El archivo no tiene canales")
            val source = PersistedSource(
                id = sourceId,
                name = name.ifBlank { "Playlist" },
                bundled = false,
                channelCount = channels.size,
                content = content,
            )
            store.update { it.copy(sources = it.sources + source) }
            channelsBySource[sourceId] = channels
            applyChannels(activeChannels())
            refreshPlaylistItems()
            channels.size
        }
    }

    fun snapshot(): StateDto {
        val now = System.currentTimeMillis()
        val activeVariantId = currentVariant.value?.channelId
        return StateDto(
            pairingCode = pairingCode.value,
            serverUrl = serverUrl.value,
            status = status.value,
            screen = screen.value.name,
            focusedId = focusedGroupId.value,
            focusedName = focusedGroupId.value?.let { catalog.value.groupById[it]?.name }.orEmpty(),
            focusedCategory = focusedCategory.value,
            currentId = currentGroup.value?.id,
            currentVariantId = activeVariantId,
            currentVariantLabel = currentVariant.value?.label.orEmpty(),
            zapFavoritesOnly = zapFavoritesOnly.value,
            playbackState = playbackState.value,
            playbackError = playbackError.value,
            videoWidth = videoWidth.value,
            videoHeight = videoHeight.value,
            numberBuffer = numberBuffer.value,
            searchQuery = query.value,
            mosaicErrors = mosaicTileErrors.value.mapKeys { it.key.toString() },
            focusedSearchId = focusedSearchId.value,
            focusedSearchName = focusedSearchId.value?.let { id ->
                catalog.value.groupById[id]?.name
            }.orEmpty(),
            channelCount = catalog.value.groups.size,
            groupCount = catalog.value.groups.size,
            variantCount = catalog.value.variantCount,
            categories = catalog.value.categories,
            channels = catalog.value.groups.map { group ->
                val (currentProgram, nextProgram) = epg.value.nowNextByTvgId(group.tvgId, now)
                ChannelDto(
                    id = group.id,
                    name = group.name,
                    logo = group.logo,
                    number = group.number,
                    groups = group.groups,
                    favorite = isFavorite(group),
                    kind = group.variants.firstOrNull()?.kind ?: Channel.KIND_HLS,
                    tvgId = group.tvgId,
                    now = currentProgram?.title.orEmpty(),
                    next = nextProgram?.title.orEmpty(),
                    activeVariantId = activeVariantId?.takeIf { id -> group.variants.any { it.channelId == id } }.orEmpty(),
                    variants = group.variants.map { variant ->
                        VariantDto(
                            id = variant.channelId,
                            name = variant.name,
                            label = variant.label,
                            quality = variant.quality,
                            region = variant.region,
                            host = variant.host,
                            status = variantStatus(variant.channelId),
                            measured = store.state.value.measuredResolution[variant.stateKey].orEmpty(),
                            active = variant.channelId == activeVariantId
                        )
                    }
                )
            },
            epgUrl = epgUrl.value,
            epgSource = epgSource.value,
            epgUpdatedAt = epgUpdatedAt.value,
            epgRefreshing = epgRefreshing.value,
            playlistRefreshing = playlistRefreshing.value,
            lists = playlists.value
        )
    }

    /**
     * Estado de un feed. "dead" caduca a las pocas horas: un fallo de red puntual
     * no debe dejar un canal inutilizado para siempre.
     */
    private fun variantStatus(variantId: String): String {
        val key = catalog.value.variantStateKeyById[variantId] ?: variantId
        val raw = store.state.value.variantStatus[key] ?: return ChannelVariant.STATUS_UNKNOWN
        if (!raw.startsWith(ChannelVariant.STATUS_DEAD)) return raw
        // Los estados guardados antes del TTL no tienen timestamp: caducan de una.
        val stamp = raw.substringAfter('#', "").toLongOrNull() ?: return ChannelVariant.STATUS_UNKNOWN
        val age = System.currentTimeMillis() - stamp
        return if (age > DEAD_TTL_MS) ChannelVariant.STATUS_UNKNOWN else ChannelVariant.STATUS_DEAD
    }

    private fun markVariant(variantId: String?, status: String) {
        if (variantId.isNullOrBlank()) return
        val key = catalog.value.variantStateKeyById[variantId] ?: variantId
        val value = if (status == ChannelVariant.STATUS_DEAD) {
            status + "#" + System.currentTimeMillis()
        } else {
            status
        }
        if (store.state.value.variantStatus[key] == value) return
        scope.launch {
            store.update { it.copy(variantStatus = it.variantStatus + (key to value)) }
        }
    }

    /**
     * Muestra un cartel en pantalla. Los avisos normales se van solos a los 3,5 s;
     * los errores (sticky = true) quedan hasta que llegue otro mensaje.
     */
    fun setStatus(text: String, sticky: Boolean = false) {
        status.value = text
        statusVisible.value = true
        statusJob?.cancel()
        if (!sticky && text.isNotBlank()) {
            statusJob = scope.launch {
                delay(3500)
                statusVisible.value = false
            }
        }
    }

    private fun showBanner() {
        bannerJob?.cancel()
        bannerVisible.value = true
        bannerJob = scope.launch {
            delay(4000)
            bannerVisible.value = false
        }
    }

    private fun generateCode(): String = (100000..999999).random().toString()

    fun setServerUrl(url: String) {
        serverUrl.value = url
    }
}
