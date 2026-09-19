package com.lumovault.lumovault.features.gallery.data.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the classifier's label decode path.
 *
 * Inference cannot run on the JVM (native library), but the decode — softmax,
 * threshold, canonical index mapping and hypernym expansion — is pure math over
 * the logits. A wrong index mapping here mislabels every photo; the map this
 * one replaced put cats 281-285 at "shed"/"garage"/"tent".
 */
class ImageClassifierDecodeTest {

    @Test
    fun `empty logits decode to no labels`() {
        assertTrue(ImageClassifierService.decodeTopLabels(FloatArray(0)).isEmpty())
    }

    @Test
    fun `a single confident class decodes to its ai_ tag`() {
        // index 281 = "tabby cat" in canonical torchvision order
        val logits = FloatArray(1000)
        logits[281] = 20.0f

        val labels = ImageClassifierService.decodeTopLabels(logits)

        assertEquals("ai_tabby", labels.first())
        // 'tabby' expands to the 'cat' hypernym.
        assertTrue("ai_cat" in labels)
        assertTrue(labels.all { it.startsWith("ai_") })
    }

    @Test
    fun `sub-threshold probabilities are dropped`() {
        // Two classes with tiny, near-equal logits — neither clears 0.08.
        val logits = FloatArray(1000)
        logits[0] = 0.0f
        logits[1] = 0.0f

        assertTrue(ImageClassifierService.decodeTopLabels(logits).isEmpty())
    }

    @Test
    fun `labels are ordered by descending probability`() {
        val logits = FloatArray(1000)
        logits[281] = 15.0f // tabby
        // 10.0f would put tench at a softmax probability of ~0.003, far below
        // the 0.08 floor, so it would be dropped before ordering is tested.
        // 14.0f keeps both classes above the floor (~0.73 / ~0.27).
        logits[0] = 14.0f   // tench

        val labels = ImageClassifierService.decodeTopLabels(logits)

        assertEquals("ai_tabby", labels[0])
        // tench has no hypernym expansion, so it appears exactly once.
        assertTrue("ai_tench" in labels)
        val tenchIndex = labels.indexOf("ai_tench")
        val catIndex = labels.indexOf("ai_cat")
        assertTrue(tenchIndex > catIndex || !labels.contains("ai_cat"))
    }

    @Test
    fun `maxLabels caps the number of returned top classes`() {
        val logits = FloatArray(1000)
        logits[281] = 20.0f
        logits[282] = 19.0f
        logits[283] = 18.0f
        logits[284] = 17.0f
        logits[285] = 16.0f
        logits[0] = 15.0f

        val labels = ImageClassifierService.decodeTopLabels(logits, maxLabels = 2)

        // Only the two highest-scoring classes seed the list; their hypernym
        // expansions may append more, but no third class contributes.
        assertTrue(labels.contains("ai_tabby"))
        assertTrue(labels.contains("ai_tiger_cat"))
        // 285 = 'Egyptian cat' would be third.
        assertTrue(!labels.contains("ai_egyptian_cat"))
    }

    @Test
    fun `hypernyms expand tabby to cat`() {
        // The whole point of the expansion: searching 'cat' must find a photo
        // the model labelled 'tabby', which has no substring relation to 'cat'.
        val hypernyms = ImageClassifierService.hypernymsFor("tabby")
        assertTrue("cat" in hypernyms)
    }

    @Test
    fun `hypernyms match on word boundaries so catamaran is not a cat`() {
        val catamaran = ImageClassifierService.hypernymsFor("catamaran")
        assertTrue("cat" !in catamaran)

        val egyptianCat = ImageClassifierService.hypernymsFor("egyptian cat")
        assertTrue("cat" in egyptianCat)
    }

    @Test
    fun `the canonical cat indices carry the expected labels`() {
        // Anchor check from tool/gen_label_map.py: if the map were reordered,
        // these would move and every label-based test above would be lying.
        assertEquals("tench", imageNetLabels[0])
        assertEquals("tabby", imageNetLabels[281])
        assertEquals("tiger cat", imageNetLabels[282])
        // Canonical torchvision order: 283 is the Persian cat, 284 the Siamese.
        assertEquals("Persian cat", imageNetLabels[283])
        assertEquals("Siamese cat", imageNetLabels[284])
        assertEquals("toilet tissue", imageNetLabels[999])
    }

    @Test
    fun `the label map covers all 1000 ImageNet classes`() {
        assertEquals(1000, imageNetLabels.size)
        for (i in 0 until 1000) {
            assertTrue("index $i missing", imageNetLabels.containsKey(i))
        }
    }
}
