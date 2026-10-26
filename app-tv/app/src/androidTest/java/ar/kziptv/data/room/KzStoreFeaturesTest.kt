package ar.kziptv.data.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ar.kziptv.core.model.EpgProgram
import ar.kziptv.data.KzStore
import ar.kziptv.data.PersistedSource
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KzStoreFeaturesTest {

    @Test
    fun cacheaEpgInvalidaPorFingerprintYGuardaHistorial() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, KzDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val store = KzStore(context, db, "kz-state-features-test.json")
        store.initialize()

        val programs = listOf(
            EpgProgram(
                channelId = "news.ar",
                start = 1000,
                stop = 2000,
                title = "Noticiero",
                description = "Resumen",
                category = "News",
            ),
        )
        store.cacheEpgPrograms("fingerprint-1", programs)

        assertEquals(programs, store.cachedEpgPrograms("fingerprint-1"))
        assertNull(store.cachedEpgPrograms("fingerprint-distinto"))

        store.recordWatch("group-1", "channel-1", "variant-1", watchedAt = 1234)
        val history = store.recentHistory()
        assertEquals(1, history.size)
        assertEquals("group-1", history.single().groupId)
        assertEquals(1234, history.single().watchedAt)

        store.close()
        File(context.filesDir, "kz-state-features-test.json").delete()
        Unit
    }

    @Test
    fun serializaUpdatesConcurrentesSinPerderDatos() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, KzDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val store = KzStore(context, db, "kz-state-concurrency-test.json")
        store.initialize()

        val jobs = (1..20).map { index ->
            async {
                store.update { state ->
                    state.copy(
                        favorites = state.favorites + "fav-$index",
                        tokens = state.tokens + "token-$index",
                    )
                }
            }
        }
        jobs.awaitAll()

        val state = store.state.value
        assertEquals(20, state.favorites.size)
        assertEquals(20, state.tokens.size)
        assertTrue(state.favorites.contains("fav-20"))
        assertTrue(state.tokens.contains("token-20"))

        store.close()
    }

    @Test
    fun conservaElContenidoDeUnaPlaylistSubida() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, KzDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val store = KzStore(context, db, "kz-state-content-test.json")
        store.initialize()

        store.update { state ->
            state.copy(
                sources = state.sources + PersistedSource(
                    id = "file-1",
                    name = "Mi lista",
                    content = "#EXTM3U\n#EXTINF:-1,Canal",
                ),
            )
        }

        assertEquals("#EXTM3U\n#EXTINF:-1,Canal", store.state.value.sources.single().content)
        store.close()
    }
}
