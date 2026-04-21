package Aquin.lubie.tracking.pipeline

import Aquin.lubie.tracking.model.CameraModel3D
import Aquin.lubie.tracking.model.PupilDatum2D
import Aquin.lubie.tracking.model.PupilDatum3D
import org.opencv.core.Mat

/**
 * Summary: Defines the future 3D detector contract.
 * @param none No constructor parameters.
 * @return A stateful 3D detector contract.
 */
interface Pupil3DDetector {

    /**
     * Summary: Resets the detector state.
     * @param none No parameters.
     * @return Unit.
     */
    fun reset()

    /**
     * Summary: Updates the detector from a 2D datum and the current frame.
     * @param datum2D Latest 2D pupil result.
     * @param grayFrame Current grayscale frame.
     * @param cameraModel Current camera model placeholder.
     * @return A 3D result or null when unavailable.
     */
    fun update(datum2D: PupilDatum2D, grayFrame: Mat, cameraModel: CameraModel3D): PupilDatum3D?

    /**
     * Summary: Reports the current detector status string.
     * @param none No parameters.
     * @return Status label for UI.
     */
    fun status(): String
}

