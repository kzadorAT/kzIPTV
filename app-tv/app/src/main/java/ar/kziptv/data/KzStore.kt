package ar.kziptv.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import ar.kziptv.core.model.EpgProgram
import ar.kziptv.data.room.EpgCacheMetaEntity
import ar.kziptv.data.room.EpgProgramEntity
import ar.kziptv.data.room.KzDatabase
import ar.kziptv.data.room.RoomStateMapper
import ar.kziptv.data.room.WatchHistoryEntity
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Fuente persistida de una playlist. */
@Serializable
data class PersistedSource(
    val id: String,
    val name: String,
    val url: String = "",
    val bundled: Boolean = false,
    val channelCount: Int = 0,
    /** Contenido M3U cacheado para playlists subidas o descargadas. */
    val content: String = "",
    /** Ultima descarga exitosa de esta playlist (epoch ms). */
    val lastRefreshAt: Long = 0
)

/**
 * Estado persistente completo. Se conserva como modelo de dominio y para leer una sola
 * vez el JSON legado; Room es la fuente de verdad desde la version 1 de la base.
 */
@Serializable
data class PersistedState(
    val sources: List<PersistedSource> = emptyList(),
    val favorites: List<String> = emptyList(),
    val pairingCode: String = "",
    val tokens: List<String> = emptyList(),
    val lastChannelId: String = "",
    val lastGroupId: String = "",
    val zapFavoritesOnly: Boolean = false,
    val qualityPreference: Map<String, String> = emptyMap(),
    val variantStatus: Map<String, String> = emptyMap(),
    val measuredResolution: Map<String, String> = emptyMap(),
    /** Numeros de canal estables, indexados por la clave canonica del canal. */
    val channelNumbers: Map<String, Int> = emptyMap(),
    /** URL de la guia EPG remota (vacio = se usa la guia embebida). */
    val epgUrl: String = "",
)

/** Entrada del historial de reproducciones. */
data class WatchHistoryEntry(
    val groupId: String,
    val channelId: String,
    val variantId: String,
    val watchedAt: Long,
)

/**
 * Fachada de persistencia sobre Room.
 *
 * - Las lecturaspublicas siguen siendo instantaneas desde [state].
 * - Las escrituras son suspend y se serializan con [mutex], por lo que dos updates
 *   simultaneos nunca se pisan.
 * - Al primer arranque se importa el JSON legado una sola vez y se conserva un backup.
 */
class KzStore(
    context: Context,
    databaseOverride: KzDatabase? = null,
    private val legacyFileName: String = "kz-state.json",
) {
    private val appContext = context.applicationContext
    private val database = databaseOverride ?: KzDatabase.create(appContext)
    private val dao = database.kzDao()
    private val legacyFile = File(appContext.filesDir, legacyFileName)
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val _state = MutableStateFlow(PersistedState())
    val state: StateFlow<PersistedState> = _state.asStateFlow()

    private var initialized = false
    private var closed = false

    /** Carga Room e importa el JSON legado siTodavia no existe una base migrada. */
    suspend fun initialize(): PersistedState = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!initialized) initializeLocked()
            _state.value
        }
    }

    /**
     * Aplica un cambio al estado y lo persiste. La transformacion se ejecuta dentro
     * del mutex para que parta siempre del ultimo estado guardado.
     */
    suspend fun update(
        transform: (PersistedState) -> PersistedState,
    ): PersistedState = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!initialized) initializeLocked()
            val old = _state.value
            val new = transform(old)
            try {
                persistDiff(old, new)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo guardar el estado en Room", e)
            }
            _state.value = new
            new
        }
    }

    /** Devuelve el cache EPG si coincide con el fingerprint del asset actual. */
    suspend fun cachedEpgPrograms(
        fingerprint: String,
        key: String = EPG_CACHE_KEY,
    ): List<EpgProgram>? = withContext(Dispatchers.IO) {
        val meta = dao.getEpgCacheMeta(key) ?: return@withContext null
        if (meta.fingerprint != fingerprint || meta.programCount <= 0) return@withContext null
        val rows = dao.getEpgPrograms()
        // Un cache incompleto (por ejemplo, una migracion previa) se vuelve a parsear.
        if (rows.size != meta.programCount) return@withContext null
        rows.map { row ->
            EpgProgram(
                channelId = row.channelId,
                start = row.start,
                stop = row.stop,
                title = row.title,
                description = row.description,
                category = row.category,
            )
        }
    }

    /** Reemplaza atomicamente el cache EPG y su metadata. */
    suspend fun cacheEpgPrograms(
        fingerprint: String,
        programs: List<EpgProgram>,
        key: String = EPG_CACHE_KEY,
    ) = withContext(Dispatchers.IO) {
        if (programs.isEmpty()) return@withContext
        database.withTransaction {
            dao.deleteEpgPrograms()
            dao.insertEpgPrograms(
                programs.mapIndexed { ordinal, program ->
                    EpgProgramEntity(
                        ordinal = ordinal,
                        channelId = program.channelId,
                        start = program.start,
                        stop = program.stop,
                        title = program.title,
                        description = program.description,
                        category = program.category,
                    )
                },
            )
            dao.upsertEpgCacheMeta(
                EpgCacheMetaEntity(
                    key = key,
                    fingerprint = fingerprint,
                    cachedAt = System.currentTimeMillis(),
                    programCount = programs.size,
                ),
            )
        }
    }

    /** Guarda una reproduccion y acota el historial. */
    suspend fun recordWatch(
        groupId: String,
        channelId: String,
        variantId: String,
        watchedAt: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.insertWatchHistory(
                WatchHistoryEntity(
                    groupId = groupId,
                    channelId = channelId,
                    variantId = variantId,
                    watchedAt = watchedAt,
                ),
            )
            dao.trimWatchHistory(MAX_HISTORY_ROWS)
        }
    }

    /** Historial reciente, del mas nuevo al mas viejo. */
    suspend fun recentHistory(limit: Int = 50): List<WatchHistoryEntry> =
        withContext(Dispatchers.IO) {
            dao.getWatchHistory(limit.coerceIn(1, MAX_HISTORY_ROWS)).map { row ->
                WatchHistoryEntry(
                    groupId = row.groupId,
                    channelId = row.channelId,
                    variantId = row.variantId,
                    watchedAt = row.watchedAt,
                )
            }
        }

    /** Espera a que terminen las escrituras pendientes. */
    suspend fun flush() = withContext(Dispatchers.IO) {
        mutex.withLock { Unit }
    }

    /** Cierra la base luego de esperar las escrituras pendientes. */
    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!closed) {
                database.close()
                closed = true
            }
        }
    }

    private suspend fun initializeLocked() {
        var legacyFallback: PersistedState? = null
        try {
            legacyFallback = readLegacyState()
            val settings = dao.getAppSettings()
            if (settings == null) {
                val imported = legacyFallback ?: PersistedState()
                persistAll(imported)
                if (legacyFallback != null) {
                    moveLegacy("$legacyFileName.room-backup")
                    Log.i(TAG, "Estado JSON migrado a Room")
                }
            } else if (legacyFallback != null) {
                // La base ya tiene datos: el JSON es una copia vieja y no debe pisarlos.
                moveLegacy("$legacyFileName.stale-room-backup")
            }
            _state.value = loadPersistedState()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir Room; se usa el estado en memoria", e)
            _state.value = legacyFallback ?: PersistedState()
        } finally {
            initialized = true
        }
    }

    private fun readLegacyState(): PersistedState? {
        if (!legacyFile.exists()) return null
        return try {
            json.decodeFromString<PersistedState>(legacyFile.readText())
        } catch (e: Exception) {
            Log.e(TAG, "El estado JSON legado esta danado; se conserva como .invalid", e)
            moveLegacy("$legacyFileName.invalid")
            null
        }
    }

    private fun moveLegacy(targetName: String) {
        if (!legacyFile.exists()) return
        val target = File(appContext.filesDir, targetName)
        if (target.exists()) target.delete()
        if (!legacyFile.renameTo(target)) {
            try {
                legacyFile.copyTo(target, overwrite = true)
                legacyFile.delete()
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo archivar el estado JSON legado", e)
            }
        }
    }

    private suspend fun loadPersistedState(): PersistedState = database.withTransaction {
        RoomStateMapper.persistedState(
            playlists = dao.getPlaylists(),
            favorites = dao.getFavorites(),
            tokens = dao.getDeviceTokens(),
            settings = dao.getAppSettings(),
            qualityPreferences = dao.getQualityPreferences(),
            variantStates = dao.getVariantStates(),
            channelNumbers = dao.getChannelNumbers(),
        )
    }

    private suspend fun persistAll(state: PersistedState) {
        database.withTransaction {
            dao.deletePlaylists()
            val playlists = RoomStateMapper.playlists(state)
            if (playlists.isNotEmpty()) dao.upsertPlaylists(playlists)

            dao.deleteFavorites()
            val favorites = RoomStateMapper.favorites(state)
            if (favorites.isNotEmpty()) dao.upsertFavorites(favorites)

            dao.deleteDeviceTokens()
            val tokens = RoomStateMapper.deviceTokens(state)
            if (tokens.isNotEmpty()) dao.upsertDeviceTokens(tokens)

            dao.upsertAppSettings(RoomStateMapper.appSettings(state))

            dao.deleteQualityPreferences()
            val quality = RoomStateMapper.qualityPreferences(state)
            if (quality.isNotEmpty()) dao.upsertQualityPreferences(quality)

            dao.deleteVariantStates()
            val variants = RoomStateMapper.variantStates(state)
            if (variants.isNotEmpty()) dao.upsertVariantStates(variants)

            dao.deleteChannelNumbers()
            val numbers = RoomStateMapper.channelNumbers(state)
            if (numbers.isNotEmpty()) dao.upsertChannelNumbers(numbers)
        }
    }

    private suspend fun persistDiff(old: PersistedState, new: PersistedState) {
        database.withTransaction {
            if (old.sources != new.sources) {
                dao.deletePlaylists()
                val rows = RoomStateMapper.playlists(new)
                if (rows.isNotEmpty()) dao.upsertPlaylists(rows)
            }
            if (old.favorites != new.favorites) {
                dao.deleteFavorites()
                val rows = RoomStateMapper.favorites(new)
                if (rows.isNotEmpty()) dao.upsertFavorites(rows)
            }
            if (old.tokens != new.tokens) {
                dao.deleteDeviceTokens()
                val rows = RoomStateMapper.deviceTokens(new)
                if (rows.isNotEmpty()) dao.upsertDeviceTokens(rows)
            }
            if (RoomStateMapper.appSettings(old) != RoomStateMapper.appSettings(new)) {
                dao.upsertAppSettings(RoomStateMapper.appSettings(new))
            }
            if (old.qualityPreference != new.qualityPreference) {
                dao.deleteQualityPreferences()
                val rows = RoomStateMapper.qualityPreferences(new)
                if (rows.isNotEmpty()) dao.upsertQualityPreferences(rows)
            }
            if (
                old.variantStatus != new.variantStatus ||
                old.measuredResolution != new.measuredResolution
            ) {
                dao.deleteVariantStates()
                val rows = RoomStateMapper.variantStates(new)
                if (rows.isNotEmpty()) dao.upsertVariantStates(rows)
            }
            if (old.channelNumbers != new.channelNumbers) {
                dao.deleteChannelNumbers()
                val rows = RoomStateMapper.channelNumbers(new)
                if (rows.isNotEmpty()) dao.upsertChannelNumbers(rows)
            }
        }
    }

    companion object {
        private const val TAG = "KzStore"
        /** Clave unica del cache EPG: describe siempre lo que hay en `epg_programs`. */
        private const val EPG_CACHE_KEY = "active"
        private const val MAX_HISTORY_ROWS = 500
    }
}
