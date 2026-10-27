package ar.kziptv.data.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Una playlist importable. La tabla ya permite mas de una lista en el futuro. */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val bundled: Boolean,
    val channelCount: Int,
    val position: Int,
    /** Contenido M3U para fuentes subidas o descargadas por la app. */
    val content: String = "",
    /** Ultima descarga exitosa de esta playlist (epoch ms). */
    val lastRefreshAt: Long = 0,
    /** true si el usuario mira esta lista (el catalogo solo usa las activas). */
    val enabled: Boolean = true,
)

/** Numero estable de un canal logico, indexado por su clave canonica. */
@Entity(tableName = "channel_numbers")
data class ChannelNumberEntity(
    @PrimaryKey val channelKey: String,
    val number: Int
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val channelId: String,
    val position: Int,
)

/** Tokens de los celulares emparejados con la TV. */
@Entity(tableName = "device_tokens")
data class DeviceTokenEntity(
    @PrimaryKey val token: String,
    val pairedAt: Long,
)

/** Preferencias globales de la aplicacion. La fila activa siempre tiene id = 1. */
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val pairingCode: String,
    val zapFavoritesOnly: Boolean,
    val lastChannelId: String,
    val lastGroupId: String,
    /** URL de la guia EPG remota (vacio = se usa la guia embebida). */
    val epgUrl: String = "",
)

/** Variante preferida por canal logico. */
@Entity(tableName = "quality_preferences")
data class QualityPreferenceEntity(
    @PrimaryKey val groupId: String,
    val variantId: String,
)

/** Estado y resolucion medida de una variante. */
@Entity(tableName = "variant_states")
data class VariantStateEntity(
    @PrimaryKey val variantId: String,
    val status: String,
    val measuredResolution: String,
)

/** Programa EPG cacheado. El ordinal conserva el orden original del XMLTV. */
@Entity(tableName = "epg_programs", indices = [Index(value = ["ordinal"])])
data class EpgProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ordinal: Int,
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String,
    val category: String,
)

/** Fingerprint del asset o descarga que produjo el cache EPG. */
@Entity(tableName = "epg_cache_meta")
data class EpgCacheMetaEntity(
    @PrimaryKey val key: String,
    val fingerprint: String,
    val cachedAt: Long,
    val programCount: Int,
)

/** Historial acotado de reproducciones. */
@Entity(tableName = "watch_history", indices = [Index(value = ["watchedAt"])])
data class WatchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: String,
    val channelId: String,
    val variantId: String,
    val watchedAt: Long,
)
