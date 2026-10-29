package net.serble.rotationbeats

import net.serble.rotationbeats.core.Song
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** The music and sound effects. Pure DSP with no Android dependencies, so it can be tested on the JVM. */
class Synth(private val sr: Int) {
    /** Starts [s] [lead] seconds from now; returns the output frame at which its beat 0 sounds. */
    fun startSong(s: Song, lead: Double): Long {
        song = s
        songStartFrame = frame + (lead * sr).toLong()
        nextStep = 0
        nextStepFrame = stepFrame(s, 0)
        musicTarget = 1f
        songPlaying = true
        return songStartFrame
    }

    fun stopSong() {
        musicTarget = 0f
        songPlaying = false
    }

    fun render(buf: ShortArray, n: Int, volume: Float) {
        for (i in 0 until n) {
            val s = sample() * volume
            buf[i] = (s.coerceIn(-1f, 1f) * 32000f).toInt().toShort()
            frame++
        }
    }

    private var frame = 0L
    private var songStartFrame = 0L
    private var song: Song? = null
    private var songPlaying = false
    private var nextStep = 0
    private var nextStepFrame = Long.MAX_VALUE
    private var musicGain = 0f
    private var musicTarget = 0f

    private fun stepFrame(s: Song, step: Int): Long {
        val bar = step / 16
        val sec = s.barStart(bar) + (step % 16) * s.barSeconds(bar) / 16.0
        return songStartFrame + (sec * sr).toLong()
    }

    // --- voices
    private var noiseSeed = 0x1234567
    private fun noise(): Float {
        var x = noiseSeed
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        noiseSeed = x
        return (x and 0xffff) / 32768f - 1f
    }

    private val inv = 1f / sr
    private fun decay(seconds: Float) = exp(-inv / seconds)

    // kick
    private var kickT = -1f; private var kickPh = 0f
    // snare / clap
    private var clapEnv = 0f; private val clapDecay = decay(0.07f); private var clapLp = 0f
    // hats
    private var hatEnv = 0f; private var hatDecay = decay(0.02f); private var hatPrev = 0f; private var hatAmp = 0f
    // crash
    private var crashEnv = 0f; private val crashDecay = decay(0.45f); private var crashPrev = 0f
    // riser
    private var riserT = -1f; private var riserLen = 0.5f; private var riserLp = 0f
    // bass
    private var bassF = 55f; private var bassPh = 0f; private var bassEnv = 0f; private val bassDecay = decay(0.16f)
    private var bassLp = 0f; private var bassCut = 0f
    // arp
    private var arpF = 440f; private var arpPh = 0f; private var arpEnv = 0f; private val arpDecay = decay(0.09f)
    private val echo = FloatArray(sr)
    private var echoPos = 0
    private var echoLen = sr / 3
    // pad
    private val padF = FloatArray(3)
    private val padPh = FloatArray(6)
    private var padLp = 0f
    private var padEnv = 0f
    // sidechain
    private var duck = 1f

    // sfx
    private var jumpT = -1f
    private var dieT = -1f
    private var dieLp = 0f
    private var chimeT = -1f
    private var chimeBase = 0

    private var intensity = 1
    private var key = 0
    private var prog = PROGS[0]

    private fun midi(n: Int) = (440.0 * 2.0.pow((n - 69) / 12.0)).toFloat()

    private fun step(s: Song, step: Int) {
        val bar = step / 16
        val st = step % 16
        val level = s.levelOfBar(bar)
        intensity = when {
            level <= 1 -> 1
            level == 2 -> 2
            level <= 4 -> 3
            else -> 4
        }
        key = KEYS[(level - 1) % KEYS.size]
        prog = PROGS[(level - 1) % PROGS.size]
        val chord = prog[bar % 4]
        val minor = chord == 0 || chord == 5
        val tones = intArrayOf(0, if (minor) 3 else 4, 7)
        val beatSec = s.barSeconds(bar) / 4.0
        echoLen = min(sr - 1, (beatSec * 0.75 * sr).toInt())

        if (st == 0) {
            for (i in 0..2) padF[i] = midi(57 + key + chord + tones[i])
            padEnv = 1f
            if (bar > 0) crashEnv = 0.9f
        }
        if (st % 4 == 0) { kickT = 0f; duck = 0f }
        if (intensity >= 2 && (st == 4 || st == 12)) clapEnv = 1f
        if (st % 4 == 2) { hatEnv = 1f; hatAmp = 0.22f; hatDecay = decay(0.045f) }
        else if (intensity >= 3 && st % 2 == 1) { hatEnv = 1f; hatAmp = 0.10f; hatDecay = decay(0.018f) }
        if (st == 12 && intensity >= 2) { riserT = 0f; riserLen = (beatSec * 1.0).toFloat() }

        if (st % 2 == 0 && !(bar == 0 && st < 8)) {
            val pat = BASS[(st / 2) % BASS.size]
            bassF = midi(45 + key + chord + pat)
            bassEnv = 1f
            bassCut = 1f
        }
        val arpEvery = if (intensity >= 3) 1 else 2
        if (st % arpEvery == 0) {
            val idx = ARP[(st / arpEvery) % ARP.size]
            val oct = if (intensity >= 4 && (st / 4) % 2 == 1) 12 else 0
            arpF = midi(69 + key + chord + tones[idx % 3] + (idx / 3) * 12 + oct - 12)
            arpEnv = if (st % 4 == 0) 1f else 0.7f
        }
    }

    fun sfx(id: Int) {
        when (id) {
            AudioEngine.SFX_JUMP -> jumpT = 0f
            AudioEngine.SFX_DIE -> dieT = 0f
            AudioEngine.SFX_LEVEL -> { chimeT = 0f; chimeBase = 69 + key }
        }
    }

    private fun sample(): Float {
        val s = song
        if (s != null && songPlaying) {
            while (frame >= nextStepFrame) {
                step(s, nextStep)
                nextStep++
                nextStepFrame = stepFrame(s, nextStep)
            }
        }
        musicGain += (musicTarget - musicGain) * 0.0008f
        duck += (1f - duck) * 0.00035f

        var drums = 0f
        var music = 0f

        if (kickT >= 0f) {
            val f = 50f + 190f * exp(-kickT * 30f)
            kickPh += 2f * PI.toFloat() * f * inv
            if (kickPh > 2f * PI.toFloat()) kickPh -= 2f * PI.toFloat()
            drums += sin(kickPh) * exp(-kickT * 10f) * 0.6f
            if (kickT < 0.006f) drums += noise() * (1f - kickT / 0.006f) * 0.25f
            kickT += inv
            if (kickT > 0.5f) kickT = -1f
        }
        if (clapEnv > 0.001f) {
            val n = noise()
            clapLp += (n - clapLp) * 0.35f
            drums += (n - clapLp) * clapEnv * 0.5f
            clapEnv *= clapDecay
        }
        if (hatEnv > 0.001f) {
            val n = noise()
            drums += (n - hatPrev) * 0.5f * hatEnv * hatAmp
            hatPrev = n
            hatEnv *= hatDecay
        }
        if (crashEnv > 0.001f) {
            val n = noise()
            drums += (n - crashPrev) * 0.5f * crashEnv * 0.12f
            crashPrev = n
            crashEnv *= crashDecay
        }
        if (riserT >= 0f) {
            val p = riserT / riserLen
            val n = noise()
            riserLp += (n - riserLp) * (0.02f + 0.5f * p * p)
            drums += riserLp * p * p * 0.18f
            riserT += inv
            if (riserT > riserLen) riserT = -1f
        }

        // Bass: saw through a plucky low-pass
        bassPh += bassF * inv
        if (bassPh >= 1f) bassPh -= 1f
        val saw = bassPh * 2f - 1f
        val cut = 0.05f + 0.22f * bassCut
        bassLp += (saw - bassLp) * cut
        bassCut *= 0.9994f
        music += bassLp * bassEnv * 0.3f * duck
        bassEnv *= bassDecay

        // Arp: soft triangle with echo
        arpPh += arpF * inv
        if (arpPh >= 1f) arpPh -= 1f
        val tri = 1f - 4f * abs(arpPh - 0.5f)
        val sq = if (arpPh < 0.5f) 0.3f else -0.3f
        val arp = (tri + sq) * arpEnv * 0.17f
        arpEnv *= arpDecay
        val delayed = echo[(echoPos - echoLen + sr) % sr]
        echo[echoPos] = arp + delayed * 0.38f
        echoPos = (echoPos + 1) % sr
        music += arp + delayed * 0.5f

        // Pad: detuned saws, filtered, side-chained to the kick
        if (intensity >= 1) {
            var pad = 0f
            for (i in 0..2) {
                for (k in 0..1) {
                    val idx = i * 2 + k
                    padPh[idx] += padF[i] * (if (k == 0) 1.003f else 0.997f) * inv
                    if (padPh[idx] >= 1f) padPh[idx] -= 1f
                    pad += padPh[idx] * 2f - 1f
                }
            }
            padLp += (pad - padLp) * 0.07f
            padEnv = max(padEnv * 0.99999f, 0.6f)
            music += padLp * 0.045f * padEnv * (0.35f + 0.65f * duck)
        }

        var out = (drums + music) * musicGain

        // Sound effects (independent of music fade)
        if (jumpT >= 0f) {
            val f = 520f + 900f * jumpT / 0.07f
            out += sin(2f * PI.toFloat() * f * jumpT) * exp(-jumpT * 40f) * 0.16f
            jumpT += inv
            if (jumpT > 0.12f) jumpT = -1f
        }
        if (dieT >= 0f) {
            val n = noise()
            dieLp += (n - dieLp) * max(0.02f, 0.5f - dieT)
            val f = 220f * exp(-dieT * 4f)
            val sq = if (sin(2f * PI.toFloat() * f * dieT) > 0) 1f else -1f
            out += (dieLp * 0.5f + sq * 0.12f) * exp(-dieT * 5f)
            dieT += inv
            if (dieT > 0.8f) dieT = -1f
        }
        if (chimeT >= 0f) {
            val note = (chimeT / 0.08f).toInt().coerceAtMost(3)
            val f = midi(chimeBase + CHIME[note])
            val local = chimeT - note * 0.08f
            out += sin(2f * PI.toFloat() * f * local) * exp(-local * 9f) * 0.12f
            chimeT += inv
            if (chimeT > 0.7f) chimeT = -1f
        }

        // Gentle soft clip
        return out / (1f + abs(out) * 0.6f)
    }

    private companion object {
        val KEYS = intArrayOf(0, 5, -2, 3, -4, 1, -1, 4, -3, 2)
        val PROGS = arrayOf(
            intArrayOf(0, 8, 3, 10),
            intArrayOf(0, 10, 8, 7),
            intArrayOf(0, 5, 8, 7),
            intArrayOf(0, 3, 10, 8),
        )
        val BASS = intArrayOf(0, 0, 12, 0, 7, 0, 12, 10)
        val ARP = intArrayOf(0, 1, 2, 3, 2, 1, 4, 2)
        val CHIME = intArrayOf(0, 7, 12, 19)
    }
}
