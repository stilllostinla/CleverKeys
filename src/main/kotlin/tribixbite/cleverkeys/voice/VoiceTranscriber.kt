package tribixbite.cleverkeys.voice

import java.io.File

/**
 * Phase 1 voice (2026-10-24, Slice C): speech-to-text seam.
 *
 * Contract: [transcribe] reads 16 kHz mono 16-bit PCM and returns plain
 * text. The caller owns the file — delete it after commit/cancel (the
 * overlay does this; see Slice C.4). Implementations must be safe to call
 * off the main thread and must throw on failure (no silent empty strings —
 * the overlay turns exceptions into [VoiceState.Error]).
 *
 * Ship order: [StubTranscriber] first (proves overlay→commit without a
 * model), then `OnnxWhisperTranscriber` (Slice D).
 */
internal interface VoiceTranscriber {
    suspend fun transcribe(pcmFile: File, sampleRateHz: Int = 16000): String
}

/**
 * Placeholder used until Slice D lands. Returns a fixed marker containing
 * the clip length so the overlay→insert path is exercisable end-to-end.
 */
internal class StubTranscriber : VoiceTranscriber {
    override suspend fun transcribe(pcmFile: File, sampleRateHz: Int): String {
        val seconds = (pcmFile.length() / (sampleRateHz * 2)).coerceAtLeast(1)
        return "stub transcript, ${seconds}s clip"
    }
}
