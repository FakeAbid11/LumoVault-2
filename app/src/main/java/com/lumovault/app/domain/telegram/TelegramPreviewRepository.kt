package com.lumovault.app.domain.telegram

/**
 * Turns a TDLib remote file id into something the UI can draw.
 *
 * Only ever called with a *thumbnail* id. The full-size `remoteFileId` on a [com.lumovault.app.domain.model.CloudMedia]
 * is stored for later phases and must not be handed to this interface by a grid or a viewer — that
 * distinction is PRD section 25's whole point, and it is why the parameter is a bare id rather than a
 * media item, so a caller cannot accidentally pass the original.
 */
interface TelegramPreviewRepository {
    /** False when TDLib is absent, in which case every caller shows a placeholder. */
    val isUsable: Boolean

    /**
     * Local file path for [remoteFileId] once Telegram has delivered that file, or null when it is
     * unavailable — unsupported build, unknown id, or still transferring.
     */
    suspend fun localPathFor(remoteFileId: String): String?
}
