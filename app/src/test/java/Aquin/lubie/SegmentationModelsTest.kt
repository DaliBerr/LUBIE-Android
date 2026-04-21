package Aquin.lubie

import Aquin.lubie.tracking.segmentation.SegmentationPerformanceTracker
import Aquin.lubie.tracking.segmentation.SegmentationTiming
import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentationModelsTest {

    /**
     * Summary: Verifies segmentation timing averages accumulate as expected.
     * @param none No parameters.
     * @return Unit.
     */
    @Test
    fun segmentationPerformanceTracker_computesAverages() {
        val tracker = SegmentationPerformanceTracker()
        tracker.record(
            SegmentationTiming(preprocessMs = 1.0, inferenceMs = 2.0, postprocessMs = 3.0),
            outputTimestampNs = 1_000_000_000L,
        )
        tracker.record(
            SegmentationTiming(preprocessMs = 3.0, inferenceMs = 4.0, postprocessMs = 5.0),
            outputTimestampNs = 1_500_000_000L,
        )

        val summary = tracker.summary()
        assertEquals(2, summary.frameCount)
        assertEquals(2.0, summary.averagePreprocessMs, 1e-6)
        assertEquals(3.0, summary.averageInferenceMs, 1e-6)
        assertEquals(4.0, summary.averagePostprocessMs, 1e-6)
        assertEquals(9.0, summary.averageTotalMs, 1e-6)
        assertEquals(2.0, summary.latestOutputFps, 1e-6)
        assertEquals(2.0, summary.averageOutputFps, 1e-6)
    }
}
