package net.serble.rotationbeats

import net.serble.rotationbeats.core.*

object Bot {
    /** Search the current bar (and the landing of the next) for a set of ticks to press on. */
    fun planBar(run: Run): Set<Int>? {
        val i = run.cur
        val prev = run.seg(i - 1)!!.layout
        val cur = run.seg(i)!!.layout
        val next = run.seg(i + 1)!!.layout
        val g = SegLayout.merged(cur, prev, next)
        val ng = SegLayout.merged(next, cur, run.seg(i + 2)!!.layout)
        val start = Body().also { it.copyFrom(run.body) }
        val path = ArrayList<Int>()
        val seen = HashSet<Long>()
        fun ok(b: Body): Boolean {
            val e = Body().also { it.copyFrom(b) }
            Sim.rotate(e, cur.w)
            val r = Explorer(ng).run(listOf(Entry(e.u, e.v, 0)))
            return r.entryOk[0]
        }
        fun dfs(b: Body, t: Int): Boolean {
            if (b.dead) return false
            if (t == P.TICKS_PER_BAR) return ok(b)
            val key = t * 100000L + Math.round(b.v * 100) * 10 + (if (b.grounded) 1 else 0)
            if (b.grounded && !seen.add(key)) return false
            if (b.grounded || b.coyote > 0) {
                val j = Body().also { it.copyFrom(b) }
                Sim.step(j, g, true)
                path.add(t)
                if (dfs(j, t + 1)) return true
                path.removeAt(path.size - 1)
            }
            val r = Body().also { it.copyFrom(b) }
            Sim.step(r, g, false)
            return dfs(r, t + 1)
        }
        return if (dfs(start, 0)) path.toSet() else null
    }

}
