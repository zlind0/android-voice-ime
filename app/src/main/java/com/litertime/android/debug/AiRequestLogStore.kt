package com.litertime.android.debug

import java.io.File
import java.util.UUID
import org.json.JSONObject

/**
 * A persisted snapshot of one AI request. The raw input lives in [dir] as immutable files:
 *
 * ```
 * ai-request-logs/<id>/
 *   meta.json        # backend, model, prompts, language, file names (written once)
 *   audio.wav        # copy of the exact audio sent to the model
 *   screenshot.jpg   # copy of the exact image sent, when present
 *   output.txt       # model output (written once when the request finishes)
 *   result.json      # status/error metadata (written once when the request finishes)
 *   playground.json  # debug playground draft (editable, never touches the files above)
 * ```
 *
 * Playground edits are intentionally stored in a separate file so re-running a request can never
 * mutate the original recording.
 */
data class AiRequestLogEntry(
    val id: String,
    val dir: File,
    val timestamp: Long,
    val backendType: String,
    val mode: String,
    val modelId: String,
    val modelDisplayName: String,
    val systemPrompt: String,
    val userPrompt: String,
    val languageHint: String,
    /** ASR transcript when the request ran through the voice cascade, otherwise blank. */
    val transcript: String = "",
    val origin: String,
    val replayOf: String,
    val audioFileName: String,
    val screenshotFileName: String,
    val hasAudio: Boolean,
    val hasScreenshot: Boolean,
    val output: String,
    val status: String,
    val errorMessage: String,
)

data class PlaygroundDraft(
    val systemPrompt: String,
    val userPrompt: String,
    val modelId: String,
)

data class ReplayInput(
    val audioFile: File,
    val screenshotFile: File?,
)

/**
 * Writes the request result once. The input files and `meta.json` are never rewritten.
 */
class RequestLogRecorder internal constructor(private val dir: File) {
    @Volatile
    private var finished: Boolean = false

    val isFinished: Boolean get() = finished

    @Synchronized
    fun finish(output: String, status: String, errorMessage: String) {
        if (finished) return
        finished = true
        runCatching {
            File(dir, "output.txt").writeText(output)
            val result = JSONObject().apply {
                put("status", status)
                put("error", errorMessage)
                put("outputLength", output.length)
                put("finishedAt", System.currentTimeMillis())
            }
            writeJsonAtomically(File(dir, "result.json"), result)
        }
    }
}

/**
 * File-backed store for AI request logs and playground drafts. Safe to use from both the main
 * process (`MainActivity`) and the `:inference` process because it only touches app-private files.
 */
class AiRequestLogStore(
    private val appFilesDir: File,
    private val cacheDir: File,
) {
    fun rootDir(): File = File(appFilesDir, "ai-request-logs")

    /**
     * Copies the request inputs into a fresh log directory and writes `meta.json`. Returns a
     * recorder used to persist the model output when inference finishes.
     */
    fun recordRequest(
        requestId: String,
        backendType: String,
        mode: String,
        modelId: String,
        modelDisplayName: String,
        systemPrompt: String,
        userPrompt: String,
        languageHint: String,
        origin: String,
        replayOf: String,
        audioFile: File?,
        screenshotFile: File?,
        transcript: String? = null,
    ): RequestLogRecorder? = runCatching {
        val timestamp = System.currentTimeMillis()
        val id = "$timestamp-$requestId"
        val dir = File(rootDir(), id).apply { mkdirs() }
        if (dir.isDirectory) {
            val audioName = audioFile
                ?.takeIf { it.isFile }
                ?.let { src ->
                    val name = "audio.wav"
                    src.copyTo(File(dir, name), overwrite = true)
                    name
                }
                .orEmpty()

            val screenshotName = screenshotFile
                ?.takeIf { it.isFile }
                ?.let { src ->
                    val extension = src.extension.ifBlank { "jpg" }
                    val name = "screenshot.$extension"
                    src.copyTo(File(dir, name), overwrite = true)
                    name
                }
                .orEmpty()

            val meta = JSONObject().apply {
                put("id", id)
                put("timestamp", timestamp)
                put("requestId", requestId)
                put("backendType", backendType)
                put("mode", mode)
                put("modelId", modelId)
                put("modelDisplayName", modelDisplayName)
                put("systemPrompt", systemPrompt)
                put("userPrompt", userPrompt)
                put("languageHint", languageHint)
                put("transcript", transcript.orEmpty())
                put("origin", origin)
                put("replayOf", replayOf)
                put("audioFileName", audioName)
                put("screenshotFileName", screenshotName)
            }
            writeJsonAtomically(File(dir, "meta.json"), meta)
            RequestLogRecorder(dir)
        } else {
            null
        }
    }.getOrNull()

    fun listEntries(): List<AiRequestLogEntry> = runCatching {
        rootDir()
            .listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { parseEntry(it) }
            ?.sortedByDescending { it.timestamp }
            .orEmpty()
    }.getOrDefault(emptyList())

    /**
     * Loads the playground draft for [entry], falling back to the original prompts/model.
     */
    fun loadDraft(entry: AiRequestLogEntry): PlaygroundDraft {
        val file = File(entry.dir, "playground.json")
        if (file.isFile) {
            val obj = runCatching { JSONObject(file.readText()) }.getOrNull()
            if (obj != null) {
                return PlaygroundDraft(
                    systemPrompt = obj.optString("systemPrompt", entry.systemPrompt),
                    userPrompt = obj.optString("userPrompt", entry.userPrompt),
                    modelId = obj.optString("modelId", entry.modelId),
                )
            }
        }
        return PlaygroundDraft(
            systemPrompt = entry.systemPrompt,
            userPrompt = entry.userPrompt,
            modelId = entry.modelId,
        )
    }

    /**
     * Persists playground edits in `playground.json`. The raw request files are left untouched.
     */
    fun saveDraft(entryId: String, draft: PlaygroundDraft) {
        runCatching {
            val dir = File(rootDir(), entryId)
            if (dir.isDirectory) {
                val obj = JSONObject().apply {
                    put("systemPrompt", draft.systemPrompt)
                    put("userPrompt", draft.userPrompt)
                    put("modelId", draft.modelId)
                    put("updatedAt", System.currentTimeMillis())
                }
                writeJsonAtomically(File(dir, "playground.json"), obj)
            }
        }
    }

    /**
     * Copies the immutable raw audio/screenshot into a throwaway cache directory so replaying a
     * request cannot delete or modify the recorded originals.
     */
    fun prepareReplay(entry: AiRequestLogEntry): ReplayInput? {
        val sourceAudio = File(entry.dir, entry.audioFileName)
        if (!sourceAudio.isFile) return null
        val replayDir = File(cacheDir, "ai-playground/${UUID.randomUUID()}").apply { mkdirs() }

        val audio = File(replayDir, "audio.wav")
        sourceAudio.copyTo(audio, overwrite = true)

        val screenshot = entry.screenshotFileName
            .takeIf { it.isNotBlank() }
            ?.let { name -> File(entry.dir, name) }
            ?.takeIf { it.isFile }
            ?.let { src ->
                val extension = src.extension.ifBlank { "jpg" }
                File(replayDir, "screenshot.$extension").also { src.copyTo(it, overwrite = true) }
            }

        return ReplayInput(audioFile = audio, screenshotFile = screenshot)
    }

    private fun parseEntry(dir: File): AiRequestLogEntry? {
        val metaFile = File(dir, "meta.json")
        if (!metaFile.isFile) return null
        val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return null
        val result = runCatching { JSONObject(File(dir, "result.json").readText()) }.getOrNull()
        val audioName = meta.optString("audioFileName", "")
        val screenshotName = meta.optString("screenshotFileName", "")
        val outputFile = File(dir, "output.txt")
        val output = if (outputFile.isFile) {
            runCatching { outputFile.readText() }.getOrDefault("")
        } else {
            ""
        }
        return AiRequestLogEntry(
            id = meta.optString("id", dir.name),
            dir = dir,
            timestamp = meta.optLong("timestamp", dir.lastModified()),
            backendType = meta.optString("backendType", ""),
            mode = meta.optString("mode", ""),
            modelId = meta.optString("modelId", ""),
            modelDisplayName = meta.optString("modelDisplayName", meta.optString("modelId", "")),
            systemPrompt = meta.optString("systemPrompt", ""),
            userPrompt = meta.optString("userPrompt", ""),
            languageHint = meta.optString("languageHint", ""),
            transcript = meta.optString("transcript", ""),
            origin = meta.optString("origin", "ime"),
            replayOf = meta.optString("replayOf", ""),
            audioFileName = audioName,
            screenshotFileName = screenshotName,
            hasAudio = audioName.isNotBlank() && File(dir, audioName).isFile,
            hasScreenshot = screenshotName.isNotBlank() && File(dir, screenshotName).isFile,
            output = output,
            status = result?.optString("status") ?: "pending",
            errorMessage = result?.optString("error").orEmpty(),
        )
    }
}

private fun writeJsonAtomically(target: File, json: JSONObject) {
    runCatching {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(json.toString(2))
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
