package ar.kziptv

import ar.kziptv.core.nav.SurfPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El "surf" del mosaico: que canal se captura despues.
 *
 * El mosaico captura de a un canal (un solo decoder de hardware), asi que el
 * orden importa: primero el canal que mira el usuario, despues una vuelta
 * completa de la pagina.
 */
class SurfPlannerTest {

    private val total = 16

    private fun plan(
        now: Long,
        focus: Int = 0,
        focusDirty: Boolean = false,
        lastFocusAt: Long = 0L,
        lastPageAt: Long = 0L,
        nextIndex: Int = 0,
        pagePending: Boolean = false
    ) = SurfPlanner.plan(
        total = total,
        focus = focus,
        now = now,
        focusDirty = focusDirty,
        lastFocusAt = lastFocusAt,
        lastPageAt = lastPageAt,
        nextIndex = nextIndex,
        pagePending = pagePending
    )

    @Test
    fun `sin nada vencido no captura nada`() {
        val p = plan(now = 10_000, lastFocusAt = 10_000, lastPageAt = 10_000)
        assertEquals(-1, p.target)
    }

    @Test
    fun `el foco movido tiene prioridad`() {
        val p = plan(now = 10_000, focus = 7, focusDirty = true, lastFocusAt = 10_000)
        assertEquals(7, p.target)
    }

    @Test
    fun `el tile enfocado vence su plazo`() {
        val p = plan(
            now = 10_000 + SurfPlanner.FOCUS_MS,
            focus = 3,
            lastFocusAt = 10_000,
            lastPageAt = 10_000
        )
        assertEquals(3, p.target)
    }

    @Test
    fun `el tile enfocado se refresca antes que la pagina`() {
        // El foco esta vencido y la pagina tambien: gana el foco.
        val p = plan(
            now = 10_000 + SurfPlanner.FOCUS_MS,
            focus = 3,
            lastFocusAt = 10_000,
            lastPageAt = 0L
        )
        assertEquals(3, p.target)
    }

    @Test
    fun `la pagina arranca una vuelta completa`() {
        // Pasaron 500 s desde la ultima vuelta: la pagina esta vencida.
        // El foco esta en el tile 5: la vuelta puede arrancar por el 0.
        val p = plan(now = 500_000, focus = 5, lastFocusAt = 500_000, lastPageAt = 0L)
        assertEquals(0, p.target)
        assertTrue(p.pagePending)
        assertEquals(1, p.nextIndex)
    }

    @Test
    fun `la vuelta avanza tile por tile`() {
        var p = plan(now = 500_000, focus = 5, lastFocusAt = 500_000, lastPageAt = 0L)
        assertEquals(0, p.target)
        p = SurfPlanner.plan(total, 5, 500_000, false, 500_000, p.lastPageAt, p.nextIndex, p.pagePending)
        assertEquals(1, p.target)
        p = SurfPlanner.plan(total, 5, 500_000, false, 500_000, p.lastPageAt, p.nextIndex, p.pagePending)
        assertEquals(2, p.target)
    }

    @Test
    fun `la vuelta saltea el tile enfocado`() {
        // La vuelta esta por llegar al tile enfocado (indice 2).
        val p = plan(
            now = 10_000,
            focus = 2,
            lastFocusAt = 10_000,
            lastPageAt = 0L,
            nextIndex = 2,
            pagePending = true
        )
        assertEquals(-1, p.target)
        assertEquals(3, p.nextIndex)
        assertTrue(p.pagePending)
    }

    @Test
    fun `la vuelta termina al dar la vuelta`() {
        val p = plan(
            now = 10_000,
            lastFocusAt = 10_000,
            lastPageAt = 0L,
            nextIndex = total - 1,
            pagePending = true
        )
        assertEquals(total - 1, p.target)
        assertEquals(0, p.nextIndex)
        assertFalse(p.pagePending)
    }

    @Test
    fun `una pagina vacia no captura nada`() {
        val p = SurfPlanner.plan(0, 0, 10_000, true, 0L, 0L, 0, false)
        assertEquals(-1, p.target)
    }

    @Test
    fun `un foco fuera de rango se corrige`() {
        val p = SurfPlanner.plan(total, 99, 10_000, true, 10_000, 10_000, 0, false)
        assertEquals(0, p.target)
    }
}
