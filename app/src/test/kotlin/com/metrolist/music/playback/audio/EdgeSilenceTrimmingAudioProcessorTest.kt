package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
@Suppress("DEPRECATION")
class EdgeSilenceTrimmingAudioProcessorTest {
    /** Verifies silent frames before first audible frame are removed. */
    @Test
    fun trimsOnlyStartSilence() {
        val processor = configuredProcessor()

        processor.queueInput(pcm(0, 0, 1_000, -1_000))

        assertArrayEquals(shortArrayOf(1_000, -1_000), output(processor))
    }

    /** Verifies silence pending at end of stream is removed. */
    @Test
    fun trimsOnlyEndSilence() {
        val processor = configuredProcessor()

        processor.queueInput(pcm(1_000, -1_000, 0, 0))
        processor.queueEndOfStream()

        assertArrayEquals(shortArrayOf(1_000, -1_000), output(processor))
    }

    /** Verifies seek flushes do not reclassify internal silence as leading silence. */
    @Test
    fun doesNotTrimInternalSilenceAfterSeekFlush() {
        val processor = configuredProcessor()

        processor.queueInput(pcm(1_000))
        val start = output(processor)
        processor.flush()
        processor.queueInput(pcm(0, 0, -1_000))
        val afterSeek = output(processor)

        assertArrayEquals(shortArrayOf(1_000, 0, 0, -1_000), start + afterSeek)
    }

    /** Verifies media-item transitions restart leading-boundary detection. */
    @Test
    fun resetsLeadingTrimOnlyForNewTrack() {
        val processor = configuredProcessor()

        processor.queueInput(pcm(1_000))
        output(processor)
        processor.resetForNewTrack()
        processor.queueInput(pcm(0, 0, -1_000))

        assertArrayEquals(shortArrayOf(-1_000), output(processor))
    }

    /** Verifies long trailing silence remains removable with compressed buffering. */
    @Test
    fun trimsLongRepeatedTrailingSilence() {
        val processor = configuredProcessor()

        processor.queueInput(pcm(1_000))
        val audio = output(processor)
        processor.queueInput(pcm(*ShortArray(100_000)))
        processor.queueEndOfStream()

        assertArrayEquals(shortArrayOf(1_000), audio + output(processor))
    }

    /** Verifies silence followed by later audio is restored unchanged. */
    @Test
    fun doesNotTrimInternalSilence() {
        val processor = configuredProcessor()

        processor.queueInput(pcm(1_000))
        val start = output(processor)
        processor.queueInput(pcm(0, 0))
        val silence = output(processor)
        processor.queueInput(pcm(-1_000))
        val end = output(processor)

        assertArrayEquals(shortArrayOf(1_000, 0, 0, -1_000), start + silence + end)
    }

    /** Creates enabled mono PCM processor for isolated tests. */
    private fun configuredProcessor() = EdgeSilenceTrimmingAudioProcessor().apply {
        enabled = true
        configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        flush()
    }

    /** Encodes signed samples as little-endian 16-bit PCM. */
    private fun pcm(vararg samples: Short): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                samples.forEach(::putShort)
                flip()
            }

    /** Decodes processor output into signed samples for assertions. */
    private fun output(processor: EdgeSilenceTrimmingAudioProcessor): ShortArray {
        val buffer = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(buffer.remaining() / 2) { buffer.short }
    }
}
