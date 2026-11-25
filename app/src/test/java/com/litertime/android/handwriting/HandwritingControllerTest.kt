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

    @Test
    fun alternatingBetweenBoxesFlushesPreviousUncommittedCharacter() {
        val controller = HandwritingController()
        val start0 = controller.onStrokeStartInBox(0)
        assertNull(start0.flushedPreviousBoxIndex)
        assertNull(start0.committedPreviousText)
        assertEquals(0, controller.activeBoxIndex)

        controller.onStrokeEnd()
        controller.onRecognitionResult(sampleCandidates)

        val start1 = controller.onStrokeStartInBox(1)
        assertEquals(0, start1.flushedPreviousBoxIndex)
        assertEquals("中", start1.committedPreviousText)
        assertTrue(start1.candidatesCleared)
        assertEquals(1, controller.activeBoxIndex)
        assertTrue(controller.candidates.isEmpty())
        assertEquals(HandwritingController.State.DRAWING, controller.state)

        controller.onStrokeEnd()
        controller.onRecognitionResult(listOf(HCCRRecognizer.Candidate("国", 0.9f)))

        val backTo0 = controller.onStrokeStartInBox(0)
        assertEquals(1, backTo0.flushedPreviousBoxIndex)
        assertEquals("国", backTo0.committedPreviousText)
        assertEquals(0, controller.activeBoxIndex)
        assertEquals(HandwritingController.State.DRAWING, controller.state)
    }

    @Test
    fun switchingFromFadingBoxDoesNotRecommitAlreadyCommittedCharacter() {
        val controller = HandwritingController()
        controller.onStrokeStartInBox(0)
        controller.onStrokeEnd()
        controller.onRecognitionResult(sampleCandidates)
        assertEquals("中", controller.onAutoCommitTimeout(autoCommitFirst = true))
        assertEquals(HandwritingController.State.FADING_OUT, controller.state)

        val switchResult = controller.onStrokeStartInBox(1)
        assertNull(switchResult.flushedPreviousBoxIndex)
        assertNull(switchResult.committedPreviousText)
        assertTrue(switchResult.candidatesCleared)
        assertNull(controller.lastAutoCommittedText)
        assertTrue(controller.candidates.isEmpty())
        assertEquals(1, controller.activeBoxIndex)
        assertEquals(HandwritingController.State.DRAWING, controller.state)
    }

    @Test
    fun switchingFromIdleBoxDoesNotCommitAnything() {
        val controller = HandwritingController()
        val freshToBox1 = controller.onStrokeStartInBox(1)
        assertNull(freshToBox1.flushedPreviousBoxIndex)
        assertNull(freshToBox1.committedPreviousText)
        assertFalse(freshToBox1.candidatesCleared)
        assertEquals(1, controller.activeBoxIndex)

        controller.onStrokeEnd()
        controller.onRecognitionResult(sampleCandidates)
        controller.onAutoCommitTimeout(autoCommitFirst = true)
        controller.onFadeFinished()
        assertEquals(HandwritingController.State.IDLE, controller.state)

        val afterFadeToBox0 = controller.onStrokeStartInBox(0)
        assertNull(afterFadeToBox0.flushedPreviousBoxIndex)
        assertNull(afterFadeToBox0.committedPreviousText)
        assertTrue(afterFadeToBox0.candidatesCleared)
        assertNull(controller.lastAutoCommittedText)
        assertTrue(controller.candidates.isEmpty())
        assertEquals(0, controller.activeBoxIndex)
    }

    @Test
    fun backspaceClearsActiveBoxFirstThenDeletesEditor() {
        val controller = HandwritingController()
        controller.onStrokeStartInBox(1)
        controller.onStrokeEnd()
        controller.onRecognitionResult(sampleCandidates)

        assertEquals(HandwritingController.BackspaceAction.CLEAR_CANVAS, controller.onBackspace())
        assertFalse(controller.hasCanvasStrokes)
        assertEquals(HandwritingController.BackspaceAction.DELETE_EDITOR, controller.onBackspace())
    }

    @Test
    fun pendingCommitQueuePreservesFifoOrderAndClearsCleanly() {
        val queue = PendingCommitQueue()

        // Empty queue commits immediately.
        assertEquals(listOf("你"), queue.enqueueCommit("你"))

        // Pending token buffers subsequent commits until resolved.
        val token1 = queue.reservePending()
        assertEquals(emptyList<String>(), queue.enqueueCommit("，"))
        val token2 = queue.reservePending()
        assertEquals(emptyList<String>(), queue.enqueueCommit("。"))

        // Resolving token2 out of order stays buffered behind token1.
        assertEquals(emptyList<String>(), queue.resolve(token2, "好"))
        // Resolving token1 drains "你" (already committed) -> "我", "，", "好", "。"
        assertEquals(listOf("我", "，", "好", "。"), queue.resolve(token1, "我"))

        // Resolving with null/empty drops the slot and drains subsequent ready entries.
        val token3 = queue.reservePending()
        assertEquals(emptyList<String>(), queue.enqueueCommit("！"))
        assertEquals(listOf("！"), queue.resolve(token3, null))

        // Clearing discards pending entries and ignores stale resolves.
        val staleToken = queue.reservePending()
        assertEquals(emptyList<String>(), queue.enqueueCommit("？"))
        queue.clear()
        assertEquals(emptyList<String>(), queue.resolve(staleToken, "啊"))
        assertEquals(listOf("新"), queue.enqueueCommit("新"))
    }
}
