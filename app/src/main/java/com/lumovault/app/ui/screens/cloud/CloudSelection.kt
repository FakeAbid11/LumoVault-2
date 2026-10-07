package com.lumovault.app.ui.screens.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The Cloud grid's selection: message ids, and the one rule select-all obeys.
 *
 * Extracted from the view model so the rules are testable without a phone — the selection is the
 * same kind of value in Photos and in Cloud (a set of ids, toggled by tap and long press), and the
 * part worth asserting is exactly the part an AndroidViewModel cannot reach: that a toggle removes
 * what it finds, that a clear means empty, and that select-all answers with *every* id the provider
 * gives it rather than whatever page happened to be loaded.
 *
 * The provider is injected rather than read here so this class holds no repository, no Room and no
 * Telegram — the unwindowed read lives in the view model, and `CloudSelectAllRealSqlTest` proves
 * that read is the whole collection.
 */
internal class CloudSelection(private val allIds: suspend () -> List<Long>) {
    private val _selected = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * The chosen message ids. The strip prints this set's size and every action acts on this set —
     * they are one number, which is what keeps "500 selected" meaning 500 no matter how far the
     * grid has scrolled.
     */
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    /** One tap adds an id that is not chosen and removes one that is. The whole of selection mode. */
    fun toggle(messageId: Long) {
        _selected.update { current ->
            if (messageId in current) current - messageId else current + messageId
        }
    }

    /** Leaves selection mode. Choosing nothing and choosing the same three twice both end here. */
    fun clear() {
        _selected.value = emptySet()
    }

    /**
     * Every applicable id — never the loaded window.
     *
     * "Select all" that meant "all that happens to be loaded" would report 300 over a cloud of
     * 30,000, and every action downstream would then act on a third of what the count claimed. The
     * answer replaces whatever was chosen: select-all is a statement about the collection, not an
     * addition to a partial one.
     */
    suspend fun selectAll() {
        _selected.value = allIds().toSet()
    }
}
