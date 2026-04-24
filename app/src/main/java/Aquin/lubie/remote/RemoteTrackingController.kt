package Aquin.lubie.remote

import Aquin.lubie.tracking.model.ReplaySpeed
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.TextureView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val RECORDING_INTERVAL_MS = 33L
private const val MAX_DEBUG_LOG_LINES = 24
private const val DEBUG_LOG_TAG = "RemoteTracking"

/**
 * Summary: Coordinates RTSP playback, demo playback, UDP gaze reception, recording, markers, and replay.
 * @param context Application context.
 * @param playerView Shared VLC video layout used for live playback.
 * @param listener UI listener that renders the latest remote state.
 * @return Single-controller coordinator for the remote streaming MVP.
 */
class RemoteTrackingController(
    context: Context,
    private val playerView: VLCVideoLayout,
    private val listener: Listener,
) {

    /**
     * Summary: Receives controller render states and messages on the main thread.
     * @return UI listener contract.
     */
    interface Listener {
        /**
         * Summary: Renders the latest remote page state.
         * @param state Latest render state.
         * @return Unit.
         */
        fun onRenderState(state: RemoteRenderState)

        /**
         * Summary: Reports a short user-facing message.
         * @param message Human-readable message.
         * @return Unit.
         */
        fun onControllerMessage(message: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val frameWriteInFlight = AtomicBoolean(false)
    private val sessionStore = RemoteSessionStore(appContext)
    private val replaySource = RemoteReplaySource(sessionStore)
    private val gazeReceiver = UdpGazeReceiver(
        onSample = { sample ->
            mainHandler.post {
                if (currentMode == RemoteMode.LIVE && sourceKind == RemoteSourceKind.RTSP) {
                    currentGaze = sample
                    dispatchState()
                }
            }
        },
        onError = { message ->
            mainHandler.post {
                latestStatusMessage = message
                appendDebugLog("UDP error | $message")
                dispatchState()
                postMessage(message)
            }
        },
    )
    private var currentMode: RemoteMode = RemoteMode.LIVE
    private var sourceKind: RemoteSourceKind? = null
    private var currentRtspUrl: String? = null
    private var currentUdpPort: Int? = null
    private var currentDemoUri: Uri? = null
    private var demoSourceDisplayName: String? = null
    private var currentGaze: GazeSample? = null
    private var latestStatusMessage: String = "Idle"
    private var recordingEnabled: Boolean = false
    private var activeRecorder: RemoteSessionRecorder? = null
    private var latestSessionId: String? = null
    private var recordingStartTimestampNs: Long? = null
    private var latestRecordedFrameId: Long? = null
    private var latestRecordedTimestampNs: Long? = null
    private var replayPlaying: Boolean = false
    private var replaySpeed: ReplaySpeed = ReplaySpeed.NORMAL
    private var replayBitmap: android.graphics.Bitmap? = null
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    private val currentMarkerTimestamps = mutableListOf<Long>()
    private val debugLogLines = ArrayDeque<String>()
    private val controllerCreatedAtMs = SystemClock.elapsedRealtime()
    private var vlcPlayerReady: Boolean = false
    private var lastLoggedVlcEvent: Int? = null
    private var lastLoggedBufferingBucket: Int? = null

    private val frameCaptureRunnable = object : Runnable {
        override fun run() {
            captureFrameIfNeeded()
            if (currentMode == RemoteMode.LIVE && recordingEnabled) {
                mainHandler.postDelayed(this, RECORDING_INTERVAL_MS)
            }
        }
    }

    private val replayRunnable = object : Runnable {
        override fun run() {
            if (!replayPlaying || currentMode != RemoteMode.REPLAY) return
            val delayMs = replaySource.delayToNextFrameMs(replaySpeed)
            workerExecutor.execute {
                val frame = replaySource.stepNext()
                mainHandler.post {
                    if (frame == null) {
                        replayPlaying = false
                        latestStatusMessage = "Replay finished"
                        dispatchState()
                        return@post
                    }
                    updateReplayFrame(frame, "Replay playing")
                    if (replayPlaying) {
                        mainHandler.postDelayed(this, delayMs)
                    }
                }
            }
        }
    }

    private val libVlc = LibVLC(
        appContext,
        arrayListOf(
            "--network-caching=600",
            "--live-caching=600",
            "--file-caching=600",
            "--drop-late-frames",
            "--skip-frames",
        ),
    )
    private val vlcEventListener = MediaPlayer.EventListener { event ->
        mainHandler.post {
            handleVlcEvent(event)
        }
    }
    private val player = MediaPlayer(libVlc).apply {
        setEventListener(vlcEventListener)
    }

    init {
        playerView.keepScreenOn = true
        player.attachViews(playerView, null, false, true)
        appendDebugLog("Controller initialized")
        dispatchState()
    }

    /**
     * Summary: Starts the RTSP live flow and arms automatic recording.
     * @param rtspUrl RTSP URL entered by the user.
     * @param udpPort UDP port that receives gaze JSON.
     * @return Unit.
     */
    fun connectRtsp(
        rtspUrl: String,
        udpPort: Int,
    ) {
        appendDebugLog("Connect requested | url=$rtspUrl udp=$udpPort player=libvlc")
        switchToLiveSource(
            sourceKind = RemoteSourceKind.RTSP,
            rtspUrl = rtspUrl,
            udpPort = udpPort,
            demoUri = null,
            demoName = null,
            mediaUri = Uri.parse(rtspUrl),
            statusMessage = "Connecting RTSP stream",
        )
        appendDebugLog("UDP receiver start | port=$udpPort")
        gazeReceiver.start(udpPort)
    }

    /**
     * Summary: Starts the hidden demo playback flow with a local video URI.
     * @param uri Selected local video URI.
     * @param displayName Human-readable file display name.
     * @return Unit.
     */
    fun startDemoVideo(
        uri: Uri,
        displayName: String,
    ) {
        appendDebugLog("Demo source selected | name=$displayName uri=$uri")
        switchToLiveSource(
            sourceKind = RemoteSourceKind.DEMO_VIDEO,
            rtspUrl = null,
            udpPort = null,
            demoUri = uri,
            demoName = displayName,
            mediaUri = uri,
            statusMessage = "Starting demo video",
        )
    }

    /**
     * Summary: Stops active playback, recording, and UDP reception.
     * @param none No parameters.
     * @return Unit.
     */
    fun disconnect() {
        appendDebugLog("Disconnect requested")
        stopReplayInternal()
        stopFrameCaptureLoop()
        flushAndCloseRecorder(saveMessage = true)
        gazeReceiver.stop()
        stopVlcPlayback()
        sourceKind = null
        currentRtspUrl = null
        currentUdpPort = null
        currentDemoUri = null
        demoSourceDisplayName = null
        currentGaze = null
        recordingEnabled = false
        videoWidth = 0
        videoHeight = 0
        currentMarkerTimestamps.clear()
        recordingStartTimestampNs = null
        latestRecordedFrameId = null
        latestRecordedTimestampNs = null
        latestStatusMessage = "Disconnected"
        lastLoggedVlcEvent = null
        lastLoggedBufferingBucket = null
        dispatchState()
    }

    /**
     * Summary: Toggles live session recording on or off.
     * @param none No parameters.
     * @return Unit.
     */
    fun toggleRecording() {
        if (currentMode != RemoteMode.LIVE || sourceKind == null) {
            postMessage("Recording is only available during live playback")
            return
        }

        if (recordingEnabled) {
            recordingEnabled = false
            stopFrameCaptureLoop()
            flushAndCloseRecorder(saveMessage = true)
            latestStatusMessage = "Recording stopped"
            appendDebugLog("Recording stopped")
        } else {
            currentMarkerTimestamps.clear()
            recordingStartTimestampNs = null
            latestRecordedFrameId = null
            latestRecordedTimestampNs = null
            recordingEnabled = true
            latestStatusMessage = "Recording armed"
            appendDebugLog("Recording armed")
            startFrameCaptureLoopIfNeeded()
        }
        dispatchState()
    }

    /**
     * Summary: Persists a marker at the latest recorded frame timestamp.
     * @param none No parameters.
     * @return Unit.
     */
    fun addMarker() {
        if (currentMode != RemoteMode.LIVE || !recordingEnabled || activeRecorder == null) {
            postMessage("Recording has not started yet.")
            return
        }
        val timestampNs = latestRecordedTimestampNs ?: run {
            postMessage("Recording has not started yet.")
            return
        }
        val frameId = latestRecordedFrameId ?: 0L
        val marker = RemoteMarkerEvent(timestampNs = timestampNs, frameId = frameId)
        currentMarkerTimestamps.add(marker.timestampNs)
        appendDebugLog("Marker saved | frame=$frameId tsNs=$timestampNs")
        val recorder = activeRecorder ?: return
        workerExecutor.execute {
            try {
                recorder.appendMarker(marker)
            } catch (_: Throwable) {
                mainHandler.post {
                    postMessage("Failed to save marker")
                }
            }
        }
        dispatchState()
        postMessage("Marker saved.")
    }

    /**
     * Summary: Starts replay for the latest recorded remote session.
     * @param none No parameters.
     * @return Unit.
     */
    fun startReplayLatest() {
        appendDebugLog("Replay latest requested")
        stopFrameCaptureLoop()
        flushAndCloseRecorder(saveMessage = true)
        gazeReceiver.stop()
        stopVlcPlayback()
        stopReplayInternal()
        latestStatusMessage = "Loading replay"
        dispatchState()
        workerExecutor.execute {
            val session = replaySource.openLatestSession()
            val frame = replaySource.emitCurrentFrame()
            mainHandler.post {
                if (session == null || frame == null) {
                    latestStatusMessage = "No recorded session available"
                    currentMode = RemoteMode.LIVE
                    dispatchState()
                    postMessage("No recorded session available.")
                    return@post
                }

                currentMode = RemoteMode.REPLAY
                sourceKind = session.sourceKind
                currentRtspUrl = session.rtspUrl
                currentUdpPort = session.udpPort
                demoSourceDisplayName = session.demoSourceDisplayName
                currentMarkerTimestamps.clear()
                currentMarkerTimestamps.addAll(session.markers.map { it.timestampNs })
                latestSessionId = session.sessionId
                replayPlaying = false
                replaySpeed = ReplaySpeed.NORMAL
                appendDebugLog("Replay loaded | session=${session.sessionId} frames=${session.frames.size}")
                updateReplayFrame(frame, "Replay loaded: ${session.sessionId}")
            }
        }
    }

    /**
     * Summary: Toggles replay playback.
     * @param none No parameters.
     * @return Unit.
     */
    fun toggleReplayPlayback() {
        if (currentMode != RemoteMode.REPLAY) return
        replayPlaying = !replayPlaying
        mainHandler.removeCallbacks(replayRunnable)
        if (replayPlaying) {
            mainHandler.post(replayRunnable)
        }
        dispatchState()
    }

    /**
     * Summary: Steps replay back by one frame.
     * @param none No parameters.
     * @return Unit.
     */
    fun stepReplayBackward() {
        if (currentMode != RemoteMode.REPLAY) return
        replayPlaying = false
        mainHandler.removeCallbacks(replayRunnable)
        workerExecutor.execute {
            val frame = replaySource.stepPrevious()
            mainHandler.post {
                frame?.let { updateReplayFrame(it, "Replay stepped backward") }
                    ?: dispatchState()
            }
        }
    }

    /**
     * Summary: Steps replay forward by one frame.
     * @param none No parameters.
     * @return Unit.
     */
    fun stepReplayForward() {
        if (currentMode != RemoteMode.REPLAY) return
        replayPlaying = false
        mainHandler.removeCallbacks(replayRunnable)
        workerExecutor.execute {
            val frame = replaySource.stepNext()
            mainHandler.post {
                frame?.let { updateReplayFrame(it, "Replay stepped forward") }
                    ?: dispatchState()
            }
        }
    }

    /**
     * Summary: Seeks replay to the closest frame for the requested seek bar progress.
     * @param progress Current seek bar progress.
     * @param max Seek bar max.
     * @return Unit.
     */
    fun seekReplayToProgress(
        progress: Int,
        max: Int,
    ) {
        if (currentMode != RemoteMode.REPLAY) return
        replayPlaying = false
        mainHandler.removeCallbacks(replayRunnable)
        workerExecutor.execute {
            val frame = replaySource.seekToProgress(progress, max)
            mainHandler.post {
                frame?.let { updateReplayFrame(it, "Replay seeked") }
                    ?: dispatchState()
            }
        }
    }

    /**
     * Summary: Toggles replay speed between quarter speed and normal speed.
     * @param none No parameters.
     * @return Unit.
     */
    fun toggleReplaySpeed() {
        replaySpeed = if (replaySpeed == ReplaySpeed.NORMAL) ReplaySpeed.QUARTER else ReplaySpeed.NORMAL
        dispatchState()
    }

    /**
     * Summary: Pauses live playback and replay timers while the activity is backgrounded.
     * @param none No parameters.
     * @return Unit.
     */
    fun pause() {
        appendDebugLog("Activity pause")
        if (currentMode == RemoteMode.REPLAY) {
            replayPlaying = false
            mainHandler.removeCallbacks(replayRunnable)
        }
        stopFrameCaptureLoop()
        player.pause()
        dispatchState()
    }

    /**
     * Summary: Resumes live playback after the activity returns to foreground.
     * @param none No parameters.
     * @return Unit.
     */
    fun resume() {
        appendDebugLog("Activity resume")
        if (currentMode == RemoteMode.LIVE && sourceKind != null) {
            player.play()
            startFrameCaptureLoopIfNeeded()
        }
        dispatchState()
    }

    /**
     * Summary: Releases all controller resources.
     * @param none No parameters.
     * @return Unit.
     */
    fun destroy() {
        appendDebugLog("Controller destroy")
        mainHandler.removeCallbacks(frameCaptureRunnable)
        mainHandler.removeCallbacks(replayRunnable)
        replayPlaying = false
        stopFrameCaptureLoop()
        flushAndCloseRecorder(saveMessage = false)
        gazeReceiver.stop()
        replaceReplayBitmap(null)
        stopVlcPlayback()
        player.setEventListener(null)
        player.detachViews()
        player.release()
        libVlc.release()
        workerExecutor.shutdown()
    }

    private fun switchToLiveSource(
        sourceKind: RemoteSourceKind,
        rtspUrl: String?,
        udpPort: Int?,
        demoUri: Uri?,
        demoName: String?,
        mediaUri: Uri,
        statusMessage: String,
    ) {
        stopReplayInternal()
        stopFrameCaptureLoop()
        flushAndCloseRecorder(saveMessage = true)
        gazeReceiver.stop()
        stopVlcPlayback()

        currentMode = RemoteMode.LIVE
        this.sourceKind = sourceKind
        currentRtspUrl = rtspUrl
        currentUdpPort = udpPort
        currentDemoUri = demoUri
        demoSourceDisplayName = demoName
        currentGaze = null
        recordingEnabled = true
        currentMarkerTimestamps.clear()
        recordingStartTimestampNs = null
        latestRecordedFrameId = null
        latestRecordedTimestampNs = null
        replayPlaying = false
        replaySpeed = ReplaySpeed.NORMAL
        replaceReplayBitmap(null)
        latestStatusMessage = statusMessage
        lastLoggedVlcEvent = null
        lastLoggedBufferingBucket = null
        appendDebugLog(
            "Switch live source | kind=${sourceKind.name} label=${demoName ?: rtspUrl ?: "unknown"}",
        )

        val media = buildVlcMedia(mediaUri, sourceKind)
        player.media = media
        media.release()
        player.play()
        dispatchState()
    }

    private fun updateReplayFrame(
        frame: RemoteReplayFrame,
        statusMessage: String,
    ) {
        currentMode = RemoteMode.REPLAY
        currentGaze = frame.metadata.gazeSample
        videoWidth = frame.metadata.width
        videoHeight = frame.metadata.height
        latestStatusMessage = statusMessage
        replaceReplayBitmap(frame.bitmap)
        dispatchState()
    }

    private fun stopReplayInternal() {
        replayPlaying = false
        mainHandler.removeCallbacks(replayRunnable)
        replaySource.clear()
        replaceReplayBitmap(null)
    }

    private fun startFrameCaptureLoopIfNeeded() {
        if (currentMode != RemoteMode.LIVE || !recordingEnabled || sourceKind == null) return
        if (!vlcPlayerReady) return
        mainHandler.removeCallbacks(frameCaptureRunnable)
        mainHandler.post(frameCaptureRunnable)
    }

    private fun stopFrameCaptureLoop() {
        mainHandler.removeCallbacks(frameCaptureRunnable)
    }

    private fun handleVlcEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                vlcPlayerReady = false
                latestStatusMessage = "Opening live video"
                logVlcEvent(event)
            }
            MediaPlayer.Event.Buffering -> {
                latestStatusMessage = "Buffering live video"
                logVlcEvent(event)
            }
            MediaPlayer.Event.Playing -> {
                vlcPlayerReady = true
                latestStatusMessage = "${sourceLabelForState()} ready"
                logVlcEvent(event)
                startFrameCaptureLoopIfNeeded()
            }
            MediaPlayer.Event.Paused -> {
                latestStatusMessage = "Playback paused"
                logVlcEvent(event)
            }
            MediaPlayer.Event.Stopped -> {
                vlcPlayerReady = false
                latestStatusMessage = if (sourceKind == null) "Idle" else "Player stopped"
                logVlcEvent(event)
            }
            MediaPlayer.Event.EndReached -> {
                vlcPlayerReady = false
                latestStatusMessage = if (sourceKind == RemoteSourceKind.DEMO_VIDEO) {
                    "Demo playback finished"
                } else {
                    "Playback ended"
                }
                logVlcEvent(event)
                if (currentMode == RemoteMode.LIVE && sourceKind == RemoteSourceKind.DEMO_VIDEO) {
                    stopFrameCaptureLoop()
                    flushAndCloseRecorder(saveMessage = true)
                    recordingEnabled = false
                }
            }
            MediaPlayer.Event.EncounteredError -> {
                vlcPlayerReady = false
                latestStatusMessage = "Playback error: libVLC"
                stopFrameCaptureLoop()
                appendDebugLog(buildVlcErrorDetail())
                postMessage(latestStatusMessage)
            }
            MediaPlayer.Event.Vout -> {
                vlcPlayerReady = true
                logVlcEvent(event)
                startFrameCaptureLoopIfNeeded()
            }
        }
        dispatchState()
    }

    private fun logVlcEvent(event: MediaPlayer.Event) {
        if (event.type == MediaPlayer.Event.Buffering) {
            val bucket = ((event.buffering / 10f).toInt() * 10).coerceIn(0, 100)
            if (lastLoggedVlcEvent == event.type && lastLoggedBufferingBucket == bucket) return
            lastLoggedBufferingBucket = bucket
        } else {
            lastLoggedBufferingBucket = null
            if (lastLoggedVlcEvent == event.type) return
        }
        lastLoggedVlcEvent = event.type
        appendDebugLog(
            "LibVLC event=${vlcEventLabel(event.type)} " +
                "buffer=${String.format(Locale.US, "%.1f", event.buffering)} " +
                "source=${sourceKind?.name ?: "NONE"} ready=$vlcPlayerReady",
        )
    }

    private fun buildVlcMedia(
        uri: Uri,
        sourceKind: RemoteSourceKind,
    ): Media {
        return Media(libVlc, uri).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=600")
            addOption(":live-caching=600")
            addOption(":file-caching=600")
            addOption(":drop-late-frames")
            addOption(":skip-frames")
            if (sourceKind == RemoteSourceKind.RTSP) {
                addOption(":rtsp-timeout=5000000")
            }
        }
    }

    private fun stopVlcPlayback() {
        vlcPlayerReady = false
        try {
            player.stop()
        } catch (_: Throwable) {
            // Stop is best-effort during source switches and shutdown.
        }
    }

    private fun captureFrameIfNeeded() {
        if (currentMode != RemoteMode.LIVE || !recordingEnabled || sourceKind == null) return
        if (!vlcPlayerReady) return
        if (frameWriteInFlight.get()) return
        val textureView = findTextureViewIn(playerView) ?: return
        if (!textureView.isAvailable) return
        val bitmap = textureView.bitmap ?: return
        val sourceKindSnapshot = sourceKind ?: return
        val gazeSnapshot = if (sourceKindSnapshot == RemoteSourceKind.RTSP) currentGaze?.copy() else null
        val timestampNs = SystemClock.elapsedRealtimeNanos()

        if (activeRecorder == null) {
            val recorder = sessionStore.createRecorder(
                sourceKind = sourceKindSnapshot,
                sourceLabel = sourceLabelForState(),
                frameWidth = bitmap.width,
                frameHeight = bitmap.height,
                rtspUrl = currentRtspUrl,
                demoSourceDisplayName = demoSourceDisplayName,
                udpPort = currentUdpPort,
            )
            activeRecorder = recorder
            latestSessionId = recorder.sessionInfo.sessionId
            latestStatusMessage = "Recording session started: ${recorder.sessionInfo.sessionId}"
            appendDebugLog("Recording session started | id=${recorder.sessionInfo.sessionId}")
        }

        val recorder = activeRecorder ?: run {
            bitmap.recycle()
            return
        }

        frameWriteInFlight.set(true)
        workerExecutor.execute {
            try {
                val metadata = recorder.appendFrame(bitmap, timestampNs, gazeSnapshot)
                bitmap.recycle()
                mainHandler.post {
                    recordingStartTimestampNs = recordingStartTimestampNs ?: metadata.timestampNs
                    latestRecordedFrameId = metadata.frameId
                    latestRecordedTimestampNs = metadata.timestampNs
                    videoWidth = metadata.width
                    videoHeight = metadata.height
                    dispatchState()
                }
            } catch (_: Throwable) {
                bitmap.recycle()
                mainHandler.post {
                    latestStatusMessage = "Recording failed"
                    recordingEnabled = false
                    stopFrameCaptureLoop()
                    flushAndCloseRecorder(saveMessage = false)
                    appendDebugLog("Recording failed")
                    dispatchState()
                    postMessage("Recording failed")
                }
            } finally {
                frameWriteInFlight.set(false)
            }
        }
    }

    private fun findTextureViewIn(view: View): TextureView? {
        if (view is TextureView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findTextureViewIn(view.getChildAt(index))?.let { textureView ->
                return textureView
            }
        }
        return null
    }

    private fun flushAndCloseRecorder(saveMessage: Boolean) {
        val recorder = activeRecorder ?: return
        try {
            workerExecutor.submit<Unit> {
                recorder.close()
            }.get()
        } catch (_: Throwable) {
            // Ignore close failures during shutdown paths.
        }
        activeRecorder = null
        frameWriteInFlight.set(false)
        if (saveMessage && latestRecordedFrameId != null) {
            latestStatusMessage = "Recording session saved"
            appendDebugLog("Recording session saved | session=$latestSessionId")
        }
    }

    private fun dispatchState() {
        val replayMetadata = replaySource.currentFrameMetadata()
        val sessionStartTimestampNs = when (currentMode) {
            RemoteMode.REPLAY -> replaySource.currentSession()?.firstTimestampNs() ?: 0L
            RemoteMode.LIVE -> recordingStartTimestampNs ?: 0L
        }
        val absoluteCurrentTimestampNs = when (currentMode) {
            RemoteMode.REPLAY -> replayMetadata?.timestampNs ?: 0L
            RemoteMode.LIVE -> latestRecordedTimestampNs ?: 0L
        }
        val sessionDurationNs = when (currentMode) {
            RemoteMode.REPLAY -> replaySource.currentSession()?.durationNs() ?: 0L
            RemoteMode.LIVE -> {
                val start = recordingStartTimestampNs
                val end = latestRecordedTimestampNs
                if (start != null && end != null) (end - start).coerceAtLeast(0L) else 0L
            }
        }
        val relativeMarkerTimestampsNs = currentMarkerTimestamps.map { markerTimestamp ->
            (markerTimestamp - sessionStartTimestampNs).coerceAtLeast(0L)
        }
        val relativeCurrentTimestampNs = (absoluteCurrentTimestampNs - sessionStartTimestampNs).coerceAtLeast(0L)

        listener.onRenderState(
            RemoteRenderState(
                mode = currentMode,
                sourceKind = sourceKind,
                sourceLabel = sourceLabelForState(),
                statusMessage = latestStatusMessage,
                isRecording = recordingEnabled,
                recordingSessionActive = activeRecorder != null,
                replayPlaying = replayPlaying,
                replaySpeed = replaySpeed,
                replayFrameIndex = replaySource.currentIndex(),
                replayFrameCount = replaySource.currentSession()?.frames?.size ?: 0,
                latestSessionId = latestSessionId,
                replayBitmap = replayBitmap,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                gazeSample = currentGaze,
                markerTimestampsNs = relativeMarkerTimestampsNs,
                currentTimestampNs = relativeCurrentTimestampNs,
                sessionDurationNs = sessionDurationNs,
                playerReady = vlcPlayerReady,
                demoSourceDisplayName = demoSourceDisplayName,
                rtspUrl = currentRtspUrl,
                udpPort = currentUdpPort,
                lastRecordedFrameId = latestRecordedFrameId,
                lastRecordedTimestampNs = latestRecordedTimestampNs,
                debugLog = buildDebugLogText(),
            ),
        )
    }

    private fun sourceLabelForState(): String {
        return when {
            currentMode == RemoteMode.REPLAY -> latestSessionId?.let { "replay_$it" } ?: "replay"
            sourceKind == RemoteSourceKind.RTSP -> currentRtspUrl ?: "rtsp"
            sourceKind == RemoteSourceKind.DEMO_VIDEO -> demoSourceDisplayName ?: currentDemoUri?.toString() ?: "demo_video"
            else -> "idle"
        }
    }

    private fun replaceReplayBitmap(newBitmap: android.graphics.Bitmap?) {
        if (replayBitmap != null && replayBitmap !== newBitmap) {
            replayBitmap?.recycle()
        }
        replayBitmap = newBitmap
    }

    private fun appendDebugLog(
        message: String,
        throwable: Throwable? = null,
    ) {
        val elapsedMs = SystemClock.elapsedRealtime() - controllerCreatedAtMs
        val line = "[${formatElapsed(elapsedMs)}] ${message.replace('\n', ' ')}"
        debugLogLines.addFirst(line)
        while (debugLogLines.size > MAX_DEBUG_LOG_LINES) {
            debugLogLines.removeLast()
        }
        if (throwable != null) {
            Log.e(DEBUG_LOG_TAG, line, throwable)
        } else {
            Log.d(DEBUG_LOG_TAG, line)
        }
    }

    private fun buildDebugLogText(): String {
        return if (debugLogLines.isEmpty()) {
            "No logs yet"
        } else {
            debugLogLines.joinToString(separator = "\n")
        }
    }

    private fun buildVlcErrorDetail(): String {
        return buildString {
            append("Playback error | player=libVLC")
            append(" | source=")
            append(sourceKind?.name ?: "NONE")
            append(" | ready=")
            append(vlcPlayerReady)
            append(" | video=")
            append(videoWidth)
            append("x")
            append(videoHeight)
            append(" | rtsp=")
            append(currentRtspUrl ?: "--")
        }
    }

    private fun vlcEventLabel(eventType: Int): String {
        return when (eventType) {
            MediaPlayer.Event.Opening -> "OPENING"
            MediaPlayer.Event.Buffering -> "BUFFERING"
            MediaPlayer.Event.Playing -> "PLAYING"
            MediaPlayer.Event.Paused -> "PAUSED"
            MediaPlayer.Event.Stopped -> "STOPPED"
            MediaPlayer.Event.EndReached -> "ENDED"
            MediaPlayer.Event.EncounteredError -> "ERROR"
            MediaPlayer.Event.Vout -> "VOUT"
            else -> "UNKNOWN($eventType)"
        }
    }

    private fun formatElapsed(elapsedMs: Long): String {
        return String.format(Locale.US, "%.3fs", elapsedMs / 1000.0)
    }

    private fun postMessage(message: String) {
        listener.onControllerMessage(message)
    }
}
