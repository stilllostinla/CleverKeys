package tribixbite.cleverkeys.voice

/**
 * Phase 1 voice (2026-10-24, Slice C): offline Tier-1 rewrite.
 *
 * Pure function, no Android dependencies. [Tone.CLEAN] applies rule-based
 * cleanup (fillers, spacing, sentence case). [Tone.RAW] returns the
 * transcript verbatim. FORMAL/FRIENDLY/CONCISE need an LLM and arrive with
 * Phase 2 (cloud opt-in); until then the overlay offers RAW + CLEAN only.
 */
internal object ToneRewriter {

    enum class Tone { RAW, CLEAN }

    /** Parse the `voice_tone` pref; unknown values fall back to [Tone.RAW]. */
    @JvmStatic
    fun parseTone(name: String?): Tone = when (name?.lowercase()) {
        "clean" -> Tone.CLEAN
        else -> Tone.RAW
    }

    @JvmStatic
    fun rewrite(text: String, tone: Tone = Tone.CLEAN): String {
        if (tone == Tone.RAW) return text
        var s = text.trim()
        if (s.isEmpty()) return s
        // Standalone fillers, optionally followed by a comma ("um, well..." -> "well...").
        // Replaced with a space (not empty): a mid-sentence filler owns both
        // surrounding spaces, and deleting it outright would glue words ("a um b" -> "ab").
        s = FILLER.matcher(s).replaceAll(" ")
        // Collapse whitespace left behind.
        s = s.replace(Regex("\\s+"), " ").trim()
        // No space before punctuation: "hello , world" -> "hello, world".
        s = s.replace(Regex("\\s+([,.!?;:])"), "$1")
        // One space after sentence punctuation when followed by a letter.
        s = s.replace(Regex("([.!?])([A-Za-z])"), "$1 $2")
        // Sentence case: uppercase a leading lowercase letter at the start or
        // after sentence punctuation. Words with internal capitals (NASA,
        // iPhone) are left alone.
        val out = StringBuilder(s.length)
        var atSentenceStart = true
        for (ch in s) {
            if (atSentenceStart && ch.isLetter()) {
                out.append(ch.uppercaseChar())
                atSentenceStart = false
            } else {
                out.append(ch)
                if (ch == '.' || ch == '!' || ch == '?') atSentenceStart = true
                else if (!ch.isWhitespace()) atSentenceStart = false
            }
        }
        return out.toString().trim()
    }

    private val FILLER: Regex = Regex(
        "(?i)(?:^|[\\s(])(?:um+|uh+|er+|ah+|hmm+|mhm|mm-hmm)(?:,|\\.|\\s|$)"
    )
}
