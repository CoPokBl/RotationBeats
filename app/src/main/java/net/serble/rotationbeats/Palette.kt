package net.serble.rotationbeats

import android.graphics.Color

/** Each level gets its own hue so progress is felt as well as seen. */
class Palette(level: Int) {
    private val hue = (205f + (level - 1) * 47f) % 360f

    val bg = hsv(hue, 0.60f, 0.11f)
    val dots = hsv(hue, 0.35f, 0.55f)
    val fill = hsv(hue, 0.50f, 0.21f)
    val edge = hsv(hue, 0.45f, 1.00f)
    val glow = hsv(hue, 0.70f, 1.00f)
    val spike = hsv((hue + 150f) % 360f, 0.62f, 1.00f)
    val accent = hsv((hue + 40f) % 360f, 0.35f, 1.00f)

    companion object {
        private val cache = HashMap<Int, Palette>()
        fun of(level: Int) = cache.getOrPut(level) { Palette(level) }

        fun hsv(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf(h, s, v))

        fun lerp(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            return Color.argb(
                (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
                (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
            )
        }

        fun withAlpha(c: Int, a: Float) = (c and 0x00ffffff) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
    }
}
