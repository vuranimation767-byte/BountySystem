# BountySystem

Advanced player bounty system for Paper (Minecraft Java 26.3), built with Gradle.

**Development stage:** 4 — complete feature set for this release: real payment handling
(Vault bridge / item mode), crash-safe transaction ledger, the `/bounty` command tree,
a full inventory GUI with a validated chat-input flow for custom rewards, admin ledger
tooling and hot-reloadable configuration.

## Requirements

| Component | Version | Notes |
|---|---|---|
| Java (JDK) | 25 | Required by Paper 26.3 (Temurin 25 works) |
| Gradle | 9.8.2 | via the wrapper (`gradlew`) — no local install needed |
| Paper server | 26.3 (build 211 or newer) | `paper-api 26.3.build.214-stable` is the compile target |
| SQLite driver | 3.53.4.0 | auto-downloaded at runtime via `plugin.yml` `libraries:` |
| Vault (optional) | VaultAPI 1.7.1 | compile-time only (`compileOnly`, no transitive Bukkit) |
| JUnit | 6.1.3 | test-only, managed by the Gradle build |

## Build & test

```
gradlew.bat build        # Windows (wrapper downloads Gradle; Java 25 toolchain)
gradlew.bat test
./gradlew build          # Linux/macOS
```

Output: `build/libs/BountySystem-0.1.0.jar` (version kept in sync with `build.gradle.kts` → `plugin.yml`).

## Install on a Paper server

1. Copy `build/libs/BountySystem-0.1.0.jar` into the server's `plugins/` folder.
2. Start the server. Paper must be 26.3 or newer (needs internet on first boot to fetch the SQLite library).
3. First run creates `plugins/BountySystem/bounties.db` (SQLite) and the documented default `config.yml`.
4. Log line: `[BountySystem] BountySystem 0.1.0 enabled (economy=items:DIAMOND, N active bounty record(s) restored).`

> **Updating from an early dev build:** if `plugins/BountySystem/config.yml` is smaller than
> ~5 KB it is a stage-1 leftover. Delete the file and restart once — the full documented
> default is regenerated automatically. Bounty data (`bounties.db`) is unaffected.

## Commands & permissions

| Command | Description | Permission |
|---|---|---|
| `/bounty` | Opens the bounty GUI (main board) | `bounty.use` |
| `/bounty list` | Lists every active bounty with the 8-character id used by `cancel` | `bounty.use` |
| `/bounty place <player> <amount>` | Escrows and places a bounty on an **online** player | `bounty.use` |
| `/bounty cancel <id>` | Cancels one of *your* bounties; the id may be the full UUID or an unambiguous 8-character prefix (tab completion offers the prefixes) | `bounty.use` |
| `/bounty reload` | Validates and applies changed configuration | `bounty.admin` |
| `/bounty admin ledger` | Lists open (PENDING/FAILED) ledger entries needing review | `bounty.admin` |
| `/bounty admin bounties` | Lists every bounty record ever stored, with status | `bounty.admin` |
| `/bounty admin resolve <id> refund\|reject` | Manually resolves a stuck ledger entry: `refund` pays the due amount now, `reject` closes it permanently without money movement | `bounty.admin` |

- `bounty.use` — default `true` (everyone), `bounty.admin` — default `op`.
- Console may use `list` and the `admin` tools; `place`/`cancel` are players-only (they always charge or refund the sender's own funds).
- All subcommands have tab completion: targets from online players for `place`, your own ids for `cancel`, and — for admins only — the `admin` tree and open transaction ids.
- Unknown arguments produce a usage message instead of a stack trace; missing permission, offline targets, insufficient funds, cooldowns and rejected refunds each get a specific in-game message.

## What it does (stage 4)

- **Inventory GUI** — seven vanilla menus reachable from `/bounty`:
  - **Main board** — browse, place, mine, close.
  - **Browse** — every target with a total, one head per target, paginated.
  - **Target detail** — the individual bounties on one target (placer, amount) with the sum in the title area.
  - **Pick a target** — online players as heads, excluding yourself (heads need real player data; offline players cannot be bountied by either path).
  - **Reward entry** — five quick-pick presets (configurable) plus a **Custom Amount** book. The book asks the player to type the amount in chat — the message is consumed (not broadcast), `cancel` returns to the main board with a confirmation message, invalid or out-of-range input re-prompts.
  - **Confirm** — target head with target/reward summary, `Confirm` and `Back to Amount`. The confirm click is **debounced (250ms) and one-shot**: a fast double-click can never double-escrow.
  - **My bounties** — the player's own active bounties with one-click cancel and live refresh.
- **GUI security model.** Menu identity is an `InventoryHolder` marker — titles, item names and lore are only decoration and are never trusted for actions. While a bounty menu is open *all* inventory interaction is cancelled (top clicks, number-key swaps, double-click collection, creative clicks, shift-clicks into the menu, every drag touching it). GUI sessions die on close and on quit. The GUI itself never moves money: every escrow/refund/payout goes through `BountyManager`, so the exact same limits and guard rails apply as in chat commands.
- **Money modes** (`economy.mode`):
  - `ITEMS` (default) — bounties are escrowed/paid in a real inventory item (`economy.items.currency`, default diamonds; `economy.items.item-value` sets how much one item is worth). Balance is checked before charging; payments only work for online players and payout overflow is dropped at the player's feet, never vanishes.
  - `VAULT` — standard economy bridge; requires the Vault plugin **plus** an economy plugin registered with it (e.g. EssentialsX). If either is missing the startup log says exactly that and bounties stay disabled — a fake economy is never used live.
- **Kill payouts** (`BountyDeathListener`): a player death where `getKiller()` is another player completes every active bounty on the victim and pays the killer the **sum of all of them** in one payout, then broadcasts a colored claim message. Suicides (self-attributed kills count), environmental deaths and mob kills never pay.
- **Self-claim rule** (`bounties.self-claim`, default `false`): a killer who placed the bounty on their own victim doesn't collect it — it stays attached until a *different* player makes the kill.
- **Limits & cooldown** (`limits.*`): min/max reward and a per-placer placement cooldown (session-based, resets on restart, `0` disables). The same placer may hold **several active bounties on the same target** — cooldown and funds are the only limits; `cancel <id>` removes one, pair-based cancellation removes the oldest.
- **Cancellation fee** (`bounties.cancellation.refund-ratio`, default `1.0`): the placer refund is `ratio × escrow` (rounded to cents); the remainder is forfeit. `/bounty admin bounties` and the ledger notes make reduced refunds auditable. Kill payouts always pay the full bounty to the killer.
- **Everything is logged**: placements, cancellations, payouts, refunds and failed attempts appear in the server log with ids and amounts; ledger notes explain unusual entries ("refund rejected — bounty remains active", "paid to … after manual review", "rejected by admin …").

## Configuration (`config.yml`)

Every section is documented in the shipped default file. Highlights:

| Key | Default | Meaning |
|---|---|---|
| `economy.mode` | `ITEMS` | `VAULT` or `ITEMS` |
| `economy.items.currency` / `item-value` | `DIAMOND` / `1.0` | item-mode currency and its worth |
| `limits.min-amount` / `max-amount` | `1.0` / `100000000.0` | allowed reward range |
| `limits.placement-cooldown-seconds` | `5` | per-placer cooldown (`0` = off) |
| `bounties.self-claim` | `false` | may a killer collect their own placed bounty |
| `bounties.cancellation.refund-ratio` | `1.0` | fraction of the escrow returned on cancel |
| `messages.*` | — | every chat/broadcast string, incl. `claim-broadcast` |
| `gui.titles.icons.names.lore.presets.prompts` | — | all seven menus, buttons, lore templates, five presets, chat prompts |

**Validation before everything goes live.** On startup and on `/bounty reload` the whole file is validated (material names real, `min ≤ max`, ratio in `0.0…1.0`, presets inside the limit range, item value positive, cooldown non-negative, economy mode spelled right). Invalid values are refused with a clear log/command message — the previous known-good configuration stays active instead of half-loading.

**Reload semantics.** Limits, cooldown, refund ratio, self-claim rule, messages and all GUI text apply live. The economy **provider** is resolved once at enable: changing `economy.mode`, `currency` or `item-value` takes effect after a restart, and the reload confirms that in chat.

## Transaction guarantees (honest version)

Every money movement is recorded as a ledger transaction (`ESCROW`, `REFUND`, `PAYOUT`) with a
status (`PENDING`, `COMPLETED`, `FAILED`, `REJECTED`) in the `transactions` table, written through
`SqliteBountyStorage` atomic helpers (bounty rows + ledger entry commit together).

- **At-most-once payouts.** Before any payout deposit is attempted, the bounty rows are marked
  COMPLETED **and** the PENDING payout entry is committed *in one database transaction*. A rejected
  deposit rolls everything back (rows back to ACTIVE, memory restored, ledger entry FAILED).
  A payout can therefore never be paid twice.
- **The economy and the database are not atomic together.** If the server crashes at the exact
  boundary between "deposit succeeded" and "ledger marked COMPLETED", the outcome is unverifiable
  at the next boot. The entry is then marked FAILED with a manual-review note and **never**
  auto-retried. This is exactly what `/bounty admin ledger` + `resolve <id> refund|reject` exist for —
  the admin either pays the due amount deliberately (then the entry records who paid it) or rejects
  it permanently (`REJECTED` is terminal; it disappears from open views and is never retried).
  Same treatment for any PENDING escrow/refund.
- **Refunds are retried safely** at boot: a FAILED refund means the deposit was *rejected* —
  verifiably no money moved — so re-attempting cannot double-pay. A rejected cancel keeps the
  bounty alive instead of eating the escrow.
- **Escrow entries are never auto-refunded at boot**: any due refund is tracked as its own REFUND
  transaction (the failed-placement path records it immediately), so boot retries can't double-refund.
- Failed placements escrow nothing permanently: if the record can't be persisted, the charge is
  refunded right away and the player sees an error.

## Storage (`plugins/BountySystem/bounties.db`)

- SQLite with WAL, `user_version` schema versioning (v2 adds the transaction ledger; older
  stage-2 databases migrate in place), transactional multi-row bookkeeping
- All writes run on a dedicated background thread (`BountySystem-DB`)
- Recovery: missing file → fresh database; corrupt file → quarantined as
  `bounties.db.corrupt-<millis>`; database from a newer plugin version → quarantined as
  `bounties.db.future-<millis>`; unreadable rows → skipped with a warning
- All bounty records and all ledger transactions are kept permanently as history

## Package structure

| Package | Purpose | Status |
|---|---|---|
| `dev.bountysystem` | Plugin entry point (`BountyPlugin`) wiring config, economy, storage, commands, GUI | done |
| `dev.bountysystem.bounties` | `Bounty` model, `BountyManager`, `BountyDeathListener`, `KillPayout` | done |
| `dev.bountysystem.storage` | `BountyStorage` interface + SQLite implementation incl. ledger | done |
| `dev.bountysystem.economy` | `EconomyProvider`, Vault bridge, item mode, `Transaction` ledger model | done |
| `dev.bountysystem.configuration` | `BountyConfig` (typed, validated settings; messages; GUI spec) | done |
| `dev.bountysystem.commands` | `/bounty` tree incl. tab completion and admin tools | done |
| `dev.bountysystem.gui` | The seven menus, `MenuListener`, pure `PlaceFlow`/`Pagination` logic | done |
| `dev.bountysystem.utilities` | `Formats` (money string) and friends | as needed |

## Tests (58, all passing — `gradlew test`, JDK 25)

**Automated (JUnit 6):**

- `BountyManagerTest` (24): escrow on creation and refund on cancel (ledger-verified), validation
  (parametrized), **multiple active bounties on the same target**, affordability, **payout of summed bounties**, **repeated
  kill events pay once**, **denied/allowed self-claim**, **payout failure leaves bounties
  claimable**, failed refunds recorded and retried by boot reconciliation, pending payouts never
  auto-paid, ambiguous escrows never auto-refunded, disabled without economy, plus stage 4:
  **per-placer placement cooldown**, **cancel by full id and unique prefix**, **refund ratio math
  (cancel returns ratio × escrow)**, **custom min/max limits before and after a live reload**,
  **admin pay/reject of stuck ledger entries (REJECTED is terminal, pay closes with deposit)**.
- `BountyConfigTest` (13): complete defaults, yaml overrides, claim broadcast formatting,
  placeholder pairs, unknown economy modes (parametrized), unknown currency/GUI material,
  crossed limits, zero max, negative cooldown, out-of-range refund ratio, zero item value,
  **presets outside the limit range rejected**, forgiving mode casing.
- `PlaceFlowTest` (7): target→amount→confirm happy path with handler called exactly once,
  confirm refused outside the CONFIRM stage, **rapid double-clicks debounced**, stage-by-stage
  back navigation clears the chosen target, chat-input state machine (`requestCustomInput` /
  `cancelInput` can't be hijacked from unrelated stages), re-arm after failure, full reset.
- `PaginationTest` (5): slicing, page clamping in both directions, empty lists, exact multiples,
  degenerate page size.
- `SqliteBountyStorageTest` (6): bounty round-trip, ledger round-trip (all statuses incl. REJECTED),
  atomic completion bookkeeping, unknown-ledger-update failure, corrupt-database recovery,
  future-schema-version quarantine.
- `BountyPluginTest` (3): metadata consistency (`plugin.yml` ↔ build), command + permission
  declaration (`bounty.use` default true; `bounty.admin` default op), all config sections documented
  in the shipped default.

**Manual (needs a real client; not claimed as automated):**

1. GUI walkthrough: `/bounty` → browse a target → detail → back; main → place → pick head → preset → confirm; custom book → type amount → type `cancel` → type amount again → confirm; `My Bounties` → cancel a bounty → list refreshes.
2. GUI guard rails (observer or second account): cannot pull items out, cannot shift-click item in, cannot drag anything into the menu, number-key/creative tricks no-op.
3. Command ergonomics: `/bounty place <offline player> 50` → "not online", `/bounty place <player> abc` → usage, `/bounty cancel` with someone else's bounty id → not-found, deop'd player: no `admin`/`reload` suggestions.
4. `$ /bounty reload` after editing the file live (and after deliberately breaking it — old settings must stay active).
5. Kill demo in-game: diamonds escrowed by player A on player B, player C kills B → one payout + broadcast.
6. Restart persistence: bounties survive restart; cancel-and-refund survives (SQLite, WAL).

**Known limitations (deliberate for this release):**

- The placement cooldown is in-memory only (session-scoped).
- The economy choice and item mode currency/value are fixed per server run (restart needed after changes).
- GUI targets must be online (heads need player data), and `/bounty place` enforces the same online-only rule; existing bounties on players who later go offline stay active and can still be claimed when someone kills them online.
- The chat-capture prompt reads a plain typed number; bold/colored chat formatting from clients is accepted but only its plain content is used.
- `bounty admin resolve refund` credits the full due amount without recomputing a ratio — records show what happened, exactly by design.

## Demo / portfolio

See `DEMO.md` in the repo root: a two-player scenario, a <30-second recording script, the GUI moments worth capturing and a restart-persistence demo — every step maps to a shipped feature listed above.

## Changelog

See `CHANGELOG.md`.
