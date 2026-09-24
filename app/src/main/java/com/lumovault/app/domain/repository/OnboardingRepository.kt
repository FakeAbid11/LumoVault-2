package com.lumovault.app.domain.repository

import com.lumovault.app.domain.model.BackupSource
import com.lumovault.app.domain.model.OnboardingProgress
import com.lumovault.app.domain.model.OptionalStepDecision
import kotlinx.coroutines.flow.Flow

/**
 * What the user decided during setup. Permission grants themselves are never stored here — they are
 * read live, since Android can revoke them at any time.
 */
interface OnboardingRepository {
    val progress: Flow<OnboardingProgress>

    suspend fun setTelegramLinked(linked: Boolean)

    suspend fun setNotificationsDecision(decision: OptionalStepDecision)

    suspend fun setBackgroundBackupDecision(decision: OptionalStepDecision)

    /** [folders] is only meaningful for [BackupSource.SelectedFolders]. */
    suspend fun setBackupSource(source: BackupSource, folders: List<String> = emptyList())

    suspend fun completeOnboarding()
}
