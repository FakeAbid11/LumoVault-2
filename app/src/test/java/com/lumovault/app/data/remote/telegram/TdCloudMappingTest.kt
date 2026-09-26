package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.telegram.BackupManifest
import com.lumovault.app.domain.telegram.BackupManifestFormat
import com.lumovault.app.domain.telegram.LumoVaultStorageProtocol
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The storage protocol and the message mapper are the two places where the cloud layer can be wrong
 * about real Telegram data without anything failing loudly, so both are pinned by fixtures rather
 * than by assumptions: the marker grammar decides which channel gets adopted, and the size choice
 * decides whether the cloud grid ever touches an original.
 *
 * The fixtures are [TdApi] objects built the way TDLib's generated Java allows, which is what turns
 * "we guessed a field name" into a compile error instead of a mapper that quietly returns null.
 */
class TdCloudMappingTest {
    private fun message(id: Long, date: Int = 1, content: TdApi.MessageContent): TdApi.Message {
        val message = TdApi.Message()
        message.id = id
        message.date = date
        message.content = content
        return message
    }

    private fun tdFile(remoteId: String, size: Long = 0): TdApi.File {
        val remote = TdApi.RemoteFile()
        remote.id = remoteId

        val file = TdApi.File()
        file.size = size
        file.remote = remote
        return file
    }

    private fun photoSize(type: String, width: Int, height: Int, remoteId: String): TdApi.PhotoSize {
        val size = TdApi.PhotoSize()
        size.type = type
        size.width = width
        size.height = height
        size.photo = tdFile(remoteId)
        return size
    }

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
        val media = TdCloudMapper.toCloudMedia(
            message(
                id = 11,
                date = 1758768000,
                content = TdApi.MessagePhoto().apply {
                    photo = TdApi.Photo().apply {
                        sizes = arrayOf(
                            photoSize("i", 4032, 3024, "PHOTO_BIG"),
                            photoSize("x", 160, 120, "PHOTO_SMALL"),
                        )
                    }
                },
            ),
            chatId = 7,
        )!!

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
    fun aPhotoSizeWithNoDimensionsStillCountsAsMedia() {
        // TDLib only guarantees width and height on sizes it has measured. The fallback must keep the
        // item rather than drop it — an index that silently skips media is worse than one whose cell
        // has no intrinsic size.
        val media = TdCloudMapper.toCloudMedia(
            message(
                id = 21,
                content = TdApi.MessagePhoto().apply {
                    photo = TdApi.Photo().apply {
                        sizes = arrayOf(photoSize("a", 0, 0, "UNMEASURED"))
                    }
                },
            ),
            chatId = 7,
        )

        assertEquals("UNMEASURED", media?.remoteFileId)
        assertEquals(0, media?.width)
    }

    @Test
    fun videosAndGifsAreToldApartFromTheirOwnFields() {
        val video = message(
            id = 12,
            content = TdApi.MessageVideo().apply {
                this.video = TdApi.Video().apply {
                    duration = 42
                    width = 1920
                    height = 1080
                    fileName = "a.mp4"
                    mimeType = "video/mp4"
                    thumbnail = TdApi.Thumbnail().apply { file = tdFile("THUMB") }
                    this.video = tdFile("VID", size = 9000)
                }
            },
        )

        val mapped = TdCloudMapper.toCloudMedia(video, 7)!!
        assertEquals(MediaType.Video, mapped.type)
        assertEquals("THUMB", mapped.previewRemoteFileId)
        assertEquals(9000L, mapped.sizeBytes)
        assertEquals("a.mp4", mapped.fileName)
        assertEquals(42L, mapped.durationSeconds)

        val gif = message(
            id = 13,
            content = TdApi.MessageAnimation().apply {
                animation = TdApi.Animation().apply {
                    duration = 3
                    width = 200
                    height = 200
                    fileName = "g.gif"
                    mimeType = "image/gif"
                    thumbnail = TdApi.Thumbnail().apply { file = tdFile("GT") }
                    this.animation = tdFile("GA", size = 300)
                }
            },
        )
        assertEquals(MediaType.Gif, TdCloudMapper.toCloudMedia(gif, 7)?.type)

        // An MP4 animation is not a GIF, and a PDF document is not media at all.
        val mp4Animation = message(
            id = 14,
            content = TdApi.MessageAnimation().apply {
                animation = TdApi.Animation().apply {
                    mimeType = "video/mp4"
                    animation = tdFile("A")
                }
            },
        )
        assertEquals(MediaType.Video, TdCloudMapper.toCloudMedia(mp4Animation, 7)?.type)

        val pdf = message(
            id = 15,
            content = TdApi.MessageDocument().apply {
                document = TdApi.Document().apply {
                    mimeType = "application/pdf"
                    document = tdFile("D")
                }
            },
        )
        assertNull(TdCloudMapper.toCloudMedia(pdf, 7))

        val gifDocument = message(
            id = 16,
            content = TdApi.MessageDocument().apply {
                document = TdApi.Document().apply {
                    mimeType = "image/gif"
                    document = tdFile("D")
                }
            },
        )
        assertEquals(MediaType.Gif, TdCloudMapper.toCloudMedia(gifDocument, 7)?.type)
    }

    @Test
    fun captionTextIsCarriedButIsNeverMediaByItself() {
        val withCaption = message(
            id = 17,
            content = TdApi.MessagePhoto().apply {
                photo = TdApi.Photo().apply { sizes = arrayOf(photoSize("x", 10, 10, "P")) }
                caption = TdApi.FormattedText().apply { text = "at the lake" }
            },
        )

        assertEquals("at the lake", TdCloudMapper.toCloudMedia(withCaption, 7)?.caption)
        assertEquals("", TdCloudMapper.toCloudMedia(message(18, content = TdApi.MessageText()), 7)?.caption.orEmpty())
    }

    @Test
    fun aBackupManifestInACaptionBecomesTheHashCodeAndTheOnlyFileNameAPhotoHas() {
        // A photo message reports no name and no identity of its own — TDLib gives a file name for a
        // document and a video, and nothing for a photo — so the manifest LumoVault wrote is both.
        val manifest = BackupManifest(
            contentHash = DIGEST,
            sizeBytes = 4_320_112L,
            modifiedSeconds = 1_790_000_000L,
            fileName = "IMG_20260925_184211.jpg",
        )
        val backedUp = message(
            id = 21,
            content = TdApi.MessagePhoto().apply {
                photo = TdApi.Photo().apply { sizes = arrayOf(photoSize("x", 10, 10, "P")) }
                caption = TdApi.FormattedText().apply { text = BackupManifestFormat.encode(manifest) }
            },
        )

        val mapped = TdCloudMapper.toCloudMedia(backedUp, 7)

        assertEquals(DIGEST, mapped?.contentHash)
        assertEquals("IMG_20260925_184211.jpg", mapped?.fileName)
        assertEquals(true, mapped?.isLumoVaultBackup)
    }

    @Test
    fun aCaptionThatIsOrdinaryTextDeclaresNoContent() {
        val handUploaded = message(
            id = 22,
            content = TdApi.MessagePhoto().apply {
                photo = TdApi.Photo().apply { sizes = arrayOf(photoSize("x", 10, 10, "P")) }
                caption = TdApi.FormattedText().apply { text = "LumoVault backup of my holiday" }
            },
        )

        val mapped = TdCloudMapper.toCloudMedia(handUploaded, 7)

        assertEquals("", mapped?.contentHash)
        assertEquals(false, mapped?.isLumoVaultBackup)
        assertEquals("holiday text is kept as the caption it is", "LumoVault backup of my holiday", mapped?.caption)
    }

    @Test
    fun aMalformedOrFutureManifestIsReadAsNothingRatherThanAsAPartialHash() {
        fun withCaption(id: Long, text: String) = TdCloudMapper.toCloudMedia(
            message(
                id = id,
                content = TdApi.MessagePhoto().apply {
                    photo = TdApi.Photo().apply { sizes = arrayOf(photoSize("x", 10, 10, "P")) }
                    caption = TdApi.FormattedText().apply { this.text = text }
                },
            ),
            7,
        )

        assertEquals(
            "the hash is the mandatory field, so a manifest carrying nothing else still identifies content",
            DIGEST,
            withCaption(23, "LUMOVAULT_META v1 h=$DIGEST")?.contentHash,
        )
        assertEquals("", withCaption(24, "LUMOVAULT_META v2 h=$DIGEST s=10")?.contentHash)
        assertEquals("", withCaption(25, "LUMOVAULT_META v1 h=${DIGEST.dropLast(1)}")?.contentHash)
        assertEquals(
            "an unknown field alongside a real digest is decoration, not a reason to discard the identity",
            DIGEST,
            withCaption(26, "LUMOVAULT_META v1 early h=$DIGEST")?.contentHash,
        )
    }

    @Test
    fun markerMessageIsTextAndNotMedia() {
        val marker = message(
            id = 1,
            content = TdApi.MessageText().apply {
                text = TdApi.FormattedText().apply { text = LumoVaultStorageProtocol.markerText() }
            },
        )
        assertNull(TdCloudMapper.toCloudMedia(marker, 7))
        assertEquals(1, TdCloudMapper.markerVersionIn(listOf(marker)))

        val plain = message(
            id = 2,
            content = TdApi.MessageText().apply {
                text = TdApi.FormattedText().apply { text = "hi" }
            },
        )
        assertNull(TdCloudMapper.markerVersionIn(listOf(plain)))
    }

    @Test
    fun onlyABroadcastSupergroupThisAccountOwnsCanBeNullStorage() {
        fun chatOfType(broadcast: Boolean) = TdApi.Chat().apply {
            id = 5
            title = LumoVaultStorageProtocol.CHANNEL_TITLE
            type = TdApi.ChatTypeSupergroup().apply {
                supergroupId = 9
                isChannel = broadcast
            }
        }

        val asBroadcast = chatOfType(broadcast = true)
        val asGroup = chatOfType(broadcast = false)
        val asPrivate = TdApi.Chat().apply {
            id = 7
            title = LumoVaultStorageProtocol.CHANNEL_TITLE
            type = TdApi.ChatTypePrivate().apply { userId = 3 }
        }

        assertTrue(TdCloudMapper.isBroadcastChannel(asBroadcast))
        assertFalse("a group is not a channel", TdCloudMapper.isBroadcastChannel(asGroup))
        assertFalse(TdCloudMapper.isBroadcastChannel(asPrivate))
        assertEquals(9L, TdCloudMapper.supergroupIdOf(asBroadcast))
        assertNull(TdCloudMapper.supergroupIdOf(asPrivate))

        fun ownedBy(status: TdApi.ChatMemberStatus) =
            TdCloudMapper.isOwnedByMe(supergroupOwnedBy(status))

        assertTrue(ownedBy(TdApi.ChatMemberStatusCreator()))
        assertTrue(ownedBy(TdApi.ChatMemberStatusAdministrator()))
        assertFalse(ownedBy(TdApi.ChatMemberStatusMember()))
        assertFalse(ownedBy(TdApi.ChatMemberStatusRestricted()))
        assertFalse(ownedBy(TdApi.ChatMemberStatusLeft()))
        assertFalse("a supergroup with no status is not ours", ownedBy(TdApi.ChatMemberStatusBanned()))
    }

    @Test
    fun anUnidentifiedMessageOrSenderIsSkippedRatherThanIndexed() {
        // TDLib reserves 0 for "no identifier". Indexing one would put a row in the cloud index that no
        // later request can resolve, and the walk's cursor would stop moving.
        assertNull(TdCloudMapper.toCloudMedia(message(id = 0, content = aPhoto()), 7))
        assertNull(TdCloudMapper.toCloudMedia(message(id = 19, date = 0, content = aPhoto()), 7))
        assertNull(TdCloudMapper.toCloudMedia(message(id = 20, content = TdApi.MessageAudio()), 7))
    }

    /** A real SHA-256 of a real input ("abc"), so a length-64 digest is not accidentally faked here. */
    private val DIGEST = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private fun aPhoto() = TdApi.MessagePhoto().apply {
        photo = TdApi.Photo().apply { sizes = arrayOf(photoSize("x", 10, 10, "P")) }
    }
}
