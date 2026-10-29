package net.serble.rotationbeats

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import net.serble.rotationbeats.core.Song
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max

/**
 * Procedural music + sound effects, synthesised live. The audio output is also the game's clock:
 * [songSeconds] reports how far into the current song the listener actually is, so jumps line up
 * with what you hear even on laggy (e.g. Bluetooth) outputs.
 */
class AudioEngine {
    private val sr = 44100
    private val block = 256

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile var volume = 1f

    private val sfxQueue = ConcurrentLinkedQueue<Int>()

    // Song control (game thread -> audio thread)
    @Volatile private var requested: Song? = null
    @Volatile private var requestSerial = 0
    @Volatile private var stopRequested = 0

    // Published by the audio thread
    @Volatile private var activeSerial = -1
    @Volatile private var songStartFrame = 0L

    // Fallback clock if the device refuses to give us an AudioTrack.
    private var fallbackStartNanos = 0L
    private var pausedAtNanos = 0L
    private var resumedAtNanos = 0L

    private val ts = AudioTimestamp()
    private var tsFrame = 0L
    private var tsNanos = 0L
    private var lastTsPoll = 0L

    fun start() {
        if (running) return
        try {
            val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(min, block * 2 * 4))
            if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            val t = builder.build()
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                t.release()
                return
            }
            track = t
            t.play()
        } catch (e: Exception) {
            track = null
            return
        }
        running = true
        thread = Thread({ loop() }, "synth").also { it.start() }
    }

    fun release() {
        running = false
        thread?.join(500)
        thread = null
        track?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        track = null
    }

    fun pause() {
        if (paused) return
        paused = true
        pausedAtNanos = System.nanoTime()
        try { track?.pause() } catch (_: Exception) {}
    }

    fun resume() {
        if (!paused) return
        val now = System.nanoTime()
        fallbackStartNanos += now - pausedAtNanos
        resumedAtNanos = now
        try { track?.play() } catch (_: Exception) {}
        paused = false
        tsNanos = 0
    }

    fun startSong(song: Song) {
        requested = song
        requestSerial++
        fallbackStartNanos = System.nanoTime() + (LEAD * 1e9).toLong()
        tsNanos = 0
    }

    fun stopSong() {
        stopRequested++
    }

    fun sfx(id: Int) {
        sfxQueue.add(id)
    }

    /** Seconds since beat 0 of the current song as heard right now, or null while it is starting. */
    fun songSeconds(): Double? {
        val t = track ?: return (System.nanoTime() - fallbackStartNanos) / 1e9
        if (activeSerial != requestSerial) return null
        val now = System.nanoTime()
        if (tsNanos == 0L || now - lastTsPoll > 200_000_000L) {
            lastTsPoll = now
            try {
                // Timestamps taken before a pause would extrapolate across it, so ignore those.
                if (t.getTimestamp(ts) && ts.nanoTime > resumedAtNanos) {
                    tsFrame = ts.framePosition
                    tsNanos = ts.nanoTime
                } else {
                    tsFrame = t.playbackHeadPosition.toLong() and 0xffffffffL
                    tsNanos = now
                }
            } catch (_: Exception) {
                tsFrame = t.playbackHeadPosition.toLong() and 0xffffffffL
                tsNanos = now
            }
        }
        val heard = tsFrame + (now - tsNanos) * sr / 1e9
        return (heard - songStartFrame) / sr
    }

    // ---------------------------------------------------------------- synth thread

    private val synth = Synth(sr)
    private var seenStop = 0

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(block)
        val t = track ?: return
        while (running) {
            if (paused) {
                Thread.sleep(10)
                continue
            }
            val req = requested
            val serial = requestSerial
            if (req != null && serial != activeSerial) {
                songStartFrame = synth.startSong(req, LEAD)
                seenStop = stopRequested
                activeSerial = serial
            }
            if (stopRequested != seenStop) {
                seenStop = stopRequested
                synth.stopSong()
            }
            while (true) {
                val id = sfxQueue.poll() ?: break
                synth.sfx(id)
            }
            synth.render(buf, block, volume)
            t.write(buf, 0, block)
        }
    }

    companion object {
        const val SFX_JUMP = 1
        const val SFX_DIE = 2
        const val SFX_LEVEL = 3
        const val LEAD = 0.35
    }
}
