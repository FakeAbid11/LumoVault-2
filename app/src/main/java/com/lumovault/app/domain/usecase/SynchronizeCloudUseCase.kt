package com.lumovault.app.domain.usecase

import com.lumovault.app.domain.repository.CloudIndexRepository
import com.lumovault.app.domain.telegram.CloudAssociation
import com.lumovault.app.domain.telegram.CloudChannelVerdict
import com.lumovault.app.domain.telegram.CloudFailure
import com.lumovault.app.domain.telegram.CloudFailureException
import com.lumovault.app.domain.telegram.CloudInitState
import com.lumovault.app.domain.telegram.LumoVaultStorageProtocol
import com.lumovault.app.domain.telegram.TelegramCloudRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The cloud start-up flow: authenticate, find or create the storage channel, then index its history.
 *
 * This is the first piece of LumoVault that genuinely spans two repositories — Telegram for what is
 * remote, Room for the index — which is why it is a use case rather than more logic in a ViewModel or
 * a pass-through layer: the alternative was either a screen owning a nine-step state machine, or a
 * repository pretending to know about another one.
 *
 * Rules it exists to enforce:
 * - A saved chat id is never trusted blindly ([CloudIndexRepository.association] is validated, and a
 *   chat id belonging to a different Telegram account is dropped before anything is read).
 * - A channel is created only when no *valid* channel exists, so a second sign-in cannot produce a
 *   second storage channel.
 * - A scan that resumes from a cursor does not prune, because it did not see the whole history.
 * - Progress is a count of indexed items, never a percentage invented from a page number.
 */
class SynchronizeCloudUseCase(
    private val telegram: TelegramCloudRepository,
    private val index: CloudIndexRepository,
    private val isAuthenticated: () -> Boolean,
    private val nowSeconds: () -> Long,
    private val pageSize: Int = PAGE_SIZE,
) {
    private val _state = MutableStateFlow<CloudInitState>(CloudInitState.Idle)
    val state: StateFlow<CloudInitState> = _state.asStateFlow()

    /**
     * @param createIfMissing false when the caller has not been told it may create a channel — a
     *   deleted channel must be reported, not silently replaced (PRD section 74).
     * @return the adopted channel, or null when the flow stopped; [state] says which step failed.
     */
    suspend fun synchronize(createIfMissing: Boolean = true): CloudAssociation? {
        if (!telegram.isUsable) {
            _state.value = CloudInitState.TelegramUnavailable
            return null
        }
        if (!isAuthenticated()) {
            // Transient: Telegram can be briefly unavailable without the user being signed out, and
            // restarting onboarding over that would be the worst possible reaction.
            _state.value = CloudInitState.WaitingForTelegram
            return null
        }

        return try {
            run(createIfMissing)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CloudFailureException) {
            _state.value = failure.toState()
            null
        } catch (error: Exception) {
            _state.value = CloudInitState.Failed(CloudFailure(CloudFailure.Kind.Unexpected))
            null
        }
    }

    private suspend fun run(createIfMissing: Boolean): CloudAssociation? {
        val userId = telegram.accountUserId()

        _state.value = CloudInitState.ValidatingChannel
        var association = existingAssociation(userId)

        if (association == null) {
            _state.value = CloudInitState.SearchingChannel
            val found = telegram.findStorageChannel()

            association = when {
                found != null -> CloudAssociation(
                    chatId = found,
                    ownerUserId = userId,
                    protocolVersion = LumoVaultStorageProtocol.VERSION,
                )

                !createIfMissing -> {
                    _state.value = CloudInitState.Failed(CloudFailure(CloudFailure.Kind.ChannelUnusable))
                    return null
                }

                else -> {
                    _state.value = CloudInitState.CreatingChannel
                    CloudAssociation(
                        chatId = telegram.createStorageChannel(),
                        ownerUserId = userId,
                        protocolVersion = LumoVaultStorageProtocol.VERSION,
                    ).also { index.saveAssociation(it) }
                }
            }
        }

        scan(association)
        _state.value = CloudInitState.Ready
        return association
    }

    /**
     * The saved channel, if it is still this account's storage.
     *
     * A mismatch on [CloudAssociation.ownerUserId] is not a repairable detail: it means the Telegram
     * account changed, and continuing would browse the previous account's library under the new one.
     * `getMe` is therefore read before any chat request, and the whole association is dropped — with
     * its index, which holds the other account's file names — rather than re-validated.
     */
    private suspend fun existingAssociation(userId: Long): CloudAssociation? {
        val saved = index.association() ?: return null

        if (saved.ownerUserId != userId) {
            index.dropAssociation()
            return null
        }

        return when (telegram.validateChannel(saved.chatId)) {
            CloudChannelVerdict.Valid -> saved
            // Anything else — deleted, lost access, renamed, or never ours — means the stored id
            // cannot be trusted any more, so discovery starts over.
            else -> {
                index.dropAssociation()
                null
            }
        }
    }

    /**
     * Walks history newest-first, one page at a time, writing each page before asking for the next.
     *
     * No page is held in memory beyond itself, which is what lets a 50,000-message channel scan
     * without a list that size. The cursor is persisted per page so a process death resumes instead of
     * restarting, and pruning waits for a walk that began at the newest message.
     */
    private suspend fun scan(starting: CloudAssociation) {
        val scanId = nowSeconds()
        var cursor = starting.lastScannedMessageId
        val walkedEverything = cursor == 0L

        _state.value = CloudInitState.Scanning(index.currentCount())

        while (true) {
            val page = telegram.loadHistoryPage(starting.chatId, cursor, pageSize)
            index.savePage(chatId = starting.chatId, scanId = scanId, items = page.media)
            _state.value = CloudInitState.Scanning(index.currentCount())

            if (page.reachedBeginning) break
            if (cursor != 0L && page.oldestMessageId >= cursor) break

            cursor = page.oldestMessageId
            index.saveAssociation(starting.copy(lastScannedMessageId = cursor))
        }

        index.finishScan(
            scanId = scanId,
            association = starting.copy(lastScannedMessageId = 0, lastSyncSeconds = scanId),
            prune = walkedEverything,
        )
    }

    private fun CloudFailureException.toState(): CloudInitState {
        val kind = failure.kind
        return when (kind) {
            // A network-shaped failure is Offline rather than Failed: the cached index stays on screen
            // and the copy says so, because nothing about the library itself is wrong.
            CloudFailure.Kind.RequestFailed,
            CloudFailure.Kind.RateLimited,
            -> CloudInitState.Offline

            CloudFailure.Kind.NotAuthenticated -> CloudInitState.WaitingForTelegram
            else -> CloudInitState.Failed(failure)
        }
    }

    private companion object {
        /** TDLib caps a history page at 100 messages and may return fewer. */
        const val PAGE_SIZE = 100
    }
}
