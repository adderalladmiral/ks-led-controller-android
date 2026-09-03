package com.h4ch1net.ksled.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Reads PCM audio from the phone microphone and reduces each chunk to a simple
 * three-band energy split (bass / mid / treble) plus overall volume, suitable for
 * driving RGB output in a music-sync loop. Uses a lightweight in-place FFT rather
 * than pulling in an external DSP dependency.
 */
class MicAnalyzer(
    private val sampleRate: Int = 44100,
    private val chunkSize: Int = 1024 // power of two, ~23ms of audio at 44.1kHz
) {
    data class AudioFrame(val volume: Float, val bass: Float, val mid: Float, val treble: Float)

    private var audioRecord: AudioRecord? = null
    @Volatile private var running = false

    private val minBufSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    /** Must be called after RECORD_AUDIO permission has been granted. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val bufSize = max(minBufSize, chunkSize * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        record.startRecording()
        audioRecord = record
        running = true
        return true
    }

    fun stop() {
        running = false
        audioRecord?.let {
            try {
                it.stop()
            } catch (_: Exception) {
                // Ignore stop-while-not-recording races
            }
            it.release()
        }
        audioRecord = null
    }

    fun isRunning(): Boolean = running

    /** Blocking read of one chunk; call from a background coroutine/thread loop. */
    fun readFrame(): AudioFrame? {
        val record = audioRecord ?: return null
        if (!running) return null

        val buffer = ShortArray(chunkSize)
        val read = record.read(buffer, 0, chunkSize)
        if (read <= 0) return null

        // Overall volume via RMS -> normalized 0..1 with light log compression so
        // quiet rooms still show some movement and loud peaks don't clip too hard.
        var sumSquares = 0.0
        for (i in 0 until read) {
            val s = buffer[i].toDouble()
            sumSquares += s * s
        }
        val rms = sqrt(sumSquares / read)
        val volume = normalizeRms(rms)

        // Simple 3-band split using a real-valued FFT magnitude spectrum.
        val fftInput = DoubleArray(chunkSize)
        for (i in 0 until read) fftInput[i] = buffer[i].toDouble() / 32768.0
        val magnitudes = fftMagnitudes(fftInput)

        val nyquist = sampleRate / 2.0
        val binHz = nyquist / (magnitudes.size)
        val bassEnd = (250 / binHz).toInt().coerceIn(1, magnitudes.size)
        val midEnd = (2000 / binHz).toInt().coerceIn(bassEnd, magnitudes.size)

        val bass = bandEnergy(magnitudes, 0, bassEnd)
        val mid = bandEnergy(magnitudes, bassEnd, midEnd)
        val treble = bandEnergy(magnitudes, midEnd, magnitudes.size)

        return AudioFrame(volume, bass, mid, treble)
    }

    private fun normalizeRms(rms: Double): Float {
        if (rms < 1.0) return 0f
        // ~90dB usable range mapped to 0..1
        val db = 20 * log10(rms / 32768.0)
        val normalized = (db + 60.0) / 60.0
        return normalized.toFloat().coerceIn(0f, 1f)
    }

    private fun bandEnergy(mags: DoubleArray, from: Int, to: Int): Float {
        if (to <= from) return 0f
        var sum = 0.0
        for (i in from until to) sum += mags[i]
        val avg = sum / (to - from)
        // Empirical scaling; FFT magnitudes here are small for 16-bit-normalized input.
        return min(1.0, avg * 12.0).toFloat()
    }

    /**
     * In-place iterative radix-2 Cooley-Tukey FFT. Input length must be a power of two
     * (chunkSize is). Returns magnitude spectrum for bins [0, n/2).
     */
    private fun fftMagnitudes(input: DoubleArray): DoubleArray {
        val n = input.size
        val real = input.copyOf()
        val imag = DoubleArray(n)

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
            var m = n shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = Math.cos(ang)
            val wi = Math.sin(ang)
            var i = 0
            while (i < n) {
                var curWr = 1.0
                var curWi = 0.0
                for (k in 0 until len / 2) {
                    val evenR = real[i + k]
                    val evenI = imag[i + k]
                    val oddR = real[i + k + len / 2] * curWr - imag[i + k + len / 2] * curWi
                    val oddI = real[i + k + len / 2] * curWi + imag[i + k + len / 2] * curWr
                    real[i + k] = evenR + oddR
                    imag[i + k] = evenI + oddI
                    real[i + k + len / 2] = evenR - oddR
                    imag[i + k + len / 2] = evenI - oddI
                    val newWr = curWr * wr - curWi * wi
                    val newWi = curWr * wi + curWi * wr
                    curWr = newWr
                    curWi = newWi
                }
                i += len
            }
            len = len shl 1
        }

        val half = n / 2
        val mags = DoubleArray(half)
        for (i in 0 until half) {
            mags[i] = sqrt(real[i] * real[i] + imag[i] * imag[i]) / n
        }
        return mags
    }
}
