package net.serble.rotationbeats.core

/**
 * One bar of level, in its own local frame: the floor's top surface is v = 0, the wall behind
 * (the previous floor) is u = 0, and the wall ahead (the next floor) is at u = [w].
 */
class SegLayout(val level: Int, val bar: Int, val w: Int) {
    val grid = Grid(-P.FLOOR_T, -P.FLOOR_T, w + 2 * P.FLOOR_T, 10)
    var corner = Corner.PLAIN

    init {
        grid.fill(-P.FLOOR_T, w + P.FLOOR_T, -P.FLOOR_T, 0, Cell.SOLID)
    }

    companion object {
        /**
         * Builds a collision grid in [cur]'s frame that also contains the previous floor (now the
         * wall behind) and the next floor (the wall ahead). Used by both the game and the level
         * verifier so they always agree.
         */
        fun merged(cur: SegLayout, prev: SegLayout?, next: SegLayout?): Grid {
            val g = Grid(-6, -6, cur.w + 12, 24)
            if (prev != null) {
                val pg = prev.grid
                for (j in 0 until pg.h) for (i in 0 until pg.w) {
                    val c = pg.c[j * pg.w + i]
                    if (c == Cell.EMPTY) continue
                    val a = i + pg.u0
                    val b = j + pg.v0
                    put(g, b, prev.w - a - 1, c, 3)
                }
            }
            if (next != null) {
                val ng = next.grid
                for (j in 0 until ng.h) for (i in 0 until ng.w) {
                    val c = ng.c[j * ng.w + i]
                    if (c == Cell.EMPTY) continue
                    val a = i + ng.u0
                    val b = j + ng.v0
                    put(g, cur.w - b - 1, a, c, 1)
                }
            }
            val cg = cur.grid
            for (j in 0 until cg.h) for (i in 0 until cg.w) {
                val c = cg.c[j * cg.w + i]
                if (c != Cell.EMPTY) put(g, i + cg.u0, j + cg.v0, c, 0)
            }
            return g
        }

        private fun put(g: Grid, a: Int, b: Int, c: Byte, dirShift: Int) {
            val existing = g[a, b]
            if (existing == Cell.SOLID) return
            if (c == Cell.SOLID) g[a, b] = Cell.SOLID
            else g[a, b] = Cell.spike(Cell.spikeDir(c) + dirShift)
        }
    }
}

object Corner {
    const val PLAIN = 0
    const val PIT = 1
    const val SPIKES = 2
    const val FAR_PIT = 3
}

/** Absolute placement of a segment in the world (integer origin, direction 0..3). */
class Frame(val ox: Int, val oy: Int, val d: Int) {
    val fx = Dir.DU[d and 3]
    val fy = Dir.DV[d and 3]
    val ux = Dir.DU[(d + 1) and 3]
    val uy = Dir.DV[(d + 1) and 3]

    fun wx(u: Double, v: Double) = ox + u * fx + v * ux
    fun wy(u: Double, v: Double) = oy + u * fy + v * uy

    /** Frame of the segment that follows when this one has its wall at [w]. */
    fun next(w: Int) = Frame(ox + w * fx, oy + w * fy, (d + 1) and 3)

    fun prev(prevW: Int): Frame {
        val pd = (d + 3) and 3
        return Frame(ox - prevW * Dir.DU[pd], oy - prevW * Dir.DV[pd], pd)
    }
}
