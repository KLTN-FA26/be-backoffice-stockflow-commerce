# Sprint 2 lane A — production workshop flow (SCRUM-396..401; BE 407, 409, 411, 418, 420, 413)

Status 2026-10-12: **plan + contracts published; implementation in progress, sequential 407 → 409 → 411 →
418 → 420 → 413.** Not done. This file is the contract lane C (441/415) builds on and the resume point.

## Where

- Branch `SCRUM-396-401-workshop-flow`, worktree
  `C:/Users/VO HOANG MINH/orca/workspaces/be-backoffice-stockflow-commerce/SCRUM-396-401-workshop-flow`,
  created from the integration checkpoint `4a389a6` (394/395 + 448 + 429; 1033 tests green).
- develop checked 2026-10-12: `74d624d` (newest migration `V20261013000600`). Lane A migrations:
  `V20261014000400`–`000499`; lane B `000500`–`000599`. Re-check develop before each new version.
- Jira read live 2026-10-12: 396–401 and 407/409/411/413/418/420 all To Do, descriptions below are theirs.

## Requirements (Jira, live)

| Story / BE | Requirement |
|---|---|
| 396 / 407 | Production Staff downloads `PRINT_READY` through `design :: api fulfillmentDownload`, verifies the checksum against the locked design (BR-PRD-02). Pass → PENDING_PREPRESS → READY. Fail → ON_HOLD (bad file), Sales notified. ON_HOLD from any state before COMPLETED with a reason (bad file, out of blanks, machine down, waiting for customer); resume returns to the previous state. |
| 397 / 409 | Issue blanks to the `PRODUCTION` area: ORDER — the blanks already reserved for the order; SAMPLE — reason `SAMPLE`. Scan per blank; never more than the LSX still needs (BR-PRD-03). READY → MATERIAL_ISSUED. Via the inventory move API. |
| 398 / 411 | MATERIAL_ISSUED → PRINTING → QC; record printed and scrap with reason; good + scrap = printed (BR-PRD-04); scrap leaves stock through a **reasoned adjustment** (never silently). Shortfall after QC → QC → READY, reserve and issue more; no blanks → ON_HOLD (out of blanks), alert purchasing. No design change once PRINTING (BR-PRD-07). Queue: due date then release time. |
| 399 / 418 | QC (BR-PRD-05): SAMPLE vs the print file, ORDER vs the customer-approved sample. Pass/fail quantities; failed = scrap with reason; enough → COMPLETED, short → reprint. |
| 400 / 420 | SAMPLE → sample request READY; ORDER → finished goods PRODUCTION → PACKING, LSX COMPLETED, publish `ProductionCompleted`. Order waits for every LSX (BR-PRD-06, order side, already built). |
| 401 / 413 | Cancel before printing (PENDING_PREPRESS, READY, MATERIAL_ISSUED) → CANCELLED, issued blanks back to stock. Once PRINTING: BR-PRD-10 (docs 17 policy: refuse, or cancel with a production fee); printed items scrapped. Holds with reasons; Warehouse Manager resolves. |

## State machine (03 §16, schema `ck_production_order_*` already enforces it)

```
PENDING_PREPRESS --prepress pass--> READY --issue blanks--> MATERIAL_ISSUED --start--> PRINTING
PRINTING --record output--> QC --pass, good >= planned--> COMPLETED
QC --shortfall--> READY (reprint loop)            any state before COMPLETED --hold(reason)--> ON_HOLD
ON_HOLD --resume--> status_before_hold            PENDING_PREPRESS/READY/MATERIAL_ISSUED --cancel--> CANCELLED
(SUBCONTRACTED branch belongs to 433/441, lane C)
```

Transition owners (one per transition): Production Staff — prepress, start, record output; Warehouse Staff
— issue scan; QC Staff — QC result (and through it COMPLETED or back to READY: **QC recording is the only
owner of the QC exit**, completion's hand-off runs inside that transaction); Warehouse Manager — hold
resolve/resume, cancel. Holds: Production Staff or Warehouse Manager.

## Contracts (published for lane C and integration)

Aggregate `production.internal.domain.ProductionOrder` grows from the creation model to the workflow:
`passPrepress(by, at, verifiedChecksum)`, `failPrepress(reason, by, at)` (→ ON_HOLD `FILE_REJECTED`),
`hold(reason, note, by, at)`, `resume(by, at)`, `issueBlanks(quantity)`, `startPrinting(by, at)`,
`recordOutput(printed, scrapByReason)`, `recordQc(round, passed, failed, by, at)` → COMPLETED | READY,
`cancel(reason, by, at)`. Quantities follow `ck_production_order_quantities`
(`good + scrap = printed`, `completed ⇒ good ≥ planned`). Every transition writes
`production_order_status_history`; scrap rows go to `production_scrap`, QC rounds to `production_qc_result`
(tables exist since V20261010000200).

Events (to `contracts/`, new unless noted):
- `ProductionCompleted(orderId, orderLineId, productionOrderId, goodQuantity)` — **existing**; published once,
  in the transaction that moves an ORDER LSX to COMPLETED.
- `ProductionOrderHeld(productionOrderId, orderNumber, type, reason, note, sampleRequestId, salesOrderId)` —
  new; notification listens (Sales on FILE_REJECTED / WAITING_CUSTOMER, purchasing on BLANKS_SHORT).
- SAMPLE completion calls `SampleProductionProgress.productionCompleted` in the same transaction (exists).

Inventory (module owner: inventory; additions are narrow and listed for review):
- **Issue of reserved order blanks — atomic**: a new `InventoryService.issueReserved(...)` moves units of
  the order's existing reservations from their storage rows to the `PRODUCTION` area under the same row
  locks `reserve` uses (id order), carrying the hold with the units (no release-then-move window, no second
  decrement), one ledger line per lot with reference `PRODUCTION_ORDER` (allowed by
  `ck_stock_movement_reference` since V20261010000200). Idempotent on a request id per scan.
- **SAMPLE blanks**: `ReserveStockCommand` needs an order id, so nothing reserves blanks for a sample today
  (the foundation only checks ATP at submit). See Q1.
- **Scrap**: `requestAdjustment(reason SCRAP)` from the PRODUCTION-area location; stock leaves only when a
  different person approves (SCRUM-145) — the LSX records the adjustment id on `production_scrap`; it is not
  bypassed.
- **Completion**: ORDER good units PRODUCTION → PACKING keeping the order's hold (same mechanism as issue).

## Questions not answered by Jira/docs (sent to the coordinator; work continues around them)

- **Q1 (409, SAMPLE blanks)** — docs say "blanks reserved for the sample request", but reservations need an
  order. Options: (a) issue unreserved stock directly at issue time with movement reference
  `PRODUCTION_ORDER` and adjustment reason `SAMPLE` for the cost (risk: ATP can drop between submit and
  issue); (b) extend inventory holds to production orders (inventory schema change). Recommended: (a) for
  Sprint 2, (b) later.
- **Q2 (413, cancel while PRINTING, BR-PRD-10)** — "refuse, or cancel with a production fee". Options:
  (a) refuse once PRINTING (simplest, no money flow); (b) allow with a fee — needs the fee amount/rule and
  who records it on the order/payment side. Recommended: (a) until a fee rule exists.
- **Q3 (420, finished goods SKU)** — printed cups stay under the blank's SKU (order lines use the blank SKU +
  design snapshot). They must not become sellable blanks: keep them held for the order when moved to PACKING
  (planned), confirm PACKING is never counted as free ATP for others.
- **Q4 (411, scrap of blanks still awaiting adjustment approval)** — until the SCRAP adjustment is approved the
  units stay on hand at PRODUCTION. Planned behaviour: the LSX does not wait for the approval (quantities on
  the LSX are the workshop's truth; stock follows on approval). Confirm.

## Lane C (441/415) collaboration plan

Lane C should start only after this branch has the workflow aggregate (after 418/420), because 433/441
(split → SUBCONTRACTED child, subcontract goods receipt → QC) adds transitions to the same aggregate and
`ck_production_order_branch`. Proposed order: A finishes 407–413 → A publishes the final aggregate API in this
file → C branches from A's checkpoint for 441, then 415 (dashboard: read-only queries over production
tables, can start in parallel on read models only). If the coordinator prefers, A does 441/415 sequentially.

## Progress (update at every checkpoint)

| BE | State | Commit | Tests |
|---|---|---|---|
| 407 | in progress | — | — |
| 409 | blocked in part by Q1 (ORDER path planned) | — | — |
| 411 | planned (Q4) | — | — |
| 418 | planned | — | — |
| 420 | planned (Q3) | — | — |
| 413 | planned (Q2) | — | — |
