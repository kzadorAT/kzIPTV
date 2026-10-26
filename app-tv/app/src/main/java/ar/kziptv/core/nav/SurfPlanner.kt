package ar.kziptv.core.nav

/**
 * Planificador del "surf" del mosaico.
 *
 * El mosaico no puede mostrar varios canales a la vez (la TV tiene UN solo
 * decoder de video por hardware), asi que los tiles se van capturando de a
 * uno. Este objeto decide CUAL es el siguiente canal a capturar, sin depender
 * de Android: se testea solo.
 *
 * Ritmo:
 *  - el tile ENFOCADO (el que esta mirando el usuario) se refresca cada
 *    [FOCUS_MS]: el canal se despierta unos segundos y vuelve a congelarse;
 *  - el resto de la pagina se refresca en rolling: una vuelta cada [PAGE_MS];
 *  - si el usuario mueve el foco, ese tile tiene prioridad absoluta.
 */
object SurfPlanner {

    /** Cada cuanto se refresca el tile enfocado. */
    const val FOCUS_MS = 15_000L

    /** Cada cuanto arranca una vuelta completa de la pagina. */
    const val PAGE_MS = 120_000L

    /** Resultado de la planificacion. */
    data class Plan(
        /** Tile a capturar, o -1 si no hay nada que hacer. */
        val target: Int,
        val lastFocusAt: Long,
        val lastPageAt: Long,
        val nextIndex: Int,
        val pagePending: Boolean
    )

    /**
     * Decide el proximo tile a capturar.
     *
     * @param total cantidad de tiles de la pagina.
     * @param focus indice del tile enfocado.
     * @param now momento actual (ms).
     * @param focusDirty el usuario movio el foco: hay que capturar ese tile ya.
     * @param lastFocusAt momento de la ultima captura del tile enfocado.
     * @param lastPageAt momento en que arranco la ultima vuelta de la pagina.
     * @param nextIndex proximo tile de la vuelta en curso.
     * @param pagePending hay una vuelta de la pagina en curso.
     */
    fun plan(
        total: Int,
        focus: Int,
        now: Long,
        focusDirty: Boolean,
        lastFocusAt: Long,
        lastPageAt: Long,
        nextIndex: Int,
        pagePending: Boolean
    ): Plan {
        if (total <= 0) {
            return Plan(-1, lastFocusAt, lastPageAt, nextIndex, pagePending)
        }
        val safeFocus = if (focus in 0 until total) focus else 0
        val focusDue = focusDirty || now - lastFocusAt >= FOCUS_MS
        if (focusDue) {
            // El tile que mira el usuario tiene prioridad absoluta.
            return Plan(safeFocus, lastFocusAt, lastPageAt, nextIndex, pagePending)
        }

        var next = nextIndex
        var pending = pagePending
        var pageAt = lastPageAt
        if (!pending && now - pageAt >= PAGE_MS) {
            // Arranca una vuelta completa de la pagina.
            pending = true
            next = 0
            pageAt = now
        }
        if (!pending) {
            // Nada vencido todavia: se espera un toque.
            return Plan(-1, lastFocusAt, pageAt, next, pending)
        }

        val target = next
        next = (next + 1) % total
        if (next == 0) pending = false
        if (target == safeFocus) {
            // El tile enfocado tiene su propio refresco: la vuelta lo saltea.
            return Plan(-1, lastFocusAt, pageAt, next, pending)
        }
        return Plan(target, lastFocusAt, pageAt, next, pending)
    }
}
