# THavalon

[![CI](https://github.com/feyweb/THavalon/actions/workflows/ci.yml/badge.svg)](https://github.com/feyweb/THavalon/actions/workflows/ci.yml)

A role dealer for [THavalon](https://github.com/aquadrizzt/THavalon), Aquadrizzt's expansion of
*The Resistance: Avalon* in which every player gets a unique role.

Create a game, everyone joins from their own phone with a shared game ID, and each player sees
only their own card. The game itself — proposals, votes, missions, assassination — happens
at the table, exactly as it does with a deck of cards.

Supports **5 to 10 players**.

## Credit

THavalon — the roles, the distribution table, the information rules and the game itself — is the
work of **Aquadrizzt**, published at
**[github.com/aquadrizzt/THavalon](https://github.com/aquadrizzt/THavalon)**. It is in turn an
expansion of Don Eskridge's *The Resistance: Avalon*.

This repository contributes none of the game design. It is a Java rewrite of the reference
Python implementation's role dealer, and every role description and information rule is ported
from that project — see [`Role.java`](src/main/java/com/thavalon/domain/Role.java) and
[`RoleInfo.java`](src/main/java/com/thavalon/domain/RoleInfo.java), which cite the original line
numbers. Read the upstream README for the actual rules of play.

## Why this exists

The reference implementation is a Python script that writes one text file per player into a
`game/` directory. That means one machine, everyone taking turns at it, and secrecy resting on
nobody opening `game/DoNotOpen`. It also crashes: its lone-lover repair path calls
`random.sample()` on a `set`, removed in Python 3.11, and its replacement pool is empty at 6 and
10 players regardless of version. Measured 4 crashes in 40 ten-player runs on Python 3.13.

This is a faithful port of the role logic — same roles, same distribution table, same information
rules — with the crash fixed and the cards delivered privately. Where the two differ, the
differences are listed under [Deliberate differences](#deliberate-differences) below.

## Running it locally

Requires Java 21.

```bash
mvn test          # 123 tests, including a 10,000-game rule simulation
mvn spring-boot:run
```

The suite runs on every pull request. Beyond the usual unit tests it includes a simulation that
re-derives each player's expected information from the dealt roles independently of the code
that produced them, and chi-square checks that no seat is favoured for any role.

Open <http://localhost:8080>. To try it properly, join from a couple of private windows and a
phone on the same network.

## Deploying

Built for a single small VM. See [deploy/oracle-setup.md](deploy/oracle-setup.md) for a full
walkthrough on Oracle Cloud's Always Free tier.

```bash
echo "THAVALON_DOMAIN=thavalon.example.com" > .env
docker compose up -d --build
```

Caddy terminates TLS and gets a certificate automatically. The app is not published to the host,
so there is no way to reach it except over HTTPS.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `THAVALON_DATA_DIR` | `./data` | Where game state is written |
| `THAVALON_GAME_TTL` | `PT6H` | Idle time before a game is deleted |
| `THAVALON_DOMAIN` | — | Hostname for TLS (compose only) |

## How it works

- **Game IDs** are chosen by the host — 3 to 12 letters, digits or hyphens — so a group can just
  agree on `FRIDAY` and reuse it. Leave the field blank and the server generates a four-character
  one instead, drawn from an alphabet with no `O`, `0`, `I` or `1`: a random string has nothing to
  help you disambiguate it when read aloud, whereas a chosen word disambiguates itself.

  Either way, typing one is case-insensitive and tolerates stray whitespace — `HY6H`, `hy6h` and
  `  Hy6h  ` all reach the same game. An ID is only reserved while its game is live, so
  `FRIDAY` is free again once last week's game expires.
- **Roles are dealt with `SecureRandom`**, freshly per game. Nothing is cached anywhere — every
  response carries `Cache-Control: no-store`, because a stale role card is the worst bug this
  app could have.
- **Reconnect** works off a per-player token held in the browser. A locked phone or a closed tab
  reopens to the same card rather than erroring or joining twice. The token is written to both
  `sessionStorage` and `localStorage`: the former is per-tab, so several players can sit in one
  browser without overwriting each other, and the latter outlives the tab so reopening still
  finds you. Reads prefer the tab's own identity.
- **Restarts are survivable.** Games are mirrored to a JSON file per game and reloaded on boot, so
  a redeploy partway through a game does not wipe anyone's role.
- **The role card renders once and then stops** — no polling, no repaint — so a screenshot always
  captures a complete, stable card.

### Distribution table

Transcribed from the reference implementation, deliberately unchanged — including the two counts
its README flags as unbalanced (6 players get a 33% Evil share; 9 players get 4 Evil where
standard Avalon gives 3). It lives as data in `RoleTable`, so adjusting it is a one-line edit
that the existing tests still validate.

| Players | Evil | Newly *eligible* at this count | Good draw | Evil draw |
|---|---|---|---|---|
| 5 | 2 | Nimue | 3 of 6 | 2 of 3 |
| 6 | 2 | — | 4 of 5 | 2 of 3 |
| 7 | 3 | Arthur, Titania | 4 of 7 | **3 of 3** |
| 8 | 3 | Agravaine | 5 of 7 | 3 of 4 |
| 9 | 4 | — | 5 of 7 | **4 of 4** |
| 10 | 4 | Colgrevance | 6 of 7 | 4 of 5 |

Merlin, Percival, Tristan, Iseult, Lancelot, Mordred, Morgana and Maelagant are eligible at every
count.

Becoming eligible is not the same as appearing. Roles are drawn from the eligible pool, so Nimue
turns up in about half of 5-player games, Arthur and Titania in a little under 60% of 7-player
games, and Colgrevance in 80% of 10-player games.

The exceptions are worth knowing at the table: at **7 and 9 players the Evil pool exactly fills
the Evil seats**, so every Evil role is in play, every game, and the composition is common
knowledge — the only question is who holds what. Elsewhere there is real uncertainty about which
Evil roles even exist.

Tristan and Iseult always appear as a pair or not at all. At 6 and 10 players the pair is
unavoidable, because there are not enough non-lover Good roles to fill the seats without them.

## Deliberate differences

Everything about the roles matches the reference implementation. These are the places this
rewrite knowingly departs from it:

| | Reference | Here |
|---|---|---|
| **Lone-lover repair** | Crashes when the replacement pool is empty — 6 and 10 players always, and `random.sample()` on a `set` fails at every count on Python 3.11+ | Falls through to the upgrade-to-a-pair branch, which is always viable |
| **First proposer** | Nominated at random and written to `game/start` | Not nominated; the table decides for itself |
| **Delivery** | One text file per player, plus `game/DoNotOpen` holding every role | Per-player web view behind a private token; no file anyone can peek at |
| **Guinevere** | Referenced in a source comment, never implemented | Also absent |

## Layout

```
src/main/java/com/thavalon/
├── domain/   Role, RoleTable, Dealer, RoleInfo — pure, no Spring, heavily tested
├── game/     Sessions, tokens, persistence
└── web/      REST API and static pages
```

The rules live entirely in `domain/` and can be exercised without starting a server.
