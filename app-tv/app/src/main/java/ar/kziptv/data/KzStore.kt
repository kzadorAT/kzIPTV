package ar.kziptv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PersistedSource(
    val id: String,
    val name: String,
    val url: String = "",
    val bundled: Boolean = false,
    val channelCount: Int = 0
)

@Serializable
data class PersistedState(
    val sources: List<PersistedSource> = emptyList(),
    val favorites: List<String> = emptyList(),
    val zapFavoritesOnly: Boolean = false,
    val pairingCode: String = "",
    val tokens: List<String> = emptyList()
)

class KzStore(context: Context) {

    private val file = File(context.filesDir, "kz-state.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val _state = MutableStateFlow(load())

    val state: StateFlow<PersistedState> = _state.asStateFlow()

    private fun load(): PersistedState = try {
        if (file.exists()) json.decodeFromString(PersistedState.serializer(), file.readText()) else PersistedState()
    } catch (e: Exception) {
        PersistedState()
    }

    fun update(block: (PersistedState) -> PersistedState) {
        _state.value = block(_state.value)
        try {
            file.writeText(json.encodeToString(PersistedState.serializer(), _state.value))
        } catch (e: Exception) {
            // sin persistencia no se cae la app
        }
    }
}
