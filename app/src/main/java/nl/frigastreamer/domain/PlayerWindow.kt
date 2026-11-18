package nl.frigastreamer.domain

data class WindowSlot(val cameraIndex: Int, val role: PlayerRole)

/**
 * Sliding window van spelers, gebaseerd op CAMERA-index (niet op pager-pagina).
 *
 * - preload 0: alleen de actieve camera
 * - preload 1: actieve + volgende camera
 * - preload 2: actieve + volgende + vorige camera
 *
 * Duplicaten (bij 1 of 2 camera's) worden samengevoegd; nooit meer dan [MAX_PLAYERS] slots.
 */
object PlayerWindow {
    const val MAX_PLAYERS = 3
    const val MAX_PRELOAD = 2

    fun compute(cameraCount: Int, activeIndex: Int, preload: Int): List<WindowSlot> {
        if (cameraCount <= 0) return emptyList()
        val active = Math.floorMod(activeIndex, cameraCount)
        val p = preload.coerceIn(0, MAX_PRELOAD)
        val result = ArrayList<WindowSlot>(MAX_PLAYERS)
        result += WindowSlot(active, PlayerRole.ACTIVE)
        val candidates = buildList {
            if (p >= 1) add(Math.floorMod(active + 1, cameraCount))
            if (p >= 2) add(Math.floorMod(active - 1, cameraCount))
        }
        for (idx in candidates) {
            if (result.none { it.cameraIndex == idx }) result += WindowSlot(idx, PlayerRole.PRELOAD)
        }
        check(result.size <= MAX_PLAYERS)
        return result
    }

    /** Camera-index bij een virtuele pager-pagina (wrap-around). */
    fun cameraIndexForPage(page: Int, cameraCount: Int): Int =
        if (cameraCount <= 0) 0 else Math.floorMod(page, cameraCount)

    /** Grote virtuele page count voor wrap-around; 1 camera = geen swipen. */
    fun virtualPageCount(cameraCount: Int): Int = when {
        cameraCount <= 0 -> 0
        cameraCount == 1 -> 1
        else -> (VIRTUAL_PAGES / cameraCount) * cameraCount
    }

    /** Startpagina in het midden van het virtuele bereik die op [cameraIndex] uitkomt. */
    fun initialPage(cameraCount: Int, cameraIndex: Int): Int {
        if (cameraCount <= 1) return 0
        val total = virtualPageCount(cameraCount)
        val middle = total / 2
        return middle - Math.floorMod(middle, cameraCount) + Math.floorMod(cameraIndex, cameraCount)
    }

    /**
     * Op welke pager-pagina de speler van [cameraIndex] gekoppeld wordt als [currentPage] zichtbaar is.
     * Bij weinig camera's kan dezelfde camera op meerdere pagina's staan (bv. 2 camera's: links én
     * rechts dezelfde); de speler mag maar aan één view hangen, dus kiezen we één "canonieke" pagina:
     * de dichtstbijzijnde, bij gelijke afstand de volgende (rechts).
     */
    fun boundPageFor(cameraIndex: Int, currentPage: Int, cameraCount: Int): Int {
        if (cameraCount <= 1) return currentPage
        val current = cameraIndexForPage(currentPage, cameraCount)
        val forward = Math.floorMod(cameraIndex - current, cameraCount)
        if (forward == 0) return currentPage
        val backward = cameraCount - forward
        return if (forward <= backward) currentPage + forward else currentPage - backward
    }

    private const val VIRTUAL_PAGES = 1_000_000
}
