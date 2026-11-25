# Brief P4c: leftovers from Phase 3 and 4 reviews

Branch `p4c-leftovers` from `main`. Read CLAUDE.md and the reports named below. Three independent items; each complete and tested before the next.

1. **Retouch: the uncropped view while retouching** (P3m report, brief item 4). As desktop, while retouch is the focused module the photo shows uncropped with the crop frame as a guide; retouch outlines and touch positions map onto that view. Reuse how P3a's crop tool and P3n's perspective panel switch the focus (pause/resume around LightPhotoLibrary renders so those are never uncropped). Tests: controller + screenshot in three skins + emulator check that a heal spot placed outside the crop area round-trips through the XMP and darktable-cli.
2. **Picker mark on curves** (strip-versions-picker report limits). rgb curve in linked mode: use the photo's actual working profile (as desktop's `picker_scale` with `dt_ioppr_get_pipe_work_profile_info`) instead of fixed Rec 2020 coefficients; apply rgb curve's zoom/offset and tone curve's log scale to the mark as the graphs do. JVM tests against values computed from upstream's formulas.
3. **diffuse or sharpen preset "lens deblur | medium" not found by name** (P4b report Q6): find why a built-in preset of diffuse is not found by its name on the emulator (localisation, `|` in names, presets list filtering, version) and fix it if it is an app bug; make sure the presets sheet lists and applies diffuse's built-in presets. Test that applies it by name.

## Report
`docs/experiments/P4c-leftovers-REPORT.md` as CLAUDE.md says, CI run ids, differences from desktop.
