package Aquin.lubie.tracking.pipeline

import Aquin.lubie.tracking.model.PupilObservation2D
import Aquin.lubie.tracking.model.PupilQualityState
import Aquin.lubie.tracking.model.RoiBounds
import Aquin.lubie.tracking.model.TrackingRoiPhase
import kotlin.math.max

private const val RECOVERY_FRAME_THRESHOLD = 5
private const val MIN_ROI_PADDING_PX = 24
private const val ROI_SIZE_MULTIPLIER = 2.0

/**
 * Summary: Tracks the ROI state machine for near-eye 2D tracking.
 * @param none No constructor parameters.
 * @return Stateful ROI tracker.
 */
class RoiTrackingStateMachine {

    private var phaseInternal: TrackingRoiPhase = TrackingRoiPhase.INIT
    private var followRoi: RoiBounds? = null
    private var lostTrackCount: Int = 0

    /**
     * Summary: Returns the current ROI phase.
     * @param none No parameters.
     * @return Current ROI phase.
     */
    fun phase(): TrackingRoiPhase = phaseInternal

    /**
     * Summary: Resets the ROI tracker to its initial state.
     * @param none No parameters.
     * @return Unit.
     */
    fun reset() {
        phaseInternal = TrackingRoiPhase.INIT
        followRoi = null
        lostTrackCount = 0
    }

    /**
     * Summary: Returns the requested ROI for the next frame.
     * @param frameWidth Current frame width.
     * @param frameHeight Current frame height.
     * @return Requested ROI for the next detection pass.
     */
    fun nextRequestedRoi(frameWidth: Int, frameHeight: Int): RoiBounds {
        return when (phaseInternal) {
            TrackingRoiPhase.FOLLOW -> followRoi?.clamp(frameWidth, frameHeight)
                ?: RoiBounds.fullFrame(frameWidth, frameHeight)

            TrackingRoiPhase.INIT,
            TrackingRoiPhase.RECOVERY,
            -> RoiBounds.fullFrame(frameWidth, frameHeight)
        }
    }

    /**
     * Summary: Updates the state machine from the latest observation.
     * @param observation Latest 2D observation.
     * @return Unit.
     */
    fun update(observation: PupilObservation2D) {
        val frameWidth = observation.frameWidth
        val frameHeight = observation.frameHeight
        if (observation.quality == PupilQualityState.VALID && observation.pupil != null) {
            followRoi = buildFollowRoi(observation).clamp(frameWidth, frameHeight)
            phaseInternal = TrackingRoiPhase.FOLLOW
            lostTrackCount = 0
            return
        }

        lostTrackCount += 1
        if (phaseInternal == TrackingRoiPhase.INIT) {
            phaseInternal = TrackingRoiPhase.INIT
            return
        }

        if (lostTrackCount >= RECOVERY_FRAME_THRESHOLD) {
            phaseInternal = TrackingRoiPhase.RECOVERY
            followRoi = null
        }
    }

    /**
     * Summary: Builds the follow ROI around the latest valid ellipse.
     * @param observation Latest valid observation.
     * @return ROI centered on the latest ellipse with safety padding.
     */
    private fun buildFollowRoi(observation: PupilObservation2D): RoiBounds {
        val ellipse = observation.pupil?.ellipse ?: return observation.debug.effectiveRoi
        val majorAxis = ellipse.axisMajor
        val halfExtent = max(majorAxis * ROI_SIZE_MULTIPLIER / 2.0, majorAxis / 2.0 + MIN_ROI_PADDING_PX)
        val minX = (ellipse.centerX - halfExtent).toInt()
        val minY = (ellipse.centerY - halfExtent).toInt()
        val maxX = (ellipse.centerX + halfExtent).toInt()
        val maxY = (ellipse.centerY + halfExtent).toInt()
        return RoiBounds(minX, minY, maxX, maxY)
    }
}
