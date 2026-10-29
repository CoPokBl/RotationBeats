package net.serble.rotationbeats.core

import kotlin.math.floor
import kotlin.math.max

/**
 * All gameplay runs in *beat* time so a level plays identically at any tempo.
 * One bar = 4 beats = [P.TICKS_PER_BAR] fixed simulation ticks, and the world rotates once per bar.
 */
object P {
    const val TPB = 48
    const val BEATS_PER_BAR = 4
    const val TICKS_PER_BAR = TPB * BEATS_PER_BAR
    const val DT = 1.0 / TPB

    const val SPEED = 5.0           // cells per beat
    const val GRAVITY = 17.6        // cells per beat^2
    const val JUMP_V = 8.8          // -> 2.2 cells high, exactly one beat of air time
    const val MAX_FALL = 26.0
    const val HALF = 0.4            // player half size for solid collision
    const val HAZ = 0.3             // player half size for hazards (forgiving)
    const val STEP_UP = 0.3         // snap onto ledges we only just clip
    const val COYOTE = 5
    const val VOID_V = -2.6

    const val BAR_CELLS = 20        // SPEED * BEATS_PER_BAR
    const val WALL_BASE = 24        // minimum distance to the wall that becomes the next floor
    const val FLOOR_T = 2           // floor thickness
}

object Cell {
    const val EMPTY: Byte = 0
    const val SOLID: Byte = 1
    fun spike(dir: Int): Byte = (2 + (dir and 3)).toByte()
    fun isSpike(c: Byte) = c >= 2
    fun spikeDir(c: Byte) = c - 2
}

/** Local direction index: 0 = +u (forward), 1 = +v (up), 2 = -u, 3 = -v. */
object Dir {
    val DU = intArrayOf(1, 0, -1, 0)
    val DV = intArrayOf(0, 1, 0, -1)
}

class Grid(val u0: Int, val v0: Int, val w: Int, val h: Int) {
    val c = ByteArray(w * h)

    operator fun get(a: Int, b: Int): Byte {
        val i = a - u0
        val j = b - v0
        if (i < 0 || j < 0 || i >= w || j >= h) return Cell.EMPTY
        return c[j * w + i]
    }

    operator fun set(a: Int, b: Int, v: Byte) {
        val i = a - u0
        val j = b - v0
        if (i < 0 || j < 0 || i >= w || j >= h) return
        c[j * w + i] = v
    }

    /** Fill [a0, a1) x [b0, b1). */
    fun fill(a0: Int, a1: Int, b0: Int, b1: Int, v: Byte) {
        for (b in b0 until b1) for (a in a0 until a1) this[a, b] = v
    }

    fun solid(a: Int, b: Int) = this[a, b] == Cell.SOLID
}

class Body {
    var u = 0.0
    var v = 0.0
    var vy = 0.0
    var grounded = false
    var coyote = 0
    var dead = false
    var cause = 0

    fun copyFrom(o: Body) {
        u = o.u; v = o.v; vy = o.vy; grounded = o.grounded; coyote = o.coyote; dead = o.dead; cause = o.cause
    }
}

object Ev {
    const val JUMP = 1
    const val LAND = 2
    const val DIE = 4
}

object Cause {
    const val CRASH = 1
    const val SPIKE = 2
    const val FALL = 3
}

object Sim {
    private const val E = 1e-7

    private fun lo(x: Double) = floor(x + E).toInt()
    private fun hi(x: Double) = floor(x - E).toInt()

    private fun boxSolid(g: Grid, u0: Double, u1: Double, v0: Double, v1: Double): Boolean {
        for (b in lo(v0)..hi(v1)) for (a in lo(u0)..hi(u1)) if (g.solid(a, b)) return true
        return false
    }

    private fun rowSolid(g: Grid, row: Int, u: Double): Boolean {
        for (a in lo(u - P.HALF)..hi(u + P.HALF)) if (g.solid(a, row)) return true
        return false
    }

    /** One fixed tick in the current segment's local frame. Returns [Ev] flags. */
    fun step(b: Body, g: Grid, jump: Boolean): Int {
        if (b.dead) return 0
        var ev = 0
        if (jump && (b.grounded || b.coyote > 0)) {
            b.vy = P.JUMP_V
            b.grounded = false
            b.coyote = 0
            ev = ev or Ev.JUMP
        }

        // Vertical
        val wasGrounded = b.grounded
        b.vy = max(b.vy - P.GRAVITY * P.DT, -P.MAX_FALL)
        val nv = b.v + b.vy * P.DT
        b.grounded = false
        if (b.vy <= 0) {
            val row = floor(nv - P.HALF).toInt()
            if (rowSolid(g, row, b.u)) {
                b.v = row + 1 + P.HALF
                b.vy = 0.0
                b.grounded = true
            } else b.v = nv
        } else {
            val row = floor(nv + P.HALF - E).toInt()
            if (rowSolid(g, row, b.u)) {
                b.v = row - P.HALF
                b.vy = 0.0
            } else b.v = nv
        }

        // Forward
        val nu = b.u + P.SPEED * P.DT
        if (boxSolid(g, nu - P.HALF, nu + P.HALF, b.v - P.HALF, b.v + P.HALF)) {
            var top = Int.MIN_VALUE
            for (row in lo(b.v - P.HALF)..hi(b.v + P.HALF)) {
                for (a in lo(nu - P.HALF)..hi(nu + P.HALF)) if (g.solid(a, row)) top = max(top, row)
            }
            val lift = top + 1 + P.HALF - b.v
            if (lift >= 0 && lift <= P.STEP_UP &&
                !boxSolid(g, nu - P.HALF, nu + P.HALF, top + 1.0, top + 1.0 + 2 * P.HALF)
            ) {
                b.v = top + 1 + P.HALF
                b.u = nu
                if (b.vy < 0) b.vy = 0.0
                b.grounded = true
            } else {
                b.dead = true
                b.cause = Cause.CRASH
                return ev or Ev.DIE
            }
        } else b.u = nu

        if (b.grounded) {
            b.coyote = P.COYOTE
            if (!wasGrounded) ev = ev or Ev.LAND
        } else if (b.coyote > 0) b.coyote--

        // Spikes
        for (row in floor(b.v - P.HAZ).toInt()..floor(b.v + P.HAZ).toInt()) {
            for (a in floor(b.u - P.HAZ).toInt()..floor(b.u + P.HAZ).toInt()) {
                val c = g[a, row]
                if (Cell.isSpike(c) && spikeHit(a, row, Cell.spikeDir(c), b.u, b.v)) {
                    b.dead = true
                    b.cause = Cause.SPIKE
                    return ev or Ev.DIE
                }
            }
        }

        if (b.v < P.VOID_V) {
            b.dead = true
            b.cause = Cause.FALL
            return ev or Ev.DIE
        }
        return ev
    }

    /** Spike hitbox: the lower, central part of the triangle. */
    private fun spikeHit(a: Int, b: Int, dir: Int, pu: Double, pv: Double): Boolean {
        val u0: Double; val u1: Double; val v0: Double; val v1: Double
        when (dir) {
            0 -> { u0 = a + 0.0; u1 = a + 0.55; v0 = b + 0.25; v1 = b + 0.75 }
            1 -> { u0 = a + 0.25; u1 = a + 0.75; v0 = b + 0.0; v1 = b + 0.55 }
            2 -> { u0 = a + 0.45; u1 = a + 1.0; v0 = b + 0.25; v1 = b + 0.75 }
            else -> { u0 = a + 0.25; u1 = a + 0.75; v0 = b + 0.45; v1 = b + 1.0 }
        }
        return pu + P.HAZ > u0 && pu - P.HAZ < u1 && pv + P.HAZ > v0 && pv - P.HAZ < v1
    }

    /**
     * The world turns 90 degrees clockwise: the wall ahead (at u = [w]) becomes the floor,
     * the old floor becomes the wall behind. Position is preserved, only the frame changes.
     */
    fun rotate(b: Body, w: Int) {
        val nu = b.v
        val nv = w - b.u
        b.u = nu
        b.v = nv
        b.vy = 0.0
        b.grounded = false
        b.coyote = 0
    }
}
