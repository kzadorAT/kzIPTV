package ar.kziptv.ui

import android.content.Context
import ar.kziptv.core.catalog.ChannelCatalog
import ar.kziptv.core.catalog.ChannelGrouper
import ar.kziptv.core.epg.EpgIndex
import ar.kziptv.core.epg.EpgParser
import ar.kziptv.core.model.Channel
import ar.kziptv.core.model.ChannelGroup
import ar.kziptv.core.model.ChannelVariant
import ar.kziptv.core.model.toChannel
import ar.kziptv.core.parse.M3uParser
import ar.kziptv.data.KzStore
import ar.kziptv.data.PersistedSource
import ar.kziptv.data.SourceRepository
import ar.kziptv.core.nav.GridNavigator
import ar.kziptv.input.RemoteKeys
import ar.kziptv.playback.PlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.zip.GZIPInputStream

enum class Screen { PLAYER, GRID, SETUP, QUALITY }

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
    val videoHeight: Int = 0
)

class TvController(
    private val context: Context,
    private val store: KzStore,
    private val repo: SourceRepository,
    val player: PlayerManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val catalog = MutableStateFlow(ChannelCatalog(emptyList()))
    val favorites = MutableStateFlow(store.state.value.favorites.toSet())
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
    val status = MutableStateFlow("Cargando…")
    val serverUrl = MutableStateFlow("")
    val pairingCode = MutableStateFlow(store.state.value.pairingCode.ifBlank { generateCode() })
    val zapFavoritesOnly = MutableStateFlow(store.state.value.zapFavoritesOnly)
    val focusedGroupId = MutableStateFlow<String?>(null)
    val focusedVariantId = MutableStateFlow<String?>(null)

    /** Categoria enfocada en la fila de chips (null = el foco esta en la grilla). */
    val focusedCategory = MutableStateFlow<String?>(null)

    val qualityTarget = MutableStateFlow<ChannelGroup?>(null)
    val playbackState = MutableStateFlow("idle")
    val playbackError = MutableStateFlow("")
    val videoWidth = MutableStateFlow(0)
    val videoHeight = MutableStateFlow(0)

    private var bannerJob: Job? = null
    private var autoSkipJob: Job? = null
    private var consecutiveFailures = 0

    /** Reintentos por feed para errores transitorios de live. */
    private val retryCount = mutableMapOf<String, Int>()

    /** Arranque del controller: sirve para medir fases (KzStartup). */
    private val bootAt = android.os.SystemClock.elapsedRealtime()

    init {
        // El player avisa por aca: los errores se muestran en pantalla y se salta al
        // canal siguiente, para no dejar al usuario frente a una pantalla negra.
        player.onStateChanged = { state ->
            if (state == "playing") {
                consecutiveFailures = 0
                playbackError.value = ""
                val id = currentVariant.value?.channelId
                if (id != null) retryCount.remove(id)
                markVariant(id, ChannelVariant.STATUS_OK)
            }
            playbackState.value = state
        }
        player.onError = { message -> onPlaybackError(message) }
        player.onVideoSize = { w, h ->
            videoWidth.value = w
            videoHeight.value = h
            // La resolución REAL del stream: el nombre del playlist suele mentir
            // (dice 1080p y entrega 480p).
            val id = currentVariant.value?.channelId
            if (id != null && w > 0) {
                val value = w.toString() + "x" + h
                if (store.state.value.measuredResolution[id] != value) {
                    store.update { it.copy(measuredResolution = it.measuredResolution + (id to value)) }
                }
            }
        }
    }

    /**
     * Un feed fallo. Primero se prueban las OTRAS VARIANTES de la misma señal
     * (mismo canal, otro proveedor/calidad); si no queda ninguna, se zaponea.
     */
    private fun onPlaybackError(message: String) {
        consecutiveFailures++
        playbackState.value = "error"
        playbackError.value = message
        val group = currentGroup.value
        val variant = currentVariant.value

        // Algunos feeds en vivo rotan la playlist antes de que el player la lea
        // (BEHIND_LIVE_WINDOW / IO_READ_POSITION_OUT_OF_RANGE): no son feeds muertos,
        // son reintentos. Re-preparar pide la playlist nueva y funciona.
        if (variant != null && group != null && isTransient(message)) {
            val attempts = retryCount[variant.channelId] ?: 0
            if (attempts < MAX_VARIANT_RETRIES) {
                retryCount[variant.channelId] = attempts + 1
                android.util.Log.w(
                    "KzIPTV",
                    "reintento " + attempts + " de " + variant.name + " (error transitorio: " + message + ")"
                )
                status.value = "Reintentando ${group.name}\u2026"
                autoSkipJob?.cancel()
                autoSkipJob = scope.launch {
                    delay(1500)
                    play(group, variant, showBanner = false, switchToPlayer = false)
                }
                return
            }
        }

        if (variant != null) markVariant(variant.channelId, ChannelVariant.STATUS_DEAD)
        val name = group?.name ?: "ese canal"
        android.util.Log.e(
            "KzIPTV",
            "error de reproduccion: " + name + " / " + (variant?.name ?: "-") + " -> " + message
        )
        status.value = "\u26A0 $name no se reproduce ($message)"
        autoSkipJob?.cancel()
        if (consecutiveFailures > MAX_AUTO_SKIP) {
            status.value = "\u26A0 Ningun canal responde. Revisa la conexion o la playlist."
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
        /** Un feed marcado como muerto se reintenta pasado este tiempo. */
        const val DEAD_TTL_MS = 6 * 60 * 60 * 1000L
        const val BUNDLED_ID = "bundled-ar"
        const val BUNDLED_URL = "https://iptv-org.github.io/iptv/countries/ar.m3u"
    }

    fun bootstrap() {
        scope.launch {
            val persisted = store.state.value.sources
            val sources = persisted.ifEmpty {
                listOf(PersistedSource(id = BUNDLED_ID, name = "iptv-org \u00b7 Argentina", url = BUNDLED_URL, bundled = true))
            }
            val all = mutableListOf<Channel>()
            sources.forEach { source ->
                val content = try {
                    if (source.bundled) repo.bundledAr() else repo.fetch(source.url)
                } catch (e: Exception) {
                    status.value = "No pude leer ${source.name}: ${e.message}"
                    null
                } ?: return@forEach
                all += withContext(Dispatchers.IO) { M3uParser.parse(content, source.id) }
            }
            applyChannels(all)
            store.update { it.copy(sources = sources, pairingCode = pairingCode.value) }
            android.util.Log.i(
                "KzStartup",
                "playlist lista en " + (android.os.SystemClock.elapsedRealtime() - bootAt) +
                    " ms (" + all.size + " feeds / " + catalog.value.groups.size + " canales)"
            )
            loadBundledEpg()
            if (all.isEmpty()) {
                screen.value = Screen.SETUP
            } else {
                // Retoma el ultimo canal que se estaba viendo; si no, el primero disponible.
                val list = catalog.value.groups
                val lastGroupId = store.state.value.lastGroupId
                val target = list.firstOrNull { it.id == lastGroupId && it.variants.any { v -> v.playable } }
                    ?: list.firstOrNull { it.variants.any { v -> v.playable } }
                if (target != null) play(target, showBanner = false)
            }
        }
    }

    /**
     * Construye el catalogo. El agrupado (regex sobre 200+ nombres) tarda ~1,5 s en la TV:
     * va en IO para no congelar la UI en el arranque.
     */
    private suspend fun applyChannels(channels: List<Channel>) {
        catalog.value = withContext(Dispatchers.IO) { ChannelCatalog(channels) }
        favorites.value = store.state.value.favorites.toSet()
        status.value = "${catalog.value.groups.size} canales" +
            (if (catalog.value.variantCount > catalog.value.groups.size) " (${catalog.value.variantCount} feeds)" else "") +
            " cargados"
    }

    private fun loadBundledEpg() {
        scope.launch {
            // Todo el parseo del XMLTV (~2 MB) va en IO: en el hilo principal provocaba ANR.
            val programs = withContext(Dispatchers.IO) {
                val text = readBundledEpgText() ?: return@withContext emptyList()
                EpgParser.parse(text)
            }
            if (programs.isNotEmpty()) {
                epg.value = EpgIndex(programs)
                status.value = "${catalog.value.groups.size} canales \u00b7 ${programs.size} programas EPG"
            }
            android.util.Log.i("KzStartup", "EPG listo en " + (android.os.SystemClock.elapsedRealtime() - bootAt) + " ms")
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
            status.value = "\u00ab${group.name}\u00bb no es reproducible en Android TV"
            return
        }
        player.play(chosen.toChannel())
        currentGroup.value = group
        currentVariant.value = chosen
        // Un reintento o fallback NO debe sacar al usuario de la grilla o del picker.
        if (switchToPlayer) screen.value = Screen.PLAYER
        playbackState.value = "buffering"
        playbackError.value = ""
        store.update { it.copy(lastGroupId = group.id, lastChannelId = chosen.channelId) }
        if (showBanner) showBanner()
    }

    /** Elige la variante: la preferencia del usuario si sigue viva, si no la de mayor calidad. */
    fun chooseVariant(group: ChannelGroup): ChannelVariant {
        val preference = store.state.value.qualityPreference[group.id]
        val alive = group.variants.filter { variantStatus(it.channelId) != ChannelVariant.STATUS_DEAD }
        val pool = if (alive.isNotEmpty()) alive else group.variants
        return pool.firstOrNull { it.channelId == preference }
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
            catalog.value.groupById[id] ?: catalog.value.groupOf(id)
        }.distinctBy { it.id }.sortedBy { it.number }.map { enrich(it) }

    /** Enriquece el grupo con la salud real de cada feed y la resolución medida. */
    private fun enrich(group: ChannelGroup): ChannelGroup {
        val measured = store.state.value.measuredResolution
        return group.copy(
            variants = group.variants.map { v ->
                v.copy(
                    status = variantStatus(v.channelId),
                    measured = measured[v.channelId].orEmpty()
                )
            }
        )
    }

    fun isFavorite(group: ChannelGroup): Boolean =
        favorites.value.contains(group.id) || group.variants.any { favorites.value.contains(it.channelId) }

    fun toggleFavorite(groupId: String? = null) {
        val id = groupId ?: focusedGroupId.value ?: currentGroup.value?.id ?: return
        val group = catalog.value.groupById[id] ?: return
        val wasFavorite = isFavorite(group)
        val updated = favorites.value.toMutableSet()
        if (wasFavorite) {
            updated.remove(group.id)
            group.variants.forEach { updated.remove(it.channelId) }
        } else {
            updated.add(group.id)
        }
        favorites.value = updated
        store.update { it.copy(favorites = updated.toList()) }
        status.value = if (!wasFavorite) "\u2605 ${group.name} agregado a favoritos" else "${group.name} quitado de favoritos"
    }

    fun toggleZapMode() {
        zapFavoritesOnly.value = !zapFavoritesOnly.value
        store.update { it.copy(zapFavoritesOnly = zapFavoritesOnly.value) }
        status.value = if (zapFavoritesOnly.value) "Zap: solo FAVORITOS" else "Zap: todos los canales"
    }

    fun openGrid() {
        // El foco arranca en el canal que estas mirando: sin esto el OK no hace nada.
        val target = currentGroup.value?.id ?: catalog.value.groups.firstOrNull()?.id
        if (target != null) focusedGroupId.value = target
        // Al reabrir la grilla el foco vuelve a estar en las tarjetas, no en un chip.
        focusedCategory.value = null
        screen.value = Screen.GRID
    }

    fun back() {
        focusedCategory.value = null
        screen.value = Screen.PLAYER
    }

    fun openPairing() {
        screen.value = Screen.SETUP
    }

    /** Abre el selector de calidad de un canal (o del canal enfocado). */
    fun openQuality(group: ChannelGroup? = null) {
        val target = group
            ?: focusedGroupId.value?.let { catalog.value.groupById[it] }
            ?: currentGroup.value
        if (target == null) {
            status.value = "No hay canal seleccionado"
            return
        }
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
        val variant = group.variants.firstOrNull { it.channelId == variantId } ?: return
        store.update { it.copy(qualityPreference = it.qualityPreference + (group.id to variantId)) }
        markVariant(variantId, ChannelVariant.STATUS_UNKNOWN)
        screen.value = Screen.PLAYER
        play(group, variant)
    }

    /** Elige una variante: se reproduce y queda como preferencia para ese canal. */
    fun selectVariant(variantId: String) {
        val target = qualityTarget.value ?: currentGroup.value ?: return
        val variant = target.variants.firstOrNull { it.channelId == variantId } ?: return
        store.update { it.copy(qualityPreference = it.qualityPreference + (target.id to variantId)) }
        markVariant(variantId, ChannelVariant.STATUS_UNKNOWN)
        screen.value = Screen.PLAYER
        play(target, variant)
    }

    fun onOk(longPress: Boolean) {
        if (longPress) {
            when (screen.value) {
                Screen.PLAYER, Screen.GRID -> openQuality()
                else -> toggleFavorite()
            }
            return
        }
        when (screen.value) {
            Screen.GRID -> {
                val chip = focusedCategory.value
                if (chip != null) {
                    setCategory(if (chip.isEmpty()) null else chip)
                    focusGrid()
                } else {
                    focusedGroupId.value?.let { id -> catalog.value.groupById[id]?.let { play(it) } }
                }
            }
            Screen.PLAYER -> openGrid()
            Screen.QUALITY -> focusedVariantId.value?.let { selectVariant(it) }
            Screen.SETUP -> Unit
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
            else -> Unit
        }
    }

    private fun moveGridFocus(code: Int) {
        val chip = focusedCategory.value
        if (chip != null) {
            // El foco esta en la fila de categorias.
            val chips = listOf("") + catalog.value.categories
            val index = chips.indexOf(chip).let { if (it < 0) 0 else it }
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
        val list = catalog.value.inCategory(category.value)
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == focusedGroupId.value }.let { if (it < 0) 0 else it }
        val columns = gridColumns.value.coerceAtLeast(1)
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
        android.util.Log.d(
            "KzNav",
            "grid code=$code -> ${list[move.index].name} (idx ${move.index}, cols $columns)"
        )
    }

    private fun moveQualityFocus(code: Int) {
        val variants = qualityTarget.value?.variants ?: return
        if (variants.isEmpty()) return
        val index = variants.indexOfFirst { it.channelId == focusedVariantId.value }.let { if (it < 0) 0 else it }
        val next = GridNavigator.moveVertical(index, variants.size, code)
        focusedVariantId.value = variants[next].channelId
        android.util.Log.d(
            "KzNav",
            "quality code=$code -> ${variants[next].label} (idx $next)"
        )
    }

    suspend fun addSourceByUrl(url: String): Int = withContext(Dispatchers.IO) {
        val content = repo.fetch(url)
        val channels = M3uParser.parse(content, "url-${System.currentTimeMillis()}")
        if (channels.isEmpty()) error("La lista no tiene canales")
        val source = PersistedSource(
            id = "url-${System.currentTimeMillis()}",
            name = url.substringAfterLast('/').ifBlank { "Playlist" },
            url = url,
            channelCount = channels.size
        )
        store.update { it.copy(sources = it.sources + source) }
        applyChannels(catalog.value.all + channels)
        channels.size
    }

    suspend fun addSourceByContent(name: String, content: String): Int = withContext(Dispatchers.IO) {
        val channels = M3uParser.parse(content, "file-${System.currentTimeMillis()}")
        if (channels.isEmpty()) error("El archivo no tiene canales")
        val source = PersistedSource(
            id = "file-${System.currentTimeMillis()}",
            name = name.ifBlank { "Playlist" },
            bundled = false,
            channelCount = channels.size
        )
        store.update { it.copy(sources = it.sources + source) }
        applyChannels(catalog.value.all + channels)
        channels.size
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
                            measured = store.state.value.measuredResolution[variant.channelId].orEmpty(),
                            active = variant.channelId == activeVariantId
                        )
                    }
                )
            }
        )
    }

    /**
     * Estado de un feed. "dead" caduca a las pocas horas: un fallo de red puntual
     * no debe dejar un canal inutilizado para siempre.
     */
    /** Errores que se resuelven reintentando el mismo feed. */
    private fun isTransient(message: String): Boolean {
        val m = message.uppercase()
        return m.contains("BEHIND_LIVE_WINDOW") ||
            m.contains("IO_READ_POSITION_OUT_OF_RANGE") ||
            m.contains("TIMEOUT") ||
            m.contains("CONNECTION_RESET") ||
            m.contains("HOST_NOT_FOUND") ||
            m.contains("UNEXPECTED_EOF")
    }

    private fun variantStatus(variantId: String): String {
        val raw = store.state.value.variantStatus[variantId] ?: return ChannelVariant.STATUS_UNKNOWN
        if (!raw.startsWith(ChannelVariant.STATUS_DEAD)) return raw
        // Los estados guardados antes del TTL no tienen timestamp: caducan de una.
        val stamp = raw.substringAfter('#', "").toLongOrNull() ?: return ChannelVariant.STATUS_UNKNOWN
        val age = System.currentTimeMillis() - stamp
        return if (age > DEAD_TTL_MS) ChannelVariant.STATUS_UNKNOWN else ChannelVariant.STATUS_DEAD
    }

    private fun markVariant(variantId: String?, status: String) {
        if (variantId.isNullOrBlank()) return
        val value = if (status == ChannelVariant.STATUS_DEAD) {
            status + "#" + System.currentTimeMillis()
        } else {
            status
        }
        if (store.state.value.variantStatus[variantId] == value) return
        store.update { it.copy(variantStatus = it.variantStatus + (variantId to value)) }
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
