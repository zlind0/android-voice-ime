package com.litertime.android.settings

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeSettingsStoreTest {
    @Test
    fun roundTripsAndClampsTraditionalInputSettings() {
        val dir = Files.createTempDirectory("ime-settings-test").toFile()
        try {
            val store = ImeSettingsStore(dir)
            assertEquals(1000, store.getSettings().handwritingFadeDelayMs)
            assertTrue(store.getSettings().handwritingAutoCommitFirst)

            store.saveSettings(
                ImeSettings(
                    handwritingFadeDelayMs = 1500,
                    handwritingAutoCommitFirst = false,
                    handwritingStrokeWidthDp = 7.5f,
                    handwritingInkColor = HandwritingInkColor.SKY_BLUE,
                    handwritingShowGrid = false,
                    handwritingPanelHeightDp = 300,
                    keyboardHeightDp = 260,
                    keyboardShowNumberRow = true,
                    keyboardHapticEnabled = false,
                    keyboardKeyPopupEnabled = false,
                    keyboardAutoCapsEnabled = false,
                    keyboardDoubleSpacePeriod = false,
                    recentEmojis = "😀\u001F🎉",
                ),
            )

            // Fresh store instance reads persisted properties file from disk
            val reloaded = ImeSettingsStore(dir).getSettings()
            assertEquals(1500, reloaded.handwritingFadeDelayMs)
            assertFalse(reloaded.handwritingAutoCommitFirst)
            assertEquals(7.5f, reloaded.handwritingStrokeWidthDp, 0.01f)
            assertEquals(HandwritingInkColor.SKY_BLUE, reloaded.handwritingInkColor)
            assertFalse(reloaded.handwritingShowGrid)
            assertEquals(300, reloaded.handwritingPanelHeightDp)
            assertEquals(260, reloaded.keyboardHeightDp)
            assertTrue(reloaded.keyboardShowNumberRow)
            assertFalse(reloaded.keyboardHapticEnabled)
            assertFalse(reloaded.keyboardKeyPopupEnabled)
            assertFalse(reloaded.keyboardAutoCapsEnabled)
            assertFalse(reloaded.keyboardDoubleSpacePeriod)
            assertEquals(listOf("😀", "🎉"), reloaded.recentEmojiList())
        } finally {
            dir.deleteRecursively()
        }
    }
}
