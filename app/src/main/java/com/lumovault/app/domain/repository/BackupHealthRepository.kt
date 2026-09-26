package com.lumovault.app.domain.repository

import com.lumovault.app.domain.model.BackupHealth
import kotlinx.coroutines.flow.Flow

/**
 * One read of the library's backup state, for Backup Health and Diagnostics.
 *
 * Observed rather than asked, so both screens move when the queue does and neither has to poll: a failed
 * item that is retried should make the number change on screen without anyone navigating back into it.
 */
interface BackupHealthRepository {
    fun observe(): Flow<BackupHealth>
}
