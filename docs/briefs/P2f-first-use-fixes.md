# Brief P2f: fixes after the first real use

Phase 2. Read `CLAUDE.md`, `docs/PLAN.md` (sections 3.6 to 3.9 and 4, including requests R15 and R16), `docs/design/ui-spec.md`, and the reports of P2b, P2e (with its addendum) and P1c under `docs/experiments/`.

## What the owner saw on his phone
He opened a photo from LightPhotoLibrary (a 24 megapixel Sony ARW with an edit of 20 history items made on the PC), edited it, tapped "done", and it synced. Then:
1. Opening the same photo again from the app's list showed a **black picture for tens of seconds** before it appeared; the list's thumbnail was black too. The list said "synced · preview still to send" at that time.
2. Editing works but **lags** noticeably.
3. The **list screen** looks unfinished: four buttons per photo that do not fit ("re…" is cut off), uneven spacing. In the darkroom's top bar the title is squeezed to "MAR04261.…" by three buttons.
4. He wants to **see what is happening** while something syncs: a progress bar, in this app and in LightPhotoLibrary.
5. He wants a **record of which device edited a photo last and what was changed**, visible in both apps.

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
8. Tests for 2, 3, 6 and 7 (JVM and emulator), the workflow green, the APK published, owner instructions updated with what to look at and how to send the per-module timings.

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
