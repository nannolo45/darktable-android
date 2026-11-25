# P5a: what every darktable module can do on the phone

Brief: `docs/briefs/P5a-module-audit.md` (PLAN 6.1 item 1). darktable commit `04477d3` (the pinned one).
This is an audit: nothing was changed in the app or the engine. Reproduce, CI runs and upstream notes:
`P5a-module-audit-REPORT.md`. The same data, machine readable: `P5a-results/modules.json`
(written by `tools/module-audit.py`).

## In short

- darktable builds **97 processing modules**: 74 current ones, 16 deprecated ones (shown by darktable only in an edit
  that already uses them) and 7 hidden ones (no panel on desktop either).
- Of the **74 current modules**: **50 work** on the phone, **19 work partly**, **5 are missing** (LUT 3D, liquify,
  external raster masks, composite, color mapping). Of the 16 deprecated ones: 6 work, 4 partly, 6 missing.
- **Renders**: every module was switched on with its defaults and rendered on a Bayer and an X-Trans raw (test C40):
  **164 renders, all 164 identical to darktable-cli** (no channel value differs at all). The modules already on in the
  test edits are covered by the suite's T1. One module could not be checked on X-Trans: raw chromatic aberrations has no
  switch there (it is a Bayer-only module, as on desktop). Nothing crashed. This proves that switching a module on is
  safe and renders exactly as desktop; it does not test every setting of every module.
- Nothing is lost when a module cannot be edited yet: a desktop edit renders and keeps its values.
- The common gaps: **colour pickers** not wired (16 current modules, mostly small), **graphs** with no editor (base curve,
  saturation curve, lowlight, raw denoise, the wavelet curves of denoise, monochrome's filter, color correction's grid),
  **files** that must be chosen (LUT 3D, raster masks, watermark list), **on-photo tools** (liquify, the colour checker
  of color calibration), and a few **lists** (white balance presets, the lens chooser).

## To offer the owner first

Most useful for a photographer for the least work, in the reviewer's suggested order; the owner decides.

1. **White balance presets** (daylight, cloudy, flash... and their fine-tuning): small; the bridge already lists them
   (C14), the app needs the list.
2. **Graduated density: move the filter line.** Today only its rotation can change, not its position. Small (one
   slider through a schema override); an on-photo line later if wanted.
3. **Lens correction: choose camera and lens by hand** (manual or adapted lenses, lenses Exif does not name) and the
   focal length, aperture and distance lists. Medium; the bridge already sets a lens (C14) and the lens database is in
   the APK. Automatic detection already works.
4. **LUT 3D: choose a LUT file** (film looks from .cube files). Medium; needs a decision on where the files come from
   (phone storage, or synced from the PC through LightPhotoLibrary).
5. **A colour dialog**, then used by framing (border and line colours) and watermark (text colour). Medium, once.
6. **Missing pickers and "auto" buttons** as one package: rgb levels auto, AgX's exposure pickers and buttons, colour
   equalizer, colour zones "create curve", colour harmonizer, unbreak input profile, splittoning and colorize hues.
   Small each, mostly the existing picker framework (P3c).
7. **Graph editors that reuse the existing curve editor:** base curve, saturation curve, lowlight; and the band editor
   for raw denoise and denoise (profiled)'s wavelet curves. Medium each.
8. **Liquify** (PLAN 6.1 item 6 names it first if wanted): large, an on-photo tool of its own.
9. **Color calibration's colour checker** (calibrating against a photographed chart): large, an on-photo chart frame.
10. **Negadoctor's pickers**, only if the owner scans film negatives: medium.

## How to read the tables

- **renders as desktop**: the bridge's picture compared with `darktable-cli`'s picture of the same edit, full size,
  with the suite's threshold (no channel value differs by more than 1 of 255). Test C40 switches each module on with
  its default parameters on top of the suite's test edit, one module at a time, on a Bayer photo (`mire1.cr2`, Canon
  EOS 40D) and an X-Trans photo (`xtransIV.raf`). "T1" means the module is already on in the test edit (or always on),
  so the suite's T1 render covers it. For modules that do nothing until given something (a shape, a file, another
  photo), the default render only shows that switching them on is harmless; "with input" names the test that checks
  them with real input, or says not checked.
- **editing on the phone** (PLAN 3.4 tiers): **works** = every control a photographer needs is there (generated
  sliders and lists, plus the hand-built graph or on-photo tool where desktop has one); **partly** = the module works
  but a part named in the next column is missing; **missing** = it cannot be used for its purpose on the phone yet.
  Every module, whatever its state here, renders an edit made on desktop and keeps its values untouched (tier 0), and
  can be switched on and off, reset, duplicated, blended and masked if desktop allows it.
- **size of the gap**: small = a schema override or a list; medium = a dialog, a picker set or a set of data files;
  large = an on-photo tool or a new graph editor.

## Modules

Sorted by darktable's "modules: all" layout (base, tone, color, correct, effect); modules darktable puts in no group
are found by search on desktop and on the phone.

<!-- p5a:tables:start -->
### base

| module | renders as desktop | editing on the phone | what is missing and why | size |
|---|---|---|---|---|
| base curve (`basecurve`) | yes (C40, identical) | partly | the curve itself cannot be drawn: no graph editor for base curve (the tone curve editor could be reused); its presets and the other sliders work; "scale for graph" also waits for it | medium |
| color reconstruction (`colorreconstruct`) | yes (C40, identical) | works | nothing | - |
| crop (`crop`) | yes (C40, identical); with input: C20 | works | nothing (tests: P3a crop tool (emulator), C20) | - |
| demosaic (`demosaic`) | yes (T1) | works | nothing (tests: C9) | - |
| exposure (`exposure`) | yes (T1) | works | nothing (tests: T2, C19) | - |
| highlight reconstruction (`highlights`) | yes (T1) | works | nothing | - |
| negadoctor (`negadoctor`) | yes (C40, identical) | partly | the seven colour pickers are not built (film base colour, D max, scan exposure, shadows, illuminant, paper black, print exposure); scanning negatives starts with "pick the film base", so the module is hard to use without them | medium |
| orientation (`flip`) | yes (T1) | works | nothing | - |
| raw black/white point (`rawprepare`) | yes (T1) | works | nothing | - |
| rotate and perspective (`ashift`) | yes (C40, identical); with input: C33 | works | nothing (tests: C33) | - |
| shadows and highlights (`shadhi`) | yes (C40, identical) | works | nothing | - |
| tone equalizer (`toneequal`) | yes (C40, identical) | works | nothing (tests: C19) | - |
| white balance (`temperature`) | yes (T1) | partly | the "settings" list (camera presets such as daylight, cloudy, flash and their fine-tuning) is not built; the bridge already lists the presets (C14), the app needs the list; temperature, tint, as shot, camera reference and "from image area" work (tests: C14) | small |

### tone

| module | renders as desktop | editing on the phone | what is missing and why | size |
|---|---|---|---|---|
| AgX (`agx`) | yes (C40, identical) | partly | the five colour pickers (white, black and pivot exposure, "set black and white") and three buttons (read exposure from metadata, reset primaries, set from above) are not built; the read-only curve display is not drawn; every slider works | medium |
| contrast & texture (`contrastntexture`) | yes (C40, identical) | works | nothing | - |
| filmic rgb (`filmicrgb`) | yes (Bayer C40, identical; X-Trans T1) | works | the read-only curve display is not drawn | - |
| local contrast (`bilat`) | yes (C40, identical) | works | nothing | - |
| rgb curve (`rgbcurve`) | yes (C40, identical) | works | nothing (tests: C19, C38) | - |
| rgb levels (`rgblevels`) | yes (C40, identical) | partly | "auto" and "apply auto levels from an area" are not built; the levels graph and the black, gray and white pickers work (tests: C19) | small |
| sigmoid (`sigmoid`) | yes (C40, identical) | works | nothing (tests: C9) | - |
| spektrafilm (`spektrafilm`) | yes (C40, identical) | works | two pickers (pre-compression boost, post-compression scale) are not built; "download data pack" is not needed (the app carries the pack) | small |
| tone curve (`tonecurve`) | yes (C40, identical) | works | "scale for graph" (a display zoom of the graph) is not built | small |

### color

| module | renders as desktop | editing on the phone | what is missing and why | size |
|---|---|---|---|---|
| color balance rgb (`colorbalancergb`) | yes (C40, identical) | works | nothing (tests: C9) | - |
| color calibration (`channelmixerrgb`) | yes (Bayer C40, identical; X-Trans T1) | partly | the colour checker calibration (chart, optimize for, patch scale, compute/check/accept buttons) needs a chart frame drawn on the photo, not built; everything else, the illuminant picker included, works (tests: C19) | large |
| color contrast (`colorcontrast`) | yes (C40, identical) | works | nothing | - |
| color correction (`colorcorrection`) | yes (C40, identical) | partly | the colour grid where desktop drags the two split-toning points is not built, so the four colour parameters cannot be changed; only saturation works | medium |
| color equalizer (`colorequal`) | yes (C40, identical) | works | two pickers (node placement, white level) are not built | small |
| color harmonizer (`colorharmonizer`) | yes (C40, identical) | partly | the hue pickers (anchor hue and the four harmony hues) and the "analyze" and "import" buttons are not built; the sliders and lists work | small |
| color look up table (`colorchecker`) | yes (C40, identical); with input: C32 | works | nothing (tests: C32) | - |
| color zones (`colorzones`) | yes (C40, identical) | works | "create a curve from an area" picker is not built; the band editor, its options and "pick/show color" work | small |
| input color profile (`colorin`) | yes (T1) | works | your own ICC profile files cannot be added (desktop reads them from its color/in folder); the built-in and embedded profiles work (tests: C14) | - |
| LUT 3D (`lut3d`) | yes (C40, identical); with input: not checked | missing | no way to choose a LUT file: desktop lists the files of its LUT folder (a preference the phone does not have); needs a file chooser and an answer to where the .cube/.3dl/.png files come from (phone storage, or synced from the PC through LightPhotoLibrary); with no file the module does nothing | medium |
| monochrome (`monochrome`) | yes (C40, identical) | partly | the colour filter position (the round a/b graph you drag on desktop) is not built, so only the neutral filter and "highlights" are available | medium |
| output color profile (`colorout`) | yes (T1) | works | your own ICC profile files cannot be added (desktop's color/out folder); the built-in profiles work | - |
| rgb primaries (`primaries`) | yes (C40, identical) | works | nothing | - |
| saturation curve (`satcurvergb`) | yes (C40, identical) | partly | the saturation curve itself (graph) cannot be edited, nor its picker; the protect sliders work | medium |
| velvia (`velvia`) | yes (C40, identical) | works | nothing | - |

### correct

| module | renders as desktop | editing on the phone | what is missing and why | size |
|---|---|---|---|---|
| astrophoto denoise (`nlmeans`) | yes (C40, identical) | works | nothing | - |
| chromatic aberrations (`cacorrectrgb`) | yes (C40, identical) | works | nothing | - |
| contrast equalizer (`atrous`) | yes (C40, identical) | works | nothing | - |
| denoise (profiled) (`denoiseprofile`) | yes (C40, identical) | partly | the wavelet curves (graph of the "wavelets" mode) are not built; profiles, non-local means mode and all sliders work (tests: C14) | medium |
| dither or posterize (`dither`) | yes (C40, identical) | works | nothing | - |
| external raster masks (`rasterfile`) | yes (C40, identical); with input: not checked | missing | needs a PNG or PFM mask file chosen with a file chooser, not built; with no file the module does nothing | medium |
| haze removal (`hazeremoval`) | yes (C40, identical) | works | nothing | - |
| hot pixels (`hotpixels`) | yes (C40, identical) | works | nothing | - |
| lens correction (`lens`) | yes (Bayer C40, identical; X-Trans T1) | partly | camera and lens cannot be chosen by hand (desktop's searchable camera and lens lists; the bridge already sets a lens, C14) and the focal length, aperture and distance lists are not built; with a lens the lensfun database knows, automatic detection from Exif works; the lensfun database is in the APK (tests: C14) | medium |
| liquify (`liquify`) | yes (C40, identical); with input: not checked | missing | liquify is drawn on the photo (points, lines, curves with strength and radius handles); the on-photo tool is not built, the panel has no controls of its own | large |
| raw chromatic aberrations (`cacorrect`) | Bayer yes (C40, identical); X-Trans not checked: no on/off switch for this image class | works | nothing | - |
| raw denoise (`rawdenoise`) | yes (C40, identical) | partly | the per-frequency curves (graph) are not built; the noise threshold works | medium |
| retouch (`retouch`) | yes (C40, identical); with input: C32 | works | wavelet decompose cannot be edited (the picture shows it); shapes have no rotation gesture (a slider instead) (tests: C32, C37) | small |
| scale pixels (`scalepixels`) | yes (C40, identical) | works | nothing | - |
| sharpen (`sharpen`) | yes (C40, identical) | works | nothing | - |
| surface blur (`bilateral`) | yes (C40, identical) | works | nothing | - |

### effect

| module | renders as desktop | editing on the phone | what is missing and why | size |
|---|---|---|---|---|
| bloom (`bloom`) | yes (C40, identical) | works | nothing | - |
| blurs (`blurs`) | yes (C40, identical) | works | nothing | - |
| censorize (`censorize`) | yes (C40, identical) | works | nothing | - |
| color mapping (`colormapping`) | yes (C40, identical); with input: not checked | missing | needs "acquire as source" and "acquire as target" (two images' colours), not built; with nothing acquired the module does nothing | medium |
| colorize (`colorize`) | yes (C40, identical) | works | the hue picker is not built; the sliders work | small |
| composite (`overlay`) | yes (C40, identical); with input: not checked | missing | composite needs another photo of the library chosen as the overlay; no way to choose it, and the phone's engine library does not hold the other photos | large |
| diffuse or sharpen (`diffuse`) | yes (C40, identical) | works | nothing (tests: C39) | - |
| enlarge canvas (`enlargecanvas`) | yes (C40, identical) | works | nothing | - |
| framing (`borders`) | yes (C40, identical) | partly | border and frame line colours cannot be chosen: desktop's colour buttons and their pickers are not built (needs a colour dialog); sizes, aspect and position work (tests: C14) | medium |
| graduated density (`graduatednd`) | yes (C40, identical) | partly | the filter line cannot be moved: its position ("offset") is set only by dragging on the photo on desktop; a slider through a schema override would do; density, hardness, rotation, hue and saturation work (hue picker not built) | small |
| grain (`grain`) | yes (C40, identical) | works | nothing | - |
| highpass (`highpass`) | yes (C40, identical) | works | nothing | - |
| lowlight vision (`lowlight`) | yes (C40, identical) | partly | the lowlight curve (graph), the module's main control, is not built; only blue shift works | medium |
| lowpass (`lowpass`) | yes (C40, identical) | works | nothing | - |
| soften (`soften`) | yes (C40, identical) | works | nothing | - |
| split-toning (`splittoning`) | yes (C40, identical) | works | the two hue pickers are not built; the sliders work | small |
| vignetting (`vignette`) | yes (C40, identical) | works | no on-photo handles; every value has a slider | small |
| watermark (`watermark`) | yes (C40, identical) | partly | the watermark list (SVG files from darktable's watermarks folder, which the APK carries), the text font and the colour button are not built, nor the 3x3 alignment; the default watermark and the sliders work; fonts on Android not checked | medium |

### no group (search only)

| module | renders as desktop | editing on the phone | what is missing and why | size |
|---|---|---|---|---|
| color balance (`colorbalance`) | yes (C40, identical) | partly | desktop's HSL view of lift/gamma/gain (hue and saturation sliders) and the two pickers (optimize luma, neutralize colors) are not built; the RGB factor sliders work; desktop lists it in no module group (search only) | small |
| defringe (`defringe`) | yes (Bayer C40, identical; X-Trans C40, max 1, mean 0.00000, PSNR 121.04) | works | desktop shows it as deprecated in its message but not in its flags (see the report's upstream notes); listed in no module group (search only) | - |
| unbreak input profile (`profile_gamma`) | yes (C40, identical) | partly | four pickers and "auto tune level" are not built; the sliders work; desktop lists it in no module group (search only) | small |

### deprecated (darktable shows them only in an edit that already uses them)

| module | renders as desktop | editing on the phone | what is missing and why | size |
|---|---|---|---|---|
| basic adjustments (`basicadj`) | yes (C40, identical) | works | "auto" and two pickers are not built | small |
| channel mixer (`channelmixer`) | yes (C40, identical) | partly | the destination list and its red/green/blue sliders are interface-only on desktop and not built | small |
| color transfer (`colortransfer`) | yes (C40, identical) | missing | no controls on desktop either (deprecated); renders and keeps its values | - |
| contrast brightness saturation (`colisa`) | yes (C40, identical) | works | nothing | - |
| crop and rotate (`clipping`) | yes (C40, identical) | partly | sliders work; flip, keystone and aspect lists are not built (crop and perspective replace it) | medium |
| fill light (`relight`) | yes (C40, identical) | works | the picker is not built | small |
| filmic (`filmic`) | yes (C40, identical) | missing | all its sliders are interface-only on desktop (none bound to a parameter): not built; renders and keeps its values | medium |
| global tonemap (`globaltonemap`) | yes (C40, identical) | works | nothing | - |
| invert (`invert`) | yes (C40, identical) | missing | its only control is the film colour picker, not built; an old edit renders and keeps its values | small |
| legacy equalizer (`equalizer`) | yes (C40, identical) | missing | no controls on desktop either (deprecated); renders and keeps its values | - |
| levels (`levels`) | yes (C40, identical) | partly | the levels graph, "auto" and the three pickers are not built (rgb levels replaces it) | small |
| old local contrast (`clahe`) | yes (C40, identical) | partly | radius and amount are interface-only on desktop: not built | small |
| spot removal (`spots`) | yes (C40, identical); with input: not checked | missing | shapes are drawn on the photo; retouch replaces it; an old edit renders and keeps its spots | large |
| tone mapping (`tonemap`) | yes (C40, identical) | works | nothing | - |
| vibrance (`vibrance`) | yes (C40, identical) | works | nothing | - |
| zone system (`zonesystem`) | yes (C40, identical) | missing | the zone bar (graph) is its only control: not built | medium |

### hidden (no panel on desktop either; darktable sets them itself)

| module | renders as desktop |
|---|---|
| crop and scale input (`demosaicscale`) | yes (T1) |
| display encoding (`gamma`) | yes (T1) |
| mask manager (`mask_manager`) | yes (C40, identical) |
| overexposed (`overexposed`) | yes (T1) |
| raw overexposed (`rawoverexposed`) | yes (T1) |
| rotate pixels (`rotatepixels`) | yes (C40, identical) |
| scale into final size (`finalscale`) | yes (T1) |
<!-- p5a:tables:end -->

## Data files a module needs at run time

| data | needed by | in the APK? |
|---|---|---|
| lensfun lens database (`share/lensfun`, 4.2 MB, S1 report) | lens correction | yes |
| camera noise profiles (`share/darktable/noiseprofiles.json`) | denoise (profiled) | yes (whole `share/darktable` is packed); list shown per photo (C14) |
| white balance presets (`wb_presets.json`) | white balance "settings" list | yes; the list is not built in the app |
| watermark SVGs (`share/darktable/watermarks`) | watermark | yes; the list is not built in the app; fonts for text watermarks on Android not checked |
| ICC profiles (`share/darktable/color/in`, `color/out`) | input / output color profile | darktable's own yes; the user's own ICC files: no folder for them |
| LUT files (.cube, .3dl, .png) | LUT 3D | no: desktop takes them from a folder set in its preferences; the phone has none |
| PNG/PFM raster masks | external raster masks | no file chooser |
| spektrafilm data pack (film and paper profiles) | spektrafilm | yes (packed by the CI build, P2f) |
| another photo of the library | composite, color mapping | no |
