package Aquin.lubie

import Aquin.lubie.databinding.ActivityMainBinding
import Aquin.lubie.remote.GazeSample
import Aquin.lubie.remote.RemoteMode
import Aquin.lubie.remote.RemoteRenderState
import Aquin.lubie.remote.RemoteSourceKind
import Aquin.lubie.remote.RemoteTrackingController
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.util.UnstableApi
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.roundToInt

private const val PREFS_NAME = "remote_stream_prefs"
private const val PREF_RTSP_URL = "rtsp_url"
private const val PREF_UDP_PORT = "udp_port"
private const val DEMO_TAP_WINDOW_MS = 2_000L
private const val DEMO_TAP_COUNT = 5

/**
 * Summary: Hosts the remote RTSP live page, hidden demo mode entry, and replay UI.
 * @return Main single-activity entry for the remote streaming MVP.
 */
@UnstableApi
class MainActivity : ComponentActivity(), RemoteTrackingController.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: RemoteTrackingController

    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }
    private val demoTapTimestamps = ArrayDeque<Long>()
    private var latestState: RemoteRenderState? = null
    private var userSeekingReplay: Boolean = false

    private val demoVideoLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Throwable) {
            // Some providers do not offer persistable access; best effort is enough for MVP.
        }
        controller.startDemoVideo(uri, resolveDisplayName(uri) ?: "demo_video")
    }

    /**
     * Summary: Creates the page, restores saved inputs, and wires the remote controller.
     * @param savedInstanceState Saved activity state.
     * @return Unit.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        controller = RemoteTrackingController(
            context = this,
            playerView = binding.playerView,
            listener = this,
        )

        restoreInputs()
        initializeControls()
        renderIdleState()
    }

    /**
     * Summary: Resumes live playback when the activity returns to foreground.
     * @param none No parameters.
     * @return Unit.
     */
    override fun onResume() {
        super.onResume()
        controller.resume()
    }

    /**
     * Summary: Pauses live playback and timers while the activity is backgrounded.
     * @param none No parameters.
     * @return Unit.
     */
    override fun onPause() {
        controller.pause()
        super.onPause()
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
     * Summary: Renders the latest controller state into the UI.
     * @param state Latest remote render state.
     * @return Unit.
     */
    override fun onRenderState(state: RemoteRenderState) {
        latestState = state
        val isReplay = state.mode == RemoteMode.REPLAY

        binding.playerView.visibility = if (isReplay) View.GONE else View.VISIBLE
        binding.replayImageView.visibility = if (isReplay) View.VISIBLE else View.GONE
        binding.replayImageView.setImageBitmap(if (isReplay) state.replayBitmap else null)
        binding.textStatusHeader.text = buildHeaderText(state)
        binding.textPrimaryInfo.text = buildPrimaryInfo(state)
        binding.textSecondaryInfo.text = buildSecondaryInfo(state)
        binding.textDebugLog.text = state.debugLog.ifBlank { getString(R.string.label_debug_log_empty) }

        val gaze = state.gazeSample.takeIf { it?.isDrawable() == true }
        binding.overlayView.updateRemoteOverlay(
            frameWidth = state.videoWidth.coerceAtLeast(1),
            frameHeight = state.videoHeight.coerceAtLeast(1),
            gazeUvX = gaze?.screenUvX?.toFloat(),
            gazeUvY = gaze?.screenUvY?.toFloat(),
            statusText = buildOverlayText(state),
        )

        if (isReplay) {
            binding.markerTimelineView.updateTimeline(
                markerTimestampsNs = state.markerTimestampsNs,
                durationNs = state.sessionDurationNs,
                currentTimestampNs = state.currentTimestampNs,
            )
        } else {
            binding.markerTimelineView.clear()
        }

        if (!userSeekingReplay) {
            binding.seekReplayTimeline.progress = calculateReplayProgress(state)
        }

        updateButtons(state)
    }

    /**
     * Summary: Shows a short controller message to the user.
     * @param message Human-readable controller message.
     * @return Unit.
     */
    override fun onControllerMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Summary: Initializes UI listeners for live control, replay control, and hidden demo mode.
     * @param none No parameters.
     * @return Unit.
     */
    private fun initializeControls() {
        binding.buttonConnect.setOnClickListener {
            val rtspUrl = binding.editRtspUrl.text.toString().trim()
            val udpPort = parseUdpPort()
            if (rtspUrl.isBlank() || !rtspUrl.startsWith("rtsp://", ignoreCase = true)) {
                showToast(getString(R.string.message_invalid_rtsp))
                return@setOnClickListener
            }
            if (udpPort == null) {
                showToast(getString(R.string.message_invalid_udp))
                return@setOnClickListener
            }
            saveInputs(rtspUrl, udpPort)
            controller.connectRtsp(rtspUrl, udpPort)
        }

        binding.buttonRecord.setOnClickListener {
            controller.toggleRecording()
        }

        binding.buttonAddMarker.setOnClickListener {
            controller.addMarker()
        }

        binding.buttonReplay.setOnClickListener {
            controller.startReplayLatest()
        }

        binding.buttonDisconnect.setOnClickListener {
            controller.disconnect()
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

        binding.seekReplayTimeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            /**
             * Summary: Tracks whether the current seek event comes from a user drag.
             * @param seekBar Active seek bar.
             * @param progress Current seek progress.
             * @param fromUser Whether the change came from direct user input.
             * @return Unit.
             */
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean,
            ) {
                if (fromUser) {
                    userSeekingReplay = true
                }
            }

            /**
             * Summary: Marks the replay seek bar as actively dragged by the user.
             * @param seekBar Active seek bar.
             * @return Unit.
             */
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeekingReplay = true
            }

            /**
             * Summary: Seeks replay to the final dragged progress value.
             * @param seekBar Active seek bar.
             * @return Unit.
             */
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val currentSeekBar = seekBar ?: return
                controller.seekReplayToProgress(currentSeekBar.progress, currentSeekBar.max)
                userSeekingReplay = false
            }
        })

        binding.textStatusHeader.setOnClickListener {
            handleDemoUnlockTap()
        }
    }

    /**
     * Summary: Restores the last-used RTSP URL and UDP port from local preferences.
     * @param none No parameters.
     * @return Unit.
     */
    private fun restoreInputs() {
        binding.editRtspUrl.setText(
            preferences.getString(PREF_RTSP_URL, getString(R.string.default_rtsp_url)),
        )
        binding.editUdpPort.setText(
            preferences.getString(PREF_UDP_PORT, getString(R.string.default_udp_port)),
        )
    }

    /**
     * Summary: Saves the latest RTSP URL and UDP port for the next launch.
     * @param rtspUrl RTSP URL to persist.
     * @param udpPort UDP port to persist.
     * @return Unit.
     */
    private fun saveInputs(
        rtspUrl: String,
        udpPort: Int,
    ) {
        preferences.edit()
            .putString(PREF_RTSP_URL, rtspUrl)
            .putString(PREF_UDP_PORT, udpPort.toString())
            .apply()
    }

    /**
     * Summary: Parses the UDP port input into a valid port number.
     * @param none No parameters.
     * @return Parsed UDP port or null when invalid.
     */
    private fun parseUdpPort(): Int? {
        val text = binding.editUdpPort.text.toString().trim()
        val port = text.toIntOrNull() ?: return null
        return port.takeIf { it in 1..65535 }
    }

    /**
     * Summary: Opens the hidden demo picker after enough quick taps on the status text.
     * @param none No parameters.
     * @return Unit.
     */
    private fun handleDemoUnlockTap() {
        val now = SystemClock.elapsedRealtime()
        while (demoTapTimestamps.isNotEmpty() && now - demoTapTimestamps.first() > DEMO_TAP_WINDOW_MS) {
            demoTapTimestamps.removeFirst()
        }
        demoTapTimestamps.addLast(now)
        if (demoTapTimestamps.size >= DEMO_TAP_COUNT) {
            demoTapTimestamps.clear()
            showToast(getString(R.string.message_demo_picker_hint))
            demoVideoLauncher.launch(arrayOf("video/*"))
        }
    }

    /**
     * Summary: Resolves a user-friendly file name for the selected demo video URI.
     * @param uri Selected demo URI.
     * @return Display name or null when unavailable.
     */
    private fun resolveDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.lastPathSegment
        }
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return uri.lastPathSegment
    }

    /**
     * Summary: Renders the initial idle UI before the first controller callback arrives.
     * @param none No parameters.
     * @return Unit.
     */
    private fun renderIdleState() {
        binding.textStatusHeader.text = getString(R.string.status_idle)
        binding.textPrimaryInfo.text = getString(R.string.label_primary_info)
        binding.textSecondaryInfo.text = getString(R.string.label_secondary_info)
        binding.textDebugLog.text = getString(R.string.label_debug_log_empty)
        binding.overlayView.clear(getString(R.string.status_idle))
        binding.markerTimelineView.clear()
        updateButtons(null)
    }

    /**
     * Summary: Builds the top status text shown above the controls.
     * @param state Latest controller state.
     * @return Formatted header string.
     */
    private fun buildHeaderText(state: RemoteRenderState): String {
        val sourceLabel = when (state.sourceKind) {
            RemoteSourceKind.RTSP -> "RTSP"
            RemoteSourceKind.DEMO_VIDEO -> "Demo"
            null -> "Idle"
        }
        val modeLabel = state.mode.name.lowercase(Locale.US)
        val recordingLabel = when {
            !state.isRecording -> "record=off"
            state.recordingSessionActive -> "record=active"
            else -> "record=armed"
        }
        return "$sourceLabel | $modeLabel | $recordingLabel | ${state.statusMessage}"
    }

    /**
     * Summary: Builds the primary info block under the preview.
     * @param state Latest controller state.
     * @return Formatted primary info text.
     */
    private fun buildPrimaryInfo(state: RemoteRenderState): String {
        val markerCount = state.markerTimestampsNs.size
        val replayText = if (state.mode == RemoteMode.REPLAY) {
            " | replay=${state.replayFrameIndex + 1}/${state.replayFrameCount}"
        } else {
            ""
        }
        val sourceValue = when (state.sourceKind) {
            RemoteSourceKind.RTSP -> state.rtspUrl ?: "n/a"
            RemoteSourceKind.DEMO_VIDEO -> state.demoSourceDisplayName ?: "demo_video"
            null -> "n/a"
        }
        return buildString {
            append("Primary Info")
            append('\n')
            append("source=$sourceValue")
            append(replayText)
            append('\n')
            append("udp=${state.udpPort ?: "--"} | session=${state.latestSessionId ?: "--"}")
            append('\n')
            append("video=${state.videoWidth}x${state.videoHeight} | markers=$markerCount")
            append('\n')
            append("time=${formatMs(state.currentTimestampNs)} / ${formatMs(state.sessionDurationNs)}")
        }
    }

    /**
     * Summary: Builds the secondary info block with gaze and playback details.
     * @param state Latest controller state.
     * @return Formatted secondary info text.
     */
    private fun buildSecondaryInfo(state: RemoteRenderState): String {
        val gaze = state.gazeSample
        return buildString {
            append("Secondary Info")
            append('\n')
            append("playerReady=${state.playerReady} | speed=${state.replaySpeed.label}")
            append('\n')
            append("lastFrame=${state.lastRecordedFrameId ?: "--"} | lastTs=${state.lastRecordedTimestampNs ?: "--"}")
            append('\n')
            if (gaze == null) {
                append("gaze=none")
            } else {
                append(
                    String.format(
                        Locale.US,
                        "gaze valid=%s | uv=(%s, %s)",
                        gaze.trackingValid,
                        gaze.screenUvX?.let { "%.3f".format(Locale.US, it) } ?: "--",
                        gaze.screenUvY?.let { "%.3f".format(Locale.US, it) } ?: "--",
                    ),
                )
                append('\n')
                append(
                    String.format(
                        Locale.US,
                        "fps=%s | inference=%sms | calib=%s",
                        gaze.fps?.let { "%.2f".format(Locale.US, it) } ?: "--",
                        gaze.inferenceMs?.let { "%.2f".format(Locale.US, it) } ?: "--",
                        gaze.calibrationState ?: "--",
                    ),
                )
                append('\n')
                append("status=${gaze.statusMessage ?: "--"}")
            }
        }
    }

    /**
     * Summary: Builds the short overlay text shown on top of the preview.
     * @param state Latest controller state.
     * @return Formatted overlay string.
     */
    private fun buildOverlayText(state: RemoteRenderState): String {
        val gaze = state.gazeSample
        return buildString {
            append(state.mode.name)
            append(" | ")
            append(state.sourceKind?.name ?: "NO_SOURCE")
            append('\n')
            append(if (state.isRecording) "record on" else "record off")
            append(" | ")
            append(state.statusMessage)
            if (gaze != null) {
                append('\n')
                append(
                    String.format(
                        Locale.US,
                        "tracking=%s | fps=%s",
                        gaze.trackingValid,
                        gaze.fps?.let { "%.2f".format(Locale.US, it) } ?: "--",
                    ),
                )
            }
        }
    }

    /**
     * Summary: Updates action button labels and enabled states.
     * @param state Latest render state when available.
     * @return Unit.
     */
    private fun updateButtons(state: RemoteRenderState?) {
        val isReplay = state?.mode == RemoteMode.REPLAY
        binding.buttonRecord.text = getString(
            if (state?.isRecording == true) R.string.action_stop_record else R.string.action_record,
        )
        binding.buttonReplayPlayPause.text = getString(
            if (state?.replayPlaying == true) R.string.action_pause else R.string.action_play,
        )
        binding.buttonReplaySpeed.text = state?.replaySpeed?.label ?: getString(R.string.action_speed_normal)

        binding.buttonRecord.isEnabled = state?.mode == RemoteMode.LIVE && state.sourceKind != null
        binding.buttonAddMarker.isEnabled = state?.mode == RemoteMode.LIVE && state.recordingSessionActive
        binding.buttonDisconnect.isEnabled = state?.sourceKind != null || state?.mode == RemoteMode.REPLAY
        binding.seekReplayTimeline.isEnabled = isReplay && (state?.replayFrameCount ?: 0) > 0
        binding.buttonReplayPlayPause.isEnabled = isReplay
        binding.buttonReplayPrev.isEnabled = isReplay
        binding.buttonReplayNext.isEnabled = isReplay
        binding.buttonReplaySpeed.isEnabled = isReplay
    }

    /**
     * Summary: Converts the current replay timestamp into seek bar progress.
     * @param state Latest controller state.
     * @return Seek bar progress in the configured range.
     */
    private fun calculateReplayProgress(state: RemoteRenderState): Int {
        if (state.mode != RemoteMode.REPLAY || state.sessionDurationNs <= 0L) {
            return 0
        }
        val fraction = state.currentTimestampNs.toDouble() / state.sessionDurationNs.toDouble()
        return (binding.seekReplayTimeline.max * fraction.coerceIn(0.0, 1.0)).roundToInt()
    }

    /**
     * Summary: Formats a nanosecond duration into milliseconds for UI text.
     * @param durationNs Duration in nanoseconds.
     * @return Formatted milliseconds string.
     */
    private fun formatMs(durationNs: Long): String {
        return String.format(Locale.US, "%.0fms", durationNs / 1_000_000.0)
    }

    /**
     * Summary: Shows a short toast message.
     * @param message User-facing message.
     * @return Unit.
     */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
