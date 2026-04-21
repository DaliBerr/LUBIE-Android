package Aquin.lubie.remote

import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.abs

/**
 * Summary: Loads and steps through the latest recorded remote session.
 * @param store Session store used to load replay data.
 * @return Replay-capable remote frame source.
 */
class RemoteReplaySource(
    private val store: RemoteSessionStore,
) {
    private var currentSession: RemoteSessionInfo? = null
    private var currentFrameIndex: Int = 0

    /**
     * Summary: Loads the newest recorded session and resets the replay index.
     * @param none No parameters.
     * @return Loaded session descriptor or null when unavailable.
     */
    fun openLatestSession(): RemoteSessionInfo? {
        val session = store.loadLatestSession() ?: return null
        currentSession = session
        currentFrameIndex = 0
        return session
    }

    /**
     * Summary: Clears the current replay session.
     * @param none No parameters.
     * @return Unit.
     */
    fun clear() {
        currentSession = null
        currentFrameIndex = 0
    }

    /**
     * Summary: Returns the loaded replay session.
     * @param none No parameters.
     * @return Current replay session or null.
     */
    fun currentSession(): RemoteSessionInfo? = currentSession

    /**
     * Summary: Returns the current replay index.
     * @param none No parameters.
     * @return Zero-based replay index.
     */
    fun currentIndex(): Int = currentFrameIndex

    /**
     * Summary: Returns the current frame metadata when available.
     * @param none No parameters.
     * @return Current frame metadata or null.
     */
    fun currentFrameMetadata(): RemoteRecordedFrameMetadata? {
        return currentSession?.frames?.getOrNull(currentFrameIndex)
    }

    /**
     * Summary: Decodes the current replay frame bitmap.
     * @param none No parameters.
     * @return Decoded replay frame or null when unavailable.
     */
    fun emitCurrentFrame(): RemoteReplayFrame? {
        val session = currentSession ?: return null
        val metadata = session.frames.getOrNull(currentFrameIndex) ?: return null
        val frameFile = File(File(session.sessionDirPath, "frames"), metadata.fileName)
        val bitmap = BitmapFactory.decodeFile(frameFile.absolutePath) ?: return null
        return RemoteReplayFrame(bitmap = bitmap, metadata = metadata)
    }

    /**
     * Summary: Steps to the next replay frame.
     * @param none No parameters.
     * @return Decoded replay frame or null when the session ended.
     */
    fun stepNext(): RemoteReplayFrame? {
        val session = currentSession ?: return null
        if (currentFrameIndex >= session.frames.lastIndex) return null
        currentFrameIndex += 1
        return emitCurrentFrame()
    }

    /**
     * Summary: Steps to the previous replay frame.
     * @param none No parameters.
     * @return Decoded replay frame or null when already at the start.
     */
    fun stepPrevious(): RemoteReplayFrame? {
        if (currentFrameIndex <= 0) return null
        currentFrameIndex -= 1
        return emitCurrentFrame()
    }

    /**
     * Summary: Seeks to the replay frame nearest to the given progress fraction.
     * @param progress Current seek bar progress.
     * @param max Seek bar max.
     * @return Decoded replay frame or null when unavailable.
     */
    fun seekToProgress(
        progress: Int,
        max: Int,
    ): RemoteReplayFrame? {
        val session = currentSession ?: return null
        if (session.frames.isEmpty()) return null
        if (max <= 0 || session.durationNs() <= 0L) {
            currentFrameIndex = 0
            return emitCurrentFrame()
        }

        currentFrameIndex = nearestFrameIndexForProgress(
            timestampsNs = session.frames.map { it.timestampNs },
            progress = progress,
            max = max,
        )
        return emitCurrentFrame()
    }

    /**
     * Summary: Returns the intended delay before the next replay frame.
     * @param speed Current replay speed.
     * @return Delay in milliseconds.
     */
    fun delayToNextFrameMs(speed: Aquin.lubie.tracking.model.ReplaySpeed): Long {
        val session = currentSession ?: return 0L
        val currentMetadata = session.frames.getOrNull(currentFrameIndex) ?: return 0L
        val nextMetadata = session.frames.getOrNull(currentFrameIndex + 1) ?: return 0L
        return frameDelayMs(
            currentTimestampNs = currentMetadata.timestampNs,
            nextTimestampNs = nextMetadata.timestampNs,
            speed = speed,
        )
    }

    companion object {
        /**
         * Summary: Computes the replay delay between two frame timestamps.
         * @param currentTimestampNs Current frame timestamp in nanoseconds.
         * @param nextTimestampNs Next frame timestamp in nanoseconds.
         * @param speed Current replay speed.
         * @return Delay in milliseconds.
         */
        internal fun frameDelayMs(
            currentTimestampNs: Long,
            nextTimestampNs: Long,
            speed: Aquin.lubie.tracking.model.ReplaySpeed,
        ): Long {
            val deltaNs = (nextTimestampNs - currentTimestampNs).coerceAtLeast(0L)
            return (deltaNs / 1_000_000.0 / speed.factor).toLong().coerceAtLeast(1L)
        }

        /**
         * Summary: Finds the frame index nearest to the requested replay progress.
         * @param timestampsNs Ordered frame timestamps.
         * @param progress Current seek bar progress.
         * @param max Seek bar max.
         * @return Nearest frame index.
         */
        internal fun nearestFrameIndexForProgress(
            timestampsNs: List<Long>,
            progress: Int,
            max: Int,
        ): Int {
            if (timestampsNs.isEmpty()) return 0
            if (max <= 0 || timestampsNs.size == 1) return 0

            val startTimestamp = timestampsNs.first()
            val durationNs = (timestampsNs.last() - startTimestamp).coerceAtLeast(0L)
            if (durationNs <= 0L) return 0

            val fraction = progress.toDouble() / max.toDouble()
            val targetTimestampNs = startTimestamp + (durationNs * fraction).toLong()
            return timestampsNs.indices.minByOrNull { index ->
                abs(timestampsNs[index] - targetTimestampNs)
            } ?: 0
        }
    }
}
