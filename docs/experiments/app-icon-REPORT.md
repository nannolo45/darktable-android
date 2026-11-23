# app-icon: darktable's own launcher icon for dt-android

Done:
- `app/app-engine/brand/darktable-icon.svg`: copy of `upstream/darktable/data/pixmaps/scalable/darktable.svg` (the icon the desktop install uses, `Icon=darktable`; note `data/pixmaps/darktable.svg` is the wordmark, not the icon), upstream commit 04477d32. Licence GPL-3.0-or-later (darktable, `upstream/darktable/LICENSE`); header comment added, nothing else changed. Ground colour #252525 is from upstream's `X-Unity-IconBackgroundColor` in the .desktop file.
- `:app-engine:appIcons` (same pattern as `:lpl-shell:shellIcons`, Svg2Vector at build time) generates the adaptive icon (foreground, `dt_brand_bg` background, monochrome layer). `android:icon="@mipmap/ic_launcher"` in the manifest. minSdk 29, so the adaptive icon covers every device; no legacy bitmap needed. The shell sets no roundIcon, so none here.
- g0 (spike app) not touched.

Simplification (said plainly): Svg2Vector does not support SVG filters. The icon has 25 Gaussian blur filters (soft shadows and rims); they are dropped, so edges the original blurs appear as hard thin rims. Gradients (blade colours, hub) are kept. Monochrome layer: shadow and ground shapes left out, gradients replaced by one fill: six blades and hub. Nothing redrawn.

Rule 5 (no literal colours in screen code) does not apply to the launcher icon, as for the shell: the colours are darktable's mark, written only in the SVG; no screen code is touched.

Reproduce: `gradle :app-engine:assembleArm64Debug -Pdta.engineDir=...` (CI p2a/p2b); generated files in `app/app-engine/build/generated/app-icons/res`.
Not verified on a phone or emulator; the APK report does not show the icon.
