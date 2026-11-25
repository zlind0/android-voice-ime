package com.litertime.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Centralized Dark Pastel color system:
 * - All blues share the exact hue of `#5BCEFA` (`H = 196.60°`)
 * - All pinks share the exact hue of `#F5A9B8` (`H = 348.16°`)
 * - Low-saturation deep sky-blue canvas (`#112026`) with zero outer borders
 * - Neutral pure white (`#FFFFFF`) & light grays on dark surfaces; neutral near-black (`#141619`) on light pastels
 */
object PastelPalette {
    val BG_CANVAS = 0xFF112026.toInt()
    val SURFACE_LOWEST = 0xFF0E1B20.toInt()
    val SURFACE_CARD = 0xFF172B33.toInt()
    val SURFACE_PAD = 0xFF172A31.toInt()
    val SURFACE_MOD_KEY = 0xFF162930.toInt()
    val SURFACE_SIDE_BTN = 0xFF1A3038.toInt()
    val SURFACE_MIC_INACTIVE = 0xFF1B323B.toInt()
    val SURFACE_CHAR_KEY = 0xFF1C3139.toInt()
    val SURFACE_CHIP_IDLE = 0xFF1E3842.toInt()
    val SURFACE_ICON_BADGE = 0xFF1F3A44.toInt()
    val SURFACE_PRESSED = 0xFF26444F.toInt()
    val SURFACE_KEY_PRESSED = 0xFF2B4752.toInt()
    val SURFACE_BACKSPACE_PRESSED = 0xFF5C2933.toInt()
    val SURFACE_SHIFT_ACTIVE = 0xFF264957.toInt()
    val SURFACE_POPUP = 0xFF26424D.toInt()

    val SKY_BLUE = 0xFFADDDEF.toInt()
    val SKY_BLUE_PRESSED = 0xFF96CDE2.toInt()
    val SKY_BLUE_LIGHT = 0xFFC6E3EE.toInt()
    val PINK_LIGHT = 0xFFEDBDC6.toInt()
    val PINK_PRESSED = 0xFFDFACB6.toInt()
    val ROSE_DANGER = 0xFFF68BA0.toInt()
    val MINT = 0xFFB2EAD3.toInt()
    val LAVENDER = 0xFFD2CCF2.toInt()
    val BUTTER = 0xFFF5E29E.toInt()

    val TEXT_PRIMARY = 0xFFFFFFFF.toInt()
    val TEXT_ON_DARK_SKY = 0xFFFFFFFF.toInt()
    val TEXT_MOD_KEY = 0xFFD4D4D8.toInt()
    val TEXT_MUTED = 0xFF9CA3AF.toInt()

    val INK_ON_PASTEL = 0xFF141619.toInt()
    val INK_ON_PINK = 0xFF141619.toInt()
    val INK_ON_ROSE = 0xFF141619.toInt()

    val DISC_BG = 0xF215272E.toInt()
    val PILL_BG = 0xEE1A313A.toInt()
    const val SECTOR_HIGHLIGHT = 0x55ADDDEF
    const val CENTER_HIGHLIGHT = 0x38ADDDEF
    const val SECTOR_CANCEL = 0x55F68BA0
    const val SECTOR_DIVIDER = 0x26ADDDEF
    const val AMPLITUDE_RING = 0x66ADDDEF
    const val AMPLITUDE_RING_CANCEL = 0x66F68BA0
    const val GRID_LINE = 0x24ADDDEF
}

private val DarkPastelColorScheme = darkColorScheme(
    primary = Color(0xFFADDDEF),
    onPrimary = Color(0xFF141619),
    primaryContainer = Color(0xFF22424E),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFEDBDC6),
    onSecondary = Color(0xFF141619),
    secondaryContainer = Color(0xFF213F4A),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFD2CCF2),
    onTertiary = Color(0xFF141619),
    tertiaryContainer = Color(0xFF2B2840),
    onTertiaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF112026),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF112026),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1A3038),
    onSurfaceVariant = Color(0xFFA1A7AE),
    surfaceContainerLowest = Color(0xFF0E1B20),
    surfaceContainerLow = Color(0xFF172B33),
    surfaceContainer = Color(0xFF1C343D),
    surfaceContainerHigh = Color(0xFF233E48),
    surfaceContainerHighest = Color(0xFF2A4854),
    error = Color(0xFFF68BA0),
    onError = Color(0xFF141619),
    errorContainer = Color(0xFF422228),
    onErrorContainer = Color(0xFFFBD5DC),
    outline = Color(0xFF2C4A56),
    outlineVariant = Color(0xFF1E3842),
)

@Composable
fun LiteRtPastelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkPastelColorScheme,
        content = content,
    )
}
