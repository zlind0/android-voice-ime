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
    fun gemmaCenterDefaultsToPlainCorrectionAndEdgesMapAllFourSectors() {
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

        val cases = listOf(
            160f to RadialAction.CANCEL,
            115f to RadialAction.GEMMA_POLISH_FORMAL,
            65f to RadialAction.GEMMA_SCREENSHOT_CORRECTION,
            20f to RadialAction.GEMMA_SCREENSHOT_INSTRUCTION,
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
}
