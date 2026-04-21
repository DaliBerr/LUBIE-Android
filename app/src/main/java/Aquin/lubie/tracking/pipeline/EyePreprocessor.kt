package Aquin.lubie.tracking.pipeline

import Aquin.lubie.tracking.model.RoiBounds
import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc

/**
 * Summary: Converts source frames into detector-friendly OpenCV mats.
 * @param none No constructor parameters.
 * @return Stateless preprocessor helper.
 */
class EyePreprocessor {

    /**
     * Summary: Converts a bitmap frame into a grayscale mat.
     * @param bitmap Source bitmap.
     * @return Grayscale mat with one 8-bit channel.
     */
    fun toGray(bitmap: Bitmap): Mat {
        val rgba = Mat()
        val gray = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        return gray
    }

    /**
     * Summary: Converts a camera frame into a rotated grayscale mat.
     * @param imageProxy CameraX image frame.
     * @return Grayscale mat aligned to display orientation.
     */
    fun toGray(imageProxy: ImageProxy): Mat {
        val plane = imageProxy.planes.first()
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val source = plane.buffer.duplicate()
        source.rewind()

        val grayBytes = ByteArray(width * height)
        if (pixelStride == 1) {
            for (row in 0 until height) {
                source.position(row * rowStride)
                source.get(grayBytes, row * width, width)
            }
        } else {
            val rowBuffer = ByteArray(rowStride)
            for (row in 0 until height) {
                source.position(row * rowStride)
                source.get(rowBuffer, 0, rowStride)
                for (column in 0 until width) {
                    grayBytes[(row * width) + column] = rowBuffer[column * pixelStride]
                }
            }
        }

        val gray = Mat(height, width, CvType.CV_8UC1)
        gray.put(0, 0, grayBytes)
        return rotateGray(gray, imageProxy.imageInfo.rotationDegrees)
    }

    /**
     * Summary: Converts ROI bounds to an OpenCV rect.
     * @param roi ROI bounds in frame coordinates.
     * @return OpenCV rect that can be used with submat.
     */
    fun toRect(roi: RoiBounds): Rect {
        return Rect(roi.minX, roi.minY, roi.width(), roi.height())
    }

    /**
     * Summary: Clones the ROI pixels into an independent grayscale mat.
     * @param gray Full-frame grayscale image.
     * @param roi ROI bounds in frame coordinates.
     * @return Cloned ROI grayscale image.
     */
    fun cloneRoi(gray: Mat, roi: RoiBounds): Mat {
        val safeRoi = roi.clamp(gray.cols(), gray.rows())
        return gray.submat(toRect(safeRoi)).clone()
    }

    /**
     * Summary: Normalizes the median blur kernel size to a valid odd value.
     * @param kernelSize Requested kernel size.
     * @return Valid odd kernel size or 1 when blurring is disabled.
     */
    fun normalizeMedianBlurKernel(kernelSize: Int): Int {
        val safeKernel = kernelSize.coerceAtLeast(1)
        return if (safeKernel % 2 == 0) safeKernel + 1 else safeKernel
    }

    /**
     * Summary: Converts a grayscale mat into a preview bitmap.
     * @param gray Source grayscale mat.
     * @return ARGB bitmap for UI or PNG encoding.
     */
    fun toBitmap(gray: Mat): Bitmap {
        val rgba = Mat()
        val bitmap = Bitmap.createBitmap(gray.cols(), gray.rows(), Bitmap.Config.ARGB_8888)
        Imgproc.cvtColor(gray, rgba, Imgproc.COLOR_GRAY2RGBA)
        Utils.matToBitmap(rgba, bitmap)
        rgba.release()
        return bitmap
    }

    /**
     * Summary: Rotates a grayscale mat into the display orientation.
     * @param gray Source grayscale mat.
     * @param rotationDegrees Rotation reported by CameraX.
     * @return Rotated grayscale mat.
     */
    private fun rotateGray(gray: Mat, rotationDegrees: Int): Mat {
        return when (rotationDegrees) {
            90 -> {
                val rotated = Mat()
                Core.rotate(gray, rotated, Core.ROTATE_90_CLOCKWISE)
                gray.release()
                rotated
            }

            180 -> {
                val rotated = Mat()
                Core.rotate(gray, rotated, Core.ROTATE_180)
                gray.release()
                rotated
            }

            270 -> {
                val rotated = Mat()
                Core.rotate(gray, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
                gray.release()
                rotated
            }

            else -> gray
        }
    }
}
