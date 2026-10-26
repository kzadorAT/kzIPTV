package ar.kziptv.data.room

import ar.kziptv.data.PersistedSource
import ar.kziptv.data.PersistedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomStateMapperTest {

    @Test
    fun roundTripConservaTodoElEstado() {
        val state = PersistedState(
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
            tokens = listOf("token-1", "token-2"),
            lastChannelId = "channel-7",
            lastGroupId = "group-7",
            zapFavoritesOnly = true,
            qualityPreference = mapOf("group-1" to "variant-2"),
            variantStatus = mapOf("variant-1" to "ok", "variant-2" to "dead#123"),
            measuredResolution = mapOf("variant-1" to "1280x720"),
        )

        val restored = RoomStateMapper.persistedState(
            playlists = RoomStateMapper.playlists(state),
            favorites = RoomStateMapper.favorites(state),
            tokens = RoomStateMapper.deviceTokens(state),
            settings = RoomStateMapper.appSettings(state),
            qualityPreferences = RoomStateMapper.qualityPreferences(state),
            variantStates = RoomStateMapper.variantStates(state),
        )

        assertEquals(state, restored)
    }

    @Test
    fun uneEstadoYResolucionDeLaMismaVariante() {
        val state = PersistedState(
            variantStatus = mapOf("a" to "ok"),
            measuredResolution = mapOf("a" to "1920x1080", "b" to "720x480"),
        )

        val rows = RoomStateMapper.variantStates(state)

        assertEquals(2, rows.size)
        assertTrue(rows.any { it.variantId == "a" && it.status == "ok" && it.measuredResolution == "1920x1080" })
        assertTrue(rows.any { it.variantId == "b" && it.status.isBlank() && it.measuredResolution == "720x480" })
    }
}
