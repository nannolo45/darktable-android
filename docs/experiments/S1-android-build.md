# S1: building the darktable engine for Android (with S2 OpenMP and S6 GTK broadway)

Status: **in progress**. This file is the technical write-up for brief
`docs/briefs/S1-android-build.md`; the running log at the end lets a
successor continue from the repository alone.

## Running log

### 2026-11-18
- Branch `s1-android-build` created from `main`.
- GitHub API from the build workspace without a token of our own: `GET /repos/nannolo45/darktable-android/actions/runs`,
  `.../runs/<id>/jobs` and even `.../actions/jobs/<id>/logs` answer 200. The workspace's proxy evidently
  authenticates these requests itself (rate limit shown as 15000/h, other repositories are refused with
  "GitHub access to this repository is not enabled for this session"), so this is not anonymous access;
  an anonymous client gets job lists for a public repository but not job logs. Logs of a job that is still
  running are not available. The `ci-logs` branch is written by every job anyway, as the brief asks.
- NDK: r30 (30.0.16248370) is the 2026 LTS release according to the NDK wiki; pinned in `deps/config.sh`.
- First version of `deps/` (31 recipes), engine and emulator workflows pushed. Local smoke test on the
  workspace (x86_64 host, 2 cores, arm64-v8a target): ndk-zlib, proxy-libintl, libffi, pcre2, expat,
  libjpeg-turbo, curl, libarchive build; other sources cannot be downloaded from the workspace
  (GitHub archive URLs, gnome.org, sqlite.org and others are blocked here), so the CI run is the test.

- Run 44403959470: all 31 recipes reported "ok" in 6 minutes, which was wrong: `build-sysroot.sh` ran each
  recipe in a subshell inside an `if`, where bash ignores `set -e`. Fixed; a recipe that installs nothing
  now fails too.
- Run 44405594139: everything except gtk3 builds for both ABIs (glib tool paths fixed, sqlite targets,
  lensfun Python helper, cargo-c for librsvg). gtk3 broadway needs `shm_open` (absent in bionic).
- Run 44407305110: gtk3 builds with the two Termux broadway patches; **both sysroots complete** (31/31),
  release `deps-0a9f7c84ba9d`. darktable configures (OpenMP found) and compiles up to three files that use
  `strtok()`, which bionic marks deprecated while darktable builds with `-Werror`.

### Next steps
- Push, watch the `s1-pipeline` run through `ci-logs`, fix recipes until both sysroots build.
