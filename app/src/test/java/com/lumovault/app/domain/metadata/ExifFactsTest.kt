package com.lumovault.app.domain.metadata

import com.lumovault.app.domain.model.MediaLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What EXIF means, rather than what it contains.
 *
 * Every assertion here is about a unit or a placeholder, because that is where an EXIF reader goes wrong
 * quietly: the values arrive as numbers either way, and a wrong one is drawn as a fact. The conversions are
 * pure functions in front of a tag lookup, so all of this is testable without a device, a file, or a
 * camera.
 */
class ExifFactsTest {

    @Test
    fun anApexIsNotSecondsAndTheTwoAreNotInterchangeable() {
        // `ExposureTime` is seconds; `ShutterSpeedValue` is the base-2 logarithm of it. Reading the apex as a
        // duration would print "8 s" under a daylight handheld shot — the most inviting mistake this block
        // of tags offers, and one no test could catch on screen because the number looks plausible.
        assertEquals(0.004, requireNotNull(ExifFacts(exposureTimeSeconds = 0.004).shutterSeconds), 0.0)
        assertEquals(1.0 / 256.0, requireNotNull(ExifFacts(shutterValueApex = 8.0).shutterSeconds), 1e-12)

        // A negative apex is a long exposure: apex -3 is eight seconds, not a third of one.
        assertEquals(8.0, requireNotNull(ExifFacts(shutterValueApex = -3.0).shutterSeconds), 1e-12)
    }

    @Test
    fun theDurationWinsWhenBothAreWritten() {
        val both = ExifFacts(exposureTimeSeconds = 0.02, shutterValueApex = 5.6)
        assertEquals(0.02, requireNotNull(both.shutterSeconds), 0.0)
    }

    @Test
    fun zeroMeansNotRecordedForEveryFieldThatUsesItAsASentinel() {
        val zeros = ExifFacts(
            focalLengthMm = 0.0,
            fNumber = 0.0,
            isoSpeed = 0,
            exposureTimeSeconds = 0.0,
        )
        assertNull(zeros.focalLength)
        assertNull(zeros.aperture)
        assertNull(zeros.shutterSeconds)
        assertNull("a file that recorded nothing at all is not metadata", zeros.toMediaMetadata())
    }

    @Test
    fun anApexBeyondAnySensorIsTreatedAsDamageRatherThanConverted() {
        // 2^32 s is over a century and 2^-64 s is below any clock, so a value outside that range is corrupt
        // data rather than a long exposure, and the panel shows nothing instead of it.
        assertNull(ExifFacts(shutterValueApex = 128.0).shutterSeconds)
        assertNull(ExifFacts(shutterValueApex = -200.0).shutterSeconds)
    }

    @Test
    fun anExactZeroPositionIsNotAPlaceInTheOcean() {
        assertNull(
            "broken GPS writers emit 0/0, and a marker in the Gulf of Guinea is an invented location",
            ExifFacts(latitude = 0.0, longitude = 0.0).location,
        )
        assertNull("half a coordinate is not a position", ExifFacts(latitude = 52.5).location)
        assertNull("and neither is the other half", ExifFacts(longitude = 13.4).location)
        assertEquals(
            MediaLocation(52.5, 13.4),
            ExifFacts(latitude = 52.5, longitude = 13.4).location,
        )
    }

    @Test
    fun aPositionOutsideThePossibleRangeIsRefused() {
        assertNull(ExifFacts(latitude = 95.0, longitude = 13.4).location)
        assertNull(ExifFacts(latitude = 52.5, longitude = -190.0).location)
    }

    @Test
    fun blankAndPaddedTextBecomeAbsence() {
        assertNull(
            "whitespace is not a camera, and an empty attribute must not become a drawn label",
            ExifFacts(make = "  ", model = "", lensModel = "   ").toMediaMetadata(),
        )

        val trimmed = requireNotNull(ExifFacts(make = "  Canon  ", model = " EOS R5 ").toMediaMetadata())
        assertEquals("Canon", trimmed.cameraMake)
        assertEquals("EOS R5", trimmed.cameraModel)
        assertEquals("Canon EOS R5", trimmed.cameraLabel)
    }

    @Test
    fun aModelThatAlreadyNamesItsMakeIsNotPrefixedAgain() {
        val apple = requireNotNull(ExifFacts(make = "Apple", model = "iPhone 15 Pro").toMediaMetadata())
        assertEquals("iPhone 15 Pro", apple.cameraLabel)

        val sameTwice = requireNotNull(ExifFacts(make = "Google", model = "Google").toMediaMetadata())
        assertEquals("Google", sameTwice.cameraLabel)
    }

    @Test
    fun aCameraNameWithoutAPositionIsStillMetadata() {
        val onlyCamera = requireNotNull(ExifFacts(model = "Pixel 9").toMediaMetadata())
        assertNull(onlyCamera.location)
        assertEquals(true, onlyCamera.hasCameraInformation)
    }

    @Test
    fun seaLevelIsAnAltitudeAndNotAnAbsence() {
        // The reader has already made the only honest absence check available to it — whether the tag is in
        // the file at all — so a zero that arrives here means what it says. This is the one field where a 0
        // has to survive.
        val atSea = requireNotNull(ExifFacts(model = "Nikon", altitudeMeters = 0.0).toMediaMetadata())
        assertEquals(0.0, requireNotNull(atSea.altitudeMeters), 0.0)
    }
}
