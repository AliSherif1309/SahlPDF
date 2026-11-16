package com.gitlab.mudlej.MjPdfReader.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Serialized by the scanner ViewModel. Source files are immutable. */
class ScanImageProcessor {
    companion object { private val available: Boolean by lazy { OpenCVLoader.initLocal() } }
    private fun initialize() { check(available) { "Image processor unavailable" } }

    fun decode(file: File, maxSide: Int): Bitmap {
        require(maxSide in 128..4096)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("Unsupported image")
        try {
            val matrix = Matrix()
            when (ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            }
            if (matrix.isIdentity) return bitmap
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { bitmap.recycle() }
        } catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }

    fun analyze(file: File): ScanAnalysis {
        initialize()
        val bitmap = decode(file, 1200)
        val rgba = Mat(); val gray = Mat(); val blurred = Mat(); val edges = Mat(); val threshold = Mat()
        val laplace = Mat(); val mean = MatOfDouble(); val deviation = MatOfDouble()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.Laplacian(gray, laplace, CvType.CV_64F)
            Core.meanStdDev(laplace, mean, deviation)
            val possibleBlur = deviation.toArray().firstOrNull()?.let { it * it < 25.0 } ?: false
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 45.0, 130.0)
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.threshold(blurred, threshold, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(threshold, threshold, Imgproc.MORPH_CLOSE, kernel)
            var best: List<ScanPoint>? = null
            var bestArea = 0.0
            for (mask in listOf(edges, threshold)) {
                val contours = ArrayList<MatOfPoint>()
                val hierarchy = Mat()
                try {
                    Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                    for (contour in contours.map { it to Imgproc.contourArea(it) }.sortedByDescending { it.second }.take(24).map { it.first }) {
                        val curve = MatOfPoint2f(*contour.toArray()); val approx = MatOfPoint2f()
                        try {
                            Imgproc.approxPolyDP(curve, approx, 0.02 * Imgproc.arcLength(curve, true), true)
                            if (approx.total() != 4L) continue
                            val points = approx.toArray()
                            val area = kotlin.math.abs(Imgproc.contourArea(approx))
                            val fraction = area / (bitmap.width.toDouble() * bitmap.height)
                            if (fraction !in 0.15..0.97 || area <= bestArea) continue
                            val ordered = runCatching {
                                ScanGeometry.ordered(points.map { ScanPoint(
                                    (it.x / (bitmap.width - 1).coerceAtLeast(1)).toFloat().coerceIn(0f, 1f),
                                    (it.y / (bitmap.height - 1).coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)) })
                            }.getOrNull() ?: continue
                            best = ordered; bestArea = area
                        } finally { curve.release(); approx.release() }
                    }
                } finally { contours.forEach { it.release() }; hierarchy.release() }
            }
            return ScanAnalysis(best ?: ScanGeometry.fullPage(), best != null, possibleBlur)
        } finally {
            bitmap.recycle()
            listOf(rgba, gray, blurred, edges, threshold, laplace, mean, deviation, kernel).forEach { it.release() }
        }
    }

    fun process(file: File, page: ScanPage, maxSide: Int = 3508): Bitmap {
        initialize()
        require(ScanGeometry.valid(page.corners))
        val bitmap = decode(file, if (maxSide <= 1600) maxOf(320, maxSide) else 4096)
        val source = Mat(); val destination = Mat(); val gray = Mat(); val filtered = Mat()
        var transform: Mat? = null
        val (width, height) = ScanGeometry.outputSize(page.corners, bitmap.width, bitmap.height, maxSide)
        val from = MatOfPoint2f(*page.corners.map {
            Point(it.x * (bitmap.width - 1).toDouble(), it.y * (bitmap.height - 1).toDouble())
        }.toTypedArray())
        val to = MatOfPoint2f(Point(0.0, 0.0), Point(width - 1.0, 0.0),
            Point(width - 1.0, height - 1.0), Point(0.0, height - 1.0))
        try {
            Utils.bitmapToMat(bitmap, source)
            bitmap.recycle()
            transform = Imgproc.getPerspectiveTransform(from, to)
            Imgproc.warpPerspective(source, destination, transform, Size(width.toDouble(), height.toDouble()),
                Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE, Scalar(255.0, 255.0, 255.0, 255.0))
            source.release()
            val result = if (page.filter == ScanFilter.NATURAL) destination else {
                Imgproc.cvtColor(destination, gray, Imgproc.COLOR_RGBA2GRAY)
                if (page.filter == ScanFilter.MONO) {
                    val block = minOf(41, minOf(width, height).let { if (it % 2 == 0) it - 1 else it })
                    require(block >= 3)
                    Imgproc.adaptiveThreshold(gray, filtered, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                        Imgproc.THRESH_BINARY, block, 12.0)
                    filtered
                } else gray
            }
            var output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                Utils.matToBitmap(result, output)
                if (page.rotation != 0) {
                    val rotated = Bitmap.createBitmap(output, 0, 0, width, height,
                        Matrix().apply { postRotate(page.rotation.toFloat()) }, true)
                    output.recycle()
                    output = rotated
                }
                return output
            } catch (failure: Throwable) { output.recycle(); throw failure }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            listOf(source, destination, gray, filtered, from, to).forEach { it.release() }
            transform?.release()
        }
    }
}
