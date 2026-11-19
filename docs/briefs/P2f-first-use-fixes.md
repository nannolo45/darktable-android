# Brief P2f: fixes after the first real use

Phase 2. Read `CLAUDE.md`, `docs/PLAN.md` (sections 3.6 to 3.9 and 4, including requests R15 and R16), `docs/design/ui-spec.md`, and the reports of P2b, P2e (with its addendum) and P1c under `docs/experiments/`.

## What the owner saw on his phone
He opened a photo from LightPhotoLibrary (a 24 megapixel Sony ARW with an edit of 20 history items made on the PC), edited it, tapped "done", and it synced. Then:
1. Opening the same photo again from the app's list showed a **black picture for tens of seconds** before it appeared; the list's thumbnail was black too. The list said "synced · preview still to send" at that time.
2. Editing works but **lags** noticeably.
3. The **list screen** looks unfinished: four buttons per photo that do not fit ("re…" is cut off), uneven spacing. In the darkroom's top bar the title is squeezed to "MAR04261.…" by three buttons.
4. He wants to **see what is happening** while something syncs: a progress bar, in this app and in LightPhotoLibrary.
5. He wants a **record of which device edited a photo last and what was changed**, visible in both apps.
6. He could not find **spektrafilm**.

## Deliverables
1. **Find the cause of the black picture and the wait, with evidence, before changing anything.** The reviewer's reading, to verify: after "done" the app renders LightPhotoLibrary's 1920 px preview through darktable's export on the one engine thread; with a heavy edit that takes long, and opening a photo waits behind it. Reproduce on the emulator with a heavy edit (an XMP using diffuse or sharpen, denoise (profiled), local contrast and the like on a public test RAW) and record the timeline (done → XMP posted → preview render start and end → open requested → first picture).
2. **Interactive work always goes first.** Opening a photo, and any render the user is waiting for, never waits behind background work. Background renders (the preview for LightPhotoLibrary, thumbnails) are cancelled or paused when the user needs the engine and are picked up again afterwards; they never run while the user is dragging. State how the engine thread orders work after your change.
3. **Never a black picture.** While a photo opens or renders, show the best picture already available (its stored thumbnail or last preview, scaled) with a clear, quiet progress indication that says what is happening in plain words (downloading, opening, rendering). The list and the filmstrip have a real thumbnail for every photo that was ever rendered (saved when the darkroom closes).
4. **Lag with a real edit.** Extend the timings page with a per-module table for the open photo's own edit (which modules take the time, at preview size and at drag size), using what darktable and the bridge can report, so the owner can send it. Measure the heavy edit from 1 on CI and report where the time goes. Do not change what the picture looks like to gain speed without the reviewer's agreement; propose options with numbers (for example a smaller drag picture when the edit is heavy, or what desktop darktable itself does in its own preview).
5. **The list screen and the darkroom's top bar**, designed rather than assembled:
   - One photo per row: thumbnail, file name on one line (shortened in the middle so the number at the end stays visible), one status line in plain words with the contract's states behind it, a thin progress bar while it downloads or syncs. Tapping the row opens the photo. One "more" control per row opens a sheet with: sync now, open in LightPhotoLibrary, remove from this phone. No row of four buttons.
   - Header: the server line (name of this device, connected or not, what is waiting) with one "sync now" for everything; the local section ("photos from this phone's files") keeps its explanation only while it is empty.
   - Darkroom top bar on the phone: back, title (file name shortened in the middle; second line the series or list title), "before", "done"; "more" becomes an icon button. The title must show at least the first letters and the last four characters of the name at 360 dp width.
   - Messages are short and correct in number ("1 photo arrived from LightPhotoLibrary", not "1 photo(s)").
   - Spacing, sizes and touch targets from `ui-theme` tokens as everywhere (rule 5); add screenshot tests for the list (empty, with photos in each state, with a download in progress) on phone and tablet, and for the top bar at 360 dp.
6. **Progress of a sync (R16, the app's side).** Every photo that is downloading or syncing shows its step and progress in the list and, for the open photo, in the darkroom. The heartbeat's working-set items carry `step` and `progress` as R16 describes, so LightPhotoLibrary can show the same.
7. **Who edited last and what changed (R15, the app's side).** XMP posts name this device (`agent` parameter of R15). The list and the darkroom's image information show the last edit as LightPhotoLibrary reports it ("last edited on Desktop · 18 Nov · exposure, color balance rgb"), and "edited here, not synced yet" for a local edit. If the server does not offer R15 yet, the line is simply absent; nothing fails. Also produce the small data file R15 needs: `schema/module-names.json`, operation name to the module's English name, generated from the control schema by a script (never by hand).
8. **Spektrafilm.** The owner looked for the module and did not find it working. Two things to establish and fix: where it appears (darktable's built-in layouts name it; the search line must find it; with the default layout it must be in the group desktop puts it in), and its data. On desktop the module fetches its film data on first use; the app cannot (PLAN.md finding on the data pack; curl is built without TLS). Find in upstream how the module locates its data, package the data with the app the way the other darktable data is packaged (state its size and licence), and show with a test that a photo edited with spektrafilm renders in the app as `darktable-cli` renders it (the public test set has a spektrafilm case).
9. Tests for 2, 3, 6, 7 and 8 (JVM and emulator), the workflow green, the APK published, owner instructions updated with what to look at and how to send the per-module timings.

## Limits
- No changes under `engine/` or `upstream/`. If the bridge needs something (for example per-module timings), describe it precisely for a follow-up and use what exists.
- Nothing is changed in LightPhotoLibrary here; R15 and R16 on its side are done separately. The client must work with a server that has them and with one that does not.
- Screens stay within CLAUDE.md rule 5; follow the darkroom's existing look.
- No person's name, device model, real server address, personal photo or private link anywhere. The owner's photo and camera are not to be mentioned; use the public test images.
- Branch `p2f-first-use-fixes`, do not merge, no pull requests.

## Acceptance
- The timeline of 1 before and after: opening a photo right after "done" shows a picture at once and the real preview without waiting for background work.
- Screenshot tests of the new list and top bar; the reviewer and the owner judge them.
- Workflow green on the last code commit, or failures explained first.

## Addendum, 19 Nov 2026 (evening): order of work and the owner's timings

**Order.** Do brief P2g (`docs/briefs/P2g-engine-start.md`) first, on its own branch, and push it for review. Branch `p2f-first-use-fixes` from that branch's tip.

**Timings from the owner's phone** (release `dt-android-a8bf82923d17`, engine package `0c316fb6b1234b4e`, 4 threads pinned to CPUs 4 to 7 of 8, Android API 33; a 50 MB X-Trans RAF, 6032 x 4032; the text does not say which edit the photo carried, the reviewer has asked):

| Step | ms |
|---|---|
| open / image info | 69 / 60 |
| first preview 1024 x 684 | 235 |
| preview again, nothing changed | 3.3 |
| preview after a late change (color balance rgb) | 133 |
| preview after an early change (exposure) | 167 |
| drag picture 320 px, 6 steps, median (min, max) | 17.1 (16.7, 81.4) |
| drag picture by size after an exposure step: 256 / 384 / 512 / 640 / 768 / 1024 px | 11.3 / 26.2 / 44.5 / 64.3 / 97.3 / 167.9 |
| fit of the drag cost | 21.7 ms fixed + 235 ns per pixel; 320 px chosen for a 50 ms target |
| **sharp region at fit, 1080 x 721, after a change** | **1295** |
| sharp region at 100 %, 1080 x 798, after a change | 196 |
| `modules()`: 185 301 bytes; bridge call / JNI string / Kotlin parse (medians of 5) | 11.5 / 1.0 / 16.0 |
| export full-size JPEG (11.9 MB) | 7118 |

**What the reviewer reads from them, to verify under deliverable 4:**
- Dragging and the 1024 px preview are quick on this photo. The slow step is the **sharp picture at fit: 1.3 s**, about seven times the 100 % region and eight times the preview of nearly the same pixel count. Fit is the view the darkroom opens in, so after every change the sharp picture arrives more than a second later; with a heavy edit, longer. This is a likely part of what the owner calls lag. Establish where the time goes (the whole sensor through the early modules and demosaic before scaling down is the reviewer's guess) and what desktop darktable does in the same view (which pipeline it shows first, what demosaic does when zoomed out). Propose options with numbers; anything that changes the picture or needs the bridge is a proposal, not a change.
- `modules()` costs about 28 ms per call on the phone (bridge plus parse). Establish how often the app calls it during and after a drag, and avoid calls that are not needed.
- The drag picture is 320 px wide. Say how that looks on a 1080 px wide screen against a larger one at a lower rate, with screenshots, so the owner can choose.
- The per-module table must show the pinning state of P2g, and the timings text should say how many history items the open photo has and which modules are on, so the next run answers the question the reviewer had to ask.
