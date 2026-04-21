package Aquin.lubie.tracking.pipeline

import Aquin.lubie.tracking.model.EyeFrame
import Aquin.lubie.tracking.model.EyeSourceMode
import Aquin.lubie.tracking.model.EyeTrackingRenderState
import Aquin.lubie.tracking.model.Pupil2DParams
import Aquin.lubie.tracking.model.PupilObservation2D
import Aquin.lubie.tracking.model.PupilQualityState
import Aquin.lubie.tracking.model.ReplaySpeed
import Aquin.lubie.tracking.model.RoiBounds
import Aquin.lubie.tracking.model.TrackingRoiPhase
import Aquin.lubie.tracking.segmentation.EyeSegmentationRunner
import Aquin.lubie.tracking.segmentation.SegmentationBackend
import Aquin.lubie.tracking.segmentation.SegmentationConfig
import Aquin.lubie.tracking.segmentation.SegmentationModelType
import Aquin.lubie.tracking.segmentation.SegmentationPerformanceTracker
import Aquin.lubie.tracking.segmentation.SegmentationResult
import Aquin.lubie.tracking.segmentation.SegmentationSessionStatus
import Aquin.lubie.tracking.segmentation.SegmentationUiState
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import org.opencv.core.Mat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val DEFAULT_EYE_ID = 0

/**
 * Summary: Coordinates live capture, replay, recording, 2D detection, and eye segmentation output.
 * @param context Application context.
 * @param lifecycleOwner Lifecycle owner for the live CameraX source.
 * @param previewView Preview surface used by the live source.
 * @param listener UI listener that receives render states and errors.
 * @return Serial eye tracking controller.
 */
class EyeTrackingController(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    private val listener: Listener,
) {

    /**
     * Summary: Receives controller state updates on the main thread.
     * @param none No constructor parameters.
     * @return UI listener contract.
     */
    interface Listener {
        /**
         * Summary: Renders the latest tracking state.
         * @param state Latest controller render state.
         * @return Unit.
         */
        fun onRenderState(state: EyeTrackingRenderState)

        /**
         * Summary: Reports a controller error or warning string.
         * @param message Human-readable status text.
         * @return Unit.
         */
        fun onControllerMessage(message: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val processingExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val recordingLock = ReentrantLock()
    private val preprocessor = EyePreprocessor()
    private val pupil2DDetector = Pupil2DDetector(preprocessor)
    private val qualityClassifier = PupilQualityClassifier()
    private val roiTracker = RoiTrackingStateMachine()
    private val sessionStore = EyeSessionStore(appContext, preprocessor)
    private val liveSource = CameraXEyeFrameSource(appContext, lifecycleOwner, previewView, preprocessor, processingExecutor)
    private val replaySource = RecordedEyeFrameSource(sessionStore, preprocessor)
    private val segmentationRunner = EyeSegmentationRunner(appContext)
    private val segmentationPerformanceTracker = SegmentationPerformanceTracker()
    private val availableSegmentationModels = segmentationRunner.availableModelTypes()

    private var currentParams = Pupil2DParams()
    private var currentMode = EyeSourceMode.LIVE
    private var lastObservation: PupilObservation2D? = null
    private var lastReplayBitmap: android.graphics.Bitmap? = null
    private var debugOverlayEnabled: Boolean = true
    private var blinkCandidateStreak: Int = 0
    private var isRecording: Boolean = false
    private var pendingRecordingStart: Boolean = false
    private var activeRecorder: EyeSessionRecorder? = null
    private var latestSessionId: String? = null
    private var replayPlaying: Boolean = false
    private var replaySpeed: ReplaySpeed = ReplaySpeed.NORMAL
    private var liveStarted: Boolean = false
    private var detection2dEnabled: Boolean = true
    private var segmentationConfig = SegmentationConfig(
        modelType = availableSegmentationModels.firstOrNull() ?: SegmentationModelType.B16_INT8_QDQ,
    )
    private var manualSegmentationRoi: RoiBounds? = null
    private var latestSegmentationResult: SegmentationResult? = null

    private val replayRunnable = object : Runnable {
        override fun run() {
            if (!replayPlaying || currentMode != EyeSourceMode.REPLAY) return
            val delayMs = replaySource.delayToNextFrameMs(replaySpeed)
            processingExecutor.execute {
                val emitted = replaySource.stepNext()
                if (emitted == null) {
                    replayPlaying = false
                    dispatchLatestState()
                    return@execute
                }
                mainHandler.postDelayed(this, delayMs.coerceAtLeast(1L))
            }
        }
    }

    /**
     * Summary: Starts the live near-eye simulation source.
     * @param none No parameters.
     * @return Unit.
     */
    fun startLive() {
        stopReplayInternal()
        currentMode = EyeSourceMode.LIVE
        if (liveStarted) return
        roiTracker.reset()
        blinkCandidateStreak = 0
        pupil2DDetector.reset()
        liveSource.start(
            frameConsumer = { frame -> handleFrame(frame, EyeSourceMode.LIVE) },
            onError = { message -> postMessage(message) },
        )
        liveStarted = true
        postMessage("Live eye source ready")
        dispatchLatestState()
    }

    /**
     * Summary: Pauses active live capture without destroying controller state.
     * @param none No parameters.
     * @return Unit.
     */
    fun pause() {
        stopReplayInternal()
        liveSource.stop()
        liveStarted = false
    }

    /**
     * Summary: Stops all active sources and closes session writers.
     * @param none No parameters.
     * @return Unit.
     */
    fun destroy() {
        stopReplayInternal()
        liveSource.stop()
        liveStarted = false
        recordingLock.withLock {
            stopRecordingInternal()
        }
        lastReplayBitmap?.recycle()
        lastReplayBitmap = null
        segmentationRunner.close()
        processingExecutor.shutdown()
    }

    /**
     * Summary: Updates the live detector parameters.
     * @param params New detector parameters.
     * @return Unit.
     */
    fun updateParams(params: Pupil2DParams) {
        currentParams = params
        if (currentMode == EyeSourceMode.REPLAY) {
            processingExecutor.execute {
                replaySource.emitCurrentFrame()
            }
        }
    }

    /**
     * Summary: Toggles the active segmentation model when the asset is available.
     * @param none No parameters.
     * @return Unit.
     */
    fun toggleSegmentationModel() {
        if (availableSegmentationModels.size <= 1) {
            postMessage("Only ${segmentationConfig.modelType.displayName} is bundled")
            dispatchLatestState()
            return
        }
        val currentIndex = availableSegmentationModels.indexOf(segmentationConfig.modelType)
            .takeIf { it >= 0 } ?: 0
        val nextModelType = availableSegmentationModels[(currentIndex + 1) % availableSegmentationModels.size]
        segmentationConfig = segmentationConfig.copy(
            modelType = nextModelType,
        )
        segmentationRunner.prepareConfig(segmentationConfig)
        latestSegmentationResult = null
        segmentationPerformanceTracker.reset()
        postMessage("Segmentation model: ${segmentationConfig.modelType.displayName}")
        requestCurrentFrameRefresh()
    }

    /**
     * Summary: Toggles the active segmentation backend between NNAPI and CPU.
     * @param none No parameters.
     * @return Unit.
     */
    fun toggleSegmentationBackend() {
        segmentationConfig = segmentationConfig.copy(
            backend = if (segmentationConfig.backend == SegmentationBackend.NNAPI) {
                SegmentationBackend.CPU
            } else {
                SegmentationBackend.NNAPI
            },
        )
        segmentationRunner.prepareConfig(segmentationConfig)
        latestSegmentationResult = null
        segmentationPerformanceTracker.reset()
        postMessage("Segmentation backend: ${segmentationConfig.backend.displayName}")
        requestCurrentFrameRefresh()
    }

    /**
     * Summary: Stores the user-selected manual eye ROI for segmentation.
     * @param roiBounds Selected ROI in frame coordinates.
     * @return Unit.
     */
    fun setManualSegmentationRoi(roiBounds: RoiBounds) {
        manualSegmentationRoi = roiBounds
        latestSegmentationResult = null
        segmentationPerformanceTracker.reset()
        segmentationRunner.prepareConfig(segmentationConfig)
        postMessage("Segmentation ROI set: ${roiBounds.width()}x${roiBounds.height()}")
        requestCurrentFrameRefresh()
    }

    /**
     * Summary: Clears the manual eye ROI and hides the current segmentation overlay.
     * @param none No parameters.
     * @return Unit.
     */
    fun clearManualSegmentationRoi() {
        manualSegmentationRoi = null
        latestSegmentationResult = null
        segmentationPerformanceTracker.reset()
        postMessage("Segmentation ROI cleared")
        dispatchLatestState()
    }

    /**
     * Summary: Toggles live session recording.
     * @param none No parameters.
     * @return Unit.
     */
    fun toggleRecording() {
        if (currentMode != EyeSourceMode.LIVE) {
            postMessage("Recording is only available in Live mode")
            return
        }
        if (isRecording || pendingRecordingStart) {
            recordingLock.withLock {
                stopRecordingInternal()
            }
            dispatchLatestState()
            return
        }

        val currentObservation = lastObservation
        if (currentObservation != null) {
            recordingLock.withLock {
                startRecorder(
                    sourceTag = liveSource.sourceTag,
                    frameWidth = currentObservation.frameWidth,
                    frameHeight = currentObservation.frameHeight,
                )
            }
        } else {
            pendingRecordingStart = true
            postMessage("Recording will start with the next live frame")
        }
        dispatchLatestState()
    }

    /**
     * Summary: Switches into replay mode using the latest recorded session.
     * @param none No parameters.
     * @return Unit.
     */
    fun startReplayLatest() {
        recordingLock.withLock {
            stopRecordingInternal()
        }
        liveSource.stop()
        liveStarted = false
        stopReplayInternal()
        replaySource.start(
            frameConsumer = { frame -> handleFrame(frame, EyeSourceMode.REPLAY) },
            onError = { message -> postMessage(message) },
        )
        val session = replaySource.openLatestSession()
        if (session == null) {
            postMessage("No recorded eye session available for replay")
            startLive()
            return
        }

        latestSessionId = session.sessionId
        currentMode = EyeSourceMode.REPLAY
        roiTracker.reset()
        blinkCandidateStreak = 0
        pupil2DDetector.reset()
        processingExecutor.execute {
            replaySource.emitCurrentFrame()
        }
        postMessage("Replay session loaded: ${session.sessionId}")
    }

    /**
     * Summary: Toggles replay playback.
     * @param none No parameters.
     * @return Unit.
     */
    fun toggleReplayPlayback() {
        if (currentMode != EyeSourceMode.REPLAY) return
        replayPlaying = !replayPlaying
        mainHandler.removeCallbacks(replayRunnable)
        if (replayPlaying) {
            mainHandler.post(replayRunnable)
        }
        dispatchLatestState()
    }

    /**
     * Summary: Steps replay backward by one frame.
     * @param none No parameters.
     * @return Unit.
     */
    fun stepReplayBackward() {
        if (currentMode != EyeSourceMode.REPLAY) return
        replayPlaying = false
        mainHandler.removeCallbacks(replayRunnable)
        processingExecutor.execute {
            replaySource.stepPrevious()
        }
        dispatchLatestState()
    }

    /**
     * Summary: Steps replay forward by one frame.
     * @param none No parameters.
     * @return Unit.
     */
    fun stepReplayForward() {
        if (currentMode != EyeSourceMode.REPLAY) return
        replayPlaying = false
        mainHandler.removeCallbacks(replayRunnable)
        processingExecutor.execute {
            replaySource.stepNext()
        }
        dispatchLatestState()
    }

    /**
     * Summary: Toggles replay speed between the supported options.
     * @param none No parameters.
     * @return Unit.
     */
    fun toggleReplaySpeed() {
        replaySpeed = if (replaySpeed == ReplaySpeed.NORMAL) ReplaySpeed.QUARTER else ReplaySpeed.NORMAL
        dispatchLatestState()
    }

    /**
     * Summary: Toggles detailed debug rendering.
     * @param none No parameters.
     * @return Unit.
     */
    fun toggleDebugOverlay() {
        debugOverlayEnabled = !debugOverlayEnabled
        dispatchLatestState()
    }

    /**
     * Summary: Toggles the classic 2D detector on or off for segmentation-only validation.
     * @param none No parameters.
     * @return Unit.
     */
    fun toggle2dDetection() {
        detection2dEnabled = !detection2dEnabled
        roiTracker.reset()
        blinkCandidateStreak = 0
        pupil2DDetector.reset()
        postMessage(
            if (detection2dEnabled) {
                "2D detector enabled"
            } else {
                "2D detector disabled; segmentation-only mode"
            },
        )
        requestCurrentFrameRefresh()
    }

    /**
     * Summary: Resets the tracking state machine and detector priors.
     * @param none No parameters.
     * @return Unit.
     */
    fun resetTracking() {
        roiTracker.reset()
        blinkCandidateStreak = 0
        pupil2DDetector.reset()
        postMessage("Tracking state reset")
        if (currentMode == EyeSourceMode.REPLAY) {
            processingExecutor.execute {
                replaySource.emitCurrentFrame()
            }
        }
    }

    private fun handleFrame(
        frame: EyeFrame,
        mode: EyeSourceMode,
    ) {
        val observation = if (detection2dEnabled) {
            val requestedRoi = roiTracker.nextRequestedRoi(frame.width, frame.height)
            val allowStrongPrior = roiTracker.phase() == TrackingRoiPhase.FOLLOW &&
                lastObservation?.quality == PupilQualityState.VALID
            val detection = try {
                pupil2DDetector.detectDetailed(
                    gray = frame.grayMat,
                    roiBounds = requestedRoi,
                    timestamp = frame.timestampNs / 1_000_000_000.0,
                    eyeId = DEFAULT_EYE_ID,
                    params = currentParams,
                    allowStrongPrior = allowStrongPrior,
                )
            } catch (_: Throwable) {
                frame.grayMat.release()
                postMessage("2D detection failed")
                return
            }

            val blinkLike = qualityClassifier.isBlinkLike(detection.pupil, detection.debug, currentParams)
            blinkCandidateStreak = if (blinkLike) blinkCandidateStreak + 1 else 0
            val quality = qualityClassifier.classify(
                pupil = detection.pupil,
                debug = detection.debug,
                blinkCandidateStreak = blinkCandidateStreak,
                params = currentParams,
            )
            PupilObservation2D(
                frameId = frame.frameId,
                captureTimestampNs = frame.timestampNs,
                eyeId = DEFAULT_EYE_ID,
                frameWidth = frame.width,
                frameHeight = frame.height,
                pupil = detection.pupil,
                quality = quality,
                debug = detection.debug,
            ).also { detectedObservation ->
                roiTracker.update(detectedObservation)
            }
        } else {
            build2dDisabledObservation(frame)
        }
        lastObservation = observation

        if (mode == EyeSourceMode.LIVE && (pendingRecordingStart || isRecording)) {
            try {
                recordingLock.withLock {
                    ensureRecorder(observation, frame.sourceTag)
                    activeRecorder?.append(frame, observation)
                }
            } catch (_: Throwable) {
                recordingLock.withLock {
                    stopRecordingInternal()
                }
                postMessage("Recording failed")
            }
        }

        latestSegmentationResult = runSegmentation(frame.grayMat)

        val replayBitmap = if (mode == EyeSourceMode.REPLAY) preprocessor.toBitmap(frame.grayMat) else null
        if (replayBitmap != null && lastReplayBitmap !== replayBitmap) {
            lastReplayBitmap?.recycle()
        }
        lastReplayBitmap = replayBitmap ?: lastReplayBitmap
        frame.grayMat.release()

        dispatchState(
            observation = observation,
            sourceTag = frame.sourceTag,
            replayBitmap = replayBitmap,
            mirrorHorizontally = if (mode == EyeSourceMode.LIVE) liveSource.isPreviewMirrored() else false,
        )
    }

    private fun runSegmentation(gray: Mat): SegmentationResult? {
        val roi = manualSegmentationRoi ?: return null
        return try {
            val result = segmentationRunner.run(
                gray = gray,
                roiBounds = roi,
                config = segmentationConfig,
            )
            segmentationPerformanceTracker.record(result.timing)
            result
        } catch (_: Throwable) {
            null
        }
    }

    private fun ensureRecorder(
        observation: PupilObservation2D,
        sourceTag: String,
    ) {
        if (activeRecorder != null) return
        startRecorder(
            sourceTag = sourceTag,
            frameWidth = observation.frameWidth,
            frameHeight = observation.frameHeight,
        )
    }

    private fun startRecorder(
        sourceTag: String,
        frameWidth: Int,
        frameHeight: Int,
    ) {
        activeRecorder = sessionStore.createRecorder(
            sourceTag = sourceTag,
            eyeId = DEFAULT_EYE_ID,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
        )
        latestSessionId = activeRecorder?.sessionInfo?.sessionId
        pendingRecordingStart = false
        isRecording = true
        postMessage("Recording session started: $latestSessionId")
    }

    private fun stopRecordingInternal() {
        activeRecorder?.close()
        activeRecorder = null
        pendingRecordingStart = false
        if (isRecording) {
            postMessage("Recording session saved")
        }
        isRecording = false
    }

    private fun stopReplayInternal() {
        replayPlaying = false
        mainHandler.removeCallbacks(replayRunnable)
        replaySource.stop()
    }

    private fun requestCurrentFrameRefresh() {
        if (currentMode == EyeSourceMode.REPLAY) {
            processingExecutor.execute {
                replaySource.emitCurrentFrame()
            }
        } else {
            dispatchLatestState()
        }
    }

    private fun dispatchLatestState() {
        val observation = lastObservation ?: return
        dispatchState(
            observation = observation,
            sourceTag = if (currentMode == EyeSourceMode.LIVE) liveSource.sourceTag else replaySource.sourceTag,
            replayBitmap = if (currentMode == EyeSourceMode.REPLAY) lastReplayBitmap else null,
            mirrorHorizontally = currentMode == EyeSourceMode.LIVE && liveSource.isPreviewMirrored(),
        )
    }

    private fun dispatchState(
        observation: PupilObservation2D,
        sourceTag: String,
        replayBitmap: android.graphics.Bitmap?,
        mirrorHorizontally: Boolean,
    ) {
        val replaySession = replaySource.currentSession()
        val segmentationUiState = buildSegmentationUiState()
        mainHandler.post {
            listener.onRenderState(
                EyeTrackingRenderState(
                    mode = currentMode,
                    sourceTag = sourceTag,
                    observation = renderObservation(observation),
                    replayBitmap = replayBitmap,
                    isRecording = isRecording,
                    replayPlaying = replayPlaying,
                    replaySpeed = replaySpeed,
                    replayFrameIndex = replaySource.currentIndex(),
                    replayFrameCount = replaySession?.frames?.size ?: 0,
                    latestSessionId = latestSessionId,
                    debugOverlayEnabled = debugOverlayEnabled,
                    detection2dEnabled = detection2dEnabled,
                    mirrorHorizontally = mirrorHorizontally,
                    manualSegmentationRoi = manualSegmentationRoi,
                    segmentationUiState = segmentationUiState,
                ),
            )
        }
    }

    private fun buildSegmentationUiState(): SegmentationUiState {
        val sessionStatus = segmentationRunner.sessionStatus(segmentationConfig)
        val sessionMessage = segmentationRunner.sessionMessage(segmentationConfig)
        val statusMessage = when {
            manualSegmentationRoi == null -> {
                if (availableSegmentationModels.isEmpty()) "ROI not set | no model asset bundled" else "ROI not set"
            }

            latestSegmentationResult != null -> {
                "${segmentationConfig.modelType.displayName} ${segmentationConfig.backend.displayName} frame ready"
            }

            sessionStatus == SegmentationSessionStatus.FAILED -> {
                sessionMessage ?: "Session initialization failed"
            }

            sessionStatus == SegmentationSessionStatus.INITIALIZING -> {
                "Initializing ${segmentationConfig.backend.displayName} session"
            }

            else -> {
                "ROI selected, waiting for next frame"
            }
        }

        return SegmentationUiState(
            config = segmentationConfig,
            sessionStatus = sessionStatus,
            statusMessage = statusMessage,
            selectedRoi = manualSegmentationRoi,
            latestResult = latestSegmentationResult,
            performanceSummary = segmentationPerformanceTracker.summary(),
            availableModelTypes = availableSegmentationModels,
        )
    }

    private fun postMessage(message: String) {
        mainHandler.post {
            listener.onControllerMessage(message)
        }
    }

    private fun build2dDisabledObservation(frame: EyeFrame): PupilObservation2D {
        return PupilObservation2D(
            frameId = frame.frameId,
            captureTimestampNs = frame.timestampNs,
            eyeId = DEFAULT_EYE_ID,
            frameWidth = frame.width,
            frameHeight = frame.height,
            pupil = null,
            quality = PupilQualityState.LOST_TRACK,
            debug = disabledDebugInfo(frame.width, frame.height),
        )
    }

    private fun renderObservation(observation: PupilObservation2D): PupilObservation2D {
        if (detection2dEnabled) return observation
        return observation.copy(
            pupil = null,
            quality = PupilQualityState.LOST_TRACK,
            debug = disabledDebugInfo(observation.frameWidth, observation.frameHeight),
        )
    }

    private fun disabledDebugInfo(
        frameWidth: Int,
        frameHeight: Int,
    ) = Aquin.lubie.tracking.model.Pupil2DDebugInfo(
        effectiveRoi = RoiBounds.fullFrame(frameWidth, frameHeight),
    )
}
