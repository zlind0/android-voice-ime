package com.litertime.android.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingControllerTest {
    private val sampleCandidates = listOf(
        HCCRRecognizer.Candidate("中", 0.85f),
        HCCRRecognizer.Candidate("申", 0.08f),
        HCCRRecognizer.Candidate("虫", 0.03f),
    )

    @Test
    fun autoCommitOnTimeoutAndReplaceOnCandidateTapAfterFade() {
        val controller = HandwritingController()
        assertFalse(controller.onStrokeStart())
        controller.onStrokeEnd()
        controller.onRecognitionResult(sampleCandidates)

        val committed = controller.onAutoCommitTimeout(autoCommitFirst = true)
        assertEquals("中", committed)
        assertEquals(HandwritingController.State.FADING_OUT, controller.state)

        controller.onFadeFinished()
        assertEquals(HandwritingController.State.IDLE, controller.state)
        assertFalse(controller.hasCanvasStrokes)
        assertEquals(3, controller.candidates.size)

        val replaceAction = controller.onCandidateSelected("申")
        assertEquals(
            HandwritingController.CommitAction.ReplaceLast(deleteChars = 1, replacement = "申"),
            replaceAction,
        )
        assertNull(controller.lastAutoCommittedText)
    }

    @Test
    fun tappingCandidateBeforeTimeoutCommitsDirectlyWithoutReplace() {
        val controller = HandwritingController()
        controller.onStrokeStart()
        controller.onStrokeEnd()
        controller.onRecognitionResult(sampleCandidates)

        val action = controller.onCandidateSelected("申")
        assertEquals(HandwritingController.CommitAction.Commit("申"), action)
        assertFalse(controller.hasCanvasStrokes)
        assertTrue(controller.candidates.isEmpty())
    }

    @Test
    fun tappingCandidateMidFadeReplacesAutoCommittedCharacter() {
        val controller = HandwritingController()
        controller.onStrokeStart()
        controller.onStrokeEnd()
        controller.onRecognitionResult(sampleCandidates)
        assertEquals("中", controller.onAutoCommitTimeout(autoCommitFirst = true))

        // Mid-fade tap
        val action = controller.onCandidateSelected("虫")
        assertEquals(
            HandwritingController.CommitAction.ReplaceLast(deleteChars = 1, replacement = "虫"),
            action,
        )
    }

    @Test
    fun newStrokeDuringFadeInterruptsFadeAndStartsFreshCharacter() {
        val controller = HandwritingController()
        controller.onStrokeStart()
        controller.onStrokeEnd()
        controller.onRecognitionResult(sampleCandidates)
        controller.onAutoCommitTimeout(autoCommitFirst = true)

        val interrupted = controller.onStrokeStart()
        assertTrue(interrupted)
        assertNull(controller.lastAutoCommittedText)
        assertTrue(controller.candidates.isEmpty())
        assertTrue(controller.hasCanvasStrokes)
        assertEquals(HandwritingController.State.DRAWING, controller.state)
    }

    @Test
    fun punctuationFlushesPendingTopCandidateAndBackspaceClearsCanvasFirst() {
        val controller = HandwritingController()
        controller.onStrokeStart()
        controller.onStrokeEnd()
        controller.onRecognitionResult(sampleCandidates)

        assertEquals(HandwritingController.BackspaceAction.CLEAR_CANVAS, controller.onBackspace())
        assertFalse(controller.hasCanvasStrokes)
        assertEquals(HandwritingController.BackspaceAction.DELETE_EDITOR, controller.onBackspace())

        controller.onStrokeStart()
        controller.onStrokeEnd()
        controller.onRecognitionResult(sampleCandidates)
        assertEquals("中", controller.flushPendingTopCandidate())
        assertFalse(controller.hasCanvasStrokes)
    }
}
