package Aquin.lubie.tracking.pipeline

import Aquin.lubie.tracking.model.Pupil2DDebugInfo
import Aquin.lubie.tracking.model.Pupil2DParams
import Aquin.lubie.tracking.model.PupilDatum2D
import Aquin.lubie.tracking.model.PupilEllipse2D
import Aquin.lubie.tracking.model.RoiBounds
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

private const val SPECTRAL_OFFSET = 5
private const val SATURATED_PIXEL_THRESHOLD = 250.0
private const val SPLIT_ANGLE_DEG = 80.0
private const val SPLIT_CONTOUR_SIZE_MIN = 3
private const val MAX_COMBINE_DEPTH = 5
private const val MAX_COMBINE_EVALUATIONS = 1000
private const val MAX_FINAL_SIZE_DIFFERENCE = 0.3

/**
 * Summary: Stores the full detector output used by the controller.
 * @param pupil Final 2D pupil result when available.
 * @param debug Detector debug information for the processed frame.
 * @return Immutable 2D detector output.
 */
data class Pupil2DDetectionResult(
    val pupil: PupilDatum2D?,
    val debug: Pupil2DDebugInfo,
)

private data class LocalDetectionPassResult(
    val ellipse: PupilEllipse2D?,
    val confidence: Double,
    val supportPixelCount: Int,
    val finalEdgeCount: Int,
    val darkPixelCount: Int,
    val saturatedPixelCount: Int,
    val usedStrongPrior: Boolean,
    val thresholdLowSpike: Int,
    val thresholdHighSpike: Int,
)

/**
 * Summary: Runs the official pupil-detectors 2D pipeline ported to Kotlin/OpenCV.
 * @param preprocessor Preprocessing helper.
 * @return Stateful 2D detector for live grayscale frames.
 */
class Pupil2DDetector(
    private val preprocessor: EyePreprocessor,
) {

    private var useStrongPrior: Boolean = false
    private var priorEllipse: PupilEllipse2D? = null
    private var lastDetectionRoi: RoiBounds = RoiBounds(0, 0, 0, 0)

    /**
     * Summary: Resets the detector state.
     * @param none No parameters.
     * @return Unit.
     */
    fun reset() {
        useStrongPrior = false
        priorEllipse = null
        lastDetectionRoi = RoiBounds(0, 0, 0, 0)
    }

    /**
     * Summary: Returns the ROI used by the latest detection pass.
     * @param none No parameters.
     * @return Effective ROI used by the detector.
     */
    fun lastEffectiveRoi(): RoiBounds = lastDetectionRoi

    /**
     * Summary: Detects a 2D pupil ellipse in a grayscale frame.
     * @param gray Full-frame grayscale image.
     * @param roiBounds Requested ROI in frame coordinates.
     * @param timestamp Timestamp in seconds.
     * @param eyeId Eye index.
     * @param params Current detector parameters.
     * @param allowStrongPrior Whether the strong prior path may be used.
     * @return 2D detector result with pupil datum and debug info.
     */
    fun detectDetailed(
        gray: Mat,
        roiBounds: RoiBounds,
        timestamp: Double,
        eyeId: Int,
        params: Pupil2DParams,
        allowStrongPrior: Boolean = true,
    ): Pupil2DDetectionResult {
        val requestedRoi = roiBounds.clamp(gray.cols(), gray.rows())
        val coarseResult = coarseDetectRoi(gray, requestedRoi, params)
        val effectiveRoi = coarseResult.roi
        lastDetectionRoi = effectiveRoi
        if (effectiveRoi.width() <= 1 || effectiveRoi.height() <= 1) {
            return Pupil2DDetectionResult(
                pupil = null,
                debug = Pupil2DDebugInfo(
                    effectiveRoi = effectiveRoi,
                    coarseDetectionUsed = coarseResult.used,
                ),
            )
        }

        val localResult = detectInRoi(
            gray = gray,
            roiBounds = effectiveRoi,
            params = params,
            coarseDetectionUsed = coarseResult.used,
            allowStrongPrior = allowStrongPrior,
        )

        val globalPupil = localResult.ellipse?.let { finalEllipse ->
            val globalCenterX = finalEllipse.centerX + effectiveRoi.minX
            val globalCenterY = finalEllipse.centerY + effectiveRoi.minY
            val globalEllipse = finalEllipse.copy(centerX = globalCenterX, centerY = globalCenterY)
            val (normX, normY) = normalizeWithFlipY(globalCenterX, globalCenterY, gray.cols(), gray.rows())
            PupilDatum2D(
                eyeId = eyeId,
                timestamp = timestamp,
                method = "2d pupil-detectors port",
                normPosX = normX,
                normPosY = normY,
                diameterPx = globalEllipse.axisMajor,
                confidence = localResult.confidence,
                ellipse = globalEllipse,
            )
        }

        val debugInfo = Pupil2DDebugInfo(
            effectiveRoi = effectiveRoi,
            supportPixelCount = localResult.supportPixelCount,
            finalEdgeCount = localResult.finalEdgeCount,
            darkPixelCount = localResult.darkPixelCount,
            coarseDetectionUsed = coarseResult.used,
            usedStrongPrior = localResult.usedStrongPrior,
            thresholdLowSpike = localResult.thresholdLowSpike,
            thresholdHighSpike = localResult.thresholdHighSpike,
            roiPixelCount = effectiveRoi.width() * effectiveRoi.height(),
            saturatedPixelCount = localResult.saturatedPixelCount,
        )
        return Pupil2DDetectionResult(globalPupil, debugInfo)
    }

    /**
     * Summary: Keeps the old simplified API for existing callers.
     * @param gray Full-frame grayscale image.
     * @param roiBounds Requested ROI in frame coordinates.
     * @param timestamp Timestamp in seconds.
     * @param eyeId Eye index.
     * @param params Current detector parameters.
     * @return Final 2D pupil result or null.
     */
    fun detect(
        gray: Mat,
        roiBounds: RoiBounds,
        timestamp: Double,
        eyeId: Int,
        params: Pupil2DParams,
    ): PupilDatum2D? {
        return detectDetailed(
            gray = gray,
            roiBounds = roiBounds,
            timestamp = timestamp,
            eyeId = eyeId,
            params = params,
        ).pupil
    }

    private fun detectInRoi(
        gray: Mat,
        roiBounds: RoiBounds,
        params: Pupil2DParams,
        coarseDetectionUsed: Boolean,
        allowStrongPrior: Boolean,
    ): LocalDetectionPassResult {
        val pupilImage = preprocessor.cloneRoi(gray, roiBounds)
        val originalRoi = pupilImage.clone()
        val binaryImage = Mat()
        val specMask = Mat()
        val saturatedMask = Mat()
        val edges = Mat()
        val dilateKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, org.opencv.core.Size(7.0, 7.0))
        val openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, org.opencv.core.Size(9.0, 9.0))

        return try {
            val (lowestSpikeIndex, highestSpikeIndex, _) = calculateSpikeIndicesAndMaxIntensity(pupilImage)
            val darkUpperBound = (lowestSpikeIndex + params.intensityRange).coerceAtMost(255)
            val specUpperBound = (highestSpikeIndex - SPECTRAL_OFFSET).coerceAtLeast(0)

            Imgproc.threshold(originalRoi, saturatedMask, SATURATED_PIXEL_THRESHOLD, 255.0, Imgproc.THRESH_BINARY)
            val saturatedPixelCount = Core.countNonZero(saturatedMask)

            Core.inRange(pupilImage, Scalar(0.0), Scalar(darkUpperBound.toDouble()), binaryImage)
            Imgproc.dilate(binaryImage, binaryImage, dilateKernel, Point(-1.0, -1.0), 2)
            val darkPixelCount = Core.countNonZero(binaryImage)

            Core.inRange(pupilImage, Scalar(0.0), Scalar(specUpperBound.toDouble()), specMask)
            Imgproc.erode(specMask, specMask, dilateKernel)

            Imgproc.morphologyEx(pupilImage, pupilImage, Imgproc.MORPH_OPEN, openKernel)
            val blurKernel = preprocessor.normalizeMedianBlurKernel(params.blurSize)
            if (blurKernel > 1) {
                Imgproc.medianBlur(pupilImage, pupilImage, blurKernel)
            }

            Imgproc.Canny(
                pupilImage,
                edges,
                params.cannyThreshold.toDouble(),
                params.cannyThreshold * params.cannyRatio.toDouble(),
                params.cannyAperture,
            )
            Core.min(edges, specMask, edges)
            Core.min(edges, binaryImage, edges)

            val rawEdges = extractNonZeroPoints(edges)
            if (allowStrongPrior) {
                val strongPriorResult = tryStrongPrior(rawEdges, roiBounds, params)
                if (strongPriorResult != null) {
                    return LocalDetectionPassResult(
                        ellipse = strongPriorResult.first,
                        confidence = strongPriorResult.second,
                        supportPixelCount = strongPriorResult.third,
                        finalEdgeCount = strongPriorResult.fourth,
                        darkPixelCount = darkPixelCount,
                        saturatedPixelCount = saturatedPixelCount,
                        usedStrongPrior = true,
                        thresholdLowSpike = lowestSpikeIndex,
                        thresholdHighSpike = highestSpikeIndex,
                    )
                }
            }

            val splitContours = findSplitContours(edges, params)
            if (splitContours.isEmpty()) {
                return LocalDetectionPassResult(
                    ellipse = null,
                    confidence = 0.0,
                    supportPixelCount = 0,
                    finalEdgeCount = 0,
                    darkPixelCount = darkPixelCount,
                    saturatedPixelCount = saturatedPixelCount,
                    usedStrongPrior = false,
                    thresholdLowSpike = lowestSpikeIndex,
                    thresholdHighSpike = highestSpikeIndex,
                )
            }

            val padding = (((pupilImage.cols() / 2.0) / 2.0) / 4.0).toInt()
            val centerVariance = Rect(
                padding,
                padding,
                (pupilImage.cols() - (2 * padding)).coerceAtLeast(1),
                (pupilImage.rows() - (2 * padding)).coerceAtLeast(1),
            )
            val seedContours = divideStrongAndWeakContours(splitContours, centerVariance, params)
            val seedIndices = if (seedContours.first.isNotEmpty()) seedContours.first else seedContours.second
            if (seedIndices.isEmpty()) {
                return LocalDetectionPassResult(
                    ellipse = null,
                    confidence = 0.0,
                    supportPixelCount = 0,
                    finalEdgeCount = 0,
                    darkPixelCount = darkPixelCount,
                    saturatedPixelCount = saturatedPixelCount,
                    usedStrongPrior = false,
                    thresholdLowSpike = lowestSpikeIndex,
                    thresholdHighSpike = highestSpikeIndex,
                )
            }

            val solutions = filterSubsets(
                pruningQuickCombine(
                    itemCount = splitContours.size,
                    seedIndices = seedIndices,
                    maxDepth = MAX_COMBINE_DEPTH,
                    maxEvaluations = MAX_COMBINE_EVALUATIONS,
                ) { indices ->
                    val testContour = indices.flatMap { splitContours[it] }
                    if (testContour.size < 5) {
                        false
                    } else {
                        val ellipse = fitEllipseOrNull(testContour)?.toPupilEllipse() ?: return@pruningQuickCombine false
                        contourEllipseDeviationVariance(testContour, ellipse) < params.initialEllipseFitThreshold
                    }
                },
            )
            if (solutions.isEmpty()) {
                return LocalDetectionPassResult(
                    ellipse = null,
                    confidence = 0.0,
                    supportPixelCount = 0,
                    finalEdgeCount = 0,
                    darkPixelCount = darkPixelCount,
                    saturatedPixelCount = saturatedPixelCount,
                    usedStrongPrior = false,
                    thresholdLowSpike = lowestSpikeIndex,
                    thresholdHighSpike = highestSpikeIndex,
                )
            }

            val resolvedContours = MutableList(splitContours.size) { emptyList<Aquin.lubie.tracking.model.ContourPoint>() }
            var maxSupportRatio = params.finalPerimeterRatioRangeMin
            var bestSolutionIndex = -1

            solutions.forEachIndexed { solutionIndex, solution ->
                val testContour = mutableListOf<Aquin.lubie.tracking.model.ContourPoint>()
                solution.forEach { contourIndex ->
                    if (resolvedContours[contourIndex].isEmpty()) {
                        resolvedContours[contourIndex] = resolveContour(splitContours[contourIndex], edges)
                    }
                    testContour += resolvedContours[contourIndex]
                }
                if (testContour.size < 5) return@forEachIndexed

                val cvEllipse = fitEllipseOrNull(testContour) ?: return@forEachIndexed
                val ellipse = cvEllipse.toPupilEllipse()
                val supportPixels = ellipseTrueSupport(ellipse, testContour, params.ellipseTrueSupportMinDist)
                val supportRatio =
                    (supportPixels.size / approximateEllipseCircumference(ellipse).coerceAtLeast(1e-6)) *
                        (supportPixels.size.toDouble() / testContour.size.toDouble()).pow(params.supportPixelRatioExponent)

                if (
                    supportRatio >= maxSupportRatio &&
                    isEllipseCandidate(cvEllipse, centerVariance, params.ellipseRoundnessRatio, params.pupilSizeMin, params.pupilSizeMax)
                ) {
                    bestSolutionIndex = solutionIndex
                    maxSupportRatio = supportRatio
                    if (supportRatio >= params.strongPerimeterRatioRangeMin) {
                        val globalEllipse = ellipse.copy(
                            centerX = ellipse.centerX + roiBounds.minX,
                            centerY = ellipse.centerY + roiBounds.minY,
                        )
                        priorEllipse = globalEllipse
                        useStrongPrior = true
                    }
                }
            }

            if (bestSolutionIndex == -1) {
                return LocalDetectionPassResult(
                    ellipse = null,
                    confidence = 0.0,
                    supportPixelCount = 0,
                    finalEdgeCount = 0,
                    darkPixelCount = darkPixelCount,
                    saturatedPixelCount = saturatedPixelCount,
                    usedStrongPrior = false,
                    thresholdLowSpike = lowestSpikeIndex,
                    thresholdHighSpike = highestSpikeIndex,
                )
            }

            val bestSolution = solutions[bestSolutionIndex].sorted()
            val bestContours = bestSolution.map { splitContours[it] }
            val bestContour = bestContours.flatten()
            if (bestContour.size < 5) {
                return LocalDetectionPassResult(
                    ellipse = null,
                    confidence = 0.0,
                    supportPixelCount = 0,
                    finalEdgeCount = 0,
                    darkPixelCount = darkPixelCount,
                    saturatedPixelCount = saturatedPixelCount,
                    usedStrongPrior = false,
                    thresholdLowSpike = lowestSpikeIndex,
                    thresholdHighSpike = highestSpikeIndex,
                )
            }

            val initialEllipseRect = fitEllipseOrNull(bestContour)
                ?: return LocalDetectionPassResult(
                    ellipse = null,
                    confidence = 0.0,
                    supportPixelCount = 0,
                    finalEdgeCount = 0,
                    darkPixelCount = darkPixelCount,
                    saturatedPixelCount = saturatedPixelCount,
                    usedStrongPrior = false,
                    thresholdLowSpike = lowestSpikeIndex,
                    thresholdHighSpike = highestSpikeIndex,
                )
            val finalEdges = resolveContours(bestContours, edges)
            if (finalEdges.size < 5) {
                return LocalDetectionPassResult(
                    ellipse = null,
                    confidence = 0.0,
                    supportPixelCount = 0,
                    finalEdgeCount = finalEdges.size,
                    darkPixelCount = darkPixelCount,
                    saturatedPixelCount = saturatedPixelCount,
                    usedStrongPrior = false,
                    thresholdLowSpike = lowestSpikeIndex,
                    thresholdHighSpike = highestSpikeIndex,
                )
            }

            val refinedEllipseRect = fitEllipseOrNull(finalEdges)
                ?: return LocalDetectionPassResult(
                    ellipse = null,
                    confidence = 0.0,
                    supportPixelCount = 0,
                    finalEdgeCount = finalEdges.size,
                    darkPixelCount = darkPixelCount,
                    saturatedPixelCount = saturatedPixelCount,
                    usedStrongPrior = false,
                    thresholdLowSpike = lowestSpikeIndex,
                    thresholdHighSpike = highestSpikeIndex,
                )
            val sizeDifference = abs(1.0 - (initialEllipseRect.size.height / refinedEllipseRect.size.height.coerceAtLeast(1e-6)))
            val finalEllipseRect =
                if (
                    isEllipseCandidate(
                        refinedEllipseRect,
                        centerVariance,
                        params.ellipseRoundnessRatio,
                        params.pupilSizeMin,
                        params.pupilSizeMax,
                    ) && sizeDifference < MAX_FINAL_SIZE_DIFFERENCE
                ) {
                    refinedEllipseRect
                } else {
                    initialEllipseRect
                }

            val finalEllipse = finalEllipseRect.toPupilEllipse()
            val supportPixels = ellipseTrueSupport(finalEllipse, finalEdges, params.ellipseTrueSupportMinDist)
            val confidence = computeConfidence(
                ellipse = finalEllipse,
                supportPixelCount = supportPixels.size,
                finalEdgeCount = finalEdges.size,
                exponent = params.supportPixelRatioExponent,
            )

            LocalDetectionPassResult(
                ellipse = finalEllipse,
                confidence = confidence,
                supportPixelCount = supportPixels.size,
                finalEdgeCount = finalEdges.size,
                darkPixelCount = darkPixelCount,
                saturatedPixelCount = saturatedPixelCount,
                usedStrongPrior = false,
                thresholdLowSpike = lowestSpikeIndex,
                thresholdHighSpike = highestSpikeIndex,
            )
        } finally {
            pupilImage.release()
            originalRoi.release()
            binaryImage.release()
            specMask.release()
            saturatedMask.release()
            edges.release()
            dilateKernel.release()
            openKernel.release()
        }
    }

    private fun tryStrongPrior(
        rawEdges: List<Aquin.lubie.tracking.model.ContourPoint>,
        roiBounds: RoiBounds,
        params: Pupil2DParams,
    ): Quadruple<PupilEllipse2D, Double, Int, Int>? {
        val previousEllipse = priorEllipse ?: return null
        if (!useStrongPrior || rawEdges.isEmpty()) return null

        useStrongPrior = false
        var localEllipse = previousEllipse.copy(
            centerX = previousEllipse.centerX - roiBounds.minX,
            centerY = previousEllipse.centerY - roiBounds.minY,
        )

        val initialSupportPixels = ellipseTrueSupport(localEllipse, rawEdges, params.ellipseTrueSupportMinDist)
        val initialCircumference = approximateEllipseCircumference(localEllipse).coerceAtLeast(1e-6)
        val initialSupportRatio = initialSupportPixels.size.toDouble() / initialCircumference
        if (initialSupportPixels.size < 5 || initialSupportRatio < params.strongPerimeterRatioRangeMin) {
            return null
        }

        val refitEllipse = fitEllipseOrNull(initialSupportPixels)?.toPupilEllipse() ?: return null
        localEllipse = refitEllipse
        val ellipseCircumference = approximateEllipseCircumference(localEllipse).coerceAtLeast(1e-6)
        val narrowSupportPixels = ellipseTrueSupport(localEllipse, rawEdges, params.ellipseTrueSupportMinDist)
        val wideSupportPixels = ellipseTrueSupport(localEllipse, rawEdges, params.ellipseTrueSupportMinDist * 2.0)
        if (wideSupportPixels.isEmpty()) return null

        val narrowWideRatio = narrowSupportPixels.size.toDouble() / wideSupportPixels.size.toDouble()
        val narrowCircumferenceRatio = narrowSupportPixels.size.toDouble() / ellipseCircumference
        val supportRatio = narrowCircumferenceRatio * narrowWideRatio.pow(params.supportPixelRatioExponent)

        val globalEllipse = localEllipse.copy(
            centerX = localEllipse.centerX + roiBounds.minX,
            centerY = localEllipse.centerY + roiBounds.minY,
        )
        priorEllipse = globalEllipse
        useStrongPrior = true
        return Quadruple(localEllipse, min(1.0, supportRatio), narrowSupportPixels.size, narrowSupportPixels.size)
    }

    private fun findSplitContours(edges: Mat, params: Pupil2DParams): List<List<Aquin.lubie.tracking.model.ContourPoint>> {
        val contours = mutableListOf<org.opencv.core.MatOfPoint>()
        val hierarchy = Mat()
        val contourInput = edges.clone()
        return try {
            Imgproc.findContours(contourInput, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE)
            val filteredContours = contours
                .map { contour -> contour.toArray().map { Aquin.lubie.tracking.model.ContourPoint(it.x, it.y) } }
                .filter { contour -> contour.size > params.contourSizeMin }

            val approxContours = filteredContours.map { contour ->
                approximateContour(contour)
            }

            splitRoughContoursOptimized(
                contours = approxContours,
                maxAngle = SPLIT_ANGLE_DEG,
                minContourSize = SPLIT_CONTOUR_SIZE_MIN,
            ).sortedByDescending { it.size }
        } finally {
            contourInput.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    private fun approximateContour(contour: List<Aquin.lubie.tracking.model.ContourPoint>): List<Aquin.lubie.tracking.model.ContourPoint> {
        val contourMat = contour.toMatOfPoint()
        val approxMat = MatOfPoint2f()
        val contour2f = MatOfPoint2f(*contourMat.toArray())
        Imgproc.approxPolyDP(contour2f, approxMat, 1.5, false)
        val approximated = approxMat.toArray().map { Aquin.lubie.tracking.model.ContourPoint(it.x, it.y) }
        contourMat.release()
        contour2f.release()
        approxMat.release()
        return approximated
    }
}

/**
 * Summary: Stores four values without introducing a full generic tuple dependency.
 * @param first First value.
 * @param second Second value.
 * @param third Third value.
 * @param fourth Fourth value.
 * @return Immutable quadruple.
 */
private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
