package io.github.rutgersmit.frigastreamer.domain

data class WindowSlot(val cameraIndex: Int, val role: PlayerRole)

/**
 * Sliding window of players, based on CAMERA index (not on pager page).
 *
 * - preload 0: only the active camera
 * - preload 1: active + next camera
 * - preload 2: active + next + previous camera
 *
 * Duplicates (with 1 or 2 cameras) are merged; never more than [MAX_PLAYERS] slots.
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

    /** Camera index for a virtual pager page (wrap-around). */
    fun cameraIndexForPage(page: Int, cameraCount: Int): Int =
        if (cameraCount <= 0) 0 else Math.floorMod(page, cameraCount)

    /** Large virtual page count for wrap-around; 1 camera = no swiping. */
    fun virtualPageCount(cameraCount: Int): Int = when {
        cameraCount <= 0 -> 0
        cameraCount == 1 -> 1
        else -> (VIRTUAL_PAGES / cameraCount) * cameraCount
    }

    /** Start page in the middle of the virtual range that lands on [cameraIndex]. */
    fun initialPage(cameraCount: Int, cameraIndex: Int): Int {
        if (cameraCount <= 1) return 0
        val total = virtualPageCount(cameraCount)
        val middle = total / 2
        return middle - Math.floorMod(middle, cameraCount) + Math.floorMod(cameraIndex, cameraCount)
    }

    /**
     * Which pager page the player of [cameraIndex] is bound to while [currentPage] is visible.
     * With few cameras the same camera can appear on several pages (e.g. 2 cameras: the same one
     * on the left and on the right); a player can only be attached to one view, so we pick one
     * "canonical" page: the nearest one, and on a tie the next one (right).
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
