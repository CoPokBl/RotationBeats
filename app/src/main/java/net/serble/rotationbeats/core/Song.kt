package net.serble.rotationbeats.core

/**
 * Timeline of a run that starts at [startLevel]. Bar n belongs to level startLevel + n / 8 and
 * plays at that level's tempo. Shared by the synth (to schedule notes) and the game (to turn
 * audio time back into beats), so the two can never disagree.
 */
class Song(val startLevel: Int) {
    private val bars = 4096
    private val starts = DoubleArray(bars + 1)

    init {
        for (n in 0 until bars) starts[n + 1] = starts[n] + barSeconds(n)
    }

    fun levelOfBar(n: Int) = startLevel + maxOf(n, 0) / LevelGen.BARS
    fun bpm(n: Int) = LevelGen.bpm(levelOfBar(n))
    fun barSeconds(n: Int) = 60.0 / bpm(n) * P.BEATS_PER_BAR
    fun barStart(n: Int): Double =
        if (n <= bars) starts[maxOf(n, 0)] else starts[bars] + (n - bars) * barSeconds(n)

    fun beatAt(sec: Double): Double {
        if (sec <= 0) return sec * bpm(0) / 60.0
        var lo = 0
        var hi = bars
        if (sec >= starts[bars]) {
            val n = bars + ((sec - starts[bars]) / barSeconds(bars)).toInt()
            return n * 4.0 + (sec - barStart(n)) / barSeconds(n) * 4.0
        }
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= sec) lo = mid else hi = mid
        }
        return lo * 4.0 + (sec - starts[lo]) / barSeconds(lo) * 4.0
    }
}
