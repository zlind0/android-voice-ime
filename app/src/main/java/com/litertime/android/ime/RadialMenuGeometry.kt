package com.litertime.android.ime

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class MicSource {
    WHISPER,
    GEMMA,
}

enum class RadialAction(val label: String) {
    NONE(""),
    WHISPER_TRANSCRIBE("Whisper 识别"),
    GEMMA_POLISH_FORMAL("润色·正式"),
    GEMMA_SCREENSHOT_CORRECTION("截屏纠错"),
    GEMMA_SCREENSHOT_INSTRUCTION("截屏指令"),
    CANCEL("取消"),
}

/**
 * App-level actions revealed by dragging a mic button *below* the mic row instead of into the
 * semicircle. Only model residency operations live here; they run without feeding audio to a model.
 */
enum class DragAppAction(val label: String) {
    UNLOAD_WHISPER("卸载 Whisper"),
    UNLOAD_GEMMA("卸载 Gemma"),
    RELOAD_GEMMA_GPU("GPU 重载"),
    LOAD_GEMMA_GPU("GPU 加载"),
}

data class RadialEvaluation(
    val knobX: Float,
    val knobY: Float,
    val action: RadialAction,
    val distanceRatio: Float,
    val angleDegrees: Float,
)

/**
 * Pure geometry solver for the expandable radial semicircle menu anchored above the pressed mic
 * button.
 *
 * Angles are measured in degrees from the positive X-axis going upward (`0°` = right, `90°` = up,
 * `180°` = left).
 */
object RadialMenuGeometry {
    const val CENTER_ZONE_RATIO: Float = 0.56f

    fun evaluate(
        anchorX: Float,
        anchorY: Float,
        pointerX: Float,
        pointerY: Float,
        radius: Float,
        source: MicSource,
        bottomGracePx: Float = 12f,
    ): RadialEvaluation {
        require(radius > 0f) { "radius must be positive" }

        val rawDx = pointerX - anchorX
        // Positive upward distance above the anchor.
        val rawUpDy = anchorY - pointerY
        val clampedUpDy = rawUpDy.coerceAtLeast(-bottomGracePx)
        val rawDist = hypot(rawDx, clampedUpDy)
        val clampedDist = rawDist.coerceAtMost(radius)

        val knobX: Float
        val knobY: Float
        if (rawDist > 0.001f && rawDist > radius) {
            val scale = radius / rawDist
            knobX = anchorX + rawDx * scale
            knobY = anchorY - clampedUpDy * scale
        } else {
            knobX = anchorX + rawDx
            knobY = anchorY - clampedUpDy
        }

        val distanceRatio = (clampedDist / radius).coerceIn(0f, 1f)
        val effectiveUpDy = clampedUpDy.coerceAtLeast(0f)
        val angleRad = atan2(effectiveUpDy, rawDx)
        val angleDeg = (angleRad * 180.0 / PI).toFloat().coerceIn(0f, 180f)

        val action = if (distanceRatio < CENTER_ZONE_RATIO) {
            when (source) {
                MicSource.WHISPER -> RadialAction.WHISPER_TRANSCRIBE
                MicSource.GEMMA -> RadialAction.GEMMA_POLISH_FORMAL
            }
        } else {
            when (source) {
                MicSource.WHISPER -> RadialAction.CANCEL
                MicSource.GEMMA -> when {
                    angleDeg >= 135f -> RadialAction.CANCEL
                    angleDeg >= 90f -> RadialAction.GEMMA_POLISH_FORMAL
                    angleDeg >= 45f -> RadialAction.GEMMA_SCREENSHOT_CORRECTION
                    else -> RadialAction.GEMMA_SCREENSHOT_INSTRUCTION
                }
            }
        }

        return RadialEvaluation(
            knobX = knobX,
            knobY = knobY,
            action = action,
            distanceRatio = distanceRatio,
            angleDegrees = angleDeg,
        )
    }

    fun pointOnArc(
        anchorX: Float,
        anchorY: Float,
        radius: Float,
        angleDegrees: Float,
    ): Pair<Float, Float> {
        val rad = angleDegrees * PI / 180.0
        val x = anchorX + (radius * cos(rad)).toFloat()
        val y = anchorY - (radius * sin(rad)).toFloat()
        return x to y
    }

    /**
     * Actions offered when the pressed mic button is dragged below the mic row.
     *
     * Whisper only exposes "unload" and only while it is resident. Gemma exposes "load with GPU"
     * while absent, "reload with GPU" while resident on CPU, and "unload" while resident. The
     * destructive action is always listed last so a downward flick never unloads by accident.
     */
    fun dragAppActions(
        source: MicSource,
        whisperLoaded: Boolean,
        gemmaLoaded: Boolean,
        gemmaLoadedWithGpu: Boolean,
    ): List<DragAppAction> = when (source) {
        MicSource.WHISPER -> if (whisperLoaded) {
            listOf(DragAppAction.UNLOAD_WHISPER)
        } else {
            emptyList()
        }
        MicSource.GEMMA -> buildList {
            if (gemmaLoaded && !gemmaLoadedWithGpu) add(DragAppAction.RELOAD_GEMMA_GPU)
            if (!gemmaLoaded) add(DragAppAction.LOAD_GEMMA_GPU)
            if (gemmaLoaded) add(DragAppAction.UNLOAD_GEMMA)
        }
    }
}
