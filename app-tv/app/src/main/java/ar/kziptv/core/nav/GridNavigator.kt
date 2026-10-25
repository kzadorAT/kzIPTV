package ar.kziptv.core.nav

import ar.kziptv.input.RemoteKeys

/**
 * Navegacion determinista del D-pad.
 *
 * En la TV real el foco interno de Compose no enganchaba (ver docs/P0-STATUS.md §8),
 * asi que el indice de foco vive aca, en codigo puro y testeable: el controller de la
 * TV solo lo aplica y la UI se limita a pintar el borde y hacer scroll.
 *
 * La grilla se recorre por filas: LEFT/RIGHT mueven dentro de la fila y no desbordan
 * a la fila vecina; UP/DOWN saltan una fila completa.
 */
object GridNavigator {

    /** Resultado de mover el foco: donde quedo y si hay que cambiar de zona. */
    data class Move(
        val index: Int,
        val toChips: Boolean = false,
        val toGrid: Boolean = false,
        val chipIndex: Int = -1
    )

    /**
     * Mueve el foco dentro de una grilla de [size] elementos con [columns] columnas.
     *
     * @param index indice actual (se recorta al rango valido).
     * @param code keycode del D-pad.
     * @return el nuevo indice; [Move.toChips] vale true cuando se llega a la primera
     *         fila y hay que subir a la fila de categorias.
     */
    fun move(index: Int, size: Int, columns: Int, code: Int): Move {
        if (size <= 0) return Move(0)
        val cols = columns.coerceAtLeast(1)
        val current = index.coerceIn(0, size - 1)
        val row = current / cols
        val col = current % cols
        return when (code) {
            RemoteKeys.DPAD_LEFT ->
                Move(if (col > 0) current - 1 else current)
            RemoteKeys.DPAD_RIGHT ->
                Move(if (col < cols - 1 && current + 1 < size) current + 1 else current)
            RemoteKeys.DPAD_UP ->
                if (row > 0) Move(current - cols) else Move(current, toChips = true)
            RemoteKeys.DPAD_DOWN ->
                Move(if (current + cols < size) current + cols else current)
            else -> Move(current)
        }
    }

    /**
     * Mueve el foco dentro de la fila de categorias.
     *
     * @return el indice del chip; [Move.toGrid] vale true cuando hay que bajar de los
     *         chips a la grilla.
     */
    fun moveChip(index: Int, size: Int, code: Int): Move {
        if (size <= 0) return Move(0)
        val current = index.coerceIn(0, size - 1)
        return when (code) {
            RemoteKeys.DPAD_LEFT -> Move((current - 1).coerceAtLeast(0))
            RemoteKeys.DPAD_RIGHT -> Move((current + 1).coerceAtMost(size - 1))
            RemoteKeys.DPAD_DOWN -> Move(current, toGrid = true)
            else -> Move(current)
        }
    }

    /** Mueve el foco en una lista vertical (selector de calidad). */
    fun moveVertical(index: Int, size: Int, code: Int): Int {
        if (size <= 0) return 0
        val current = index.coerceIn(0, size - 1)
        return when (code) {
            RemoteKeys.DPAD_UP -> (current - 1).coerceAtLeast(0)
            RemoteKeys.DPAD_DOWN -> (current + 1).coerceAtMost(size - 1)
            else -> current
        }
    }
}
