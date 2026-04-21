package Aquin.lubie.tracking.pipeline

import Aquin.lubie.tracking.model.EyeFrame
import Aquin.lubie.tracking.model.PupilObservation2D
import Aquin.lubie.tracking.model.RecordedFrameMetadata
import Aquin.lubie.tracking.model.RecordedSessionInfo
import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SESSIONS_ROOT_DIR = "eye_sessions"
private const val SESSION_FILE_NAME = "session.json"
private const val METADATA_FILE_NAME = "metadata.jsonl"
private const val FRAMES_DIR_NAME = "frames"
private val FLAT_JSON_PATTERN = Regex("\"([^\"]+)\"\\s*:\\s*(\"((?:\\\\.|[^\"])*)\"|-?\\d+(?:\\.\\d+)?|null)")

/**
 * Summary: Stores and loads recorded eye tracking sessions.
 * @param context Application context.
 * @param preprocessor Shared preprocessor used for PNG encoding.
 * @return Session store rooted in the app private directory.
 */
class EyeSessionStore(
    private val context: Context,
    private val preprocessor: EyePreprocessor,
) {

    /**
     * Summary: Returns whether at least one recorded session exists.
     * @param none No parameters.
     * @return True when a recorded session can be replayed.
     */
    fun hasSessions(): Boolean = latestSessionDirectory() != null

    /**
     * Summary: Creates a recorder for a new live session.
     * @param sourceTag Source label used during recording.
     * @param eyeId Eye index.
     * @param frameWidth Frame width in pixels.
     * @param frameHeight Frame height in pixels.
     * @return Recorder that writes frames and metadata into a new session directory.
     */
    fun createRecorder(
        sourceTag: String,
        eyeId: Int,
        frameWidth: Int,
        frameHeight: Int,
    ): EyeSessionRecorder {
        val rootDir = sessionsRootDir().apply { mkdirs() }
        val createdAtEpochMs = System.currentTimeMillis()
        val sessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(createdAtEpochMs))
        val sessionDir = File(rootDir, sessionId).apply { mkdirs() }
        val framesDir = File(sessionDir, FRAMES_DIR_NAME).apply { mkdirs() }
        val sessionInfo = RecordedSessionInfo(
            sessionId = sessionId,
            sourceTag = sourceTag,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            eyeId = eyeId,
            createdAtEpochMs = createdAtEpochMs,
            sessionDirPath = sessionDir.absolutePath,
            frames = emptyList(),
        )
        writeSessionFile(sessionDir, sessionInfo)
        val metadataWriter = BufferedWriter(
            OutputStreamWriter(
                FileOutputStream(File(sessionDir, METADATA_FILE_NAME), true),
                StandardCharsets.UTF_8,
            ),
        )
        return EyeSessionRecorder(sessionInfo, framesDir, metadataWriter, preprocessor)
    }

    /**
     * Summary: Loads the latest recorded session descriptor.
     * @param none No parameters.
     * @return Latest recorded session or null when no session exists.
     */
    fun loadLatestSession(): RecordedSessionInfo? {
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

    private fun writeSessionFile(sessionDir: File, sessionInfo: RecordedSessionInfo) {
        File(sessionDir, SESSION_FILE_NAME).writeText(sessionInfoToJsonString(sessionInfo), StandardCharsets.UTF_8)
    }

    private fun loadSession(sessionDir: File): RecordedSessionInfo? {
        val sessionFile = File(sessionDir, SESSION_FILE_NAME)
        val metadataFile = File(sessionDir, METADATA_FILE_NAME)
        if (!sessionFile.exists() || !metadataFile.exists()) return null

        val sessionText = sessionFile.readText(StandardCharsets.UTF_8)
        val frames = metadataFile
            .readLines(StandardCharsets.UTF_8)
            .filter { it.isNotBlank() }
            .map { line -> parseMetadataFromString(line) }

        return parseSessionInfoFromString(sessionText, sessionDir.absolutePath, frames)
    }

    companion object {
        /**
         * Summary: Encodes one session descriptor into JSON.
         * @param sessionInfo Session descriptor.
         * @return JSON object used for persistence.
         */
        internal fun sessionInfoToJsonString(sessionInfo: RecordedSessionInfo): String {
            return buildString {
                append("{")
                append("\"sessionId\":\"${escapeJson(sessionInfo.sessionId)}\",")
                append("\"sourceTag\":\"${escapeJson(sessionInfo.sourceTag)}\",")
                append("\"frameWidth\":${sessionInfo.frameWidth},")
                append("\"frameHeight\":${sessionInfo.frameHeight},")
                append("\"eyeId\":${sessionInfo.eyeId},")
                append("\"createdAtEpochMs\":${sessionInfo.createdAtEpochMs}")
                append("}")
            }
        }

        /**
         * Summary: Decodes one persisted session descriptor from text.
         * @param jsonText Session JSON text.
         * @param sessionDirPath Absolute session directory path.
         * @param frames Session frame metadata list.
         * @return Parsed session descriptor.
         */
        internal fun parseSessionInfoFromString(
            jsonText: String,
            sessionDirPath: String,
            frames: List<RecordedFrameMetadata>,
        ): RecordedSessionInfo {
            val values = parseFlatJson(jsonText)
            return RecordedSessionInfo(
                sessionId = values.getValue("sessionId"),
                sourceTag = values.getValue("sourceTag"),
                frameWidth = values.getValue("frameWidth").toInt(),
                frameHeight = values.getValue("frameHeight").toInt(),
                eyeId = values.getValue("eyeId").toInt(),
                createdAtEpochMs = values.getValue("createdAtEpochMs").toLong(),
                sessionDirPath = sessionDirPath,
                frames = frames,
            )
        }

        /**
         * Summary: Encodes one frame metadata line into JSON.
         * @param metadata Frame metadata entry.
         * @return JSON object used for metadata.jsonl.
         */
        internal fun metadataToJsonLine(metadata: RecordedFrameMetadata): String {
            return buildString {
                append("{")
                append("\"frameId\":${metadata.frameId},")
                append("\"timestampNs\":${metadata.timestampNs},")
                append("\"fileName\":\"${escapeJson(metadata.fileName)}\",")
                append("\"quality\":\"${metadata.quality.name}\",")
                append("\"confidence\":")
                append(metadata.confidence ?: "null")
                append("}")
            }
        }

        /**
         * Summary: Decodes one metadata line from text.
         * @param jsonText Metadata JSON text.
         * @return Parsed frame metadata.
         */
        internal fun parseMetadataFromString(jsonText: String): RecordedFrameMetadata {
            val values = parseFlatJson(jsonText)
            return RecordedFrameMetadata(
                frameId = values.getValue("frameId").toLong(),
                timestampNs = values.getValue("timestampNs").toLong(),
                fileName = values.getValue("fileName"),
                quality = enumValueOf(values.getValue("quality")),
                confidence = values["confidence"]?.takeUnless { it == "null" }?.toDouble(),
            )
        }

        private fun parseFlatJson(jsonText: String): Map<String, String> {
            return FLAT_JSON_PATTERN.findAll(jsonText).associate { match ->
                val key = match.groupValues[1]
                val rawStringValue = match.groupValues[3]
                val value = if (rawStringValue.isNotEmpty()) unescapeJson(rawStringValue) else match.groupValues[2]
                key to value
            }
        }

        private fun escapeJson(value: String): String {
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
        }

        private fun unescapeJson(value: String): String {
            return value
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
    }
}

/**
 * Summary: Writes grayscale frame PNGs and metadata for one session.
 * @param sessionInfo Session descriptor.
 * @param framesDir Session frame directory.
 * @param metadataWriter Session metadata writer.
 * @param preprocessor Shared preprocessor used for bitmap encoding.
 * @return Recorder for one active session.
 */
class EyeSessionRecorder(
    val sessionInfo: RecordedSessionInfo,
    private val framesDir: File,
    private val metadataWriter: BufferedWriter,
    private val preprocessor: EyePreprocessor,
) {

    /**
     * Summary: Appends one recorded frame and its metadata.
     * @param frame Source frame.
     * @param observation 2D observation recorded for the frame.
     * @return Unit.
     */
    fun append(
        frame: EyeFrame,
        observation: PupilObservation2D,
    ) {
        val fileName = String.format(Locale.US, "%06d.png", frame.frameId)
        val frameFile = File(framesDir, fileName)
        val bitmap = preprocessor.toBitmap(frame.grayMat)
        FileOutputStream(frameFile).use { output ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()

        val metadataJson = EyeSessionStore.metadataToJsonLine(
            RecordedFrameMetadata(
                frameId = frame.frameId,
                timestampNs = frame.timestampNs,
                fileName = fileName,
                quality = observation.quality,
                confidence = observation.pupil?.confidence,
            ),
        )
        metadataWriter.append(metadataJson)
        metadataWriter.newLine()
        metadataWriter.flush()
    }

    /**
     * Summary: Closes the session writer.
     * @param none No parameters.
     * @return Unit.
     */
    fun close() {
        metadataWriter.flush()
        metadataWriter.close()
    }
}
