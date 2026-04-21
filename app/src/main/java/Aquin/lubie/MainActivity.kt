package Aquin.lubie

import Aquin.lubie.databinding.ActivityMainBinding
import Aquin.lubie.tracking.model.EyeSourceMode
import Aquin.lubie.tracking.model.EyeTrackingRenderState
import Aquin.lubie.tracking.model.Pupil2DParams
import Aquin.lubie.tracking.model.PupilObservation2D
import Aquin.lubie.tracking.pipeline.EyeTrackingController
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import java.util.Locale

/**
 * Summary: Hosts the near-eye live/replay debug page with ONNX segmentation validation controls.
 * @return Main single-activity entry for the MVP.
 */
class MainActivity : ComponentActivity(), EyeTrackingController.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: EyeTrackingController

    private var currentParams = Pupil2DParams()
    private var openCvReady: Boolean = false
    private var desiredMode: EyeSourceMode = EyeSourceMode.LIVE
    private var latestRenderState: EyeTrackingRenderState? = null
    private var roiSelectionEnabled: Boolean = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            if (openCvReady && desiredMode == EyeSourceMode.LIVE) {
                controller.startLive()
                binding.textOpenCvStatus.text = getString(R.string.camera_starting)
            }
        } else {
            binding.textOpenCvStatus.text = getString(R.string.camera_permission_required)
            clearOverlay(getString(R.string.camera_permission_required))
        }
    }

    /**
     * Summary: Creates the page and wires the tracking controller.
     * @param savedInstanceState Saved activity state.
     * @return Unit.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cameraPreview.scaleType = PreviewView.ScaleType.FILL_CENTER
        controller = EyeTrackingController(
            context = this,
            lifecycleOwner = this,
            previewView = binding.cameraPreview,
            listener = this,
        )

        initializeControls()
        initializeOpenCv()
        applyModeVisibility(desiredMode)
        updateActionButtons(null)
        clearOverlay(getString(R.string.status_processing))
    }

    /**
     * Summary: Restarts live mode after the activity returns to foreground.
     * @param none No parameters.
     * @return Unit.
     */
    override fun onResume() {
        super.onResume()
        if (!openCvReady) return
        if (desiredMode == EyeSourceMode.LIVE && hasCameraPermission()) {
            controller.startLive()
            binding.textOpenCvStatus.text = getString(R.string.camera_starting)
        }
    }

    /**
     * Summary: Stops active camera/replay work while keeping controller state.
     * @param none No parameters.
     * @return Unit.
     */
    override fun onPause() {
        super.onPause()
        controller.pause()
    }

    /**
     * Summary: Releases controller resources when the activity is destroyed.
     * @param none No parameters.
     * @return Unit.
     */
    override fun onDestroy() {
        controller.destroy()
        super.onDestroy()
    }

    /**
     * Summary: Receives render updates from the tracking controller.
     * @param state Latest render state.
     * @return Unit.
     */
    override fun onRenderState(state: EyeTrackingRenderState) {
        latestRenderState = state
        desiredMode = state.mode

        val isReplay = state.mode == EyeSourceMode.REPLAY
        applyModeVisibility(state.mode)
        binding.replayImageView.setImageBitmap(if (isReplay) state.replayBitmap else null)

        binding.textFrameInfo.text = buildFrameInfo(state)
        binding.textResult2d.text = buildResultText(state)
        binding.textDebugInfo.text = buildDebugText(state)
        binding.textOpenCvStatus.text = buildHeaderStatus(state)
        binding.segmentationPreviewPanel.visibility = if (state.segmentationUiState.selectedRoi != null) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.imageSegmentationGrayPreview.setImageBitmap(
            state.segmentationUiState.latestResult?.grayscalePreviewBitmap,
        )
        binding.imageSegmentationOverlayPreview.setImageBitmap(
            state.segmentationUiState.latestResult?.blendedPreviewBitmap,
        )

        binding.overlayView.updateOverlay(
            frameWidth = state.observation.frameWidth,
            frameHeight = state.observation.frameHeight,
            detectionRoi = if (state.detection2dEnabled) state.observation.debug.effectiveRoi else null,
            manualSegmentationRoi = state.manualSegmentationRoi,
            pupil = state.observation.pupil,
            segmentationOverlayBitmap = state.segmentationUiState.latestResult?.overlayBitmap,
            statusText = buildOverlayStatus(state),
            mirrorHorizontally = state.mirrorHorizontally,
        )

        updateActionButtons(state)
    }

    /**
     * Summary: Receives controller status or error messages.
     * @param message Human-readable status text.
     * @return Unit.
     */
    override fun onControllerMessage(message: String) {
        binding.textOpenCvStatus.text = message
    }

    /**
     * Summary: Initializes UI event listeners and the parameter sliders.
     * @param none No parameters.
     * @return Unit.
     */
    private fun initializeControls() {
        binding.buttonLive.setOnClickListener {
            setRoiSelectionEnabled(false)
            desiredMode = EyeSourceMode.LIVE
            applyModeVisibility(desiredMode)
            updateActionButtons(latestRenderState)
            ensureLiveMode()
        }
        binding.buttonRecord.setOnClickListener {
            controller.toggleRecording()
        }
        binding.buttonReplay.setOnClickListener {
            setRoiSelectionEnabled(false)
            desiredMode = EyeSourceMode.REPLAY
            applyModeVisibility(desiredMode)
            updateActionButtons(latestRenderState)
            controller.startReplayLatest()
        }
        binding.buttonResetTracking.setOnClickListener {
            controller.resetTracking()
        }
        binding.buttonReplayPlayPause.setOnClickListener {
            controller.toggleReplayPlayback()
        }
        binding.buttonReplayPrev.setOnClickListener {
            controller.stepReplayBackward()
        }
        binding.buttonReplayNext.setOnClickListener {
            controller.stepReplayForward()
        }
        binding.buttonReplaySpeed.setOnClickListener {
            controller.toggleReplaySpeed()
        }
        binding.buttonToggleDebug.setOnClickListener {
            controller.toggleDebugOverlay()
        }
        binding.buttonSegmentationModel.setOnClickListener {
            controller.toggleSegmentationModel()
        }
        binding.buttonSegmentationBackend.setOnClickListener {
            controller.toggleSegmentationBackend()
        }
        binding.buttonToggle2d.setOnClickListener {
            controller.toggle2dDetection()
        }
        binding.buttonSetSegmentationRoi.setOnClickListener {
            setRoiSelectionEnabled(!roiSelectionEnabled)
            binding.textOpenCvStatus.text = if (roiSelectionEnabled) {
                getString(R.string.segmentation_roi_hint)
            } else {
                latestRenderState?.segmentationUiState?.statusMessage ?: getString(R.string.status_processing)
            }
        }
        binding.buttonClearSegmentationRoi.setOnClickListener {
            setRoiSelectionEnabled(false)
            controller.clearManualSegmentationRoi()
        }
        binding.overlayView.setOnManualRoiSelectedListener { roiBounds ->
            setRoiSelectionEnabled(false)
            controller.setManualSegmentationRoi(roiBounds)
        }

        binding.seekIntensityRange.progress = currentParams.intensityRange
        binding.seekPupilMin.progress = currentParams.pupilSizeMin
        binding.seekPupilMax.progress = currentParams.pupilSizeMax
        binding.seekCannyThreshold.progress = currentParams.cannyThreshold

        val listener = object : SeekBar.OnSeekBarChangeListener {
            /**
             * Summary: Updates detector params when the user changes a slider.
             * @param seekBar Updated seek bar.
             * @param progress Current slider progress.
             * @param fromUser Whether the change came from direct user input.
             * @return Unit.
             */
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                normalizeSeekBounds()
                currentParams = currentParams.copy(
                    intensityRange = binding.seekIntensityRange.progress.coerceIn(0, binding.seekIntensityRange.max),
                    pupilSizeMin = binding.seekPupilMin.progress.coerceAtLeast(4),
                    pupilSizeMax = binding.seekPupilMax.progress.coerceAtLeast(binding.seekPupilMin.progress.coerceAtLeast(4)),
                    cannyThreshold = binding.seekCannyThreshold.progress.coerceAtLeast(1),
                )
                updateParameterLabels()
                controller.updateParams(currentParams)
            }

            /**
             * Summary: Ignored callback required by SeekBar.
             * @param seekBar Updated seek bar.
             * @return Unit.
             */
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            /**
             * Summary: Ignored callback required by SeekBar.
             * @param seekBar Updated seek bar.
             * @return Unit.
             */
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

        binding.seekIntensityRange.setOnSeekBarChangeListener(listener)
        binding.seekPupilMin.setOnSeekBarChangeListener(listener)
        binding.seekPupilMax.setOnSeekBarChangeListener(listener)
        binding.seekCannyThreshold.setOnSeekBarChangeListener(listener)
        updateParameterLabels()
    }

    /**
     * Summary: Initializes OpenCV and starts live mode when possible.
     * @param none No parameters.
     * @return Unit.
     */
    private fun initializeOpenCv() {
        openCvReady = OpenCVLoader.initLocal()
        if (!openCvReady) {
            binding.textOpenCvStatus.text = getString(R.string.opencv_failed)
            clearOverlay(getString(R.string.opencv_failed))
            return
        }
        binding.textOpenCvStatus.text = getString(R.string.opencv_ready)
        ensureLiveMode()
    }

    /**
     * Summary: Requests permission if needed and starts live mode otherwise.
     * @param none No parameters.
     * @return Unit.
     */
    private fun ensureLiveMode() {
        if (!openCvReady) return
        if (hasCameraPermission()) {
            controller.startLive()
            binding.textOpenCvStatus.text = getString(R.string.camera_starting)
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Summary: Returns whether camera permission is already granted.
     * @param none No parameters.
     * @return True when live mode can start immediately.
     */
    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Summary: Keeps the min/max slider pair in a valid range.
     * @param none No parameters.
     * @return Unit.
     */
    private fun normalizeSeekBounds() {
        val minValue = binding.seekPupilMin.progress.coerceAtLeast(4)
        if (binding.seekPupilMin.progress != minValue) {
            binding.seekPupilMin.progress = minValue
        }
        if (binding.seekPupilMax.progress < minValue) {
            binding.seekPupilMax.progress = minValue
        }
        if (binding.seekCannyThreshold.progress <= 0) {
            binding.seekCannyThreshold.progress = 1
        }
    }

    /**
     * Summary: Refreshes the visible slider labels.
     * @param none No parameters.
     * @return Unit.
     */
    private fun updateParameterLabels() {
        binding.labelIntensityRange.text = "Intensity Range: ${currentParams.intensityRange}"
        binding.labelPupilMin.text = "Pupil Min: ${currentParams.pupilSizeMin}px"
        binding.labelPupilMax.text = "Pupil Max: ${currentParams.pupilSizeMax}px"
        binding.labelCannyThreshold.text = "Canny Threshold: ${currentParams.cannyThreshold}"
    }

    /**
     * Summary: Clears the overlay when no render state is available yet.
     * @param statusText Initial status text.
     * @return Unit.
     */
    private fun clearOverlay(statusText: String) {
        binding.overlayView.clear(statusText)
        binding.segmentationPreviewPanel.visibility = View.GONE
        binding.imageSegmentationGrayPreview.setImageBitmap(null)
        binding.imageSegmentationOverlayPreview.setImageBitmap(null)
        binding.textFrameInfo.text = getString(R.string.label_frame_info)
        binding.textResult2d.text = getString(R.string.label_result_2d)
        binding.textDebugInfo.text = getString(R.string.label_debug_info)
    }

    /**
     * Summary: Switches preview visibility between live camera and replay image.
     * @param mode Target source mode.
     * @return Unit.
     */
    private fun applyModeVisibility(mode: EyeSourceMode) {
        val isReplay = mode == EyeSourceMode.REPLAY
        binding.cameraPreview.visibility = if (isReplay) View.GONE else View.VISIBLE
        binding.replayImageView.visibility = if (isReplay) View.VISIBLE else View.GONE
        if (!isReplay) {
            binding.replayImageView.setImageBitmap(null)
        }
    }

    /**
     * Summary: Enables or disables manual ROI selection on the overlay.
     * @param enabled Whether drag selection should be active.
     * @return Unit.
     */
    private fun setRoiSelectionEnabled(enabled: Boolean) {
        roiSelectionEnabled = enabled
        binding.overlayView.setSelectionEnabled(enabled)
        updateActionButtons(latestRenderState)
    }

    /**
     * Summary: Builds the frame information block.
     * @param state Latest render state.
     * @return Formatted frame information text.
     */
    private fun buildFrameInfo(state: EyeTrackingRenderState): String {
        val observation = state.observation
        val replayText = if (state.mode == EyeSourceMode.REPLAY) {
            " | replay=${state.replayFrameIndex + 1}/${state.replayFrameCount}"
        } else {
            ""
        }
        val sessionText = state.latestSessionId?.let { " | session=$it" } ?: ""
        return buildString {
            append("Frame Info")
            append('\n')
            append("mode=${state.mode.name.lowercase(Locale.US)}")
            append(" | source=${state.sourceTag}")
            append(replayText)
            append(sessionText)
            append('\n')
            append("frameId=${observation.frameId}")
            append(" | tsNs=${observation.captureTimestampNs}")
            append('\n')
            append("size=${observation.frameWidth}x${observation.frameHeight}")
        }
    }

    /**
     * Summary: Builds the combined result block shown under the preview.
     * @param state Latest render state.
     * @return Formatted result text.
     */
    private fun buildResultText(state: EyeTrackingRenderState): String {
        val observation = state.observation
        val segmentation = state.segmentationUiState
        val segmentationResult = segmentation.latestResult
        val pupil = observation.pupil

        return buildString {
            append("Results")
            append('\n')
            if (!state.detection2dEnabled) {
                append("2d disabled | segmentation-only mode")
            } else if (pupil == null) {
                append("2d quality=${observation.quality.name} | no ellipse")
            } else {
                append(
                    String.format(
                        Locale.US,
                        "2d quality=%s | conf=%.3f | center=(%.1f, %.1f)",
                        observation.quality.name,
                        pupil.confidence,
                        pupil.ellipse.centerX,
                        pupil.ellipse.centerY,
                    ),
                )
            }
            append('\n')
            if (segmentationResult == null) {
                append("seg status=${segmentation.statusMessage}")
            } else {
                append(
                    "seg roi=${segmentationResult.roiWidth}x${segmentationResult.roiHeight}" +
                        " | iris=${segmentationResult.hasIris} | pupil=${segmentationResult.hasPupil}",
                )
                append('\n')
                append(
                    String.format(
                        Locale.US,
                        "seg total=%.2fms | fps(now)=%.2f | fps(avg)=%.2f",
                        segmentationResult.timing.totalMs(),
                        segmentation.performanceSummary.latestOutputFps,
                        segmentation.performanceSummary.averageOutputFps,
                    ),
                )
            }
        }
    }

    /**
     * Summary: Builds the debug block shown under the result text.
     * @param state Latest render state.
     * @return Formatted debug text.
     */
    private fun buildDebugText(state: EyeTrackingRenderState): String {
        if (!state.debugOverlayEnabled) {
            return buildString {
                append("Debug Info")
                append('\n')
                append("hidden")
            }
        }

        val debug = state.observation.debug
        val segmentation = state.segmentationUiState
        val segmentationResult = segmentation.latestResult
        val roiText = segmentation.selectedRoi?.let { roi ->
            "${roi.minX},${roi.minY} -> ${roi.maxX},${roi.maxY}"
        } ?: "none"

        return buildString {
            append("Segmentation Debug")
            append('\n')
            append("2d=")
            append(if (state.detection2dEnabled) "on" else "off")
            append('\n')
            append("model=${segmentation.config.modelType.displayName}")
            append(" | backend=${segmentation.config.backend.displayName}")
            append(" | session=${segmentation.sessionStatus.displayName}")
            append('\n')
            append("status=${segmentation.statusMessage}")
            append('\n')
            append("roi=$roiText")
            append('\n')
            if (segmentationResult != null) {
                append(
                    String.format(
                        Locale.US,
                        "frame ms pre=%.2f inf=%.2f post=%.2f",
                        segmentationResult.timing.preprocessMs,
                        segmentationResult.timing.inferenceMs,
                        segmentationResult.timing.postprocessMs,
                    ),
                )
                append('\n')
            } else {
                append("frame ms pre=-- inf=-- post=--")
                append('\n')
            }
            append(
                String.format(
                    Locale.US,
                    "avg(%d) ms pre=%.2f inf=%.2f post=%.2f total=%.2f",
                    segmentation.performanceSummary.frameCount,
                    segmentation.performanceSummary.averagePreprocessMs,
                    segmentation.performanceSummary.averageInferenceMs,
                    segmentation.performanceSummary.averagePostprocessMs,
                    segmentation.performanceSummary.averageTotalMs,
                ),
            )
            append('\n')
            append(
                String.format(
                    Locale.US,
                    "seg fps now=%.2f | avg=%.2f",
                    segmentation.performanceSummary.latestOutputFps,
                    segmentation.performanceSummary.averageOutputFps,
                ),
            )
            append('\n')
            if (state.detection2dEnabled) {
                append("2d roi=${debug.effectiveRoi.minX},${debug.effectiveRoi.minY} -> ${debug.effectiveRoi.maxX},${debug.effectiveRoi.maxY}")
                append('\n')
                append(
                    "2d support=${debug.supportPixelCount}" +
                        " | finalEdge=${debug.finalEdgeCount}" +
                        " | dark=${debug.darkPixelCount}",
                )
            } else {
                append("2d disabled for current run")
            }
        }
    }

    /**
     * Summary: Builds the short overlay text rendered on top of the preview.
     * @param state Latest render state.
     * @return Overlay status string.
     */
    private fun buildOverlayStatus(state: EyeTrackingRenderState): String {
        val segmentation = state.segmentationUiState
        val segmentationResult = segmentation.latestResult
        return buildString {
            append(state.mode.name)
            append(" | ")
            append(if (state.detection2dEnabled) "2D on" else "2D off")
            append(" | ")
            append(segmentation.config.modelType.displayName)
            append(" | ")
            append(segmentation.config.backend.displayName)
            append('\n')
            append("seg=")
            append(segmentation.sessionStatus.displayName)
            append(" | ")
            append(
                segmentation.selectedRoi?.let { "${it.width()}x${it.height()}" } ?: "roi not set",
            )
            append('\n')
            append(
                if (segmentationResult != null) {
                    String.format(
                        Locale.US,
                        "iris=%s | pupil=%s | %.2ffps",
                        segmentationResult.hasIris,
                        segmentationResult.hasPupil,
                        segmentation.performanceSummary.latestOutputFps,
                    )
                } else {
                    segmentation.statusMessage
                },
            )
        }
    }

    /**
     * Summary: Builds the header status line shown above the buttons.
     * @param state Latest render state.
     * @return Short status text.
     */
    private fun buildHeaderStatus(state: EyeTrackingRenderState): String {
        val recordText = if (state.isRecording) " | recording" else ""
        val qualityText = if (state.detection2dEnabled) state.observation.quality.name else "2D_OFF"
        return "${state.sourceTag} | $qualityText | ${state.segmentationUiState.sessionStatus.displayName}$recordText"
    }

    /**
     * Summary: Updates action button labels and enabled states.
     * @param state Latest render state if available.
     * @return Unit.
     */
    private fun updateActionButtons(state: EyeTrackingRenderState?) {
        val effectiveMode = state?.mode ?: desiredMode
        val isReplay = effectiveMode == EyeSourceMode.REPLAY
        val segmentationState = state?.segmentationUiState

        binding.buttonRecord.text = getString(
            if (state?.isRecording == true) R.string.action_stop_record else R.string.action_record,
        )
        binding.buttonReplayPlayPause.text = getString(
            if (state?.replayPlaying == true) R.string.action_pause else R.string.action_play,
        )
        binding.buttonReplaySpeed.text = state?.replaySpeed?.label ?: getString(R.string.action_speed_normal)
        binding.buttonToggleDebug.text = getString(
            if (state?.debugOverlayEnabled != false) R.string.action_hide_debug else R.string.action_show_debug,
        )
        binding.buttonSegmentationModel.text =
            "Model: ${segmentationState?.config?.modelType?.displayName ?: "B16 INT8"}"
        binding.buttonSegmentationBackend.text =
            "Backend: ${segmentationState?.config?.backend?.displayName ?: "NNAPI"}"
        binding.buttonToggle2d.text = getString(
            if (state?.detection2dEnabled != false) R.string.action_2d_on else R.string.action_2d_off,
        )
        binding.buttonSetSegmentationRoi.text = getString(
            if (roiSelectionEnabled) R.string.action_cancel_roi else R.string.action_set_roi,
        )

        binding.buttonReplayPlayPause.isEnabled = isReplay
        binding.buttonReplayPrev.isEnabled = isReplay
        binding.buttonReplayNext.isEnabled = isReplay
        binding.buttonReplaySpeed.isEnabled = isReplay
        binding.buttonRecord.isEnabled = openCvReady && effectiveMode == EyeSourceMode.LIVE
        binding.buttonSegmentationModel.isEnabled = (segmentationState?.availableModelTypes?.size ?: 0) > 1
        binding.buttonClearSegmentationRoi.isEnabled = state?.manualSegmentationRoi != null
    }
}
