/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.abs

/**
 * Removes near-silent PCM from track boundaries while preserving silence between audible frames.
 *
 * Pending silence is compressed until later audio proves it is internal. Silence still pending at
 * end of stream is discarded as trailing silence.
 */
@UnstableApi
@Suppress("DEPRECATION")
class EdgeSilenceTrimmingAudioProcessor(
    private val silenceThreshold: Int = 256,
) : AudioProcessor {
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false
    private var heardAudio = false
    private val pendingSilence = PendingSilence()

    @Volatile
    var enabled = false

    /** Accepts 16-bit PCM and records channel layout used for frame-level silence detection. */
    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    /** Keeps processor in audio chain so [enabled] can change without rebuilding player. */
    override fun isActive(): Boolean = true

    /** Processes input PCM, withholding silence until it is classified as internal or trailing. */
    @Synchronized
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            outputBuffer = EMPTY_BUFFER
            return
        }
        if (!enabled || channelCount <= 0) {
            if (heardAudio || !pendingSilence.isEmpty) resetTrimmingState()
            copyInput(inputBuffer)
            return
        }

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val frameSize = channelCount * 2
        val frameCount = inputBuffer.remaining() / frameSize
        val basePosition = inputBuffer.position()
        val output = ByteArrayOutputStream(inputBuffer.remaining())

        var silentRunStart = -1
        repeat(frameCount) { frameIndex ->
            val framePosition = basePosition + frameIndex * frameSize
            var framePeak = 0
            repeat(channelCount) { channelIndex ->
                val sample = abs(inputBuffer.getShort(framePosition + channelIndex * 2).toInt())
                if (sample > framePeak) framePeak = sample
            }

            if (framePeak < silenceThreshold) {
                if (heardAudio && silentRunStart == -1) silentRunStart = framePosition
            } else {
                if (silentRunStart != -1) {
                    pendingSilence.append(inputBuffer, silentRunStart, framePosition - silentRunStart)
                    silentRunStart = -1
                }
                if (!pendingSilence.isEmpty) {
                    pendingSilence.writeTo(output)
                    pendingSilence.clear()
                }
                heardAudio = true
                writeFrame(output, inputBuffer, framePosition, frameSize)
            }
        }

        val endPosition = basePosition + frameCount * frameSize
        if (silentRunStart != -1) {
            pendingSilence.append(inputBuffer, silentRunStart, endPosition - silentRunStart)
        }
        inputBuffer.position(endPosition)
        setOutput(output.toByteArray())
    }

    /** Copies one interleaved PCM frame without changing input buffer position. */
    private fun writeFrame(
        output: ByteArrayOutputStream,
        input: ByteBuffer,
        position: Int,
        frameSize: Int,
    ) {
        repeat(frameSize) { offset -> output.write(input.get(position + offset).toInt()) }
    }

    /** Passes input through unchanged when edge trimming is disabled. */
    private fun copyInput(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining())
        output.put(inputBuffer)
        output.flip()
    }

    /** Publishes processed bytes through AudioProcessor output contract. */
    private fun setOutput(bytes: ByteArray) {
        if (bytes.isEmpty()) {
            outputBuffer = EMPTY_BUFFER
            return
        }
        val output = replaceOutputBuffer(bytes.size)
        output.put(bytes)
        output.flip()
    }

    /** Discards silence still pending at end of stream, identifying it as trailing silence. */
    @Synchronized
    override fun queueEndOfStream() {
        inputEnded = true
        pendingSilence.clear()
    }

    /** Returns current output and transfers ownership until next processor call. */
    override fun getOutput(): ByteBuffer {
        val output = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return output
    }

    /** Reports completion after end of input and output drain. */
    override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY_BUFFER

    /** Clears buffered output after seek while preserving whether track audio has started. */
    @Deprecated("Deprecated in AudioProcessor")
    @Synchronized
    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
        pendingSilence.clear()
    }

    /** Starts boundary detection for a newly selected media item. */
    @Synchronized
    fun resetForNewTrack() {
        resetTrimmingState()
    }

    /** Releases stream state and configured audio format. */
    @Deprecated("Deprecated in AudioProcessor")
    @Synchronized
    override fun reset() {
        flush()
        channelCount = 0
        encoding = C.ENCODING_INVALID
    }

    /** Clears track-specific detection state without changing audio format. */
    private fun resetTrimmingState() {
        heardAudio = false
        if (!pendingSilence.isEmpty) pendingSilence.clear()
    }

    /** Reuses direct output storage when capacity permits. */
    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        return outputBuffer
    }

    private class PendingSilence {
        private var compressed: ByteArrayOutputStream? = null
        private var deflater: Deflater? = null
        private var compressor: DeflaterOutputStream? = null
        private val buffer = ByteArray(8 * 1024)

        var isEmpty = true
            private set

        /** Compresses a contiguous silent PCM range for possible later restoration. */
        fun append(input: ByteBuffer, position: Int, length: Int) {
            if (length == 0) return
            if (isEmpty) initializeCompressor()
            isEmpty = false
            val output = checkNotNull(compressor)
            var offset = 0
            while (offset < length) {
                val chunkSize = minOf(buffer.size, length - offset)
                repeat(chunkSize) { index -> buffer[index] = input.get(position + offset + index) }
                output.write(buffer, 0, chunkSize)
                offset += chunkSize
            }
        }

        /** Restores pending PCM exactly when later audio proves silence is internal. */
        fun writeTo(output: ByteArrayOutputStream) {
            checkNotNull(compressor).finish()
            InflaterInputStream(ByteArrayInputStream(checkNotNull(compressed).toByteArray())).use {
                it.copyTo(output)
            }
        }

        /** Disposes compressed data and native compressor resources. */
        fun clear() {
            deflater?.end()
            isEmpty = true
            compressed = null
            deflater = null
            compressor = null
        }

        /** Lazily creates compression resources only when silence must be retained. */
        private fun initializeCompressor() {
            compressed = ByteArrayOutputStream()
            deflater = Deflater(Deflater.BEST_SPEED)
            compressor = DeflaterOutputStream(compressed, deflater)
        }
    }

    private companion object {
        val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
