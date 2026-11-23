package com.litertime.android.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingCommitGuardTest {
    @Test
    fun focusSwitchRejectsStaleTokensAndReturnsActiveRequestId() {
        val guard = StreamingCommitGuard()
        guard.beginSession()
        guard.startRequest("req-1")
        assertTrue(guard.shouldCommit("req-1"))
        assertFalse(guard.shouldCommit("req-other"))

        val stale = guard.beginSession()
        assertEquals("req-1", stale)
        assertFalse(guard.shouldCommit("req-1"))
        assertNull(guard.activeId())
    }

    @Test
    fun cancelStopsFurtherCommits() {
        val guard = StreamingCommitGuard()
        guard.beginSession()
        guard.startRequest("req-2")
        assertTrue(guard.shouldCommit("req-2"))

        assertEquals("req-2", guard.cancelActive())
        assertFalse(guard.shouldCommit("req-2"))
    }
}
