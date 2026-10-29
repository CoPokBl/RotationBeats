package net.serble.rotationbeats.core

import java.util.Random
import kotlin.math.min

/**
 * Builds a level: [BARS] segments, each one bar long. Every candidate segment is verified by
 * [Explorer] against the actual states the player can arrive in, so every level is beatable and
 * every corner ("where the floor will be after the next rotation") has a fair timing window.
 */
class LevelGen(private val level: Int) {

    companion object {
        const val BARS = 8
        private const val MAX_ENTRIES = 14

        fun bpm(level: Int): Double = min(100.0 + (level - 1) * 5.0, 145.0)

        /** States the player may be in when a level starts (fresh spawn or carried over). */
        fun startEntries(): List<Entry> {
            val list = ArrayList<Entry>()
            list.add(Entry(1.0, P.HALF, 0, grounded = true))
            var gi = 1
            for (u in doubleArrayOf(0.4, 1.5, 2.6)) for (h in doubleArrayOf(1.4, 3.2, 5.0, 6.8)) {
                list.add(Entry(u, h, gi++))
            }
            return list
        }
    }

    /** Minimum timing window (in ticks, 48 per beat) for a corner jump. Tighter as levels go on. */
    private val minWindow = when {
        level <= 1 -> 22
        level <= 3 -> 17
        level <= 6 -> 13
        else -> 10
    }

    private val rng = Random(level * 0x9E3779B1L + 77L)

    private fun types(): IntArray {
        val t = ArrayList<Int>()
        t += listOf(Ob.SPIKE1, Ob.SPIKE1, Ob.GAP2)
        if (level >= 2) t += listOf(Ob.SPIKE2, Ob.STEP1, Ob.GAP2)
        if (level >= 3) t += listOf(Ob.GAP3, Ob.CEIL, Ob.SPIKE2)
        if (level >= 4) t += listOf(Ob.SPIKE3, Ob.STEP2, Ob.STEP1)
        if (level >= 5) t += listOf(Ob.STEP_SPIKE, Ob.CEIL, Ob.GAP3)
        if (level >= 7) t += listOf(Ob.SPIKE3, Ob.STEP_SPIKE)
        return t.toIntArray()
    }

    private val obstacleTypes = types()

    fun generate(): List<SegLayout> {
        val out = ArrayList<SegLayout>()
        var prev: SegLayout? = null
        var prevExits: List<Exit>? = null
        for (bar in 0 until BARS) {
            var chosen: Pair<SegLayout, List<Exit>>? = null
            for (attempt in 0 until 40) {
                val simple = attempt >= 30
                chosen = tryBar(bar, prev, prevExits, simple) ?: continue
                break
            }
            if (chosen == null) chosen = tryBar(bar, prev, prevExits, simple = true, plain = true)!!
            out.add(chosen.first)
            prev = chosen.first
            prevExits = chosen.second
        }
        return out
    }

    private fun dummyPrev() = SegLayout(level, -1, P.WALL_BASE)

    private fun tryBar(
        bar: Int, prev: SegLayout?, prevExits: List<Exit>?, simple: Boolean, plain: Boolean = false,
    ): Pair<SegLayout, List<Exit>>? {
        val maxExtra = if (level <= 1) 1 else 3
        val w = P.WALL_BASE + rng.nextInt(maxExtra + 1)
        val seg = SegLayout(level, bar, w)
        val left = prev ?: dummyPrev()

        val cornerChance = when {
            bar == 0 -> 0.0
            level == 1 -> if (bar % 2 == 1 && bar >= 3) 1.0 else 0.0
            else -> min(0.35 + level * 0.06, 0.75)
        }
        val wantCorner = !plain && !simple && prev != null && rng.nextDouble() < cornerChance
        if (!plain) addObstacles(seg, bar, simple)

        // A stand-in wall ahead, so paths that would run into it before the bar count as dead.
        val ahead = SegLayout(level, bar + 1, P.WALL_BASE)

        val entries: List<Entry> = if (prev == null || prevExits == null) startEntries() else {
            val mapped = prevExits.map { Entry(it.v, prev.w - it.u, it.entry) }
            var alive: BooleanArray? = null
            if (wantCorner) alive = pickCorner(seg, left, ahead, mapped, prevExits) ?: return null
            if (alive == null) {
                val g = SegLayout.merged(seg, left, ahead)
                alive = BooleanArray(mapped.size) { survivesLanding(g, mapped[it]) }
            }
            sample(mapped.filterIndexed { i, _ -> alive[i] })
        }
        if (entries.isEmpty()) return null

        val res = Explorer(SegLayout.merged(seg, left, ahead)).run(entries)
        if (!res.entryOk.all { it }) return null
        if (res.exits.isEmpty()) return null
        return seg to res.exits
    }

    /**
     * Carves the corner feature into the start of [seg] (the wall the player sees ahead during
     * the previous bar), choosing the most demanding size that still leaves a fair window.
     */
    private fun pickCorner(
        seg: SegLayout, left: SegLayout, ahead: SegLayout, mapped: List<Entry>, prevExits: List<Exit>,
    ): BooleanArray? {
        val g = seg.grid
        val type = when (rng.nextInt(if (level >= 4) 5 else 3)) {
            0, 1 -> Corner.PIT
            2 -> Corner.SPIKES
            3 -> Corner.PIT
            else -> Corner.FAR_PIT
        }
        val sizes = if (type == Corner.FAR_PIT) intArrayOf(4, 3) else intArrayOf(6, 5, 4, 3)
        for (p in sizes) {
            when (type) {
                Corner.PIT -> g.fill(0, p, -P.FLOOR_T, 0, Cell.EMPTY)
                Corner.SPIKES -> for (a in 0 until p) g[a, 0] = Cell.spike(1)
                else -> g.fill(p, p + 2, -P.FLOOR_T, 0, Cell.EMPTY)
            }
            val grid = SegLayout.merged(seg, left, ahead)
            val alive = BooleanArray(mapped.size) { survivesLanding(grid, mapped[it]) }
            val matters = alive.any { !it }
            if (matters && windowOk(prevExits, alive)) {
                seg.corner = type
                return alive
            }
            // Undo and try a smaller one.
            when (type) {
                Corner.PIT -> g.fill(0, p, -P.FLOOR_T, 0, Cell.SOLID)
                Corner.SPIKES -> for (a in 0 until p) g[a, 0] = Cell.EMPTY
                else -> g.fill(p, p + 2, -P.FLOOR_T, 0, Cell.SOLID)
            }
        }
        return null
    }

    /** Fall from the rotation without input and make sure the landing itself is survivable. */
    private fun survivesLanding(g: Grid, e: Entry): Boolean {
        val b = Body().apply { u = e.u; v = e.v }
        var after = -1
        for (t in 0 until P.TICKS_PER_BAR) {
            Sim.step(b, g, false)
            if (b.dead) return false
            if (b.grounded && after < 0) after = 0
            if (after >= 0 && ++after > 4) return true
        }
        return !b.dead
    }

    /**
     * For each way the player could have entered the previous bar, the corner must either be
     * survivable by simply running, or by a jump whose timing window is at least [minWindow] ticks.
     */
    private fun windowOk(exits: List<Exit>, alive: BooleanArray): Boolean {
        val groups = exits.map { it.entry }.toSet()
        for (gid in groups) {
            val taus = HashSet<Int>()
            var groundedOk = false
            for ((i, e) in exits.withIndex()) {
                if (e.entry != gid || !alive[i]) continue
                if (e.tau == 0) groundedOk = true else taus.add(e.tau)
            }
            if (!groundedOk && taus.size < minWindow) return false
        }
        // The feature should actually matter for someone, otherwise it is just decoration.
        return true
    }

    private fun sample(list: List<Entry>): List<Entry> {
        if (list.size <= MAX_ENTRIES) return list
        val sorted = list.sortedWith(compareBy({ it.u }, { it.v }))
        val out = ArrayList<Entry>()
        for (i in 0 until MAX_ENTRIES) out.add(sorted[i * (sorted.size - 1) / (MAX_ENTRIES - 1)])
        return out
    }

    private fun addObstacles(seg: SegLayout, bar: Int, simple: Boolean) {
        val g = seg.grid
        val count = when {
            bar == 0 -> 1
            simple -> 1
            level == 1 -> 2
            else -> 3
        }
        val spacing = when {
            level <= 2 -> 4
            level <= 5 -> 3
            else -> 2
        }
        var u = if (bar == 0) 8 + rng.nextInt(2) else 6 + rng.nextInt(2)
        for (i in 0 until count) {
            val type = obstacleTypes[rng.nextInt(obstacleTypes.size)]
            val width = Ob.width(type, rng)
            val limit = if (Ob.raises(type)) 13 else 16
            if (u + width > limit) break
            Ob.build(g, type, u, width)
            u += width + spacing + rng.nextInt(2)
        }
    }
}

object Ob {
    const val SPIKE1 = 0
    const val SPIKE2 = 1
    const val SPIKE3 = 2
    const val GAP2 = 3
    const val GAP3 = 4
    const val STEP1 = 5
    const val STEP2 = 6
    const val CEIL = 7
    const val STEP_SPIKE = 8

    fun raises(t: Int) = t == STEP1 || t == STEP2 || t == STEP_SPIKE

    fun width(t: Int, r: Random) = when (t) {
        SPIKE1 -> 1
        SPIKE2 -> 2
        SPIKE3 -> 3
        GAP2 -> 2
        GAP3 -> 3
        STEP1 -> 3 + r.nextInt(3)
        STEP2 -> 3 + r.nextInt(2)
        CEIL -> 3 + r.nextInt(2)
        else -> 4 + r.nextInt(2)
    }

    fun build(g: Grid, t: Int, u: Int, w: Int) {
        when (t) {
            SPIKE1, SPIKE2, SPIKE3 -> for (a in u until u + w) g[a, 0] = Cell.spike(1)
            GAP2, GAP3 -> g.fill(u, u + w, -P.FLOOR_T, 0, Cell.EMPTY)
            STEP1 -> g.fill(u, u + w, 0, 1, Cell.SOLID)
            STEP2 -> g.fill(u, u + w, 0, 2, Cell.SOLID)
            CEIL -> {
                g.fill(u, u + w, 3, 4, Cell.SOLID)
                for (a in u until u + w) g[a, 2] = Cell.spike(3)
            }
            STEP_SPIKE -> {
                g.fill(u, u + w, 0, 1, Cell.SOLID)
                g[u + w - 1, 1] = Cell.spike(1)
            }
        }
    }
}
