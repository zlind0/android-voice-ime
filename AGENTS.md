# LiteRT IME Android — Engineering & UI Guidelines

## Scope & Architecture
- **Workspace root:** `android/` (Gradle Android app module `app/`).
- **Primary UI & IME components:**
  - Centralized color palette & Compose theme: `app/src/main/java/com/litertime/android/ui/PastelPalette.kt` and `app/src/main/res/values/themes.xml`
  - Top voice bar & radial gesture overlay: `app/src/main/java/com/litertime/android/ime/VoiceImeOverlayView.kt`
  - English QWERTY / Symbols / Emoji panels: `app/src/main/java/com/litertime/android/ime/traditional/QwertyKeyboardView.kt`, `EnglishKeyboardPanelView.kt`, `EmojiPickerView.kt`
  - Chinese handwriting & punctuation panels: `app/src/main/java/com/litertime/android/handwriting/HandwritingPanelView.kt`, `HandwritingCanvasView.kt`, `app/src/main/java/com/litertime/android/ime/traditional/ChinesePunctuationView.kt`
  - Settings Compose UI: `app/src/main/java/com/litertime/android/ui/MainActivity.kt`

---

## Dark Pastel UI Design System (Mandatory)

All UI work across the IME views and Compose Settings screens MUST follow the **Dark Pastel** design system defined in `PastelPalette.kt`.

### 1. Hue Anchors & Saturation/Lightness Calibration
- **Sky Blue Hue Anchor:** All blues (canvas background, inactive/active surfaces, sky-blue highlights) MUST share the exact hue of `#5BCEFA` (**H = 196.60°**), calibrated with low-saturation dark-pastel S/L values.
- **Pink Hue Anchor:** All pinks (Enter / Done action keys, cancel/danger coral-rose) MUST share the exact hue of `#F5A9B8` (**H = 348.16°**), calibrated with matching pastel S/L values.
- **Single Source of Truth:** Always reference `PastelPalette` (for Canvas/Android Views) and `LiteRtPastelTheme` / `MaterialTheme.colorScheme` (for Jetpack Compose). Never hardcode ad-hoc hex colors in UI components.

### 2. Surface & State Color Mapping
- **Overall Base Canvas:** Low-saturation deep sky blue `#112026` (`BG_CANVAS`), darker than all inactive buttons.
- **Inactive Buttons & Voice Controls:** Deep sky-blue surfaces close to the canvas so the keyboard stays dark and understated:
  - Normal / AI mic buttons (unloaded): `#1B323B` (`SURFACE_MIC_INACTIVE`), pressed `#26444F` (`SURFACE_PRESSED`)
  - Side bar toggles (`En`, `中`, `Backspace`): `#1A3038` (`SURFACE_SIDE_BTN`), pressed `#26444F`
  - QWERTY character & space keys: `#1C3139` (`SURFACE_CHAR_KEY`), pressed `#2B4752` (`SURFACE_KEY_PRESSED`)
  - QWERTY / handwriting / picker modifier keys: `#162930` (`SURFACE_MOD_KEY`)
  - Handwriting writing pad: `#172A31` (`SURFACE_PAD`)
  - Settings cards (`surfaceContainerLow`): `#172B33` (`SURFACE_CARD`)
- **Active Modes & Loaded Models (Light Pastel Sky Blue `#ADDDEF`):**
  - Selected keyboard input modes (`En` QWERTY, `中` Chinese handwriting)
  - Loaded speech/AI models (both normal Whisper mic and AI mic when loaded, plus Settings `"已加载"` badge)
  - Selected FilterChips, active Emoji category tab, top-1 auto-commit handwriting candidate, Caps Lock key, radial floating knob, and active GPU tray chips
  - Pressed state: `#96CDE2` (`SKY_BLUE_PRESSED`)
- **Enter & Done Keys (Light Pastel Pink `#EDBDC6`):**
  - Top voice bar Enter button and QWERTY/Symbols/Symbols2 `KeyKind.ENTER` (`↵`, `Done`, `Go`, `Next`, `Search`, `Send`)
  - Pressed state: `#DFACB6` (`PINK_PRESSED`)
- **Cancel / Destructive Actions:** Pastel rose `#F68BA0` (`ROSE_DANGER`).

### 3. Neutral Foreground Text & Icon Rules (No Color Tinting)
- **On Dark Surfaces:**
  - Primary text and icons — including QWERTY letters, inactive microphone capsule fills & strokes, `"AI"` label, inactive `En`/`中` buttons, Backspace, punctuation keys, radial labels, and Settings primary text/icons — MUST be **pure white** (`#FFFFFF`).
  - Secondary / lighter text — including modifier labels (`?123`, `ABC`, `=\<`, `标点`, `空格`, `手写`, `⌫`), spacebar `"English"` label, memory readout, hints, and Settings subtitles — MUST be **neutral light gray** (`#D4D4D8` / `#9CA3AF` / `#A1A7AE`).
  - **Do NOT tint regular text or non-selected icons blue or other colors.**
- **On Light Pastel Surfaces (`#ADDDEF`, `#EDBDC6`, `#F68BA0`):**
  - Use **neutral near-black** (`#141619`: `INK_ON_PASTEL`, `INK_ON_PINK`, `INK_ON_ROSE`) for text and icons (including loaded microphone fills/strokes and Enter/Done symbols) to preserve crisp legibility.

### 4. Borderless & Understated QWERTY Design
- **Zero Outer Borders:** Do NOT draw outer border strokes on buttons, cards, chips, switches, key preview popups, handwriting canvas, candidate chips, emoji tabs, radial disc, or panel top hairlines. Do NOT render green loaded-status indicator dots on mic buttons.
- **QWERTY Polish:** Keep the QWERTY layout plain and restrained — `8.5dp` key corner radius, balanced spacing (`6dp` horizontal outer padding, `7dp` row gap, `5.5dp` key gap), regular-weight pure white letters, and a vector return arrow for `"↵"`.

### 5. Token Reference Table

| Token | Hex | Hue / Role |
| :--- | :--- | :--- |
| `BG_CANVAS` | `#112026` | H=196.6° · Base canvas (darkest surface) |
| `SURFACE_CARD` / `SURFACE_PAD` | `#172B33` / `#172A31` | H=196.6° · Cards & handwriting pad |
| `SURFACE_MOD_KEY` | `#162930` | H=196.6° · Modifier & toolbar keys |
| `SURFACE_SIDE_BTN` / `SURFACE_MIC_INACTIVE` | `#1A3038` / `#1B323B` | H=196.6° · Inactive toggles & unloaded mics |
| `SURFACE_CHAR_KEY` | `#1C3139` | H=196.6° · QWERTY character & space keys |
| `SURFACE_CHIP_IDLE` | `#1E3842` | H=196.6° · Unselected chips & tonal buttons |
| `SKY_BLUE` / `SKY_BLUE_PRESSED` | `#ADDDEF` / `#96CDE2` | H=196.6° · Active modes, loaded models, selected chips |
| `PINK_LIGHT` / `PINK_PRESSED` | `#EDBDC6` / `#DFACB6` | H=348.2° · Enter & Done action keys |
| `ROSE_DANGER` | `#F68BA0` | H=348.2° · Cancel & destructive actions |
| `TEXT_PRIMARY` | `#FFFFFF` | Neutral pure white on dark surfaces |
| `TEXT_MOD_KEY` / `TEXT_MUTED` | `#D4D4D8` / `#9CA3AF` | Neutral light gray secondary text |
| `INK_ON_PASTEL` / `INK_ON_PINK` / `INK_ON_ROSE` | `#141619` | Neutral near-black on light pastel buttons |

---

## Build & Verification
Run unit tests and assemble the debug APK before concluding UI or logic changes:
```bash
./gradlew testDebugUnitTest assembleDebug
```
