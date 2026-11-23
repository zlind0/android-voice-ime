package com.litertime.android.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

enum class StorageMode {
    APP,
    ANDROID,
}

enum class StorageAccess {
    ALL_FILES,
    SAF,
}

data class ModelStorageConfig(
    val mode: StorageMode = StorageMode.APP,
    val access: StorageAccess = StorageAccess.ALL_FILES,
    val treeUri: String = "",
    val path: String = "",
    val label: String = "",
)

/**
 * Implements the cross-app model storage contract defined in `model-storage.md`.
 *
 * Uses a private `model-storage.properties` file cached by mtime so both the IME UI/service
 * process and the `:inference` process always observe configuration changes consistently.
 */
class ModelStorage(
    private val appFilesDir: File,
    private val appCacheDir: File,
    private val externalStorageDirProvider: () -> File? = { null },
) {
    constructor(context: Context) : this(
        appFilesDir = context.applicationContext.filesDir,
        appCacheDir = context.applicationContext.cacheDir,
        externalStorageDirProvider = {
            @Suppress("DEPRECATION")
            android.os.Environment.getExternalStorageDirectory()
        },
    )

    private val configFile = File(appFilesDir, "model-storage.properties")

    @Volatile
    private var cachedMtime: Long = -1L

    @Volatile
    private var cachedConfig: ModelStorageConfig = ModelStorageConfig()

    @Synchronized
    fun getConfig(): ModelStorageConfig {
        if (!configFile.isFile) {
            cachedMtime = -1L
            cachedConfig = ModelStorageConfig()
            return cachedConfig
        }
        val mtime = configFile.lastModified()
        if (mtime == cachedMtime) {
            return cachedConfig
        }
        val props = Properties()
        runCatching {
            FileInputStream(configFile).use { props.load(it) }
        }
        val mode = when (props.getProperty("mode")) {
            "ANDROID" -> StorageMode.ANDROID
            else -> StorageMode.APP
        }
        val access = when (props.getProperty("access")) {
            "SAF" -> StorageAccess.SAF
            else -> StorageAccess.ALL_FILES
        }
        val config = ModelStorageConfig(
            mode = mode,
            access = access,
            treeUri = props.getProperty("tree", ""),
            path = props.getProperty("path", ""),
            label = props.getProperty("label", ""),
        )
        cachedMtime = mtime
        cachedConfig = config
        return config
    }

    @Synchronized
    fun saveConfig(config: ModelStorageConfig) {
        appFilesDir.mkdirs()
        val props = Properties().apply {
            setProperty("mode", config.mode.name)
            setProperty("access", config.access.name)
            setProperty("tree", config.treeUri)
            setProperty("path", config.path)
            setProperty("label", config.label)
        }
        val tmp = File(appFilesDir, "model-storage.properties.tmp")
        FileOutputStream(tmp).use { props.store(it, "LiteRT IME model storage configuration") }
        if (!tmp.renameTo(configFile)) {
            tmp.copyTo(configFile, overwrite = true)
            tmp.delete()
        }
        cachedMtime = configFile.lastModified()
        cachedConfig = config
    }

    fun setAppStorageMode() {
        saveConfig(ModelStorageConfig(mode = StorageMode.APP))
    }

    /**
     * Validates that [dir] can be written directly via `java.io.File` (required for native mmap)
     * and stores it as the active shared root. Returns `false` if probing fails.
     */
    fun setSharedDirectory(
        dir: File,
        access: StorageAccess,
        treeUri: String = "",
        label: String = dir.name.ifBlank { dir.absolutePath },
    ): Boolean {
        if (!probeWritable(dir)) {
            return false
        }
        saveConfig(
            ModelStorageConfig(
                mode = StorageMode.ANDROID,
                access = access,
                treeUri = treeUri,
                path = dir.absolutePath,
                label = label,
            ),
        )
        return true
    }

    fun isConfiguredSharedDirAccessible(): Boolean {
        val config = getConfig()
        if (config.mode != StorageMode.ANDROID) return true
        if (config.path.isBlank()) return false
        val dir = File(config.path)
        return dir.isDirectory && dir.canRead() && probeWritable(dir)
    }

    fun effectiveRootDir(): File {
        val config = getConfig()
        if (config.mode == StorageMode.ANDROID && config.path.isNotBlank()) {
            val shared = File(config.path)
            if (shared.isDirectory && shared.canRead()) {
                return shared
            }
        }
        return appFilesDir
    }

    fun isSharedDirReadableOnly(): Boolean {
        val config = getConfig()
        if (config.mode != StorageMode.ANDROID || config.path.isBlank()) return false
        val dir = File(config.path)
        return dir.isDirectory && dir.canRead() && !probeWritable(dir)
    }

    fun llmModelsRoot(): File = File(effectiveRootDir(), "llm-models")

    fun asrModelsRoot(): File = File(effectiveRootDir(), "asr-models")

    fun llmModelDir(modelId: String): File = File(llmModelsRoot(), modelId)

    fun asrModelDir(modelId: String): File = File(asrModelsRoot(), modelId)

    fun vadFile(): File = File(File(asrModelsRoot(), "vad"), "silero_vad.onnx")

    /**
     * Compilation cache path per `model-storage.md` §6.
     * In shared mode uses `<root>/litert-cache/` if writable; otherwise falls back to `<app>/cache/litert-lm/`.
     */
    fun litertCacheDir(): File {
        val config = getConfig()
        if (config.mode == StorageMode.ANDROID && config.path.isNotBlank()) {
            val sharedRoot = File(config.path)
            val sharedCache = File(sharedRoot, "litert-cache")
            if (probeWritable(sharedCache)) {
                return sharedCache
            }
        }
        return File(appCacheDir, "litert-lm").apply { mkdirs() }
    }

    /**
     * Always stored in app-private `filesDir/llm-models/gpu-crashed` per `model-storage.md` §7.
     */
    fun gpuCrashMarkerFile(): File = File(File(appFilesDir, "llm-models"), "gpu-crashed")

    fun hasGpuCrashMarker(currentVersionCode: Int? = null): Boolean {
        val marker = gpuCrashMarkerFile()
        if (!marker.isFile) return false
        if (currentVersionCode == null) return true

        val recordedVersion = runCatching {
            marker.readLines()
                .firstOrNull { it.startsWith("version=") }
                ?.substringAfter("version=")
                ?.trim()
                ?.toIntOrNull()
        }.getOrNull()

        if (recordedVersion == null || recordedVersion < currentVersionCode) {
            marker.delete()
            return false
        }
        return true
    }

    fun writeGpuCrashMarker(pid: Int, appVersionCode: Int = 0) {
        val marker = gpuCrashMarkerFile()
        marker.parentFile?.mkdirs()
        marker.writeText("pid=$pid\nversion=$appVersionCode\n")
    }

    fun clearGpuCrashMarker() {
        val marker = gpuCrashMarkerFile()
        if (marker.exists()) {
            marker.delete()
        }
    }

    fun isLlmInstalled(spec: LlmModelSpec): Boolean {
        val file = File(llmModelDir(spec.id), spec.fileName)
        return file.isFile && file.length() > 0L
    }

    fun hasExactLlmSize(spec: LlmModelSpec): Boolean {
        val file = File(llmModelDir(spec.id), spec.fileName)
        return file.isFile && file.length() == spec.expectedSizeBytes
    }

    fun llmModelFile(spec: LlmModelSpec): File? {
        val file = File(llmModelDir(spec.id), spec.fileName)
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    fun isAsrInstalled(spec: AsrModelSpec): Boolean {
        val dir = asrModelDir(spec.id)
        return spec.files.all { f ->
            val file = File(dir, f.fileName)
            file.isFile && file.length() > 0L
        }
    }

    fun findFirstInstalledLlmId(): String? =
        ModelCatalog.llmModels.firstOrNull { isLlmInstalled(it) }?.id

    fun findFirstInstalledAsrId(): String? =
        ModelCatalog.asrModels.firstOrNull { isAsrInstalled(it) }?.id

    /**
     * Safe deletion per `model-storage.md` §1.3:
     * Only deletes the recognized `<modelId>` directory, never `llm-models/`, `asr-models/`, or `asr-models/vad/`.
     */
    fun deleteLlmModel(modelId: String): Boolean {
        val spec = ModelCatalog.findLlm(modelId) ?: return false
        val dir = llmModelDir(spec.id)
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    fun deleteAsrModel(modelId: String): Boolean {
        if (modelId == "vad") return false
        val spec = ModelCatalog.findAsr(modelId) ?: return false
        val dir = asrModelDir(spec.id)
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    fun litertCacheSizeBytes(): Long {
        val dir = litertCacheDir()
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun clearLitertCache(): Boolean {
        val dir = litertCacheDir()
        if (!dir.exists()) return true
        var ok = true
        dir.listFiles()?.forEach { child ->
            if (!child.deleteRecursively()) {
                ok = false
            }
        }
        return ok
    }

    fun probeWritable(dir: File): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs()) {
                return false
            }
            if (!dir.isDirectory) return false
            val probe = File(dir, ".litertime-write-test")
            FileOutputStream(probe).use { it.write(1) }
            probe.delete()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun resolveTreeUriToPath(treeUri: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null
        return resolveTreeDocumentIdToPath(docId, externalStorageDirProvider()?.absolutePath)
    }

    companion object {
        fun resolveTreeDocumentIdToPath(
            documentId: String,
            primaryExternalPath: String? = "/storage/emulated/0",
        ): String? {
            val parts = documentId.split(":", limit = 2)
            if (parts.isEmpty()) return null
            val volume = parts[0]
            val relative = parts.getOrElse(1) { "" }.trimStart('/')
            val base = when (volume) {
                "primary" -> primaryExternalPath?.trimEnd('/') ?: "/storage/emulated/0"
                "home" -> "${primaryExternalPath?.trimEnd('/') ?: "/storage/emulated/0"}/Documents"
                else -> if (volume.isNotBlank() && !volume.equals("raw", ignoreCase = true)) {
                    "/storage/$volume"
                } else if (volume.equals("raw", ignoreCase = true) && relative.isNotBlank()) {
                    return "/$relative"
                } else {
                    return null
                }
            }
            return if (relative.isEmpty()) base else "$base/$relative"
        }
    }
}
