package Aquin.lubie.tracking.pipeline

import Aquin.lubie.tracking.model.Pupil2DDebugInfo
import Aquin.lubie.tracking.model.Pupil2DParams
import Aquin.lubie.tracking.model.PupilDatum2D
import Aquin.lubie.tracking.model.PupilQualityState

private const val VALID_CONFIDENCE_MIN = 0.55
private const val LOW_CONFIDENCE_MIN = 0.25
private const val VALID_SUPPORT_PIXEL_MIN = 12
private const val VALID_FINAL_EDGE_MIN = 20
private const val SATURATED_PIXEL_RATIO_MAX = 0.20
private const val BLINK_STREAK_MIN = 2
private const val BLINK_DARK_RATIO_MAX = 0.01
private const val BLINK_AXIS_RATIO = 0.8

/**
 * Summary: Classifies per-frame pupil observation quality.
 * @param none No constructor parameters.
 * @return Stateless quality classifier.
 */
class PupilQualityClassifier {

    /**
     * Summary: Classifies one detector output into a quality state.
     * @param pupil Current pupil result if available.
     * @param debug Current detector debug info.
     * @param blinkCandidateStreak Current streak of blink-like frames.
     * @param params Current detector parameters.
     * @return Quality label for the current frame.
     */
    fun classify(
        pupil: PupilDatum2D?,
        debug: Pupil2DDebugInfo,
        blinkCandidateStreak: Int,
        params: Pupil2DParams,
    ): PupilQualityState {
        if (
            pupil != null &&
            pupil.confidence >= VALID_CONFIDENCE_MIN &&
            debug.supportPixelCount >= VALID_SUPPORT_PIXEL_MIN &&
            debug.finalEdgeCount >= VALID_FINAL_EDGE_MIN
        ) {
            return PupilQualityState.VALID
        }

        if (
            pupil != null &&
            pupil.confidence >= LOW_CONFIDENCE_MIN &&
            pupil.confidence < VALID_CONFIDENCE_MIN
        ) {
            return PupilQualityState.LOW_CONFIDENCE
        }

        if (debug.saturatedPixelRatio() > SATURATED_PIXEL_RATIO_MAX) {
            return PupilQualityState.SATURATED
        }

        if (
            blinkCandidateStreak >= BLINK_STREAK_MIN &&
            isBlinkLike(pupil = pupil, debug = debug, params = params)
        ) {
            return PupilQualityState.BLINK
        }

        return PupilQualityState.LOST_TRACK
    }

    /**
     * Summary: Returns whether the current frame looks blink-like.
     * @param pupil Current pupil result if available.
     * @param debug Current detector debug info.
     * @param params Current detector parameters.
     * @return True when the frame resembles a blink.
     */
    fun isBlinkLike(
        pupil: PupilDatum2D?,
        debug: Pupil2DDebugInfo,
        params: Pupil2DParams,
    ): Boolean {
        val lowConfidence = (pupil?.confidence ?: 0.0) < LOW_CONFIDENCE_MIN
        val darkRatioLow = debug.darkPixelRatio() < BLINK_DARK_RATIO_MAX
        val pupilTooSmall = pupil?.ellipse?.axisMajor?.let { it < (params.pupilSizeMin * BLINK_AXIS_RATIO) } ?: false
        return lowConfidence && (darkRatioLow || pupilTooSmall)
    }
}
