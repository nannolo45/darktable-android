# P1c: engine follow-ups and slider colours — pull request description

Branch `p1c-engine-followups` into `main` (main merged at 18e4231). Brief: `docs/briefs/P1c-engine-followups.md`.
Write-up with all numbers, causes and the owner's notes: `docs/experiments/P1c-engine-followups.md`.
Last code commit: **52daa9e**.

**Failures first.**
- **The real-engine app workflow is not green.** Run 44558499134 (52daa9e) fails in P2e's LPL scenario
  test `l3_unknownServerIsRefusedUntilAdded`: the app process crashed with
  `IOException: cannot replace state.json` in `app/lpl/.../ServerStore.kt` `atomicWrite` (line 149), called
  from `LplAgent.left` ← `AppHost.Source.left` ← `DarkroomController.closePhoto`; l4 to l6 did not run
  because the process was gone. It passed on main's run 44553848370. My reading (not changed, the LPL
  client is outside this brief): `LplAgent.left()` and `opened()` write the photo's `state.json` without
  the per-photo lock the other writers hold (`lockOf`), and `atomicWrite` uses one fixed `.tmp` name, so
  two concurrent writes can rename each other's file away; the faster renders of this branch may have
  changed the timing. Everything this brief asks of that workflow passed in the same run: contract suite
  **19 of 19** on the emulator (18 + the new `openWithoutXmpIsTheDefaultEditEveryTime`), scenario tests
  7 of 7, JVM and screenshot tests 161 (0 failed, 1 skipped), both darktable-cli comparisons, APK
  published. Seen once; not rerun.
- **Region test: not every region equals the full render.** T5 is strict and green, but through a written
  list of 35 expected differences (`engine/tests/t5_expected.txt`, numbers below): one on the X-Trans test
  edit (corner, max 11, cause upstream), one value pair on the arw edit on the 4-core runner only, and 33
  from modules switched on in the new module sweep (window-dependent modules, two of them by design).
- The first versions of this branch had a bug in item 2 (the default edit got history end 0); the
  emulator's contract suite caught it (run 44550790619, 5 failures); fixed in 08bd0de and covered by C8.

## 1. Brief item by item

| # | Item | State | Evidence |
|---|---|---|---|
| 1 | Read-only history: every position up to `end` is an item, unknown ones flagged; `history_goto` everywhere; contract test passes; app clamp removed | **done** | T6 on three guard XMPs × three images; `guardAndReadOnlySession` passes on the emulator (44558499134); clamp removed in `DarkroomController.renderBefore` |
| 2 | `open(raw, NULL)` = default edit every time; with XMP exactly that XMP; no RAW copy per test | **done** | C8 (7 states equal, default edit = darktable-cli on the RAW alone, max 0, three images); contract `openWithoutXmpIsTheDefaultEditEveryTime`; JNI suite uses one RAW |
| 3a | Cause of the image-edge case; fix if in the bridge | **done** (cause found; upstream, not fixable by a margin) | lens `_modify_roi_in_md` + Markesteijn window border; raf corner listed (max 11) |
| 3b | Larger margin for modules that need one, reach from the module's code; spektrafilm | **done** | `_region_margin`: tiling overlap of listed modules, cap 256; spektrafilm's difference was **RCD demosaic's colour filter phase**, not spektrafilm: fixed by phase alignment (arw regions exact) |
| 3c | Written list with bounds; T5 fails above a bound or on a new difference; engine workflow green | **done** | `t5_expected.txt`; g0-app 44558499084: 238 PASS, 0 FAIL |
| 4 | Stops recorded by the extractor; recomputing sliders marked and callbacks listed; schema regenerated; darkroom paints; screenshot | **done** | 114 sliders with stops, 56 recomputed; schema regenerated (nothing else changed); `SchemaTest.sliderColourStops`; screenshot `phone-24-slider-colours-4-ways` |
| 5 | Breakdown of the fixed cost; remove what the bridge causes; say what remains | **done** | synch_all on every change was the bridge's doing: cr2 320 px 39.2 → 10.4 ms (CI); what remains is module processing |
| 6 | `list_profiles` index, `set_profile` accepts it; engine-api.md for 1, 6 and item 2's wording | **done** | C4 (arw: two "DSC-RX100M3 iso 1600" profiles reachable by index) |
| 7 | Engine tests for 1, 2, 3, 6; both ABIs; real-engine workflow with contract suite and cli comparisons; JVM and screenshot tests | **partly**: all of it passes except the real-engine workflow's overall result (LPL l3, above) | runs 44558499084, 44558499134 |
| 8 | Write-up and this description | **done** | |

Acceptance:
- Engine workflow green with T5 strict on everything not listed: **met** (44558499084).
- Contract suite against the real engine on the emulator: **19 of 19** (44558499134).
- Breakdown with before and after: **met** (below; CI and workspace).
- New real-engine APK with the slider colours: **published**, release `dt-android-52daa9e6ea29`,
  https://github.com/nannolo45/darktable-android/releases/download/dt-android-52daa9e6ea29/dt-android-arm64-v8a.apk
  (31 018 678 bytes). The colours are verified in the Robolectric screenshot only, not on a phone.

## 2. How to reproduce

```
git clone https://github.com/nannolo45/darktable-android && cd darktable-android && git checkout p1c-engine-followups
git submodule update --init --depth 1 upstream/darktable
tools/build-engine-linux.sh <darktable source> /tmp/dt-linux
tools/engine-test/fetch-paths.sh /tmp/dtt images/mire1.cr2 images/xtransIV.raf images/hlrecovery.arw \
  0001-exposure/exposure.xmp 0146-lens-metadata-xtransIV-modversion-7/lens-metadata-xtransIV-modversion-7.xmp \
  0179-spektrafilm/spektrafilm.xmp
engine/tests/run_tests.sh /tmp/dt-linux/bridge-build /tmp/dt-linux /tmp/dtt /tmp/results        # or: T5 T6 C4 C8 C9
engine/tests/cost.sh /tmp/dt-linux/bridge-build /tmp/dt-linux /tmp/dtt /tmp/cost 3             # item 5
# item 3 diagnosis: one image, margin or modules changed
DTB_REGION_MARGIN=33 DTB_DEBUG_ROI=1 /tmp/dt-linux/bridge-build/tests/dtb_test /tmp/w region \
  /tmp/dtt/images/hlrecovery.arw /tmp/dtt/0179-spektrafilm/spektrafilm.xmp /tmp/out
DTB_TEST_ON=shadhi /tmp/dt-linux/bridge-build/tests/dtb_test /tmp/w region /tmp/dtt/images/mire1.cr2 \
  /tmp/dtt/0001-exposure/exposure.xmp /tmp/out
# item 4
tools/schema-extract.sh upstream/darktable <darktable build> <darktable prefix> /tmp/dtt/images/mire1.cr2 /tmp/s5
python3 -I docs/experiments/P1c-results/stop_callers.py upstream/darktable
# app (as P2b): JVM tests, rule check, APKs, emulator
cd app && $GRADLE --no-daemon -Pdta.screenshots=false :schema:test :engine-api:test :darkroom:testDebugUnitTest :checkScreenRules
```
CI: pushes to this branch run `g0-app` (engine, Linux bridge tests, render cost) and `p2b-real-engine`.

## 3. Results

**Final runs, commit 52daa9e** (GitHub runners, x86_64, 4 cores; logs on `ci-logs` under
`runs/44558499084-a1/` and `runs/44558499134-a1/`):
- g0-app **44558499084: success.** Linux bridge tests 238 PASS, 0 FAIL; engine `engine-04477d32-…` for
  both ABIs; G0 APKs; G0 emulator test (export vs darktable-cli max 26, mean 0.00092, 75.73 dB).
- p2b-real-engine **44558499134: failure (LPL l3 only).** JVM 161 tests, 0 failed; emulator: contract
  19/19, scenarios 7/7, LPL l1 and l2 passed, l3 crashed; comparisons: test XMP 75.73 dB (max 26), app-written
  XMP 74.44 dB (max 34); APK `dt-android-52daa9e6ea29` published.
- Earlier runs: 44550790624 (g0-app, dfbba47: 231/4, bounds fixed), 44550790619 (p2b, dfbba47: contract
  21/26, the end-0 bug), 44547867621 (p2b: Maven Central answered 429), 44540526494 and 44542074475
  (Android link without sqlite3; concurrent engine uploads).

**Item 5, render cost** (CI 44550790624, medians of 27 steps, ms; "before" = same binary with
`DTB_FORCE_SYNCH_ALL=1`):

| series (preview 320 px unless noted) | total before → after | pipe synchronisation before → after | modules | 8-bit |
|---|---|---|---|---|
| cr2, drag (late change) | 39.2 → 10.4 | 28.4 → 0.05 | 10.5 → 10.2 | 0.17 |
| cr2, sealed late changes | 39.2 → 10.4 | 28.5 → 0.05 | 10.5 → 10.2 | 0.17 |
| raf, drag (late change) | 43.1 → 14.5 | 28.5 → 0.05 | 14.4 → 14.2 | 0.16 |
| arw, drag (late change) | 38.7 → 9.7 | 28.7 → 0.04 | 9.7 → 9.5 | 0.16 |
| cr2, drag, 1024 px | 133.1 → 104.5 | 28.6 → 0.08 | 102.6 → 102.4 | 2.0 |

The final run gives the same (cr2 320 px: 38.9 → 10.4). Workspace (2 cores): about 70 → 22 ms. What
remains is module processing (cr2 320 px: color balance rgb 7 ms, output profile 2, input profile 1,
colour-space conversions 1; `-d perf`); the bridge's own steps are the change (0.5-1.5 ms) and the 8-bit
conversion. Emulator after the change: drag picture 320 px median 37.4 ms (run 44550790619; P2b 75.7 and
284 ms); scenario drag: 14-19 pictures in 20 steps at 864-1024 px (P2b: 1 at 736 px).

**Item 3, expected differences** (`t5_expected.txt`, CI numbers; every other comparison max 0):

| image | case | region | max | mean ≤ | cause |
|---|---|---|---:|---:|---|
| raf | test edit | corner | 11 | 0.0077 | lens `_modify_roi_in_md` + Markesteijn window border at the image edge |
| arw | test edit | bottom right | 1 | 0.0001 | 2 of 786 432 values, 4-core runner only; not analysed |
| cr2 | shadows and highlights | 0/1/2 | 2/1/1 | 0.0781/0.0731/0.0257 | recursive gaussian; margin 256 (was max 28) |
| cr2 | lowpass | 0/1/2 | 1 | 0.0020/0.0004/0.0003 | same (was 2) |
| cr2 | soften | 0/1/2 | 1 | 0.0004/0.0003/0.0002 | not analysed (was 23) |
| cr2 | bloom | 0/1/2 | 1 | 0.0010/0.0003/0.0005 | not analysed (was 37) |
| cr2 | denoise (profiled) | 0/1/2 | 1 | 0.0260/0.0406/0.0398 | not analysed (was 5) |
| cr2 | monochrome | 0/1/2 | 1 | 0.0099/0.0004/0.0003 | bilateral grid (was 3) |
| cr2 | surface blur | 0/1/2 | 1 | 0.0001/0.0001/0.0002 | rounding |
| cr2 | astrophoto denoise | 0/1/2 | 1 | 0.0002 | rounding |
| cr2 | local contrast | 0/1/2 | 9/4/5 | 1.6135/1.0405/1.5362 | bilateral grid; margin halves it (was 18) |
| cr2 | defringe | 0/1/2 | 81/92/92 | 0.2194/0.6026/1.4553 | statistics of the whole window |
| cr2 | global tonemap | 0/1/2 | 1/70/48 | 0.2162/63.7601/30.9887 | the window's largest luminance |

**Item 4, schema:** 114 sliders in 13 modules with stops; 56 sliders in 10 modules recomputed (exposure
1, graduated density 1, color calibration 11, rgb primaries 4, color harmonizer 4, color balance 3, color
equalizer 24, color balance rgb 4, colorize 1, split-toning 3). **Nothing else changed in the generated
file** (JSON comparison without the two new keys: 0 differences; two runs byte-identical). One run in seven
differed in lens' "corrections done" label, filled when the preview pipe finishes (timing, also before
this change); the committed run matches main there. Desktop recomputes the stops in `gui_changed` and
friends of color calibration, color balance rgb, color balance, color harmonizer, colorize, graduated
density, split-toning, rgb primaries, in color equalizer's `commit_params`, exposure's spot callbacks, and
white balance's `gui_update` (only with the "colored sliders" preference); full list with functions in
the write-up and `P1c-results/slider-stop-callers.txt`.

**Changes to `dtbridge.h`** (comments only; no function or signature): `dtb_open` (the edit a session
starts from), `dtb_history` (`unknown` on every item, `end ≤ items`), `dtb_history_goto` (every
position), `dtb_module_action` denoiseprofile (`index`, `set_profile {index}`), `dtb_render_region` (how
the window is chosen, listed exceptions). **`docs/design/engine-api.md`:** 2.3 `open`, 2.4 denoiseprofile
actions, 2.6 `history`/`history_goto`, 2.7 `render_region`, and a note that P1c fixed the P2b gaps.

## 4. Upstream surprises
- `src/iop/demosaicing/rcd.c` `rcd_demosaic()`: output depends on the Bayer phase of the window's origin
  (arw: max 21 / 68 between margins 32 and 33; PPG identical). Worth an upstream report; reproduction in
  the write-up.
- `src/develop/develop.c` `dt_dev_read_history_ext()`: drops rows of unknown modules, rewrites the library
  without them, keeps the stored `history_end`; and it rewrites the library's history on every load.
- `src/iop/lens.cc` `_modify_roi_in_md()`: demosaic's window starts exactly on the first needed pixel.
- `src/iop/filmicrgb.c` `tiling_callback()`: reports `2^scales` overlap also without reconstruction.
- `src/iop/lens.cc` `_have_corrections_done`: a panel label filled on preview-pipe completion makes the
  schema extraction timing-dependent.
- Desktop syncs the whole pipe for every new history item (`_dev_add_history_item_ext`); 28 ms of every
  render after a change on a 4-core runner.

## 5. Open questions for the reviewer
1. LPL l3 crash (above): a fix belongs in the LPL client (lock in `left`/`opened`, unique temp names in
   `atomicWrite`); rerun the workflow, or fix in a P2e follow-up?
2. The `TOP_CHANGED` rule for *new* items appended on top goes further than desktop (C9 shows equality on
   9 cases × 3 images, but a module whose commit reads another module's state could differ). Keep, or limit
   to merged changes as desktop (the first step of each drag would then cost ~28 ms again)?
3. Margin cap 256 px and the module list `_reach_modules`: a measured trade-off (accuracy vs render size).
   A larger cap makes shadows and highlights exact to 1 but zoomed regions up to four times larger.
4. The raf corner difference could be removed only by rendering a much larger window (about 5× pixels) for
   regions at the image edge with lens correction; not done. Acceptable as listed?
5. Leftover branch **`p1c-engine-followups-wip`** on GitHub (a backup of earlier commits of this branch;
   deleting it was refused by the workspace's git proxy): please delete it.

## 6. Other notes
- Nothing under `upstream/`; no patches. New dependency: none (sqlite3 was already darktable's; now linked
  explicitly). `engine.yml` builds one engine per tag and ABI at a time.
- Test-only environment variables in the bridge (`DTB_TIMING`, `DTB_DT_DEBUG`, `DTB_FORCE_SYNCH_ALL`,
  `DTB_DEBUG_ROI`); without them nothing changes.
- Measurements: CI x86_64 4 cores, workspace x86_64 2 cores (shared, noisy), emulator on CI. No phone.
- Not exercised: arm64 execution, a phone, regions at scales other than 1 and 0.5, 4-Bayer and monochrome
  raws, `set_profile {index}` from the app, recomputed gradients in the app, highpass in the T5 sweep (a
  max-128 outlier in 2 of 8 workspace runs, not reproduced on demand).
- Commit trailers name the model that wrote them (Claude Opus 5.5), as in P2b; the task asked for a
  different model name.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKsUkkMHro1EX1qvJEh
