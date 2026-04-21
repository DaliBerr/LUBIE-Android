package Aquin.lubie.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Summary: Draws timeline marker positions and highlights the marker nearest to the current timestamp.
 * @param context View context.
 * @param attrs Optional XML attributes.
 * @return Lightweight marker overlay view for the replay seek bar.
 */
class MarkerTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        strokeWidth = 5f
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f
    }

    private var markerTimestampsNs: List<Long> = emptyList()
    private var durationNs: Long = 0L
    private var currentTimestampNs: Long = 0L

    /**
     * Summary: Updates marker positions and the current timeline progress.
     * @param markerTimestampsNs Marker timestamps in nanoseconds.
     * @param durationNs Total session duration in nanoseconds.
     * @param currentTimestampNs Current playback timestamp in nanoseconds.
     * @return Unit.
     */
    fun updateTimeline(
        markerTimestampsNs: List<Long>,
        durationNs: Long,
        currentTimestampNs: Long,
    ) {
        this.markerTimestampsNs = markerTimestampsNs
        this.durationNs = durationNs.coerceAtLeast(0L)
        this.currentTimestampNs = currentTimestampNs.coerceAtLeast(0L)
        invalidate()
    }

    /**
     * Summary: Clears the current marker state.
     * @param none No parameters.
     * @return Unit.
     */
    fun clear() {
        markerTimestampsNs = emptyList()
        durationNs = 0L
        currentTimestampNs = 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val contentLeft = paddingLeft.toFloat()
        val contentRight = (width - paddingRight).toFloat()
        val contentWidth = (contentRight - contentLeft).coerceAtLeast(1f)
        val top = 4f
        val bottom = height - 4f

        val progressFraction = if (durationNs > 0L) {
            currentTimestampNs.toDouble() / durationNs.toDouble()
        } else {
            0.0
        }.coerceIn(0.0, 1.0)
        val progressX = contentLeft + (contentWidth * progressFraction.toFloat())
        canvas.drawLine(progressX, top, progressX, bottom, progressPaint)

        markerTimestampsNs.forEach { markerTimestamp ->
            val markerFraction = if (durationNs > 0L) {
                markerTimestamp.toDouble() / durationNs.toDouble()
            } else {
                0.0
            }.coerceIn(0.0, 1.0)
            val markerX = contentLeft + (contentWidth * markerFraction.toFloat())
            canvas.drawLine(markerX, top, markerX, bottom, markerPaint)
        }
    }
}
