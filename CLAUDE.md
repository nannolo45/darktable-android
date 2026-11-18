# darktable-android: rules for coding agents

Read `docs/PLAN.md` first. It is the source of truth for architecture, the LPL contract (section 4) and the phases. Your task brief is in `docs/briefs/`.

## What this repository is
An unofficial Android port of darktable: upstream darktable's engine, unmodified, driven by a small C bridge (`engine/`) under a Kotlin/Compose app (`app/`). Edits are darktable XMP sidecars. The owner tests the app on a phone and is not expected to read code. Reviewer with the final say on merges: the supervising Claude session.

## Hard rules
1. **Never edit anything under `upstream/`.** It is a pinned git submodule. If upstream code blocks you, stop and describe the problem; a change may only go into `patches/` as a separate patch file with a written reason, and only after the reviewer agrees.
2. **The app talks to the engine only through `engine/include/dtbridge.h`.** No darktable internals above the bridge.
3. **Do not change the LPL contract** (PLAN.md section 4) or the repository layout (section 5). Propose changes in your pull request description instead.
4. **Never write to a RAW file.** Never rewrite an XMP that contains an unknown module or a newer module version (PLAN.md 2.1).
5. **No literal colours, sizes or icon drawings in screen code.** Use tokens from `ui-theme` and icons from `ui-icons` (PLAN.md 3.6).
6. **No secrets in the repository**: no passwords, tokens, server addresses or personal photos. Test images come from public test sets only.
7. Licence is GPL-3.0-or-later. New source files start with an SPDX line: `// SPDX-License-Identifier: GPL-3.0-or-later`.

## How to work
- One work package = one branch = one pull request. Branch from `main`, name it after the brief (for example `s3-bridge-prototype`). Do not push to `main` and do not merge your own pull request.
- If the `gh` command cannot open a pull request (it is not signed in on the build workspace), push the branch and put the pull request description in `docs/experiments/<brief name>-REPORT.md` on that branch. The reviewer merges.
- Commit in small steps with messages that say what changed and why.
- Every claim in a pull request must be backed by something a reviewer can run: a test, a script, or a log committed under `docs/experiments/`.
- **Report failures plainly.** Do not weaken, skip or delete a test to make it pass, and do not describe a partial result as done. If an acceptance test cannot be met, say so first in the pull request description, with numbers.
- Do not add dependencies, services or tools the brief does not call for without explaining why in the pull request.
- Keep build products out of git (`build/`, `out/`, `.gradle/`, `*.apk`, `*.so`, downloaded sources).
- Measurements taken on the build workspace are x86_64 numbers on 2 cores. Label them so; they are not phone numbers.

## Pull request description
1. What the brief asked and what you delivered, item by item (done / partly / not done).
2. How to reproduce: exact commands.
3. Results with numbers.
4. Anything in upstream that surprised you, with file and function names.
5. Open questions for the reviewer.
