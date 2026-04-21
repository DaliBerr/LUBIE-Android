package Aquin.lubie.tracking.model

/**
 * Summary: Stores an ellipse in image coordinates.
 * @param centerX Ellipse center X in pixels.
 * @param centerY Ellipse center Y in pixels.
 * @param axisMinor Ellipse minor axis length in pixels.
 * @param axisMajor Ellipse major axis length in pixels.
 * @param angleDeg Ellipse rotation angle in degrees.
 * @return Value object used by 2D and 3D outputs.
 */
data class PupilEllipse2D(
    val centerX: Double,
    val centerY: Double,
    val axisMinor: Double,
    val axisMajor: Double,
    val angleDeg: Double,
)

/**
 * Summary: Stores a 2D pupil detection result.
 * @param eyeId Eye index.
 * @param timestamp Timestamp in seconds.
 * @param method Detector label.
 * @param normPosX Normalized X coordinate.
 * @param normPosY Normalized Y coordinate with flipped Y axis.
 * @param diameterPx Pupil diameter in pixels.
 * @param confidence Confidence score.
 * @param ellipse Fitted ellipse.
 * @return Immutable 2D pupil datum.
 */
data class PupilDatum2D(
    val eyeId: Int,
    val timestamp: Double,
    val method: String,
    val normPosX: Double,
    val normPosY: Double,
    val diameterPx: Double,
    val confidence: Double,
    val ellipse: PupilEllipse2D,
)

/**
 * Summary: Stores a 3D vector.
 * @param x X component.
 * @param y Y component.
 * @param z Z component.
 * @return Immutable vector.
 */
data class Vec3(
    val x: Double,
    val y: Double,
    val z: Double,
)

/**
 * Summary: Stores a 3D pupil circle.
 * @param center Circle center.
 * @param normal Circle normal.
 * @param radius Circle radius.
 * @return Immutable circle descriptor.
 */
data class Circle3D(
    val center: Vec3,
    val normal: Vec3,
    val radius: Double,
)

/**
 * Summary: Stores a sphere model.
 * @param center Sphere center.
 * @param radius Sphere radius.
 * @return Immutable sphere descriptor.
 */
data class Sphere3D(
    val center: Vec3,
    val radius: Double,
)

/**
 * Summary: Stores a 3D pupil detection result.
 * @param eyeId Eye index.
 * @param timestamp Timestamp in seconds.
 * @param method Detector label.
 * @param normPosX Normalized X coordinate.
 * @param normPosY Normalized Y coordinate with flipped Y axis.
 * @param diameterPx Projected pupil diameter in pixels.
 * @param confidence Search confidence.
 * @param modelConfidence Model confidence.
 * @param theta Polar angle.
 * @param phi Azimuth angle.
 * @param ellipse Projected pupil ellipse.
 * @param projectedSphere Projected sphere outline if present.
 * @param sphere Sphere model.
 * @param circle3D 3D pupil circle.
 * @param diameter3dMm 3D pupil diameter in millimeters.
 * @return Immutable 3D pupil datum.
 */
data class PupilDatum3D(
    val eyeId: Int,
    val timestamp: Double,
    val method: String,
    val normPosX: Double,
    val normPosY: Double,
    val diameterPx: Double,
    val confidence: Double,
    val modelConfidence: Double,
    val theta: Double,
    val phi: Double,
    val ellipse: PupilEllipse2D,
    val projectedSphere: PupilEllipse2D?,
    val sphere: Sphere3D,
    val circle3D: Circle3D,
    val diameter3dMm: Double,
)

/**
 * Summary: Stores an ROI in image coordinates.
 * @param minX Left bound.
 * @param minY Top bound.
 * @param maxX Right bound.
 * @param maxY Bottom bound.
 * @return Immutable ROI bounds.
 */
data class RoiBounds(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
) {
    /**
     * Summary: Calculates ROI width.
     * @param none No parameters.
     * @return Width in pixels.
     */
    fun width(): Int = (maxX - minX + 1).coerceAtLeast(1)

    /**
     * Summary: Calculates ROI height.
     * @param none No parameters.
     * @return Height in pixels.
     */
    fun height(): Int = (maxY - minY + 1).coerceAtLeast(1)

    /**
     * Summary: Clamps ROI to frame size.
     * @param frameWidth Frame width in pixels.
     * @param frameHeight Frame height in pixels.
     * @return A valid ROI inside the frame.
     */
    fun clamp(frameWidth: Int, frameHeight: Int): RoiBounds {
        val safeMaxX = (frameWidth - 1).coerceAtLeast(0)
        val safeMaxY = (frameHeight - 1).coerceAtLeast(0)
        val left = minX.coerceIn(0, safeMaxX)
        val top = minY.coerceIn(0, safeMaxY)
        val right = maxX.coerceIn(left, safeMaxX)
        val bottom = maxY.coerceIn(top, safeMaxY)
        return RoiBounds(left, top, right, bottom)
    }

    companion object {
        /**
         * Summary: Creates a full-frame ROI.
         * @param frameWidth Frame width in pixels.
         * @param frameHeight Frame height in pixels.
         * @return ROI that covers the whole frame.
         */
        fun fullFrame(frameWidth: Int, frameHeight: Int): RoiBounds {
            return RoiBounds(0, 0, (frameWidth - 1).coerceAtLeast(0), (frameHeight - 1).coerceAtLeast(0))
        }
    }
}

/**
 * Summary: Stores the tunable 2D detector parameters ported from pupil-detectors.
 * @param coarseDetection Whether coarse ROI narrowing is enabled on large inputs.
 * @param coarseFilterMin Minimum coarse filter size in pixels.
 * @param coarseFilterMax Maximum coarse filter size in pixels.
 * @param intensityRange Threshold band above the darkest histogram spike.
 * @param blurSize Median blur kernel size.
 * @param cannyThreshold Canny low threshold.
 * @param cannyRatio Ratio between low and high Canny thresholds.
 * @param cannyAperture Canny Sobel aperture size.
 * @param pupilSizeMin Minimum ellipse major axis in pixels.
 * @param pupilSizeMax Maximum ellipse major axis in pixels.
 * @param strongPerimeterRatioRangeMin Minimum strong contour perimeter ratio.
 * @param strongPerimeterRatioRangeMax Maximum strong contour perimeter ratio.
 * @param strongAreaRatioRangeMin Minimum strong contour area ratio.
 * @param strongAreaRatioRangeMax Maximum strong contour area ratio.
 * @param contourSizeMin Minimum contour size before approximation.
 * @param ellipseRoundnessRatio Minimum minor-to-major ratio.
 * @param initialEllipseFitThreshold Maximum variance for initial ellipse fitting.
 * @param finalPerimeterRatioRangeMin Minimum support ratio for final candidate search.
 * @param finalPerimeterRatioRangeMax Maximum support ratio property kept for parity.
 * @param ellipseTrueSupportMinDist Maximum support-pixel distance from ellipse.
 * @param supportPixelRatioExponent Confidence exponent.
 * @return Immutable parameter preset.
 */
data class Pupil2DParams(
    val coarseDetection: Boolean = true,
    val coarseFilterMin: Int = 128,
    val coarseFilterMax: Int = 280,
    val intensityRange: Int = 23,
    val blurSize: Int = 5,
    val cannyThreshold: Int = 160,
    val cannyRatio: Int = 2,
    val cannyAperture: Int = 5,
    val pupilSizeMin: Int = 10,
    val pupilSizeMax: Int = 100,
    val strongPerimeterRatioRangeMin: Double = 0.8,
    val strongPerimeterRatioRangeMax: Double = 1.1,
    val strongAreaRatioRangeMin: Double = 0.6,
    val strongAreaRatioRangeMax: Double = 1.1,
    val contourSizeMin: Int = 5,
    val ellipseRoundnessRatio: Double = 0.1,
    val initialEllipseFitThreshold: Double = 1.8,
    val finalPerimeterRatioRangeMin: Double = 0.6,
    val finalPerimeterRatioRangeMax: Double = 1.2,
    val ellipseTrueSupportMinDist: Double = 2.5,
    val supportPixelRatioExponent: Double = 2.0,
)

/**
 * Summary: Stores a camera model placeholder for future 3D work.
 * @param focalLengthPx Focal length in pixels.
 * @param width Frame width.
 * @param height Frame height.
 * @return Immutable camera model.
 */
data class CameraModel3D(
    val focalLengthPx: Double,
    val width: Int,
    val height: Int,
)

/**
 * Summary: Stores a simplified application session state.
 * @param currentFrameIndex Current frame index.
 * @param roi Current ROI.
 * @param lastPupil2D Latest 2D result.
 * @param lastPupil3DStatus Latest 3D status label.
 * @param lastTimestampMs Latest timestamp in milliseconds.
 * @return Immutable session state.
 */
data class EyeTrackingSessionState(
    val currentFrameIndex: Int = 0,
    val roi: RoiBounds = RoiBounds(0, 0, 0, 0),
    val lastPupil2D: PupilDatum2D? = null,
    val lastPupil3DStatus: String = "3D pending",
    val lastTimestampMs: Long = 0L,
)

/**
 * Summary: Stores a 2D contour point for pure Kotlin math helpers.
 * @param x X coordinate in pixels.
 * @param y Y coordinate in pixels.
 * @return Immutable 2D point.
 */
data class ContourPoint(
    val x: Double,
    val y: Double,
)
