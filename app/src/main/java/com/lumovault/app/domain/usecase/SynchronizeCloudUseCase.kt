package com.lumovault.app.domain.usecase

import com.lumovault.app.domain.repository.CloudIndexRepository
import com.lumovault.app.domain.telegram.ChannelDiscovery
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
 * - A channel is created only when discovery has *concluded* that none exists. A lookup that came back
 *   empty because TDLib is still loading its chat list — which is every cloud start-up after a reinstall,
 *   until this rule existed — is not an answer, and building on it costs the user the channel holding every
 *   photograph they backed up.
 * - A scan that resumes from a cursor does not prune, because it did not see the whole history.
 * - Progress is a count of indexed items, never a percentage invented from a page number.
 */
class SynchronizeCloudUseCase(
    private val telegram: TelegramCloudRepository,
    private val index: CloudIndexRepository,
    private val isAuthenticated: () -> Boolean,
    private val nowSeconds: () -> Long,
    private val pageSize: Int = PAGE_SIZE,
    /**
     * Where the recovery decision goes, one event per line.
     *
     * A parameter rather than a call to `Log`, because this class is the domain and stays readable without
     * an Android framework in it — the container wires it to the real log. What it is for is the question a
     * support conversation cannot otherwise answer: was the saved channel reused, did discovery run, did it
     * find the old channel, and was creation actually permitted. Chat ids only; never a title or a path.
     */
    private val recover: (String) -> Unit = { },
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
            association = when (val discovery = telegram.discoverStorageChannel()) {
                is ChannelDiscovery.Found -> {
                    recover("CLOUD_CHANNEL_RECOVERED chat_id=${discovery.chatId}")
                    if (discovery.alternates > 0) {
                        // More than one channel passed every check. The decision below is deterministic and
                        // nothing is deleted, so this is a fact worth having in a log rather than a fault.
                        recover("CLOUD_CHANNEL_AMBIGUOUS alternates=${discovery.alternates}")
                    }
                    CloudAssociation(
                        chatId = discovery.chatId,
                        ownerUserId = userId,
                        protocolVersion = LumoVaultStorageProtocol.VERSION,
                    ).also { index.saveAssociation(it) }
                }

                // The only branch creation can come from: discovery finished and answered.
                ChannelDiscovery.Absent -> {
                    if (!createIfMissing) {
                        // A channel that used to be here was deleted, and PRD section 74 wants that said
                        // rather than papered over with a fresh empty one.
                        recover("CLOUD_CHANNEL_CREATION_DECLINED reason=caller_forbade_creation")
                        _state.value = CloudInitState.Failed(CloudFailure(CloudFailure.Kind.ChannelUnusable))
                        return null
                    }

                    recover("CLOUD_CHANNEL_CREATION_ALLOWED")
                    _state.value = CloudInitState.CreatingChannel
                    CloudAssociation(
                        chatId = telegram.createStorageChannel(),
                        ownerUserId = userId,
                        protocolVersion = LumoVaultStorageProtocol.VERSION,
                    ).also {
                        index.saveAssociation(it)
                        recover("CLOUD_CHANNEL_CREATED chat_id=${it.chatId}")
                    }
                }

                is ChannelDiscovery.InProgress -> {
                    // Nothing was concluded, so nothing is built on top of the silence. Retryable, and the
                    // reason it is not `Failed` is that nothing about the library is wrong yet.
                    recover("CLOUD_CHANNEL_DISCOVERY_RETRY reason=${discovery.reason}")
                    _state.value = CloudInitState.Offline
                    null
                }
            } ?: return null
        } else {
            recover("CLOUD_CHANNEL_ASSOCIATION_FOUND chat_id=${association.chatId}")
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
