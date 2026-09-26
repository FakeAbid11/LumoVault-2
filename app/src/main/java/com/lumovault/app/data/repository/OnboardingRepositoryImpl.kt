package com.lumovault.app.data.repository

import com.lumovault.app.data.local.AppSettingsEntity
import com.lumovault.app.data.local.AppSettingsStore
import com.lumovault.app.domain.model.BackupSource
import com.lumovault.app.domain.model.OnboardingProgress
import com.lumovault.app.domain.model.FolderPaths
import com.lumovault.app.domain.model.OptionalStepDecision
import com.lumovault.app.domain.repository.OnboardingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OnboardingRepositoryImpl(
    private val store: AppSettingsStore,
) : OnboardingRepository {
    override val progress: Flow<OnboardingProgress> =
        store.changes.map { it?.toProgress() ?: OnboardingProgress() }

    override suspend fun setTelegramLinked(linked: Boolean) {
        store.update { it.copy(telegramLinked = linked) }
    }

    override suspend fun setNotificationsDecision(decision: OptionalStepDecision) {
        store.update { it.copy(notificationPreference = decision.storageKey) }
    }

    override suspend fun setBackgroundBackupDecision(decision: OptionalStepDecision) {
        store.update { it.copy(backgroundBackupPreference = decision.storageKey) }
    }

    override suspend fun setBackupSource(source: BackupSource, folders: List<String>) {
        store.update {
            it.copy(
                sourceSelection = source.storageKey,
                // Only a real selection can enable automatic backup; "Not now" cannot.
                backupEnabled = source.enablesBackup,
                selectedFolders = if (source == BackupSource.SelectedFolders) {
                    // Stored as the identity the queue matches on, not as the label the picker showed.
                    // These two columns are read back by `autoBackupCandidates`, whose SQL compares
                    // `relative_path` exactly — a value with its trailing separator trimmed matches
                    // nothing, which is how a folder selection could silently stop backing up.
                    folders.map(FolderPaths::normalize).distinct().joinToString(FOLDER_SEPARATOR)
                } else {
                    ""
                },
            )
        }
    }

    override suspend fun completeOnboarding() {
        store.update { it.copy(onboardingCompleted = true) }
    }
}

private fun AppSettingsEntity.toProgress(): OnboardingProgress = OnboardingProgress(
    onboardingCompleted = onboardingCompleted,
    telegramLinked = telegramLinked,
    backupEnabled = backupEnabled,
    notifications = OptionalStepDecision.fromStorageKey(notificationPreference),
    backupSource = BackupSource.fromStorageKey(sourceSelection),
    selectedFolders = selectedFolders
        .split(FOLDER_SEPARATOR)
        .filter { it.isNotBlank() }
        // Normalized on the way out too, so a value stored before the writer did it is read as the
        // folder it meant rather than as a path that matches nothing. Idempotent, so this costs nothing
        // for the ones that were already written correctly.
        .map(FolderPaths::normalize)
        .distinct(),
    backgroundBackup = OptionalStepDecision.fromStorageKey(backgroundBackupPreference),
)

/** Newline, because a relative folder path can contain almost anything else. */
private const val FOLDER_SEPARATOR = "\n"
