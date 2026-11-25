package com.litertime.android.handwriting

/**
 * Pure state machine governing live handwriting recognition, auto-commit of the top candidate on
 * fade-out timeout, post-commit candidate replacement, punctuation/Enter flush, and Backspace.
 */
class HandwritingController {
    enum class State {
        IDLE,
        DRAWING,
        WAITING_AUTO_COMMIT,
        FADING_OUT,
    }

    sealed interface CommitAction {
        data class Commit(val text: String) : CommitAction
        data class ReplaceLast(val deleteChars: Int, val replacement: String) : CommitAction
        data object None : CommitAction
    }

    enum class BackspaceAction {
        CLEAR_CANVAS,
        DELETE_EDITOR,
    }

    var state: State = State.IDLE
        private set

    var hasCanvasStrokes: Boolean = false
        private set

    var candidates: List<HCCRRecognizer.Candidate> = emptyList()
        private set

    /**
     * Most recently auto-committed character from the previous character's timeout so tapping
     * candidate #2..#10 right after fade-out replaces it in-place rather than appending.
     */
    var lastAutoCommittedText: String? = null
        private set

    fun onStrokeStart(): Boolean {
        val wasFading = state == State.FADING_OUT
        if (wasFading) {
            // Lock in the previously auto-committed character and start fresh for the next char.
            lastAutoCommittedText = null
            candidates = emptyList()
            hasCanvasStrokes = false
        } else if (state == State.IDLE) {
            // New strokes invalidate replacing an older committed char.
            lastAutoCommittedText = null
        }
        state = State.DRAWING
        hasCanvasStrokes = true
        return wasFading
    }

    fun onStrokeEnd() {
        if (!hasCanvasStrokes) {
            state = State.IDLE
            return
        }
        state = State.WAITING_AUTO_COMMIT
    }

    fun onRecognitionResult(results: List<HCCRRecognizer.Candidate>) {
        if (hasCanvasStrokes) {
            candidates = results
        }
    }

    /**
     * Called when the user finishes writing a character and the configured delay elapses.
     * Returns the character to auto-commit (if [autoCommitFirst] is enabled and a top candidate
     * exists) and transitions to [State.FADING_OUT] so the canvas fades out smoothly.
     */
    fun onAutoCommitTimeout(autoCommitFirst: Boolean): String? {
        if (!hasCanvasStrokes || state != State.WAITING_AUTO_COMMIT) {
            return null
        }
        val top = if (autoCommitFirst) candidates.firstOrNull()?.text else null
        lastAutoCommittedText = top
        state = State.FADING_OUT
        return top
    }

    fun onFadeFinished() {
        if (state == State.FADING_OUT) {
            hasCanvasStrokes = false
            state = State.IDLE
            // Keep `candidates` and `lastAutoCommittedText` so the user can still tap candidate
            // #2..#10 right after fade-out to replace the auto-committed top-1 character.
        }
    }

    fun onCandidateSelected(candidateText: String): CommitAction {
        if (candidateText.isEmpty()) return CommitAction.None
        val action = if (hasCanvasStrokes) {
            // Canvas still holds the current character (either before timeout or mid-fade).
            val priorAuto = lastAutoCommittedText
            if (state == State.FADING_OUT && !priorAuto.isNullOrEmpty()) {
                CommitAction.ReplaceLast(deleteChars = priorAuto.length, replacement = candidateText)
            } else {
                CommitAction.Commit(candidateText)
            }
        } else {
            val priorAuto = lastAutoCommittedText
            if (!priorAuto.isNullOrEmpty()) {
                CommitAction.ReplaceLast(deleteChars = priorAuto.length, replacement = candidateText)
            } else {
                CommitAction.Commit(candidateText)
            }
        }
        clearAll(clearLastAutoCommit = true)
        return action
    }

    /**
     * Flushes the top candidate if un-committed strokes are still waiting on the canvas before
     * inserting punctuation, space, Enter, or switching panels.
     */
    fun flushPendingTopCandidate(): String? {
        val textToCommit = if (hasCanvasStrokes && state != State.FADING_OUT) {
            candidates.firstOrNull()?.text
        } else {
            null
        }
        clearAll(clearLastAutoCommit = true)
        return textToCommit
    }

    fun onBackspace(): BackspaceAction {
        return if (hasCanvasStrokes && state != State.FADING_OUT) {
            clearAll(clearLastAutoCommit = true)
            BackspaceAction.CLEAR_CANVAS
        } else {
            lastAutoCommittedText = null
            BackspaceAction.DELETE_EDITOR
        }
    }

    fun clearAll(clearLastAutoCommit: Boolean = true) {
        hasCanvasStrokes = false
        state = State.IDLE
        candidates = emptyList()
        if (clearLastAutoCommit) {
            lastAutoCommittedText = null
        }
    }
}
