package tribixbite.cleverkeys.voice

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Phase 1 voice (2026-10-24, Slice B): recorder state machine with a fake
 * [VoiceAudioRecorder.PcmSource] and a controllable clock — no mic, no Robolectric.
 */
class VoiceAudioRecorderTest {

    private lateinit var dir: File
    private var nowMs: Long = 0L
    private val dispatcher = StandardTestDispatcher()

    private class FakeSource(
        var framesLeft: Int,
        override val framesPerRead: Int = 256,
        private val sample: Short = 1000,
        private val onRead: (() -> Unit)? = null,
    ) : VoiceAudioRecorder.PcmSource {
        var started = false
        var stopped = false
        var released = false

        override fun start() {
            started = true
        }

        override fun read(buf: ShortArray, offset: Int, length: Int): Int {
            onRead?.invoke()
            if (framesLeft <= 0) return -1
            val n = minOf(length, framesPerRead, framesLeft)
            for (i in 0 until n) buf[offset + i] = sample
            framesLeft -= n
            return n
        }

        override fun stop() {
            stopped = true
        }

        override fun release() {
            released = true
        }
    }

    private fun recorder(
        source: FakeSource,
        maxDurationMs: Long = 60_000L,
    ) = VoiceAudioRecorder(
        cacheDir = dir,
        sourceFactory = { _, _ -> source },
        clockMs = { nowMs },
        io = dispatcher,
    )

    @Before
    fun setup() {
        dir = Files.createTempDirectory("voice_test").toFile()
        nowMs = 0L
    }

    @After
    fun teardown() {
        dir.deleteRecursively()
    }

    @Test
    fun startStopWritesPcmFile() = runTest(dispatcher) {
        val source = FakeSource(framesLeft = 512)
        val rec = recorder(source)
        val levels = mutableListOf<Float>()

        rec.start(onAmplitude = { levels += it })
        advanceUntilIdle()
        val file = rec.stop()

        assertThat(file).isNotNull()
        assertThat(file!!.exists()).isTrue()
        // 512 little-endian 16-bit samples.
        assertThat(file.length()).isEqualTo(1024L)
        val raw = file.readBytes()
        val first = ByteBuffer.wrap(raw, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short
        assertThat(first).isEqualTo(1000.toShort())
        assertThat(levels).isNotEmpty()
        assertThat(levels.max()).isWithin(0.001f).of(1000f / 32768f)
        assertThat(source.stopped).isTrue()
        assertThat(source.released).isTrue()
        assertThat(rec.state).isEqualTo(VoiceState.Idle)
    }

    @Test
    fun cancelDeletesPartialFile() = runTest(dispatcher) {
        val source = FakeSource(framesLeft = 10_000)
        val rec = recorder(source)

        rec.start()
        advanceUntilIdle()
        rec.cancel()

        assertThat(dir.listFiles()).isEmpty()
        assertThat(source.released).isTrue()
        assertThat(rec.state).isEqualTo(VoiceState.Idle)
    }

    @Test
    fun doubleStartIsNoOp() = runTest(dispatcher) {
        val source = FakeSource(framesLeft = 10_000)
        val rec = recorder(source)

        rec.start()
        rec.start()
        advanceUntilIdle()
        val file = rec.stop()

        assertThat(file).isNotNull()
        assertThat(dir.listFiles()!!.size).isEqualTo(1)
    }

    @Test
    fun autoStopsAtMaxDuration() = runTest(dispatcher) {
        // Each read advances the fake clock 100 ms; cap is 250 ms.
        val source = FakeSource(
            framesLeft = 100_000,
            onRead = { nowMs += 100L },
        )
        val rec = recorder(source, maxDurationMs = 250L)

        rec.start()
        advanceUntilIdle()
        val file = rec.stop()

        assertThat(file).isNotNull()
        assertThat(source.released).isTrue()
    }

    @Test
    fun stopWithoutStartReturnsNull() = runTest(dispatcher) {
        val rec = recorder(FakeSource(framesLeft = 0))
        assertThat(rec.stop()).isNull()
        assertThat(rec.state).isEqualTo(VoiceState.Idle)
    }

    @Test
    fun emptyCaptureReturnsNullAndCleansUp() = runTest(dispatcher) {
        val rec = recorder(FakeSource(framesLeft = 0))
        rec.start()
        advanceUntilIdle()
        assertThat(rec.stop()).isNull()
        assertThat(dir.listFiles()).isEmpty()
    }
}
