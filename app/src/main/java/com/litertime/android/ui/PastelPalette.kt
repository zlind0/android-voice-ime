package com.litertime.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * A resolved Dark Pastel color set.
 *
 * The user can customize exactly three base colors:
 * - [accent1]: the light sky-blue accent used for selected modes / loaded Whisper.
 * - [accent2]: the light pink accent used for Enter / Done and the loaded AI mic.
 * - [background]: the base keyboard canvas.
 *
 * Every other surface is derived from [background] by blending a fixed-opacity white overlay
 * ([whiteOverlay]) – or, for the few darker tokens, a fixed-opacity black overlay. This keeps the
 * whole keyboard visually coherent for any background the user picks, including the default one.
 */
class PastelThemeColors(
    val accent1: Int = PastelPalette.DEFAULT_ACCENT_1,
    val accent2: Int = PastelPalette.DEFAULT_ACCENT_2,
    val background: Int = PastelPalette.DEFAULT_BG_CANVAS,
) {
    private val isDefaultAccent1 = accent1 == PastelPalette.DEFAULT_ACCENT_1
    private val isDefaultAccent2 = accent2 == PastelPalette.DEFAULT_ACCENT_2

    private fun whiteOverlay(alpha: Float): Int = blendArgb(background, WHITE, alpha)

    private fun blackOverlay(alpha: Float): Int = blendArgb(background, BLACK, alpha)

    // ---- Background-derived surfaces -------------------------------------------------------
    val bgCanvas: Int = background
    val surfaceLowest: Int = blackOverlay(0.03f)
    val surfaceCard: Int = whiteOverlay(0.045f)
    val surfacePad: Int = whiteOverlay(0.040f)
    val surfaceModKey: Int = whiteOverlay(0.035f)
    val surfaceSideBtn: Int = whiteOverlay(0.065f)
    val surfaceMicInactive: Int = whiteOverlay(0.072f)
    val surfaceCharKey: Int = whiteOverlay(0.070f)
    val surfaceChipIdle: Int = whiteOverlay(0.095f)
    val surfaceIconBadge: Int = whiteOverlay(0.105f)
    val surfacePressed: Int = whiteOverlay(0.145f)
    val surfaceKeyPressed: Int = whiteOverlay(0.160f)
    val surfacePopup: Int = whiteOverlay(0.140f)
    val surfaceBackspacePressed: Int =
        blendArgb(background, withHslLightness(accent2, 0.28f), 0.85f)

    // ---- Accents ---------------------------------------------------------------------------
    val skyBlue: Int = accent1
    val skyBluePressed: Int =
        if (isDefaultAccent1) 0xFF96CDE2.toInt() else shiftHslLightness(accent1, -0.07f)
    val skyBlueLight: Int =
        if (isDefaultAccent1) 0xFFC6E3EE.toInt() else shiftHslLightness(accent1, 0.07f)
    val pinkLight: Int = accent2
    val pinkPressed: Int =
        if (isDefaultAccent2) 0xFFDFACB6.toInt() else shiftHslLightness(accent2, -0.06f)
    val roseDanger: Int = 0xFFF68BA0.toInt()

    // ---- Foreground text -------------------------------------------------------------------
    private val lightBackground = relativeLuminance(background) > 0.42f
    val textPrimary: Int = if (lightBackground) 0xFF141619.toInt() else WHITE
    val textOnDarkSky: Int = textPrimary
    val textModKey: Int = if (lightBackground) 0xFF3F3F46.toInt() else 0xFFD4D4D8.toInt()
    val textMuted: Int = if (lightBackground) 0xFF52525B.toInt() else 0xFF9CA3AF.toInt()

    /** Neutral ink that stays legible on top of [accent1]. */
    val inkOnPastel: Int = contrastingInk(accent1)

    /** Neutral ink that stays legible on top of [accent2]. */
    val inkOnPink: Int = contrastingInk(accent2)

    /** Rose is a fixed semantic danger color, so its ink stays dark. */
    val inkOnRose: Int = 0xFF141619.toInt()

    // ---- Translucent overlays --------------------------------------------------------------
    val discBg: Int = withAlpha(background, 0xF2)
    val pillBg: Int = withAlpha(background, 0xEE)

    val sectorHighlight: Int = withAlpha(accent1, 0x55)
    val centerHighlight: Int = withAlpha(accent1, 0x38)
    val sectorCancel: Int = withAlpha(roseDanger, 0x55)
    val sectorDivider: Int = withAlpha(accent1, 0x26)
    val amplitudeRing: Int = withAlpha(accent1, 0x66)
    val amplitudeRingCancel: Int = withAlpha(roseDanger, 0x66)
    val gridLine: Int = withAlpha(accent1, 0x24)

    val isDefault: Boolean
        get() = accent1 == PastelPalette.DEFAULT_ACCENT_1 &&
            accent2 == PastelPalette.DEFAULT_ACCENT_2 &&
            background == PastelPalette.DEFAULT_BG_CANVAS

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val BLACK = 0xFF000000.toInt()
    }
}

/**
 * Centralized Dark Pastel color system, now driven by the three user-customizable base colors.
 *
 * All property reads resolve against the most recently applied theme, so existing view code can
 * keep using `PastelPalette.SURFACE_CHAR_KEY` etc. Android Views cache paint colors, so they must
 * call their own palette-refresh helpers after [applyTheme] runs (see the IME root container).
 */
object PastelPalette {
    val DEFAULT_ACCENT_1 = 0xFFADDDEF.toInt()
    val DEFAULT_ACCENT_2 = 0xFFEDBDC6.toInt()
    val DEFAULT_BG_CANVAS = 0xFF1E2028.toInt()

    @Volatile
    private var current: PastelThemeColors = PastelThemeColors()

    fun applyTheme(accent1: Int, accent2: Int, background: Int) {
        current = PastelThemeColors(accent1, accent2, background)
    }

    fun resetTheme() {
        current = PastelThemeColors()
    }

    val colors: PastelThemeColors
        get() = current

    fun colorsFor(accent1: Int, accent2: Int, background: Int): PastelThemeColors =
        PastelThemeColors(accent1, accent2, background)

    val BG_CANVAS: Int get() = current.bgCanvas
    val SURFACE_LOWEST: Int get() = current.surfaceLowest
    val SURFACE_CARD: Int get() = current.surfaceCard
    val SURFACE_PAD: Int get() = current.surfacePad
    val SURFACE_MOD_KEY: Int get() = current.surfaceModKey
    val SURFACE_SIDE_BTN: Int get() = current.surfaceSideBtn
    val SURFACE_MIC_INACTIVE: Int get() = current.surfaceMicInactive
    val SURFACE_CHAR_KEY: Int get() = current.surfaceCharKey
    val SURFACE_CHIP_IDLE: Int get() = current.surfaceChipIdle
    val SURFACE_ICON_BADGE: Int get() = current.surfaceIconBadge
    val SURFACE_PRESSED: Int get() = current.surfacePressed
    val SURFACE_KEY_PRESSED: Int get() = current.surfaceKeyPressed
    val SURFACE_BACKSPACE_PRESSED: Int get() = current.surfaceBackspacePressed
    val SURFACE_POPUP: Int get() = current.surfacePopup

    val SKY_BLUE: Int get() = current.skyBlue
    val SKY_BLUE_PRESSED: Int get() = current.skyBluePressed
    val SKY_BLUE_LIGHT: Int get() = current.skyBlueLight
    val PINK_LIGHT: Int get() = current.pinkLight
    val PINK_PRESSED: Int get() = current.pinkPressed
    val ROSE_DANGER: Int get() = current.roseDanger
    val MINT = 0xFFB2EAD3.toInt()
    val LAVENDER = 0xFFD2CCF2.toInt()
    val BUTTER = 0xFFF5E29E.toInt()

    val TEXT_PRIMARY: Int get() = current.textPrimary
    val TEXT_ON_DARK_SKY: Int get() = current.textOnDarkSky
    val TEXT_MOD_KEY: Int get() = current.textModKey
    val TEXT_MUTED: Int get() = current.textMuted

    val INK_ON_PASTEL: Int get() = current.inkOnPastel
    val INK_ON_PINK: Int get() = current.inkOnPink
    val INK_ON_ROSE: Int get() = current.inkOnRose

    val DISC_BG: Int get() = current.discBg
    val PILL_BG: Int get() = current.pillBg
    val SECTOR_HIGHLIGHT: Int get() = current.sectorHighlight
    val CENTER_HIGHLIGHT: Int get() = current.centerHighlight
    val SECTOR_CANCEL: Int get() = current.sectorCancel
    val SECTOR_DIVIDER: Int get() = current.sectorDivider
    val AMPLITUDE_RING: Int get() = current.amplitudeRing
    val AMPLITUDE_RING_CANCEL: Int get() = current.amplitudeRingCancel
    val GRID_LINE: Int get() = current.gridLine
}

// ---------------------------------------------------------------------------------------------
// Color math helpers (pure Kotlin so the same code can run in Compose and Android View code).
// ---------------------------------------------------------------------------------------------

internal fun withAlpha(color: Int, alpha: Int): Int =
    (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

internal fun blendArgb(base: Int, overlay: Int, alpha: Float): Int {
    val a = alpha.coerceIn(0f, 1f)
    val br = (base shr 16) and 0xFF
    val bg = (base shr 8) and 0xFF
    val bb = base and 0xFF
    val or = (overlay shr 16) and 0xFF
    val og = (overlay shr 8) and 0xFF
    val ob = overlay and 0xFF
    val r = (br + (or - br) * a).toInt().coerceIn(0, 255)
    val g = (bg + (og - bg) * a).toInt().coerceIn(0, 255)
    val b = (bb + (ob - bb) * a).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

internal fun relativeLuminance(color: Int): Float {
    val r = ((color shr 16) and 0xFF) / 255f
    val g = ((color shr 8) and 0xFF) / 255f
    val b = (color and 0xFF) / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

internal fun contrastingInk(color: Int): Int =
    if (relativeLuminance(color) > 0.55f) 0xFF141619.toInt() else 0xFFFFFFFF.toInt()

internal fun rgbToHsl(color: Int): FloatArray {
    val r = ((color shr 16) and 0xFF) / 255f
    val g = ((color shr 8) and 0xFF) / 255f
    val b = (color and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    if (max == min) return floatArrayOf(0f, 0f, l)
    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = when (max) {
        r -> ((g - b) / d + if (g < b) 6f else 0f)
        g -> ((b - r) / d + 2f)
        else -> ((r - g) / d + 4f)
    } * 60f
    return floatArrayOf(h, s, l)
}

internal fun hslToArgb(hue: Float, saturation: Float, lightness: Float): Int {
    val h = ((hue % 360f) + 360f) % 360f / 360f
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)
    if (s == 0f) {
        val v = (l * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    fun channel(t0: Float): Int {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        val value = when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
        return (value * 255f).toInt().coerceIn(0, 255)
    }
    val r = channel(h + 1f / 3f)
    val g = channel(h)
    val b = channel(h - 1f / 3f)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

internal fun shiftHslLightness(color: Int, delta: Float): Int {
    val hsl = rgbToHsl(color)
    return hslToArgb(hsl[0], hsl[1], (hsl[2] + delta).coerceIn(0f, 1f))
}

internal fun withHslLightness(color: Int, lightness: Float): Int {
    val hsl = rgbToHsl(color)
    return hslToArgb(hsl[0], hsl[1], lightness.coerceIn(0f, 1f))
}

private fun customColorScheme(palette: PastelThemeColors) = darkColorScheme(
    primary = Color(palette.skyBlue),
    onPrimary = Color(palette.inkOnPastel),
    primaryContainer = Color(blendArgb(palette.bgCanvas, palette.skyBlue, 0.18f)),
    onPrimaryContainer = Color(palette.textPrimary),
    secondary = Color(palette.pinkLight),
    onSecondary = Color(palette.inkOnPink),
    secondaryContainer = Color(blendArgb(palette.bgCanvas, palette.pinkLight, 0.16f)),
    onSecondaryContainer = Color(palette.textPrimary),
    tertiary = Color(PastelPalette.LAVENDER),
    onTertiary = Color(0xFF141619),
    tertiaryContainer = Color(blendArgb(palette.bgCanvas, PastelPalette.LAVENDER, 0.16f)),
    onTertiaryContainer = Color(palette.textPrimary),
    background = Color(palette.bgCanvas),
    onBackground = Color(palette.textPrimary),
    surface = Color(palette.bgCanvas),
    onSurface = Color(palette.textPrimary),
    surfaceVariant = Color(palette.surfaceSideBtn),
    onSurfaceVariant = Color(palette.textMuted),
    surfaceContainerLowest = Color(palette.surfaceLowest),
    surfaceContainerLow = Color(palette.surfaceCard),
    surfaceContainer = Color(palette.surfaceCharKey),
    surfaceContainerHigh = Color(palette.surfacePressed),
    surfaceContainerHighest = Color(palette.surfaceKeyPressed),
    error = Color(palette.roseDanger),
    onError = Color(palette.inkOnRose),
    errorContainer = Color(blendArgb(palette.bgCanvas, palette.roseDanger, 0.22f)),
    onErrorContainer = Color(palette.textPrimary),
    outline = Color(blendArgb(palette.bgCanvas, palette.textPrimary, 0.24f)),
    outlineVariant = Color(palette.surfaceChipIdle),
)

@Composable
fun LiteRtPastelTheme(
    accentColor1: Int = PastelPalette.DEFAULT_ACCENT_1,
    accentColor2: Int = PastelPalette.DEFAULT_ACCENT_2,
    backgroundColor: Int = PastelPalette.DEFAULT_BG_CANVAS,
    content: @Composable () -> Unit,
) {
    val colorScheme = remember(accentColor1, accentColor2, backgroundColor) {
        customColorScheme(PastelPalette.colorsFor(accentColor1, accentColor2, backgroundColor))
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}