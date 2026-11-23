package com.litertime.android.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelStorageTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun propertiesConfigReloadsWhenFileChanges() {
        val filesDir = tempFolder.newFolder("files")
        val cacheDir = tempFolder.newFolder("cache")
        val sharedDir = tempFolder.newFolder("shared")

        val writer = ModelStorage(filesDir, cacheDir)
        val reader = ModelStorage(filesDir, cacheDir)

        assertEquals(StorageMode.APP, reader.getConfig().mode)
        assertTrue(writer.setSharedDirectory(sharedDir, StorageAccess.ALL_FILES, label = "models"))

        // Ensure mtime differs or reload reads updated properties cleanly
        val loaded = reader.getConfig()
        assertEquals(StorageMode.ANDROID, loaded.mode)
        assertEquals(sharedDir.absolutePath, loaded.path)
        assertEquals("models", loaded.label)
        assertEquals(File(sharedDir, "litert-cache"), reader.litertCacheDir())
    }

    @Test
    fun safTreeDocumentIdResolution() {
        assertEquals(
            "/storage/emulated/0/Download/models",
            ModelStorage.resolveTreeDocumentIdToPath("primary:Download/models"),
        )
        assertEquals(
            "/storage/emulated/0",
            ModelStorage.resolveTreeDocumentIdToPath("primary:"),
        )
        assertEquals(
            "/storage/ABCD-1234/AI/models",
            ModelStorage.resolveTreeDocumentIdToPath("ABCD-1234:AI/models"),
        )
    }

    @Test
    fun completenessIgnoresPartAndMigratingFiles() {
        val filesDir = tempFolder.newFolder("files")
        val cacheDir = tempFolder.newFolder("cache")
        val storage = ModelStorage(filesDir, cacheDir)

        val gemma = ModelCatalog.findLlm("gemma4-e2b")!!
        val gemmaDir = storage.llmModelDir(gemma.id).apply { mkdirs() }
        File(gemmaDir, "${gemma.fileName}.part").writeBytes(byteArrayOf(1, 2, 3))
        File(gemmaDir, "${gemma.fileName}.migrating").writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(storage.isLlmInstalled(gemma))

        File(gemmaDir, gemma.fileName).writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(storage.isLlmInstalled(gemma))
        assertFalse(storage.hasExactLlmSize(gemma))
    }

    @Test
    fun deleteAsrPreservesVadAndOtherModels() {
        val filesDir = tempFolder.newFolder("files")
        val cacheDir = tempFolder.newFolder("cache")
        val storage = ModelStorage(filesDir, cacheDir)

        val vad = storage.vadFile().apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9, 9, 9))
        }
        val whisper = ModelCatalog.findAsr("whisper-base")!!
        val whisperDir = storage.asrModelDir(whisper.id).apply { mkdirs() }
        whisper.files.forEach { f ->
            File(whisperDir, f.fileName).writeBytes(byteArrayOf(1, 2))
        }
        val extraDir = File(storage.asrModelsRoot(), "custom-other-app-model").apply { mkdirs() }
        val extraFile = File(extraDir, "weights.bin").apply { writeBytes(byteArrayOf(5)) }

        assertTrue(storage.isAsrInstalled(whisper))
        assertTrue(storage.deleteAsrModel(whisper.id))
        assertFalse(storage.isAsrInstalled(whisper))
        assertFalse(whisperDir.exists())
        assertTrue(vad.isFile)
        assertTrue(extraFile.isFile)
        assertFalse(storage.deleteAsrModel("vad"))
    }

    @Test
    fun gpuCrashMarkerAlwaysLivesInAppPrivateFilesDir() {
        val filesDir = tempFolder.newFolder("files")
        val cacheDir = tempFolder.newFolder("cache")
        val sharedDir = tempFolder.newFolder("shared")
        val storage = ModelStorage(filesDir, cacheDir)
        assertTrue(storage.setSharedDirectory(sharedDir, StorageAccess.ALL_FILES))

        storage.writeGpuCrashMarker(12345, appVersionCode = 1)
        assertTrue(storage.hasGpuCrashMarker())
        assertTrue(storage.hasGpuCrashMarker(currentVersionCode = 1))
        assertTrue(storage.gpuCrashMarkerFile().absolutePath.startsWith(filesDir.absolutePath))
        assertFalse(File(sharedDir, "llm-models/gpu-crashed").exists())

        // Upgrading to version 2 automatically clears stale marker from version 1
        assertFalse(storage.hasGpuCrashMarker(currentVersionCode = 2))
        assertFalse(storage.gpuCrashMarkerFile().exists())

        storage.writeGpuCrashMarker(12346, appVersionCode = 2)
        assertTrue(storage.hasGpuCrashMarker(currentVersionCode = 2))

        storage.clearGpuCrashMarker()
        assertFalse(storage.hasGpuCrashMarker())
    }
}
