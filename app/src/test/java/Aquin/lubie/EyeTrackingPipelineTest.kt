package Aquin.lubie

import Aquin.lubie.tracking.model.Pupil2DDebugInfo
import Aquin.lubie.tracking.model.Pupil2DParams
import Aquin.lubie.tracking.model.PupilDatum2D
import Aquin.lubie.tracking.model.PupilEllipse2D
import Aquin.lubie.tracking.model.PupilObservation2D
import Aquin.lubie.tracking.model.PupilQualityState
import Aquin.lubie.tracking.model.RecordedFrameMetadata
import Aquin.lubie.tracking.model.RecordedSessionInfo
import Aquin.lubie.tracking.model.RoiBounds
import Aquin.lubie.tracking.model.TrackingRoiPhase
import Aquin.lubie.tracking.pipeline.EyeSessionStore
import Aquin.lubie.tracking.pipeline.PupilQualityClassifier
import Aquin.lubie.tracking.pipeline.RoiTrackingStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EyeTrackingPipelineTest {

    /**
     * Summary: Verifies ROI tracking moves from init to follow and then to recovery.
     * @param none No parameters.
     * @return Unit.
     */
    @Test
    fun roiTracker_transitionsFromInitToFollowToRecovery() {
        val tracker = RoiTrackingStateMachine()
        assertEquals(TrackingRoiPhase.INIT, tracker.phase())

        val validObservation = buildObservation(
            frameId = 1L,
            pupil = buildPupil(confidence = 0.88, centerX = 80.0, centerY = 64.0),
            quality = PupilQualityState.VALID,
            debug = Pupil2DDebugInfo(
                effectiveRoi = RoiBounds.fullFrame(160, 120),
                supportPixelCount = 24,
                finalEdgeCount = 32,
                darkPixelCount = 30,
                roiPixelCount = 160 * 120,
            ),
        )
        tracker.update(validObservation)
        assertEquals(TrackingRoiPhase.FOLLOW, tracker.phase())

        val followRoi = tracker.nextRequestedRoi(160, 120)
        assertTrue(followRoi.width() < 160)
        assertTrue(followRoi.height() < 120)

        repeat(5) { index ->
            tracker.update(
                buildObservation(
                    frameId = (index + 2).toLong(),
                    pupil = null,
                    quality = PupilQualityState.LOST_TRACK,
                    debug = Pupil2DDebugInfo(
                        effectiveRoi = followRoi,
                        roiPixelCount = followRoi.width() * followRoi.height(),
                    ),
                ),
            )
        }
        assertEquals(TrackingRoiPhase.RECOVERY, tracker.phase())
        assertEquals(RoiBounds.fullFrame(160, 120), tracker.nextRequestedRoi(160, 120))
    }

    /**
     * Summary: Verifies the default quality classifier rules.
     * @param none No parameters.
     * @return Unit.
     */
    @Test
    fun qualityClassifier_returnsExpectedStates() {
        val classifier = PupilQualityClassifier()
        val params = Pupil2DParams(pupilSizeMin = 20)
        val validDebug = Pupil2DDebugInfo(
            effectiveRoi = RoiBounds(0, 0, 99, 99),
            supportPixelCount = 18,
            finalEdgeCount = 28,
            darkPixelCount = 40,
            roiPixelCount = 10_000,
        )
        val validPupil = buildPupil(confidence = 0.72)
        assertEquals(
            PupilQualityState.VALID,
            classifier.classify(validPupil, validDebug, blinkCandidateStreak = 0, params = params),
        )

        val lowConfidencePupil = buildPupil(confidence = 0.30)
        assertEquals(
            PupilQualityState.LOW_CONFIDENCE,
            classifier.classify(lowConfidencePupil, validDebug, blinkCandidateStreak = 0, params = params),
        )

        val saturatedDebug = validDebug.copy(saturatedPixelCount = 2_500)
        assertEquals(
            PupilQualityState.SATURATED,
            classifier.classify(null, saturatedDebug, blinkCandidateStreak = 0, params = params),
        )

        val blinkDebug = validDebug.copy(darkPixelCount = 0)
        assertEquals(
            PupilQualityState.BLINK,
            classifier.classify(null, blinkDebug, blinkCandidateStreak = 2, params = params),
        )
    }

    /**
     * Summary: Verifies the 2D observation contract keeps timestamp and size fields intact.
     * @param none No parameters.
     * @return Unit.
     */
    @Test
    fun pupilObservation_preservesCoreContractFields() {
        val pupil = buildPupil(confidence = 0.66, centerX = 48.0, centerY = 36.0)
        val observation = buildObservation(
            frameId = 42L,
            timestampNs = 123_456_789L,
            frameWidth = 128,
            frameHeight = 96,
            pupil = pupil,
            quality = PupilQualityState.VALID,
            debug = Pupil2DDebugInfo(
                effectiveRoi = RoiBounds(16, 8, 112, 88),
                supportPixelCount = 14,
                finalEdgeCount = 21,
                darkPixelCount = 18,
                roiPixelCount = 97 * 81,
            ),
        )

        assertEquals(42L, observation.frameId)
        assertEquals(123_456_789L, observation.captureTimestampNs)
        assertEquals(128, observation.frameWidth)
        assertEquals(96, observation.frameHeight)
        assertEquals(PupilQualityState.VALID, observation.quality)
        assertTrue(observation.pupil != null)
        assertEquals(48.0, observation.pupil!!.ellipse.centerX, 1e-6)
    }

    /**
     * Summary: Verifies session and metadata JSON helpers round-trip without data loss.
     * @param none No parameters.
     * @return Unit.
     */
    @Test
    fun eyeSessionStore_jsonHelpersRoundTrip() {
        val metadata = RecordedFrameMetadata(
            frameId = 7L,
            timestampNs = 900_000_000L,
            fileName = "000007.png",
            quality = PupilQualityState.LOW_CONFIDENCE,
            confidence = 0.41,
        )
        val parsedMetadata = EyeSessionStore.parseMetadataFromString(EyeSessionStore.metadataToJsonLine(metadata))
        assertEquals(metadata, parsedMetadata)

        val sessionInfo = RecordedSessionInfo(
            sessionId = "20260326_120000",
            sourceTag = "camera_front",
            frameWidth = 640,
            frameHeight = 480,
            eyeId = 0,
            createdAtEpochMs = 1_700_000_000_000L,
            sessionDirPath = "C:/tmp/session",
            frames = listOf(metadata),
        )
        val parsedSession = EyeSessionStore.parseSessionInfoFromString(
            jsonText = EyeSessionStore.sessionInfoToJsonString(sessionInfo),
            sessionDirPath = sessionInfo.sessionDirPath,
            frames = sessionInfo.frames,
        )
        assertEquals(sessionInfo.sessionId, parsedSession.sessionId)
        assertEquals(sessionInfo.sourceTag, parsedSession.sourceTag)
        assertEquals(sessionInfo.frameWidth, parsedSession.frameWidth)
        assertEquals(sessionInfo.frameHeight, parsedSession.frameHeight)
        assertEquals(sessionInfo.eyeId, parsedSession.eyeId)
        assertEquals(sessionInfo.createdAtEpochMs, parsedSession.createdAtEpochMs)
        assertEquals(sessionInfo.frames, parsedSession.frames)
    }

    private fun buildObservation(
        frameId: Long,
        timestampNs: Long = frameId * 1_000_000L,
        frameWidth: Int = 160,
        frameHeight: Int = 120,
        pupil: PupilDatum2D?,
        quality: PupilQualityState,
        debug: Pupil2DDebugInfo,
    ): PupilObservation2D {
        return PupilObservation2D(
            frameId = frameId,
            captureTimestampNs = timestampNs,
            eyeId = 0,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            pupil = pupil,
            quality = quality,
            debug = debug,
        )
    }

    private fun buildPupil(
        confidence: Double,
        centerX: Double = 60.0,
        centerY: Double = 50.0,
    ): PupilDatum2D {
        return PupilDatum2D(
            eyeId = 0,
            timestamp = 0.0,
            method = "test",
            normPosX = 0.5,
            normPosY = 0.5,
            diameterPx = 28.0,
            confidence = confidence,
            ellipse = PupilEllipse2D(
                centerX = centerX,
                centerY = centerY,
                axisMinor = 22.0,
                axisMajor = 28.0,
                angleDeg = 5.0,
            ),
        )
    }
}
