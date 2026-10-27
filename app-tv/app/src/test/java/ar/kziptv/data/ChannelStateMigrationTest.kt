package ar.kziptv.data

import ar.kziptv.core.catalog.ChannelCatalog
import ar.kziptv.core.parse.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El refresh cambia el nombre y la URL del feed, pero el canal logico sigue siendo el
 * mismo: favoritos, calidad, salud y numero deben sobrevivir al cambio.
 */
class ChannelStateMigrationTest {

    private val oldM3u = """
        #EXTM3U
        #EXTINF:-1 tvg-id="Canal.ar" group-title="Noticias",Canal Antiguo
        http://viejo.example/1.m3u8
    """.trimIndent()

    private val newM3u = """
        #EXTM3U
        #EXTINF:-1 tvg-id="Canal.ar" group-title="Noticias",Canal Nuevo
        http://nuevo.example/1.m3u8
    """.trimIndent()

    @Test
    fun conservaElEstadoCuandoCambianNombreYUrl() {
        val oldChannels = M3uParser.parse(oldM3u, "src-1")
        val newChannels = M3uParser.parse(newM3u, "src-1")
        val oldCatalog = ChannelCatalog(oldChannels)
        val newCatalog = ChannelCatalog(newChannels)
        val oldGroup = oldCatalog.groups.single()
        val newGroup = newCatalog.groups.single()
        val oldVariant = oldGroup.variants.single()
        val newVariant = newGroup.variants.single()

        // El ID legacy cambia con el nombre, pero la clave canonica se mantiene.
        assertTrue(oldGroup.id != newGroup.id)
        assertEquals(oldGroup.stateKey, newGroup.stateKey)
        assertEquals(oldVariant.stateKey, newVariant.stateKey)

        val state = PersistedState(
            favorites = listOf(oldGroup.id),
            qualityPreference = mapOf(oldGroup.id to oldVariant.channelId),
            variantStatus = mapOf(oldVariant.channelId to "ok"),
            measuredResolution = mapOf(oldVariant.channelId to "1280x720"),
            channelNumbers = mapOf(oldGroup.id to 7),
            lastGroupId = oldGroup.id,
            lastChannelId = oldVariant.channelId,
        )

        val migrated = ChannelStateMigration.migrate(state, newCatalog, oldCatalog)

        assertEquals(listOf(newGroup.stateKey), migrated.favorites)
        assertEquals(mapOf(newGroup.stateKey to newVariant.stateKey), migrated.qualityPreference)
        assertEquals(mapOf(newVariant.stateKey to "ok"), migrated.variantStatus)
        assertEquals(mapOf(newVariant.stateKey to "1280x720"), migrated.measuredResolution)
        assertEquals(mapOf(newGroup.stateKey to 7), migrated.channelNumbers)
        assertEquals(newGroup.stateKey, migrated.lastGroupId)
        assertEquals(newVariant.stateKey, migrated.lastChannelId)
    }

    @Test
    fun conservaFavoritoHuerfanoYBorraPreferenciaSinDestino() {
        val oldChannels = M3uParser.parse(oldM3u, "src-1")
        val newChannels = M3uParser.parse(newM3u, "src-1")
        val oldCatalog = ChannelCatalog(oldChannels)
        val newCatalog = ChannelCatalog(newChannels)
        val oldGroup = oldCatalog.groups.single()
        val oldVariant = oldGroup.variants.single()

        val state = PersistedState(
            favorites = listOf("g:no-existe"),
            // La preferencia apunta a un feed que ya no esta en la playlist nueva.
            qualityPreference = mapOf(oldGroup.id to "feed-que-no-existe"),
            variantStatus = mapOf("feed-que-no-existe" to "dead#1"),
        )

        val migrated = ChannelStateMigration.migrate(state, newCatalog, oldCatalog)

        // El huerfano se conserva para que el canal pueda volver.
        assertEquals(listOf("g:no-existe"), migrated.favorites)
        assertTrue(migrated.qualityPreference.isEmpty())
        assertTrue(migrated.variantStatus.isEmpty())
        assertTrue(oldVariant.stateKey.isNotBlank())
    }
}
