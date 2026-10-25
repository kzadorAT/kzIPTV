package ar.kziptv

import ar.kziptv.core.model.Channel
import ar.kziptv.core.parse.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private fun fixture(): String =
        javaClass.classLoader!!.getResourceAsStream("ar.m3u")!!.bufferedReader().readText()

    @Test
    fun parseaLaPlaylistRealDeIptvOrg() {
        val channels = M3uParser.parse(fixture(), "test")
        assertEquals(206, channels.size)
        assertTrue("todos con logo", channels.all { it.logo.isNotBlank() })
        assertTrue("todos con tvg-id", channels.all { it.tvgId.isNotBlank() })
        assertTrue("todos con nombre", channels.all { it.name.isNotBlank() })
    }

    @Test
    fun detectaFormatosDeStream() {
        val channels = M3uParser.parse(fixture(), "test")
        val hls = channels.count { it.kind == Channel.KIND_HLS }
        val dash = channels.count { it.kind == Channel.KIND_DASH }
        val unsupported = channels.count { it.kind == Channel.KIND_UNSUPPORTED }
        assertTrue("HLS >= 195 (fue ${hls})", hls >= 195)
        assertEquals("DASH = 2", 2, dash)
        assertTrue("hay al menos 1 no soportado", unsupported >= 1)
    }

    @Test
    fun extraeHeadersExtvlcopt() {
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="x.ar" tvg-logo="http://logo" group-title="News",Canal Test
            #EXTVLCOPT:http-user-agent=Mozilla/5.0 Test
            #EXTVLCOPT:http-referrer=https://ejemplo.com/
            http://ejemplo.com/stream.m3u8
        """.trimIndent()
        val channel = M3uParser.parse(content, "test").first()
        assertEquals("Canal Test", channel.name)
        assertEquals("News", channel.groups.first())
        assertEquals("Mozilla/5.0 Test", channel.headers["User-Agent"])
        assertEquals("https://ejemplo.com/", channel.headers["Referer"])
        assertEquals(Channel.KIND_HLS, channel.kind)
    }

    @Test
    fun nombreConComasSeConserva() {
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-id="y.ar",Canal, Con Coma
            http://ejemplo.com/stream.m3u8
        """.trimIndent()
        assertEquals("Canal, Con Coma", M3uParser.parse(content, "test").first().name)
    }
}
