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
    GEMMA_SCREENSHOT_REBUTTAL("截屏反驳"),
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

data class StreamlinedCanopyLayout(
    val domeCx: Float,
    val domeCy: Float,
    val canopyRadius: Float,
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

    /**
     * Outer-ring sectors of the AI mic, ordered from the far right (`0°`) to the far left
     * (`180°`). Every sector sweeps an equal [SECTOR_SWEEP_DEG] of the semicircle:
     *
     * ```
     * 180° ┌ 取消 ┬ 正式 ┬ 截屏 ┬ 指令 ┬ 反驳 ┐ 0°
     * ```
     */
    val GEMMA_OUTER_SECTORS: List<RadialAction> = listOf(
        RadialAction.GEMMA_SCREENSHOT_REBUTTAL,
        RadialAction.GEMMA_SCREENSHOT_INSTRUCTION,
        RadialAction.GEMMA_SCREENSHOT_CORRECTION,
        RadialAction.GEMMA_POLISH_FORMAL,
        RadialAction.CANCEL,
    )

    /** Number of equal outer-ring sectors of the AI mic. */
    const val SECTOR_COUNT: Int = 5

    /** Angular width of one outer-ring sector, `180° / [SECTOR_COUNT]`. */
    const val SECTOR_SWEEP_DEG: Float = 180f / SECTOR_COUNT

    /** Action committed when the finger stays in the inner disc (i.e. the AI mic default). */
    fun centerActionFor(source: MicSource): RadialAction = when (source) {
        MicSource.WHISPER -> RadialAction.WHISPER_TRANSCRIBE
        // Plain correction without a screenshot: reuse the raw ASR transcript mode so the
        // default AI-mic gesture never captures the screen.
        MicSource.GEMMA -> RadialAction.WHISPER_TRANSCRIBE
    }

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
            centerActionFor(source)
        } else {
            outerActionFor(source, angleDeg)
        }

        return RadialEvaluation(
            knobX = knobX,
            knobY = knobY,
            action = action,
            distanceRatio = distanceRatio,
            angleDegrees = angleDeg,
        )
    }

    /** Outer-ring action for [angleDegrees], measured from `0°` (right) to `180°` (left). */
    fun outerActionFor(source: MicSource, angleDegrees: Float): RadialAction = when (source) {
        MicSource.WHISPER -> RadialAction.CANCEL
        MicSource.GEMMA -> {
            val index = (angleDegrees / SECTOR_SWEEP_DEG)
                .toInt()
                .coerceIn(0, GEMMA_OUTER_SECTORS.lastIndex)
            GEMMA_OUTER_SECTORS[index]
        }
    }

    /** Index of [action] in [GEMMA_OUTER_SECTORS], or `-1` when it is not an outer sector. */
    fun sectorIndex(action: RadialAction): Int = GEMMA_OUTER_SECTORS.indexOf(action)

    /** Mid-arc angle of the sector at [index] (`index 0` = far right), used to place its label. */
    fun sectorMidAngle(index: Int): Float = SECTOR_SWEEP_DEG * (index + 0.5f)

    /**
     * Canvas `startAngle` for drawing the sector at [index] with `Canvas.drawArc`, where `0°` is
     * 3 o'clock and angles grow clockwise (the Y axis points down).
     */
    fun sectorCanvasStartAngle(index: Int): Float =
        180f + SECTOR_SWEEP_DEG * (SECTOR_COUNT - 1 - index)

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

    /**
     * Resolves the upper canopy center and radius for the streamlined morphing voice button so
     * that pressing anywhere inside the idle mic button starts safely inside [CENTER_ZONE_RATIO]
     * while fitting cleanly inside `[margin, viewWidth - margin]`.
     */
    fun resolveCanopyLayout(
        source: MicSource,
        btnLeft: Float,
        btnTop: Float,
        btnRight: Float,
        viewWidth: Float,
        panelExpandProgress: Float,
        density: Float,
    ): StreamlinedCanopyLayout {
        val dp = { value: Float -> value * density }
        val btnWidth = (btnRight - btnLeft).coerceAtLeast(1f)
        val btnCx = (btnLeft + btnRight) * 0.5f
        val p = panelExpandProgress.coerceIn(0f, 1f)

        val baseRadius = when (source) {
            MicSource.GEMMA -> dp(136f) + (dp(128f) - dp(136f)) * p
            MicSource.WHISPER -> dp(114f) + (dp(104f) - dp(114f)) * p
        }
        val minSafeRadius = (btnWidth * 0.5f + dp(16f)) / CENTER_ZONE_RATIO
        val margin = dp(10f)
        val maxFitRadius = ((viewWidth - margin * 2f) * 0.5f).coerceAtLeast(dp(64f))
        val canopyRadius = maxOf(baseRadius, minSafeRadius).coerceAtMost(maxFitRadius)

        val minCx = canopyRadius + margin
        val maxCx = (viewWidth - canopyRadius - margin).coerceAtLeast(minCx)
        val domeCx = btnCx.coerceIn(minCx, maxCx)
        val domeCy = btnTop - (dp(4f) + (dp(20f) - dp(4f)) * p)

        return StreamlinedCanopyLayout(
            domeCx = domeCx,
            domeCy = domeCy,
            canopyRadius = canopyRadius,
        )
    }
}
