package tribixbite.cleverkeys.voice

/**
 * Phase 1 voice (2026-10-24): recorder state. Single source of truth for
 * the overlay (Slice C) and its tests — the recorder owns transitions.
 */
internal sealed interface VoiceState {
    /** Nothing captured yet, or last capture was discarded. */
    data object Idle : VoiceState

    /** Actively capturing from the mic. */
    data class Recording(val startedAtMs: Long) : VoiceState

    /** Capture finished; transcription running (Slice C/D). */
    data object Transcribing : VoiceState

    /** Usable transcript. The PCM file is deleted once this is emitted. */
    data class Ready(val text: String) : VoiceState

    /** Anything the user must hear about (mic denied mid-record, IO error…). */
    data class Error(val message: String) : VoiceState
}
