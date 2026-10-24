# BountySystem — Demo & Portfolio Guide

Everything below ships in `BountySystem-0.1.0.jar` and its README — every demo step maps to a
feature in the codebase, nothing here is aspirational.

## One-paragraph pitch

BountySystem is a complete Paper 1.26.3 addon for player bounties: place an escrowed reward on
another player through a polished vanilla-style GUI (or a command), browse and cancel your own
bounties, and when a third player makes the kill, the whole sum pays out — with a
crash-safe SQLite transaction ledger that ships admin tooling for the hard edge cases instead of
hand-waving them.

## Feature list (all shipped, all listed in README)

- 7-menu inventory GUI: main board, browse, target detail, pick-a-target, reward entry
  (presets + chat input), confirm, my-bounties/cancel; player heads, pagination, back navigation
- `/bounty [list|place|cancel]`, `/bounty reload`, `/bounty admin ledger|bounties|resolve` —
  tab-completed, permission-gated (`bounty.use` default true, `bounty.admin` default op)
- Escrow/payout/refund ledger (ESCROW, REFUND, PAYOUT × PENDING/COMPLETED/FAILED/REJECTED)
- Boot reconciliation: safe refund retries, never auto-pays unknown outcomes, never double-pays
- Item economy mode (default, diamonds) + Vault bridge for real economy plugins
- Configurable limits, cooldown, cancellation fee, every message and GUI string — validated before
  applied, live-reloadable
- SQLite persistence (WAL, schema-versioned, corrupt-DB quarantine)

## Suggested config for the demo (`plugins/BountySystem/config.yml`)

```yaml
economy:
  mode: ITEMS
  items:
    currency: DIAMOND
    item-value: 1.0
limits:
  min-amount: 10.0
  max-amount: 5000.0
  placement-cooldown-seconds: 3
bounties:
  self-claim: false
  cancellation:
    refund-ratio: 0.75
gui:
  presets: [100, 250, 500, 1000, 2500]
```

With `refund-ratio: 0.75`, a cancelled 200-diamond bounty visibly returns 150 — a nice forced
cancellation-fee moment for the camera (ledger note documents it).

## Two-player scenario ( Player "block" is the operator; second account "guest" optional)

1. `block` runs `/bounty`, clicks **Place a Bounty**, picks `guest`'s head, chooses preset 250
   (or the Custom Amount book → types `300` in chat), confirms. 250–300 diamonds leave `block`'s
   inventory (escrow). `guest` now appears in **Browse** with a total.
2. `block` opens **My Bounties**, sees the head with its lore, clicks it → cancel → refund back to
   the inventory (75% if fee enabled). Places again (cooldown allows after 3s) — this time it stays.
3. `guest` is killed by `block` in a simple arena duel → **sum of all active bounties pays out in
   one deposit** + server broadcast "block claimed the 300 bounty on guest!". Empty inventory slot
   check: diamonds are just added back.

## <30-second recording script

| Second | Shot |
|---|---|
| 0–4 | `block`: `/bounty` — main board opens; click **Active Bounties**; show empty "no active bounties" or the list |
| 4–12 | click **Place a Bounty**; pick `guest`'s head; reward menu: click preset 100 |
| 12–17 | confirm menu: target head + reward summary; click confirm; diamonds leave inventory |
| 17–22 | chat: success message; `/bounty list` shows `ID: <8-char> | Target: guest - 100 diamonds (by block)`; **Browse** shows the head w/ lore |
| 22–27 | kill `guest` → payout + broadcast; `/bounty list` now empty |

## GUI moments worth capturing (20–45s)

- **Custom amount via chat**: reward menu → Custom Amount book → menu stays open, typed chat
  message is NOT broadcast → confirm opens on the typed number. Then type `cancel` once to show abort
  (message + back to the main board).
- **Cancel with fee**: `My Bounties` → click a bounty → refund lands short (ratio) — pair with
  `/bounty admin bounties` showing the CANCELLED row.
- **Guard rails demo**: try dragging an item into the menu / shift-clicking diamonds into it —
  nothing happens (good for a before/after clip if you want).
- **Cooldown message**: place twice quickly → chat warns "wait 3s between placements".

## Persistence demo (restart proof)

1. Place a bounty on `guest` (e.g. 500).
2. Stop the server (`stop`), look at `plugins/BountySystem/bounties.db` still on disk.
3. Start the server → enable log says `1 active bounty record(s) restored` → in game, **Browse**
   still shows the 500 bounty, `guest` can still be killed for it.

## Admin/ledger show-off (45–60s, optional)

- `/bounty admin ledger` — empty board message ("nothing needs manual review").
- Kill the server with task manager mid-flight (rare timing) → boot → if a PENDING entry exists:
  `/bounty admin ledger` lists it red → `resolve <id> reject` → permanent REJECTED status
  (honest demo: relies on crash timing, so optional/best-effort).
- `/bounty admin resolve` demo alternative without any crash: give yourself a pending refund or
  payout via the plugin's logs — skip if too fiddly for a portfolio clip.

## What NOT to claim

- No real economy plugin is required: the demo uses the built-in diamond mode.
- The placement cooldown is session-scoped (resets on restart) — documented, not a bug.
- 58 automated tests pass; real-client GUI/command checks are documented as the manual list in the
  README — say so if asked, the separation is deliberate.
