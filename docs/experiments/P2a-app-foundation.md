# P2a: app foundation and darkroom on a fake engine

Brief: `docs/briefs/P2a-app-foundation.md`. Branch `p2a-app-foundation`.

## Running log (newest first)

- 2026-11-19: resumed after a server restart (session stopped for several hours). Committed so far: engine-api,
  schema (with `schema/overrides/`), ui-theme, ui-icons, each with JVM tests. Next: darkroom screen, app shell,
  screen-rule check, screenshot tests, CI workflow, documents.
- 2026-11-19: `engine-api` module: `DarktableEngine` (engine-api.md 1 and 2), JSON shapes, `FakeEngine`
  driven by `schema/ui-schema.json`; engine contract suite (test fixtures) passes on the fake (24 tests).
  Next: `schema` module (loader, visibility rules, overrides, groups), `ui-theme`, `ui-icons`, darkroom.
