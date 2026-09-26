package com.lumovault.app.domain.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The text a backup carries into the channel, and everything that must be true when it comes back.
 *
 * [BackupManifestFormat.decode] is the half that matters most, because its output decides whether a local
 * file is told "already stored" without ever being uploaded. A caption that is merely *near* the format
 * has to decode to nothing: a false hash here does not produce a wrong label, it produces a missing photo
 * that everyone believes is safe.
 */
class BackupManifestFormatTest {
    private val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private val manifest = BackupManifest(
        contentHash = hash,
        sizeBytes = 4_320_112L,
        modifiedSeconds = 1_790_000_000L,
        fileName = "IMG_20260925_184211.jpg",
    )

    @Test
    fun aManifestSurvivesTheRoundTripThroughACaption() {
        val text = BackupManifestFormat.encode(manifest)

        assertEquals(manifest, BackupManifestFormat.decode(text))
    }

    @Test
    fun theSameContentAlwaysEncodesToTheSameText() {
        // Determinism is what lets two passes agree about a manifest without comparing objects, and what
        // makes a caption diff readable when a user screenshots their own channel.
        assertEquals(BackupManifestFormat.encode(manifest), BackupManifestFormat.encode(manifest))
        assertEquals(
            "LUMOVAULT_META v1 h=$hash s=4320112 m=1790000000 n=IMG_20260925_184211.jpg",
            BackupManifestFormat.encode(manifest),
        )
    }

    @Test
    fun aNameWithSeparatorsInItComesBackUnchanged() {
        val awkward = manifest.copy(fileName = "my photo = 100%.jpg")

        val decoded = BackupManifestFormat.decode(BackupManifestFormat.encode(awkward))

        assertEquals("my photo = 100%.jpg", decoded?.fileName)
        assertEquals(hash, decoded?.contentHash)
    }

    @Test
    fun aNameThatWouldNotFitIsDroppedRatherThanCut() {
        val long = manifest.copy(fileName = "v".repeat(600) + ".jpg")

        val encoded = BackupManifestFormat.encode(long)
        val decoded = BackupManifestFormat.decode(encoded)

        assertEquals("the budget is respected", true, encoded.length <= BackupManifestFormat.CAPTION_BUDGET_CHARS)
        assertEquals("", decoded?.fileName)
        assertEquals("and the identity still round trips", hash, decoded?.contentHash)
        assertEquals(4_320_112L, decoded?.sizeBytes)
    }

    @Test
    fun theHashIsTheLastThingToGoAndTheFirstThingRequired() {
        val encoded = BackupManifestFormat.encode(manifest, budgetChars = 40)

        // 40 characters cannot hold even the prefix and the digest. The hash still has to be there,
        // because a manifest without it identifies nothing; the optional fields are what give way.
        assertNotNull(BackupManifestFormat.decode(encoded))
        assertEquals(hash, BackupManifestFormat.decode(encoded)?.contentHash)
    }

    @Test
    fun aCaptionThatIsNotAManifestIsIgnoredRatherThanHalfRead() {
        assertNull(BackupManifestFormat.decode(null))
        assertNull(BackupManifestFormat.decode(""))
        assertNull(BackupManifestFormat.decode("at the lake with the family"))
        assertNull(BackupManifestFormat.decode("LUMOVAULT_META h=$hash"))
        assertNull(BackupManifestFormat.decode("LUMOVAULT_META v1 s=10"))
        assertNull(BackupManifestFormat.decode("LUMOVAULT_META v1 h=not-a-hash s=10"))
        assertNull(BackupManifestFormat.decode("LUMOVAULT_META v1 h=${hash.dropLast(1)} s=10"))
        assertNull(BackupManifestFormat.decode("LUMOVAULT_META v9 h=$hash"))
    }

    @Test
    fun aChannelMarkerIsNeverMistakenForABackupManifest() {
        // Both are LumoVault text in the same channel, and the two readers run over the same captions. If
        // a marker parsed as a manifest, adopting a channel would appear to back up an arbitrary file.
        val marker = LumoVaultStorageProtocol.markerText()

        assertNull(BackupManifestFormat.decode(marker))
        assertNull(BackupManifestFormat.decode("LUMOVAULT_META is not $marker"))
    }

    @Test
    fun aManifestWrittenAcrossLinesStillReads() {
        // Telegram is free to reflow what it stores, and the channel description gets the same treatment.
        val folded = BackupManifestFormat.encode(manifest).replace(" ", "\n")

        assertEquals(manifest, BackupManifestFormat.decode(folded))
    }

    @Test
    fun anUpperCaseDigestIsNormalisedToLowercase() {
        // The column stores lowercase and the lookup is exact, so a digest that arrived uppercased would
        // never match its own file.
        val decoded = BackupManifestFormat.decode("LUMOVAULT_META v1 h=${hash.uppercase()}")

        assertEquals(hash, decoded?.contentHash)
    }

    @Test
    fun negativeAndGarbageNumbersFallBackToNothingRatherThanToAWrongFact() {
        val decoded = BackupManifestFormat.decode("LUMOVAULT_META v1 h=$hash s=-40 m=abc n=x.jpg")

        assertEquals(hash, decoded?.contentHash)
        assertEquals(0L, decoded?.sizeBytes)
        assertEquals(0L, decoded?.modifiedSeconds)
        assertEquals("x.jpg", decoded?.fileName)
    }

    @Test
    fun aNewerProtocolVersionIsRefusedInsteadOfGuessedAt() {
        val future = "LUMOVAULT_META v${BackupManifestFormat.VERSION + 1} h=$hash s=10"

        assertNull(
            "an app must not invent a backup relationship from a field layout it has never seen",
            BackupManifestFormat.decode(future),
        )
    }
}
