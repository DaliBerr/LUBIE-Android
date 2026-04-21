package Aquin.lubie.tracking.pipeline

import Aquin.lubie.tracking.model.EyeFrame

/**
 * Summary: Defines a pluggable eye frame source.
 * @param none No constructor parameters.
 * @return A frame source that emits grayscale eye frames.
 */
interface EyeFrameSource {

    /**
     * Summary: Returns a human-readable source identifier.
     * @param none No parameters.
     * @return Source label.
     */
    val sourceTag: String

    /**
     * Summary: Starts emitting frames to the provided consumer.
     * @param frameConsumer Callback that receives emitted frames.
     * @param onError Callback invoked when the source cannot continue.
     * @return Unit.
     */
    fun start(
        frameConsumer: (EyeFrame) -> Unit,
        onError: (String) -> Unit,
    )

    /**
     * Summary: Stops emitting frames and releases source resources.
     * @param none No parameters.
     * @return Unit.
     */
    fun stop()
}
