package ar.kziptv.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface KzDao {

    @Query("SELECT * FROM playlists ORDER BY position")
    suspend fun getPlaylists(): List<PlaylistEntity>

    @Upsert
    suspend fun upsertPlaylists(rows: List<PlaylistEntity>)

    @Query("DELETE FROM playlists")
    suspend fun deletePlaylists()

    @Query("SELECT * FROM favorites ORDER BY position")
    suspend fun getFavorites(): List<FavoriteEntity>

    @Upsert
    suspend fun upsertFavorites(rows: List<FavoriteEntity>)

    @Query("DELETE FROM favorites")
    suspend fun deleteFavorites()

    @Query("SELECT * FROM device_tokens ORDER BY pairedAt, token")
    suspend fun getDeviceTokens(): List<DeviceTokenEntity>

    @Upsert
    suspend fun upsertDeviceTokens(rows: List<DeviceTokenEntity>)

    @Query("DELETE FROM device_tokens")
    suspend fun deleteDeviceTokens()

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun getAppSettings(): AppSettingsEntity?

    @Upsert
    suspend fun upsertAppSettings(row: AppSettingsEntity)

    @Query("SELECT * FROM quality_preferences")
    suspend fun getQualityPreferences(): List<QualityPreferenceEntity>

    @Upsert
    suspend fun upsertQualityPreferences(rows: List<QualityPreferenceEntity>)

    @Query("DELETE FROM quality_preferences")
    suspend fun deleteQualityPreferences()

    @Query("SELECT * FROM variant_states")
    suspend fun getVariantStates(): List<VariantStateEntity>

    @Upsert
    suspend fun upsertVariantStates(rows: List<VariantStateEntity>)

    @Query("DELETE FROM variant_states")
    suspend fun deleteVariantStates()

    @Query("SELECT * FROM epg_programs ORDER BY ordinal")
    suspend fun getEpgPrograms(): List<EpgProgramEntity>

    @Insert
    suspend fun insertEpgPrograms(rows: List<EpgProgramEntity>)

    @Query("DELETE FROM epg_programs")
    suspend fun deleteEpgPrograms()

    @Query("SELECT * FROM epg_cache_meta WHERE key = :key")
    suspend fun getEpgCacheMeta(key: String): EpgCacheMetaEntity?

    @Upsert
    suspend fun upsertEpgCacheMeta(row: EpgCacheMetaEntity)

    @Insert
    suspend fun insertWatchHistory(row: WatchHistoryEntity)

    @Query(
        "DELETE FROM watch_history WHERE id NOT IN " +
            "(SELECT id FROM watch_history ORDER BY watchedAt DESC, id DESC LIMIT :maxRows)",
    )
    suspend fun trimWatchHistory(maxRows: Int)

    @Query("SELECT * FROM watch_history ORDER BY watchedAt DESC, id DESC LIMIT :limit")
    suspend fun getWatchHistory(limit: Int): List<WatchHistoryEntity>
}
