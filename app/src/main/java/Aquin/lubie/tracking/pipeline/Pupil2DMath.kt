package Aquin.lubie.tracking.pipeline

import Aquin.lubie.tracking.model.ContourPoint
import Aquin.lubie.tracking.model.Pupil2DParams
import Aquin.lubie.tracking.model.PupilEllipse2D
import Aquin.lubie.tracking.model.RoiBounds
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.RotatedRect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

private const val COARSE_SCALE = 2
private const val COARSE_MIN_AREA = 320 * 240
private const val COARSE_H_STEP = 4
private const val COARSE_STEP = 5
private const val COARSE_MAX_RESULTS = 30
private const val COARSE_MIN_RESPONSE_RATIO = 0.4f

private data class CoarseCandidate(
    val x: Int,
    val y: Int,
    val width: Int,
    val response: Float,
)

/**
 * Summary: Stores the effective ROI returned by the coarse detector.
 * @param roi Effective ROI after coarse detection.
 * @param used Whether the ROI differs from the requested ROI.
 * @return Immutable coarse detector result.
 */
data class CoarseRoiResult(
    val roi: RoiBounds,
    val used: Boolean,
)

/**
 * Summary: Calculates signed polyline angles in degrees.
 * @param points Contour points ordered along the polyline.
 * @return Per-vertex signed angles.
 */
fun polylineAngles(points: List<ContourPoint>): List<Double> {
    if (points.size < 3) return emptyList()
    return (1 until points.lastIndex).map { index ->
        val a = points[index - 1]
        val b = points[index]
        val c = points[index + 1]
        val abx = b.x - a.x
        val aby = b.y - a.y
        val cbx = b.x - c.x
        val cby = b.y - c.y
        val dot = (abx * cbx) + (aby * cby)
        val cross = (abx * cby) - (aby * cbx)
        Math.toDegrees(atan2(cross, dot))
    }
}

/**
 * Summary: Finds split points using kink and direction change rules.
 * @param angles Signed polyline angles.
 * @param angleThresholdDeg Kink threshold in degrees.
 * @return Point indices where the contour should split.
 */
fun findKinksAndDirectionChanges(angles: List<Double>, angleThresholdDeg: Double): List<Int> {
    if (angles.isEmpty()) return emptyList()
    val result = mutableListOf<Int>()
    var currentSign = angles.first() > 0.0
    angles.forEachIndexed { index, angle ->
        val isPositive = angle > 0.0
        if (abs(angle) < angleThresholdDeg || isPositive != currentSign) {
            result += index
        }
        currentSign = isPositive
    }
    return result
}

/**
 * Summary: Splits a contour at the requested corner indices.
 * @param points Original contour points.
 * @param indices Split point indices.
 * @return Split contour segments.
 */
fun splitAtCornerIndices(points: List<ContourPoint>, indices: List<Int>): List<List<ContourPoint>> {
    if (indices.isEmpty()) return listOf(points)
    val segments = mutableListOf<List<ContourPoint>>()
    var startIndex = 0
    for (index in 0..indices.size) {
        val nextIndex = if (index < indices.size) indices[index] + 1 else points.lastIndex
        if (startIndex <= nextIndex && nextIndex < points.size) {
            segments += points.subList(startIndex, nextIndex + 1).toList()
        }
        startIndex = nextIndex
    }
    return segments
}

/**
 * Summary: Splits approximated contours using the optimized official rule set.
 * @param contours Approximated contours in ROI coordinates.
 * @param maxAngle Maximum kink angle before splitting.
 * @param minContourSize Minimum contour size after splitting.
 * @return Split contour list.
 */
fun splitRoughContoursOptimized(
    contours: List<List<ContourPoint>>,
    maxAngle: Double,
    minContourSize: Int,
): List<List<ContourPoint>> {
    val splitContours = mutableListOf<List<ContourPoint>>()
    contours.forEach { contour ->
        if (contour.size < minContourSize) return@forEach
        var currentlyPositive = true
        var firstLoop = true
        var lastContourEndIndex = 0
        for (pointIndex in 2 until contour.size) {
            val first = contour[pointIndex - 2]
            val second = contour[pointIndex - 1]
            val third = contour[pointIndex]
            val angle = polylineAngles(listOf(first, second, third)).firstOrNull() ?: continue
            val isPositive = angle > 0.0
            if (abs(angle) < maxAngle || (!firstLoop && isPositive != currentlyPositive)) {
                val currentContourEndIndex = pointIndex - 1
                if ((currentContourEndIndex + 1) - lastContourEndIndex >= minContourSize) {
                    splitContours += contour.subList(lastContourEndIndex, currentContourEndIndex + 1).toList()
                }
                lastContourEndIndex = currentContourEndIndex
            }
            currentlyPositive = isPositive
            firstLoop = false
        }
        if (contour.size - lastContourEndIndex >= minContourSize) {
            splitContours += contour.subList(lastContourEndIndex, contour.size).toList()
        }
    }
    return splitContours
}

/**
 * Summary: Calculates polyline length.
 * @param points Ordered contour points.
 * @return Arc length in pixels.
 */
fun contourLength(points: List<ContourPoint>): Double {
    if (points.size < 2) return 0.0
    return points.zipWithNext { a, b ->
        val dx = b.x - a.x
        val dy = b.y - a.y
        sqrt((dx * dx) + (dy * dy))
    }.sum()
}

/**
 * Summary: Approximates ellipse area using major and minor radii.
 * @param ellipse Fitted ellipse.
 * @return Area in square pixels.
 */
fun ellipseArea(ellipse: PupilEllipse2D): Double {
    return PI * (ellipse.axisMajor / 2.0) * (ellipse.axisMinor / 2.0)
}

/**
 * Summary: Approximates ellipse circumference using the official pupil-detectors formula.
 * @param ellipse Fitted ellipse.
 * @return Circumference in pixels.
 */
fun approximateEllipseCircumference(ellipse: PupilEllipse2D): Double {
    val majorRadius = ellipse.axisMajor / 2.0
    val minorRadius = ellipse.axisMinor / 2.0
    return PI * abs(
        (3.0 * (majorRadius + minorRadius)) -
            sqrt((10.0 * majorRadius * minorRadius) + (3.0 * ((majorRadius * majorRadius) + (minorRadius * minorRadius)))),
    )
}

/**
 * Summary: Calculates the signed approximate distance used by pupil-detectors.
 * @param point Point to evaluate.
 * @param ellipse Fitted ellipse.
 * @return Signed approximate distance in pixels.
 */
fun signedDistanceToEllipse(point: ContourPoint, ellipse: PupilEllipse2D): Double {
    val majorRadius = ellipse.axisMajor / 2.0
    val minorRadius = ellipse.axisMinor / 2.0
    if (majorRadius <= 0.0 || minorRadius <= 0.0) return Double.NEGATIVE_INFINITY

    val angle = Math.toRadians(ellipse.angleDeg)
    val cosAngle = cos(angle)
    val sinAngle = sin(angle)

    val rA00 = cosAngle
    val rA01 = sinAngle
    val radiusRatio = majorRadius / minorRadius
    val rA10 = -radiusRatio * sinAngle
    val rA11 = radiusRatio * cosAngle

    val rAt0 = (rA00 * ellipse.centerX) + (rA01 * ellipse.centerY)
    val rAt1 = (rA10 * ellipse.centerX) + (rA11 * ellipse.centerY)

    val rAxt = ((rA00 * point.x) + (rA01 * point.y)) - rAt0
    val rAyt = ((rA10 * point.x) + (rA11 * point.y)) - rAt1
    val xyDistance = sqrt((rAxt * rAxt) + (rAyt * rAyt))
    return majorRadius - xyDistance
}

/**
 * Summary: Calculates unsigned approximate distance from a point to an ellipse.
 * @param point Point to evaluate.
 * @param ellipse Fitted ellipse.
 * @return Absolute distance in pixels.
 */
fun pointDistanceToEllipse(point: ContourPoint, ellipse: PupilEllipse2D): Double {
    return abs(signedDistanceToEllipse(point, ellipse))
}

/**
 * Summary: Calculates mean squared contour deviation from a fitted ellipse.
 * @param points Points used for evaluation.
 * @param ellipse Fitted ellipse.
 * @return Mean squared deviation.
 */
fun contourEllipseDeviationVariance(points: List<ContourPoint>, ellipse: PupilEllipse2D): Double {
    if (points.isEmpty()) return Double.POSITIVE_INFINITY
    return points.sumOf { point ->
        val distance = abs(signedDistanceToEllipse(point, ellipse))
        distance * distance
    } / points.size.toDouble()
}

/**
 * Summary: Calculates mean point-to-ellipse error.
 * @param points Points used for evaluation.
 * @param ellipse Fitted ellipse.
 * @return Mean distance in pixels.
 */
fun averageEllipseError(points: List<ContourPoint>, ellipse: PupilEllipse2D): Double {
    if (points.isEmpty()) return Double.POSITIVE_INFINITY
    return points.sumOf { point -> pointDistanceToEllipse(point, ellipse) } / points.size.toDouble()
}

/**
 * Summary: Calculates polygon area using the shoelace formula.
 * @param points Polygon points.
 * @return Absolute polygon area.
 */
fun polygonArea(points: List<ContourPoint>): Double {
    if (points.size < 3) return 0.0
    var area = 0.0
    for (index in points.indices) {
        val current = points[index]
        val next = points[(index + 1) % points.size]
        area += (current.x * next.y) - (next.x * current.y)
    }
    return abs(area) / 2.0
}

/**
 * Summary: Calculates contour support ratios against a fitted ellipse.
 * @param ellipse Fitted ellipse.
 * @param contour Contour points in ROI coordinates.
 * @return Pair of area ratio and perimeter ratio.
 */
fun ellipseContourSupportRatio(ellipse: PupilEllipse2D, contour: List<ContourPoint>): Pair<Double, Double> {
    if (contour.isEmpty()) return 0.0 to 0.0
    val contourMat = contour.toMatOfPoint()
    val hullIndices = MatOfInt()
    Imgproc.convexHull(contourMat, hullIndices)
    val sourcePoints = contourMat.toArray()
    val hullPoints = hullIndices.toArray().map { index: Int -> sourcePoints[index] }
    val hullMat = MatOfPoint()
    if (hullPoints.isNotEmpty()) {
        hullMat.fromList(hullPoints.toList())
    }
    val actualArea = if (hullPoints.size >= 3) Imgproc.contourArea(hullMat) else 0.0
    val actualLength = contourLength(contour)
    val ellipseArea = ellipseArea(ellipse).coerceAtLeast(1e-6)
    val ellipseCircumference = approximateEllipseCircumference(ellipse).coerceAtLeast(1e-6)
    contourMat.release()
    hullIndices.release()
    hullMat.release()
    return (actualArea / ellipseArea) to (actualLength / ellipseCircumference)
}

/**
 * Summary: Returns support pixels lying close to a fitted ellipse.
 * @param ellipse Fitted ellipse.
 * @param rawEdges Candidate edge pixels.
 * @param maxDistance Maximum distance to accept.
 * @return Support pixels near the ellipse.
 */
fun ellipseTrueSupport(
    ellipse: PupilEllipse2D,
    rawEdges: List<ContourPoint>,
    maxDistance: Double,
): List<ContourPoint> {
    return rawEdges.filter { point ->
        abs(signedDistanceToEllipse(point, ellipse)) <= maxDistance
    }
}

/**
 * Summary: Evaluates whether a rotated rect is a plausible pupil ellipse.
 * @param ellipse OpenCV rotated rect.
 * @param centerVariance Bounds within which the ellipse center must lie.
 * @param roundnessRatio Minimum minor-to-major ratio.
 * @param sizeMin Minimum major axis in pixels.
 * @param sizeMax Maximum major axis in pixels.
 * @return True when the candidate passes the official geometry gate.
 */
fun isEllipseCandidate(
    ellipse: RotatedRect,
    centerVariance: Rect,
    roundnessRatio: Double,
    sizeMin: Int,
    sizeMax: Int,
): Boolean {
    val centered =
        centerVariance.x < ellipse.center.x &&
            ellipse.center.x < (centerVariance.x + centerVariance.width) &&
            centerVariance.y < ellipse.center.y &&
            ellipse.center.y < (centerVariance.y + centerVariance.height)
    if (!centered) return false

    val maxRadius = ellipse.size.height
    val minRadius = ellipse.size.width
    if (maxRadius <= 0.0 || minRadius <= 0.0) return false
    val roundEnough = (minRadius / maxRadius) >= roundnessRatio
    if (!roundEnough) return false
    return sizeMin <= maxRadius && maxRadius <= sizeMax
}

/**
 * Summary: Divides contours into strong and weak seed groups.
 * @param contours Split contours in ROI coordinates.
 * @param centerVariance Bounds within which ellipse centers must lie.
 * @param params Current detector parameters.
 * @return Pair of strong contour indices and weak contour indices.
 */
fun divideStrongAndWeakContours(
    contours: List<List<ContourPoint>>,
    centerVariance: Rect,
    params: Pupil2DParams,
): Pair<List<Int>, List<Int>> {
    val strongContours = mutableListOf<Int>()
    val weakContours = mutableListOf<Int>()

    contours.forEachIndexed { index, contour ->
        if (contour.size < 5) return@forEachIndexed
        val ellipse = fitEllipseOrNull(contour) ?: return@forEachIndexed
        if (!isEllipseCandidate(ellipse, centerVariance, params.ellipseRoundnessRatio, params.pupilSizeMin, params.pupilSizeMax)) {
            return@forEachIndexed
        }

        val pupilEllipse = ellipse.toPupilEllipse()
        val fitVariance = contourEllipseDeviationVariance(contour, pupilEllipse)
        if (fitVariance >= params.initialEllipseFitThreshold) return@forEachIndexed

        val (areaRatio, perimeterRatio) = ellipseContourSupportRatio(pupilEllipse, contour)
        if (
            params.strongPerimeterRatioRangeMin <= perimeterRatio &&
            perimeterRatio <= params.strongPerimeterRatioRangeMax &&
            params.strongAreaRatioRangeMin <= areaRatio &&
            areaRatio <= params.strongAreaRatioRangeMax
        ) {
            strongContours += index
        } else {
            weakContours += index
        }
    }

    return strongContours to weakContours
}

/**
 * Summary: Runs a pruning combination search similar to pupil-detectors.
 * @param itemCount Number of available contour segments.
 * @param seedIndices Preferred starting segments.
 * @param maxDepth Maximum combination depth.
 * @param maxEvaluations Maximum number of combinations to evaluate.
 * @param evaluator Callback that validates a combination.
 * @return Passing combinations expressed as original indices.
 */
fun pruningQuickCombine(
    itemCount: Int,
    seedIndices: List<Int>,
    maxDepth: Int,
    maxEvaluations: Int = 1000,
    evaluator: (List<Int>) -> Boolean,
): List<Set<Int>> {
    if (itemCount <= 0 || seedIndices.isEmpty()) return emptyList()
    val mapping = seedIndices.distinct() + (0 until itemCount).filterNot { it in seedIndices }
    val unknown = mapping.indices
        .take(seedIndices.distinct().size)
        .map { listOf(it) }
        .toMutableList()
    val results = mutableListOf<Set<Int>>()
    val pruned = mutableListOf<Set<Int>>()
    var evaluationCount = 0

    while (unknown.isNotEmpty() && evaluationCount <= maxEvaluations) {
        val currentPath = unknown.removeAt(unknown.lastIndex)
        evaluationCount += 1
        if (currentPath.size > maxDepth) continue
        val currentPathSet = currentPath.toSet()
        if (pruned.any { badPath -> currentPathSet.containsAll(badPath) }) continue

        val mapped = currentPath.map { mapping[it] }
        if (evaluator(mapped)) {
            results += mapped.toSet()
            val lastIndex = currentPath.last()
            for (next in (lastIndex + 1) until mapping.size) {
                unknown += (currentPath + next)
            }
        } else {
            pruned += currentPathSet
        }
    }

    return results
}

/**
 * Summary: Removes candidate sets that are strict subsets of other sets.
 * @param combinations Candidate combinations.
 * @return Maximal combinations only.
 */
fun filterSubsets(combinations: List<Set<Int>>): List<Set<Int>> {
    return combinations.filterIndexed { index, candidate ->
        combinations.indices.none { otherIndex ->
            if (otherIndex == index) return@none false
            val other = combinations[otherIndex]
            other.size >= candidate.size &&
                candidate.all { it in other } &&
                other != candidate
        }
    }
}

/**
 * Summary: Calculates the geometric confidence score.
 * @param ellipse Final fitted ellipse.
 * @param supportPixelCount Number of support pixels.
 * @param finalEdgeCount Number of final contour edges.
 * @param exponent Support ratio exponent.
 * @return Confidence score in [0, 0.99].
 */
fun computeConfidence(
    ellipse: PupilEllipse2D,
    supportPixelCount: Int,
    finalEdgeCount: Int,
    exponent: Double,
): Double {
    if (supportPixelCount <= 0 || finalEdgeCount <= 0) return 0.0
    val circumference = approximateEllipseCircumference(ellipse).coerceAtLeast(1e-6)
    val supportRatio = supportPixelCount / circumference
    val supportEdgeRatio = supportPixelCount.toDouble() / finalEdgeCount.toDouble()
    return min(0.99, supportRatio) * supportEdgeRatio.pow(exponent)
}

/**
 * Summary: Normalizes a pixel point to Pupil's flipped-Y coordinates.
 * @param x Pixel X.
 * @param y Pixel Y.
 * @param width Frame width.
 * @param height Frame height.
 * @return Pair of normalized coordinates.
 */
fun normalizeWithFlipY(x: Double, y: Double, width: Int, height: Int): Pair<Double, Double> {
    val safeWidth = max(1, width)
    val safeHeight = max(1, height)
    return Pair(x / safeWidth.toDouble(), 1.0 - (y / safeHeight.toDouble()))
}

/**
 * Summary: Extracts all non-zero edge points from a binary image.
 * @param binary Binary image.
 * @return Non-zero points in image coordinates.
 */
fun extractNonZeroPoints(binary: Mat): List<ContourPoint> {
    val nonZero = MatOfPoint()
    Core.findNonZero(binary, nonZero)
    val points = nonZero.toArray().map { ContourPoint(it.x, it.y) }
    nonZero.release()
    return points
}

/**
 * Summary: Fits an OpenCV ellipse from contour points.
 * @param points Contour points in image coordinates.
 * @return Fitted OpenCV ellipse or null on failure.
 */
fun fitEllipseOrNull(points: List<ContourPoint>): RotatedRect? {
    if (points.size < 5) return null
    val mat = MatOfPoint2f()
    mat.fromList(points.map { Point(it.x, it.y) })
    val ellipse = try {
        Imgproc.fitEllipse(mat)
    } catch (_: Throwable) {
        null
    }
    mat.release()
    return ellipse
}

/**
 * Summary: Resolves a single approximated contour back to real edge pixels.
 * @param contour Approximated contour in ROI coordinates.
 * @param edges Binary edge map.
 * @return Edge pixels that overlap the contour support mask.
 */
fun resolveContour(contour: List<ContourPoint>, edges: Mat): List<ContourPoint> {
    val supportMask = Mat.zeros(edges.rows(), edges.cols(), edges.type())
    val contourMat = contour.toMatOfPoint()
    Imgproc.polylines(supportMask, listOf(contourMat), false, org.opencv.core.Scalar(255.0), 2)
    val resolvedEdges = Mat()
    Core.min(edges, supportMask, resolvedEdges)
    val resolvedPoints = extractNonZeroPoints(resolvedEdges)
    contourMat.release()
    supportMask.release()
    resolvedEdges.release()
    return resolvedPoints
}

/**
 * Summary: Resolves a list of approximated contours back to real edge pixels.
 * @param contours Approximated contours in ROI coordinates.
 * @param edges Binary edge map.
 * @return Real edge pixels underneath the contour support mask.
 */
fun resolveContours(contours: List<List<ContourPoint>>, edges: Mat): List<ContourPoint> {
    if (contours.isEmpty()) return emptyList()
    val supportMask = Mat.zeros(edges.rows(), edges.cols(), edges.type())
    val contourMats = contours.map { contour -> contour.toMatOfPoint() }
    Imgproc.polylines(supportMask, contourMats, false, org.opencv.core.Scalar(255.0), 2)
    val resolvedEdges = Mat()
    Core.min(edges, supportMask, resolvedEdges)
    val resolvedPoints = extractNonZeroPoints(resolvedEdges)
    contourMats.forEach { it.release() }
    supportMask.release()
    resolvedEdges.release()
    return resolvedPoints
}

/**
 * Summary: Calculates histogram spike indices using the official threshold rule.
 * @param roiGray Grayscale ROI image.
 * @param amountIntensityValues Minimum histogram count to treat as a spike.
 * @return Lowest spike index, highest spike index, and peak histogram value.
 */
fun calculateSpikeIndicesAndMaxIntensity(roiGray: Mat, amountIntensityValues: Int = 40): Triple<Int, Int, Float> {
    val histogram = Mat()
    val histSize = MatOfInt(256)
    val channels = MatOfInt(0)
    val histRange = MatOfFloat(0f, 256f)
    Imgproc.calcHist(listOf(roiGray), channels, Mat(), histogram, histSize, histRange, false)

    var lowestSpikeIndex = 255
    var highestSpikeIndex = 0
    var maxIntensity = 0f
    var foundOne = false

    for (index in 0 until histogram.rows()) {
        val intensity = histogram.get(index, 0)?.firstOrNull()?.toFloat() ?: 0f
        if (intensity > amountIntensityValues) {
            maxIntensity = max(maxIntensity, intensity)
            lowestSpikeIndex = min(lowestSpikeIndex, index)
            highestSpikeIndex = max(highestSpikeIndex, index)
            foundOne = true
        }
    }

    histogram.release()
    histSize.release()
    channels.release()
    histRange.release()

    if (!foundOne) {
        return Triple(200, 255, maxIntensity)
    }
    return Triple(lowestSpikeIndex, highestSpikeIndex, maxIntensity)
}

/**
 * Summary: Narrows a large ROI using the official center-surround coarse detector.
 * @param gray Full-frame grayscale image.
 * @param roi Initial ROI.
 * @param params Current detector parameters.
 * @return Narrowed ROI when coarse detection succeeds, otherwise the original ROI.
 */
fun coarseDetectRoi(gray: Mat, roi: RoiBounds, params: Pupil2DParams): CoarseRoiResult {
    val safeRoi = roi.clamp(gray.cols(), gray.rows())
    if (!params.coarseDetection || safeRoi.width() * safeRoi.height() <= COARSE_MIN_AREA) {
        return CoarseRoiResult(safeRoi, false)
    }

    val roiRect = Rect(safeRoi.minX, safeRoi.minY, safeRoi.width(), safeRoi.height())
    val userRoiImage = gray.submat(roiRect)
    val downscaled = Mat()
    Imgproc.resize(
        userRoiImage,
        downscaled,
        Size(
            max(1.0, userRoiImage.cols() / COARSE_SCALE.toDouble()),
            max(1.0, userRoiImage.rows() / COARSE_SCALE.toDouble()),
        ),
        0.0,
        0.0,
        Imgproc.INTER_AREA,
    )

    val integral = Mat()
    Imgproc.integral(downscaled, integral, CvType.CV_32S)
    val integralWidth = integral.cols()
    val integralHeight = integral.rows()
    val integralData = IntArray(integralWidth * integralHeight)
    integral.get(0, 0, integralData)

    val minWidth = (params.coarseFilterMin / COARSE_SCALE).coerceAtLeast(3)
    val maxWidth = (params.coarseFilterMax / COARSE_SCALE).coerceAtLeast(minWidth + 1)
    val candidates = mutableListOf<CoarseCandidate>()
    var bestResponse = -10_000f

    val minHeight = max(1, minWidth / 3)
    val maxHeight = max(minHeight + 1, maxWidth / 3)

    for (height in minHeight until maxHeight step COARSE_H_STEP) {
        val width = height * 3
        if (width <= 0 || width >= integralWidth || width >= integralHeight) continue
        for (row in 0 until (integralHeight - width) step COARSE_STEP) {
            for (column in 0 until (integralWidth - width) step COARSE_STEP) {
                val outerArea = integralArea(integralData, integralWidth, row, column, 0, 0, width, width)
                val innerArea = integralArea(integralData, integralWidth, row, column, height, height, height * 2, height * 2)
                val response = (outerArea.toFloat() / (width * width).toFloat()) - (innerArea.toFloat() / (height * height).toFloat())
                if (response > bestResponse) {
                    bestResponse = response
                    candidates += CoarseCandidate(column, row, width, response)
                    if (candidates.size > COARSE_MAX_RESULTS) {
                        candidates.removeAt(0)
                    }
                }
            }
        }
    }

    val filtered = candidates.toMutableList()
    for (candidate in candidates.reversed()) {
        if (candidate.response < bestResponse * COARSE_MIN_RESPONSE_RATIO) {
            filtered.remove(candidate)
            continue
        }
        val fullyContainsAnother = filtered.any { other ->
            candidate !== other &&
                candidate.x < other.x &&
                candidate.y < other.y &&
                candidate.x + candidate.width > other.x + other.width &&
                candidate.y + candidate.width > other.y + other.width
        }
        if (fullyContainsAnother) {
            filtered.remove(candidate)
        }
    }

    val narrowedRoi = if (filtered.isNotEmpty()) {
        var minX = filtered.first().x
        var minY = filtered.first().y
        var maxX = filtered.first().x + filtered.first().width
        var maxY = filtered.first().y + filtered.first().width
        filtered.forEach { candidate ->
            minX = min(minX, candidate.x)
            minY = min(minY, candidate.y)
            maxX = max(maxX, candidate.x + candidate.width)
            maxY = max(maxY, candidate.y + candidate.width)
        }
        RoiBounds(
            minX = safeRoi.minX + (minX * COARSE_SCALE),
            minY = safeRoi.minY + (minY * COARSE_SCALE),
            maxX = safeRoi.minX + (maxX * COARSE_SCALE) - 1,
            maxY = safeRoi.minY + (maxY * COARSE_SCALE) - 1,
        ).clamp(gray.cols(), gray.rows())
    } else {
        safeRoi
    }

    userRoiImage.release()
    downscaled.release()
    integral.release()
    return CoarseRoiResult(narrowedRoi, narrowedRoi != safeRoi)
}

/**
 * Summary: Converts an OpenCV rotated rect into the detector's ellipse model.
 * @param rect OpenCV rotated rect.
 * @return Immutable ellipse model.
 */
fun RotatedRect.toPupilEllipse(): PupilEllipse2D {
    return PupilEllipse2D(
        centerX = center.x,
        centerY = center.y,
        axisMinor = size.width,
        axisMajor = size.height,
        angleDeg = ((angle.toDouble() + 90.0) % 180.0 + 180.0) % 180.0,
    )
}

/**
 * Summary: Converts contour points to an OpenCV point matrix.
 * @param none Extension receiver is the contour point list.
 * @return OpenCV MatOfPoint.
 */
fun List<ContourPoint>.toMatOfPoint(): MatOfPoint {
    val mat = MatOfPoint()
    mat.fromList(map { Point(it.x, it.y) })
    return mat
}

/**
 * Summary: Calculates one rectangular sum on an integral image.
 * @param data Flattened integral image data.
 * @param stride Integral image row stride.
 * @param offsetRow Window top row.
 * @param offsetCol Window left column.
 * @param startRow Window-relative start row.
 * @param startCol Window-relative start col.
 * @param endRow Window-relative end row.
 * @param endCol Window-relative end col.
 * @return Integral sum for the rectangle.
 */
private fun integralArea(
    data: IntArray,
    stride: Int,
    offsetRow: Int,
    offsetCol: Int,
    startRow: Int,
    startCol: Int,
    endRow: Int,
    endCol: Int,
): Int {
    val row1 = offsetRow + startRow
    val col1 = offsetCol + startCol
    val row2 = offsetRow + endRow
    val col2 = offsetCol + endCol
    return data[(row2 * stride) + col2] +
        data[(row1 * stride) + col1] -
        data[(row1 * stride) + col2] -
        data[(row2 * stride) + col1]
}
