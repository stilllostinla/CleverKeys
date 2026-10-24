# Voice Button — Phase 1 (offline-only): recorder + overlay + stub STT

Date: 2026-10-24 | Project: whisper-flow-2-clever-keyboard | Code: `~/coding-projects/CleverKeys`
Scope: **Phase 1 only** — mic key → AudioRecord capture → overlay → stub/offline transcription → rule-based tone cleanup → `commitText`. No `INTERNET`, no cloud STT, no LLM. Phase 2 (cloud-opt-in, Voice Cursor parity) is explicitly out of scope and gated separately.

## 0. Where we start (verified 2026-10-24)

- `VoiceImeSwitcher.kt` (130 lines) only enumerates voice-subtype IMEs and hops away — zero audio/STT in tree (`AudioRecord|MediaRecorder|SpeechRecognizer|Whisper` all absent from `src/main`).
- Funnel today: layout token `voice_typing` → `KeyValue.Event.SWITCH_VOICE_TYPING` (`KeyValue.kt:38,635`) → `KeyboardReceiver.kt:606-616` → `CleverKeysService.triggerKeyboardEvent` (`:1131`) → swipe `AvailableCommand.VOICE_INPUT` (`Keyboard2View.kt:~1195`).
- Commit path to reuse: `KeyEventHandler.kt:526` `conn.commitText(...)`; user feedback: `CleverKeysService.showSuggestionBarMessage` (`:1204`).
- Visibility gate: `LayoutModifier.kt:254-256` × `Config.shouldOfferVoiceTyping` (forced `true` in `SubtypeManager.kt:121`).
- Example wiring: `src/main/layouts/latn_qwerty_us_swiftkey_style.xml:81` — `<key c="," n="loc voice_typing"/>`.
- Manifest: `RECORD_AUDIO` commented out (`AndroidManifest.xml:11`), `INTERNET` absent by policy (`:7`).
- Reusable infra: `onnx/ModelLoader.kt` + OrtSession cache, `swipe/CtcEngineAdapter.kt`, `kotlinx-coroutines-android:1.8.1` + `onnxruntime-android:1.21.1` already in `build.gradle`; consent pattern in `PrivacyManager.kt` (`KEY_CONSENT_GIVEN`, consent-first §30); tests under `src/test/kotlin/tribixbite/cleverkeys/`.
- Build: `./build-on-termux.sh debug --no-install` (wraps `scripts/gradle-guard.sh --no-daemon --max-workers=1`).

## 1. Decision split (locks Phase 1 vs Phase 2)

- Phase 1 ships with `voice_enabled=false` default, `voice_mode=builtin|external` (default `external` = today's hop-away behavior, zero regression risk), `voice_tone` presets off.
- Phase 1 adds **only** `RECORD_AUDIO`. Any `INTERNET`/`FOREGROUND_SERVICE_MICROPHONE`/cloud code is a Phase 2 diff with its own consent screen and F-Droid flavor review.
- STT lands in two steps inside Phase 1: (a) `StubTranscriber` (returns placeholder, proves overlay→commit path shippable without a model), then (b) `OnnxWhisperTranscriber` reusing the ONNX cache. Model (~75 MB whisper-tiny.en quantized) is **side-loaded via the langpack SAF pattern** (`langpack/LanguagePackManager.kt`), never bundled in the APK.

## 2. Implementation order (smallest shippable slices)

### Slice A — permission gate + event plumbing (no audio yet)
1. `AndroidManifest.xml`: uncomment `RECORD_AUDIO` (leave `INTERNET` absent).
2. `KeyValue.kt`: add `Event.VOICE_TRANSCRIBE`; map `"voice_transcribe"` token next to `:635` (`eventKey(0xE015, ...)` — pick adjacent private-use codepoint, document it).
3. `KeyboardReceiver.kt` (~`:606`): handle `VOICE_TRANSCRIBE` → if `!Config.voice_enabled` or `voice_mode==external`, fall through to existing `SWITCH_VOICE_TYPING` path (fallback preserved); else open overlay.
4. New `voice/VoicePermissionGate.kt`: `hasPermission()`, `requestFrom(activity)` via `ActivityResultContracts.RequestPermission` — called from `activities/SettingsActivity.kt` + `LauncherActivity.kt` (IME service cannot show the runtime dialog itself). Denied → `showSuggestionBarMessage(voice_mic_denied)` + fall back to external IME.
5. `Config.kt` + `ConfigurationManager.kt` + `ConfigPropagator.kt`: `voice_enabled` (default false), `voice_mode` (`external` default), `voice_tone` (`raw` default). Wire `shouldOfferVoiceTyping` so the mic key stays visible in both modes (gate is at tap-time, not layout-time — avoids layout churn).

### Slice B — recorder (no UI yet, testable headless)
6. New `voice/VoiceAudioRecorder.kt`: `AudioRecord`, 16 kHz mono 16-bit PCM, `VOICE_RECOGNITION` source; 60 s cap with auto-stop; temp file in `cacheDir`; `start()/stop()/cancel()` as suspend functions on `Dispatchers.IO`; `stop` on `onFinishInputView()`/`onDestroy()` (mic must never leak across focus/lockscreen — release in `CleverKeysService` lifecycle callbacks).
7. New `voice/VoiceModels.kt`: sealed `Idle|Recording|Transcribing|Ready|Error` — single source of truth for overlay + tests.
8. Tests: `src/test/.../voice/VoiceAudioRecorderTest.kt` (state machine with fake clock: start→stop→file exists; cancel→file deleted; timeout→auto-stop; double-start→no-op) using `kotlinx-coroutines-test:1.7.3`.

### Slice C — overlay + commit (stub STT, end-to-end value)
9. New `voice/VoiceTranscriber.kt` interface (`suspend fun transcribe(pcmFile): String`) + `voice/StubTranscriber.kt` (returns canned string; proves path).
10. New `voice/ToneRewriter.kt` (Tier 1 offline only): filler-word strip ("um/uh"), double-space collapse, sentence casing via existing `Autocapitalisation` + `LanguageDetector`, spacing via `SmartAutoSpace`. Pure function — `ToneRewriterTest.kt` with 20 fixed cases.
11. New `voice/VoiceOverlayView.kt` + `res/layout/voice_overlay.xml`: waveform (amplitude from recorder callback), cancel/done buttons, live transcript line, tone chips (Raw/Formal/Friendly — chips only switch `ToneRewriter` presets in Phase 1), insert button. Sibling to `_contentPaneContainer`/`_suggestionBar`, themed via `theme/KeyboardTheme.kt`; TalkBack labels in `a11y/KeyLabels.kt`.
12. Insert path: overlay done → `InputCoordinator` → `currentInputConnection.commitText(text, 1)` (same call as `KeyEventHandler.kt:526`) → push pre-insert snapshot to `SuggestionBar.kt` undo + optional `ClipboardManager.kt` entry. Confirm via `showSuggestionBarMessage`.
13. Manual QA Slice C: airplane mode (must work — no network), mic denied (falls back, no crash), rotation/foldable (`KeyboardDimensionsHelper`), Chrome/Gmail long-press fields, DirectBoot (prefs via `DirectBootAwarePreferences`).

### Slice D — real on-device STT (only after A–C green)
14. New `voice/OnnxWhisperTranscriber.kt` implementing `VoiceTranscriber`: reuse `onnx/ModelLoader.kt` OrtSession cache (same pattern as `swipe/OnnxCtcEmissionModel.kt` + `CtcEngineAdapter.kt`); model file resolved via langpack side-load, SHA-pinned; load lazily on first transcribe, never on keyboard start (cold-start budget).
15. Wire in `ConfigurationManager`: `voice_mode==builtin && modelPresent` → ONNX path; model absent → overlay shows "download voice model" deep-link to langpack screen (same UX as dictionary packs).
16. Perf budget: transcribe ≤ 2× realtime on-device for ≤ 30 s clips; memory: release OrtSession on `onDestroy`; never hold PCM + session simultaneously longer than needed; auto-delete PCM after commit/cancel.

## 3. Settings + privacy surface (Phase 1)

- `ui/settings/sections/InputBehaviorSection.kt`: `voice_enabled` toggle (default off) + `voice_mode` radio (External app / Built-in offline) + `voice_tone` chips.
- `PrivacySection.kt` + `PrivacyManager.kt`: `voice_consent` (versioned, timestamped like `KEY_CONSENT_GIVEN`), `voice_retention` (default: delete PCM immediately); consent dialog on first mic tap (opt-in, not opt-out).
- Exclusions: raw PCM + transcripts excluded from `PlaygroundTraceRecorder.kt` / `MLDataCollector.kt` / clipboard export; `res/xml/backup_rules.xml`: exclude keys + audio globs.
- Docs: `SECURITY.md:304`, `README.md`, `metadata/` F-Droid — note mic permission, offline-only processing, side-loaded model.

## 4. Files to touch vs create

Modify: `AndroidManifest.xml`, `KeyValue.kt`, `KeyboardReceiver.kt`, `CleverKeysService.kt` (lifecycle release + `triggerKeyboardEvent` reuse), `InputCoordinator.kt`, `Config.kt`, `ConfigurationManager.kt`, `ConfigPropagator.kt`, `LayoutModifier.kt` (keep gate, no behavior change), `SubtypeManager.kt` (keep forced-true while both modes exist), `activities/SettingsActivity.kt`, `LauncherActivity.kt`, `ui/settings/sections/InputBehaviorSection.kt`, `PrivacySection.kt`, `SettingsSearch.kt`, `PrivacyManager.kt`, `a11y/KeyLabels.kt`, `SuggestionBar.kt`, `ClipboardManager.kt`, `SECURITY.md`, `README.md`, `res/xml/backup_rules.xml`.
Create: `voice/{VoiceAudioRecorder,VoicePermissionGate,VoiceTranscriber,StubTranscriber,OnnxWhisperTranscriber,ToneRewriter,VoiceOverlayView,VoiceModels}.kt`, `res/layout/voice_overlay.xml`, `src/test/.../voice/{VoiceAudioRecorderTest,ToneRewriterTest}.kt`, this plan.
Do NOT touch in Phase 1: `build.gradle` deps (reuse ONNX/coroutines), any `INTERNET` surface, `VoiceImeSwitcher.kt` (kept as fallback path — extend, don't replace).

## 5. Risks + mitigations

- IME permission UX: runtime mic dialog from an IME context is impossible — always route through Settings/Launcher activities; dead-mic state falls back to external IME with a suggestion-bar hint, never a silent no-op.
- Mic leak across focus/lockscreen: timeout + `onFinishInputView`/`onDestroy` release + single-owner recorder (second `start()` while recording is a no-op, logged).
- APK size / Termux RAM: no model in APK; lazy OrtSession; respect `gradle-guard.sh --no-daemon --max-workers=1`; `./build-on-termux.sh debug --no-install` stays green.
- Biometric leakage: PCM deleted on commit/cancel/timeout; transcripts excluded from collectors/exports/backups.
- Regression: `voice_mode=external` default means today's behavior is byte-identical until the user opts in.

## 6. Done criteria (Phase 1)

- [ ] Mic key with `voice_mode=external` behaves exactly as today (chooser/hop).
- [ ] Opt-in → tap mic → overlay records (waveform moves) → stop → transcript inserts at cursor via `commitText`, undo available, PCM deleted.
- [ ] Airplane mode works end-to-end; mic-denied falls back gracefully; no `INTERNET` in manifest diff.
- [ ] `VoiceAudioRecorderTest` + `ToneRewriterTest` green; `./build-on-termux.sh debug --no-install` green.
- [ ] Privacy: consent recorded, exclusions verified, docs updated.

Next (Phase 2, separate plan): `CloudSttProvider.kt` + `OpenRouterRewriter.kt` (presets, timeout, no-logging, key in `DirectBootAwarePreferences`), `INTERNET` + `FOREGROUND_SERVICE_MICROPHONE` behind flavor/opt-in, `voice_cloud_enabled=false` default — Voice Cursor parity for users who explicitly choose it.
