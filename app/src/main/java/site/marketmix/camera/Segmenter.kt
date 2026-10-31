package site.marketmix.camera

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Selfie segmentation with the bundled MediaPipe selfie segmenter model.
 * The model takes a 256x256 RGB float image in [0,1] and returns a
 * single-channel person confidence map.
 */
class Segmenter(context: Context) {

    private val interpreter: Interpreter
    private val inW: Int
    private val inH: Int
    private val outW: Int
    private val outH: Int
    private val inputBuffer: ByteBuffer
    private val outputBuffer: ByteBuffer
    private var pixels = IntArray(0)
    private val prevMask: FloatArray

    init {
        val options = Interpreter.Options().apply { setNumThreads(2) }
        interpreter = Interpreter(loadModel(context), options)
        val inShape = interpreter.getInputTensor(0).shape()
        val outShape = interpreter.getOutputTensor(0).shape()
        inH = inShape[1]
        inW = inShape[2]
        outH = outShape[1]
        outW = outShape[2]
        inputBuffer = ByteBuffer.allocateDirect(inW * inH * 3 * 4).order(ByteOrder.nativeOrder())
        outputBuffer = ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.nativeOrder())
        prevMask = FloatArray(outW * outH)
        pixels = IntArray(inW * inH)
    }

    /** Fills [dest] (size outW*outH) with a smoothed person mask in 0..255. */
    fun segment(bitmap: Bitmap, dest: ByteArray) {
        val scaled = Bitmap.createScaledBitmap(bitmap, inW, inH, true)
        scaled.getPixels(pixels, 0, inW, 0, 0, inW, inH)
        if (scaled !== bitmap) scaled.recycle()

        inputBuffer.rewind()
        val fb = inputBuffer.asFloatBuffer()
        var i = 0
        for (p in pixels) {
            fb.put(((p shr 16) and 0xFF) / 255f)
            fb.put(((p shr 8) and 0xFF) / 255f)
            fb.put((p and 0xFF) / 255f)
            i++
        }
        inputBuffer.rewind()
        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)

        outputBuffer.rewind()
        val out = outputBuffer.asFloatBuffer()
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        val n = outW * outH
        for (k in 0 until n) {
            val v = out.get(k)
            if (v < min) min = v
            if (v > max) max = v
        }
        val logits = min < -0.01f || max > 1.01f
        for (k in 0 until n) {
            var v = out.get(k)
            if (logits) v = 1f / (1f + Math.exp((-v).toDouble()).toFloat())
            v = v.coerceIn(0f, 1f)
            prevMask[k] = prevMask[k] * 0.35f + v * 0.65f
            dest[k] = (prevMask[k] * 255f).toInt().toByte()
        }
    }

    fun reset() {
        prevMask.fill(0f)
    }

    private fun loadModel(context: Context): MappedByteBuffer {
        val afd = context.assets.openFd("selfie_segmenter.tflite")
        FileInputStream(afd.fileDescriptor).use { stream ->
            return stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }
}
