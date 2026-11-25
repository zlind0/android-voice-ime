package com.litertime.android.handwriting

import android.content.res.AssetManager
import android.graphics.Bitmap
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * Reusable offline Chinese handwriting recognizer powered by `Ismantic/Handwritten` (NCNN INT8).
 * All native handle access is synchronized so calls from the background executor and `close()` are safe.
 */
class HCCRRecognizer(
    assets: AssetManager,
    paramName: String = DEFAULT_PARAM_ASSET,
    binName: String = DEFAULT_BIN_ASSET,
    charsetName: String = DEFAULT_CHARSET_ASSET,
) : AutoCloseable {

    data class Candidate(
        val text: String,
        val probability: Float,
    )

    private val lock = Any()
    private var nativeHandle: Long
    private val idxToChar: Array<String?>

    init {
        loadNativeLibrary()
        nativeHandle = nativeInit(assets, paramName, binName)
        if (nativeHandle == 0L) {
            throw IOException("手写模型加载失败: $paramName")
        }
        idxToChar = loadCharset(assets, charsetName)
    }

    fun recognizeBitmap(bitmap: Bitmap, topK: Int = DEFAULT_TOP_K): List<Candidate> {
        if (topK <= 0) return emptyList()
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return emptyList()

        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        val gray = ByteArray(w * h)
        for (i in argb.indices) {
            val c = argb[i]
            val r = (c ushr 16) and 0xff
            val g = (c ushr 8) and 0xff
            val b = c and 0xff
            gray[i] = ((299 * r + 587 * g + 114 * b) / 1000).toByte()
        }

        val input = FloatArray(CANVAS_INPUT_SIZE * CANVAS_INPUT_SIZE)
        synchronized(lock) {
            if (nativeHandle == 0L) return emptyList()
            val hasInk = nativePreprocess(gray, w, h, input)
            if (!hasInk) return emptyList()
            val indices = IntArray(topK)
            val probabilities = FloatArray(topK)
            val count = nativePredict(nativeHandle, input, indices, probabilities, topK)
            if (count <= 0) return emptyList()
            return List(count) { i ->
                val idx = indices[i]
                val text = if (idx in idxToChar.indices) idxToChar[idx].orEmpty() else "?"
                Candidate(text = text, probability = probabilities[i])
            }.filter { it.text.isNotEmpty() }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (nativeHandle != 0L) {
                nativeRelease(nativeHandle)
                nativeHandle = 0L
            }
        }
    }

    companion object {
        const val DEFAULT_PARAM_ASSET = "handwriting/model.ncnn.param"
        const val DEFAULT_BIN_ASSET = "handwriting/model.ncnn.bin"
        const val DEFAULT_CHARSET_ASSET = "handwriting/charset.json"
        const val DEFAULT_TOP_K = 10
        private const val CANVAS_INPUT_SIZE = 64

        @Volatile
        private var nativeLibraryLoaded = false

        @Synchronized
        fun loadNativeLibrary() {
            if (!nativeLibraryLoaded) {
                System.loadLibrary("hccr_jni")
                nativeLibraryLoaded = true
            }
        }

        private fun loadCharset(assets: AssetManager, name: String): Array<String?> {
            try {
                assets.open(name).use { stream ->
                    val root = JSONObject(String(stream.readBytes(), StandardCharsets.UTF_8))
                    val chars = arrayOfNulls<String>(root.getInt("num_classes"))
                    val map = root.getJSONObject("char_to_idx")
                    val it = map.keys()
                    while (it.hasNext()) {
                        val text = it.next()
                        val index = map.getInt(text)
                        if (index in chars.indices) {
                            chars[index] = text
                        }
                    }
                    return chars
                }
            } catch (e: Exception) {
                throw IOException("手写字表加载失败: $name", e)
            }
        }

        @JvmStatic
        private external fun nativeInit(assets: AssetManager, param: String, bin: String): Long

        @JvmStatic
        private external fun nativePredict(
            handle: Long,
            input: FloatArray,
            indices: IntArray,
            probabilities: FloatArray,
            requested: Int,
        ): Int

        @JvmStatic
        private external fun nativeRelease(handle: Long)

        @JvmStatic
        private external fun nativePreprocess(
            gray: ByteArray,
            width: Int,
            height: Int,
            output: FloatArray,
        ): Boolean
    }
}
