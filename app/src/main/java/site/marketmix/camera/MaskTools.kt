package site.marketmix.camera

import android.graphics.Bitmap

object MaskTools {

    fun toBitmap(mask: ByteArray, size: Int): Bitmap {
        val pixels = IntArray(size * size)
        val grown = dilate(mask, size)
        for (i in pixels.indices) {
            val v = grown[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /**
     * Two separable max passes, so the subject grows by two mask texels. The
     * model's outline falls just inside the person, which otherwise blurs the
     * nearest edge of a shoulder or arm and looks wrong.
     */
    private fun dilate(mask: ByteArray, size: Int): ByteArray {
        val a = IntArray(size * size) { mask[it].toInt() and 0xFF }
        val b = IntArray(size * size)
        repeat(2) {
            for (y in 0 until size) {
                val row = y * size
                for (x in 0 until size) {
                    val here = a[row + x]
                    val left = if (x > 0) a[row + x - 1] else here
                    val right = if (x < size - 1) a[row + x + 1] else here
                    b[row + x] = maxOf(here, maxOf(left, right))
                }
            }
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val here = b[y * size + x]
                    val up = if (y > 0) b[(y - 1) * size + x] else here
                    val down = if (y < size - 1) b[(y + 1) * size + x] else here
                    a[y * size + x] = maxOf(here, maxOf(up, down))
                }
            }
        }
        return ByteArray(size * size) { a[it].toByte() }
    }
}
