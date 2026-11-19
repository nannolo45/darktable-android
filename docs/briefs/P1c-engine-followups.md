# Brief P1c: engine follow-ups and slider colours

Phase 1 and 2 follow-up. Read `CLAUDE.md`, `docs/design/engine-api.md` (including both "Clarifications" sections), and the reports of P1a (`P1a-engine-core-REPORT.md`), P2b (`P2b-real-engine-REPORT.md` and `docs/experiments/P2b-results/`) and S5 (`S5-control-schema.md`).

## Why
Putting the real engine into the app showed a handful of things on the engine side that are wrong or missing. None blocks editing, but each shows up in use: a contract test fails, a zoomed-in area can differ slightly from the export, sliders that carry colour on desktop are grey in the app, and every preview render has a fixed cost that keeps dragging from feeling immediate.

## Deliverables
1. **Read-only sessions with an unknown module.** Today `history().end` can be larger than the number of items and `history_goto(end)` is refused (reproduction in `P2b-results/`). Make the history a read-only session reports consistent: every position up to `end` is an item (an item for a module the engine does not know carries its operation name and a flag saying so), and `history_goto` works for every position. The contract test `guardAndReadOnlySession` passes against the real engine; remove the app's clamp if it is no longer needed.
2. **`open(raw, NULL)` means the default edit.** Within one process it returns whatever the engine's private library still holds for that file from an earlier session. Opening without an XMP must start from the default edit every time; opening with an XMP must give exactly that XMP's edit. Find where the library keeps the state and clear it at the right moment (close or open); the app's tests no longer need one copy of the RAW per test.
3. **Zoomed regions equal the full render.** The region test T5 fails for a few values within 10 px of the image edge (one image) and for spektrafilm, whose result depends on the window it is given.
   - Find the cause of the image-edge case and fix it if it is in the bridge.
   - Give the bridge a way to use a larger internal margin when a module that needs one is switched on, with the reach taken from that module's own code (cite file and function). Apply it to spektrafilm if a margin can make the region equal the full render; if its result depends on the window in a way no margin fixes, show that with numbers.
   - What remains gets a written list of expected differences with bounds (image, region, module, maximum and mean), and T5 fails when a bound is exceeded or a new difference appears. The engine workflow is green when only listed differences within bounds occur.
4. **Slider colours.** The control schema records only how many colour stops a slider has. Extend the extractor to record each stop (position and colour) as desktop sets them when a module's panel is built, and to mark the sliders whose stops desktop recomputes while editing (find those callbacks upstream and list them). Regenerate `schema/ui-schema.json` with the extractor, never by hand, and state what else changed in the generated file. In the darkroom, a slider with stops shows the gradient in its track the way desktop's slider does, with marker and value still readable; recomputed gradients stay as extracted for now. Screenshot test with color balance rgb's "4 ways" tab.
5. **The fixed cost of a preview render.** P2b measured about 70 ms per preview render on the emulator whatever the picture's size. Measure on Linux with the three test images where the time of a small preview render after a late module change goes (darktable's own per-module timing output and timers in the bridge around its own steps), report the breakdown, and remove what the bridge itself causes (rebuilding or resynchronising more of the pipeline than the change requires, conversions, copies). Do not change upstream. Say what remains and why. Numbers are x86_64 numbers.
6. **Noise profiles with equal names** (P1a open question 9): `list_profiles` returns an index per entry and `set_profile` accepts it. The reviewer approves this contract change and the history change in 1; update `docs/design/engine-api.md` in this branch for exactly these two points and for item 2's wording.
7. **Tests and builds**: engine tests extended for 1, 2, 3 and 6; the engine builds for both Android ABIs; the real-engine app's workflow passes on your branch with the new engine, including the contract suite on the emulator (target: 18 of 18) and the comparisons with `darktable-cli`; JVM and screenshot tests pass.
8. Write-up `docs/experiments/P1c-engine-followups.md` and the pull request description `P1c-engine-followups-REPORT.md`.

## Limits
- No changes under `upstream/`; no patches. If something can only be fixed upstream, describe it precisely (file, function, a reproduction) so it can be reported there.
- `dtbridge.h` changes only as far as items 1, 3 and 6 require; list every change.
- Not part of this: blend, masks, presets, styles, layouts in the engine (P1b), graph editors, the LightPhotoLibrary client, `app/lpl-shell`.
- Screens stay within CLAUDE.md rule 5 (the gradient's colours are data from the schema; everything else uses tokens).
- No person's name, device model, personal photo or private link anywhere.
- Branch `p1c-engine-followups`, do not merge.

## Acceptance
- Engine workflow green with T5 strict on everything not on the written list.
- Contract suite 18 of 18 against the real engine on the emulator, or each failure explained first with numbers.
- The breakdown of the fixed render cost with before and after numbers.
- A new real-engine APK published from the branch for the owner, with the slider colours visible.
