package site.marketmix.camera

import android.graphics.Bitmap

object MaskTools {

    fun toBitmap(mask: ByteArray, size: Int): Bitmap {
        val pixels = IntArray(size * size)
        for (i in pixels.indices) {
            val v = mask[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
