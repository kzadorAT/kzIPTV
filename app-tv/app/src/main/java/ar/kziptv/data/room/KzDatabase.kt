package ar.kziptv.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        FavoriteEntity::class,
        DeviceTokenEntity::class,
        AppSettingsEntity::class,
        QualityPreferenceEntity::class,
        VariantStateEntity::class,
        EpgProgramEntity::class,
        EpgCacheMetaEntity::class,
        WatchHistoryEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class KzDatabase : RoomDatabase() {

    abstract fun kzDao(): KzDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `playlists` ADD COLUMN `content` TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `epg_programs` (" +
                        "`channelId` TEXT NOT NULL, " +
                        "`start` INTEGER NOT NULL, " +
                        "`stop` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "PRIMARY KEY(`channelId`, `start`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `epg_cache_meta` (" +
                        "`key` TEXT NOT NULL, " +
                        "`fingerprint` TEXT NOT NULL, " +
                        "`cachedAt` INTEGER NOT NULL, " +
                        "`programCount` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`key`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watch_history` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`groupId` TEXT NOT NULL, " +
                        "`channelId` TEXT NOT NULL, " +
                        "`variantId` TEXT NOT NULL, " +
                        "`watchedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_watch_history_watchedAt` " +
                        "ON `watch_history` (`watchedAt`)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `epg_programs_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`ordinal` INTEGER NOT NULL, " +
                        "`channelId` TEXT NOT NULL, " +
                        "`start` INTEGER NOT NULL, " +
                        "`stop` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL)",
                )
                db.execSQL(
                    "INSERT INTO `epg_programs_new` " +
                        "(`ordinal`,`channelId`,`start`,`stop`,`title`,`description`,`category`) " +
                        "SELECT rowid - 1,`channelId`,`start`,`stop`,`title`,`description`,`category` " +
                        "FROM `epg_programs` ORDER BY rowid",
                )
                db.execSQL("DROP TABLE `epg_programs`")
                db.execSQL("ALTER TABLE `epg_programs_new` RENAME TO `epg_programs`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_epg_programs_ordinal` " +
                        "ON `epg_programs` (`ordinal`)",
                )
            }
        }

        /** La URL de la guia EPG remota se suma a los ajustes (esquema 4). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `epgUrl` TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        fun create(context: Context): KzDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                KzDatabase::class.java,
                "kz.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}
