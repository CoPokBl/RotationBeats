package net.serble.rotationbeats.core

/** A segment placed in the world. Index -1 is the decorative wall behind the spawn. */
class Placed(val index: Int, val layout: SegLayout, val frame: Frame)

fun interface LevelSource {
    fun level(n: Int): List<SegLayout>
}

/** One attempt, starting at [startLevel]. Advances in fixed ticks and reports [Ev] flags. */
class Run(val startLevel: Int, private val source: LevelSource) {
    val segs = ArrayList<Placed>()
    val behind: Placed
    val song = Song(startLevel)

    var cur = 0
        private set
    var tick = 0
        private set
    val body = Body()
    private var grid: Grid

    // World position at the previous and current tick, for render interpolation.
    var px0 = 0.0; var py0 = 0.0
    var px1 = 0.0; var py1 = 0.0

    var lastJumpTick = -1000
        private set
    var lastLandTick = -1000
        private set
    var lastRotateTick = 0
        private set

    private var held = false
    private var buffer = 0

    init {
        val first = source.level(startLevel)
        val f0 = Frame(0, 0, 0)
        behind = Placed(-1, SegLayout(startLevel, -1, P.WALL_BASE), f0.prev(P.WALL_BASE))
        segs.add(Placed(0, first[0], f0))
        ensure(2)
        body.u = 1.0
        body.v = P.HALF
        body.grounded = true
        body.coyote = P.COYOTE
        grid = buildGrid(0)
        updateWorld()
        px0 = px1; py0 = py1
    }

    fun seg(i: Int): Placed? = if (i == -1) behind else segs.getOrNull(i)

    val current: Placed get() = segs[cur]
    val level: Int get() = current.layout.level
    val barInLevel: Int get() = current.layout.bar

    private fun ensure(upTo: Int) {
        while (segs.size <= upTo) {
            val last = segs.last()
            val lvl = if (last.layout.bar == LevelGen.BARS - 1) last.layout.level + 1 else last.layout.level
            val layouts = source.level(lvl)
            val idx = if (lvl == last.layout.level) last.layout.bar + 1 else 0
            segs.add(Placed(segs.size, layouts[idx], last.frame.next(last.layout.w)))
        }
    }

    private fun buildGrid(i: Int): Grid {
        val prev = if (i == 0) behind.layout else segs[i - 1].layout
        return SegLayout.merged(segs[i].layout, prev, segs[i + 1].layout)
    }

    fun press() {
        held = true
        buffer = 10
    }

    fun release() {
        held = false
    }

    /** Advances to [target] ticks; returns accumulated event flags (plus [ROTATED]). */
    fun advance(target: Int): Int {
        var flags = 0
        while (tick < target && !body.dead) {
            px0 = px1; py0 = py1
            val ev = Sim.step(body, grid, held || buffer > 0)
            if (ev and Ev.JUMP != 0) {
                buffer = 0
                lastJumpTick = tick
            }
            if (ev and Ev.LAND != 0) lastLandTick = tick
            if (buffer > 0) buffer--
            flags = flags or ev
            tick++
            if (!body.dead && tick % P.TICKS_PER_BAR == 0) {
                val w = current.layout.w
                cur++
                ensure(cur + 3)
                Sim.rotate(body, w)
                grid = buildGrid(cur)
                lastRotateTick = tick
                flags = flags or ROTATED
                if (current.layout.bar == 0) flags = flags or LEVEL_UP
            }
            updateWorld()
        }
        return flags
    }

    private fun updateWorld() {
        val f = current.frame
        px1 = f.wx(body.u, body.v)
        py1 = f.wy(body.u, body.v)
    }

    companion object {
        const val ROTATED = 1 shl 8
        const val LEVEL_UP = 1 shl 9
    }
}
