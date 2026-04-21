package Aquin.lubie.tracking.pipeline

import Aquin.lubie.tracking.model.EyeFrame
import Aquin.lubie.tracking.model.RecordedFrameMetadata
import Aquin.lubie.tracking.model.RecordedSessionInfo
import android.graphics.BitmapFactory
import java.io.File

/**
 * Summary: Emits recorded eye frames from the latest private app session.
 * @param store Session store used to load the latest recording.
 * @param preprocessor Shared preprocessor used to decode grayscale frames.
 * @return Replay-capable recorded eye frame source.
 */
class RecordedEyeFrameSource(
    private val store: EyeSessionStore,
    private val preprocessor: EyePreprocessor,
) : EyeFrameSource {

    override var sourceTag: String = "replay_none"
        private set

    private var frameConsumer: ((EyeFrame) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var currentSession: RecordedSessionInfo? = null
    private var currentFrameIndex: Int = 0

    override fun start(
        frameConsumer: (EyeFrame) -> Unit,
        onError: (String) -> Unit,
    ) {
        this.frameConsumer = frameConsumer
        this.onError = onError
    }

    override fun stop() {
        frameConsumer = null
        onError = null
        currentSession = null
        currentFrameIndex = 0
        sourceTag = "replay_none"
    }

    /**
     * Summary: Loads the latest recorded session and resets the replay index.
     * @param none No parameters.
     * @return Latest recorded session descriptor or null when unavailable.
     */
    fun openLatestSession(): RecordedSessionInfo? {
        val session = store.loadLatestSession() ?: return null
        currentSession = session
        currentFrameIndex = 0
        sourceTag = "replay_${session.sessionId}"
        return session
    }

    /**
     * Summary: Returns the current replay session if loaded.
     * @param none No parameters.
     * @return Current replay session descriptor or null.
     */
    fun currentSession(): RecordedSessionInfo? = currentSession

    /**
     * Summary: Returns the current replay frame index.
     * @param none No parameters.
     * @return Zero-based replay frame index.
     */
    fun currentIndex(): Int = currentFrameIndex

    /**
     * Summary: Emits the current replay frame to the registered consumer.
     * @param none No parameters.
     * @return Emitted frame or null when unavailable.
     */
    fun emitCurrentFrame(): EyeFrame? {
        val session = currentSession ?: return null
        val metadata = session.frames.getOrNull(currentFrameIndex) ?: return null
        val frame = readFrame(session, metadata) ?: return null
        frameConsumer?.invoke(frame)
        return frame
    }

    /**
     * Summary: Moves to the next replay frame and emits it.
     * @param none No parameters.
     * @return Emitted frame or null when no next frame exists.
     */
    fun stepNext(): EyeFrame? {
        val session = currentSession ?: return null
        if (currentFrameIndex >= session.frames.lastIndex) return null
        currentFrameIndex += 1
        return emitCurrentFrame()
    }

    /**
     * Summary: Moves to the previous replay frame and emits it.
     * @param none No parameters.
     * @return Emitted frame or null when no previous frame exists.
     */
    fun stepPrevious(): EyeFrame? {
        if (currentFrameIndex <= 0) return null
        currentFrameIndex -= 1
        return emitCurrentFrame()
    }

    /**
     * Summary: Returns the intended delay to the next replay frame.
     * @param speed Current replay speed.
     * @return Delay in milliseconds before the next frame should be emitted.
     */
    fun delayToNextFrameMs(speed: Aquin.lubie.tracking.model.ReplaySpeed): Long {
        val session = currentSession ?: return 0L
        val currentMetadata = session.frames.getOrNull(currentFrameIndex) ?: return 0L
        val nextMetadata = session.frames.getOrNull(currentFrameIndex + 1) ?: return 0L
        val frameDeltaNs = (nextMetadata.timestampNs - currentMetadata.timestampNs).coerceAtLeast(0L)
        return (frameDeltaNs / 1_000_000.0 / speed.factor).toLong()
    }

    private fun readFrame(
        session: RecordedSessionInfo,
        metadata: RecordedFrameMetadata,
    ): EyeFrame? {
        return try {
            val frameFile = File(File(session.sessionDirPath, "frames"), metadata.fileName)
            val bitmap = BitmapFactory.decodeFile(frameFile.absolutePath) ?: return null
            val gray = preprocessor.toGray(bitmap)
            bitmap.recycle()
            EyeFrame(
                frameId = metadata.frameId,
                timestampNs = metadata.timestampNs,
                width = gray.cols(),
                height = gray.rows(),
                grayMat = gray,
                sourceTag = sourceTag,
            )
        } catch (_: Throwable) {
            onError?.invoke("Failed to load replay frame")
            null
        }
    }
}
