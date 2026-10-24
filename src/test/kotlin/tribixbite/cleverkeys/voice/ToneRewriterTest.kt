package tribixbite.cleverkeys.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import tribixbite.cleverkeys.voice.ToneRewriter.Tone

/**
 * Phase 1 voice (2026-10-24, Slice C): Tier-1 rewrite is a pure function —
 * 20 fixed cases, no Android needed.
 */
class ToneRewriterTest {

    private fun clean(input: String): String = ToneRewriter.rewrite(input, Tone.CLEAN)

    @Test
    fun emptyStaysEmpty() {
        assertThat(clean("")).isEmpty()
        assertThat(clean("   ")).isEmpty()
        assertThat(clean("um")).isEmpty()
        assertThat(clean("uh," )).isEmpty()
    }

    @Test
    fun rawPassesThroughUntouched() {
        val messy = "  um hello ,  world "
        assertThat(ToneRewriter.rewrite(messy, Tone.RAW)).isEqualTo(messy)
    }

    @Test
    fun fillersRemovedEverywhere() {
        assertThat(clean("um hello")).isEqualTo("Hello")
        assertThat(clean("hello um")).isEqualTo("Hello")
        assertThat(clean("a um b")).isEqualTo("A b")
        assertThat(clean("um, well let's go")).isEqualTo("Well let's go")
        assertThat(clean("yeah uh no")).isEqualTo("Yeah no")
        assertThat(clean("Hmm, let me think...")).isEqualTo("Let me think...")
    }

    @Test
    fun nearFillersLeftAlone() {
        // "museum"/"umami" contain filler letters but are real words.
        assertThat(clean("visit the museum")).isEqualTo("Visit the museum")
        assertThat(clean("don't remove umami")).isEqualTo("Don't remove umami")
        assertThat(clean("i love my iphone")).isEqualTo("I love my iphone")
    }

    @Test
    fun spacingAndPunctuationFixed() {
        assertThat(clean("hello , world")).isEqualTo("Hello, world")
        assertThat(clean("hello  world")).isEqualTo("Hello world")
        assertThat(clean("how are you ?")).isEqualTo("How are you?")
        assertThat(clean("a\t\nb")).isEqualTo("A b")
        assertThat(clean("what?!really")).isEqualTo("What?! Really")
    }

    @Test
    fun sentenceCaseApplied() {
        assertThat(clean("it works . really")).isEqualTo("It works. Really")
        assertThat(clean("stop. go. wait.")).isEqualTo("Stop. Go. Wait.")
        assertThat(clean("i went to nasa")).isEqualTo("I went to nasa")
    }

    @Test
    fun toneParsing() {
        assertThat(ToneRewriter.parseTone("clean")).isEqualTo(Tone.CLEAN)
        assertThat(ToneRewriter.parseTone("CLEAN")).isEqualTo(Tone.CLEAN)
        assertThat(ToneRewriter.parseTone("raw")).isEqualTo(Tone.RAW)
        // Phase 2 tones fall back to RAW until the LLM rewriter lands.
        assertThat(ToneRewriter.parseTone("formal")).isEqualTo(Tone.RAW)
        assertThat(ToneRewriter.parseTone(null)).isEqualTo(Tone.RAW)
        assertThat(ToneRewriter.parseTone("bogus")).isEqualTo(Tone.RAW)
    }
}
