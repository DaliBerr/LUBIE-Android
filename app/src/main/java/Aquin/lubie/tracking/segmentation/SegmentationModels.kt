package Aquin.lubie.tracking.segmentation

import Aquin.lubie.tracking.model.RoiBounds
import android.graphics.Bitmap

/**
 * Summary: Identifies the supported segmentation model assets.
 * @param assetFileName Asset file name copied into app private storage.
 * @param displayName Human-readable model label for the debug UI.
 * @return Supported segmentation model type.
 */
enum class SegmentationModelType(
    val assetFileName: String,
    val displayName: String,
) {
    B16_INT8_QDQ(
        assetFileName = "unet_b16_384x240_int8_qdq.onnx",
        displayName = "B16 INT8",
    ),
    B8_INT8_QDQ(
        assetFileName = "unet_b8_384x240_int8_qdq.onnx",
        displayName = "B8 INT8",
    ),
    FP32(
        assetFileName = "unet_b16_384x240_fp32.onnx",
        displayName = "FP32",
    ),
}

/**
 * Summary: Identifies the supported execution backends.
 * @param displayName Human-readable backend label for the debug UI.
 * @return Supported segmentation backend.
 */
enum class SegmentationBackend(val displayName: String) {
    NNAPI("NNAPI"),
    CPU("CPU"),
}

/**
 * Summary: Tracks the lifecycle of one ONNX Runtime session.
 * @param displayName Human-readable session state label.
 * @return Session initialization state.
 */
enum class SegmentationSessionStatus(val displayName: String) {
    IDLE("idle"),
    INITIALIZING("initializing"),
    READY("ready"),
    FAILED("failed"),
}

/**
 * Summary: Stores the active segmentation model and backend pair.
 * @param modelType Selected model type.
 * @param backend Selected inference backend.
 * @return Immutable segmentation execution config.
 */
data class SegmentationConfig(
    val modelType: SegmentationModelType = SegmentationModelType.B16_INT8_QDQ,
    val backend: SegmentationBackend = SegmentationBackend.NNAPI,
)

/**
 * Summary: Stores one frame's segmentation timing breakdown.
 * @param preprocessMs Preprocess duration in milliseconds.
 * @param inferenceMs Model inference duration in milliseconds.
 * @param postprocessMs Postprocess duration in milliseconds.
 * @return Immutable per-frame timing snapshot.
 */
data class SegmentationTiming(
    val preprocessMs: Double,
    val inferenceMs: Double,
    val postprocessMs: Double,
) {
    /**
     * Summary: Calculates the total segmentation latency for the frame.
     * @param none No parameters.
     * @return Total preprocess + inference + postprocess latency.
     */
    fun totalMs(): Double = preprocessMs + inferenceMs + postprocessMs
}

/**
 * Summary: Stores running average latency stats for the current validation run.
 * @param frameCount Number of processed segmentation frames included in the averages.
 * @param averagePreprocessMs Average preprocess latency.
 * @param averageInferenceMs Average inference latency.
 * @param averagePostprocessMs Average postprocess latency.
 * @param averageTotalMs Average total latency.
 * @return Immutable average latency snapshot.
 */
data class SegmentationPerformanceSummary(
    val frameCount: Int = 0,
    val averagePreprocessMs: Double = 0.0,
    val averageInferenceMs: Double = 0.0,
    val averagePostprocessMs: Double = 0.0,
    val averageTotalMs: Double = 0.0,
    val latestOutputFps: Double = 0.0,
    val averageOutputFps: Double = 0.0,
)

/**
 * Summary: Stores one frame's segmentation output and ROI-scoped overlay.
 * @param labelMap Argmax label map in model output resolution.
 * @param overlayBitmap Semi-transparent visualization bitmap aligned to the ROI.
 * @param roiBounds ROI used for preprocessing and overlay placement.
 * @param roiWidth Source ROI width in pixels.
 * @param roiHeight Source ROI height in pixels.
 * @param hasIris Whether class 2 is present in the label map.
 * @param hasPupil Whether class 3 is present in the label map.
 * @param timing Per-frame timing breakdown.
 * @return Immutable segmentation result snapshot.
 */
data class SegmentationResult(
    val labelMap: ByteArray,
    val overlayBitmap: Bitmap,
    val grayscalePreviewBitmap: Bitmap,
    val blendedPreviewBitmap: Bitmap,
    val roiBounds: RoiBounds,
    val roiWidth: Int,
    val roiHeight: Int,
    val hasIris: Boolean,
    val hasPupil: Boolean,
    val timing: SegmentationTiming,
)

/**
 * Summary: Stores the UI-facing segmentation state for the debug page.
 * @param config Active segmentation config.
 * @param sessionStatus Current session initialization status.
 * @param statusMessage Human-readable segmentation status text.
 * @param selectedRoi User-selected manual segmentation ROI.
 * @param latestResult Latest segmentation frame result when available.
 * @param performanceSummary Running average timing summary.
 * @param availableModelTypes Model types currently bundled in assets.
 * @return Immutable segmentation debug UI state.
 */
data class SegmentationUiState(
    val config: SegmentationConfig = SegmentationConfig(),
    val sessionStatus: SegmentationSessionStatus = SegmentationSessionStatus.IDLE,
    val statusMessage: String = "ROI not set",
    val selectedRoi: RoiBounds? = null,
    val latestResult: SegmentationResult? = null,
    val performanceSummary: SegmentationPerformanceSummary = SegmentationPerformanceSummary(),
    val availableModelTypes: List<SegmentationModelType> = emptyList(),
)

/**
 * Summary: Tracks running latency averages without allocating per-frame history.
 * @param none No constructor parameters.
 * @return Mutable timing accumulator for the active validation run.
 */
class SegmentationPerformanceTracker {

    private var frameCount: Int = 0
    private var totalPreprocessMs: Double = 0.0
    private var totalInferenceMs: Double = 0.0
    private var totalPostprocessMs: Double = 0.0
    private var previousOutputTimestampNs: Long? = null
    private var latestOutputIntervalNs: Long? = null
    private var totalOutputIntervalNs: Long = 0L
    private var outputIntervalCount: Int = 0

    /**
     * Summary: Clears all accumulated latency stats.
     * @param none No parameters.
     * @return Unit.
     */
    fun reset() {
        frameCount = 0
        totalPreprocessMs = 0.0
        totalInferenceMs = 0.0
        totalPostprocessMs = 0.0
        previousOutputTimestampNs = null
        latestOutputIntervalNs = null
        totalOutputIntervalNs = 0L
        outputIntervalCount = 0
    }

    /**
     * Summary: Adds one frame timing sample to the running averages.
     * @param timing Per-frame timing breakdown.
     * @param outputTimestampNs Monotonic timestamp when this segmentation output became available.
     * @return Unit.
     */
    fun record(
        timing: SegmentationTiming,
        outputTimestampNs: Long = System.nanoTime(),
    ) {
        frameCount += 1
        totalPreprocessMs += timing.preprocessMs
        totalInferenceMs += timing.inferenceMs
        totalPostprocessMs += timing.postprocessMs
        previousOutputTimestampNs?.let { previousTimestamp ->
            val intervalNs = (outputTimestampNs - previousTimestamp).coerceAtLeast(0L)
            latestOutputIntervalNs = intervalNs
            totalOutputIntervalNs += intervalNs
            outputIntervalCount += 1
        }
        previousOutputTimestampNs = outputTimestampNs
    }

    /**
     * Summary: Returns the current average latency snapshot.
     * @param none No parameters.
     * @return Immutable average latency summary.
     */
    fun summary(): SegmentationPerformanceSummary {
        if (frameCount <= 0) {
            return SegmentationPerformanceSummary()
        }
        val averagePreprocessMs = totalPreprocessMs / frameCount.toDouble()
        val averageInferenceMs = totalInferenceMs / frameCount.toDouble()
        val averagePostprocessMs = totalPostprocessMs / frameCount.toDouble()
        return SegmentationPerformanceSummary(
            frameCount = frameCount,
            averagePreprocessMs = averagePreprocessMs,
            averageInferenceMs = averageInferenceMs,
            averagePostprocessMs = averagePostprocessMs,
            averageTotalMs = averagePreprocessMs + averageInferenceMs + averagePostprocessMs,
            latestOutputFps = latestOutputIntervalNs.toFps(),
            averageOutputFps = if (outputIntervalCount > 0) {
                totalOutputIntervalNs.toDouble().let { totalInterval ->
                    if (totalInterval <= 0.0) 0.0 else (outputIntervalCount * 1_000_000_000.0) / totalInterval
                }
            } else {
                0.0
            },
        )
    }

    private fun Long?.toFps(): Double {
        val interval = this ?: return 0.0
        return if (interval <= 0L) 0.0 else 1_000_000_000.0 / interval.toDouble()
    }
}
