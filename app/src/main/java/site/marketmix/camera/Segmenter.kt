package site.marketmix.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Selfie segmentation through ML Kit, which carries its own copy of the model
 * and the custom ops that model needs. The same model loaded on the stock
 * TFLite runtime failed with "Encountered unresolved custom op:
 * Convolution2DTransposeBias", because that op ships with MediaPipe, not
 * TFLite.
 *
 * The mask is smoothed across frames so the blur edge does not flicker.
 */
class Segmenter {

    private val detector = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .build()
    )

    private val prevMask = FloatArray(MASK_SIZE * MASK_SIZE)

    /**
     * Fills [dest] (size [MASK_SIZE] squared) with a smoothed person mask in
     * 0..255, in the orientation of the frame it came from. [rotationDegrees]
     * is how far the frame has to turn for the subject to stand up, which is
     * the only way the model recognises one.
     */
    fun segment(bitmap: Bitmap, dest: ByteArray, rotationDegrees: Int) {
        val quarter = ((rotationDegrees % 360) + 360) % 360
        val upright = if (quarter == 0) bitmap else stoodUp(bitmap, quarter)
        val mask = try {
            Tasks.await(
                detector.process(InputImage.fromBitmap(upright, 0)),
                TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
        } finally {
            if (upright !== bitmap) upright.recycle()
        }
        val width = mask.width
        val height = mask.height
        if (width <= 0 || height <= 0) return
        val confidence = mask.buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (y in 0 until MASK_SIZE) {
            for (x in 0 until MASK_SIZE) {
                var sx = x
                var sy = y
                when (quarter) {
                    90 -> {
                        sx = MASK_SIZE - 1 - y
                        sy = x
                    }
                    180 -> {
                        sx = MASK_SIZE - 1 - x
                        sy = MASK_SIZE - 1 - y
                    }
                    270 -> {
                        sx = y
                        sy = MASK_SIZE - 1 - x
                    }
                }
                val column = (sx * width / MASK_SIZE).coerceIn(0, width - 1)
                val row = (sy * height / MASK_SIZE).coerceIn(0, height - 1)
                val value = confidence.get(row * width + column).coerceIn(0f, 1f)
                val i = y * MASK_SIZE + x
                prevMask[i] = prevMask[i] * 0.35f + value * 0.65f
                dest[i] = (prevMask[i] * 255f).toInt().toByte()
            }
        }
    }

    fun reset() {
        prevMask.fill(0f)
    }

    /** Turns the thumbnail clockwise so the subject stands up. */
    private fun stoodUp(src: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private companion object {
        const val MASK_SIZE = 256
        const val TIMEOUT_SECONDS = 5L
    }
}
