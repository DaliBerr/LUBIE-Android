package Aquin.lubie.ui

import Aquin.lubie.tracking.model.PupilDatum2D
import Aquin.lubie.tracking.model.RoiBounds
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_MANUAL_ROI_SIZE_PX = 20

/**
 * Summary: Draws pupil overlays, segmentation overlays, and manual ROI selection on top of the preview.
 * @param context View context.
 * @param attrs Optional XML attributes.
 * @return Custom overlay view.
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val detectionRoiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val manualRoiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 152, 0)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val draftRoiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val ellipsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.FILL
    }
    private val gazeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    private val gazeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    private var frameWidth: Int = 0
    private var frameHeight: Int = 0
    private var detectionRoi: RoiBounds? = null
    private var manualSegmentationRoi: RoiBounds? = null
    private var draftSegmentationRoi: RoiBounds? = null
    private var pupil: PupilDatum2D? = null
    private var segmentationOverlayBitmap: Bitmap? = null
    private var statusText: String = ""
    private var gazeUvX: Float? = null
    private var gazeUvY: Float? = null
    private var mirrorHorizontally: Boolean = false
    private var selectionEnabled: Boolean = false
    private var dragStartX: Int? = null
    private var dragStartY: Int? = null
    private var onManualRoiSelected: ((RoiBounds) -> Unit)? = null

    /**
     * Summary: Updates all overlay state and redraws the view.
     * @param frameWidth Source frame width.
     * @param frameHeight Source frame height.
     * @param detectionRoi Current pupil tracking ROI.
     * @param manualSegmentationRoi Current manual segmentation ROI.
     * @param pupil Current 2D pupil result.
     * @param segmentationOverlayBitmap Current ROI-scoped segmentation overlay bitmap.
     * @param statusText Status text shown on top of the preview.
     * @param mirrorHorizontally Whether X coordinates should be mirrored.
     * @return Unit.
     */
    fun updateOverlay(
        frameWidth: Int,
        frameHeight: Int,
        detectionRoi: RoiBounds?,
        manualSegmentationRoi: RoiBounds?,
        pupil: PupilDatum2D?,
        segmentationOverlayBitmap: Bitmap?,
        statusText: String,
        mirrorHorizontally: Boolean = false,
    ) {
        this.frameWidth = frameWidth
        this.frameHeight = frameHeight
        this.detectionRoi = detectionRoi
        this.manualSegmentationRoi = manualSegmentationRoi
        this.pupil = pupil
        this.segmentationOverlayBitmap = segmentationOverlayBitmap
        this.statusText = statusText
        this.mirrorHorizontally = mirrorHorizontally
        invalidate()
    }

    /**
     * Summary: Updates the simplified remote streaming overlay.
     * @param frameWidth Current source frame width.
     * @param frameHeight Current source frame height.
     * @param gazeUvX Normalized gaze X coordinate.
     * @param gazeUvY Normalized gaze Y coordinate.
     * @param statusText Status text rendered on top of the preview.
     * @return Unit.
     */
    fun updateRemoteOverlay(
        frameWidth: Int,
        frameHeight: Int,
        gazeUvX: Float?,
        gazeUvY: Float?,
        statusText: String,
    ) {
        this.frameWidth = frameWidth
        this.frameHeight = frameHeight
        detectionRoi = null
        manualSegmentationRoi = null
        draftSegmentationRoi = null
        pupil = null
        segmentationOverlayBitmap = null
        this.statusText = statusText
        this.gazeUvX = gazeUvX
        this.gazeUvY = gazeUvY
        mirrorHorizontally = false
        invalidate()
    }

    /**
     * Summary: Enables or disables drag-based ROI selection on top of the preview.
     * @param enabled Whether ROI selection should be active.
     * @return Unit.
     */
    fun setSelectionEnabled(enabled: Boolean) {
        selectionEnabled = enabled
        if (!enabled) {
            dragStartX = null
            dragStartY = null
            draftSegmentationRoi = null
        }
        invalidate()
    }

    /**
     * Summary: Returns whether drag-based ROI selection is currently active.
     * @param none No parameters.
     * @return True when touch events select a manual ROI.
     */
    fun isSelectionEnabled(): Boolean = selectionEnabled

    /**
     * Summary: Registers a callback for committed manual ROI selections.
     * @param listener ROI callback invoked when the drag gesture completes.
     * @return Unit.
     */
    fun setOnManualRoiSelectedListener(listener: ((RoiBounds) -> Unit)?) {
        onManualRoiSelected = listener
    }

    /**
     * Summary: Clears the overlay while keeping an optional status text.
     * @param statusText Optional status text shown on top of the view.
     * @return Unit.
     */
    fun clear(statusText: String = "") {
        frameWidth = 0
        frameHeight = 0
        detectionRoi = null
        manualSegmentationRoi = null
        draftSegmentationRoi = null
        pupil = null
        segmentationOverlayBitmap = null
        this.statusText = statusText
        gazeUvX = null
        gazeUvY = null
        mirrorHorizontally = false
        selectionEnabled = false
        dragStartX = null
        dragStartY = null
        invalidate()
    }

    /**
     * Summary: Handles manual ROI drag gestures when selection mode is enabled.
     * @param event Current touch event.
     * @return True when the overlay consumed the touch event.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!selectionEnabled || frameWidth <= 0 || frameHeight <= 0) {
            return super.onTouchEvent(event)
        }

        val contentRect = contentRect()
        if (!contentRect.contains(event.x, event.y) && event.actionMasked == MotionEvent.ACTION_DOWN) {
            return false
        }

        val frameX = viewToFrameX(event.x, contentRect)
        val frameY = viewToFrameY(event.y, contentRect)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragStartX = frameX
                dragStartY = frameY
                draftSegmentationRoi = RoiBounds(frameX, frameY, frameX, frameY)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val startX = dragStartX ?: return true
                val startY = dragStartY ?: return true
                draftSegmentationRoi = buildRoi(startX, startY, frameX, frameY)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val startX = dragStartX ?: return true
                val startY = dragStartY ?: return true
                val finalRoi = buildRoi(startX, startY, frameX, frameY)
                dragStartX = null
                dragStartY = null
                draftSegmentationRoi = null
                performClick()
                if (finalRoi.width() >= MIN_MANUAL_ROI_SIZE_PX && finalRoi.height() >= MIN_MANUAL_ROI_SIZE_PX) {
                    onManualRoiSelected?.invoke(finalRoi)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragStartX = null
                dragStartY = null
                draftSegmentationRoi = null
                invalidate()
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    /**
     * Summary: Supports accessibility click handling for the custom overlay.
     * @param none No parameters.
     * @return True after delegating to the base implementation.
     */
    override fun performClick(): Boolean {
        return super.performClick()
    }

    /**
     * Summary: Draws the current segmentation ROI, pupil ellipse, and status text.
     * @param canvas Drawing canvas.
     * @return Unit.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val contentRect = if (frameWidth > 0 && frameHeight > 0) contentRect() else null

        if (contentRect != null) {
            drawSegmentationOverlay(canvas, contentRect)

            detectionRoi?.let { roi ->
                canvas.drawRect(roiToRectF(roi, contentRect), detectionRoiPaint)
            }

            manualSegmentationRoi?.let { roi ->
                canvas.drawRect(roiToRectF(roi, contentRect), manualRoiPaint)
            }

            draftSegmentationRoi?.let { roi ->
                canvas.drawRect(roiToRectF(roi, contentRect), draftRoiPaint)
            }

            pupil?.let { currentPupil ->
                val centerX = mapX(currentPupil.ellipse.centerX.toFloat(), contentRect)
                val centerY = mapY(currentPupil.ellipse.centerY.toFloat(), contentRect)
                val halfMajor = (currentPupil.ellipse.axisMajor.toFloat() / frameWidth) * contentRect.width() / 2f
                val halfMinor = (currentPupil.ellipse.axisMinor.toFloat() / frameHeight) * contentRect.height() / 2f
                val ovalRect = RectF(-halfMajor, -halfMinor, halfMajor, halfMinor)
                canvas.save()
                canvas.translate(centerX, centerY)
                canvas.rotate(currentPupil.ellipse.angleDeg.toFloat())
                canvas.drawOval(ovalRect, ellipsePaint)
                canvas.restore()
                canvas.drawCircle(centerX, centerY, 6f, centerPaint)
            }

            if (gazeUvX != null && gazeUvY != null) {
                val gazeCenterX = contentRect.left + (gazeUvX!!.coerceIn(0f, 1f) * contentRect.width())
                val gazeCenterY = contentRect.top + (gazeUvY!!.coerceIn(0f, 1f) * contentRect.height())
                canvas.drawCircle(gazeCenterX, gazeCenterY, 18f, gazeStrokePaint)
                canvas.drawCircle(gazeCenterX, gazeCenterY, 12f, gazeFillPaint)
            }
        }

        if (statusText.isNotEmpty()) {
            val lines = statusText.lines().filter { it.isNotBlank() }
            val lineHeight = textPaint.fontSpacing
            val totalHeight = lineHeight * lines.size
            var y = height - 20f - totalHeight + lineHeight
            lines.forEach { line ->
                canvas.drawText(line, 16f, y, textPaint)
                y += lineHeight
            }
        }
    }

    /**
     * Summary: Calculates the drawable content rect for fill-center scaling.
     * @param none No parameters.
     * @return Rect where the image content is displayed.
     */
    private fun contentRect(): RectF {
        val scale = max(width / frameWidth.toFloat(), height / frameHeight.toFloat())
        val drawWidth = frameWidth * scale
        val drawHeight = frameHeight * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        return RectF(left, top, left + drawWidth, top + drawHeight)
    }

    private fun drawSegmentationOverlay(
        canvas: Canvas,
        contentRect: RectF,
    ) {
        val roi = manualSegmentationRoi ?: return
        val bitmap = segmentationOverlayBitmap ?: return
        canvas.drawBitmap(bitmap, null, roiToRectF(roi, contentRect), bitmapPaint)
    }

    private fun roiToRectF(
        roi: RoiBounds,
        contentRect: RectF,
    ): RectF {
        return RectF(
            mapX(roi.minX.toFloat(), contentRect),
            mapY(roi.minY.toFloat(), contentRect),
            mapX((roi.maxX + 1).toFloat(), contentRect),
            mapY((roi.maxY + 1).toFloat(), contentRect),
        )
    }

    private fun buildRoi(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
    ): RoiBounds {
        return RoiBounds(
            minX = min(startX, endX),
            minY = min(startY, endY),
            maxX = max(startX, endX),
            maxY = max(startY, endY),
        ).clamp(frameWidth, frameHeight)
    }

    private fun viewToFrameX(
        x: Float,
        contentRect: RectF,
    ): Int {
        val normalized = ((x - contentRect.left) / contentRect.width()).coerceIn(0f, 1f)
        val frameNormalized = if (mirrorHorizontally) 1f - normalized else normalized
        return (frameNormalized * (frameWidth - 1)).roundToInt().coerceIn(0, frameWidth - 1)
    }

    private fun viewToFrameY(
        y: Float,
        contentRect: RectF,
    ): Int {
        val normalized = ((y - contentRect.top) / contentRect.height()).coerceIn(0f, 1f)
        return (normalized * (frameHeight - 1)).roundToInt().coerceIn(0, frameHeight - 1)
    }

    /**
     * Summary: Maps a frame X coordinate into the view content rect.
     * @param x Frame-space X coordinate.
     * @param contentRect Fill-center content rect.
     * @return View-space X coordinate.
     */
    private fun mapX(
        x: Float,
        contentRect: RectF,
    ): Float {
        val normalized = x / frameWidth
        val mapped = if (mirrorHorizontally) 1f - normalized else normalized
        return contentRect.left + (mapped * contentRect.width())
    }

    /**
     * Summary: Maps a frame Y coordinate into the view content rect.
     * @param y Frame-space Y coordinate.
     * @param contentRect Fill-center content rect.
     * @return View-space Y coordinate.
     */
    private fun mapY(
        y: Float,
        contentRect: RectF,
    ): Float {
        return contentRect.top + ((y / frameHeight) * contentRect.height())
    }
}
