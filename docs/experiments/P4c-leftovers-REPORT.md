# P4c: leftovers (retouch uncropped view, picker mark, diffuse preset by name)

Branch `p4c-leftovers`. Brief: `docs/briefs/P4c-leftovers.md`. No file under `upstream/` changed; the app reaches the
engine only through `dtbridge.h` (one new call, `dtb_retouch_focus`, and a new `"work"` member in `dtb_color_pick`'s
answer; engine-api.md 6.10 and 6.3).

**Said first:** all three items are done. Two limits: the app's curve graphs have no zoom/offset and no "scale for
graph" control, so the mark's transforms for them are built and tested but run as the identity (item 2); and on the
arw test RAW a heal spot gives no visible change at the place the bridge test uses, so the bridge test checks the
picture change with a clone spot and only reports the heal's (item 1, section 3).

## 1. What was asked and what is delivered

| Item | State |
|---|---|
| 1. While retouch is focused, the photo shows uncropped with the crop frame as a guide; outlines and touches map onto that view | done |
| 1. Reuse the focus switching of P3a/P3n; pause/resume around LightPhotoLibrary renders | done (`pause`/`resume` as P3n's; `DarkroomController.pausedFocusLocked` brackets both) |
| 1. Tests: controller, screenshots in three skins, emulator heal spot outside the crop through XMP and darktable-cli | done: `P4cRetouchFocusTest` (5), `PhoneP4cScreenshots` (3), emulator `P4cScenarioTest.r1` + p2b compare `p4c heal`; bridge test C37 |
| 2. rgb curve linked mode: the photo's working profile instead of fixed Rec 2020 | done: the bridge sends picker_scale's values computed by darktable's own functions with `dt_ioppr_get_pipe_work_profile_info` (any profile, also non-linear) |
| 2. rgb curve zoom/offset and tone curve log scale applied to the mark "as the graphs do" | partly: `CurvePick` applies `_curve_to_mouse` and `to_log` through `CurveView`; the app's graphs have neither control, so the view is the identity (open question 2) |
| 2. JVM tests against upstream's formulas | done: `P4cCurvePickTest` (6); bridge test C38 checks the bridge's values against the formulas for Rec 2020 and linear Rec 709 |
| 3. Why "lens deblur \| medium" was not found; fix if app bug; sheet lists and applies diffuse's built-ins; test applying by name | done: an app bug (section 4); `P4cDiffusePresetTest` (4), bridge C39, emulator `P4cScenarioTest.r2` |

### Item 1: how it works

- darktable at the pinned commit: while a module is `dev->gui_module`, the pipe skips every module after it whose
  `operation_tags()` meet its `operation_tags_filter()` (`imageop.c` `dt_iop_module_is_skipped`, used by
  `pixelpipe_hb.c` `_skip_piece_on_tags`, the distort transforms and the cache hash). Retouch's filter is
  `IOP_TAG_DECORATION | IOP_TAG_CROPPING` (`retouch.c` `operation_tags_filter`, "switch off cropping and decoration,
  we want to see the full image"); crop's tags include `IOP_TAG_CROPPING`. Crop then draws its box over the picture as
  a guide (`darkroom.c` "expose cropper", `crop.c` `gui_post_expose` with `dimmed`: outline only, no shading). The
  user manual's retouch page says the same: "you will be shown the uncropped image (possibly with the cropping
  rectangle overlaid as a guide)".
- The bridge's pipes are export pipes, for which darktable never skips, and the bridge keeps no `gui_module`. So
  `dtb_retouch_focus` keeps the focus in the bridge (`engine/src/retouch.c`), and `dtb__bypass_begin`
  (`render.c`), which every render, size, `dtb_image_info`, coordinate transform and picker already calls, also leaves
  out the modules `dt_iop_module_is_skipped` would skip (same test: after retouch, tags meeting the filter). Nothing
  in the edit, history or XMP changes; exports run darktable's own pipe. The answer has `left_out` and `frame` (the
  edit's corners taken back to the original and forward with the focus on).
- App (`Retouch.kt`): the panel opening takes the focus (`begin`, as a change so the picture's size and preview are
  read again), closing the panel or opening another module gives it back (`end`); LightPhotoLibrary renders on the open
  photo run between `pause` and `resume`; the uncropped preview is not stored as the filmstrip thumbnail (also for
  rotate and perspective's focus, which had the same gap). The frame is drawn read-only with the crop frame colour
  token, grid line width (`drawFocusFrame`). Outlines and touches needed no change: they already map through the
  engine's coordinates, which now follow the focus.

## 2. Reproduce

```
# bridge tests (Linux reference build, CI job g0-app/linux)
engine/tests/run_tests.sh <bridge-build> <dt-linux> <darktable-tests> <out> C37 C38 C39
cd app
gradle :darkroom:testDebugUnitTest --tests '*P4cRetouchFocusTest' --tests '*P4cCurvePickTest' --tests '*P4cDiffusePresetTest'
gradle :darkroom:testDebugUnitTest --tests '*PhoneP4cScreenshots'      # Robolectric (ran locally this time)
gh workflow run g0-app.yml           --repo nannolo45/darktable-android --ref p4c-leftovers
gh workflow run p2a-app.yml          --repo nannolo45/darktable-android --ref p4c-leftovers
gh workflow run p2b-real-engine.yml  --repo nannolo45/darktable-android --ref p4c-leftovers   # P4cScenarioTest, compare "p4c"
```

## 3. Results (x86_64 CI runners and the build workspace, 2 cores; not phone numbers)

| Workflow | Run id | Result |
|---|---|---|
| p2a-app | 45606255850 | success (all JVM and screenshot tests) |
| p2b-real-engine | 45610254507 | success: emulator 125/125 tests, compare `p4c heal` ok |
| g0-app (all bridge tests) | 45610250484 | linux: 497 passes, 1 failure (C37 arw, heal gives no change; changed in the next commit); apk success; emulator: see below |
| g0-app (C37 C38 C39) | 45620907355 | see below |

C37 (bridge, crop 0.25..0.75 freehand): cr2 cropped 1954x1301, focused 3908x2602 (image_info follows), frame
0.2500,0.2498 .. 0.7500,0.7498; raf 3125x2084 -> 6250x4168, frame 0.2499..0.7500; arw 2746x1836 -> 5492x3672, frame
0.2500..0.7500. Original 0.1,0.1: cropped -0.31,-0.31 (outside), focused 0.09..0.10 (inside, left of and above the
frame). Focused preview vs a render with crop bypassed: max 0 on all three. Pause 1954x1301 / resume 3908x2602, no
history item from begin/pause/resume/end. Heal outside the crop (focused preview): cr2 max 54, raf max 117, arw max 0
(both places flat at preview size: heal keeps the target's border and takes the source's detail); the change is now
checked with a clone spot. Round trip: the heal's centre and source read back from the XMP; darktable-cli of
`p4c_focus.xmp` vs the bridge: max 0.

Emulator (p2b 45610254507): r1 crop 0.3..0.8: 1954x1301 -> 3908x2602, frame 0.2999,0.2998 .. 0.7999,0.7998; tap at
0.1499,0.1499 of the whole picture, outline at the same place, original 0.1562,0.1556; after the panel closed the spot
is at -0.30,-0.30 of the edit's picture (outside); read back from the XMP: centre 0.1562,0.1556. App export vs
darktable-cli of the app's XMP: 1954x1301, PSNR 43.53, max 18 (threshold as the other editors). r2: 21 built-in
presets of diffuse listed, "lens deblur | medium" found by the shown name, applied by the sheet: items 11 -> 12,
iterations 16, radius 10, preview diff 0.8678.

C38: the pinned darktable's linear Rec 2020 working profile has the luminance row 0.2790405 0.6753473 0.0456121
(`matrix_in`, D50), not the textbook 0.2627 0.6780 0.0593 the app used; linear Rec 709: 0.2224818 0.7169127 0.0606055.
The bridge's luminance and middle-grey values equal the formulas within 1e-5 on all three RAWs.

C39: diffuse lists its built-ins as `_builtin_artistic effects | bloom`, ..., `_builtin_lens deblur | medium`, ...;
"lens deblur | medium" gives NOT_FOUND; `_builtin_lens deblur | medium` applies, one item, iterations 16, radius 10.

Locally: the full darkroom JVM + screenshot suite once had `P3aCropToolTest.dragAndMoveThenLeaveWriteOneHistoryItem...`
fail (12 items instead of 13) under load; it passed 3 times alone and in p2a. I take it for a timing flake; not
changed.

## 4. Upstream surprises

- `gui/presets.c` `dt_gui_presets_add_generic` stores darktable's own presets as `BUILTIN_PREFIX` + name, and
  `gui/presets.h` turns `_("…")` into `N_("…")`, so the stored name is `_builtin_lens deblur | medium` in every
  language. The P4b benchmark asked `dtb_preset_apply` for "lens deblur | medium" (no such row: Q6 of the P4b report).
  engine-api.md 5.2 already documented the prefix; the benchmark was the bug. It now finds the stored name from the
  shown one (`DtNames.storedName`) and applies the preset; the sheet always applied by stored name.
- `imageop.c` `dt_iop_module_is_skipped` (and `pixelpipe_hb.c` `_skip_piece_on_tags`) skip only on basic pipes
  (`dt_pipe_is_basic`); an export-type pipe, such as the bridge's, never shows the focused view on its own.
- darktable's linear Rec 2020 luminance row differs from the commonly quoted one (C38), so the earlier fixed
  coefficients were off even for the default working profile.

## 5. Where the app differs from desktop, and why

- The focus is taken when retouch's panel opens and given back when it closes or another module opens (desktop: the
  module's focus). Read-only photos get the view too (it changes nothing).
- The frame is drawn as a thin outline in the crop frame colour; desktop's dimmed crop frame is dashed. No dashes
  token exists; I did not add one.
- The curve mark: no zoom/offset and no "scale for graph" in the app's graphs (open question 2).

## 6. Open questions for the reviewer

1. The focus mechanism in the bridge is generic (any module's `operation_tags_filter`), but only retouch uses it. Liquify
   and rotate and perspective have the same filter on desktop; rotate and perspective has its own focus (P3n). Use it
   for liquify when that module comes?
2. Add rgb curve's zoom (desktop: ctrl+scroll) and tone curve's "scale for graph" slider to the app's graphs? The mark
   already follows `CurveView`.
3. The fallback when the engine sends no working-profile values (only the stand-in engine) still uses 0.2627 0.6780
   0.0593, which an existing test (`CurvePickTest.rgbCurveLinkedUsesTheRec2020Luminance`) pins. Change it to darktable's
   0.2790405 0.6753473 0.0456121 and that test's expected value with it?

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKoep891vjXMfqGv9Mc
