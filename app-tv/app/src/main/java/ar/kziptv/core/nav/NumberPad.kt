package ar.kziptv.core.nav

/**
 * Teclado numerico en pantalla: para entrar un numero de canal sin teclas de numeros.
 *
 * Son 12 celdas en 3 columnas (1-9, borrar, 0, ir). Es codigo puro a proposito:
 * el D-pad de la TV no usa el foco nativo de Compose (ver docs/P0-STATUS.md §8), asi que
 * toda la interaccion se decide aca y se testea sin Android.
 */
object NumberPad {

    /** Columnas del teclado (3: como el teclado de un telefono). */
    const val COLUMNS = 3

    /** Cuantas celdas tiene el teclado. */
    const val CELLS = 12

    /** Indice de la celda "borrar" (⌫). */
    const val DELETE = 9

    /** Indice de la celda "ir" (▶): reproduce el canal escrito. */
    const val GO = 11

    /**
     * El digito de una celda, o null si la celda no es un digito
     * (la de borrar o la de ir).
     */
    fun digitAt(index: Int): Int? = when {
        index in 0..8 -> index + 1
        index == 10 -> 0
        else -> null
    }

    /** ¿La celda es la de borrar? */
    fun isDelete(index: Int): Boolean = index == DELETE

    /** ¿La celda es la de ir? */
    fun isGo(index: Int): Boolean = index == GO

    /**
     * Agrega un digito al numero escrito.
     *
     * Mismo criterio que el buffer de las teclas fisicas: sin ceros a la izquierda y
     * con un tope de digitos.
     */
    fun append(buffer: String, digit: Int): String = NumberEntry.append(buffer, digit)

    /** Borra el ultimo digito del numero escrito. */
    fun backspace(buffer: String): String = buffer.dropLast(1)
}
