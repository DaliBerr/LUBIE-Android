package Aquin.lubie

import Aquin.lubie.remote.GazeSample
import Aquin.lubie.remote.GazeSampleJson
import Aquin.lubie.remote.RemoteMarkerEvent
import Aquin.lubie.remote.RemoteRecordedFrameMetadata
import Aquin.lubie.remote.RemoteReplaySource
import Aquin.lubie.remote.RemoteSessionInfo
import Aquin.lubie.remote.RemoteSessionStore
import Aquin.lubie.remote.RemoteSourceKind
import Aquin.lubie.tracking.model.ReplaySpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTrackingPipelineTest {

    /**
     * Summary: Verifies the UDP gaze parser keeps all expected fields from the full payload.
     * @param none No parameters.
     * @return Unit.
     */
    @Test
    fun gazeSampleJson_parsesFullPayload() {
        val sample = GazeSampleJson.parseOrNull(
            """
            {
              "eye_timestamp_ns": 123456789012345,
              "fpv_timestamp_ns": 123456789045678,
              "screen_uv": [0.43, 0.58],
              "fpv_output_mode": "rtsp",
              "recording_active": false,
              "tracking_valid": true,
              "feature_valid": true,
              "feature_mode": "iris_only",
              "calibration_state": "completed",
              "calibration_step": "done",
              "calibrated": true,
              "calibration_target_uv": null,
              "sync_skew_ms": 5.2,
              "sync_stale": false,
              "eye_stale": false,
              "fpv_stale": false,
              "fps": 27.8,
              "inference_ms": 11.4,
              "status_message": "Nine-point calibration completed."
            }
            """.trimIndent(),
        )

        requireNotNull(sample)
        assertEquals(123456789012345L, sample.eyeTimestampNs)
        assertEquals(123456789045678L, sample.fpvTimestampNs)
        assertEquals(0.43, sample.screenUvX!!, 1e-6)
        assertEquals(0.58, sample.screenUvY!!, 1e-6)
        assertEquals("rtsp", sample.fpvOutputMode)
        assertEquals(false, sample.recordingActive)
        assertTrue(sample.trackingValid)
        assertEquals(true, sample.featureValid)
        assertEquals("completed", sample.calibrationState)
        assertEquals(5.2, sample.syncSkewMs!!, 1e-6)
        assertEquals(27.8, sample.fps!!, 1e-6)
        assertEquals(11.4, sample.inferenceMs!!, 1e-6)
        assertEquals("Nine-point calibration completed.", sample.statusMessage)
        assertTrue(sample.isDrawable())
    }

    /**
     * Summary: Verifies null gaze coordinates remain null and suppress overlay rendering.
     * @param none No parameters.
     * @return Unit.
     */
    @Test
    fun gazeSampleJson_handlesNullScreenUv() {
        val sample = GazeSampleJson.parseOrNull(
            """
            {
              "tracking_valid": true,
              "screen_uv": null,
              "status_message": "waiting"
            }
            """.trimIndent(),
        )

        requireNotNull(sample)
        assertNull(sample.screenUvX)
        assertNull(sample.screenUvY)
        assertFalse(sample.isDrawable())
        assertEquals("waiting", sample.statusMessage)
    }

    /**
     * Summary: Verifies remote session JSON helpers round-trip metadata, gaze, and marker data.
     * @param none No parameters.
     * @return Unit.
     */
    @Test
    fun remoteSessionStore_jsonHelpersRoundTrip() {
        val gazeSample = GazeSample(
            screenUvX = 0.40,
            screenUvY = 0.60,
            trackingValid = true,
            fps = 30.0,
            statusMessage = "ok",
        )
        val metadata = RemoteRecordedFrameMetadata(
            frameId = 12L,
            timestampNs = 1_000_000_000L,
            fileName = "000012.jpg",
            width = 1280,
            height = 720,
            sourceKind = RemoteSourceKind.RTSP,
            gazeSample = gazeSample,
        )
        val marker = RemoteMarkerEvent(timestampNs = 1_050_000_000L, frameId = 12L)
        val session = RemoteSessionInfo(
            sessionId = "20260421_120000",
            sourceKind = RemoteSourceKind.RTSP,
            sourceLabel = "rtsp://demo",
            frameWidth = 1280,
            frameHeight = 720,
            createdAtEpochMs = 1_700_000_000_000L,
            sessionDirPath = "C:/tmp/remote",
            rtspUrl = "rtsp://demo",
            demoSourceDisplayName = null,
            udpPort = 5005,
            frames = listOf(metadata),
            markers = listOf(marker),
        )

        val parsedMetadata = RemoteSessionStore.parseMetadataFromString(
            RemoteSessionStore.metadataToJsonLine(metadata),
        )
        assertEquals(metadata.frameId, parsedMetadata.frameId)
        assertEquals(metadata.timestampNs, parsedMetadata.timestampNs)
        assertEquals(metadata.fileName, parsedMetadata.fileName)
        assertEquals(metadata.width, parsedMetadata.width)
        assertEquals(metadata.height, parsedMetadata.height)
        assertEquals(metadata.sourceKind, parsedMetadata.sourceKind)
        requireNotNull(parsedMetadata.gazeSample)
        assertEquals(0.40, parsedMetadata.gazeSample!!.screenUvX!!, 1e-6)
        assertEquals("ok", parsedMetadata.gazeSample!!.statusMessage)

        val parsedMarker = RemoteSessionStore.parseMarkerFromString(
            RemoteSessionStore.markerToJsonLine(marker),
        )
        assertEquals(marker, parsedMarker)

        val parsedSession = RemoteSessionStore.parseSessionInfoFromString(
            jsonText = RemoteSessionStore.sessionInfoToJsonString(session),
            sessionDirPath = session.sessionDirPath,
            frames = session.frames,
            markers = session.markers,
        )
        assertEquals(session.sessionId, parsedSession.sessionId)
        assertEquals(session.sourceKind, parsedSession.sourceKind)
        assertEquals(session.sourceLabel, parsedSession.sourceLabel)
        assertEquals(session.frameWidth, parsedSession.frameWidth)
        assertEquals(session.frameHeight, parsedSession.frameHeight)
        assertEquals(session.createdAtEpochMs, parsedSession.createdAtEpochMs)
        assertEquals(session.rtspUrl, parsedSession.rtspUrl)
        assertEquals(session.udpPort, parsedSession.udpPort)
        assertEquals(session.frames, parsedSession.frames)
        assertEquals(session.markers, parsedSession.markers)
    }

    /**
     * Summary: Verifies replay helper math keeps 30fps timing and seek positioning stable.
     * @param none No parameters.
     * @return Unit.
     */
    @Test
    fun remoteReplaySource_helperMathMatches30fpsAndSeekBehavior() {
        val timestampsNs = listOf(
            1_000_000_000L,
            1_033_333_333L,
            1_066_666_666L,
            1_100_000_000L,
        )

        assertEquals(
            33L,
            RemoteReplaySource.frameDelayMs(
                currentTimestampNs = timestampsNs[0],
                nextTimestampNs = timestampsNs[1],
                speed = ReplaySpeed.NORMAL,
            ),
        )
        assertEquals(
            133L,
            RemoteReplaySource.frameDelayMs(
                currentTimestampNs = timestampsNs[0],
                nextTimestampNs = timestampsNs[1],
                speed = ReplaySpeed.QUARTER,
            ),
        )
        assertEquals(
            0,
            RemoteReplaySource.nearestFrameIndexForProgress(
                timestampsNs = timestampsNs,
                progress = 0,
                max = 1000,
            ),
        )
        assertEquals(
            2,
            RemoteReplaySource.nearestFrameIndexForProgress(
                timestampsNs = timestampsNs,
                progress = 650,
                max = 1000,
            ),
        )
        assertEquals(
            3,
            RemoteReplaySource.nearestFrameIndexForProgress(
                timestampsNs = timestampsNs,
                progress = 1000,
                max = 1000,
            ),
        )
    }

    /**
     * Summary: Verifies session duration is based on the first and last recorded timestamps.
     * @param none No parameters.
     * @return Unit.
     */
    @Test
    fun remoteSessionInfo_durationUsesFirstAndLastFrame() {
        val frames = listOf(
            RemoteRecordedFrameMetadata(
                frameId = 0L,
                timestampNs = 10L,
                fileName = "000000.jpg",
                width = 100,
                height = 100,
                sourceKind = RemoteSourceKind.DEMO_VIDEO,
                gazeSample = null,
            ),
            RemoteRecordedFrameMetadata(
                frameId = 1L,
                timestampNs = 70L,
                fileName = "000001.jpg",
                width = 100,
                height = 100,
                sourceKind = RemoteSourceKind.DEMO_VIDEO,
                gazeSample = null,
            ),
        )
        val session = RemoteSessionInfo(
            sessionId = "demo",
            sourceKind = RemoteSourceKind.DEMO_VIDEO,
            sourceLabel = "demo.mp4",
            frameWidth = 100,
            frameHeight = 100,
            createdAtEpochMs = 0L,
            sessionDirPath = "C:/tmp/demo",
            rtspUrl = null,
            demoSourceDisplayName = "demo.mp4",
            udpPort = null,
            frames = frames,
            markers = emptyList(),
        )

        assertEquals(10L, session.firstTimestampNs())
        assertEquals(70L, session.lastTimestampNs())
        assertEquals(60L, session.durationNs())
    }
}
