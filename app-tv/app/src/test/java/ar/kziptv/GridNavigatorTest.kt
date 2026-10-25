package ar.kziptv

import ar.kziptv.core.nav.GridNavigator
import ar.kziptv.input.RemoteKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La navegacion del D-pad es determinista (ver docs/P0-STATUS.md §8): el foco lo
 * maneja el controller y la UI solo pinta el borde. Estos tests fijan el contrato.
 */
class GridNavigatorTest {

    private val left = RemoteKeys.DPAD_LEFT
    private val right = RemoteKeys.DPAD_RIGHT
    private val up = RemoteKeys.DPAD_UP
    private val down = RemoteKeys.DPAD_DOWN

    @Test
    fun derechaAvanzaDentroDeLaFila() {
        val move = GridNavigator.move(index = 6, size = 20, columns = 4, code = right)
        assertEquals(7, move.index)
        assertFalse(move.toChips)
    }

    @Test
    fun derechaNoDesbordaElBordeDeLaFila() {
        // indice 3 es el final de la primera fila (columnas 0..3)
        val move = GridNavigator.move(index = 3, size = 20, columns = 4, code = right)
        assertEquals("no debe saltar a la fila siguiente", 3, move.index)
    }

    @Test
    fun izquierdaNoCruzaALaFilaAnterior() {
        // el indice 4 es el primero (columna 0) de la segunda fila
        val move = GridNavigator.move(index = 4, size = 20, columns = 4, code = left)
        assertEquals("no debe cruzar de fila", 4, move.index)
    }

    @Test
    fun izquierdaDentroDeLaFila() {
        val move = GridNavigator.move(index = 6, size = 20, columns = 4, code = left)
        assertEquals(5, move.index)
    }

    @Test
    fun abajoSaltaUnaFilaCompleta() {
        val move = GridNavigator.move(index = 7, size = 20, columns = 4, code = down)
        assertEquals(11, move.index)
    }

    @Test
    fun arribaSaltaUnaFilaCompleta() {
        val move = GridNavigator.move(index = 11, size = 20, columns = 4, code = up)
        assertEquals(7, move.index)
    }

    @Test
    fun arribaEnLaPrimeraFilaSubeALosChips() {
        val move = GridNavigator.move(index = 2, size = 20, columns = 4, code = up)
        assertTrue("debe pedir el salto a la fila de categorias", move.toChips)
    }

    @Test
    fun abajoEnLaUltimaFilaNoSeSale() {
        // 20 elementos / 4 columnas = 5 filas exactas; el indice 19 es el final
        val move = GridNavigator.move(index = 19, size = 20, columns = 4, code = down)
        assertEquals(19, move.index)
    }

    @Test
    fun abajoEnLaFilaIncompletaNoSePasaDelUltimo() {
        // 19 elementos: la ultima fila esta incompleta (3 elementos: 16, 17, 18)
        val move = GridNavigator.move(index = 17, size = 19, columns = 4, code = down)
        assertEquals("ya esta en la ultima fila", 17, move.index)
    }

    @Test
    fun abajoDesdeLaFilaIncompletaAvanza() {
        val move = GridNavigator.move(index = 13, size = 19, columns = 4, code = down)
        assertEquals(17, move.index)
    }

    @Test
    fun indicesFueraDeRangoSeRecortan() {
        // -5 se recorta a 0 y al mover a la derecha avanza a 1
        assertEquals(1, GridNavigator.move(index = -5, size = 20, columns = 4, code = right).index)
        // 99 se recorta al ultimo (19, columna 3) y al mover a la izquierda va a 18
        assertEquals(18, GridNavigator.move(index = 99, size = 20, columns = 4, code = left).index)
    }

    @Test
    fun grillaVaciaNoSeMueve() {
        assertEquals(0, GridNavigator.move(index = 0, size = 0, columns = 4, code = right).index)
    }

    @Test
    fun chipsSeMuevenHorizontalmente() {
        assertEquals(2, GridNavigator.moveChip(index = 1, size = 6, code = right).index)
        assertEquals(0, GridNavigator.moveChip(index = 1, size = 6, code = left).index)
    }

    @Test
    fun chipsNoSeSalenDelRango() {
        assertEquals(0, GridNavigator.moveChip(index = 0, size = 6, code = left).index)
        assertEquals(5, GridNavigator.moveChip(index = 5, size = 6, code = right).index)
    }

    @Test
    fun abajoEnLosChipsVuelveALaGrilla() {
        val move = GridNavigator.moveChip(index = 2, size = 6, code = down)
        assertTrue(move.toGrid)
    }

    @Test
    fun listaVerticalRecorreLasVariantes() {
        assertEquals(1, GridNavigator.moveVertical(index = 0, size = 3, code = down))
        assertEquals(2, GridNavigator.moveVertical(index = 1, size = 3, code = down))
        assertEquals(0, GridNavigator.moveVertical(index = 0, size = 3, code = up))
        assertEquals(1, GridNavigator.moveVertical(index = 2, size = 3, code = up))
    }
}
