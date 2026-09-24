package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.telegram.LumoVaultStorageProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The storage protocol and the message mapper are the two places where Phase 4 can be wrong about
 * real Telegram data without anything failing loudly, so both are pinned by fixtures rather than by
 * assumptions: the marker grammar decides which channel gets adopted, and the size choice decides
 * whether the cloud grid ever touches an original.
 */
class TdCloudMappingTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).let { it as JsonObject }

    @Test
    fun markerParsesFromMessageAndFromFlattenedDescription() {
        assertEquals(1, LumoVaultStorageProtocol.markerVersion("LUMOVAULT_BACKUP\nversion: 1"))
        assertEquals(1, LumoVaultStorageProtocol.markerVersion("LUMOVAULT_BACKUP version: 1"))
        assertEquals(1, LumoVaultStorageProtocol.markerVersion("  LUMOVAULT_BACKUP\n\n  version: 1  "))
        assertTrue(LumoVaultStorageProtocol.isMarker(LumoVaultStorageProtocol.markerText()))
    }

    @Test
    fun lookalikesAreNotMarkers() {
        assertFalse(LumoVaultStorageProtocol.isMarker("LumoVault Backup"))
        assertFalse(LumoVaultStorageProtocol.isMarker("please back up my photos"))
        // A newer protocol is a marker, but not one this build may adopt.
        assertEquals(7, LumoVaultStorageProtocol.markerVersion("LUMOVAULT_BACKUP\nversion: 7"))
        assertFalse(LumoVaultStorageProtocol.isMarker("LUMOVAULT_BACKUP\nversion: 7"))
        assertNull(LumoVaultStorageProtocol.markerVersion("LUMOVAULT_BACKUP\nbuilt with LumoVault"))
        assertNull(LumoVaultStorageProtocol.markerVersion("version: 1"))
    }

    @Test
    fun photoPreviewIsTheSmallestSizeNeverTheLargest() {
        val message = json(
            """
            {"id":11,"date":1758768000,"content":{"@type":"messagePhoto","photo":{
              "sizes":[
                {"type":"i","width":4032,"height":3024,"photo":{"remote":{"id":"PHOTO_BIG"}}},
                {"type":"x","width":160,"height":120,"photo":{"remote":{"id":"PHOTO_SMALL"}}}
              ]}}}
            """.trimIndent(),
        )

        val media = TdCloudMapper.toCloudMedia(message, chatId = 7)!!

        assertEquals(MediaType.Photo, media.type)
        assertEquals("PHOTO_BIG", media.remoteFileId)
        // The grid must reference the 160px variant; picking by list position would pick the 4032px
        // original, which is the download PRD section 25 forbids.
        assertEquals("PHOTO_SMALL", media.previewRemoteFileId)
        assertEquals(4032, media.width)
        assertEquals(7, media.chatId)
        assertEquals(1758768000L, media.dateSeconds)
    }

    @Test
    fun videosAndGifsAreToldApartFromTheirOwnFields() {
        val video = json(
            """
            {"id":12,"date":1,"content":{"@type":"messageVideo","video":{
              "duration":42,"width":1920,"height":1080,"file_name":"a.mp4","mime_type":"video/mp4",
              "thumbnail":{"file":{"remote":{"id":"THUMB"}}},"video":{"size":9000,"remote":{"id":"VID"}}}}}
            """.trimIndent(),
        )
        assertEquals(MediaType.Video, TdCloudMapper.toCloudMedia(video, 7)!!.type)
        assertEquals("THUMB", TdCloudMapper.toCloudMedia(video, 7)!!.previewRemoteFileId)
        assertEquals(9000L, TdCloudMapper.toCloudMedia(video, 7)!!.sizeBytes)

        val gif = json(
            """
            {"id":13,"date":1,"content":{"@type":"messageAnimation","animation":{
              "duration":3,"width":200,"height":200,"file_name":"g.gif","mime_type":"image/gif",
              "thumbnail":{"file":{"remote":{"id":"GT"}}},"animation":{"size":300,"remote":{"id":"GA"}}}}}
            """.trimIndent(),
        )
        assertEquals(MediaType.Gif, TdCloudMapper.toCloudMedia(gif, 7)!!.type)

        // An MP4 animation is not a GIF, and a PDF document is not media at all.
        val mp4Animation = json(
            """
            {"id":14,"date":1,"content":{"@type":"messageAnimation","animation":{
              "mime_type":"video/mp4","animation":{"remote":{"id":"A"}}}}}
            """.trimIndent(),
        )
        assertEquals(MediaType.Video, TdCloudMapper.toCloudMedia(mp4Animation, 7)!!.type)

        val pdf = json(
            """
            {"id":15,"date":1,"content":{"@type":"messageDocument","document":{
              "mime_type":"application/pdf","document":{"remote":{"id":"D"}}}}}
            """.trimIndent(),
        )
        assertNull(TdCloudMapper.toCloudMedia(pdf, 7))

        val gifDocument = json(
            """
            {"id":16,"date":1,"content":{"@type":"messageDocument","document":{
              "mime_type":"image/gif","document":{"remote":{"id":"D"}}}}}
            """.trimIndent(),
        )
        assertEquals(MediaType.Gif, TdCloudMapper.toCloudMedia(gifDocument, 7)!!.type)
    }

    @Test
    fun markerMessageIsTextAndNotMedia() {
        // \\n so JSON receives an escaped newline inside the string literal.
        val marker = json(
            """{"id":1,"date":1,"content":{"@type":"messageText","text":{"text":"LUMOVAULT_BACKUP\\nversion: 1"}}}""",
        )
        assertNull(TdCloudMapper.toCloudMedia(marker, 7))
        assertEquals(1, TdCloudMapper.markerVersionIn(listOf(marker)))

        val plain = json(
            """{"id":2,"date":1,"content":{"@type":"messageText","text":{"text":"hi"}}}""",
        )
        assertNull(TdCloudMapper.markerVersionIn(listOf(plain)))
    }

    @Test
    fun onlyABroadcastSupergroupThisAccountOwnsCanBeNullStorage() {
        val channel = json("""{"id":5,"title":"LumoVault Backup","type":{"@type":"chatTypeSupergroup","is_channel":true,"supergroup_id":9}}""")
        val group = json("""{"id":6,"title":"LumoVault Backup","type":{"@type":"chatTypeSupergroup","is_channel":false,"supergroup_id":9}}""")
        val private = json("""{"id":7,"title":"LumoVault Backup","type":{"@type":"chatTypePrivate","user_id":3}}""")

        assertTrue(TdCloudMapper.isBroadcastChannel(channel))
        assertFalse("a group is not a channel", TdCloudMapper.isBroadcastChannel(group))
        assertFalse(TdCloudMapper.isBroadcastChannel(private))
        assertEquals(9L, TdCloudMapper.supergroupIdOf(channel))

        assertTrue(TdCloudMapper.isOwnedByMe(json("""{"status":{"@type":"chatMemberStatusCreator"}}""")))
        assertTrue(TdCloudMapper.isOwnedByMe(json("""{"status":{"@type":"chatMemberStatusAdministrator"}}""")))
        assertFalse(TdCloudMapper.isOwnedByMe(json("""{"status":{"@type":"chatMemberStatusMember"}}""")))
        assertFalse(TdCloudMapper.isOwnedByMe(json("""{"status":{"@type":"chatMemberStatusLeft"}}""")))
    }

    @Test
    fun idsSurviveBothNumberAndStringEncodings() {
        // TDLib documents int53 as a number, but a value that arrives quoted must not silently drop
        // the item: the walk would look complete and the index would be short.
        assertEquals(42L, json("""{"id":42}""").longOf("id"))
        assertEquals(42L, json("""{"id":"42"}""").longOf("id"))
        assertNull(json("""{"id":"abc"}""").longOf("id"))
    }
}
