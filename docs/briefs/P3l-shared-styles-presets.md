# Brief P3l: styles, presets and module layouts shared through LPL (app side of R11)

Read `docs/PLAN.md` 3.9 and **4.5** first: 4.5 is the contract and you must not change it (CLAUDE.md rule 3). The LPL side (server, web UI, PC agent) is built at the same time on LPL's branch `shared-r11`; until it lands, test against a fake server in the tests. Mockups the owner approved (phone boards 1–4): described below; you do not need the link.

Branch `p3l-shared-styles-presets` from `main`.

## Deliverables
1. **Bridge** (`engine/include/dtbridge.h`, new `engine/src/shared.c`), on the app's own `data.db`:
   - `int dtb_shared_snapshot(char **json)` -> `[{kind, operation, op_version, name, hash, body}]` for every shared preset and style (4.5 "What is shared"; layouts come from the app, not from here). `hash` exactly as 4.5; must reproduce every vector in `docs/design/R11-hash-vectors.json` (feed the vectors' bodies through the same canonical/hash code in a bridge test).
   - `int dtb_shared_apply(const char *body_json, char **json)` -> `{"usable": true|false}`: writes a preset row (insert or replace on name+operation+op_version, `writeprotect = 0`) or replaces a style and its items, in one transaction, then refreshes whatever the engine caches (styles list, presets menus, style actions). A preset or style item whose module version is newer than the engine's module: not written, `usable: false`.
   - `int dtb_shared_delete(const char *kind, const char *operation, int op_version, const char *name)`.
   - REAL formatting `%.17g`, BLOB lowercase hex, columns by name (unknown body columns ignored).
2. **Layouts**: map the app's layouts (`layouts.json`, `LayoutCodec`) to and from a `layout` body (`operation` `modulegroups`, `op_params` byte for byte as desktop stores the string, `op_version` as desktop's modulegroups lib). Prove the bytes against a row desktop wrote (the built-in layouts extractor or a public desktop `.dtpreset` of a layout), with a test. Applying a layout adds or replaces it; it never changes which layout is active.
3. **Sync client** (`app/lpl`): `"shared"` in heartbeat caps when the server lists it in `server_caps`; per-item state on disk (`id`, `rev`, `local_hash`, `state`) as 4.5; uploads (PUT/DELETE) of local changes from the existing sync pass and the P3k service, respecting "auto sync off"; `GET /api/shared?since=` polling at most once a minute while the app is open and at each sync pass; `PUT /api/shared/state` after uploads and updates; 409 handling and `/resolve`; newest-wins policy sent in the state.
4. **UI** (theme tokens and icons only, CLAUDE.md rule 5), all three skins:
   - Photo list: a banner "n updates to styles, presets and layouts from <device names>" with **Review**; a dot on the settings button while something waits. After an update the banner says what was taken in and what kept waiting.
   - Review sheet: grouped Styles / Presets / Module layouts / Deleted elsewhere; per row name, new or changed, device, time, a checkbox; a conflict row with "changed here too ›" opening the conflict dialog; a greyed "needs a newer darktable" row; deletions with "Decide ›". Buttons Later and **Update n**. Nothing changes on the phone before Update.
   - Conflict dialog: name, both devices with their change times, **Keep this phone's / Take <device>'s / Keep both** (the other's copy arrives as "<name> (<device>)"), and "Same choice for the other conflicts in this update". No parameter values (they are not decoded; the mockup's value rows are dropped).
   - Deletion dialog: "Deleted on <device> · <when>", **Delete here too / Keep on this phone / Decide later**, with the line that a kept item stays on this phone only.
   - Settings › LightPhotoLibrary: "Share through LightPhotoLibrary" on/off, Styles / Your presets / Module layouts switches, "Same item changed on two devices: Ask me (default) / Newest wins", a "Now" row (n waiting, checked when) with Review, "Sent from this phone", and the existing import by hand.
5. **Tests**: hash vectors (C and Kotlin if both compute); bridge apply/delete/snapshot round trip incl. a newer module version refused; client against a fake server covering: first sync with identical items on both sides (no conflict), one-side change, conflict + each choice, newest wins, deletion offered + delete/keep, kept item changed again, rename; UI tests for banner, sheet, both dialogs in the emulator job.

## Not in this brief
PC agent and LPL server/web UI (LPL branch `shared-r11`). Darktable's built-in presets. Making a received layout active.

## Report
`docs/experiments/P3l-shared-styles-presets-REPORT.md` as CLAUDE.md says, with CI run ids (p2a, p2b) and any part of 4.5 that proved wrong in practice, as a proposal.
