package ar.kziptv

import ar.kziptv.core.catalog.ChannelGrouper
import ar.kziptv.core.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelGrouperTest {

    private fun channel(
        id: String,
        name: String,
        tvgId: String = "",
        url: String = "http://example.com/$id.m3u8"
    ) = Channel(id = id, sourceId = "test", tvgId = tvgId, name = name, url = url)

    @Test
    fun agrupaPorTvgIdSinCalificador() {
        val groups = ChannelGrouper.group(
            listOf(
                channel("1", "Cartoon Network Latin America", "CartoonNetworkLatinAmerica.us@SouthAtlanticHD"),
                channel("2", "Cartoon Network Latin America South Atlantic (1080p)", "CartoonNetworkLatinAmerica.us@SouthAtlantic")
            )
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups.first().variants.size)
        assertEquals("Cartoon Network Latin America", groups.first().name)
    }

    @Test
    fun unePorNombreCuandoElTvgIdDifiere() {
        val groups = ChannelGrouper.group(
            listOf(
                channel("1", "Telefe (1080p)", "Telefe.ar@SD"),
                channel("2", "Telefe", "OtroTelefe.ar@HD")
            )
        )
        assertEquals("mismo nombre limpio -> mismo canal", 1, groups.size)
        assertEquals(2, groups.first().variants.size)
    }

    @Test
    fun noUneCanalesDistintos() {
        val groups = ChannelGrouper.group(
            listOf(
                channel("1", "Telefe (1080p)", "Telefe.ar@SD"),
                channel("2", "Telefe Interior (720p)", "TelefeInterior.ar@SD")
            )
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun sacaCalidadYRegionDelNombre() {
        assertEquals("Disney Channel Latin America", ChannelGrouper.cleanName("Disney Channel Latin America Panregional HD (1080p)", "PanregionalHD"))
        assertEquals("Cartoon Network Latin America", ChannelGrouper.cleanName("Cartoon Network Latin America South Atlantic (1080p)", "SouthAtlantic"))
        assertEquals("Allegro HD", ChannelGrouper.cleanName("Allegro HD (1080p)", "SD"))
    }

    @Test
    fun sacaLaCalidadDeLaVariante() {
        assertEquals("1080p", ChannelGrouper.qualityOf("A24 (1080p)", "SD"))
        assertEquals("hd", ChannelGrouper.qualityOf("Cartoon Network Latin America", "SouthAtlanticHD"))
        assertEquals("", ChannelGrouper.qualityOf("Disney Channel Latin America South", "South"))
    }

    @Test
    fun sacaLaRegionDeLaVariante() {
        assertEquals("South Atlantic", ChannelGrouper.regionOf("SouthAtlanticHD"))
        assertEquals("Panregional", ChannelGrouper.regionOf("PanregionalHD"))
        assertEquals("", ChannelGrouper.regionOf("SD"))
    }

    @Test
    fun ordenaVariantesPorCalidad() {
        val groups = ChannelGrouper.group(
            listOf(
                channel("1", "A24 (720p)", "A24.ar@SD"),
                channel("2", "A24 (1080p)", "A24.ar@HD")
            )
        )
        val variants = groups.first().variants
        assertEquals("1080p", variants.first().quality)
        assertTrue("la mejor calidad va primera", variants.first().quality == "1080p")
    }
}
