package com.lumovault.lumovault.features.gallery.data.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the CLIP tokenizer's pure logic.
 *
 * Loading the vocab asset needs the Android asset manager, so [ClipTokenizer]
 * construction is tested through [ClipTokenizer.fromLines] with a small
 * synthetic vocab — exactly what the original Dart tests did. The properties
 * that matter:
 *  - output is always exactly contextLength,
 *  - SOT/EOT bracket the ids at the canonical 49406/49407,
 *  - non-latin text must byte-encode rather than vanish.
 */
class ClipTokenizerTest {

    private fun syntheticTokenizer(contextLength: Int = 77): ClipTokenizer {
        // A line count above the merge slice so the clamping path is exercised,
        // with a few real merge pairs the BPE loop can act on.
        val lines = buildList {
            add("#version: 0.2") // header, skipped like the real file's first line
            // Real OpenCLIP merges so BPE has something to join.
            add("a b")
            add("ab c")
            add("c d")
            // Pad past the merge slice (48894 entries) with no-op lines.
            repeat(50_000) { add("x y") }
        }
        return ClipTokenizer.fromLines(lines, contextLength = contextLength)
    }

    @Test
    fun `tokenize pads to exactly the context length`() {
        val tokenizer = syntheticTokenizer(contextLength = 32)

        val tokens = tokenizer.tokenize("a")

        assertEquals(32, tokens.size)
    }

    @Test
    fun `tokenize brackets the ids with the canonical SOT and EOT`() {
        val tokenizer = syntheticTokenizer(contextLength = 16)

        val tokens = tokenizer.tokenize("hello")

        // SOT is always first; EOT always follows the last real id, with zeros
        // after it. These ids are fixed by the model's embedding table — the
        // earlier 49415-based slice collided with them.
        assertEquals(49406L, tokens[0])
        assertEquals(49407L, tokens[tokens.indexOfFirst { it == 49407L }])
        // Everything after EOT is zero padding.
        val eotIndex = tokens.indexOfFirst { it == 49407L }
        for (i in (eotIndex + 1) until tokens.size) {
            assertEquals(0L, tokens[i])
        }
    }

    @Test
    fun `EOT lands right after the last token for a single-word query`() {
        val tokenizer = syntheticTokenizer(contextLength = 12)

        val tokens = tokenizer.tokenize("ab")

        // "ab" byte-encodes to a, b which BPE may merge; either way EOT sits at
        // index 2 (one word after SOT) unless a merge changed the count.
        val eotIndex = tokens.indexOfFirst { it == 49407L }
        assertTrue("EOT present", eotIndex > 0)
        // Nothing between SOT and EOT is zero padding.
        for (i in 1 until eotIndex) {
            assertTrue("unexpected padding before EOT at $i", tokens[i] != 0L)
        }
    }

    @Test
    fun `tokenize truncates queries longer than the context`() {
        val tokenizer = syntheticTokenizer(contextLength = 8)

        val tokens = tokenizer.tokenize("a b c d e f g h i j k l m n o p")

        assertEquals(8, tokens.size)
        assertEquals(49406L, tokens[0])
        // SOT + at most 5 ids + EOT = 7 slots, so EOT never overflows.
        val eotIndex = tokens.indexOfFirst { it == 49407L }
        assertTrue(eotIndex <= 7)
    }

    @Test
    fun `tokenize lowercases the input`() {
        val tokenizer = syntheticTokenizer(contextLength = 16)

        val lower = tokenizer.tokenize("ab")
        val upper = tokenizer.tokenize("AB")

        // Casing must not change the ids.
        assertEquals(lower.toList(), upper.toList())
    }

    @Test
    fun `tokenize collapses internal whitespace`() {
        val tokenizer = syntheticTokenizer(contextLength = 16)

        val one = tokenizer.tokenize("a  b")
        val two = tokenizer.tokenize("a b")

        assertEquals(one.toList(), two.toList())
    }

    @Test
    fun `an empty query still yields a well-formed token vector`() {
        val tokenizer = syntheticTokenizer(contextLength = 10)

        val tokens = tokenizer.tokenize("")

        assertEquals(10, tokens.size)
        assertEquals(49406L, tokens[0])
        assertEquals(49407L, tokens[1])
        for (i in 2 until tokens.size) {
            assertEquals(0L, tokens[i])
        }
    }

    @Test
    fun `the merge slice clamps to the available lines without throwing`() {
        // A vocab far shorter than the 48894-merge slice must still build.
        val lines = listOf("#version", "a b", "c d")
        val tokenizer = ClipTokenizer.fromLines(lines, contextLength = 8)

        assertEquals(8, tokenizer.tokenize("a").size)
    }

    @Test
    fun `a short vocab builds a usable encoder without throwing`() {
        // The vocab is byte values (256) + byte values with </w> (256) + merges
        // + 2 specials; with the merge slice clamped to a small synthetic vocab
        // the count shrinks, but every piece the tokenizer emits must still
        // resolve to an id — an unresolved piece is a silently dropped token.
        val lines = listOf("#version", "a b", "c d", "e f")
        val tokenizer = ClipTokenizer.fromLines(lines, contextLength = 8)

        val tokens = tokenizer.tokenize("ab")

        // SOT + the ids for "a" and "b</w>" + EOT, zero-padded to 8.
        assertEquals(49406L, tokens[0])
        assertTrue(tokens[1] != 0L)
        assertTrue(tokens[2] != 0L)
        assertEquals(49407L, tokens[3])
    }
}
