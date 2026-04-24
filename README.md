# LUBIE

## Current Status
- The app is currently an Android native Kotlin MVP. The main flow centers on `RTSP video + UDP gaze JSON` provided by a remote Raspberry Pi.
- The current home page uses XML Layout, and the main UI supports:
  - Connect RTSP
  - Automatically start local recording
  - Add marker
  - Replay the most recent session
  - Disconnect
- The app is currently locked to portrait orientation. The main video area is displayed at `16:9`, and full-screen display mode is used to avoid cropping the frame.
- A hidden demo mode is supported: continuously tapping the top status text `5` times opens the system video picker and imports a local video as the demo source.
- The demo mode does not receive UDP and no longer draws a gaze dot. It is intended for demo material where the overlay is already embedded in the video.
- The repository still retains the old local eye-tracking, OpenCV, CameraX, 2D detector, and segmentation code and files, but the home page no longer exposes these entry points.

## How to Run
- Open the project in Android Studio and run Gradle Sync.
- After running the `app` module, the home page stays on the remote video page by default, and no camera permission is requested.
- The current page is fixed to portrait orientation.
- Enter the Raspberry Pi RTSP address in the `RTSP URL` input box, and enter the gaze UDP port in the `UDP Port` input box.
- After tapping `Connect`:
  - The page uses libVLC `VLCVideoLayout` to play the RTSP video
  - RTSP is currently handled by libVLC for stream pulling and decoding, using VLC's RTSP/RTP processing pipeline
  - UDP starts receiving gaze JSON
  - Local recording starts automatically after the first frame is received
- Tap `Add Marker` to write a marker into the current recording session.
- Tap `Replay` to load the most recently recorded session and enter local playback mode.
- Hidden demo mode flow:
  - Tap the top status text `5` times in a row
  - Select a local `video/*` file
  - The page switches to demo source playback and also starts automatic recording, marker support, and replay support

## Data Flow
- Live mode is currently managed entirely by `RemoteTrackingController`:
  - RTSP or local demo video playback
  - UDP gaze listening
  - `TextureView` frame capture at a fixed `30 fps`
  - session recording
  - marker recording
  - replay scheduling
  - UI render state callbacks
- Under RTSP mode, the UDP payload is currently parsed using the following structure:
  - `screen_uv`
  - `tracking_valid`
  - `fps`
  - `inference_ms`
  - `calibration_state`
  - `status_message`
  - and other synchronization / calibration / feature-related fields
- Currently, a gaze dot is drawn on the video overlay only when:
  - `tracking_valid == true`
  - and `screen_uv` is not empty
- In demo mode, gaze is always empty. The overlay only shows status text and does not draw gaze points.

## Recording and Replay
- Recording is currently written uniformly into the app-private directory `files/remote_sessions/<sessionId>/`.
- Each session directory currently contains:
  - `session.json`
  - `metadata.jsonl`
  - `markers.jsonl`
  - `frames/000000.jpg ...`
- `session.json` currently records:
  - `sourceKind`
  - `sourceLabel`
  - `frameWidth / frameHeight`
  - `rtspUrl`
  - `demoSourceDisplayName`
  - `udpPort`
- `metadata.jsonl` currently records each frame:
  - `frameId`
  - `timestampNs`
  - `fileName`
  - `width / height`
  - `sourceKind`
  - `gazeSample`
- `markers.jsonl` currently records each line:
  - `timestampNs`
  - `frameId`
- Replay currently reads only the most recent session.
- Replay currently supports:
  - `Play / Pause`
  - `Prev / Next`
  - `0.25x / 1x`
  - dragging the progress bar to seek
  - marker-highlighted timeline
- RTSP session replay reuses the gaze snapshot saved during recording and redraws the overlay.
- Demo session replay does not draw gaze. It only shows video frames and the marker timeline.

## Core Modules
- `app/src/main/java/Aquin/lubie/MainActivity.kt`
  - Responsible for RTSP input, the hidden demo mode entry point, main button events, replay dragging, and UI text rendering.
- `app/src/main/java/Aquin/lubie/remote/RemoteTrackingController.kt`
  - Responsible for live / replay state switching, libVLC playback, UDP gaze handling, 30fps recording, markers, and replay scheduling.
- `app/src/main/java/Aquin/lubie/remote/UdpGazeReceiver.kt`
  - Responsible for UDP socket reception and gaze JSON parsing callbacks.
- `app/src/main/java/Aquin/lubie/remote/RemoteSessionStore.kt`
  - Responsible for remote session directory creation, metadata / marker encoding and decoding, and frame-sequence writing.
- `app/src/main/java/Aquin/lubie/remote/RemoteReplaySource.kt`
  - Responsible for reading the most recent remote session and stepping through replay and seek operations by timestamp.
- `app/src/main/java/Aquin/lubie/ui/DetectionOverlayView.kt`
  - Responsible for drawing the gaze dot and status text on the live / replay preview.
- `app/src/main/java/Aquin/lubie/ui/MarkerTimelineView.kt`
  - Responsible for drawing marker positions and the currently highlighted marker on the replay progress bar.

## Dependencies and Permissions
- The current main flow additionally uses:
  - `org.videolan.android:libvlc-all`
- The permission currently used by the main flow in `AndroidManifest.xml` is:
  - `android.permission.INTERNET`
- The app no longer requests camera permission.

## Testing
- Local unit tests currently cover:
  - UDP gaze JSON parsing
  - degraded behavior when `screen_uv=null`
  - remote session / metadata / marker JSON encoding and decoding
  - replay 30fps time base
  - replay seek positioning logic
  - session duration calculation
- The repository root currently provides a PC-side RTSP probe script:
  - `rtsp_probe.py`
  - Example: `python rtsp_probe.py rtsp://192.168.1.48:8554/fpv --transport udp --show-sdp`
  - Purpose: print `OPTIONS / DESCRIBE / SETUP / PLAY / RTP` logs to help distinguish path errors, transport issues, and server-side no-data issues
- The repository root also provides a local RTSP noise-source script:
  - `rtsp_noise_server.py`
  - Example: `python rtsp_noise_server.py --port 8554 --path android_noise`
  - Purpose: start an embedded RTSP server on the PC. It prefers to convert the real video file in the repository root, `test.mp4` by default, into `720p / 30fps / H.264 yuv420p` RTP data, then emits `RTP/UDP` or `RTP over RTSP/TCP` according to the client's SETUP request. If no usable video file exists, it falls back to a random noise source so Android-side troubleshooting can separate "material / encoding problems", "streaming-side problems", and "Android receiving-side problems"
  - The script currently defaults to `--source auto`:
    - If `test.mp4` exists in the repository root, it is used directly as the RTSP video source
    - If a local video is specified with `--input-file <path>`, that file is preferred
    - If no usable real video is available, it automatically falls back to the random noise source
  - To force real video, use: `python rtsp_noise_server.py --source file --input-file test.mp4`
  - To force random noise, use: `python rtsp_noise_server.py --source noise`
  - The script currently opens a local `ffplay` preview window automatically, making it easy to compare PC-side stream smoothness with Android-side stream smoothness directly
  - To disable the local preview window, use: `python rtsp_noise_server.py --port 8554 --path android_noise --no-preview`
  - The embedded RTSP server currently supports multiple clients connecting at the same time, and supports both `RTP/UDP` and `RTP over RTSP/TCP` video transport. The local `ffplay` preview window uses TCP by default, while Android currently prefers UDP
- The following commands have currently been verified as working:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest`
  - `./gradlew.bat :app:assembleDebug`

## Known Limits
- Replay currently reads only the most recent session and does not provide a session list page.
- "Save video" still follows the MVP path and writes a local `30 fps` JPEG frame sequence instead of MP4.
- The recording frame source is `TextureView`. If device performance is insufficient, frame writes are proactively skipped so that preview and the main thread remain smooth first.
- RTSP and demo mode share the same recording / replay structure, but demo mode does not receive UDP.
- The old local eye-tracking pipeline code is still retained in the repository. If we later need to fully clean up dependencies and entry points, we will need a separate consolidation pass.
