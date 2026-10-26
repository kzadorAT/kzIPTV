package ar.kziptv.data.room

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KzDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KzDatabase::class.java,
    )

    @Test
    fun migraV1AV3SinPerderDatos() {
        val dbName = "migration-room-v1-v3"
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO playlists " +
                    "(id,name,url,bundled,channelCount,position) " +
                    "VALUES ('bundled-ar','Argentina','https://example.test/ar.m3u',1,206,0)",
            )
            execSQL(
                "INSERT INTO favorites (channelId,position) VALUES ('fav-1',0)",
            )
            execSQL(
                "INSERT INTO app_settings " +
                    "(id,pairingCode,zapFavoritesOnly,lastChannelId,lastGroupId) " +
                    "VALUES (1,'593819',0,'channel-1','group-1')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            dbName,
            3,
            true,
            KzDatabase.MIGRATION_1_2,
            KzDatabase.MIGRATION_2_3,
        )

        migrated.query("SELECT content FROM playlists WHERE id='bundled-ar'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("", cursor.getString(0))
        }
        migrated.query("SELECT pairingCode FROM app_settings WHERE id=1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("593819", cursor.getString(0))
        }
        migrated.execSQL(
            "INSERT INTO watch_history " +
                "(groupId,channelId,variantId,watchedAt) VALUES ('g1','c1','v1',123)",
        )
        migrated.query("SELECT COUNT(*) FROM watch_history").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        migrated.close()
    }
}
