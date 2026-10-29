package net.serble.rotationbeats

import android.graphics.Path
import net.serble.rotationbeats.core.Cell
import net.serble.rotationbeats.core.Dir
import net.serble.rotationbeats.core.Frame
import net.serble.rotationbeats.core.Grid
import net.serble.rotationbeats.core.Placed
import net.serble.rotationbeats.core.SegLayout

/** World-space geometry of one segment, prepared once so drawing is just a few batched calls. */
class SegGfx(placed: Placed, prev: SegLayout?, next: SegLayout?) {
    val level = placed.layout.level
    val complete = prev != null && next != null
    val fills: FloatArray
    val edges: FloatArray
    val spikes = Path()

    init {
        val lay = placed.layout
        val g = lay.grid
        val f = placed.frame
        val merged = SegLayout.merged(lay, prev, next)

        val rects = ArrayList<Float>()
        for (b in g.v0 until g.v0 + g.h) {
            var a = g.u0
            while (a < g.u0 + g.w) {
                if (!g.solid(a, b)) { a++; continue }
                val start = a
                while (a < g.u0 + g.w && g.solid(a, b)) a++
                addRect(rects, f, start.toDouble(), b.toDouble(), a.toDouble(), b + 1.0)
            }
        }
        fills = rects.toFloatArray()

        val lines = ArrayList<Float>()
        // Horizontal edges (tops, n = 1, and bottoms, n = 3), merged along u.
        for (n in intArrayOf(1, 3)) for (b in g.v0 until g.v0 + g.h) {
            var a = g.u0
            while (a < g.u0 + g.w) {
                if (!exposed(g, merged, a, b, n)) { a++; continue }
                val start = a
                while (a < g.u0 + g.w && exposed(g, merged, a, b, n)) a++
                val y = if (n == 1) b + 1.0 else b.toDouble()
                addLine(lines, f, start.toDouble(), y, a.toDouble(), y)
            }
        }
        // Vertical edges (n = 0 and n = 2), merged along v.
        for (n in intArrayOf(0, 2)) for (a in g.u0 until g.u0 + g.w) {
            var b = g.v0
            while (b < g.v0 + g.h) {
                if (!exposed(g, merged, a, b, n)) { b++; continue }
                val start = b
                while (b < g.v0 + g.h && exposed(g, merged, a, b, n)) b++
                val x = if (n == 0) a + 1.0 else a.toDouble()
                addLine(lines, f, x, start.toDouble(), x, b.toDouble())
            }
        }
        edges = lines.toFloatArray()

        for (b in g.v0 until g.v0 + g.h) for (a in g.u0 until g.u0 + g.w) {
            val c = g[a, b]
            if (!Cell.isSpike(c)) continue
            val d = Cell.spikeDir(c)
            val du = Dir.DU[d].toDouble()
            val dv = Dir.DV[d].toDouble()
            val cu = a + 0.5
            val cv = b + 0.5
            val bu = cu - du * 0.5
            val bv = cv - dv * 0.5
            val pu = -dv * 0.42
            val pv = du * 0.42
            spikes.moveTo(f.wx(bu + pu, bv + pv).toFloat(), f.wy(bu + pu, bv + pv).toFloat())
            spikes.lineTo(f.wx(cu + du * 0.38, cv + dv * 0.38).toFloat(), f.wy(cu + du * 0.38, cv + dv * 0.38).toFloat())
            spikes.lineTo(f.wx(bu - pu, bv - pv).toFloat(), f.wy(bu - pu, bv - pv).toFloat())
            spikes.close()
        }
    }

    private fun exposed(g: Grid, merged: Grid, a: Int, b: Int, n: Int): Boolean =
        g.solid(a, b) && !merged.solid(a + Dir.DU[n], b + Dir.DV[n])

    private fun addRect(out: ArrayList<Float>, f: Frame, u0: Double, v0: Double, u1: Double, v1: Double) {
        val x0 = f.wx(u0, v0); val y0 = f.wy(u0, v0)
        val x1 = f.wx(u1, v1); val y1 = f.wy(u1, v1)
        out.add(minOf(x0, x1).toFloat()); out.add(minOf(y0, y1).toFloat())
        out.add(maxOf(x0, x1).toFloat()); out.add(maxOf(y0, y1).toFloat())
    }

    private fun addLine(out: ArrayList<Float>, f: Frame, u0: Double, v0: Double, u1: Double, v1: Double) {
        out.add(f.wx(u0, v0).toFloat()); out.add(f.wy(u0, v0).toFloat())
        out.add(f.wx(u1, v1).toFloat()); out.add(f.wy(u1, v1).toFloat())
    }
}
