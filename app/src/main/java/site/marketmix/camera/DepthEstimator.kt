package site.marketmix.camera

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Monocular relative inverse depth (MiDaS v2.1 small). The phone exposes no
 * depth stream, so this is the only signal that separates what is in front of
 * the subject from what is behind it. Near = high value.
 */
class DepthEstimator(context: Context) {

    private val interpreter: Interpreter? = try {
        Interpreter(loadModel(context), Interpreter.Options().setNumThreads(4)).also {
            Log.d("MarketMixCamera", "depth model ready")
        }
    } catch (e: Exception) {
        Log.w("MarketMixCamera", "depth model failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private var failuresLogged = 0

    private val input: ByteBuffer = ByteBuffer
        .allocateDirect(1 * SIZE * SIZE * 3 * 4)
        .order(ByteOrder.nativeOrder())

    /** Fills [dest] with upright-orientation inverse depth, or returns false. */
    fun estimate(upright: Bitmap, dest: FloatArray): Boolean {
        val interp = interpreter ?: return false
        val scaled = if (upright.width == SIZE && upright.height == SIZE) upright
        else Bitmap.createScaledBitmap(upright, SIZE, SIZE, true)
        val pixels = IntArray(SIZE * SIZE)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== upright) scaled.recycle()

        input.rewind()
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            input.putFloat((r - MEAN[0]) / STD[0])
            input.putFloat((g - MEAN[1]) / STD[1])
            input.putFloat((b - MEAN[2]) / STD[2])
        }
        input.rewind()
        // The tensor is [1, 256, 256]; TFLite refuses a flattened [1, 65536].
        val out = Array(1) { Array(SIZE) { FloatArray(SIZE) } }
        return try {
            interp.run(input, out)
            for (y in 0 until SIZE) {
                System.arraycopy(out[0][y], 0, dest, y * SIZE, SIZE)
            }
            true
        } catch (e: Exception) {
            if (failuresLogged < 3) {
                failuresLogged++
                Log.w("MarketMixCamera", "depth run failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            false
        }
    }

    fun close() {
        interpreter?.close()
    }

    private fun loadModel(context: Context): MappedByteBuffer {
        context.assets.openFd(MODEL).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    private companion object {
        const val SIZE = 256
        const val MODEL = "midas_small_256_fp16.tflite"
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}