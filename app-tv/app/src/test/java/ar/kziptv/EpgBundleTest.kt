package ar.kziptv

import ar.kziptv.core.epg.EpgIndex
import ar.kziptv.core.epg.EpgParser
import ar.kziptv.core.parse.M3uParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Verifica la cadena completa del EPG:
 * assets/epg/ar.xml.gz -> EpgParser -> EpgIndex -> match con la playlist real.
 */
class EpgBundleTest {

    private fun bundledEpg(): String {
        val gz = File("src/main/assets/epg/ar.xml.gz")
        assertTrue("falta el EPG empaquetado en los assets", gz.exists())
        return GZIPInputStream(gz.inputStream()).bufferedReader().readText()
    }

    @Test
    fun elEpgEmpaquetadoTraeProgramas() {
        val programs = EpgParser.parse(bundledEpg())
        assertTrue("el EPG deberia traer programs (fue ${programs.size})", programs.size > 1000)
    }

    @Test
    fun elEpgMatcheaLosCanalesDeLaPlaylist() {
        val programs = EpgParser.parse(bundledEpg())
        val index = EpgIndex(programs)
        val channels = M3uParser.parse(File("src/test/resources/ar.m3u").readText(), "ar")
        val conGuia = channels.count { index.forChannel(it).isNotEmpty() }
        assertTrue("deberian matchear al menos 40 canales (fue ${conGuia})", conGuia >= 40)
    }

    @Test
    fun ahoraYSiguienteFuncionanParaUnCanalConGuia() {
        val programs = EpgParser.parse(bundledEpg())
        val index = EpgIndex(programs)
        val channels = M3uParser.parse(File("src/test/resources/ar.m3u").readText(), "ar")
        val conGuia = channels.first { index.forChannel(it).isNotEmpty() }
        val (now, next) = index.nowNext(conGuia)
        assertTrue("deberia tener un programa en curso o proximo", now != null || next != null)
    }
}
