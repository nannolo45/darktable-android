# P1b, part 1: presets and styles in the engine, and the open engine points

The engine package the owner's next steps wait for (20 Nov 2026). Engine and bridge side, with the JNI and Kotlin
interface that goes with each call; **no interface (screens) in this package**. Branch `p1b-engine-presets-styles`
from `main`. Report `docs/experiments/P1b-engine-presets-styles-REPORT.md`, running log
`docs/experiments/P1b-engine-presets-styles.md` (pushed at least every 30 minutes). Another agent works on the app's
crop tool at the same time (`app/darkroom`, `ui`): keep out of screen code.

Read `CLAUDE.md`, `docs/design/engine-api.md` (all of it; section 3 is the outline this package makes exact),
PLAN.md 3.9, and the engine sections of these reports: P2f (section 7: E1, E2), P2g (section 5), P2h (section 0),
P2j (section 6 and open questions), P1c.

## Order of work
**First, within the first hour: the exact signatures and JSON** of every new or changed call, written into
`docs/design/engine-api.md` on the branch and pushed, so the reviewer reads them while you build. Then build in the
order below. Stop at a clean point if the package proves too large and say exactly what is built.

## Deliverables
1. **E1: a file without a decodable picture is refused at `dtb_open`** with `DTB_E_IO` and the loader's reason, and
   no render can start on an empty input (P2f report section 7, P2h report: `open` answers OK and `image_info`
   answers `DTB_E_ENGINE` today; a render of such a session hung and was followed by heap corruption on the
   emulator). Engine test with junk bytes, an empty file, a truncated real RAW (cut a public test RAW at several
   lengths) and a non-RAW picture darktable can read (which must keep opening). Say what a truncated RAW does. If the
   hang can be reproduced on Linux under a sanitizer, do it and say where the memory fault is; if not, say so.
2. **Presets** (per module, darktable's own database in the app's configuration folder, as desktop): list for a
   module (name, built-in or user, whether it applies automatically and to what, whether it fits the open image),
   apply to an instance (one history item, as desktop), store the instance's current settings as a preset, rename,
   delete, import from `.dtpreset` files, export to one. Behaviour as the manual's presets page.
3. **Styles**: list (name, description, the modules each holds), apply to the open photo with desktop's two modes
   (append and overwrite, as `dt_styles_apply_to_image` and the preference), import from `.dtstyle` files (single
   and several), delete, create from the open photo's history (all or chosen items). A style whose module the engine
   does not know or whose module version is newer is refused with a clear reason, never half applied. Tests with
   public style files and with styles written by darktable-cli's own build in CI; the render after applying a style
   through the bridge equals darktable-cli with `--style` for the same style within the usual bound.
4. **The default-edit difference** (P2j review): the app's export and darktable-cli's render of the app-written XMP
   of a *fresh default edit* differ by about one level on average (44 to 47 dB, 40 to 64 % of values; the test XMP
   gives 75 dB). Reproduce on Linux through the bridge: open the public test RAW without an XMP, write the XMP,
   export; compare with darktable-cli of that XMP. Find the module and the cause (something applied in the session
   that the XMP does not carry, or the reverse; an automatic preset; a parameter computed from the image), fix it in
   the bridge if it is there, and add the case to the engine tests. This is about the promise that an edit made on
   the phone looks the same on the PC: report it first if it cannot be closed.
5. **The calls P2j waits for** (its report, section 6): `colorin: list_profiles`; denoise (profiled)'s current
   profile; white balance's preset position and fine tuning; a way to write the values a module's own callback
   writes outside a field's limits (framing's "image" and "constant border", lens correction's "use latest
   algorithm": choose between the two options there and say why); `lens: list_lenses` with the smallest aperture.
6. **Threads and CPUs** (P2g report section 5, three points): a CPU is tested against what the process may use at
   the time of the call; `set_thread_cpus([])` restores that set; the thread count when the process starts with few
   CPUs allowed.
7. **E2: per-module times of the last render** as a call returning JSON, so the app need not read darktable's log;
   the app's timings page uses it (the only app change outside the Kotlin interface allowed here).
8. `FakeEngine` and the contract suite follow every new call (same tests against both implementations). Engine
   tests on Linux; the engine builds for both Android ABIs; the real-engine workflow green on your branch with the
   contract suite on the emulator. `engine-api.md` is the source of truth and is complete for everything built.

## Not in this package (part 2, after review of this one)
Blending and masks, mask display, `coords_transform`, histogram and colour picker data, photo duplicates, a demosaic
shortcut for the fit view (E3), a render option that bypasses one instance (if the crop package asks for it, it
will be added here by the reviewer's message).

## Limits and rules
No changes under `upstream/`; no patches without the reviewer's agreement (describe the need precisely instead).
`dtbridge.h`: list every change in the report. The LPL contract is not touched. `CLAUDE.md`. Nothing personal in the
repository (no names of people, phone or camera models, names of the owner's layouts, presets or styles, addresses,
private links); test styles and presets come from public sources or are made by the tests. SPDX line on new files.
Do not weaken a test. Never push to `main`, no pull requests. Report shortfalls first, plainly. Maven Central is
flaky in CI: rerun before suspecting the change.
