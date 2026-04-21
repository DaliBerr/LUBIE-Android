package Aquin.lubie.remote

import Aquin.lubie.tracking.model.ReplaySpeed
import android.graphics.Bitmap

/**
 * Summary: Identifies the current remote video source kind.
 * @return Enum used by live and replay flows.
 */
enum class RemoteSourceKind {
    RTSP,
    DEMO_VIDEO,
}

/**
 * Summary: Identifies whether the UI is showing live playback or recorded replay.
 * @return Enum used by the remote controller.
 */
enum class RemoteMode {
    LIVE,
    REPLAY,
}

/**
 * Summary: Stores one gaze sample received from the Raspberry Pi UDP stream.
 * @param eyeTimestampNs Eye camera timestamp in nanoseconds.
 * @param fpvTimestampNs FPV timestamp in nanoseconds.
 * @param screenUvX Normalized screen X coordinate.
 * @param screenUvY Normalized screen Y coordinate.
 * @param fpvOutputMode Current FPV output mode.
 * @param recordingActive Whether the remote side is recording.
 * @param trackingValid Whether the gaze output is valid.
 * @param featureValid Whether feature extraction is valid.
 * @param featureMode Current feature mode string.
 * @param calibrationState Current calibration state string.
 * @param calibrationStep Current calibration step string.
 * @param calibrated Whether calibration is complete.
 * @param calibrationTargetUvX Current calibration target X coordinate.
 * @param calibrationTargetUvY Current calibration target Y coordinate.
 * @param syncSkewMs Current clock skew in milliseconds.
 * @param syncStale Whether sync data is stale.
 * @param eyeStale Whether eye data is stale.
 * @param fpvStale Whether FPV data is stale.
 * @param fps Current processing FPS.
 * @param inferenceMs Current inference latency in milliseconds.
 * @param statusMessage Human-readable status message.
 * @return Immutable gaze snapshot.
 */
data class GazeSample(
    val eyeTimestampNs: Long? = null,
    val fpvTimestampNs: Long? = null,
    val screenUvX: Double? = null,
    val screenUvY: Double? = null,
    val fpvOutputMode: String? = null,
    val recordingActive: Boolean? = null,
    val trackingValid: Boolean = false,
    val featureValid: Boolean? = null,
    val featureMode: String? = null,
    val calibrationState: String? = null,
    val calibrationStep: String? = null,
    val calibrated: Boolean? = null,
    val calibrationTargetUvX: Double? = null,
    val calibrationTargetUvY: Double? = null,
    val syncSkewMs: Double? = null,
    val syncStale: Boolean? = null,
    val eyeStale: Boolean? = null,
    val fpvStale: Boolean? = null,
    val fps: Double? = null,
    val inferenceMs: Double? = null,
    val statusMessage: String? = null,
) {
    /**
     * Summary: Returns whether this sample can be rendered as a screen-space gaze point.
     * @param none No parameters.
     * @return True when the overlay dot should be visible.
     */
    fun isDrawable(): Boolean {
        return trackingValid && screenUvX != null && screenUvY != null
    }
}

/**
 * Summary: Stores one recorded frame entry for remote replay.
 * @param frameId Monotonic frame identifier inside the session.
 * @param timestampNs Frame timestamp in nanoseconds.
 * @param fileName Stored JPEG file name.
 * @param width Stored frame width.
 * @param height Stored frame height.
 * @param sourceKind Original source kind that produced the frame.
 * @param gazeSample Gaze sample snapshot attached to the frame when available.
 * @return Immutable frame metadata.
 */
data class RemoteRecordedFrameMetadata(
    val frameId: Long,
    val timestampNs: Long,
    val fileName: String,
    val width: Int,
    val height: Int,
    val sourceKind: RemoteSourceKind,
    val gazeSample: GazeSample?,
)

/**
 * Summary: Stores one marker event recorded during live capture.
 * @param timestampNs Marker timestamp in nanoseconds.
 * @param frameId Frame identifier nearest to the marker.
 * @return Immutable marker metadata.
 */
data class RemoteMarkerEvent(
    val timestampNs: Long,
    val frameId: Long,
)

/**
 * Summary: Stores one remote session descriptor and its recorded contents.
 * @param sessionId Session identifier.
 * @param sourceKind Source kind used during recording.
 * @param sourceLabel Human-readable source label.
 * @param frameWidth Recorded frame width.
 * @param frameHeight Recorded frame height.
 * @param createdAtEpochMs Session creation time in milliseconds.
 * @param sessionDirPath Absolute session directory path.
 * @param rtspUrl Recorded RTSP source when applicable.
 * @param demoSourceDisplayName Recorded demo file display name when applicable.
 * @param udpPort UDP port used during recording when applicable.
 * @param frames Recorded frame metadata list.
 * @param markers Recorded marker metadata list.
 * @return Immutable remote session descriptor.
 */
data class RemoteSessionInfo(
    val sessionId: String,
    val sourceKind: RemoteSourceKind,
    val sourceLabel: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val createdAtEpochMs: Long,
    val sessionDirPath: String,
    val rtspUrl: String? = null,
    val demoSourceDisplayName: String? = null,
    val udpPort: Int? = null,
    val frames: List<RemoteRecordedFrameMetadata>,
    val markers: List<RemoteMarkerEvent>,
) {
    /**
     * Summary: Returns the first recorded timestamp or zero when empty.
     * @param none No parameters.
     * @return First frame timestamp in nanoseconds.
     */
    fun firstTimestampNs(): Long = frames.firstOrNull()?.timestampNs ?: 0L

    /**
     * Summary: Returns the last recorded timestamp or zero when empty.
     * @param none No parameters.
     * @return Last frame timestamp in nanoseconds.
     */
    fun lastTimestampNs(): Long = frames.lastOrNull()?.timestampNs ?: 0L

    /**
     * Summary: Calculates the recorded session duration.
     * @param none No parameters.
     * @return Session duration in nanoseconds.
     */
    fun durationNs(): Long = (lastTimestampNs() - firstTimestampNs()).coerceAtLeast(0L)
}

/**
 * Summary: Stores one decoded replay frame and its metadata.
 * @param bitmap Replay bitmap.
 * @param metadata Metadata entry for the replay frame.
 * @return Immutable replay frame payload.
 */
data class RemoteReplayFrame(
    val bitmap: Bitmap,
    val metadata: RemoteRecordedFrameMetadata,
)

/**
 * Summary: Stores the UI-facing render state for the remote streaming page.
 * @param mode Current page mode.
 * @param sourceKind Current source kind when available.
 * @param sourceLabel Human-readable source label.
 * @param statusMessage Human-readable status line.
 * @param isRecording Whether recording is armed or active.
 * @param recordingSessionActive Whether a session writer has started.
 * @param replayPlaying Whether replay playback is currently running.
 * @param replaySpeed Current replay speed.
 * @param replayFrameIndex Current replay frame index.
 * @param replayFrameCount Total replay frame count.
 * @param latestSessionId Latest recorded session identifier.
 * @param replayBitmap Replay bitmap when replay mode is active.
 * @param videoWidth Current source frame width.
 * @param videoHeight Current source frame height.
 * @param gazeSample Latest gaze sample for live or replay.
 * @param markerTimestampsNs Marker timestamps shown on the timeline.
 * @param currentTimestampNs Current live or replay timestamp.
 * @param sessionDurationNs Current session duration.
 * @param playerReady Whether live playback is ready.
 * @param demoSourceDisplayName Current demo source label when applicable.
 * @param rtspUrl Current RTSP URL when applicable.
 * @param udpPort Current UDP port when applicable.
 * @param lastRecordedFrameId Latest recorded frame id when available.
 * @param lastRecordedTimestampNs Latest recorded timestamp when available.
 * @return Immutable render state snapshot.
 */
data class RemoteRenderState(
    val mode: RemoteMode = RemoteMode.LIVE,
    val sourceKind: RemoteSourceKind? = null,
    val sourceLabel: String = "idle",
    val statusMessage: String = "Idle",
    val isRecording: Boolean = false,
    val recordingSessionActive: Boolean = false,
    val replayPlaying: Boolean = false,
    val replaySpeed: ReplaySpeed = ReplaySpeed.NORMAL,
    val replayFrameIndex: Int = 0,
    val replayFrameCount: Int = 0,
    val latestSessionId: String? = null,
    val replayBitmap: Bitmap? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val gazeSample: GazeSample? = null,
    val markerTimestampsNs: List<Long> = emptyList(),
    val currentTimestampNs: Long = 0L,
    val sessionDurationNs: Long = 0L,
    val playerReady: Boolean = false,
    val demoSourceDisplayName: String? = null,
    val rtspUrl: String? = null,
    val udpPort: Int? = null,
    val lastRecordedFrameId: Long? = null,
    val lastRecordedTimestampNs: Long? = null,
)
