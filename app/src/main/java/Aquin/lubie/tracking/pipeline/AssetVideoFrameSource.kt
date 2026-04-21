package Aquin.lubie.tracking.pipeline

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Summary: Represents one decoded sample frame.
 * @param index Frame index.
 * @param timestampMs Frame timestamp in milliseconds.
 * @param bitmap Decoded bitmap.
 * @return Immutable sample frame.
 */
data class SampleFrame(
    val index: Int,
    val timestampMs: Long,
    val bitmap: Bitmap,
)

/**
 * Summary: Decodes frames from an asset-backed sample video.
 * @param assetManager Asset manager used to open the video.
 * @param assetPath Relative asset path.
 * @param targetFps Desired stepping rate.
 * @return Frame source for offline debugging.
 */
class AssetVideoFrameSource(
    assetManager: AssetManager,
    assetPath: String,
    targetFps: Int = 15,
) : AutoCloseable {

    private val retriever = MediaMetadataRetriever()
    private val frameStepMs = max(1L, (1000.0 / targetFps).roundToLong())

    val durationMs: Long
    val frameCount: Int

    init {
        val descriptor = assetManager.openFd(assetPath)
        retriever.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
        descriptor.close()

        durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?: 0L
        frameCount = if (durationMs <= 0L) {
            1
        } else {
            ((durationMs / frameStepMs) + 1L).toInt()
        }
    }

    /**
     * Summary: Returns a decoded frame for the requested index.
     * @param index Requested frame index.
     * @return Decoded sample frame or null if decoding fails.
     */
    fun getFrame(index: Int): SampleFrame? {
        val safeIndex = index.coerceIn(0, frameCount - 1)
        val timestampMs = (safeIndex * frameStepMs).coerceAtMost(durationMs)
        val bitmap = retriever.getFrameAtTime(timestampMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: retriever.getFrameAtTime(timestampMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: return null
        return SampleFrame(safeIndex, timestampMs, bitmap)
    }

    /**
     * Summary: Releases the underlying retriever.
     * @param none No parameters.
     * @return Unit.
     */
    override fun close() {
        retriever.release()
    }
}
