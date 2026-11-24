# strip-versions-picker: tests and CI

Branch `strip-versions-picker`. The branch was built without tests at the owner's request; this adds them so it can merge.

## 1. What was asked and what is delivered

| Item | State |
|---|---|
| JVM tests for `CurvePick.mark` against darktable's `picker_scale` | done: `CurvePickTest` (9 tests) |
| Controller tests for the filmstrip badge (refresh, toggle, open a version of the same photo and of another photo) | done: `StripVersionsTest` (8 tests) |
| Screenshot/interaction test in all three skins (darktable, modern, l1) | done: `PhoneStripVersionsScreenshots` (3 tests) |
| CI p2a-app and p2b-real-engine green | done, see below |
| g0-app | not run: no file under `engine/` changed (`git diff --stat 12c20e838..HEAD -- engine` is empty) |

## 2. Reproduce

```
cd app
gradle :darkroom:testDebugUnitTest --tests '*CurvePickTest' --tests '*StripVersionsTest'
gradle :darkroom:testDebugUnitTest --tests '*PhoneStripVersionsScreenshots'   # needs Robolectric's Android jars (network)
```

## 3. Results

- `CurvePickTest`, `StripVersionsTest`: green locally (x86_64) and in p2a-app.
- `PhoneStripVersionsScreenshots`: green in p2a-app only (Robolectric could not fetch its jars on the build workspace). Screenshots `phone-strip-versions-{darktable,modern,l1}-{badge,expanded,opened-orig}.png`.

| Workflow | Run id | Result |
|---|---|---|
| p2a-app | 45479767963 | success |
| p2b-real-engine | 45479774778 | success |
| apk-only (push) | 45479698151 | success |

## 4. Check against upstream (pin 04477d32a00cc5abaea36d5bee084eb40c6eabb2)

- `tonecurve.c` `picker_scale`: L/100, a and b `(v+128)/256`, each clamped to 0..1. `CurvePick.mark` agrees; no code change.
- `rgbcurve.c` `picker_scale`: independent channels (`DT_S_SCALE_MANUAL_RGB` = 1) use the channel, linked (`DT_S_SCALE_AUTOMATIC_RGB` = 0) the working-profile luminance written to channel 0; "compensate middle grey" is `dt_ioppr_compensate_middle_grey` (Lab L/100 of the grey); results clipped to 0..1. `CurvePick.mark` agrees, including the enum values and the closed form of the middle grey for a linear profile. No code change was needed.
- Limits, not changed: the luminance uses fixed Rec 2020 coefficients (the default working profile), not the document's actual working profile; rgb curve's zoom/offset (`_curve_to_mouse`) and tone curve's log scale (`to_log`) are not applied in `CurvePick.mark`.

## 5. Surprises in upstream

None beyond the above.

## 6. Open questions

- Should the mark follow the document's working profile when it is not Rec 2020?
- p2b-real-engine does not start on push; both workflows were started with `gh workflow run`.
