package tribixbite.cleverkeys.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Phase 1 voice (2026-10-24, Slice B): mic capture to a temp PCM file.
 *
 * 16 kHz mono 16-bit little-endian PCM ([MediaRecorder.AudioSource.VOICE_RECOGNITION]),
 * auto-stops at [maxDurationMs]. The file lives in [cacheDir] and the caller
 * must delete it after transcription (Slice C does this on commit/cancel).
 *
 * [PcmSource] is injectable so the state machine is unit-testable on the JVM
 * without Robolectric or a mic. Production uses [AudioRecordSource].
 */
internal class VoiceAudioRecorder(
    private val cacheDir: File,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
    private val sourceFactory: (sampleRate: Int, bufferFrames: Int) -> PcmSource = ::AudioRecordSource,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : VoiceCapture {
    /** Minimal seam over [AudioRecord] for JVM tests. */
    internal interface PcmSource {
        /** Buffer size in frames the source wants per read. */
        val framesPerRead: Int
        fun start()
        /** Returns frames actually read, or a negative error code. */
        fun read(buf: ShortArray, offset: Int, length: Int): Int
        fun stop()
        fun release()
    }

    private val lock = Mutex()
    private val scope = CoroutineScope(io + SupervisorJob())
    private var job: Job? = null
    private var outFile: File? = null
    private var startedAtMs: Long = 0L

    val state: VoiceState
        get() = if (job == null) VoiceState.Idle else VoiceState.Recording(startedAtMs)

    /**
     * Start capturing. No-op when already recording. [onAmplitude] receives
     * 0..1 levels from the recording thread; the overlay posts them to main.
     */
    override suspend fun start(onAmplitude: ((Float) -> Unit)? = null) {
        var source: PcmSource? = null
        var file: File? = null
        lock.withLock {
            if (job != null) return
            file = File.createTempFile("voice_", ".pcm", cacheDir)
            source = sourceFactory(sampleRate, MIN_BUFFER_FRAMES)
            outFile = file
            startedAtMs = clockMs()
        }
        val s = source!!
        val f = file!!
        s.start()
        job = launchCapture(s, f, onAmplitude)
    }

    /** Stop capturing and return the PCM file, or null when not recording. */
    override suspend fun stop(): File? = withContext(io) {
        val j: Job?
        val f: File?
        lock.withLock {
            j = job
            f = outFile
            job = null
            outFile = null
        }
        j?.join()
        if (f != null && f.exists()) {
            if (f.length() > 0) f else {
                f.delete()
                null
            }
        } else {
            null
        }
    }

    /** Stop capturing and delete the partial file. */
    override suspend fun cancel() {
        val f = stop()
        withContext(io) {
            if (f != null && f.exists()) f.delete()
        }
    }

    private fun launchCapture(
        source: PcmSource,
        file: File,
        onAmplitude: ((Float) -> Unit)?,
    ): Job {
        // The Job itself is the recording flag, joined by stop()/cancel().
        // It never escapes this class.
        return scope.launch {
            val buf = ShortArray(source.framesPerRead)
            val bytes = ByteBuffer.allocate(buf.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            try {
                FileOutputStream(file).use { out ->
                    while (isActive) {
                        if (clockMs() - startedAtMs >= maxDurationMs) break
                        val n = source.read(buf, 0, buf.size)
                        if (n <= 0) break
                        var peak = 0
                        for (i in 0 until n) {
                            val v = buf[i].toInt()
                            val a = if (v < 0) -v else v
                            if (a > peak) peak = a
                            bytes.putShort(i * 2, buf[i])
                        }
                        out.write(bytes.array(), 0, n * 2)
                        bytes.clear()
                        onAmplitude?.invoke(peak / 32768f)
                    }
                }
            } finally {
                try {
                    source.stop()
                } catch (_: Exception) {
                }
                source.release()
            }
        }
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 16000
        const val DEFAULT_MAX_DURATION_MS = 60_000L
        const val MIN_BUFFER_FRAMES = 2048
    }
}

/** Production [VoiceAudioRecorder.PcmSource] backed by [AudioRecord]. */
internal fun AudioRecordSource(sampleRate: Int, bufferFrames: Int): VoiceAudioRecorder.PcmSource {
    val minFrames = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ) / 2
    val frames = maxOf(bufferFrames, if (minFrames > 0) minFrames else bufferFrames)
    val record = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        frames * 2
    )
    return object : VoiceAudioRecorder.PcmSource {
        override val framesPerRead: Int = frames
        override fun start() = record.startRecording()
        override fun read(buf: ShortArray, offset: Int, length: Int): Int =
            record.read(buf, offset, length)
        override fun stop() {
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
        }
        override fun release() = record.release()
    }
}
