# S1: building the darktable engine for Android (with S2 OpenMP and S6 GTK broadway)

Status: **in progress**. This file is the technical write-up for brief
`docs/briefs/S1-android-build.md`; the running log at the end lets a
successor continue from the repository alone.

## Running log

### 2026-11-18
- Branch `s1-android-build` created from `main`.
- Unauthenticated GitHub API from the build workspace: `GET /repos/nannolo45/darktable-android/actions/runs`
  answers 200 (through the workspace proxy, which only allows this repository). Whether job logs can be
  downloaded this way is checked once the first run exists. The `ci-logs` branch is used in any case.
- NDK: r30 (30.0.16248370) is the 2026 LTS release according to the NDK wiki; pinned in `deps/config.sh`.
- First version of `deps/` (31 recipes), engine and emulator workflows pushed. Local smoke test on the
  workspace (x86_64 host, 2 cores, arm64-v8a target): ndk-zlib, proxy-libintl, libffi, pcre2, expat,
  libjpeg-turbo, curl, libarchive build; other sources cannot be downloaded from the workspace
  (GitHub archive URLs, gnome.org, sqlite.org and others are blocked here), so the CI run is the test.

### Next steps
- Push, watch the `s1-pipeline` run through `ci-logs`, fix recipes until both sysroots build.
