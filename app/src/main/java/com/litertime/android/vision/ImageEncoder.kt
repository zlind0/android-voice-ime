package com.litertime.android.vision

import android.graphics.Bitmap
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

object ImageEncoder {
    /**
     * Downscales [bitmap] so its longest side is at most [maxEdge] pixels.
     */
    fun downscaleBitmap(bitmap: Bitmap, maxEdge: Int = 896): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val longest = max(width, height)
        if (longest <= maxEdge || longest <= 0) return bitmap
        val scale = maxEdge.toFloat() / longest.toFloat()
        val targetW = (width * scale).roundToInt().coerceAtLeast(1)
        val targetH = (height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    /**
     * Encodes [bitmap] into an uncompressed 32-bit BGRA top-down TGA byte array with 2-byte
     * alignment padding, matching the format expected by LiteRT-LM `Content.ImageBytes`.
     */
    fun encodeTga(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        val isHardware =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE
        if (isHardware) {
            val software = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) {
                "Failed to copy hardware bitmap"
            }
            try {
                software.getPixels(pixels, 0, width, 0, 0, width, height)
            } finally {
                software.recycle()
            }
        } else {
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        }

        val tgaData = ByteArray(20 + width * height * 4)
        val buffer = ByteBuffer.wrap(tgaData).order(ByteOrder.LITTLE_ENDIAN)

        tgaData[0] = 2 // ID Length = 2 alignment bytes
        tgaData[2] = 2 // Uncompressed true-color
        tgaData[12] = (width and 0xFF).toByte()
        tgaData[13] = ((width ushr 8) and 0xFF).toByte()
        tgaData[14] = (height and 0xFF).toByte()
        tgaData[15] = ((height ushr 8) and 0xFF).toByte()
        tgaData[16] = 32
        tgaData[17] = 0x28 // Top-down, 8 alpha bits
        tgaData[18] = 0
        tgaData[19] = 0

        buffer.position(20)
        buffer.asIntBuffer().put(pixels)
        return tgaData
    }
}
