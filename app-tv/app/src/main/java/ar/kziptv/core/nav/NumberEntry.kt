package ar.kziptv.core.nav

import ar.kziptv.core.catalog.ChannelCatalog
import ar.kziptv.core.model.ChannelGroup

/**
 * Entrada directa por numero de canal (teclas 0-9) y busqueda por texto.
 *
 * Es codigo puro a proposito: el D-pad de la TV ya no usa el foco nativo de Compose
 * (ver docs/P0-STATUS.md §8), asi que toda la interaccion se decide aca y se
 * testea sin Android.
 */
object NumberEntry {

    /** Cuanto tiempo queda el numero escrito antes de darlo por confirmado. */
    const val TIMEOUT_MS = 2_500L

    /** Maximo de digitos: el catalogo tiene 201 canales, o sea 3 digitos. */
    const val MAX_DIGITS = 3

    /**
     * Agrega un digito al buffer.
     *
     * @return el buffer nuevo (sin ceros a la izquierda).
     */
    fun append(buffer: String, digit: Int): String {
        if (digit !in 0..9) return buffer
        // Un "0" inicial se ve, pero se descarta al escribir el siguiente digito.
        if (buffer.isEmpty() && digit == 0) return "0"
        val next = (buffer + digit.toString()).trimStart('0')
        return next.takeLast(MAX_DIGITS)
    }

    /**
     * Resuelve el numero escrito al canal logico que lo tiene.
     *
     * @return el canal si existe, null si el numero no corresponde a ninguno.
     */
    fun resolve(catalog: ChannelCatalog, buffer: String): ChannelGroup? {
        val number = buffer.trimStart('0').toIntOrNull() ?: return null
        if (number <= 0) return null
        return catalog.groups.firstOrNull { it.number == number }
    }

    /**
     * Normaliza lo que escribe el usuario para que la busqueda tolere acentos y
     * mayusculas: "cartoon network" tiene que encontrar "Cartoon Network".
     */
    fun normalize(text: String): String =
        text.trim().lowercase()
            .replace("á", "a").replace("é", "e").replace("í", "i")
            .replace("ó", "o").replace("ú", "u").replace("ü", "u")
            .replace("ñ", "n")

    /**
     * Busca canales por nombre, tvg-id, categoria o **numero de canal**.
     *
     * @param query texto libre. Si son solo digitos, tambien matchea el numero.
     */
    fun search(catalog: ChannelCatalog, query: String): List<ChannelGroup> {
        val q = normalize(query)
        if (q.isEmpty()) return catalog.groups
        val asNumber = q.toIntOrNull()
        val matches = catalog.groups.filter { group ->
            normalize(group.name).contains(q) ||
                normalize(group.tvgId).contains(q) ||
                group.variants.any { normalize(it.name).contains(q) } ||
                group.groups.any { normalize(it).contains(q) } ||
                (asNumber != null && group.number == asNumber)
        }
        if (asNumber == null) return matches
        // Si el texto es un numero, el canal con ese numero va primero: escribir
        // "5" tiene que llevar al canal 5, no a "LAMIL35 TV".
        return matches.sortedBy { if (it.number == asNumber) 0 else 1 }
    }
}
