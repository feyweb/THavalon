# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

The THavalon **role dealer**: a host creates a game, players join from their phones with a shared game ID, the server deals roles, and each player sees only their own card. Proposals, votes and missions happen at the table; the server never sees them. [README.md](README.md) covers the stack, deployment, configuration, the distribution table and the deliberate differences from the reference Python implementation. Read it for those; this file covers how the code fits together.

A companion phone tracker ([hw55/THavalon-tracker](https://github.com/hw55/THavalon-tracker)) records the play the server doesn't see. Issue #19 asks for a player-list export so it can load the seating.

## Commands

Java 21 and `mvn` on the `PATH` (no Maven wrapper is committed).

```bash
mvn test                                          # whole suite, incl. the 10,000-game simulation (what CI runs)
mvn test -Dtest=GameApiTest                       # one class
mvn test -Dtest=GameApiTest#hostCanChooseGameId  # one method
mvn test -Dtest='Dealer*,RoleInfoTest'            # several
mvn spring-boot:run                               # http://localhost:8080, data in ./data
docker compose up -d --build                      # production-shaped run behind Caddy (needs THAVALON_DOMAIN in .env)
```

CI (`.github/workflows/ci.yml`) runs `mvn -B test` on every PR and push, plus a `docker build` and a health-check boot on `main`.

## Architecture

Three layers, with dependencies pointing down only: `web` → `game` → `domain`, and `audit` used by `game`.

```
domain/  Role, RoleTable, Dealer, RoleInfo, Deal/Assignment/Seat/Team   pure Java, no Spring
game/    GameService, Game, Player, GameStore, GameState, GameException, ThavalonProperties
audit/   AuditLog, AuditEvent, AuditEventType                            append-only trail
web/     GameController, AuditController, Api (DTOs), ApiExceptionHandler, NoStoreFilter
static/  index.html, lobby.html, role.html, history.html, app.js, style.css
```

**`domain` is the game's rules and has no state.** `Dealer.deal(names)` returns a `Deal`: one `Assignment` per player (role, assassin flag, information lines) plus the public `firstProposer`. `RoleTable` holds the per-count pools as data; `RoleInfo` generates each role's information. Comments cite `thavalon.py` line numbers from the upstream implementation. Keep that habit when porting more rules.

**`GameService` is the only thing that mutates a `Game`, always under `synchronized (game)`.** The `Game`/`Player` classes are plain mutable beans relying on that lock. The lifecycle is two states: `LOBBY` (join, leave, host removes someone) → `start` → `DEALT` (locked; roles written onto each `Player`). There is no state after `DEALT`; the server can't tell when a game ends.

**`GameStore` is in-memory with a JSON mirror.** It's a `ConcurrentHashMap` keyed by the canonical (trimmed, `Locale.ROOT` upper-case) game ID. Every `save` writes `data/<ID>.json` via a temp file and atomic move. `reserve` uses `putIfAbsent`, so two hosts can't claim the same ID. Snapshots are reloaded on boot, and a scheduled sweep deletes games idle longer than `game-ttl`. Memory is authoritative; a failed snapshot write is logged, not thrown.

**`AuditLog` is separate from game storage on purpose.** Trails are JSON lines in `data/audit/<auditKey>.jsonl` and outlive the game. The `auditKey` is `<gameId>-<UTC timestamp>-<random>`, not the game ID, because host-chosen IDs like `FRIDAY` get reused weekly. `GAME_STARTED` carries every role, so reading a trail is gated by the **seal**: `GameService.auditFor` refuses until `audit-unlock-after` has passed since the deal (423 `AUDIT_SEALED`). A swept game counts as unsealed. The three durations constrain each other and are checked at startup in `ThavalonProperties` (the README explains why). Audit writes never fail a request.

**`web` is thin.** Controllers translate HTTP to `GameService` calls and wrap results in `Api` records. `Api.LobbyResponse` is public to anyone with the game ID; `Api.MeResponse` is the caller's own card, served only against their token. **Never return `Game` or `Player` directly**: they carry tokens.

### Identity and errors

- No accounts. Joining returns a random per-player token; the client sends it as `X-Player-Token`. The host is whoever holds `game.hostToken`, which passes to the next player if the host leaves the lobby. The browser keeps tokens in both `sessionStorage` (per tab, so several test players fit in one browser) and `localStorage` (survives closing the tab); see `app.js` `store`.
- Client-visible failures are `GameException` factory methods carrying an HTTP status and a stable `code` (`GAME_NOT_FOUND`, `BAD_TOKEN`, `NOT_HOST`, `ALREADY_STARTED`, `AUDIT_SEALED`, …). `ApiExceptionHandler` turns them into `{code, message}`. The pages branch on `code`, so add a new factory method rather than throwing a bare exception, and don't rename existing codes.
- Every response is `Cache-Control: no-store` (`NoStoreFilter`). A stale role card is the worst bug this app can have.

### API surface

`GET /api/health` · `POST /api/games` (create; body `{name, gameId?}`) · `POST /api/games/{id}/players` (join) · `DELETE /api/games/{id}/players/me` (leave) · `POST /api/games/{id}/remove` (host removes; name in the body) · `GET /api/games/{id}` (lobby, polled) · `POST /api/games/{id}/start` (host deals) · `GET /api/games/{id}/me` (own card; first fetch after the deal records `ROLE_VIEWED`) · `GET /api/audit` (unsealed past games) · `GET /api/audit/{id}`.

### Frontend

Four static pages and one shared `app.js` (`api()` fetch wrapper with the token header, `store`, `onClick` double-submit guard, `showError`), served by Spring from `src/main/resources/static`. There's no build step, no framework and no CDN: assets are same-origin only. The lobby polls; the role card renders once and then stops, so a screenshot always captures a complete card.

## Conventions

- Comments explain **why**, often naming the failure they prevent ("checking then saving would let two hosts…"). Match that density and tone; this codebase documents its reasons in place.
- Randomness: `SecureRandom` in production; `Dealer(Random)` takes a seeded `Random` in tests.
- Case-folding always uses `Locale.ROOT` (a Turkish-locale host breaks IDs otherwise).
- Fields added to `Game`/`Player` must cope with older snapshots on disk. `Game` is `@JsonIgnoreProperties(ignoreUnknown = true)`, and missing values are filled in by `GameStore.backfill`; add to it whenever a new field can't be null.
- Tests: `domain` tests are plain JUnit, parameterised over player counts; `SimulationTest` re-derives every player's information from the dealt roles *independently* of `RoleInfo`, and `RandomnessTest` runs chi-square checks for seat bias. `web` tests are `@SpringBootTest` + MockMvc with a temp data directory set via `@DynamicPropertySource`. A rule change needs both a direct test and the simulation to agree.

## Boundaries

- **Always:** keep `domain` free of Spring and I/O; keep tokens and other players' roles out of every response but the caller's own `me`; run `mvn test` before a PR.
- **Ask first:** changing the distribution table or any role's information (it's ported from upstream on purpose, and differences belong in the README's "Deliberate differences"); adding a dependency, a database, a JS build step or a CDN asset (the README calls these deliberate ceilings); changing an API response shape or error code that the pages rely on.
- **Never:** add a way to open an audit early (password, host override, flag). The seal is a clock so that no player can pull a lever mid-game.

## Known drift between docs and code

- `application.yaml` says *"The host pressing Reveal opens it immediately"* above `audit-unlock-after`. No host reveal has ever existed (the comment dates from the first commit, `722eaa0`, with no code behind it), and `GameService.auditFor` deliberately has no early unlock. The only control is the server-wide `thavalon.audit-unlock-after` duration (env `THAVALON_AUDIT_UNLOCK_AFTER` or `--thavalon.audit-unlock-after=…`, read at startup); `PT0S` opens every trail at the deal, which the API tests rely on. The "Reveal" in the pages is the role card's client-side cover, unrelated to the audit.

## Hosting without a persistent disk

Everything the server remembers lives in `THAVALON_DATA_DIR`. On hosts whose filesystem doesn't survive a restart (free tiers that also spin idle instances down), games in progress and all audit trails are lost on every restart, redeploy or spin-down, so "restarts are survivable" and **Past games** only hold with a persistent volume. Ideas for revealing roles without one are in #21.

## Contributing

Changes arrive as pull requests against `main`; CI runs `mvn test` on each.
