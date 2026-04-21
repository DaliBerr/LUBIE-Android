package Aquin.lubie.remote

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SESSIONS_ROOT_DIR = "remote_sessions"
private const val SESSION_FILE_NAME = "session.json"
private const val METADATA_FILE_NAME = "metadata.jsonl"
private const val MARKERS_FILE_NAME = "markers.jsonl"
private const val FRAMES_DIR_NAME = "frames"

/**
 * Summary: Stores and loads recorded remote playback sessions.
 * @param context Application context.
 * @return Session store rooted in the app private files directory.
 */
class RemoteSessionStore(
    private val context: Context,
) {

    /**
     * Summary: Creates a recorder for one new remote session.
     * @param sourceKind Source kind being recorded.
     * @param sourceLabel Human-readable source label.
     * @param frameWidth Recorded frame width.
     * @param frameHeight Recorded frame height.
     * @param rtspUrl Active RTSP URL when applicable.
     * @param demoSourceDisplayName Active demo source name when applicable.
     * @param udpPort Active UDP port when applicable.
     * @return Recorder rooted in a new private session directory.
     */
    fun createRecorder(
        sourceKind: RemoteSourceKind,
        sourceLabel: String,
        frameWidth: Int,
        frameHeight: Int,
        rtspUrl: String?,
        demoSourceDisplayName: String?,
        udpPort: Int?,
    ): RemoteSessionRecorder {
        val rootDir = sessionsRootDir().apply { mkdirs() }
        val createdAtEpochMs = System.currentTimeMillis()
        val sessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(createdAtEpochMs))
        val sessionDir = File(rootDir, sessionId).apply { mkdirs() }
        val framesDir = File(sessionDir, FRAMES_DIR_NAME).apply { mkdirs() }
        val sessionInfo = RemoteSessionInfo(
            sessionId = sessionId,
            sourceKind = sourceKind,
            sourceLabel = sourceLabel,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            createdAtEpochMs = createdAtEpochMs,
            sessionDirPath = sessionDir.absolutePath,
            rtspUrl = rtspUrl,
            demoSourceDisplayName = demoSourceDisplayName,
            udpPort = udpPort,
            frames = emptyList(),
            markers = emptyList(),
        )
        writeSessionFile(sessionDir, sessionInfo)
        val metadataWriter = BufferedWriter(
            OutputStreamWriter(
                FileOutputStream(File(sessionDir, METADATA_FILE_NAME), true),
                StandardCharsets.UTF_8,
            ),
        )
        val markersWriter = BufferedWriter(
            OutputStreamWriter(
                FileOutputStream(File(sessionDir, MARKERS_FILE_NAME), true),
                StandardCharsets.UTF_8,
            ),
        )
        return RemoteSessionRecorder(sessionInfo, framesDir, metadataWriter, markersWriter)
    }

    /**
     * Summary: Loads the newest recorded remote session.
     * @param none No parameters.
     * @return Latest session descriptor or null when none exists.
     */
    fun loadLatestSession(): RemoteSessionInfo? {
        val sessionDir = latestSessionDirectory() ?: return null
        return loadSession(sessionDir)
    }

    private fun sessionsRootDir(): File = File(context.filesDir, SESSIONS_ROOT_DIR)

    private fun latestSessionDirectory(): File? {
        return sessionsRootDir()
            .takeIf { it.exists() }
            ?.listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun writeSessionFile(
        sessionDir: File,
        sessionInfo: RemoteSessionInfo,
    ) {
        File(sessionDir, SESSION_FILE_NAME).writeText(sessionInfoToJsonString(sessionInfo), StandardCharsets.UTF_8)
    }

    private fun loadSession(sessionDir: File): RemoteSessionInfo? {
        val sessionFile = File(sessionDir, SESSION_FILE_NAME)
        val metadataFile = File(sessionDir, METADATA_FILE_NAME)
        val markersFile = File(sessionDir, MARKERS_FILE_NAME)
        if (!sessionFile.exists() || !metadataFile.exists()) return null

        val frames = metadataFile
            .readLines(StandardCharsets.UTF_8)
            .filter { it.isNotBlank() }
            .map { parseMetadataFromString(it) }
        val markers = if (markersFile.exists()) {
            markersFile
                .readLines(StandardCharsets.UTF_8)
                .filter { it.isNotBlank() }
                .map { parseMarkerFromString(it) }
        } else {
            emptyList()
        }
        return parseSessionInfoFromString(
            jsonText = sessionFile.readText(StandardCharsets.UTF_8),
            sessionDirPath = sessionDir.absolutePath,
            frames = frames,
            markers = markers,
        )
    }

    companion object {
        /**
         * Summary: Encodes one session descriptor into JSON text.
         * @param sessionInfo Session descriptor.
         * @return JSON string used for session.json.
         */
        internal fun sessionInfoToJsonString(sessionInfo: RemoteSessionInfo): String {
            return JSONObject().apply {
                put("sessionId", sessionInfo.sessionId)
                put("sourceKind", sessionInfo.sourceKind.name)
                put("sourceLabel", sessionInfo.sourceLabel)
                put("frameWidth", sessionInfo.frameWidth)
                put("frameHeight", sessionInfo.frameHeight)
                put("createdAtEpochMs", sessionInfo.createdAtEpochMs)
                put("rtspUrl", sessionInfo.rtspUrl ?: JSONObject.NULL)
                put("demoSourceDisplayName", sessionInfo.demoSourceDisplayName ?: JSONObject.NULL)
                put("udpPort", sessionInfo.udpPort ?: JSONObject.NULL)
            }.toString()
        }

        /**
         * Summary: Parses one session descriptor from JSON text.
         * @param jsonText Raw session JSON text.
         * @param sessionDirPath Absolute session directory path.
         * @param frames Recorded frame metadata list.
         * @param markers Recorded marker metadata list.
         * @return Parsed remote session descriptor.
         */
        internal fun parseSessionInfoFromString(
            jsonText: String,
            sessionDirPath: String,
            frames: List<RemoteRecordedFrameMetadata>,
            markers: List<RemoteMarkerEvent>,
        ): RemoteSessionInfo {
            val json = JSONObject(jsonText)
            return RemoteSessionInfo(
                sessionId = json.getString("sessionId"),
                sourceKind = enumValueOf(json.getString("sourceKind")),
                sourceLabel = json.getString("sourceLabel"),
                frameWidth = json.getInt("frameWidth"),
                frameHeight = json.getInt("frameHeight"),
                createdAtEpochMs = json.getLong("createdAtEpochMs"),
                sessionDirPath = sessionDirPath,
                rtspUrl = json.optNullableString("rtspUrl"),
                demoSourceDisplayName = json.optNullableString("demoSourceDisplayName"),
                udpPort = json.optNullableInt("udpPort"),
                frames = frames,
                markers = markers,
            )
        }

        /**
         * Summary: Encodes one frame metadata record into JSON line text.
         * @param metadata Frame metadata record.
         * @return JSON line text for metadata.jsonl.
         */
        internal fun metadataToJsonLine(metadata: RemoteRecordedFrameMetadata): String {
            return JSONObject().apply {
                put("frameId", metadata.frameId)
                put("timestampNs", metadata.timestampNs)
                put("fileName", metadata.fileName)
                put("width", metadata.width)
                put("height", metadata.height)
                put("sourceKind", metadata.sourceKind.name)
                put("gazeSample", metadata.gazeSample?.let { GazeSampleJson.toJsonObject(it) } ?: JSONObject.NULL)
            }.toString()
        }

        /**
         * Summary: Parses one frame metadata line.
         * @param jsonText Raw metadata JSON text.
         * @return Parsed frame metadata record.
         */
        internal fun parseMetadataFromString(jsonText: String): RemoteRecordedFrameMetadata {
            val json = JSONObject(jsonText)
            return RemoteRecordedFrameMetadata(
                frameId = json.getLong("frameId"),
                timestampNs = json.getLong("timestampNs"),
                fileName = json.getString("fileName"),
                width = json.getInt("width"),
                height = json.getInt("height"),
                sourceKind = enumValueOf(json.getString("sourceKind")),
                gazeSample = json.optJSONObject("gazeSample")?.let { GazeSampleJson.fromJsonObject(it) },
            )
        }

        /**
         * Summary: Encodes one marker event into JSON line text.
         * @param marker Marker event.
         * @return JSON line text for markers.jsonl.
         */
        internal fun markerToJsonLine(marker: RemoteMarkerEvent): String {
            return JSONObject().apply {
                put("timestampNs", marker.timestampNs)
                put("frameId", marker.frameId)
            }.toString()
        }

        /**
         * Summary: Parses one marker event line.
         * @param jsonText Raw marker JSON text.
         * @return Parsed marker event.
         */
        internal fun parseMarkerFromString(jsonText: String): RemoteMarkerEvent {
            val json = JSONObject(jsonText)
            return RemoteMarkerEvent(
                timestampNs = json.getLong("timestampNs"),
                frameId = json.getLong("frameId"),
            )
        }

        private fun JSONObject.optNullableInt(key: String): Int? {
            return if (!has(key) || isNull(key)) null else getInt(key)
        }

        private fun JSONObject.optNullableString(key: String): String? {
            return if (!has(key) || isNull(key)) null else getString(key)
        }
    }
}

/**
 * Summary: Writes recorded frames and marker events into one remote session directory.
 * @param sessionInfo Session descriptor.
 * @param framesDir Directory that stores recorded JPEG frames.
 * @param metadataWriter Writer for frame metadata.
 * @param markersWriter Writer for marker metadata.
 * @return Recorder for one active remote session.
 */
class RemoteSessionRecorder(
    val sessionInfo: RemoteSessionInfo,
    private val framesDir: File,
    private val metadataWriter: BufferedWriter,
    private val markersWriter: BufferedWriter,
) {
    private var nextFrameId: Long = 0L

    /**
     * Summary: Appends one recorded frame and its current gaze snapshot.
     * @param bitmap Recorded frame bitmap.
     * @param timestampNs Frame timestamp in nanoseconds.
     * @param gazeSample Gaze snapshot attached to the frame when available.
     * @return Persisted frame metadata record.
     */
    fun appendFrame(
        bitmap: Bitmap,
        timestampNs: Long,
        gazeSample: GazeSample?,
    ): RemoteRecordedFrameMetadata {
        val frameId = nextFrameId++
        val fileName = String.format(Locale.US, "%06d.jpg", frameId)
        val frameFile = File(framesDir, fileName)
        FileOutputStream(frameFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        }

        val metadata = RemoteRecordedFrameMetadata(
            frameId = frameId,
            timestampNs = timestampNs,
            fileName = fileName,
            width = bitmap.width,
            height = bitmap.height,
            sourceKind = sessionInfo.sourceKind,
            gazeSample = gazeSample,
        )
        metadataWriter.append(RemoteSessionStore.metadataToJsonLine(metadata))
        metadataWriter.newLine()
        metadataWriter.flush()
        return metadata
    }

    /**
     * Summary: Appends one marker event for the current session.
     * @param marker Marker event to persist.
     * @return Unit.
     */
    fun appendMarker(marker: RemoteMarkerEvent) {
        markersWriter.append(RemoteSessionStore.markerToJsonLine(marker))
        markersWriter.newLine()
        markersWriter.flush()
    }

    /**
     * Summary: Closes the session writers.
     * @param none No parameters.
     * @return Unit.
     */
    fun close() {
        metadataWriter.flush()
        metadataWriter.close()
        markersWriter.flush()
        markersWriter.close()
    }
}
