package ar.kziptv.core.nav

import ar.kziptv.input.RemoteKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El teclado numerico en pantalla (ir a canal # sin teclas de numeros).
 */
class NumberPadTest {

    @Test
    fun `las celdas son 1 a 9 arriba y borrar 0 ir abajo`() {
        assertEquals(1, NumberPad.digitAt(0))
        assertEquals(9, NumberPad.digitAt(8))
        assertEquals(0, NumberPad.digitAt(10))
        assertNull(NumberPad.digitAt(NumberPad.DELETE))
        assertNull(NumberPad.digitAt(NumberPad.GO))
    }

    @Test
    fun `la celda 9 es borrar y la celda 11 es ir`() {
        assertTrue(NumberPad.isDelete(9))
        assertFalse(NumberPad.isGo(9))
        assertTrue(NumberPad.isGo(11))
        assertFalse(NumberPad.isDelete(11))
    }

    @Test
    fun `el numero escrito no tiene ceros a la izquierda`() {
        assertEquals("0", NumberPad.append("", 0))
        assertEquals("7", NumberPad.append("0", 7))
        assertEquals("42", NumberPad.append("4", 2))
    }

    @Test
    fun `el numero escrito admite como mucho tres digitos`() {
        assertEquals("234", NumberPad.append("2", 3).let { NumberPad.append(it, 4) })
        // Con mas de tres digitos se quedan los ultimos tres: el numero escrito
        // es siempre el que se esta tecleando.
        assertEquals("345", NumberPad.append("234", 5))
        assertEquals("456", NumberPad.append("345", 6))
    }

    @Test
    fun `borrar saca el ultimo digito`() {
        assertEquals("12", NumberPad.backspace("123"))
        assertEquals("", NumberPad.backspace("7"))
        assertEquals("", NumberPad.backspace(""))
    }

    @Test
    fun `el d-pad recorre el teclado de a tres celdas`() {
        // 1 2 3 / 4 5 6 / 7 8 9 / borrar 0 ir
        assertEquals(1, GridNavigator.move(0, NumberPad.CELLS, NumberPad.COLUMNS, RemoteKeys.DPAD_RIGHT).index)
        assertEquals(2, GridNavigator.move(1, NumberPad.CELLS, NumberPad.COLUMNS, RemoteKeys.DPAD_RIGHT).index)
        // No se sale de la fila: en la celda 3 el RIGHT no hace nada.
        assertEquals(2, GridNavigator.move(2, NumberPad.CELLS, NumberPad.COLUMNS, RemoteKeys.DPAD_RIGHT).index)
        // Abajo salta una fila entera.
        assertEquals(5, GridNavigator.move(2, NumberPad.CELLS, NumberPad.COLUMNS, RemoteKeys.DPAD_DOWN).index)
        // Arriba vuelve.
        assertEquals(2, GridNavigator.move(5, NumberPad.CELLS, NumberPad.COLUMNS, RemoteKeys.DPAD_UP).index)
        // LEFT en la primera celda no se sale.
        assertEquals(0, GridNavigator.move(0, NumberPad.CELLS, NumberPad.COLUMNS, RemoteKeys.DPAD_LEFT).index)
    }
}
