package net.serble.rotationbeats

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.robolectric.RuntimeEnvironment
import net.serble.rotationbeats.core.Ev
import net.serble.rotationbeats.core.P
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h360dp-land-xhdpi")
class ScreenshotTest {
    private val out = File(System.getProperty("shots.dir") ?: "build/shots").also { it.mkdirs() }

    private fun shot(v: View, name: String) {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        FileOutputStream(File(out, "$name.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Renders real frames to build/shots. Opt-in (needs Robolectric's android-all jars): SCREENSHOTS=1. */
    @Test
    fun frames() {
        org.junit.Assume.assumeTrue(System.getenv("SCREENSHOTS") != null)
        val ctx = RuntimeEnvironment.getApplication()
        val v = GameView(ctx) {}
        v.measure(View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1600, 720)
        // Menu (needs the level generated)
        Thread.sleep(800)
        repeat(30) { v.tickForTest(1 / 60.0) }
        shot(v, "00_menu")

        var sec = -0.2
        v.clockOverride = { sec }
        v.startForTest(1)
        val run = v.runForTest!!
        val bpm = net.serble.rotationbeats.core.LevelGen.bpm(1)
        var beat = -0.4
        var idx = 1
        var plan: Set<Int>? = null
        var planBar = -1
        var shots = 0
        while (beat < 40 && shots < 14) {
            beat += 1.0 / 120
            sec = beat * 60 / bpm
            val bar = run.tick / P.TICKS_PER_BAR
            if (bar != planBar) { plan = Bot.planBar(run); planBar = bar }
            v.pressForTest(plan?.contains(run.tick % P.TICKS_PER_BAR) == true)
            v.tickForTest(1.0 / 120)
            val ahead = run.seg(run.cur + 1)!!.layout.corner
            val inBar = beat - bar * 4.0
            val interesting = (ahead != net.serble.rotationbeats.core.Corner.PLAIN && inBar > 3.0) ||
                (run.current.layout.corner != net.serble.rotationbeats.core.Corner.PLAIN && inBar < 1.2)
            if (beat < 0.5 && idx == 1 || interesting && (Math.round(beat * 120) % 18 == 0L)) {
                shot(v, "%02d_beat_%.2f".format(idx++, beat)); shots++
            }
            if (run.body.dead) { shot(v, "%02d_dead".format(idx++)); break }
        }
    }

    private fun shouldJump(r: net.serble.rotationbeats.core.Run): Boolean {
        // Look ahead with a copy of the body: if running on gets us killed within half a beat, jump.
        val g = net.serble.rotationbeats.core.SegLayout.merged(r.current.layout, r.seg(r.cur - 1)!!.layout, r.seg(r.cur + 1)!!.layout)
        val b = net.serble.rotationbeats.core.Body().also { it.copyFrom(r.body) }
        val tickInBar = r.tick % P.TICKS_PER_BAR
        for (t in 0 until 24) {
            if (tickInBar + t >= P.TICKS_PER_BAR - 1) return tickInBar + t >= P.TICKS_PER_BAR - 24 && t < 2
            net.serble.rotationbeats.core.Sim.step(b, g, false)
            if (b.dead) return true
        }
        return false
    }
}
