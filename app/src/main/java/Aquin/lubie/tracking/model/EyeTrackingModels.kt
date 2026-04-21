package Aquin.lubie.tracking.model

import Aquin.lubie.tracking.segmentation.SegmentationUiState
import android.graphics.Bitmap

/**
 * Summary: Stores per-frame detector debug values for the 2D pipeline.
 * @param effectiveRoi Effective ROI used for the current detection pass.
 * @param supportPixelCount Number of support pixels on the final ellipse.
 * @param finalEdgeCount Number of final edges used for the final confidence.
 * @param darkPixelCount Number of dark-mask pixels in the effective ROI.
 * @param coarseDetectionUsed Whether coarse ROI narrowing changed the requested ROI.
 * @param usedStrongPrior Whether the final result came from the strong prior path.
 * @param thresholdLowSpike Lowest histogram spike index.
 * @param thresholdHighSpike Highest histogram spike index.
 * @param roiPixelCount Total number of pixels inside the effective ROI.
 * @param saturatedPixelCount Number of saturated pixels inside the effective ROI.
 * @return Immutable debug snapshot.
 */
data class Pupil2DDebugInfo(
    val effectiveRoi: RoiBounds,
    val supportPixelCount: Int = 0,
    val finalEdgeCount: Int = 0,
    val darkPixelCount: Int = 0,
    val coarseDetectionUsed: Boolean = false,
    val usedStrongPrior: Boolean = false,
    val thresholdLowSpike: Int = 0,
    val thresholdHighSpike: Int = 0,
    val roiPixelCount: Int = 0,
    val saturatedPixelCount: Int = 0,
) {
    /**
     * Summary: Calculates dark-mask ratio inside the ROI.
     * @param none No parameters.
     * @return Dark pixel ratio in [0, 1].
     */
    fun darkPixelRatio(): Double {
        return if (roiPixelCount <= 0) 0.0 else darkPixelCount.toDouble() / roiPixelCount.toDouble()
    }

    /**
     * Summary: Calculates saturated pixel ratio inside the ROI.
     * @param none No parameters.
     * @return Saturated pixel ratio in [0, 1].
     */
    fun saturatedPixelRatio(): Double {
        return if (roiPixelCount <= 0) 0.0 else saturatedPixelCount.toDouble() / roiPixelCount.toDouble()
    }
}

/**
 * Summary: Represents the quality state of one 2D pupil observation.
 * @return Enum used by later gaze stages and debug UI.
 */
enum class PupilQualityState {
    VALID,
    LOW_CONFIDENCE,
    BLINK,
    LOST_TRACK,
    SATURATED,
}

/**
 * Summary: Stores the 2D observation contract for later gaze mapping.
 * @param frameId Monotonic frame id from the current source.
 * @param captureTimestampNs Source timestamp in nanoseconds.
 * @param eyeId Eye index.
 * @param frameWidth Frame width in pixels.
 * @param frameHeight Frame height in pixels.
 * @param pupil Raw pupil detector result when available.
 * @param quality Quality label for the current observation.
 * @param debug Per-frame detector debug values.
 * @return Immutable 2D observation.
 */
data class PupilObservation2D(
    val frameId: Long,
    val captureTimestampNs: Long,
    val eyeId: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    val pupil: PupilDatum2D?,
    val quality: PupilQualityState,
    val debug: Pupil2DDebugInfo,
)

/**
 * Summary: Stores one grayscale frame coming from a pluggable source.
 * @param frameId Monotonic frame id from the current source.
 * @param timestampNs Source timestamp in nanoseconds.
 * @param width Frame width in pixels.
 * @param height Frame height in pixels.
 * @param grayMat Frame grayscale image owned by the receiver.
 * @param sourceTag Human-readable source identifier.
 * @return Immutable frame wrapper.
 */
data class EyeFrame(
    val frameId: Long,
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val grayMat: org.opencv.core.Mat,
    val sourceTag: String,
)

/**
 * Summary: Identifies the active source mode.
 * @return Enum used by controller and UI.
 */
enum class EyeSourceMode {
    LIVE,
    REPLAY,
}

/**
 * Summary: Stores replay speed options.
 * @param label Human-readable label.
 * @param factor Playback speed factor.
 * @return Immutable replay speed option.
 */
enum class ReplaySpeed(val label: String, val factor: Double) {
    QUARTER("0.25x", 0.25),
    NORMAL("1x", 1.0),
}

/**
 * Summary: Tracks the current ROI state machine phase.
 * @return Enum used by ROI tracking and tests.
 */
enum class TrackingRoiPhase {
    INIT,
    FOLLOW,
    RECOVERY,
}

/**
 * Summary: Stores metadata for one recorded frame.
 * @param frameId Frame id in the recorded session.
 * @param timestampNs Recorded timestamp in nanoseconds.
 * @param fileName Frame image filename.
 * @param quality Recorded quality label.
 * @param confidence Recorded confidence if available.
 * @return Immutable metadata line.
 */
data class RecordedFrameMetadata(
    val frameId: Long,
    val timestampNs: Long,
    val fileName: String,
    val quality: PupilQualityState,
    val confidence: Double?,
)

/**
 * Summary: Stores one recorded session descriptor.
 * @param sessionId Session identifier.
 * @param sourceTag Source label used during recording.
 * @param frameWidth Frame width in pixels.
 * @param frameHeight Frame height in pixels.
 * @param eyeId Eye index.
 * @param createdAtEpochMs Session creation time in milliseconds.
 * @param sessionDirPath Absolute session directory path.
 * @param frames Recorded frame metadata.
 * @return Immutable recorded session descriptor.
 */
data class RecordedSessionInfo(
    val sessionId: String,
    val sourceTag: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val eyeId: Int,
    val createdAtEpochMs: Long,
    val sessionDirPath: String,
    val frames: List<RecordedFrameMetadata>,
)

/**
 * Summary: Stores the render state emitted by the tracking controller.
 * @param mode Current source mode.
 * @param sourceTag Current source label.
 * @param observation Latest 2D observation.
 * @param replayBitmap Replay preview bitmap when replay is active.
 * @param isRecording Whether the live source is being recorded.
 * @param replayPlaying Whether replay is currently playing.
 * @param replaySpeed Current replay speed.
 * @param replayFrameIndex Current replay frame index.
 * @param replayFrameCount Total number of replay frames.
 * @param latestSessionId Latest recorded session identifier if available.
 * @param debugOverlayEnabled Whether detailed debug text should be shown.
 * @param detection2dEnabled Whether the classic 2D detector is enabled.
 * @param mirrorHorizontally Whether the overlay should mirror X coordinates.
 * @param manualSegmentationRoi User-selected segmentation ROI if available.
 * @param segmentationUiState Latest segmentation debug state.
 * @return Immutable render state for the page.
 */
data class EyeTrackingRenderState(
    val mode: EyeSourceMode,
    val sourceTag: String,
    val observation: PupilObservation2D,
    val replayBitmap: Bitmap? = null,
    val isRecording: Boolean = false,
    val replayPlaying: Boolean = false,
    val replaySpeed: ReplaySpeed = ReplaySpeed.NORMAL,
    val replayFrameIndex: Int = 0,
    val replayFrameCount: Int = 0,
    val latestSessionId: String? = null,
    val debugOverlayEnabled: Boolean = true,
    val detection2dEnabled: Boolean = true,
    val mirrorHorizontally: Boolean = false,
    val manualSegmentationRoi: RoiBounds? = null,
    val segmentationUiState: SegmentationUiState = SegmentationUiState(),
)
