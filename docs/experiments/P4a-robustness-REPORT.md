# P4a: robustness (interrupted downloads and syncs, low memory, local crash log)

Branch `p4a-robustness`. Brief: `docs/briefs/P4a-robustness.md`. No file under `upstream/` changed; the app reaches the
engine only through `dtbridge.h` (two new calls, `dtb_memory_info` and `dtb_memory_trim`, engine-api.md 10). The LPL
contract (PLAN.md 4) and the repository layout are unchanged; LightPhotoLibrary was only read and run locally.

## 0. Said first

- **A bridge test passed while it printed failures, once.** The first CI run of C34 (run 45547424824) printed
  `FAIL … undo after the trims undoes the change` on arw and raf but was counted PASS: `cmd_memory` returned 0 instead
  of `g_fail`. The cause of the failure itself was the test: it set exposure to +0.7 EV, which the default edit of those
  two photos already has, so there was nothing to undo. Fixed in the test (current value + 0.5 EV, undo must bring the
  value back; failures returned). The final result is in section 3.
- **The first emulator memory numbers of the image cache were wrong (0 MB)**: upstream counts `mip_full` and `mip_f`
  in buffers, not bytes (`dt_mipmap_cache_init`: cost 1 per entry, quota = worker threads + 1). `dtb_memory_info` now
  sums the entries' `data_size` and reports `_entries` and `_quota` as well.
- **LPL answers `Range` (R4 holds).** LightPhotoLibrary main, Starlette 1.7.0, `FileResponse` in
  `server/lpl/api.py` `original()`: `Range: bytes=n-` → 206 with `Content-Range`, a matching `If-Range` (its ETag) → 206,
  a stale `If-Range` → 200 with the whole file, a start past the end → 416. LPL gives no checksum in `/checkout`.
- **Two duplicates on the server were possible before this package and are fixed** (section 4): an export whose answer
  was lost was stored twice (`name (2).jpg`), and a new version whose post's answer was lost became a second version
  (the repeat met `409 version_exists` and was renumbered).
- Local runs on the build workspace are x86_64 on 2 cores; the emulator is x86_64 too. **No number here is a phone
  number.**

## 1. Item by item

| Brief item | State |
|---|---|
| 1. Resumable downloads: partial file, `Range` resume, check before use, never a partial file in the engine; R4 checked against LPL | **done.** Resume with `Range`/`If-Range` existed since P2e (`LplClient.downloadOriginal`). Added: the finished file is checked (size from `/checkout`, and SHA-256 when `/checkout` gives `sha256`) before it is moved into place (`DownloadCheck`, `LplAgent.download`); one more full download on a mismatch, refused after the second; with no known size the answer's own length decides; a server that ignores `Range` gets a whole download again (said in the log); the part file is flushed every 4 MiB. R4: LPL answers `Range` (above). LPL gives no checksum: request in section 5 |
| 2. Interrupted syncs: every upload safe to repeat, backoff, never duplicated, never `done` before the server confirmed; a killed process resumes; honest sync bar | **done**, see section 4. Edits (XMP, version 0) and previews were already idempotent and confirmed; exports and new versions were not (fixed); shared items: LPL already treats a repeated PUT and DELETE as no change (stub aligned). Exports are queued on disk before they are sent. A safety-net background job runs if the process dies during the sync service. The list's server line and the sync notification say "waiting for connection" (no network) and "retrying" |
| 3. Low memory: `onTrimMemory`/`onLowMemory`, drop caches and thumbnails, keep the edit; out-of-memory render fails gracefully; emulator numbers | **done.** Bridge `dtb_memory_trim` level 1/2 and `dtb_memory_info`; `RealApp.onTrimMemory` → `MemoryTrim.plan` → `DarkroomController.trimMemory` (engine + far thumbnails, which come back from storage in the foreground). A render out of memory (engine or Java buffer) gives memory back once and tries again, then fails with a message, edit kept, logged. Numbers in section 3 (x86_64). What the phone must confirm: section 6 |
| 4. Local crash log: uncaught Kotlin exceptions before the process dies; native crashes and ANRs from `ApplicationExitInfo` on the next start; bridge errors shown; capped; Settings › About › crash log with copy and clear; nothing sent | **done.** `CrashLog` (JSON lines in `files/logs/crash-log.jsonl`, 200 entries / 1 MB, fsync per entry, torn lines skipped), `ExitReasons` (native crash with signal and the readable strings of the tombstone, ANR with the main thread's trace, low-memory kill, excessive resources, failed start; once each), bridge errors the darkroom shows, failed engine starts, errors caught in background work, renders out of memory. About › "crash log" (also on the start page when the engine did not start). Nothing is sent |
| 5. Tests | **done**: JVM downloads cut at 1-4 random points in 40 downloads plus a killed agent resuming; emulator scenario that cuts a transfer (server gone mid-file) and resumes, then opens the RAW in the engine; uploads repeated after lost answers (stub) leave one result; onTrimMemory levels keep the edit (JVM controller, bridge C34, emulator); uncaught exception and simulated exits in the log (Robolectric); crash log screen in three skins. Not done: uploads repeated against the real LPL with lost answers (the real server cannot drop an answer without a proxy; its code was read instead, section 4) |

## 2. How to reproduce

```
# JVM (build workspace)
cd app
gradle :lpl:test --tests 'app.dtandroid.lpl.P4a*'
ANDROID_HOME=<sdk> gradle :darkroom:testDebugUnitTest --tests '*P4a*'      # Robolectric + screenshots (needs the
                                                                             # Robolectric android-all 35 jar)
# against a local LightPhotoLibrary (read-only checkout at /home/claude/lpl, branch main)
cd /home/claude/lpl/server && PHOTO_ROOT=/tmp/lplphotos DATA_DIR=/tmp/lpldata PORT=18791 python3 -m lpl.main &
head -c 15000000 /dev/urandom > /tmp/lplphotos/2024/TEST0001.RAF; curl -X POST http://127.0.0.1:18791/api/scan
curl -s -D - -o /dev/null -H 'Range: bytes=1000-' http://127.0.0.1:18791/api/photos/1/original   # 206
cd app && gradle :lpl:test --tests '*RealServerRun' -Pdta.lplUrl=http://127.0.0.1:18791 \
   -Pdta.lplLog=../docs/experiments/P4a-results/real-lpl-run.txt
# CI
gh workflow run g0-app.yml           --repo nannolo45/darktable-android --ref p4a-robustness   # bridge C34
gh workflow run p2b-real-engine.yml  --repo nannolo45/darktable-android --ref p4a-robustness   # JVM + emulator P4aScenarioTest
gh workflow run p2a-app.yml          --repo nannolo45/darktable-android --ref p4a-robustness   # JVM, screenshots
```

Logs: `ci-logs` branch, `runs/<run id>-a1/` (g0-linux/<camera>/C34.log and bridge-tests-summary.txt,
p2b-emulator/p2b-test/p4a-results.txt and instrument-summary.txt, p2a-app/screenshots/*p4a*).

## 3. Results with numbers

CI_RESULTS_PLACEHOLDER

## 4. Uploads: what can be repeated, and what was changed

| Upload | After an interruption | Before P4a | Now |
|---|---|---|---|
| XMP, version 0 (`POST /xmp?checkout_ts`) | repeated by the client (2 more tries, 0.5 s / 2 s) and by the next sync pass | the same file is stored again; LPL merges the edit event of the same device on the same day (`edits.py`, `saves+1`); synced only after the answer | unchanged; test: three lost answers leave the photo waiting, the fourth post confirms, the server has the edit |
| Preview (`POST /preview`) | repeated | overwritten: one preview | unchanged; test |
| New version n (R17) | repeated | **the repeat met `409 version_exists` (the server had stored the first post) and the phone renumbered its file to n+1 and posted it: two versions** | the post is noted on disk (`VersionState.posting`) before it starts; a 409 for a noted number whose server copy carries the same darktable history (`GET /xmp?version=n`, `XmpHistory`) is this phone's own post: checkout (LPL notes the number) and the same number again. A number another device took meanwhile is still renumbered (P3f). Tests: lost answer, app killed between post and answer, another device's version |
| Export (R18) | repeated whole (no resumable upload) | **a lost answer meant a second export `name (2).jpg`**; an app killed during the upload lost the export (it was queued only after a failure) | the photo's export list is read and the file queued with it before the upload; after a lost answer, and when a queued export is taken up again, the list is checked for an export from this device with the same version, size and name (or LPL's `name (n).ext`) that was not there before and is not claimed by another upload of this device; found = sent. Tests: lost answer, killed after the upload, two exports the user asked for stay two |
| Shared items (R11) | repeated by the next pass | LPL: a repeated PUT with the same hash changes nothing; a repeated DELETE of a deleted item is no conflict (`server/lpl/shared.py`) | unchanged; the stub now answers DELETE as LPL does; test: PUT and DELETE each repeated after a lost answer, one revision each |

Kept on disk across a killed process: edits (status read back as waiting), queued exports with their list, the
posting mark of versions, partial downloads (`.part` + `.etag`). While the sync service runs, a second request for the
background job (`SyncJob.scheduleSafetyNet`: network, at least 10 minutes later, persisted) takes the work up if the
process dies; it is cancelled when the service ends with nothing left. A batch download (P2k) still ends with the
process (LPL shows the job interrupted, as PLAN.md 4.1 says); its partial file is resumed when the photo is taken again.

Words: the list's server line (`LinkWords.connection`): "connected", "retrying (attempt n)" while a call is repeated,
"waiting for connection" when Android reports no network and something waits, "LightPhotoLibrary is not reachable right
now · retrying" while work waits and the app tries again by itself. The sync notification adds "waiting for
connection", "retrying" or "server not reachable · retrying" to its second line.

## 5. Surprises, and what LPL could add

- darktable poisons `fopen` (`src/common/poison.h`); the bridge reads `/proc/self/statm` with GLib.
- The image cache's levels count differently (`common/mipmap_cache.c` `dt_mipmap_cache_init`: thumbs in bytes with a
  quota from the resource level, about 1.9 GB on the CI runner and 612 MB on the emulator; full and f in buffers, worker
  threads + 1).
- `dt_cache_gc` expects the cache's lock held (`common/cache.c`, called from `dt_cache_get` under it); the bridge takes it.
- The JDK's `HttpURLConnection` repeats a GET or DELETE by itself once when the connection closes without an answer
  (seen in the tests: two lost answers were needed for one failed call). Android's implementation (OkHttp) has its own
  retry rules; the app's logic does not depend on either.
- **LPL request (not done, not needed for correctness):** `sha256` of the original in `/checkout` (the app checks it when
  present, `Checkout.sha256`); and an idempotency key for `POST /exports` (for example `client_id=<uuid>`, answered with
  the existing export when repeated), which would replace the app's list comparison.

## 6. Open questions and what needs the owner's phone

1. **Memory on the phone** (Snapdragon 865, 12 GB, Android 13 ColorOS): darktable sizes its caches from the device's
   memory; the thumbs quota and the pipelines' cache limit will be larger than on the emulator. Please open a large RAW,
   zoom in, then switch to another app for a minute and back: the photo must still show the edit, the first render after
   that may take about a second longer (level 2 reads the RAW again). The crash log should then show nothing new.
2. **Downloads**: take a photo from LPL, switch Wi-Fi off in the middle, on again, open it: it continues (the list says
   "waiting for connection" while off). Same with the app swiped away in the middle.
3. **Crash log**: Settings › About › crash log should be empty on a phone that never crashed; after a crash it lists it
   at the next start. ColorOS may record kills of its own as "low memory" (`REASON_LOW_MEMORY`); please send a copy if
   entries appear without a visible crash.
4. Reviewer: is reading the server's export list (one extra GET per export) acceptable until LPL has an idempotency
   key, or should the app wait for that?
5. Reviewer: `MemoryTrim.plan` frees the open photo's decoded RAW on RUNNING_CRITICAL while the app is on the screen
   (the next render is slower). Desktop has no equivalent; is that the right trade?

🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01SnXMKoep891vjXMfqGv9Mc
