package net.serble.rotationbeats

import net.serble.rotationbeats.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelTest {
    private val cache = HashMap<Int, List<SegLayout>>()
    private val source = LevelSource { n -> cache.getOrPut(n) { LevelGen(n).generate() } }

    @Test
    fun generationIsDeterministicAndFast() {
        for (lvl in 1..12) {
            val t0 = System.nanoTime()
            val a = LevelGen(lvl).generate()
            val ms = (System.nanoTime() - t0) / 1e6
            val b = LevelGen(lvl).generate()
            assertEquals(8, a.size)
            for (i in a.indices) assertTrue(a[i].grid.c.contentEquals(b[i].grid.c))
            val corners = a.count { it.corner != Corner.PLAIN }
            println("level $lvl: ${"%.0f".format(ms)} ms, walls ${a.map { it.w }}, corners $corners")
        }
    }

    /** Plays whole levels inside the real [Run] with inputs found by search, bar by bar. */
    @Test
    fun botBeatsLevels() {
        for (start in listOf(1, 2, 3, 4, 6, 9, 13)) {
            val run = Run(start, source)
            var bars = 0
            while (bars < 16) {
                val plan = Bot.planBar(run) ?: error("no plan at level ${run.level} bar ${run.barInLevel}")
                val base = run.tick
                for (t in 0 until P.TICKS_PER_BAR) {
                    if (plan.contains(t)) run.press() else run.release()
                    val f = run.advance(base + t + 1)
                    assertFalse("died at level ${run.level} bar ${run.barInLevel} tick $t cause ${run.body.cause}", f and Ev.DIE != 0)
                }
                bars++
            }
            println("start $start: survived $bars bars, now level ${run.level}")
        }
    }

    @Test
    fun songTimelineRoundTrips() {
        val s = Song(3)
        for (bar in listOf(0, 1, 7, 8, 9, 40)) {
            val beat = s.beatAt(s.barStart(bar) + 0.001)
            assertTrue(beat >= bar * 4 && beat < bar * 4 + 0.01)
        }
    }
}
