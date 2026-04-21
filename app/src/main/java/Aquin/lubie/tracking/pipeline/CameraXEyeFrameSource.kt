package Aquin.lubie.tracking.pipeline

import Aquin.lubie.tracking.model.EyeFrame
import android.content.Context
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService

private const val DEFAULT_ANALYSIS_WIDTH = 640
private const val DEFAULT_ANALYSIS_HEIGHT = 480

/**
 * Summary: Emits grayscale eye frames from CameraX for live simulation.
 * @param context Application context.
 * @param lifecycleOwner Lifecycle owner used for CameraX binding.
 * @param previewView Preview surface for live rendering.
 * @param preprocessor Frame preprocessor for grayscale conversion.
 * @param executor Shared serial executor used by the controller.
 * @return Camera-backed eye frame source.
 */
class CameraXEyeFrameSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val preprocessor: EyePreprocessor,
    private val executor: ExecutorService,
) : EyeFrameSource {

    override var sourceTag: String = "camera_pending"
        private set

    private var frameConsumer: ((EyeFrame) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var started: Boolean = false
    private var currentLensFacing: Int = CameraSelector.LENS_FACING_FRONT
    private var frameCounter: Long = 0L

    /**
     * Summary: Returns whether the live preview should be mirrored.
     * @param none No parameters.
     * @return True for the front camera.
     */
    fun isPreviewMirrored(): Boolean {
        return currentLensFacing == CameraSelector.LENS_FACING_FRONT
    }

    override fun start(
        frameConsumer: (EyeFrame) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (started) return
        started = true
        this.frameConsumer = frameConsumer
        this.onError = onError
        frameCounter = 0L

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    bindUseCases(provider)
                } catch (_: Throwable) {
                    started = false
                    onError("Failed to start live eye source")
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    override fun stop() {
        started = false
        cameraProvider?.unbindAll()
        frameConsumer = null
        onError = null
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        provider.unbindAll()
        val selector = selectCamera(provider)
        val preview = Preview.Builder()
            .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }

        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
            .setTargetResolution(Size(DEFAULT_ANALYSIS_WIDTH, DEFAULT_ANALYSIS_HEIGHT))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(executor) { imageProxy ->
            val consumer = frameConsumer
            if (!started || consumer == null) {
                imageProxy.close()
                return@setAnalyzer
            }

            val gray = try {
                preprocessor.toGray(imageProxy)
            } catch (_: Throwable) {
                imageProxy.close()
                onError?.invoke("Camera frame conversion failed")
                return@setAnalyzer
            }
            val timestampNs = imageProxy.imageInfo.timestamp
            imageProxy.close()

            consumer(
                EyeFrame(
                    frameId = frameCounter++,
                    timestampNs = timestampNs,
                    width = gray.cols(),
                    height = gray.rows(),
                    grayMat = gray,
                    sourceTag = sourceTag,
                ),
            )
        }

        val viewPort = previewView.viewPort
        if (viewPort != null) {
            val group = UseCaseGroup.Builder()
                .setViewPort(viewPort)
                .addUseCase(preview)
                .addUseCase(analysis)
                .build()
            provider.bindToLifecycle(lifecycleOwner, selector, group)
        } else {
            provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        }
    }

    private fun selectCamera(provider: ProcessCameraProvider): CameraSelector {
        val front = CameraSelector.DEFAULT_FRONT_CAMERA
        val back = CameraSelector.DEFAULT_BACK_CAMERA
        return when {
            provider.hasCamera(front) -> {
                currentLensFacing = CameraSelector.LENS_FACING_FRONT
                sourceTag = "camera_front"
                front
            }

            provider.hasCamera(back) -> {
                currentLensFacing = CameraSelector.LENS_FACING_BACK
                sourceTag = "camera_back"
                back
            }

            else -> throw IllegalStateException("No available live camera source")
        }
    }
}
