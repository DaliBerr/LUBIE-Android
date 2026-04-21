package Aquin.lubie.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Summary: Measures itself to a fixed 16:9 aspect ratio based on the available width.
 * @param context View context.
 * @param attrs Optional XML attributes.
 * @return FrameLayout sized for full 16:9 video presentation.
 */
class SixteenNineFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /**
     * Summary: Forces the layout to keep a 16:9 ratio while respecting the parent width.
     * @param widthMeasureSpec Width measure spec from the parent.
     * @param heightMeasureSpec Height measure spec from the parent.
     * @return Unit.
     */
    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val targetHeight = ((width.toFloat() * 9f) / 16f).toInt()
        val exactHeightSpec = MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, exactHeightSpec)
        setMeasuredDimension(width, targetHeight)
    }
}
