# Changelog

## Unreleased — post-stage-4 live-test fixes

### Changed
- **Multiple bounties per placer/target are allowed.** The old "you already have an active bounty
  on that player" rejection is gone; the per-placer cooldown and available funds are the only
  limits. `cancel(placer, target)` now always removes the placer's **oldest** matching bounty;
  `/bounty cancel <id>` removes a specific one. Kill payouts already summed every active bounty,
  so stacked bounties pay out together at most once.
- **`/bounty list` is per bounty record:** `ID: <8-char> | Target: <name> - <amount> <currency>
  (by <placer>)`. The 8-character id is directly usable with `/bounty cancel`; the currency label
  follows the active economy (`diamonds` for ITEMS, `$` for Vault).
- **Custom amount entry:** typing the cancel word now shows `gui.prompts.amount-cancelled` and
  returns to the main board (was a silent return to the reward menu). Amounts outside
  `limits.min/max` count as invalid input and re-prompt at entry time; the balance/inventory check
  still happens at confirm through `BountyManager`.
- **Confirm-time offline guard in the GUI:** if the picked target leaves while the flow is open,
  Confirm now reports `playerNotOnline` and re-arms instead of creating the bounty.

### Fixed
- **GUI sub-menus were silently dead (session killed on menu switch).** Opening a menu fires
  `InventoryCloseEvent` for the previous one; the listener deleted the session, so every sub-menu
  click was ignored. Menu switches are now tracked (`switchingMenus`), close events during a switch
  are ignored, quit always clears, and close is the only path that ends a session.
- Multi-placement no longer leaves the old duplicate-rejection message reachable.

## 0.1.0 — stage 4 (commands, GUI, admin tooling, reload)

Released: 2026-10-23

### Added
- `/bounty` command tree: GUI open, `list`, `place <player> <amount>`, `cancel <id>`
  (full id or unique 8-char prefix), `reload`, plus the `bounty.admin` ledger tools
  `admin ledger`, `admin bounties`, `admin resolve <id> refund|reject`.
  Full tab completion (subcommands, online targets, own ids, admin tree, open transaction ids).
- Permissions: `bounty.use` (default true) gates all player paths; `bounty.admin` (default op)
  gates reload + admin tools; console may run `list` and admin tools.
- Inventory GUI with seven menus (main board, browse, target detail, pick a target, reward
  entry, confirm, my bounties): player heads, pagination, back/close buttons, one-click cancel.
- Reward entry via presets **and** chat input: custom amount typed in chat is captured
  (message not broadcast), `cancel` aborts, invalid input re-prompts.
- GUI security model: `InventoryHolder`-keyed menus (titles/names/lore never trusted),
  blanket-cancellation of clicks/shift-clicks/drags/hotbar/double-click/creative while a menu is
  open, 250 ms debounced one-shot confirm, sessions cleaned on close and quit; the GUI itself
  performs zero money operations — everything goes through `BountyManager`.
- Configuration: `limits.min/max-amount`, `limits.placement-cooldown-seconds` (per-placer,
  session-scoped), `bounties.cancellation.refund-ratio` (0–1 escrow return on cancel; remainder
  forfeit), every message incl. claim broadcast, all GUI titles/icons/names/lore/presets/prompts.
  Startup and `/bounty reload` validate the whole file; invalid values are refused and the previous
  settings stay live. Reload applies limits/ratio/messages/GUI immediately; economy provider
  changes need a restart (stated in chat).
- Admin ledger resolution: `adminPayTransaction` deliberately pays a stuck entry (recording the
  admin), `adminRejectTransaction` closes it terminally as `REJECTED` (new status; excluded from
  open views, never auto-retried, same-db compatible — no schema bump needed).
- Money-movement logging: placements, cancellations, refunds, payouts, failed/rejected attempts.
  `/bounty admin bounties` lists all bounty records across all statuses.
- Purely testable GUI logic: `PlaceFlow` (state machine with injected clock + one-shot confirm)
  and `Pagination` math.

### Fixed
- Failed refund path now writes the rejected refund with the correct escrow amount and keeps
  the bounty active (pre-review false-attempt arm required the confirm to be re-armed).
- `queueRowStatus` executor submission is guarded — a terminated pool can no longer throw at
  the kill-event caller (shutdown race).
- Receipt of status updates for a currency-less ITEMS mode refund of non-multiples now lands
  correctly in the refund-rejected handling (bounty stays active instead of a half refund).

### Test suite
- 58 automated tests (was 26) — all passing on JDK 25: +13 config validation, +7 placement flow,
  +5 pagination, +5 manager (cooldown, cancel-by-id, refund ratio, live limits reload, admin
  pay/reject), +2 plugin.yml/config.yml coverage.

## 0.1.0 — stage 3 (payments, ledger, reconciliation)

- Vault economy bridge + item-mode economy (`economy.items.*`), resolved once at enable with
  honest "disabled this session" startup messages.
- Kill settlement via death listener: sums all active bounties on the victim, at-most-once payout
  with write-ahead bookkeeping, self-claim rule, claim broadcast.
- Crash-safe transaction ledger (`transactions` table, schema v2): ESCROW/REFUND/PAYOUT with
  PENDING/COMPLETED/FAILED; boot `reconcile()` retries FAILED refunds safely, marks unknown-outcome
  PENDING entries FAILED for manual review, never auto-refunds ambiguous escrows.

## 0.1.0 — stages 1–2 (scaffold, model, storage)

- Plugin scaffold with default config, Java 25 + Paper 26.3 toolchain, plugin.yml library loading.
- `Bounty` model + `BountyManager` basics: creation/at-most-one-active-per-target validation,
  cancellation, kill claim searches, `BountyStatus` lifecycle.
- SQLite storage (`SqliteBountyStorage`): WAL journal, `user_version` schema versioning,
  corrupt-future-database quarantine, background write thread (`BountySystem-DB`).
