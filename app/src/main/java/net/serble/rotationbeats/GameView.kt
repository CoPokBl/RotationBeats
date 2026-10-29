package net.serble.rotationbeats

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import net.serble.rotationbeats.core.Corner
import net.serble.rotationbeats.core.Ev
import net.serble.rotationbeats.core.LevelGen
import net.serble.rotationbeats.core.LevelSource
import net.serble.rotationbeats.core.P
import net.serble.rotationbeats.core.Placed
import net.serble.rotationbeats.core.Run
import net.serble.rotationbeats.core.SegLayout
import net.serble.rotationbeats.core.Song
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

@SuppressLint("ViewConstructor")
class GameView(context: Context, private val onExit: () -> Unit) : View(context), Choreographer.FrameCallback {

    private enum class State { MENU, PLAY, DYING, PAUSED }

    private val prefs = context.getSharedPreferences("rotation", Context.MODE_PRIVATE)
    private val audio = AudioEngine()
    private val levels = LevelStore()
    private val dp = resources.displayMetrics.density

    private var state = State.MENU
    private var unlocked = prefs.getInt("unlocked", 1)
    private var selected = prefs.getInt("selected", unlocked).coerceIn(1, unlocked)
    private var sound = prefs.getBoolean("sound", true)
    private val attempts = HashMap<Int, Int>()

    private var run: Run? = null
    private var demo: Run? = null
    private val gfx = HashMap<Placed, SegGfx>()

    private var songSec: Double? = null
    private var beat = 0.0
    private var lastFrameNanos = 0L
    private var time = 0.0           // real seconds since launch, for UI animation
    private var stateTime = 0.0      // real seconds since the last state change
    private var bannerBeat = -100.0
    private var bannerText = ""
    private var bannerSub = ""
    private var hint = ""
    private var hintBeat = -100.0
    private var cornerHints = prefs.getInt("cornerHints", 0)
    private var rotateFlash = 0f
    private var shake = 0f
    private var deathFlash = 0f
    private var running = false

    // Camera, in world units
    private var camX = 0.0
    private var camY = 0.0
    private var camAngle = 0.0
    private var scale = 1f
    private val camMatrix = Matrix()
    private val invMatrix = Matrix()

    // Player visuals
    private var spin = 0.0
    private var landTime = -10.0
    private var jumpTime = -10.0
    private val trail = FloatArray(2 * 12)
    private var trailCount = 0
    private var trailTimer = 0.0

    private val particles = Particles()

    // Paints
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val spikePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val playerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val uiPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val vignette = Paint()
    private val rect = RectF()
    private val pts = FloatArray(8)
    private var dotBuf = FloatArray(512)

    init {
        isFocusable = true
        haloPaint.shader = RadialGradient(0f, 0f, 1f, intArrayOf(Color.WHITE, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        audio.volume = if (sound) 1f else 0f
        levels.prefetch(1)
        levels.prefetch(selected)
    }

    // ------------------------------------------------------------------ lifecycle

    fun resume() {
        audio.start()
        // A paused game keeps its audio paused until the player chooses to carry on.
        if (state != State.PAUSED) audio.resume()
        if (state == State.MENU) audio.startSong(menuSong())
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun pause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        if (state == State.PLAY || state == State.DYING) pauseGame() else audio.pause()
    }

    fun release() {
        audio.release()
        levels.shutdown()
    }

    /** Returns true if the back press was consumed. */
    fun back(): Boolean = when (state) {
        State.PLAY, State.DYING -> { pauseGame(); true }
        State.PAUSED -> { audio.resume(); toMenu(); true }
        State.MENU -> false
    }

    private var pausedWhileDying = false

    private fun pauseGame() {
        pausedWhileDying = state == State.DYING
        run?.release()
        audio.pause()
        setState(State.PAUSED)
    }

    private fun resumeGame() {
        val r = run ?: return toMenu()
        audio.resume()
        if (pausedWhileDying) return startRun(r.level)
        songSec = null
        setState(State.PLAY)
    }

    private fun setState(s: State) {
        state = s
        stateTime = 0.0
    }

    private var menuSongCache: Song? = null
    private fun menuSong(): Song = menuSongCache?.takeIf { it.startLevel == selected } ?: Song(selected).also { menuSongCache = it }

    private fun toMenu() {
        run = null
        gfx.clear()
        setState(State.MENU)
        audio.startSong(menuSong())
    }

    private fun startRun(level: Int) {
        val r = Run(level, levels)
        run = r
        gfx.clear()
        levels.prefetch(level + 1)
        songSec = null
        beat = -1.0
        spin = 0.0
        trailCount = 0
        particles.clear()
        audio.startSong(r.song)
        hint = ""
        val n = (attempts[level] ?: 0) + 1
        attempts[level] = n
        banner("LEVEL $level", if (n > 1) "attempt $n" else "tap to jump · the world turns every bar")
        bannerBeat = 0.0
        setState(State.PLAY)
        snapCamera(r)
    }

    private fun showHint(text: String) {
        hint = text
        hintBeat = beat
    }

    private fun banner(text: String, sub: String) {
        bannerText = text
        bannerSub = sub
        bannerBeat = beat
    }

    // ------------------------------------------------------------------ input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                if (state == State.PLAY || state == State.DYING) {
                    if (onPauseButton(e.getX(i), e.getY(i))) pauseGame() else run?.press()
                }
            }
            MotionEvent.ACTION_UP -> {
                run?.release()
                when (state) {
                    State.MENU -> menuTap(e.x, e.y)
                    State.PAUSED -> if (stateTime > 0.2) pausedTap(e.x, e.y)
                    else -> {}
                }
            }
            MotionEvent.ACTION_CANCEL -> run?.release()
        }
        return true
    }

    private fun pauseButtonX() = width - 22 * dp - 20 * dp - 62 * dp
    private fun pauseButtonY() = 22 * dp + 18 * dp

    private fun onPauseButton(x: Float, y: Float): Boolean {
        val dx = x - pauseButtonX()
        val dy = y - pauseButtonY()
        return dx * dx + dy * dy < (32 * dp) * (32 * dp)
    }

    private fun pausedTap(x: Float, y: Float) {
        val h = height.toFloat()
        val w = width.toFloat()
        if (abs(y - h * 0.72f) < 30 * dp) {
            if (abs(x - (w / 2 - 95 * dp)) < 80 * dp) { audio.resume(); run?.let { startRun(it.level) }; return }
            if (abs(x - (w / 2 + 95 * dp)) < 80 * dp) { audio.resume(); toMenu(); return }
        }
        resumeGame()
    }

    private fun menuTap(x: Float, y: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (x > w - 120 * dp && y > h - 70 * dp) {
            sound = !sound
            audio.volume = if (sound) 1f else 0f
            prefs.edit().putBoolean("sound", sound).apply()
            return
        }
        val selY = h * 0.80f
        if (abs(y - selY) < 34 * dp && unlocked > 1) {
            val cx = w / 2
            if (x < cx - 70 * dp && x > cx - 190 * dp) { changeSelected(-1); return }
            if (x > cx + 70 * dp && x < cx + 190 * dp) { changeSelected(1); return }
        }
        startRun(selected)
    }

    private fun changeSelected(d: Int) {
        val s = (selected + d).coerceIn(1, unlocked)
        if (s == selected) return
        selected = s
        prefs.edit().putInt("selected", s).apply()
        levels.prefetch(s)
        demo = null
        audio.startSong(menuSong())
    }

    // ------------------------------------------------------------------ frame loop

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrameNanos == 0L) 1.0 / 60 else ((frameTimeNanos - lastFrameNanos) / 1e9).coerceIn(0.0, 0.1)
        lastFrameNanos = frameTimeNanos
        time += dt
        stateTime += dt
        update(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Test hook: replaces the audio clock with a scripted one. */
    internal var clockOverride: (() -> Double?)? = null

    internal fun tickForTest(dt: Double) {
        time += dt
        stateTime += dt
        update(dt)
    }

    internal fun startForTest(level: Int) = startRun(level)

    internal fun pressForTest(down: Boolean) {
        if (down) run?.press() else run?.release()
    }

    internal val runForTest: Run? get() = run

    private fun update(dt: Double) {
        rotateFlash = max(0f, rotateFlash - dt.toFloat() * 3f)
        deathFlash = max(0f, deathFlash - dt.toFloat() * 4f)
        shake = max(0f, shake - dt.toFloat() * 2.5f)
        particles.update(dt)
        val r = run
        when (state) {
            State.PLAY -> if (r != null) {
                val raw = clockOverride?.invoke() ?: audio.songSeconds()
                if (raw != null) {
                    val s = songSec
                    songSec = if (s == null || abs(raw - (s + dt)) > 0.05) raw else s + dt + (raw - (s + dt)) * 0.1
                    beat = r.song.beatAt(songSec!!)
                    val target = max(r.tick, floor(beat * P.TPB).toInt())
                    val flags = r.advance(target)
                    handle(r, flags)
                    if (r.level == 1 && r.cur == 0 && beat >= 1.5 && hint.isEmpty()) {
                        showHint("every bar the world turns: the wall ahead becomes your floor")
                    }
                }
            }
            State.DYING -> if (stateTime > 0.75 && r != null) startRun(r.level)
            else -> {}
        }
        if (state == State.MENU) {
            val s = audio.songSeconds()
            beat = if (s != null) menuSong().beatAt(max(s, 0.0)) else time * 2
        }
    }

    private fun handle(r: Run, flags: Int) {
        if (flags and Ev.JUMP != 0) {
            jumpTime = beat
            audio.sfx(AudioEngine.SFX_JUMP)
        }
        if (flags and Ev.LAND != 0) {
            landTime = beat
            val (dx, dy) = downVec(r)
            particles.burst(r.px1 + dx * 0.4, r.py1 + dy * 0.4, 5, 2.5, -dx, -dy, Color.WHITE, 0.35)
        }
        if (flags and Run.ROTATED != 0) {
            rotateFlash = 1f
            val ahead = r.seg(r.cur + 1)?.layout
            if (ahead != null && ahead.corner != Corner.PLAIN && cornerHints < 4) {
                cornerHints++
                prefs.edit().putInt("cornerHints", cornerHints).apply()
                showHint(
                    if (ahead.corner == Corner.FAR_PIT) "gap further up the wall? stay low when it turns"
                    else "hole in the wall ahead? be mid-jump when it turns"
                )
            }
        }
        if (flags and Run.LEVEL_UP != 0) {
            val lvl = r.level
            if (lvl > unlocked) {
                unlocked = lvl
                prefs.edit().putInt("unlocked", unlocked).apply()
            }
            selected = lvl
            prefs.edit().putInt("selected", selected).apply()
            levels.prefetch(lvl + 1)
            audio.sfx(AudioEngine.SFX_LEVEL)
            banner("LEVEL $lvl", "")
        }
        if (flags and Ev.DIE != 0) {
            audio.stopSong()
            audio.sfx(AudioEngine.SFX_DIE)
            particles.burst(r.px1, r.py1, 26, 7.0, 0.0, 0.0, Color.WHITE, 0.9)
            particles.burst(r.px1, r.py1, 12, 5.0, 0.0, 0.0, Palette.of(r.level).glow, 0.8)
            shake = 1f
            deathFlash = 1f
            setState(State.DYING)
        }
    }

    private fun downVec(r: Run): Pair<Double, Double> {
        val f = r.current.frame
        return Pair(-f.ux.toDouble(), -f.uy.toDouble())
    }

    // ------------------------------------------------------------------ camera

    private fun cameraFor(r: Run, px: Double, py: Double, index: Int): Pair<Double, Double> {
        val f = r.seg(index)!!.frame
        val v = (px - f.ox) * f.ux + (py - f.oy) * f.uy
        val h = 2.6 - v
        return Pair(px + 5.0 * f.fx + h * f.ux, py + 5.0 * f.fy + h * f.uy)
    }

    private fun rotationProgress(r: Run): Double {
        if (r.cur == 0) return 1.0
        val since = beat - r.lastRotateTick.toDouble() / P.TPB
        return (since / 0.35).coerceIn(0.0, 1.0)
    }

    private fun snapCamera(r: Run) {
        val (cx, cy) = cameraFor(r, r.px1, r.py1, r.cur)
        camX = cx; camY = cy
        camAngle = -90.0 * r.cur
    }

    private fun updateCamera(r: Run, px: Double, py: Double) {
        val p = rotationProgress(r)
        val e = 1 - (1 - p).pow(3)
        val (nx, ny) = cameraFor(r, px, py, r.cur)
        if (p < 1.0 && r.cur > 0) {
            val (ox, oy) = cameraFor(r, px, py, r.cur - 1)
            camX = ox + (nx - ox) * e
            camY = oy + (ny - oy) * e
            camAngle = -90.0 * (r.cur - 1 + e)
        } else {
            camX = nx; camY = ny
            camAngle = -90.0 * r.cur
        }
    }

    private fun applyCamera(canvas: Canvas, zoom: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        scale = min(h / 12.5f, w / 22f) * zoom
        camMatrix.reset()
        camMatrix.preTranslate(w / 2, h / 2)
        if (shake > 0f) {
            val s = shake * shake * 10 * dp
            camMatrix.preTranslate(Random.nextFloat() * s - s / 2, Random.nextFloat() * s - s / 2)
        }
        camMatrix.preScale(scale, -scale)
        camMatrix.preRotate(camAngle.toFloat())
        camMatrix.preTranslate(-camX.toFloat(), -camY.toFloat())
        canvas.concat(camMatrix)
        camMatrix.invert(invMatrix)
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val r = run
        val pal = Palette.of(r?.level ?: selected)
        canvas.drawColor(backgroundColor(r))

        when (state) {
            State.MENU -> drawMenu(canvas)
            else -> if (r != null) drawGame(canvas, r)
        }
        drawVignette(canvas)

        if (state != State.MENU && r != null) {
            drawHud(canvas, r, pal)
            drawHint(canvas, pal)
            if (state != State.PAUSED) drawPauseButton(canvas)
        }
        if (deathFlash > 0f) canvas.drawColor(Palette.withAlpha(Color.WHITE, deathFlash * 0.25f))
        if (state == State.PAUSED) drawPaused(canvas)
    }

    private fun backgroundColor(r: Run?): Int {
        if (r == null) return Palette.of(selected).bg
        val lvl = r.level
        val cur = Palette.of(lvl).bg
        if (lvl == r.startLevel || r.barInLevel > 0) return cur
        val levelBeat = r.cur * 4.0
        return Palette.lerp(Palette.of(lvl - 1).bg, cur, ((beat - levelBeat) / 2.0).toFloat())
    }

    private fun drawGame(canvas: Canvas, r: Run) {
        val frac = if (state == State.PLAY) (beat * P.TPB - (r.tick - 1)).coerceIn(0.0, 1.0) else 1.0
        val px = r.px0 + (r.px1 - r.px0) * frac
        val py = r.py0 + (r.py1 - r.py0) * frac
        updateCamera(r, px, py)
        val rp = rotationProgress(r)
        val zoom = 1f - 0.035f * sin(PI * rp).toFloat()

        canvas.save()
        applyCamera(canvas, zoom)
        val pulse = beatPulse()
        drawDots(canvas, Palette.of(r.level), pulse)
        for (i in r.cur + 2 downTo r.cur - 2) {
            val seg = r.seg(i) ?: continue
            // Two bars back is the old ceiling: let it go as the new one turns into view.
            val a = if (i < r.cur - 1) (1.0 - rp).toFloat() else 1f
            if (a <= 0.01f) continue
            drawSegment(canvas, r, seg, a, pulse)
        }
        if (!r.body.dead) drawPlayer(canvas, r, px, py)
        particles.draw(canvas, playerPaint, (-camAngle).toFloat())
        canvas.restore()
    }

    private fun beatPulse(): Float {
        val f = beat - floor(beat)
        return if (beat < 0) 0f else exp(-f * 5.0).toFloat()
    }

    private fun gfxFor(r: Run, seg: Placed): SegGfx {
        val cached = gfx[seg]
        if (cached != null && cached.complete) return cached
        val prev = r.seg(seg.index - 1)?.layout
        val next = r.seg(seg.index + 1)?.layout
        if (cached != null && (prev == null || next == null)) return cached
        return SegGfx(seg, prev, next).also { gfx[seg] = it }
    }

    private fun drawSegment(canvas: Canvas, r: Run, seg: Placed, alpha: Float, pulse: Float) {
        val g = gfxFor(r, seg)
        val pal = Palette.of(g.level)
        fillPaint.color = Palette.withAlpha(pal.fill, alpha)
        val f = g.fills
        var i = 0
        while (i < f.size) {
            canvas.drawRect(f[i], f[i + 1], f[i + 2], f[i + 3], fillPaint)
            i += 4
        }
        glowPaint.color = Palette.withAlpha(pal.glow, alpha * (0.16f + 0.14f * pulse))
        glowPaint.strokeWidth = 0.32f
        canvas.drawLines(g.edges, glowPaint)
        edgePaint.color = Palette.withAlpha(Palette.lerp(pal.edge, Color.WHITE, 0.35f * pulse), alpha)
        edgePaint.strokeWidth = 0.09f
        canvas.drawLines(g.edges, edgePaint)
        spikePaint.color = Palette.withAlpha(pal.spike, alpha)
        canvas.drawPath(g.spikes, spikePaint)
    }

    private fun drawDots(canvas: Canvas, pal: Palette, pulse: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        pts[0] = 0f; pts[1] = 0f; pts[2] = w; pts[3] = 0f; pts[4] = 0f; pts[5] = h; pts[6] = w; pts[7] = h
        invMatrix.mapPoints(pts)
        var x0 = pts[0]; var x1 = pts[0]; var y0 = pts[1]; var y1 = pts[1]
        for (k in 1..3) {
            x0 = min(x0, pts[2 * k]); x1 = max(x1, pts[2 * k])
            y0 = min(y0, pts[2 * k + 1]); y1 = max(y1, pts[2 * k + 1])
        }
        val step = 2
        val gx0 = floor(x0 / step).toInt() * step
        val gy0 = floor(y0 / step).toInt() * step
        var n = 0
        var gx = gx0
        while (gx <= ceil(x1)) {
            var gy = gy0
            while (gy <= ceil(y1)) {
                if (n + 2 > dotBuf.size) dotBuf = dotBuf.copyOf(dotBuf.size * 2)
                dotBuf[n++] = gx.toFloat()
                dotBuf[n++] = gy.toFloat()
                gy += step
            }
            gx += step
        }
        dotPaint.color = Palette.withAlpha(pal.dots, 0.18f + 0.12f * pulse)
        dotPaint.strokeWidth = 0.13f
        canvas.drawPoints(dotBuf, 0, n, dotPaint)
    }

    private fun drawPlayer(canvas: Canvas, r: Run, px: Double, py: Double) {
        // Spin a quarter turn per jump, then settle on the nearest edge.
        val air = beat - jumpTime
        val jumping = !r.body.grounded && jumpTime > landTime && air in 0.0..1.0
        val target = if (jumping) -90.0 * smooth(air) else (spin / 90.0).roundToInt() * 90.0
        spin = if (jumping) target else spin + (target - spin) * 0.35
        if (r.body.grounded && abs(spin - target) < 0.5) spin = 0.0

        val sinceLand = (beat - landTime).coerceAtLeast(0.0)
        val squash = (exp(-sinceLand * 9.0)).toFloat()
        val sx = 1f + 0.22f * squash
        val sy = 1f - 0.24f * squash

        val pal = Palette.of(r.level)
        val upright = (-camAngle).toFloat()

        // Trail
        trailTimer += 1
        if (trailTimer >= 1) {
            trailTimer = 0.0
            System.arraycopy(trail, 0, trail, 2, trail.size - 2)
            trail[0] = px.toFloat(); trail[1] = py.toFloat()
            trailCount = min(trailCount + 1, trail.size / 2)
        }
        trailPaint.color = pal.glow
        for (k in trailCount - 1 downTo 1) {
            val t = 1f - k.toFloat() / trailCount
            trailPaint.alpha = (t * 0.45f * 255).toInt()
            trailPaint.strokeWidth = 0.55f * t + 0.05f
            val ax = if (k == 1) px.toFloat() else trail[2 * k - 2]
            val ay = if (k == 1) py.toFloat() else trail[2 * k - 1]
            canvas.drawLine(trail[2 * k], trail[2 * k + 1], ax, ay, trailPaint)
        }

        canvas.save()
        canvas.translate(px.toFloat(), py.toFloat())
        canvas.save()
        canvas.scale(1.6f, 1.6f)
        haloPaint.alpha = (90 + 60 * beatPulse()).toInt()
        canvas.drawCircle(0f, 0f, 1f, haloPaint)
        canvas.restore()
        canvas.rotate(upright)
        // Squash relative to the floor, which is always screen-down.
        canvas.translate(0f, -P.HALF.toFloat() * (1f - sy))
        canvas.scale(sx, sy)
        canvas.rotate(spin.toFloat())
        val h = P.HALF.toFloat()
        rect.set(-h, -h, h, h)
        playerPaint.color = Color.WHITE
        canvas.drawRoundRect(rect, 0.12f, 0.12f, playerPaint)
        playerPaint.color = Palette.withAlpha(pal.glow, 0.9f)
        rect.set(-h * 0.42f, -h * 0.42f, h * 0.42f, h * 0.42f)
        canvas.drawRoundRect(rect, 0.06f, 0.06f, playerPaint)
        canvas.restore()
    }

    private fun smooth(t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        return x * x * (3 - 2 * x)
    }

    private fun drawVignette(canvas: Canvas) {
        if (vignette.shader == null) return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignette)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val radius = max(w, h) * 0.75f
        vignette.shader = RadialGradient(
            w / 2f, h / 2f, radius,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0x88000000.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
    }

    private fun drawHud(canvas: Canvas, r: Run, pal: Palette) {
        val w = width.toFloat()
        val m = 22 * dp

        // Level and bar pips, top-left
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 15 * dp
        textPaint.letterSpacing = 0.2f
        textPaint.color = Palette.withAlpha(Color.WHITE, 0.85f)
        canvas.drawText("LEVEL ${r.level}", m, m + 12 * dp, textPaint)
        val bar = r.barInLevel
        for (i in 0 until LevelGen.BARS) {
            val x = m + 4 * dp + i * 13 * dp
            val y = m + 28 * dp
            uiPaint.color = when {
                i < bar -> pal.edge
                i == bar -> Palette.lerp(pal.edge, Color.WHITE, beatPulse())
                else -> Palette.withAlpha(Color.WHITE, 0.18f)
            }
            canvas.drawCircle(x, y, if (i == bar) 4 * dp else 3 * dp, uiPaint)
        }

        // Bar clock, top-right: fills up to the next rotation.
        val cx = w - m - 20 * dp
        val cy = m + 18 * dp
        val rad = 17 * dp
        val barFrac = ((beat / 4.0) - floor(beat / 4.0)).toFloat().coerceIn(0f, 1f)
        uiPaint.style = Paint.Style.STROKE
        uiPaint.strokeWidth = 3 * dp
        uiPaint.color = Palette.withAlpha(Color.WHITE, 0.15f)
        canvas.drawCircle(cx, cy, rad, uiPaint)
        uiPaint.color = Palette.lerp(pal.edge, Color.WHITE, rotateFlash)
        uiPaint.strokeCap = Paint.Cap.ROUND
        if (beat >= 0) {
            rect.set(cx - rad, cy - rad, cx + rad, cy + rad)
            canvas.drawArc(rect, -90f, 360f * barFrac, false, uiPaint)
        }
        uiPaint.style = Paint.Style.FILL
        for (k in 0 until 4) {
            val a = -PI / 2 + k * PI / 2
            val on = beat >= 0 && floor(beat).toInt().mod(4) >= k
            uiPaint.color = if (on) Color.WHITE else Palette.withAlpha(Color.WHITE, 0.3f)
            canvas.drawCircle(cx + (cos(a) * (rad + 9 * dp)).toFloat(), cy + (sin(a) * (rad + 9 * dp)).toFloat(), 2.2f * dp, uiPaint)
        }
        // Curved arrow hint in the middle of the clock.
        uiPaint.style = Paint.Style.STROKE
        uiPaint.strokeWidth = 2 * dp
        uiPaint.color = Palette.withAlpha(Color.WHITE, 0.55f + 0.45f * rotateFlash)
        rect.set(cx - 7 * dp, cy - 7 * dp, cx + 7 * dp, cy + 7 * dp)
        canvas.drawArc(rect, -60f, 250f, false, uiPaint)
        uiPaint.style = Paint.Style.FILL

        // Banner
        val since = beat - bannerBeat
        if (bannerText.isNotEmpty() && since < 3.0) {
            val a = when {
                since < 0 -> 1f
                since < 2.0 -> 1f
                else -> (3.0 - since).toFloat()
            }.coerceIn(0f, 1f)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.letterSpacing = 0.3f
            textPaint.textSize = 34 * dp
            textPaint.color = Palette.withAlpha(Color.WHITE, a)
            canvas.drawText(bannerText, w / 2, height * 0.30f, textPaint)
            if (bannerSub.isNotEmpty()) {
                textPaint.textSize = 13 * dp
                textPaint.letterSpacing = 0.1f
                textPaint.color = Palette.withAlpha(pal.accent, a * 0.85f)
                canvas.drawText(bannerSub, w / 2, height * 0.30f + 28 * dp, textPaint)
            }
        }
    }

    private fun drawHint(canvas: Canvas, pal: Palette) {
        val since = beat - hintBeat
        if (hint.isEmpty() || since > 6.0) return
        val a = (min(since * 4.0, 1.0) * min((6.0 - since) / 1.0, 1.0)).toFloat().coerceIn(0f, 1f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.letterSpacing = 0.1f
        textPaint.textSize = 15 * dp
        val tw = textPaint.measureText(hint)
        val y = height * 0.2f
        uiPaint.color = Palette.withAlpha(Color.BLACK, 0.35f * a)
        rect.set(width / 2f - tw / 2 - 16 * dp, y - 22 * dp, width / 2f + tw / 2 + 16 * dp, y + 12 * dp)
        canvas.drawRoundRect(rect, 17 * dp, 17 * dp, uiPaint)
        textPaint.color = Palette.withAlpha(pal.accent, a)
        canvas.drawText(hint, width / 2f, y, textPaint)
    }

    private fun drawPaused(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(0xAA000000.toInt())
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.letterSpacing = 0.3f
        textPaint.textSize = 32 * dp
        textPaint.color = Color.WHITE
        canvas.drawText("PAUSED", w / 2, h * 0.36f, textPaint)
        textPaint.textSize = 14 * dp
        textPaint.letterSpacing = 0.1f
        textPaint.color = Palette.withAlpha(Color.WHITE, 0.7f)
        canvas.drawText(if (pausedWhileDying) "tap to try again" else "tap anywhere to resume", w / 2, h * 0.36f + 34 * dp, textPaint)

        val pal = Palette.of(run?.level ?: selected)
        textPaint.textSize = 14 * dp
        textPaint.letterSpacing = 0.2f
        for ((i, label) in listOf("RETRY LEVEL", "MENU").withIndex()) {
            val cx = w / 2 + (if (i == 0) -95 else 95) * dp
            val cy = h * 0.72f
            rect.set(cx - 78 * dp, cy - 22 * dp, cx + 78 * dp, cy + 22 * dp)
            uiPaint.style = Paint.Style.STROKE
            uiPaint.strokeWidth = 1.5f * dp
            uiPaint.color = Palette.withAlpha(pal.edge, 0.8f)
            canvas.drawRoundRect(rect, 22 * dp, 22 * dp, uiPaint)
            uiPaint.style = Paint.Style.FILL
            textPaint.color = Color.WHITE
            canvas.drawText(label, cx, cy + 5 * dp, textPaint)
        }
    }

    private fun drawPauseButton(canvas: Canvas) {
        val cx = pauseButtonX()
        val cy = pauseButtonY()
        uiPaint.style = Paint.Style.FILL
        uiPaint.color = Palette.withAlpha(Color.WHITE, 0.08f)
        canvas.drawCircle(cx, cy, 19 * dp, uiPaint)
        uiPaint.color = Palette.withAlpha(Color.WHITE, 0.75f)
        rect.set(cx - 6 * dp, cy - 7 * dp, cx - 2 * dp, cy + 7 * dp)
        canvas.drawRoundRect(rect, 1.5f * dp, 1.5f * dp, uiPaint)
        rect.set(cx + 2 * dp, cy - 7 * dp, cx + 6 * dp, cy + 7 * dp)
        canvas.drawRoundRect(rect, 1.5f * dp, 1.5f * dp, uiPaint)
    }

    // ------------------------------------------------------------------ menu

    private fun drawMenu(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pal = Palette.of(selected)

        // A slowly turning glimpse of the selected level behind the title.
        val d = demo ?: levels.ready(selected)?.let { Run(selected, levels) }?.also { demo = it; gfx.clear() }
        if (d != null) {
            var sx = 0.0; var sy = 0.0
            for (i in 0..2) { val f = d.seg(i)!!.frame; sx += f.ox; sy += f.oy }
            val turn = time * 14.0 + 30.0 * smoothStepBeat()
            camX = sx / 3 + 4 * cos(time * 0.3)
            camY = sy / 3 + 4 * sin(time * 0.37)
            camAngle = -turn
            canvas.save()
            applyCamera(canvas, 0.42f)
            val pulse = beatPulse()
            drawDots(canvas, pal, pulse)
            for (i in -1..2) drawSegment(canvas, d, d.seg(i)!!, 0.55f, pulse)
            canvas.restore()
            canvas.drawColor(Palette.withAlpha(pal.bg, 0.35f))
        }

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.letterSpacing = 0.35f
        textPaint.textSize = min(64 * dp, w / 9f)
        textPaint.color = Color.WHITE
        canvas.drawText("ROTATION", w / 2, h * 0.33f, textPaint)
        textPaint.textSize = 16 * dp
        textPaint.letterSpacing = 1.1f
        textPaint.color = pal.edge
        canvas.drawText("BEATS", w / 2, h * 0.33f + 30 * dp, textPaint)

        textPaint.letterSpacing = 0.08f
        textPaint.textSize = 13 * dp
        textPaint.color = Palette.withAlpha(Color.WHITE, 0.6f)
        canvas.drawText("tap to jump  ·  the world turns every bar  ·  plan for the floor that's coming", w / 2, h * 0.50f, textPaint)

        val pulse = beatPulse()
        textPaint.letterSpacing = 0.3f
        textPaint.textSize = 18 * dp
        textPaint.color = Palette.withAlpha(Color.WHITE, 0.65f + 0.35f * pulse)
        canvas.drawText("TAP TO PLAY", w / 2, h * 0.65f, textPaint)

        val selY = h * 0.80f
        textPaint.textSize = 15 * dp
        textPaint.letterSpacing = 0.2f
        textPaint.color = pal.edge
        canvas.drawText("LEVEL $selected", w / 2, selY + 5 * dp, textPaint)
        if (unlocked > 1) {
            textPaint.textSize = 26 * dp
            textPaint.color = Palette.withAlpha(Color.WHITE, if (selected > 1) 0.85f else 0.2f)
            canvas.drawText("‹", w / 2 - 110 * dp, selY + 8 * dp, textPaint)
            textPaint.color = Palette.withAlpha(Color.WHITE, if (selected < unlocked) 0.85f else 0.2f)
            canvas.drawText("›", w / 2 + 110 * dp, selY + 8 * dp, textPaint)
        }

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 13 * dp
        textPaint.letterSpacing = 0.15f
        textPaint.color = Palette.withAlpha(Color.WHITE, 0.6f)
        canvas.drawText(if (sound) "SOUND ON" else "SOUND OFF", w - 24 * dp, h - 26 * dp, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("BEST  LEVEL $unlocked", 24 * dp, h - 26 * dp, textPaint)
    }

    /** Eased quarter turn on every bar of the menu music. */
    private fun smoothStepBeat(): Double {
        val bars = beat / 4.0
        val whole = floor(bars)
        val f = ((bars - whole) * 4.0 / 0.4).coerceIn(0.0, 1.0)
        return (whole + 1 - (1 - f).pow(3)) * 3.0
    }
}

/** Generates levels off the main thread, ahead of when they are needed. */
class LevelStore : LevelSource {
    private val cache = ConcurrentHashMap<Int, List<SegLayout>>()
    private val pending = ConcurrentHashMap<Int, Future<List<SegLayout>>>()
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "levelgen").apply { isDaemon = true } }

    fun prefetch(n: Int) {
        if (cache.containsKey(n)) return
        pending.computeIfAbsent(n) { exec.submit<List<SegLayout>> { LevelGen(n).generate().also { cache[n] = it } } }
    }

    fun ready(n: Int): List<SegLayout>? {
        prefetch(n)
        return cache[n]
    }

    override fun level(n: Int): List<SegLayout> {
        cache[n]?.let { return it }
        prefetch(n)
        return pending[n]!!.get()
    }

    fun shutdown() = exec.shutdownNow()
}

/** Small square particles living in world space. */
class Particles {
    private val max = 160
    private val x = DoubleArray(max)
    private val y = DoubleArray(max)
    private val vx = DoubleArray(max)
    private val vy = DoubleArray(max)
    private val life = DoubleArray(max)
    private val total = DoubleArray(max)
    private val color = IntArray(max)
    private var n = 0

    fun clear() { n = 0 }

    fun burst(px: Double, py: Double, count: Int, speed: Double, dirX: Double, dirY: Double, c: Int, lifetime: Double) {
        repeat(count) {
            if (n >= max) return
            val a = Random.nextDouble(0.0, 2 * PI)
            val s = speed * Random.nextDouble(0.3, 1.0)
            x[n] = px; y[n] = py
            vx[n] = cos(a) * s + dirX * speed * 0.5
            vy[n] = sin(a) * s + dirY * speed * 0.5
            total[n] = lifetime * Random.nextDouble(0.6, 1.0)
            life[n] = total[n]
            color[n] = c
            n++
        }
    }

    fun update(dt: Double) {
        var i = 0
        while (i < n) {
            life[i] -= dt
            if (life[i] <= 0) {
                n--
                x[i] = x[n]; y[i] = y[n]; vx[i] = vx[n]; vy[i] = vy[n]
                life[i] = life[n]; total[i] = total[n]; color[i] = color[n]
                continue
            }
            val damp = exp(-dt * 3.0)
            vx[i] *= damp; vy[i] *= damp
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            i++
        }
    }

    fun draw(canvas: Canvas, paint: Paint, angle: Float) {
        for (i in 0 until n) {
            val t = (life[i] / total[i]).toFloat()
            paint.color = Palette.withAlpha(color[i], t)
            val s = 0.09f + 0.08f * t
            canvas.save()
            canvas.translate(x[i].toFloat(), y[i].toFloat())
            canvas.rotate(angle + (1 - t) * 180f)
            canvas.drawRect(-s, -s, s, s, paint)
            canvas.restore()
        }
    }
}
