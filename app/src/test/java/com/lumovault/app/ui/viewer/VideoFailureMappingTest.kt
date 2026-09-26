package com.lumovault.app.ui.viewer

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which of Media3's error codes LumoVault calls what.
 *
 * The UI shows one sentence for every failure, so a mistake here is invisible on screen and shows up only in
 * a log line — which is precisely the thing somebody reads when a device nobody has says a video would not
 * start. So the ranges are pinned at both ends, because the risk is not that a code means the wrong thing
 * today, it is that Media3 adds one outside a range tomorrow and it silently lands in the default.
 *
 * These are the numbers `PlaybackException` defines, named rather than repeated: if a value ever changes, the
 * mapping follows the name, and a test written against a literal would only have proved that both files
 * disagreed with the library in the same way.
 */
class VideoFailureMappingTest {
    @Test
    fun aFileThatIsGoneOrNoLongerReadableIsNotReportedAsACorruptClip() {
        assertEquals(VideoFailureKind.SourceUnopenable, videoFailureKindOf(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertEquals(VideoFailureKind.SourceUnopenable, videoFailureKindOf(PlaybackException.ERROR_CODE_IO_NO_PERMISSION))
    }

    @Test
    fun loadingFailuresAcrossTheWholeIoRangeAreOneCategory() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_DISCONNECTED,
        )) {
            assertEquals("code $code", VideoFailureKind.IoFailure, videoFailureKindOf(code))
        }
    }

    @Test
    fun aContainerTheExtractorsCannotReadIsUnsupportedMediaRatherThanABrokenDecoder() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_NOT_SUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )) {
            assertEquals("code $code", VideoFailureKind.UnsupportedMedia, videoFailureKindOf(code))
        }
    }

    @Test
    fun aDecoderOrAudioTrackThatRefusedToStartIsAFailureOfTheSameKind() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        )) {
            assertEquals("code $code", VideoFailureKind.DecoderFailure, videoFailureKindOf(code))
        }
    }

    /**
     * Everything left over. A local file in a MediaStore collection cannot be DRM-restricted, region-blocked
     * or behind a premium account, so those get no word of their own — they are a playback failure, and the
     * person sees the same sentence either way.
     */
    @Test
    fun nothingElseGetsItsOwnName() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            PlaybackException.ERROR_CODE_INVALID_STATE,
            PlaybackException.ERROR_CODE_BAD_VALUE,
            PlaybackException.ERROR_CODE_NOT_AVAILABLE_IN_REGION,
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
            PlaybackException.ERROR_CODE_END_OF_PLAYLIST,
            PlaybackException.CUSTOM_ERROR_CODE_BASE,
        )) {
            assertEquals("code $code", VideoFailureKind.PlaybackFailed, videoFailureKindOf(code))
        }
    }
}
