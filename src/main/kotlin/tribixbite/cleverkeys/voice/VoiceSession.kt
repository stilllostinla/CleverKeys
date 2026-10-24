package tribixbite.cleverkeys.voice

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Phase 1 voice (2026-10-24, Slice C): record → transcribe → rewrite.
 *
 * UI-free orchestration; the receiver drives it and posts [onState] to the
 * main thread. All work runs on an internal scope — call [shutdown] when the
 * owning component is destroyed. The PCM file is always deleted after
 * transcription (success or failure): raw audio never persists.
 */
internal interface VoiceCapture {
    suspend fun start(onAmplitude: ((Float) -> Unit)? = null)
    suspend fun stop(): File?
    suspend fun cancel()
}

internal class VoiceSession(
    private val captureFactory: (cacheDir: File) -> VoiceCapture,
    private val transcriber: VoiceTranscriber = StubTranscriber(),
    private val tone: () -> ToneRewriter.Tone = { ToneRewriter.Tone.CLEAN },
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var capture: VoiceCapture? = null

    fun startRecording(cacheDir: File, onState: (VoiceState) -> Unit, onLevel: (Float) -> Unit) {
        if (capture != null) return
        val cap = captureFactory(cacheDir)
        capture = cap
        scope.launch {
            val startedAt = System.currentTimeMillis()
            onState(VoiceState.Recording(startedAt))
            cap.start { level -> onLevel(level) }
        }
    }

    fun finishRecording(onState: (VoiceState) -> Unit) {
        val cap = capture ?: return
        capture = null
        scope.launch {
            onState(VoiceState.Transcribing)
            val file = try {
                cap.stop()
            } catch (e: Exception) {
                onState(VoiceState.Error(e.message ?: "recording failed"))
                return@launch
            }
            if (file == null) {
                onState(VoiceState.Error("nothing captured"))
                return@launch
            }
            try {
                val raw = transcriber.transcribe(file)
                onState(VoiceState.Ready(ToneRewriter.rewrite(raw, tone())))
            } catch (e: Exception) {
                onState(VoiceState.Error(e.message ?: "transcription failed"))
            } finally {
                if (file.exists()) file.delete()
            }
        }
    }

    fun cancel() {
        val cap = capture ?: return
        capture = null
        scope.launch {
            try {
                cap.cancel()
            } catch (_: Exception) {
            }
        }
    }

    fun shutdown() {
        cancel()
    }
}
