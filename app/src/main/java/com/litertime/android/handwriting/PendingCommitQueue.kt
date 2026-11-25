package com.litertime.android.handwriting

/**
 * FIFO commit queue that keeps character commit order strict when dual-box switching or
 * punctuation flushing triggers an asynchronous recognition pass while subsequent input arrives.
 */
class PendingCommitQueue {
    private sealed interface Entry {
        data class Pending(val token: Long) : Entry
        data class Ready(val text: String) : Entry
    }

    private var nextToken = 1L
    private val queue = ArrayDeque<Entry>()

    fun reservePending(): Long {
        val token = nextToken++
        queue.addLast(Entry.Pending(token))
        return token
    }

    fun resolve(token: Long, text: String?): List<String> {
        val idx = queue.indexOfFirst { it is Entry.Pending && it.token == token }
        if (idx < 0) {
            return emptyList()
        }
        if (text.isNullOrEmpty()) {
            queue.removeAt(idx)
        } else {
            queue[idx] = Entry.Ready(text)
        }
        return drainReadyHead()
    }

    fun enqueueCommit(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        if (queue.isEmpty()) return listOf(text)
        queue.addLast(Entry.Ready(text))
        return drainReadyHead()
    }

    fun clear() {
        queue.clear()
    }

    private fun drainReadyHead(): List<String> {
        if (queue.isEmpty() || queue.first() !is Entry.Ready) {
            return emptyList()
        }
        val ready = mutableListOf<String>()
        while (queue.isNotEmpty() && queue.first() is Entry.Ready) {
            ready.add((queue.removeFirst() as Entry.Ready).text)
        }
        return ready
    }
}
