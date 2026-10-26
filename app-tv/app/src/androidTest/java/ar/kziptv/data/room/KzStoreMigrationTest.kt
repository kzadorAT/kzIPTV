package ar.kziptv.data.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ar.kziptv.data.KzStore
import ar.kziptv.data.PersistedSource
import ar.kziptv.data.PersistedState
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KzStoreMigrationTest {

    @Test
    fun importaElJsonLegadoYLoArchiva() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileName = "kz-state-test.json"
        val legacyFile = File(context.filesDir, fileName)
        val backupFile = File(context.filesDir, "$fileName.room-backup")
        legacyFile.delete()
        backupFile.delete()

        val legacy = PersistedState(
            sources = listOf(
                PersistedSource(
                    id = "bundled-ar",
                    name = "Argentina",
                    url = "https://example.test/ar.m3u",
                    bundled = true,
                    channelCount = 206,
                ),
            ),
            favorites = listOf("fav-1", "fav-2"),
            pairingCode = "593819",
            tokens = listOf("token-1"),
            lastChannelId = "channel-7",
            lastGroupId = "group-7",
            zapFavoritesOnly = true,
            qualityPreference = mapOf("group-1" to "variant-2"),
            variantStatus = mapOf("variant-1" to "ok"),
            measuredResolution = mapOf("variant-1" to "1280x720"),
        )
        legacyFile.writeText(Json.encodeToString(PersistedState.serializer(), legacy))

        val db = Room.inMemoryDatabaseBuilder(context, KzDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val store = KzStore(context, db, fileName)

        val loaded = store.initialize()

        assertEquals(legacy, loaded)
        assertFalse("el JSON original debe quedar archivado", legacyFile.exists())
        assertTrue("debe quedar un backup del JSON", backupFile.exists())
        assertEquals(2, db.kzDao().getFavorites().size)
        assertEquals("593819", db.kzDao().getAppSettings()?.pairingCode)

        store.close()
        backupFile.delete()
        Unit
    }
}
