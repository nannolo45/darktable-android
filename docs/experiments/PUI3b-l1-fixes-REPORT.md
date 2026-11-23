# PUI3b: l1 fixes

| Item | State | Notes |
|---|---|---|
| `HostAction.id` (optional, default null); set to `HostAction.EXPORT` on the app's export action (`MainActivity`); l1 picks export with `L1Model.exportAction` (by id) | done | Existing call sites unchanged. The old label-prefix lookup is gone. |
| Export button shows in the l1 top bar whenever the host offers export; not repeated in the photo menu in l1 | done | `L1Model.menuActions` drops it from the menu in l1 only; other skins keep it in the menu. |
| Screenshot test with the export button visible | done | Cause of the missing button in PUI3a screenshots: they passed no host actions. `show(...)` now takes `actions`; `phone-l1-01-rest` and `phone-l1-10-sideways` assert the button and the menu without a duplicate. |
| JVM test: L1 finds export by id | done | `PUI3aL1ModelTest.exportIsFoundByIdAndLeftOutOfTheMenu` (a label starting with "export" is not enough). |
| Sideways sections column: every cell >= 44 dp, scrolls vertically if they do not fit | done | Token `size.l1SectionCell` = 44 (tokens.json and l1-tokens.json); cells share the height but never go below it. |
| Test: token >= 44 | done | `TokensTest.l1SkinStacksOnModernAndEveryKeyIsAToken`. |
| Test: nine sections, all reachable | done | `SidewaysPhoneL1Screenshots.sidewaysNineSections`: scrolls to each section, asserts displayed and height >= 44 dp. The stand-in layout has six sections, so the test adds groups through `DarkroomController.setLayoutForTest` (internal, test hook). |

## CI (x86_64 runners)
- p2a-app run 45091755482 (commit 09326603): success.
- p2b-real-engine run 45091755578: see the final message / ci-logs.
- Earlier failed p2a-app runs: 45086567186 (size.l1SectionCell missing from tokens.json), 45090693520 and 45091171538 (test compile error; then the stand-in has only six sections).

## Screenshots (ci-logs `runs/45091755482-a1/p2a-app/screenshots/`)
`phone-l1-01-rest.png`, `phone-l1-10-sideways.png`, `phone-l1-11-sideways-nine-sections.png` (export button in the top bar, nine sections in the column).
