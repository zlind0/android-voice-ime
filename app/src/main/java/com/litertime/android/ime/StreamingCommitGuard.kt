package com.litertime.android.ime

/**
 * Guards streaming `commitText` calls against stale tokens arriving after the user switches
 * editors, closes the IME window, or taps Cancel.
 */
class StreamingCommitGuard {
    private var sessionToken: Long = 0L
    private var activeRequestId: String? = null
    private var activeSessionToken: Long = -1L

    /**
     * Advances the editor session and returns any previously active request ID that must be
     * cancelled immediately.
     */
    @Synchronized
    fun beginSession(): String? {
        sessionToken += 1L
        val staleRequestId = activeRequestId
        activeRequestId = null
        activeSessionToken = -1L
        return staleRequestId
    }

    @Synchronized
    fun startRequest(requestId: String): Long {
        activeRequestId = requestId
        activeSessionToken = sessionToken
        return sessionToken
    }

    @Synchronized
    fun shouldCommit(requestId: String): Boolean =
        requestId == activeRequestId && activeSessionToken == sessionToken

    @Synchronized
    fun activeId(): String? = activeRequestId

    @Synchronized
    fun complete(requestId: String) {
        if (requestId == activeRequestId) {
            activeRequestId = null
            activeSessionToken = -1L
        }
    }

    @Synchronized
    fun cancelActive(): String? {
        val current = activeRequestId
        activeRequestId = null
        activeSessionToken = -1L
        return current
    }
}
