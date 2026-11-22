# PUI2a: modern skin (M1 Refined): report

## Built
- **Setting `interface`** (`darktable` default, `modern`): `app-engine/.../real/AppSettings.kt`, stored in `files/settings/app.json`. Chosen on the About screen ("interface", two buttons). `MainActivity` collects it and builds `DtTokens.load(skin)`; the root `DtTheme` switches at once, the engine keeps running.
- **`app/ui-theme/modern-tokens.json`**, packed next to `tokens.json`; `DtTokens.load(Skin.MODERN)` layers it under an optional debug `theme.json`.
- **New tokens (darktable values keep today's look):** `color.accent`, `color.accentPower`, `color.accentTab`, `color.raised`, `type.moduleName`, `size.moduleGap`, `size.moduleSeparator`, `size.sliderThumbRound`, `shape.sliderThumb` (`marker`|`round`), `shape.valueFigures` (`proportional`|`tabular`). Tokens starting with `shape.` hold a word, not a colour.
- **Component styles** (screen code reads tokens only): round accent thumb with the fill up to it; accent on power icon, tab indicators (group, module, bottom), slider fill and progress; 1 dp hairline instead of the 2 dp gap between modules; slider values in a `value` text style (tnum when tabular); module names in `type.moduleName`; open module header and text buttons use `color.raised`.
- **Tests:** `TokensTest` (modern file parses, all keys exist in `tokens.json`, thumb values, darktable skin unchanged); screenshots `phone-20-modern-modules`, `phone-21-modern-history`, `phone-22-modern-sheet`.

## Changed from the brief
- One `color.accent` could not keep today's colours (tab indicator is `text`, power icon `textBright`), so there are per-use tokens `accentPower` and `accentTab`; the modern file sets all three to `#d08a3c`. Slider fill and progress use `color.sliderFill`, which the modern skin sets to the accent.
- Tabular figures are behind `shape.valueFigures`, off in the darktable skin.

## Not built
- The interface preview app (`app/app`) and the LPL shell stay on the darktable skin; only the real app has the setting.
- No font bundled. No visual comparison against the mockup (no device here).

## How to switch
About screen, "interface": darktable or modern. For a quick try without the UI, put `modern-tokens.json` as `theme.json` in a debug build's files folder.

## Evidence (CI)
See the run ids at the end of this file.

## Open points for a reviewer
- Look at the three modern screenshots from the p2a-app release files against the mockup (accent, hairlines, 8 dp corners, thumb position on the track).
- Darktable-skin screenshots are produced, not compared to references by CI; the darktable values are asserted in `TokensTest.darktableSkinKeepsTodaysLooks`.
- `color.sliderTrack` (#333333 modern) is also the background of some list rows in sheets.

Commit 57a664b: p2a-app run 45016472377 success (6m10s); p2b-real-engine run 45016472376 success (18m5s).
