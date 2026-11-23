package com.litertime.android.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(
        val fileName: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val fileIndex: Int,
        val totalFiles: Int,
    ) : DownloadState

    data class Completed(val message: String = "下载完成") : DownloadState
    data class Failed(val error: String) : DownloadState
}

/**
 * Resumable model downloader following `model-storage.md` §5 and §8:
 * - Writes into `<fileName>.part`
 * - Supports `Range: bytes=<downloaded>-` resume and HTTP 416 handling
 * - Atomically renames `.part` to final filename only after verification
 */
class ModelDownloader(private val modelStorage: ModelStorage) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    fun downloadLlm(spec: LlmModelSpec, mirror: DownloadMirror, onFinished: () -> Unit = {}) {
        if (jobs[spec.id]?.isActive == true) return
        val dir = modelStorage.llmModelDir(spec.id)
        if (!modelStorage.probeWritable(dir)) {
            updateState(spec.id, DownloadState.Failed("目标目录不可写，请检查存储权限"))
            return
        }
        val job = scope.launch {
            try {
                val targetFile = File(dir, spec.fileName)
                if (targetFile.isFile && targetFile.length() == spec.expectedSizeBytes) {
                    updateState(spec.id, DownloadState.Completed("模型已存在"))
                    onFinished()
                    return@launch
                }
                downloadSingleFile(
                    modelId = spec.id,
                    url = spec.downloadUrl(mirror),
                    targetFile = targetFile,
                    expectedExactBytes = spec.expectedSizeBytes,
                    fileIndex = 1,
                    totalFiles = 1,
                    scope = this,
                )
                updateState(spec.id, DownloadState.Completed())
                onFinished()
            } catch (_: CancellationException) {
                updateState(spec.id, DownloadState.Idle)
            } catch (e: Exception) {
                updateState(spec.id, DownloadState.Failed(e.message ?: "下载失败"))
            } finally {
                jobs.remove(spec.id)
            }
        }
        jobs[spec.id] = job
    }

    fun downloadAsr(spec: AsrModelSpec, mirror: DownloadMirror, onFinished: () -> Unit = {}) {
        if (jobs[spec.id]?.isActive == true) return
        val dir = modelStorage.asrModelDir(spec.id)
        if (!modelStorage.probeWritable(dir)) {
            updateState(spec.id, DownloadState.Failed("目标目录不可写，请检查存储权限"))
            return
        }
        val job = scope.launch {
            try {
                val totalFiles = spec.files.size
                spec.files.forEachIndexed { index, fileSpec ->
                    val target = File(dir, fileSpec.fileName)
                    if (target.isFile && target.length() > 0L) {
                        return@forEachIndexed
                    }
                    downloadSingleFile(
                        modelId = spec.id,
                        url = spec.downloadUrl(fileSpec.fileName, mirror),
                        targetFile = target,
                        expectedExactBytes = null,
                        fileIndex = index + 1,
                        totalFiles = totalFiles,
                        scope = this,
                    )
                }
                updateState(spec.id, DownloadState.Completed())
                onFinished()
            } catch (_: CancellationException) {
                updateState(spec.id, DownloadState.Idle)
            } catch (e: Exception) {
                updateState(spec.id, DownloadState.Failed(e.message ?: "下载失败"))
            } finally {
                jobs.remove(spec.id)
            }
        }
        jobs[spec.id] = job
    }

    fun cancel(modelId: String) {
        jobs.remove(modelId)?.cancel()
        updateState(modelId, DownloadState.Idle)
    }

    private fun downloadSingleFile(
        modelId: String,
        url: String,
        targetFile: File,
        expectedExactBytes: Long?,
        fileIndex: Int,
        totalFiles: Int,
        scope: CoroutineScope,
    ) {
        val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
        var existingBytes = if (partFile.isFile) partFile.length() else 0L

        if (expectedExactBytes != null && existingBytes == expectedExactBytes) {
            if (!partFile.renameTo(targetFile)) {
                throw IOException("无法重命名已完成的 .part 文件")
            }
            return
        }
        if (expectedExactBytes != null && existingBytes > expectedExactBytes) {
            partFile.delete()
            existingBytes = 0L
        }

        val requestBuilder = Request.Builder().url(url)
        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 416) {
                if (expectedExactBytes == null || partFile.length() == expectedExactBytes) {
                    if (!partFile.renameTo(targetFile)) {
                        throw IOException("无法重命名已完成的 .part 文件")
                    }
                    return
                } else {
                    partFile.delete()
                    throw IOException("分片大小不匹配，已清除损坏的断点文件")
                }
            }
            if (!response.isSuccessful && response.code != 206) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }

            val body = response.body ?: throw IOException("响应体为空")
            val appendMode = response.code == 206 && existingBytes > 0L
            if (!appendMode && existingBytes > 0L) {
                partFile.delete()
                existingBytes = 0L
            }

            val contentLength = body.contentLength()
            val totalBytes = when {
                expectedExactBytes != null -> expectedExactBytes
                contentLength > 0L -> existingBytes + contentLength
                else -> -1L
            }

            updateState(
                modelId,
                DownloadState.Downloading(
                    fileName = targetFile.name,
                    downloadedBytes = existingBytes,
                    totalBytes = totalBytes,
                    fileIndex = fileIndex,
                    totalFiles = totalFiles,
                ),
            )

            var written = existingBytes
            var lastNotifyAt = 0L
            val buffer = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                FileOutputStream(partFile, appendMode).use { output ->
                    while (true) {
                        if (!scope.isActive) throw CancellationException()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastNotifyAt >= 200L) {
                            lastNotifyAt = now
                            updateState(
                                modelId,
                                DownloadState.Downloading(
                                    fileName = targetFile.name,
                                    downloadedBytes = written,
                                    totalBytes = totalBytes,
                                    fileIndex = fileIndex,
                                    totalFiles = totalFiles,
                                ),
                            )
                        }
                    }
                    output.flush()
                }
            }

            if (expectedExactBytes != null && partFile.length() != expectedExactBytes) {
                throw IOException(
                    "文件大小校验失败 (${partFile.length()} != $expectedExactBytes)，保留 .part 可续传"
                )
            }
            if (partFile.length() <= 0L) {
                throw IOException("下载文件为空")
            }
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!partFile.renameTo(targetFile)) {
                throw IOException("重命名模型文件失败: ${targetFile.name}")
            }
        }
    }

    private fun updateState(modelId: String, state: DownloadState) {
        _states.update { current -> current + (modelId to state) }
    }
}
