package Aquin.lubie.tracking.segmentation

import Aquin.lubie.tracking.model.RoiBounds
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val INPUT_NAME = "input"
private const val MODEL_OUTPUT_WIDTH = 384
private const val MODEL_OUTPUT_HEIGHT = 240
private const val MODEL_INPUT_CHANNELS = 1
private const val MODEL_OUTPUT_CLASSES = 4
private const val IRIS_CLASS_ID = 2
private const val PUPIL_CLASS_ID = 3
private const val NANOS_TO_MILLIS = 1_000_000.0

private val INPUT_SHAPE = longArrayOf(1L, MODEL_INPUT_CHANNELS.toLong(), MODEL_OUTPUT_HEIGHT.toLong(), MODEL_OUTPUT_WIDTH.toLong())

/**
 * Summary: Runs ONNX Runtime eye segmentation on grayscale ROI inputs.
 * @param context Application context used for model asset preparation.
 * @return Session-caching segmentation runner for the Android MVP.
 */
class EyeSegmentationRunner(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val modelAssetRepository = ModelAssetRepository(appContext)
    private val sessions = mutableMapOf<SegmentationConfig, OrtSession>()
    private val sessionStatuses = mutableMapOf<SegmentationConfig, SegmentationSessionStatus>()
    private val sessionMessages = mutableMapOf<SegmentationConfig, String>()
    private val inputPixels = ByteArray(MODEL_OUTPUT_WIDTH * MODEL_OUTPUT_HEIGHT)
    private val inputBuffer = ByteBuffer.allocateDirect(MODEL_OUTPUT_WIDTH * MODEL_OUTPUT_HEIGHT * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val grayscalePreviewPixels = IntArray(MODEL_OUTPUT_WIDTH * MODEL_OUTPUT_HEIGHT)
    private val overlayPixels = IntArray(MODEL_OUTPUT_WIDTH * MODEL_OUTPUT_HEIGHT)
    private val blendedPreviewPixels = IntArray(MODEL_OUTPUT_WIDTH * MODEL_OUTPUT_HEIGHT)
    private val grayscalePreviewBitmap = Bitmap.createBitmap(
        MODEL_OUTPUT_WIDTH,
        MODEL_OUTPUT_HEIGHT,
        Bitmap.Config.ARGB_8888,
    )
    private val overlayBitmap = Bitmap.createBitmap(
        MODEL_OUTPUT_WIDTH,
        MODEL_OUTPUT_HEIGHT,
        Bitmap.Config.ARGB_8888,
    )
    private val blendedPreviewBitmap = Bitmap.createBitmap(
        MODEL_OUTPUT_WIDTH,
        MODEL_OUTPUT_HEIGHT,
        Bitmap.Config.ARGB_8888,
    )

    /**
     * Summary: Returns the bundled segmentation models that can be selected in the UI.
     * @param none No parameters.
     * @return Available model types backed by app assets.
     */
    fun availableModelTypes(): List<SegmentationModelType> = modelAssetRepository.availableModelTypes()

    /**
     * Summary: Returns the current session status for the requested config.
     * @param config Requested segmentation config.
     * @return Current session status.
     */
    fun sessionStatus(config: SegmentationConfig): SegmentationSessionStatus {
        return sessionStatuses[config] ?: SegmentationSessionStatus.IDLE
    }

    /**
     * Summary: Returns the latest human-readable session message for the requested config.
     * @param config Requested segmentation config.
     * @return Latest session message or null when unavailable.
     */
    fun sessionMessage(config: SegmentationConfig): String? = sessionMessages[config]

    /**
     * Summary: Clears a failed status so the config can be retried on the next frame.
     * @param config Requested segmentation config.
     * @return Unit.
     */
    fun prepareConfig(config: SegmentationConfig) {
        if (!sessions.containsKey(config)) {
            sessionStatuses[config] = SegmentationSessionStatus.IDLE
            sessionMessages.remove(config)
        }
    }

    /**
     * Summary: Runs segmentation on the selected grayscale ROI.
     * @param gray Full-frame grayscale image.
     * @param roiBounds Manual segmentation ROI in frame coordinates.
     * @param config Active segmentation config.
     * @return Segmentation result for the requested ROI.
     */
    fun run(
        gray: Mat,
        roiBounds: RoiBounds,
        config: SegmentationConfig,
    ): SegmentationResult {
        val safeRoi = roiBounds.clamp(gray.cols(), gray.rows())
        val rect = Rect(safeRoi.minX, safeRoi.minY, safeRoi.width(), safeRoi.height())
        val roiView = gray.submat(rect)
        val resized = Mat()

        try {
            val session = ensureSession(config)

            val preprocessStartNs = SystemClock.elapsedRealtimeNanos()
            Imgproc.resize(
                roiView,
                resized,
                Size(MODEL_OUTPUT_WIDTH.toDouble(), MODEL_OUTPUT_HEIGHT.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_LINEAR,
            )
            writeInputBuffer(resized)
            val inputTensor = OnnxTensor.createTensor(environment, inputBuffer, INPUT_SHAPE)
            val preprocessMs = (SystemClock.elapsedRealtimeNanos() - preprocessStartNs) / NANOS_TO_MILLIS

            try {
                val inferenceStartNs = SystemClock.elapsedRealtimeNanos()
                val sessionResult = session.run(mapOf(INPUT_NAME to inputTensor))
                val inferenceMs = (SystemClock.elapsedRealtimeNanos() - inferenceStartNs) / NANOS_TO_MILLIS

                try {
                    val postprocessStartNs = SystemClock.elapsedRealtimeNanos()
                    val outputTensor = sessionResult.get(0) as OnnxTensor
                    val parsedOutput = parseOutput(outputTensor)
                    val postprocessMs = (SystemClock.elapsedRealtimeNanos() - postprocessStartNs) / NANOS_TO_MILLIS

                    return SegmentationResult(
                        labelMap = parsedOutput.labelMap,
                        overlayBitmap = overlayBitmap,
                        grayscalePreviewBitmap = grayscalePreviewBitmap,
                        blendedPreviewBitmap = blendedPreviewBitmap,
                        roiBounds = safeRoi,
                        roiWidth = safeRoi.width(),
                        roiHeight = safeRoi.height(),
                        hasIris = parsedOutput.hasIris,
                        hasPupil = parsedOutput.hasPupil,
                        timing = SegmentationTiming(
                            preprocessMs = preprocessMs,
                            inferenceMs = inferenceMs,
                            postprocessMs = postprocessMs,
                        ),
                    )
                } finally {
                    sessionResult.close()
                }
            } finally {
                inputTensor.close()
            }
        } finally {
            resized.release()
            roiView.release()
        }
    }

    /**
     * Summary: Releases cached sessions and reusable bitmap resources.
     * @param none No parameters.
     * @return Unit.
     */
    override fun close() {
        sessions.values.forEach { session ->
            session.close()
        }
        sessions.clear()
        sessionStatuses.clear()
        sessionMessages.clear()
        grayscalePreviewBitmap.recycle()
        overlayBitmap.recycle()
        blendedPreviewBitmap.recycle()
    }

    private fun ensureSession(config: SegmentationConfig): OrtSession {
        sessions[config]?.let { existing ->
            sessionStatuses[config] = SegmentationSessionStatus.READY
            return existing
        }

        if (sessionStatuses[config] == SegmentationSessionStatus.FAILED) {
            throw IllegalStateException(sessionMessages[config] ?: "Session initialization failed")
        }

        sessionStatuses[config] = SegmentationSessionStatus.INITIALIZING
        return try {
            val localModelFiles = modelAssetRepository.ensureLocalModelFiles(config.modelType)
                ?: throw IllegalStateException("${config.modelType.displayName} model asset is missing")
            val options = OrtSession.SessionOptions()
            try {
                if (config.backend == SegmentationBackend.NNAPI) {
                    options.addNnapi()
                }
                val session = environment.createSession(localModelFiles.modelFile.absolutePath, options)
                sessions[config] = session
                sessionStatuses[config] = SegmentationSessionStatus.READY
                sessionMessages[config] = "${config.modelType.displayName} ${config.backend.displayName} ready"
                session
            } finally {
                options.close()
            }
        } catch (throwable: Throwable) {
            sessionStatuses[config] = SegmentationSessionStatus.FAILED
            sessionMessages[config] = throwable.message ?: "Session initialization failed"
            throw IllegalStateException(sessionMessages[config], throwable)
        }
    }

    private fun writeInputBuffer(resizedGray: Mat) {
        resizedGray.get(0, 0, inputPixels)
        inputBuffer.clear()
        inputPixels.forEachIndexed { index, value ->
            val gray = value.toInt() and 0xFF
            inputBuffer.put(gray / 255f)
            grayscalePreviewPixels[index] = grayToArgb(gray)
            blendedPreviewPixels[index] = grayscalePreviewPixels[index]
        }
        grayscalePreviewBitmap.setPixels(
            grayscalePreviewPixels,
            0,
            MODEL_OUTPUT_WIDTH,
            0,
            0,
            MODEL_OUTPUT_WIDTH,
            MODEL_OUTPUT_HEIGHT,
        )
        inputBuffer.rewind()
    }

    private fun parseOutput(outputTensor: OnnxTensor): ParsedOutput {
        val outputBuffer = outputTensor.floatBuffer
        val labelMap = ByteArray(MODEL_OUTPUT_WIDTH * MODEL_OUTPUT_HEIGHT)
        outputBuffer.rewind()

        var hasIris = false
        var hasPupil = false
        val classPlaneSize = MODEL_OUTPUT_WIDTH * MODEL_OUTPUT_HEIGHT

        for (pixelIndex in 0 until classPlaneSize) {
            var bestClass = 0
            var bestValue = outputBuffer.get(pixelIndex)
            for (classIndex in 1 until MODEL_OUTPUT_CLASSES) {
                val candidateValue = outputBuffer.get((classIndex * classPlaneSize) + pixelIndex)
                if (candidateValue > bestValue) {
                    bestValue = candidateValue
                    bestClass = classIndex
                }
            }

            labelMap[pixelIndex] = bestClass.toByte()
            overlayPixels[pixelIndex] = overlayColor(bestClass)
            blendedPreviewPixels[pixelIndex] = blendArgb(
                baseColor = grayscalePreviewPixels[pixelIndex],
                overlayColor = overlayPixels[pixelIndex],
            )
            if (bestClass == IRIS_CLASS_ID) {
                hasIris = true
            }
            if (bestClass == PUPIL_CLASS_ID) {
                hasPupil = true
            }
        }

        overlayBitmap.setPixels(
            overlayPixels,
            0,
            MODEL_OUTPUT_WIDTH,
            0,
            0,
            MODEL_OUTPUT_WIDTH,
            MODEL_OUTPUT_HEIGHT,
        )
        blendedPreviewBitmap.setPixels(
            blendedPreviewPixels,
            0,
            MODEL_OUTPUT_WIDTH,
            0,
            0,
            MODEL_OUTPUT_WIDTH,
            MODEL_OUTPUT_HEIGHT,
        )

        return ParsedOutput(
            labelMap = labelMap,
            hasIris = hasIris,
            hasPupil = hasPupil,
        )
    }

    private fun overlayColor(classId: Int): Int {
        return when (classId) {
            1 -> 0x332196F3
            IRIS_CLASS_ID -> 0x8832CD32.toInt()
            PUPIL_CLASS_ID -> 0x99FF3B30.toInt()
            else -> 0x00000000
        }
    }

    private fun grayToArgb(gray: Int): Int {
        return (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
    }

    private fun blendArgb(
        baseColor: Int,
        overlayColor: Int,
    ): Int {
        val alpha = (overlayColor ushr 24) and 0xFF
        if (alpha == 0) return baseColor

        val inverseAlpha = 255 - alpha
        val baseRed = (baseColor ushr 16) and 0xFF
        val baseGreen = (baseColor ushr 8) and 0xFF
        val baseBlue = baseColor and 0xFF
        val overlayRed = (overlayColor ushr 16) and 0xFF
        val overlayGreen = (overlayColor ushr 8) and 0xFF
        val overlayBlue = overlayColor and 0xFF

        val red = ((overlayRed * alpha) + (baseRed * inverseAlpha) + 127) / 255
        val green = ((overlayGreen * alpha) + (baseGreen * inverseAlpha) + 127) / 255
        val blue = ((overlayBlue * alpha) + (baseBlue * inverseAlpha) + 127) / 255
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private data class ParsedOutput(
        val labelMap: ByteArray,
        val hasIris: Boolean,
        val hasPupil: Boolean,
    )
}
