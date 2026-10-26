package ar.kziptv.input

object RemoteKeys {
    const val CHANNEL_UP = 166      // KEYCODE_CHANNEL_UP
    const val CHANNEL_DOWN = 167    // KEYCODE_CHANNEL_DOWN
    const val PROG_RED = 183
    const val PROG_GREEN = 184
    const val PROG_YELLOW = 185
    const val PROG_BLUE = 186
    const val MENU = 82
    /** Boton INFO: abre el mosaico para comparar canales. */
    const val INFO = 172
    const val MEDIA_PLAY_PAUSE = 85
    const val OK = 23               // KEYCODE_DPAD_CENTER
    const val ENTER = 66
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22

    /** Digitos 0..9 del control remoto (KEYCODE_0..KEYCODE_9). */
    const val DIGIT_0 = 7
    const val DIGIT_9 = 16

    /** Digitos del teclado numerico (KEYCODE_NUMPAD_0..KEYCODE_NUMPAD_9). */
    const val NUMPAD_0 = 96
    const val NUMPAD_9 = 105

    /**
     * Devuelve el digito de un keycode (0..9) o null si no es un numero.
     * Manyos controles remotos mandan KEYCODE_NUMPAD_* cuando el usuario
     * escribe un numero en la pantalla de busqueda.
     */
    fun digitOf(keyCode: Int): Int? = when (keyCode) {
        in DIGIT_0..DIGIT_9 -> keyCode - DIGIT_0
        in NUMPAD_0..NUMPAD_9 -> keyCode - NUMPAD_0
        else -> null
    }
}
