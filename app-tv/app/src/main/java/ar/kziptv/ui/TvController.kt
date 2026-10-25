package ar.kziptv.ui

import android.content.Context
import ar.kziptv.core.catalog.ChannelCatalog
import ar.kziptv.core.epg.EpgIndex
import ar.kziptv.core.epg.EpgParser
import ar.kziptv.core.model.Channel
import ar.kziptv.core.parse.M3uParser
import ar.kziptv.data.KzStore
import ar.kziptv.data.PersistedSource
import ar.kziptv.data.SourceRepository
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

enum class Screen { PLAYER, GRID, SETUP }

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
    val next: String = ""
)

@Serializable
data class StateDto(
    val pairingCode: String = "",
    val serverUrl: String = "",
    val status: String = "",
    val currentId: String? = null,
    val zapFavoritesOnly: Boolean = false,
    val channelCount: Int = 0,
    val categories: List<String> = emptyList(),
    val channels: List<ChannelDto> = emptyList()
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
    val current = MutableStateFlow<Channel?>(null)
    val bannerVisible = MutableStateFlow(false)
    val screen = MutableStateFlow(Screen.PLAYER)
    val category = MutableStateFlow<String?>(null)
    val query = MutableStateFlow("")
    val epg = MutableStateFlow(EpgIndex(emptyList()))
    val status = MutableStateFlow("Cargando…")
    val serverUrl = MutableStateFlow("")
    val pairingCode = MutableStateFlow(store.state.value.pairingCode.ifBlank { generateCode() })
    val zapFavoritesOnly = MutableStateFlow(store.state.value.zapFavoritesOnly)
    val focusedChannelId = MutableStateFlow<String?>(null)

    private var bannerJob: Job? = null

    companion object {
        const val BUNDLED_ID = "bundled-ar"
        const val BUNDLED_URL = "https://iptv-org.github.io/iptv/countries/ar.m3u"
    }

    fun bootstrap() {
        scope.launch {
            val persisted = store.state.value.sources
            val sources = persisted.ifEmpty {
                listOf(PersistedSource(id = BUNDLED_ID, name = "iptv-org · Argentina", url = BUNDLED_URL, bundled = true))
            }
            val all = mutableListOf<Channel>()
            sources.forEach { source ->
                val content = try {
                    if (source.bundled) repo.bundledAr() else repo.fetch(source.url)
                } catch (e: Exception) {
                    status.value = "No pude leer ${source.name}: ${e.message}"
                    null
                } ?: return@forEach
                all += M3uParser.parse(content, source.id)
            }
            applyChannels(all)
            store.update { it.copy(sources = sources, pairingCode = pairingCode.value) }
            loadBundledEpg()
            if (all.isEmpty()) {
                screen.value = Screen.SETUP
            } else {
                val first = catalog.value.numbered.firstOrNull { it.playable }
                if (first != null) play(first, showBanner = false)
            }
        }
    }

    private fun applyChannels(channels: List<Channel>) {
        catalog.value = ChannelCatalog(channels)
        favorites.value = store.state.value.favorites.toSet()
        status.value = "${channels.size} canales cargados"
    }

    private fun loadBundledEpg() {
        scope.launch {
            val text = withContext(Dispatchers.IO) { readBundledEpgText() } ?: return@launch
            val programs = EpgParser.parse(text)
            if (programs.isNotEmpty()) {
                epg.value = EpgIndex(programs)
                status.value = "${catalog.value.all.size} canales · ${programs.size} programas EPG"
            }
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

    fun play(channel: Channel, showBanner: Boolean = true) {
        if (!channel.playable) {
            status.value = "«${channel.name}» no es reproducible en Android TV"
            return
        }
        player.play(channel)
        current.value = channel
        screen.value = Screen.PLAYER
        if (showBanner) showBanner()
    }

    fun zap(delta: Int) {
        val list = activeList()
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == current.value?.id }
        val nextIndex = if (index < 0) 0 else ((index + delta) % list.size + list.size) % list.size
        play(list[nextIndex])
    }

    fun activeList(): List<Channel> {
        val base = if (zapFavoritesOnly.value) favoritesList() else catalog.value.numbered
        val selected = category.value
        return if (selected != null) base.filter { selected in it.groups } else base
    }

    fun favoritesList(): List<Channel> =
        favorites.value.mapNotNull { catalog.value.byId[it] }.sortedBy { it.number }

    fun toggleFavorite(channelId: String? = null) {
        val id = channelId ?: focusedChannelId.value ?: current.value?.id ?: return
        val updated = favorites.value.toMutableSet()
        val added = if (updated.contains(id)) { updated.remove(id); false } else { updated.add(id); true }
        favorites.value = updated
        store.update { it.copy(favorites = updated.toList()) }
        val name = catalog.value.byId[id]?.name ?: "canal"
        status.value = if (added) "★ ${name} agregado a favoritos" else "${name} quitado de favoritos"
    }

    fun toggleZapMode() {
        zapFavoritesOnly.value = !zapFavoritesOnly.value
        store.update { it.copy(zapFavoritesOnly = zapFavoritesOnly.value) }
        status.value = if (zapFavoritesOnly.value) "Zap: solo FAVORITOS" else "Zap: todos los canales"
    }

    fun openGrid() {
        screen.value = Screen.GRID
    }

    fun back() {
        screen.value = Screen.PLAYER
    }

    fun openPairing() {
        screen.value = Screen.SETUP
    }

    fun onOk(longPress: Boolean) {
        if (longPress) {
            toggleFavorite()
            return
        }
        when (screen.value) {
            Screen.GRID -> focusedChannelId.value?.let { id -> catalog.value.byId[id]?.let { play(it) } }
            Screen.PLAYER -> openGrid()
            Screen.SETUP -> Unit
        }
    }

    fun setCategory(category: String?) {
        this.category.value = category
    }

    fun setQuery(query: String) {
        this.query.value = query
    }

    fun setFocused(id: String?) {
        focusedChannelId.value = id
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
        val favs = favorites.value
        val now = System.currentTimeMillis()
        return StateDto(
            pairingCode = pairingCode.value,
            serverUrl = serverUrl.value,
            status = status.value,
            currentId = current.value?.id,
            zapFavoritesOnly = zapFavoritesOnly.value,
            channelCount = catalog.value.all.size,
            categories = catalog.value.categories,
            channels = catalog.value.numbered.map { channel ->
                val (currentProgram, nextProgram) = epg.value.nowNext(channel, now)
                ChannelDto(
                    id = channel.id,
                    name = channel.name,
                    logo = channel.logo,
                    number = channel.number,
                    groups = channel.groups,
                    favorite = favs.contains(channel.id),
                    kind = channel.kind,
                    tvgId = channel.tvgId,
                    now = currentProgram?.title.orEmpty(),
                    next = nextProgram?.title.orEmpty()
                )
            }
        )
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
