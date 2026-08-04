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

This is a port of the role logic — same roles, same distribution table, same information
rules. Where the two differ, the differences are listed under [Deliberate differences](#deliberate-differences) below.

## How it's built

| Layer | What it is |
|---|---|
| Language | Java 21 — virtual threads enabled, so a request per player costs almost nothing |
| Framework | Spring Boot 3.3, with only `starter-web`, `starter-validation` and `starter-test` |
| Build | Maven, single module. No wrapper is committed, so `mvn` must be on your `PATH` |
| Frontend | Four hand-written HTML pages, one `app.js`, one `style.css` |
| Storage | One JSON file per game on disk. No database |
| Container | Multi-stage Docker — `maven:3.9-eclipse-temurin-21` builds, `eclipse-temurin:21-jre-alpine` runs, as a non-root user |
| Edge | Caddy 2, for automatic TLS. The app is never published to the host |
| CI | GitHub Actions: `mvn test` on Java 21, then a `docker build` |

**There is no Node, npm or JavaScript build step anywhere.** The frontend is plain HTML, CSS and
browser JavaScript in [`src/main/resources/static`](src/main/resources/static), served directly by
Spring Boot — no bundler, no `package.json`, no `node_modules`, no framework. It also loads nothing
from a CDN: `app.js` and `style.css` are the only two assets the pages pull, both same-origin. So
the whole app is one `mvn` build producing one jar, and the browser gets the files as written.

That is a deliberate ceiling on complexity rather than a limitation to route around. The client's
entire job is to show one player their own card and poll a lobby, which needs no framework — and
avoiding the second toolchain is what keeps the container a single build stage and the deploy a
single `docker compose up`.

Persistence is files for the same reason: a game is a handful of role assignments that must survive
a restart mid-game, which a JSON file per game does, and a database would mean another container to
run and back up on a 1 GB VM.

## Running it locally

Requires Java 21, and `mvn` on your `PATH`.

```bash
mvn test          # includes a 10,000-game rule simulation
mvn spring-boot:run
```

The suite runs on every pull request. Beyond the usual unit tests it includes a simulation that
re-derives each player's expected information from the dealt roles independently of the code
that produced them, and chi-square checks that no seat is favoured for any role.

Open <http://localhost:8080>. To try it properly, join from a couple of private windows and a
phone on the same network.

## Deploying

Built for a single small VM. The steps below take you from a bare Ubuntu server to a running,
HTTPS-only instance and work on any provider. For provider-specific gotchas — free-tier shapes,
firewall quirks, staying inside the free allowance — see
[deploy/oracle-setup.md](deploy/oracle-setup.md) (Oracle Cloud) and
[deploy/gcp-setup.md](deploy/gcp-setup.md) (Google Cloud).

Nothing is installed on the host but Docker: the app compiles inside the container, and Caddy
terminates TLS and obtains a Let's Encrypt certificate on its own. The app is never published to
the host, so the only way in is over HTTPS through Caddy — TLS cannot be bypassed.

### What you need

- A VM with at least 1 GB of RAM running Ubuntu 22.04 (arm64 or x86 — the image builds natively
  either way, so there is no `--platform` flag to remember).
- A hostname pointed at the VM. Caddy needs one to get a certificate; an IP address will not do.
  Use a domain you own, or register a free `something.duckdns.org` at
  <https://www.duckdns.org> in about a minute.
- Inbound TCP ports **80 and 443** open to the world. Port 80 is required — Caddy uses it for the
  certificate challenge and to redirect to HTTPS.

### 1. Point a hostname at the VM

Add a DNS `A` record for your hostname pointing at the VM's public IP. If the IP can change (many
clouds hand out ephemeral IPs that move when the VM restarts), reserve a static one first so DNS
does not break on the next reboot.

### 2. Open ports 80 and 443

Open both in your cloud provider's firewall / security group, source `0.0.0.0/0`, protocol TCP.

Some images (notably Oracle's Ubuntu) *also* ship restrictive local `iptables` rules that must be
opened separately — opening only the cloud firewall leaves the port silently unreachable. See
[deploy/oracle-setup.md](deploy/oracle-setup.md#2-open-the-ports--in-both-places) for that case.
Verify from your laptop before continuing: `nc -vz <vm-ip> 80` should connect.

### 3. Install Docker

```bash
sudo apt-get update && sudo apt-get install -y ca-certificates curl git
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
```

Log out and back in so the group membership applies (`docker ps` should then work without `sudo`).

### 4. (Only on a 1 GB VM) add swap

The image compiles with Maven inside the container, which needs more than 1 GB. On a 1 GB shape,
add swap first or the build is OOM-killed partway through:

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

On such a shape also cap the heap explicitly — in `compose.yaml`, under the `thavalon` service:

```yaml
environment:
  JAVA_OPTS: "-Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
```

Skip this whole step on a VM with 2 GB or more.

### 5. Deploy

```bash
git clone <your-fork> thavalon && cd thavalon
echo "THAVALON_DOMAIN=something.duckdns.org" > .env
docker compose up -d --build
```

The first build takes a few minutes — it compiles inside the container — and Caddy then fetches a
certificate on the first request to your hostname.

### 6. Verify

```bash
curl https://something.duckdns.org/api/health     # {"status":"ok"}
docker compose logs -f
```

If TLS never comes up, it is almost always a closed port from step 2 — recheck the local
`iptables` rules if your provider has them.

### Operating it

```bash
docker compose logs -f thavalon      # application log, audit lines included
docker compose restart thavalon      # games survive; state is on the volume
docker compose up -d --build         # deploy a new version
```

Game state lives on the `thavalon-data` volume and is reloaded on boot, so restarts and redeploys
do not interrupt a game in progress.

### Backups

The data volume is small enough to copy wholesale:

```bash
docker run --rm -v thavalon_thavalon-data:/data -v $(pwd):/backup alpine \
  tar czf /backup/thavalon-$(date +%F).tar.gz -C /data .
```

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `THAVALON_DATA_DIR` | `./data` | Where game state is written |
| `THAVALON_GAME_TTL` | `PT6H` | Idle time before a playable game is deleted |
| `THAVALON_AUDIT_UNLOCK_AFTER` | `PT4H` | Time after the deal before a game's audit opens |
| `THAVALON_AUDIT_RETENTION` | `P30D` | How long a finished game stays in **Past games** before deletion |
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
- **Past games** are browsable from the home page. A playable game is swept `THAVALON_GAME_TTL`
  after its last activity — freeing the ID for reuse — but its audit trail, recording who was
  dealt which role, outlives it. Each finished game unlocks `THAVALON_AUDIT_UNLOCK_AFTER` after
  the deal (so a game in progress can never be read), then stays in the list until it is deleted
  `THAVALON_AUDIT_RETENTION` later. The list shows only games that were actually dealt; abandoned
  lobbies never appear.

### Distribution table

Transcribed from the reference implementation, deliberately unchanged — including the two counts
its README flags as unbalanced (6 players get a 33% Evil share; 9 players get 4 Evil where
standard Avalon gives 3). It lives as data in `RoleTable`, so adjusting it is a one-line edit
that the existing tests still validate.

| Players | Evil | Newly *eligible* at this count | Good draw | Evil draw |
|---|---|---|---|---|
| 5 | 2 | — | 3 of 5 | 2 of 3 |
| 6 | 2 | — | 4 of 5 | 2 of 3 |
| 7 | 3 | Arthur, Titania | 4 of 7 | **3 of 3** |
| 8 | 3 | Agravaine | 5 of 7 | 3 of 4 |
| 9 | 4 | — | 5 of 7 | **4 of 4** |
| 10 | 4 | Colgrevance | 6 of 7 | 4 of 5 |

Merlin, Percival, Tristan, Iseult, Lancelot, Mordred, Morgana and Maelagant are eligible at every
count.

Becoming eligible is not the same as appearing. Roles are drawn from the eligible pool, so Arthur
and Titania turn up in a little under 60% of 7-player games, and Colgrevance in 80% of 10-player
games.

**Nimue is not played here.** The reference implementation makes her eligible at 5 players only;
this group does not use her, so she is not in `Role` at all and cannot be dealt at any count. That
is why 5 players draw 3 Good from the same core five that 6 players draw 4 from.

One consequence at the table: upstream lists three priority Assassination targets — Merlin, the
Lovers as a pair, and Nimue. Without her there are two, so the Assassin can only call Merlin or
the Lovers. Nothing here enforces that either way; this app deals roles and never runs the
Assassination phase.

The exceptions are worth knowing at the table: at **7 and 9 players the Evil pool exactly fills
the Evil seats**, so every Evil role is in play, every game, and the composition is common
knowledge — the only question is who holds what. Elsewhere there is real uncertainty about which
Evil roles even exist.

Tristan and Iseult always appear as a pair or not at all. At 6 and 10 players the pair is
unavoidable, because there are not enough non-lover Good roles to fill the seats without them.

## Deliberate differences

The information rules for every role that is played match the reference implementation exactly.
These are the places this rewrite knowingly departs from it:

| | Reference | Here |
|---|---|---|
| **Nimue** | Eligible at 5 players | Not played here — removed from `Role`, so she cannot be dealt at any count |
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
