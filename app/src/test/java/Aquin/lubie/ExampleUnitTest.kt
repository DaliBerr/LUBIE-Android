package Aquin.lubie

import Aquin.lubie.tracking.model.ContourPoint
import Aquin.lubie.tracking.model.PupilEllipse2D
import Aquin.lubie.tracking.model.RoiBounds
import Aquin.lubie.tracking.pipeline.computeConfidence
import Aquin.lubie.tracking.pipeline.findKinksAndDirectionChanges
import Aquin.lubie.tracking.pipeline.normalizeWithFlipY
import Aquin.lubie.tracking.pipeline.pruningQuickCombine
import Aquin.lubie.tracking.pipeline.splitAtCornerIndices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    /**
     * Summary: Verifies ROI bounds stay inside a frame.
     * @param none No parameters.
     * @return Unit.
     */
    @Test
    fun roiClamp_keepsBoundsInsideFrame() {
        val roi = RoiBounds(-4, 3, 120, 90).clamp(frameWidth = 64, frameHeight = 32)
        assertEquals(0, roi.minX)
        assertEquals(3, roi.minY)
        assertEquals(63, roi.maxX)
        assertEquals(31, roi.maxY)
    }

    /**
     * Summary: Verifies normalization follows Pupil's flipped-Y convention.
     * @param none No parameters.
     * @return Unit.
     */
    @Test
    fun normalizeWithFlipY_flipsVerticalAxis() {
        val (x, y) = normalizeWithFlipY(x = 96.0, y = 48.0, width = 192, height = 192)
        assertEquals(0.5, x, 1e-6)
        assertEquals(0.75, y, 1e-6)
    }

    /**
     * Summary: Verifies contour splitting returns multiple segments when kinks exist.
     * @param none No parameters.
     * @return Unit.
     */
    @Test
    fun splitAtCornerIndices_splitsPolylineAtDetectedCorners() {
        val contour = listOf(
            ContourPoint(0.0, 0.0),
            ContourPoint(0.0, 2.0),
            ContourPoint(2.0, 2.0),
            ContourPoint(4.0, 2.0),
            ContourPoint(4.0, 4.0),
            ContourPoint(2.0, 6.0),
            ContourPoint(2.0, 8.0),
        )
        val splitIndices = findKinksAndDirectionChanges(
            angles = listOf(-90.0, 180.0, -90.0, -45.0, 135.0),
            angleThresholdDeg = 80.0,
        )
        val segments = splitAtCornerIndices(contour, splitIndices)
        assertTrue(segments.size >= 2)
        assertFalse(segments.any { it.isEmpty() })
    }

    /**
     * Summary: Verifies the confidence formula produces a positive bounded score.
     * @param none No parameters.
     * @return Unit.
     */
    @Test
    fun computeConfidence_returnsBoundedValue() {
        val ellipse = PupilEllipse2D(
            centerX = 10.0,
            centerY = 12.0,
            axisMinor = 20.0,
            axisMajor = 30.0,
            angleDeg = 15.0,
        )
        val confidence = computeConfidence(
            ellipse = ellipse,
            supportPixelCount = 42,
            finalEdgeCount = 50,
            exponent = 1.0,
        )
        assertTrue(confidence > 0.0)
        assertTrue(confidence <= 0.99)
    }

    /**
     * Summary: Verifies pruning search can expand valid seed combinations.
     * @param none No parameters.
     * @return Unit.
     */
    @Test
    fun pruningQuickCombine_discoversPassingCombinations() {
        val combinations = pruningQuickCombine(
            itemCount = 4,
            seedIndices = listOf(0),
            maxDepth = 3,
        ) { indices ->
            indices.sum() <= 3
        }
        assertTrue(combinations.isNotEmpty())
        assertTrue(combinations.any { it.contains(0) })
    }
}
