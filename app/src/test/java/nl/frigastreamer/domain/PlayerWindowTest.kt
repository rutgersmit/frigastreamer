package nl.frigastreamer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerWindowTest {
    private fun ids(slots: List<WindowSlot>) = slots.map { it.cameraIndex }

    @Test
    fun `een camera geeft altijd precies een speler`() {
        for (p in 0..2) {
            val w = PlayerWindow.compute(1, 0, p)
            assertEquals(listOf(WindowSlot(0, PlayerRole.ACTIVE)), w)
        }
    }

    @Test
    fun `twee camera's geven nooit twee spelers voor dezelfde camera`() {
        for (active in 0..1) for (p in 0..2) {
            val w = PlayerWindow.compute(2, active, p)
            assertEquals(ids(w).toSet().size, w.size)
            assertEquals(if (p == 0) 1 else 2, w.size)
            assertEquals(active, w.first().cameraIndex)
        }
    }

    @Test
    fun `preload 1 is de volgende camera, preload 2 ook de vorige`() {
        assertEquals(listOf(5), ids(PlayerWindow.compute(10, 5, 0)))
        assertEquals(listOf(5, 6), ids(PlayerWindow.compute(10, 5, 1)))
        assertEquals(listOf(5, 6, 4), ids(PlayerWindow.compute(10, 5, 2)))
    }

    @Test
    fun `wrap-around aan beide randen`() {
        assertEquals(listOf(0, 1, 9), ids(PlayerWindow.compute(10, 0, 2)))
        assertEquals(listOf(9, 0, 8), ids(PlayerWindow.compute(10, 9, 2)))
        assertEquals(listOf(9, 0), ids(PlayerWindow.compute(10, -1, 1)))
    }

    @Test
    fun `nooit meer dan drie spelers en geen duplicaten`() {
        for (n in 1..15) for (a in -20..20) for (p in -1..5) {
            val w = PlayerWindow.compute(n, a, p)
            assertTrue(w.size <= PlayerWindow.MAX_PLAYERS)
            assertEquals(w.size, ids(w).toSet().size)
            assertEquals(1, w.count { it.role == PlayerRole.ACTIVE })
            assertTrue(w.all { it.cameraIndex in 0 until n })
        }
        assertTrue(PlayerWindow.compute(0, 0, 2).isEmpty())
    }

    @Test
    fun `pagina naar camera-index met wrap-around`() {
        assertEquals(0, PlayerWindow.cameraIndexForPage(0, 3))
        assertEquals(2, PlayerWindow.cameraIndexForPage(-1, 3))
        assertEquals(1, PlayerWindow.cameraIndexForPage(1_000_000, 3))
        for (n in 1..12) {
            val total = PlayerWindow.virtualPageCount(n)
            if (n > 1) assertEquals(0, total % n)
            for (idx in 0 until n) {
                val start = PlayerWindow.initialPage(n, idx)
                assertEquals(idx, PlayerWindow.cameraIndexForPage(start, n))
                assertTrue(start in 0 until maxOf(total, 1))
                // Vanaf de startpagina kan in beide richtingen ruim geswipet worden.
                if (n > 1) assertTrue(start > 1000 && total - start > 1000)
            }
        }
        assertEquals(1, PlayerWindow.virtualPageCount(1))
    }

    @Test
    fun `speler wordt aan precies een pagina gekoppeld, ook bij 2 camera's`() {
        for (n in 1..6) {
            val current = PlayerWindow.initialPage(n, 0)
            // Alle pagina's die tegelijk gecomposed kunnen zijn (huidige +-1).
            val pages = (current - 1)..(current + 1)
            for (idx in 0 until n) {
                val bound = PlayerWindow.boundPageFor(idx, current, n)
                assertEquals(idx, PlayerWindow.cameraIndexForPage(bound, n))
                val candidates = pages.filter { PlayerWindow.cameraIndexForPage(it, n) == idx }
                assertTrue(candidates.count { it == bound } <= 1)
            }
        }
        // 2 camera's: links en rechts staat dezelfde camera; alleen rechts krijgt de speler.
        val cur = PlayerWindow.initialPage(2, 0)
        assertEquals(cur + 1, PlayerWindow.boundPageFor(1, cur, 2))
        assertEquals(cur, PlayerWindow.boundPageFor(0, cur, 2))
    }
}
