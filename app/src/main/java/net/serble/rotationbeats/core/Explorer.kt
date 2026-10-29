package net.serble.rotationbeats.core

import kotlin.math.roundToInt

/** A player state as it enters a segment (already in that segment's frame). */
class Entry(val u: Double, val v: Double, val group: Int, val grounded: Boolean = false)

/** A player state at the moment the bar ends (in the segment's own frame). */
class Exit(
    val u: Double,
    val v: Double,
    val grounded: Boolean,
    /** Ticks between the last jump and the bar line, 0 if standing on the floor. */
    val tau: Int,
    val entry: Int,
)

/**
 * Exhaustive search over the only choice the player has: when to jump. Because forward speed
 * is constant, a grounded state is fully described by (tick, floor row), which keeps this small.
 */
class Explorer(private val g: Grid, private val ticks: Int = P.TICKS_PER_BAR) {

    private val rows = 24
    private val rowBase = 6

    class Result(val entryOk: BooleanArray, val exits: List<Exit>)

    fun run(entries: List<Entry>): Result {
        val ok = BooleanArray(entries.size)
        val exits = ArrayList<Exit>()
        val seen = HashSet<Long>()
        for ((i, e) in entries.withIndex()) {
            val before = exits.size
            explore(e, i, exits, seen)
            ok[i] = exits.size > before
        }
        return Result(ok, exits)
    }

    private class Node(val body: Body, val tick: Int, val lastJump: Int)

    private fun explore(e: Entry, idx: Int, out: MutableList<Exit>, seen: HashSet<Long>) {
        val visited = BooleanArray((ticks + 1) * rows)
        val stack = ArrayDeque<Node>()
        val start = Body().apply { u = e.u; v = e.v; grounded = e.grounded; if (e.grounded) coyote = P.COYOTE }
        if (e.grounded) stack.add(Node(start, 0, -1))
        else fly(start, 0, -1, idx, stack, visited, out, seen)

        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n.tick >= ticks) {
                emit(n.body, n.lastJump, idx, out, seen)
                continue
            }
            // Option 1: jump now.
            val j = Body().also { it.copyFrom(n.body) }
            val ev = Sim.step(j, g, true)
            if (ev and Ev.DIE == 0) fly(j, n.tick + 1, n.tick, idx, stack, visited, out, seen)
            // Option 2: keep running.
            val r = Body().also { it.copyFrom(n.body) }
            Sim.step(r, g, false)
            if (!r.dead) {
                if (r.grounded) push(r, n.tick + 1, n.lastJump, stack, visited)
                else fly(r, n.tick + 1, n.lastJump, idx, stack, visited, out, seen)
            }
        }
    }

    private fun push(b: Body, tick: Int, lastJump: Int, stack: ArrayDeque<Node>, visited: BooleanArray) {
        val row = (b.v - P.HALF).roundToInt() + rowBase
        if (row < 0 || row >= rows) return
        val key = tick * rows + row
        if (visited[key]) return
        visited[key] = true
        stack.add(Node(b, tick, lastJump))
    }

    /** Simulate without input until the body lands, dies, or the bar ends. */
    private fun fly(
        b: Body, startTick: Int, lastJump: Int, idx: Int,
        stack: ArrayDeque<Node>, visited: BooleanArray, out: MutableList<Exit>, seen: HashSet<Long>,
    ) {
        var t = startTick
        while (true) {
            if (b.dead) return
            if (b.grounded) {
                push(b, t, lastJump, stack, visited)
                return
            }
            if (t >= ticks) {
                emit(b, lastJump, idx, out, seen)
                return
            }
            Sim.step(b, g, false)
            t++
        }
    }

    private fun emit(b: Body, lastJump: Int, idx: Int, out: MutableList<Exit>, seen: HashSet<Long>) {
        val tau = if (b.grounded || lastJump < 0) 0 else ticks - lastJump
        val key = (b.u * 64).roundToInt().toLong() * 1_000_003L +
            (b.v * 64).roundToInt().toLong() * 7919L + tau * 31L + idx
        if (!seen.add(key)) return
        out.add(Exit(b.u, b.v, b.grounded, tau, idx))
    }
}
