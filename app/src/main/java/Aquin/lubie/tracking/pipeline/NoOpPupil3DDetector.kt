package Aquin.lubie.tracking.pipeline

import Aquin.lubie.tracking.model.CameraModel3D
import Aquin.lubie.tracking.model.PupilDatum2D
import Aquin.lubie.tracking.model.PupilDatum3D
import org.opencv.core.Mat

/**
 * Summary: Placeholder 3D detector used by the MVP.
 * @param none No constructor parameters.
 * @return A no-op detector implementation.
 */
class NoOpPupil3DDetector : Pupil3DDetector {

    /**
     * Summary: Resets the placeholder detector.
     * @param none No parameters.
     * @return Unit.
     */
    override fun reset() = Unit

    /**
     * Summary: Returns no 3D result for the MVP.
     * @param datum2D Latest 2D pupil result.
     * @param grayFrame Current grayscale frame.
     * @param cameraModel Current camera model placeholder.
     * @return Always null.
     */
    override fun update(datum2D: PupilDatum2D, grayFrame: Mat, cameraModel: CameraModel3D): PupilDatum3D? {
        return null
    }

    /**
     * Summary: Reports placeholder 3D status.
     * @param none No parameters.
     * @return Status label for UI.
     */
    override fun status(): String = "3D pending"
}
