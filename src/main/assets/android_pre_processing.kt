import android.graphics.Bitmap
import android.graphics.Color
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.round

/**
 * Preprocessing logic converted for Android.
 * This class provides two ways to implement the logic:
 * 1. Using OpenCV (Most accurate, direct translation)
 * 2. Using Native Android Bitmaps (No library dependency)
 */
object ImagePreprocessor {

    /**
     * OPENCV VERSION (Recommended if OpenCV is already in your project)
     * Requires: implementation 'org.apache.commons:commons-math3:3.6.1' or similar for some ops,
     * but here we use standard OpenCV Mat operations.
     */
    fun preprocessWithOpenCV(inputBitmap: Bitmap): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(inputBitmap, src)

        // 1. Resize to 256x256 using Cubic Interpolation
        val resized = Mat()
        Imgproc.resize(src, resized, Size(256.0, 256.0), 0.0, 0.0, Imgproc.INTER_CUBIC)
        
        // 2. Convert to BGR (OpenCV default is usually BGR, but Bitmap is RGBA)
        val bgr = Mat()
        Imgproc.cvtColor(resized, bgr, Imgproc.COLOR_RGBA2BGR)
        
        // 3. Split channels and calculate means
        val channels = mutableListOf<Mat>()
        Core.split(bgr, channels) // Order: B, G, R
        
        val bMean = Core.mean(channels[0]).`val`[0]
        val gMean = Core.mean(channels[1]).`val`[0]
        val rMean = Core.mean(channels[2]).`val`[0]
        
        val scaleB = if (bMean != 0.0) 128.0 / bMean else 1.0
        val scaleG = if (gMean != 0.0) 128.0 / gMean else 1.0
        val scaleR = if (rMean != 0.0) 128.0 / rMean else 1.0
        
        // 4. Scale, Clip and Round
        for (i in 0..2) {
            val scale = when(i) {
                0 -> scaleB
                1 -> scaleG
                else -> scaleR
            }
            Core.multiply(channels[i], Scalar(scale), channels[i])
            // Clipping is handled implicitly by convertTo or explicitly:
            Core.min(channels[i], Scalar(255.0), channels[i])
            Core.max(channels[i], Scalar(0.0), channels[i])
        }
        
        // 5. Merge back
        val merged = Mat()
        Core.merge(channels, merged)
        
        // 6. Convert to RGB for Android Bitmap
        val rgb = Mat()
        Imgproc.cvtColor(merged, rgb, Imgproc.COLOR_BGR2RGBA)
        
        val outputBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgb, outputBitmap)
        
        return outputBitmap
    }

    /**
     * NATIVE ANDROID VERSION (No OpenCV dependency)
     * Performs the same scaling and white-balancing logic using Bitmap pixels.
     */
    fun preprocessNative(inputBitmap: Bitmap): Bitmap {
        // 1. Resize
        val scaledBitmap = Bitmap.createScaledBitmap(inputBitmap, 256, 256, true)
        val width = scaledBitmap.width
        val height = scaledBitmap.height
        val pixels = IntArray(width * height)
        scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 2. Calculate means
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        
        for (pixel in pixels) {
            sumR += Color.red(pixel)
            sumG += Color.green(pixel)
            sumB += Color.blue(pixel)
        }
        
        val count = (width * height).toDouble()
        val meanR = sumR / count
        val meanG = sumG / count
        val meanB = sumB / count
        
        val scaleR = if (meanR != 0.0) 128.0 / meanR else 1.0
        val scaleG = if (meanG != 0.0) 128.0 / meanG else 1.0
        val scaleB = if (meanB != 0.0) 128.0 / meanB else 1.0

        // 3. Apply scaling and clipping
        val outPixels = IntArray(pixels.size)
        for (i in pixels.indices) {
            val r = (Color.red(pixels[i]) * scaleR).coerceIn(0.0, 255.0)
            val g = (Color.green(pixels[i]) * scaleG).coerceIn(0.0, 255.0)
            val b = (Color.blue(pixels[i]) * scaleB).coerceIn(0.0, 255.0)
            
            // Reassemble with rounding (mimicking Python's uint8 cast)
            outPixels[i] = Color.rgb(r.toInt(), g.toInt(), b.toInt())
        }

        val outputBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        outputBitmap.setPixels(outPixels, 0, width, 0, 0, width, height)
        
        return outputBitmap
    }
}
