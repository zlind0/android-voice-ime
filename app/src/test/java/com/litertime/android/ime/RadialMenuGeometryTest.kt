package com.litertime.android.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadialMenuGeometryTest {
    private val anchorX = 500f
    private val anchorY = 400f
    private val radius = 200f

    @Test
    fun whisperCenterTranscribesAndEdgeCancels() {
        val center = RadialMenuGeometry.evaluate(
            anchorX = anchorX,
            anchorY = anchorY,
            pointerX = anchorX,
            pointerY = anchorY - 40f,
            radius = radius,
            source = MicSource.WHISPER,
        )
        assertEquals(RadialAction.WHISPER_TRANSCRIBE, center.action)

        for (angle in listOf(15f, 90f, 165f)) {
            val (px, py) = RadialMenuGeometry.pointOnArc(anchorX, anchorY, 170f, angle)
            val edge = RadialMenuGeometry.evaluate(
                anchorX = anchorX,
                anchorY = anchorY,
                pointerX = px,
                pointerY = py,
                radius = radius,
                source = MicSource.WHISPER,
            )
            assertEquals("Angle $angle should cancel Whisper", RadialAction.CANCEL, edge.action)
        }
    }

    @Test
    fun gemmaCenterDefaultsToPlainCorrectionAndEdgesMapAllFiveSectors() {
        val center = RadialMenuGeometry.evaluate(
            anchorX = anchorX,
            anchorY = anchorY,
            pointerX = anchorX + 10f,
            pointerY = anchorY - 50f,
            radius = radius,
            source = MicSource.GEMMA,
        )
        assertEquals(RadialAction.WHISPER_TRANSCRIBE, center.action)
        assertEquals(
            RadialAction.WHISPER_TRANSCRIBE,
            RadialMenuGeometry.centerActionFor(MicSource.GEMMA),
        )
        assertEquals(5, RadialMenuGeometry.GEMMA_OUTER_SECTORS.size)
        assertEquals(36f, RadialMenuGeometry.SECTOR_SWEEP_DEG, 0.001f)

        val cases = listOf(
            162f to RadialAction.CANCEL,
            126f to RadialAction.GEMMA_POLISH_FORMAL,
            90f to RadialAction.GEMMA_SCREENSHOT_CORRECTION,
            54f to RadialAction.GEMMA_SCREENSHOT_INSTRUCTION,
            18f to RadialAction.GEMMA_SCREENSHOT_REBUTTAL,
        )
        for ((angle, expected) in cases) {
            val (px, py) = RadialMenuGeometry.pointOnArc(anchorX, anchorY, 170f, angle)
            val eval = RadialMenuGeometry.evaluate(
                anchorX = anchorX,
                anchorY = anchorY,
                pointerX = px,
                pointerY = py,
                radius = radius,
                source = MicSource.GEMMA,
            )
            assertEquals("Angle $angle mismatch", expected, eval.action)
        }
    }

    @Test
    fun fiveSectorsSplitTheSemicircleIntoThirtySixDegreeWedges() {
        val cases = listOf(
            0.5f to RadialAction.GEMMA_SCREENSHOT_REBUTTAL,
            35.9f to RadialAction.GEMMA_SCREENSHOT_REBUTTAL,
            36.1f to RadialAction.GEMMA_SCREENSHOT_INSTRUCTION,
            71.9f to RadialAction.GEMMA_SCREENSHOT_INSTRUCTION,
            72.1f to RadialAction.GEMMA_SCREENSHOT_CORRECTION,
            107.9f to RadialAction.GEMMA_SCREENSHOT_CORRECTION,
            108.1f to RadialAction.GEMMA_POLISH_FORMAL,
            143.9f to RadialAction.GEMMA_POLISH_FORMAL,
            144.1f to RadialAction.CANCEL,
            179.9f to RadialAction.CANCEL,
        )
        for ((angle, expected) in cases) {
            assertEquals(
                "Angle $angle boundary mismatch",
                expected,
                RadialMenuGeometry.outerActionFor(MicSource.GEMMA, angle),
            )
        }
        assertEquals(
            RadialAction.CANCEL,
            RadialMenuGeometry.outerActionFor(MicSource.GEMMA, 180f),
        )
    }

    @Test
    fun sectorDrawingHelpersMatchTheOuterSectorOrder() {
        assertEquals(
            listOf(324f, 288f, 252f, 216f, 180f),
            RadialMenuGeometry.GEMMA_OUTER_SECTORS.indices.map {
                RadialMenuGeometry.sectorCanvasStartAngle(it)
            },
        )
        assertEquals(
            listOf(18f, 54f, 90f, 126f, 162f),
            RadialMenuGeometry.GEMMA_OUTER_SECTORS.indices.map {
                RadialMenuGeometry.sectorMidAngle(it)
            },
        )
        assertEquals(
            0,
            RadialMenuGeometry.sectorIndex(RadialAction.GEMMA_SCREENSHOT_REBUTTAL),
        )
        assertEquals(4, RadialMenuGeometry.sectorIndex(RadialAction.CANCEL))
        assertEquals(-1, RadialMenuGeometry.sectorIndex(RadialAction.NONE))
    }

    @Test
    fun draggingBelowTheAnchorKeepsTheDefaultInsteadOfFormalPolish() {
        // The AI mic button is tall, so the finger spends most of its start position below the
        // anchor. No sector label is under it there; the gesture must stay on the default action
        // (plain correction without a screenshot) rather than silently switching to "正式".
        for (dy in listOf(8f, 40f, 70f, 110f)) {
            for (dx in listOf(-6f, 0f, 6f)) {
                val eval = RadialMenuGeometry.evaluate(
                    anchorX = anchorX,
                    anchorY = anchorY,
                    pointerX = anchorX + dx,
                    pointerY = anchorY + dy,
                    radius = radius,
                    source = MicSource.GEMMA,
                )
                assertEquals(
                    "dx=$dx dy=$dy should keep the AI mic default",
                    RadialAction.WHISPER_TRANSCRIBE,
                    eval.action,
                )
            }
        }
    }

    @Test
    fun knobIsClampedWithinSemicircleRadius() {
        val (px, py) = RadialMenuGeometry.pointOnArc(anchorX, anchorY, 500f, 45f)
        val eval = RadialMenuGeometry.evaluate(
            anchorX = anchorX,
            anchorY = anchorY,
            pointerX = px,
            pointerY = py,
            radius = radius,
            source = MicSource.GEMMA,
        )
        val dx = eval.knobX - anchorX
        val dy = anchorY - eval.knobY
        val dist = kotlin.math.hypot(dx, dy)
        assertTrue(dist <= radius + 0.1f)
        assertEquals(1f, eval.distanceRatio, 0.01f)
    }

    @Test
    fun whisperDragMenuOnlyOffersUnloadWhileResident() {
        assertTrue(
            RadialMenuGeometry.dragAppActions(
                source = MicSource.WHISPER,
                whisperLoaded = false,
                gemmaLoaded = false,
                gemmaLoadedWithGpu = false,
            ).isEmpty(),
        )
        assertEquals(
            listOf(DragAppAction.UNLOAD_WHISPER),
            RadialMenuGeometry.dragAppActions(
                source = MicSource.WHISPER,
                whisperLoaded = true,
                gemmaLoaded = false,
                gemmaLoadedWithGpu = false,
            ),
        )
    }

    @Test
    fun gemmaDragMenuTracksResidencyAndBackend() {
        assertEquals(
            listOf(DragAppAction.LOAD_GEMMA_GPU),
            RadialMenuGeometry.dragAppActions(
                source = MicSource.GEMMA,
                whisperLoaded = false,
                gemmaLoaded = false,
                gemmaLoadedWithGpu = false,
            ),
        )
        assertEquals(
            listOf(DragAppAction.RELOAD_GEMMA_GPU, DragAppAction.UNLOAD_GEMMA),
            RadialMenuGeometry.dragAppActions(
                source = MicSource.GEMMA,
                whisperLoaded = true,
                gemmaLoaded = true,
                gemmaLoadedWithGpu = false,
            ),
        )
        assertEquals(
            listOf(DragAppAction.UNLOAD_GEMMA),
            RadialMenuGeometry.dragAppActions(
                source = MicSource.GEMMA,
                whisperLoaded = false,
                gemmaLoaded = true,
                gemmaLoadedWithGpu = true,
            ),
        )
    }

    @Test
    fun canopyLayoutStaysWithinScreenAndProtectsInitialButtonTouch() {
        val density = 3f
        val dp = { v: Float -> v * density }

        for (widthDp in listOf(320f, 380f)) {
            val viewWidth = dp(widthDp)
            for (panelProgress in listOf(0f, 1f)) {
                val (whisperLeft, whisperRight, gemmaLeft, gemmaRight) = if (panelProgress == 0f) {
                    val micsLeft = dp(14f + 56f + 8f)
                    val micsRight = viewWidth - dp(14f + 48f + 8f)
                    val eachW = (micsRight - micsLeft - dp(8f)) / 2f
                    listOf(micsLeft, micsLeft + eachW, micsLeft + eachW + dp(8f), micsRight)
                } else {
                    val pairW = dp(44f * 2f + 6f)
                    val micsLeft = dp(14f) + pairW + dp(8f)
                    val micsRight = viewWidth - dp(14f) - pairW - dp(8f)
                    val eachW = (micsRight - micsLeft - dp(8f)) / 2f
                    listOf(micsLeft, micsLeft + eachW, micsLeft + eachW + dp(8f), micsRight)
                }
                val btnTop = dp(200f)

                for ((source, bounds) in listOf(
                    MicSource.WHISPER to (whisperLeft to whisperRight),
                    MicSource.GEMMA to (gemmaLeft to gemmaRight),
                )) {
                    val (btnLeft, btnRight) = bounds
                    val layout = RadialMenuGeometry.resolveCanopyLayout(
                        source = source,
                        btnLeft = btnLeft,
                        btnTop = btnTop,
                        btnRight = btnRight,
                        viewWidth = viewWidth,
                        panelExpandProgress = panelProgress,
                        density = density,
                    )
                    val margin = dp(10f)
                    assertTrue(
                        "Left edge exceeded for $source width=$widthDp p=$panelProgress",
                        layout.domeCx - layout.canopyRadius >= margin - 0.5f,
                    )
                    assertTrue(
                        "Right edge exceeded for $source width=$widthDp p=$panelProgress",
                        layout.domeCx + layout.canopyRadius <= viewWidth - margin + 0.5f,
                    )

                    val btnCx = (btnLeft + btnRight) * 0.5f
                    for (touchX in listOf(btnLeft, btnCx, btnRight)) {
                        val eval = RadialMenuGeometry.evaluate(
                            anchorX = layout.domeCx,
                            anchorY = layout.domeCy,
                            pointerX = touchX,
                            pointerY = btnTop,
                            radius = layout.canopyRadius,
                            source = source,
                            bottomGracePx = dp(12f),
                        )
                        assertEquals(
                            "Initial touch at x=$touchX on $source width=$widthDp p=$panelProgress",
                            RadialMenuGeometry.centerActionFor(source),
                            eval.action,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun canopyPetalMidpointsActivateExpectedActions() {
        val density = 3f
        val dp = { v: Float -> v * density }
        val viewWidth = dp(380f)
        val btnTop = dp(220f)

        val gemmaLayout = RadialMenuGeometry.resolveCanopyLayout(
            source = MicSource.GEMMA,
            btnLeft = dp(198f),
            btnTop = btnTop,
            btnRight = dp(310f),
            viewWidth = viewWidth,
            panelExpandProgress = 0f,
            density = density,
        )
        val petalR = gemmaLayout.canopyRadius * (RadialMenuGeometry.CENTER_ZONE_RATIO + 1f) * 0.5f
        for ((index, expectedAction) in RadialMenuGeometry.GEMMA_OUTER_SECTORS.withIndex()) {
            val (px, py) = RadialMenuGeometry.pointOnArc(
                anchorX = gemmaLayout.domeCx,
                anchorY = gemmaLayout.domeCy,
                radius = petalR,
                angleDegrees = RadialMenuGeometry.sectorMidAngle(index),
            )
            val eval = RadialMenuGeometry.evaluate(
                anchorX = gemmaLayout.domeCx,
                anchorY = gemmaLayout.domeCy,
                pointerX = px,
                pointerY = py,
                radius = gemmaLayout.canopyRadius,
                source = MicSource.GEMMA,
                bottomGracePx = dp(12f),
            )
            assertEquals("Petal $index mismatch", expectedAction, eval.action)
        }

        val whisperLayout = RadialMenuGeometry.resolveCanopyLayout(
            source = MicSource.WHISPER,
            btnLeft = dp(78f),
            btnTop = btnTop,
            btnRight = dp(190f),
            viewWidth = viewWidth,
            panelExpandProgress = 0f,
            density = density,
        )
        val whisperPetalR = whisperLayout.canopyRadius * (RadialMenuGeometry.CENTER_ZONE_RATIO + 1f) * 0.5f
        val (wx, wy) = RadialMenuGeometry.pointOnArc(
            anchorX = whisperLayout.domeCx,
            anchorY = whisperLayout.domeCy,
            radius = whisperPetalR,
            angleDegrees = 90f,
        )
        val whisperEval = RadialMenuGeometry.evaluate(
            anchorX = whisperLayout.domeCx,
            anchorY = whisperLayout.domeCy,
            pointerX = wx,
            pointerY = wy,
            radius = whisperLayout.canopyRadius,
            source = MicSource.WHISPER,
            bottomGracePx = dp(12f),
        )
        assertEquals(RadialAction.CANCEL, whisperEval.action)
    }
}
