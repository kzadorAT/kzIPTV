package ar.kziptv

import ar.kziptv.core.epg.EpgIndex
import ar.kziptv.core.epg.EpgParser
import ar.kziptv.core.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgParserTest {

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="A24.ar@SD"><display-name>A24</display-name></channel>
          <programme start="20261025183000 +0300" stop="20261025200000 +0300" channel="A24.ar@SD">
            <title>Noticias</title><desc>Edition central</desc><category>News</category>
          </programme>
        </tv>
    """.trimIndent()

    @Test
    fun parseaProgramasXmltv() {
        val programs = EpgParser.parse(xml)
        assertEquals(1, programs.size)
        assertEquals("Noticias", programs.first().title)
        assertEquals("News", programs.first().category)
        assertTrue(programs.first().stop > programs.first().start)
    }

    @Test
    fun matchPorTvgIdExactoYNormalizado() {
        val index = EpgIndex(EpgParser.parse(xml))
        val exact = Channel(id = "1", sourceId = "s", tvgId = "A24.ar@SD", name = "A24", url = "u")
        val normalized = Channel(id = "2", sourceId = "s", tvgId = "A24.ar", name = "A24", url = "u")
        assertNotNull(index.forChannel(exact).firstOrNull())
        assertNotNull(index.forChannel(normalized).firstOrNull())
    }

    @Test
    fun calculaAhoraYSiguiente() {
        val index = EpgIndex(EpgParser.parse(xml))
        val channel = Channel(id = "1", sourceId = "s", tvgId = "A24.ar@SD", name = "A24", url = "u")
        val program = index.forChannel(channel).first()
        val (now, next) = index.nowNext(channel, program.start + 1000)
        assertEquals("Noticias", now?.title)
        assertEquals(null, next?.title)
    }
}
