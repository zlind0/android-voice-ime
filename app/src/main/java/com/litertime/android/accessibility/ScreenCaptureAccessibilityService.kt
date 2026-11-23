package com.litertime.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.litertime.android.vision.ImageEncoder
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "ScreenCaptureA11y"

class ScreenCaptureAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        _isSystemEnabled.value = true
        Log.i(TAG, "ScreenCaptureAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        _isConnected.value = false
        super.onDestroy()
    }

    suspend fun captureToFile(outputFile: File): File? = suspendCancellableCoroutine { cont ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    try {
                        val colorSpace = screenshot.colorSpace
                        val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        if (wrapped == null) {
                            if (cont.isActive) cont.resume(null)
                            return
                        }
                        val software = try {
                            wrapped.copy(Bitmap.Config.ARGB_8888, false)
                        } finally {
                            wrapped.recycle()
                        }
                        if (software == null) {
                            if (cont.isActive) cont.resume(null)
                            return
                        }
                        try {
                            val scaled = ImageEncoder.downscaleBitmap(software, maxEdge = 896)
                            try {
                                outputFile.parentFile?.mkdirs()
                                FileOutputStream(outputFile).use { out ->
                                    scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
                                }
                                if (cont.isActive) cont.resume(outputFile)
                            } finally {
                                if (scaled !== software) scaled.recycle()
                            }
                        } finally {
                            software.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save screenshot", e)
                        if (cont.isActive) cont.resume(null)
                    } finally {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            runCatching { hardwareBuffer.close() }
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "takeScreenshot failed with errorCode=$errorCode")
                    if (cont.isActive) cont.resume(null)
                }
            },
        )
    }

    companion object {
        @Volatile
        private var instance: ScreenCaptureAccessibilityService? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _isSystemEnabled = MutableStateFlow(false)
        val isSystemEnabled: StateFlow<Boolean> = _isSystemEnabled.asStateFlow()

        fun isReady(): Boolean = instance != null

        suspend fun captureScreenshotToFile(outputFile: File): File? =
            instance?.captureToFile(outputFile)

        fun refreshSystemEnabledState(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
            val expected = ComponentName(
                context.packageName,
                ScreenCaptureAccessibilityService::class.java.name,
            ).flattenToString()
            val expectedShort = ComponentName(
                context.packageName,
                ScreenCaptureAccessibilityService::class.java.simpleName,
            ).flattenToShortString()
            val enabled = enabledServices.split(':').any {
                it.equals(expected, ignoreCase = true) ||
                    it.equals(expectedShort, ignoreCase = true)
            }
            _isSystemEnabled.value = enabled || instance != null
            return _isSystemEnabled.value
        }
    }
}
