package net.serble.rotationbeats

import net.serble.rotationbeats.core.Song
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

class SynthTest {
    @Test
    fun rendersCleanAudio() {
        for (level in listOf(1, 3, 6)) {
            val sr = 44100
            val synth = Synth(sr)
            val song = Song(level)
            synth.startSong(song, 0.1)
            val seconds = song.barStart(8) + 0.5
            val n = (seconds * sr).toInt()
            val pcm = ShortArray(n)
            var i = 0
            val buf = ShortArray(256)
            while (i < n) {
                if (i == sr * 5) synth.sfx(AudioEngine.SFX_JUMP)
                synth.render(buf, 256, 1f)
                System.arraycopy(buf, 0, pcm, i, minOf(256, n - i))
                i += 256
            }
            var peak = 0
            var sum = 0.0
            var clipped = 0
            for (s in pcm) {
                peak = maxOf(peak, abs(s.toInt()))
                sum += s.toDouble() * s
                if (abs(s.toInt()) >= 32000) clipped++
            }
            val rms = sqrt(sum / n) / 32000
            println("level $level: ${"%.1f".format(seconds)} s, peak ${peak / 32000.0}, rms ${"%.3f".format(rms)}, clipped $clipped")
            assertTrue(rms > 0.03)
            assertTrue(clipped < n / 1000)
            writeWav(File(System.getProperty("shots.dir") ?: "build/shots", "music_level$level.wav"), pcm, sr)
        }
    }

    private fun writeWav(f: File, pcm: ShortArray, sr: Int) {
        f.parentFile?.mkdirs()
        DataOutputStream(FileOutputStream(f)).use { o ->
            fun le32(v: Int) { o.write(v and 0xff); o.write(v shr 8 and 0xff); o.write(v shr 16 and 0xff); o.write(v shr 24 and 0xff) }
            fun le16(v: Int) { o.write(v and 0xff); o.write(v shr 8 and 0xff) }
            o.writeBytes("RIFF"); le32(36 + pcm.size * 2); o.writeBytes("WAVEfmt ")
            le32(16); le16(1); le16(1); le32(sr); le32(sr * 2); le16(2); le16(16)
            o.writeBytes("data"); le32(pcm.size * 2)
            for (s in pcm) le16(s.toInt())
        }
    }
}
