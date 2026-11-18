# Brief S1: build the engine for Android in CI (includes S2 and S6)

Phase 0 experiment. Read `CLAUDE.md` and `docs/PLAN.md` (sections 2, 3.1, 5, 6) before starting. **Start only when the repository is public**, so the builds do not use up the owner's private build minutes.

## Question this experiment answers
Can our own recipes build darktable (the commit pinned in `upstream/darktable`, or `04477d3` if the submodule is not there yet) and everything it needs for Android, reproducibly in GitHub Actions, with no change to upstream source, and does the result actually process a RAW file on Android?

## Deliverables
1. **`deps/`**: one recipe per library with pinned version and checksum, build options, and NDK cross files. A single entry script builds the whole set for one ABI into a sysroot. ABIs: `arm64-v8a` (the phone) and `x86_64` (emulator tests).
   - Use the current LTS NDK; record its version. Minimum Android API level 29. Link everything with 16 KB page alignment.
   - Keep the set as small as darktable allows. Features to switch off in darktable: Lua, AI, G'MIC, camera support, map, print, colord, libsecret, kwallet, PortMidi, SDL2, and any optional image format whose library is costly; list what you switched off and why.
   - The Termux project builds darktable 5.6.2 with these libraries for Android (`https://github.com/termux/termux-packages`, for example `x11-packages/darktable/build.sh`). Use its recipes and patches as a reference for what each library needs on Android. Where you take a patch from there, keep its origin and licence in a comment.
2. **S6, GTK3 backend:** first try GTK3 with only the broadway backend, so no X11 libraries are needed. If that does not build or link in reasonable effort, use the X11 backend as Termux does, and record the size difference. darktable links GTK but the port never initialises it.
3. **S2, OpenMP:** build darktable with OpenMP on (Termux turns it off). If it cannot be made to work, say exactly what fails.
4. **Workflows in `.github/workflows/`:**
   - `deps`: builds the sysroot per ABI and publishes it as a release file named by the hash of `deps/`, and skips the build when that file already exists.
   - `engine`: builds darktable (library, module plugins, `darktable-cli`, data files) per ABI against the cached sysroot and publishes an archive.
   - `emulator-test`: starts an x86_64 Android emulator (API 33), pushes the x86_64 engine archive to `/data/local/tmp`, runs `darktable-cli` there on at least two public test images with their XMP (from `https://github.com/darktable-org/darktable-tests`), pulls the results and compares them with a reference made by a Linux build of the same commit. Report max and mean difference and PSNR. Also run once with 1 thread and once with all threads and report both times, to show OpenMP works.
   - Every workflow, also when it fails, writes its key logs and a short summary to a branch `ci-logs` (one folder per run), because the build workspace cannot read Actions logs through the GitHub API without signing in. Check first whether unauthenticated API access works for this public repository and say what you found.
5. **Write-up** `docs/experiments/S1-android-build.md`: library list with versions and sizes, total size of the engine per ABI (stripped), every patch to a dependency and why, whether zero patches to darktable held, build times, emulator results, and what remains uncertain for the real phone (Snapdragon 865, Android 13). End with a verdict: go, go with conditions, or no-go.

## Limits
- Do not modify `upstream/`. A needed change to darktable goes into `patches/` as a file with a reason and is flagged at the top of the write-up. Patches to third-party dependencies live with their recipe in `deps/`.
- No app, no JNI, no bridge work here.
- You may build single libraries in the workspace to shorten the loop (the NDK can be downloaded there; gnome.org is not reachable from it, GitHub mirrors are), but the proof is the CI run. The workspace has 2 cores; another agent may be using them.
- No secrets in workflows. Use only the default `GITHUB_TOKEN` for publishing release files and the `ci-logs` branch.
- Branch `s1-android-build`, report in `docs/experiments/S1-android-build-REPORT.md`, do not merge.
