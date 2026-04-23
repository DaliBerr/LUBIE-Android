package Aquin.lubie.remote

import Aquin.lubie.tracking.model.ReplaySpeed
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
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
 * @param playerView Shared PlayerView used for live playback.
 * @param listener UI listener that renders the latest remote state.
 * @return Single-controller coordinator for the remote streaming MVP.
 */
@UnstableApi
class RemoteTrackingController(
    context: Context,
    private val playerView: PlayerView,
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
    private var lastLoggedPlaybackState: Int? = null

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

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (lastLoggedPlaybackState != playbackState) {
                lastLoggedPlaybackState = playbackState
                appendDebugLog(
                    "Player state=${playbackStateLabel(playbackState)} " +
                        "source=${sourceKind?.name ?: "NONE"} ready=${playbackState == Player.STATE_READY}",
                )
            }
            latestStatusMessage = when (playbackState) {
                Player.STATE_IDLE -> if (sourceKind == null) "Idle" else "Player idle"
                Player.STATE_BUFFERING -> "Buffering live video"
                Player.STATE_READY -> "${sourceLabelForState()} ready"
                Player.STATE_ENDED -> if (sourceKind == RemoteSourceKind.DEMO_VIDEO) "Demo playback finished" else "Playback ended"
                else -> latestStatusMessage
            }

            if (playbackState == Player.STATE_READY && currentMode == RemoteMode.LIVE && recordingEnabled) {
                startFrameCaptureLoopIfNeeded()
            }

            if (playbackState == Player.STATE_ENDED &&
                currentMode == RemoteMode.LIVE &&
                sourceKind == RemoteSourceKind.DEMO_VIDEO
            ) {
                stopFrameCaptureLoop()
                flushAndCloseRecorder(saveMessage = true)
                recordingEnabled = false
            }

            dispatchState()
        }

        override fun onRenderedFirstFrame() {
            appendDebugLog("First frame rendered")
            if (currentMode == RemoteMode.LIVE && recordingEnabled) {
                startFrameCaptureLoopIfNeeded()
            }
            dispatchState()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoWidth = videoSize.width
                videoHeight = videoSize.height
                appendDebugLog("Video size=${videoSize.width}x${videoSize.height}")
                dispatchState()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            latestStatusMessage = "Playback error: ${error.errorCodeName}"
            stopFrameCaptureLoop()
            appendDebugLog(buildPlaybackErrorDetail(error), error)
            dispatchState()
            postMessage(latestStatusMessage)
        }
    }

    private val player = ExoPlayer.Builder(appContext)
        .build()
        .apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
            addListener(playerListener)
        }

    init {
        playerView.player = player
        playerView.useController = false
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
        appendDebugLog("Connect requested | url=$rtspUrl udp=$udpPort transport=udp-first")
        val rtspMediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(false)
            .createMediaSource(MediaItem.fromUri(rtspUrl))
        switchToLiveSource(
            sourceKind = RemoteSourceKind.RTSP,
            rtspUrl = rtspUrl,
            udpPort = udpPort,
            demoUri = null,
            demoName = null,
            mediaItem = MediaItem.fromUri(rtspUrl),
            mediaSource = rtspMediaSource,
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
            mediaItem = MediaItem.fromUri(uri),
            mediaSource = null,
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
        player.stop()
        player.clearMediaItems()
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
        lastLoggedPlaybackState = null
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
        player.stop()
        player.clearMediaItems()
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
        player.release()
        workerExecutor.shutdown()
    }

    private fun switchToLiveSource(
        sourceKind: RemoteSourceKind,
        rtspUrl: String?,
        udpPort: Int?,
        demoUri: Uri?,
        demoName: String?,
        mediaItem: MediaItem,
        mediaSource: androidx.media3.exoplayer.source.MediaSource?,
        statusMessage: String,
    ) {
        stopReplayInternal()
        stopFrameCaptureLoop()
        flushAndCloseRecorder(saveMessage = true)
        gazeReceiver.stop()
        player.stop()
        player.clearMediaItems()

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
        lastLoggedPlaybackState = null
        appendDebugLog(
            "Switch live source | kind=${sourceKind.name} label=${demoName ?: rtspUrl ?: "unknown"}",
        )

        if (mediaSource != null) {
            player.setMediaSource(mediaSource)
        } else {
            player.setMediaItem(mediaItem)
        }
        player.prepare()
        player.playWhenReady = true
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
        if (player.playbackState != Player.STATE_READY) return
        mainHandler.removeCallbacks(frameCaptureRunnable)
        mainHandler.post(frameCaptureRunnable)
    }

    private fun stopFrameCaptureLoop() {
        mainHandler.removeCallbacks(frameCaptureRunnable)
    }

    private fun captureFrameIfNeeded() {
        if (currentMode != RemoteMode.LIVE || !recordingEnabled || sourceKind == null) return
        if (frameWriteInFlight.get()) return
        val textureView = playerView.videoSurfaceView as? TextureView ?: return
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
                playerReady = player.playbackState == Player.STATE_READY,
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

    private fun buildPlaybackErrorDetail(error: PlaybackException): String {
        val causeSummary = buildThrowableSummary(error.cause)
        val messageSummary = error.message?.replace('\n', ' ') ?: "n/a"
        return buildString {
            append("Playback error | code=")
            append(error.errorCodeName)
            append(" | message=")
            append(messageSummary)
            append(" | cause=")
            append(causeSummary)
            append(" | source=")
            append(sourceKind?.name ?: "NONE")
            append(" | playbackState=")
            append(playbackStateLabel(player.playbackState))
            append(" | video=")
            append(videoWidth)
            append("x")
            append(videoHeight)
            append(" | rtsp=")
            append(currentRtspUrl ?: "--")
        }
    }

    private fun buildThrowableSummary(throwable: Throwable?): String {
        if (throwable == null) return "none"
        val parts = mutableListOf<String>()
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 4) {
            val typeName = current::class.java.simpleName
            val message = current.message?.replace('\n', ' ') ?: "no-message"
            parts.add("$typeName($message)")
            current = current.cause
            depth += 1
        }
        return parts.joinToString(" -> ")
    }

    private fun playbackStateLabel(playbackState: Int): String {
        return when (playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($playbackState)"
        }
    }

    private fun formatElapsed(elapsedMs: Long): String {
        return String.format(Locale.US, "%.3fs", elapsedMs / 1000.0)
    }

    private fun postMessage(message: String) {
        listener.onControllerMessage(message)
    }
}
