package com.lumovault.app.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of the state machine, checked directly.
 *
 * These are not bookkeeping tests. The queue's rows are what puts a mark on a thumbnail and what tells
 * Phase 6 which items to trust, so a row that could go `Failed` → `BackedUp` with no send in between
 * would show ✓ on a photo that was never uploaded — and nothing else in the app would ever notice.
 */
class UploadTransitionsTest {
    @Test
    fun `the happy path is the whole forward journey`() {
        assertTrue(UploadTransitions.isLegal(UploadState.Queued, UploadState.Preparing))
        assertTrue(UploadTransitions.isLegal(UploadState.Preparing, UploadState.Uploading))
        assertTrue(UploadTransitions.isLegal(UploadState.Uploading, UploadState.BackedUp))
    }

    @Test
    fun `a row cannot reach the done state without passing through an upload`() {
        assertFalse(UploadTransitions.isLegal(UploadState.Queued, UploadState.BackedUp))
        assertFalse(UploadTransitions.isLegal(UploadState.Preparing, UploadState.BackedUp))
        assertFalse(UploadTransitions.isLegal(UploadState.Failed, UploadState.BackedUp))
        assertFalse(UploadTransitions.isLegal(UploadState.Cancelled, UploadState.BackedUp))
    }

    @Test
    fun `an interrupted send is re-queued rather than reported as a failure`() {
        // Process death is not the file's fault, so recovery has to be legal from the in-flight
        // states — and it returns to the queue rather than claiming anything was achieved.
        assertTrue(UploadTransitions.isLegal(UploadState.Uploading, UploadState.Queued))
        assertTrue(UploadTransitions.isLegal(UploadState.Preparing, UploadState.Queued))
        assertFalse(UploadTransitions.isLegal(UploadState.Uploading, UploadState.Cancelled))
    }

    @Test
    fun `only a failed item can be retried, and a queued one can still be withdrawn`() {
        assertTrue(UploadTransitions.isLegal(UploadState.Failed, UploadState.Queued))
        assertTrue(UploadTransitions.isLegal(UploadState.Queued, UploadState.Cancelled))
        assertFalse(UploadTransitions.isLegal(UploadState.BackedUp, UploadState.Queued))
    }

    @Test
    fun `writing the state a row already has is always allowed`() {
        // A refresh that re-asserts the current state is not a transition; refusing it would make an
        // idempotent write fail.
        UploadState.entries.forEach { state ->
            assertTrue("expected a no-op write to be legal for ${state.storageKey}",
                UploadTransitions.isLegal(state, state))
        }
    }

    @Test
    fun `an unknown stored key fails closed rather than re-queuing itself`() {
        assertEquals(UploadState.Queued, UploadState.fromStorageKey("queued"))
        assertEquals(UploadState.BackedUp, UploadState.fromStorageKey("backed_up"))
        assertEquals(UploadState.Failed, UploadState.fromStorageKey("made_up_state"))
        assertEquals(UploadState.Failed, UploadState.fromStorageKey(null))
    }

    @Test
    fun `the states in flight are exactly the ones a restart has to reconcile`() {
        assertTrue(UploadState.Preparing.isInFlight)
        assertTrue(UploadState.Uploading.isInFlight)
        assertFalse(UploadState.Queued.isInFlight)
        assertFalse(UploadState.BackedUp.isInFlight)
        assertFalse(UploadState.Failed.isInFlight)
        assertFalse(UploadState.Cancelled.isInFlight)
    }

    @Test
    fun `storage keys are stable strings, never ordinals`() {
        // Stored rows on installs already in use hold these values, so reordering the enum must not
        // change what an existing row means.
        assertEquals("queued", UploadState.Queued.storageKey)
        assertEquals("preparing", UploadState.Preparing.storageKey)
        assertEquals("uploading", UploadState.Uploading.storageKey)
        assertEquals("backed_up", UploadState.BackedUp.storageKey)
        assertEquals("failed", UploadState.Failed.storageKey)
        assertEquals("cancelled", UploadState.Cancelled.storageKey)
    }

    @Test
    fun `only the states that can genuinely recover are marked retryable`() {
        assertTrue(BackupFailureKind.Network.retryable)
        assertTrue(BackupFailureKind.RateLimited.retryable)
        assertTrue(BackupFailureKind.NotAuthenticated.retryable)
        assertTrue(BackupFailureKind.Unknown.retryable)

        assertFalse(BackupFailureKind.SourceMissing.retryable)
        assertFalse(BackupFailureKind.SourceUnreadable.retryable)
        assertFalse(BackupFailureKind.InsufficientSpace.retryable)
        assertFalse(BackupFailureKind.ChannelUnavailable.retryable)
        assertFalse(BackupFailureKind.Rejected.retryable)
    }
}
