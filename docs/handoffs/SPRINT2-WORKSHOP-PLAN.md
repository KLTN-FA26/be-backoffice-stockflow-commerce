# Sprint 2 lane A — production workshop flow (SCRUM-396..401; BE 407, 409, 411, 418, 420, 413)

Status 2026-10-13: **407, 409, 411, 418, 420, 413, 415, 441 done (BE)**; Q1–Q9 decided by the user and
built (Q5 76d96f2, FIFO/clock c4e0171, hand-over e9efad9, Q6a/b d16990a, Q7/Q8/Q9 see 441). Not 100%: see the
remaining gaps below; the acceptance → code → test table is at the end.
This file is the contract lane C (441/415) builds on and the resume point.

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

Inventory (module owner: inventory; additions are narrow and listed for review). **Built in 409
(V20261014000400):**
- **Owner of a hold**: `stock_reservation` belongs to an order (`order_id`, as before) **or** to a production
  order (`production_order_id`, new, FK) — `ck_stock_reservation_owner`, exactly one. A production hold never
  expires (`ck_stock_reservation_production_pinned`) and announces nothing to `order` (no
  `StockReserved/Released/Deducted` with a null order id). No order id is invented.
- **Stock status `IN_PRODUCTION`** (new): issued blanks and the cups printed from them, in a PRODUCTION area.
  Not reservable → never in ATP, **whatever happens to the hold**. Why: an order cancelled after the issue
  releases its holds in the order's own transaction (`OrderServiceImpl.cancelLoaded`), before production can
  react; with AVAILABLE status those units would be free blanks for any checkout for that moment (or for
  ever if the production listener failed). ATP is still "AVAILABLE − held" — no system-wide rule changed.
  Leaves the status only by an explicit step: finished → PACKING (AVAILABLE, held), unprinted → back to
  storage, scrap → written off by an approved adjustment.
- `InventoryService.reserveForProduction(ReserveProductionStockCommand)` — same algorithm as `reserve`
  (replay first, plan, lock in id order, re-check), restricted to the production order's warehouse; joins
  the caller's transaction. Used at sample submit (Q1) and for a reprint round.
- `InventoryService.issueReserved(IssueReservedStockCommand)` → `IssueReservedStockResult` — the **whole
  hold moves with its goods** AVAILABLE → IN_PRODUCTION: SKU lock, then source and destination rows in id
  order; the reservation row is re-parented by `UPDATE stock_item_id` (same id — the order line and
  fulfilment FKs still find it; never deleted/re-inserted), one MOVE ledger line `PRODUCTION_ORDER`. Refuses
  a hold that is closed, another owner's, or not pinned (an order still waiting for payment —
  `RESERVATION_NOT_ISSUABLE`). Hold already at the destination → answered without a second ledger line.
- `releaseProductionHolds(productionOrderId)`, `productionHolds(productionOrderId)`.
- **A hold in the print shop is closed only by production** (411..413): `InventoryService.release` is a
  no-op for an IN_PRODUCTION hold (an order cancelled after the issue does not free it), the expiry sweep and
  `rearmReservations` skip it. So a hold's quantity is always the job's own count of units still there, and
  printed cups are never mistaken for blanks.
- `writeOffHeld(WriteOffHeldStockCommand)` (411/418, Q4 decided): units leave the hold at once (quantity
  reduced, same id; closed when all) and stay IN_PRODUCTION; a SCRAP (or SAMPLE, 420) adjustment of **that
  layer** is requested (`stock_adjustment.received_at`, V20261014000420). Approval deducts once (second
  approval `INVALID_ADJUSTMENT_TRANSITION`); rejection leaves them IN_PRODUCTION, never sellable. The LSX
  does not wait for the approval.
- `returnHeld(ReturnHeldStockCommand)`: unprinted blanks leave the hold and go back, free and AVAILABLE, to
  where they were issued from; one MOVE line IN_PRODUCTION → AVAILABLE.
- `unissueHeld` / `finishHeld` (`IssueReservedStockCommand`): a whole hold with its goods IN_PRODUCTION →
  AVAILABLE, still held — back to storage for an order that still wants them (cancel before printing), or to
  PACKING (completion).
- `liveHolds(ids)`: the live holds among ids with their current quantity and place.

Warehouse (420): `WarehouseService.areasOf(warehouseId, areaType)` — completion uses the warehouse's only
usable PACKING area unless QC names one.

Warehouse: `AreaType.PRODUCTION` added to the Java enum (the DB has allowed it since V20261010000200; a
PRODUCTION area could not be loaded before), `StorageLocationView.areaType` (null for a bin; old 8-argument
constructor kept). Demo seed `V20261014000410`: area `HCM-PRD01` (Xưởng in).

Identity: resource `production-material-issues` (READ, CREATE): WAREHOUSE_STAFF and WAREHOUSE_MANAGER
READ+CREATE, PRODUCTION_STAFF READ, SYSTEM_ADMIN all; role versions advanced. Separate from
`production-orders:UPDATE`, which would also let the warehouse pass prepress or record output.

## Decisions (user, 2026-10-12) and remaining questions

- **Q1 decided — built in 409**: a sample's blanks are held at submit, for its production order (owner model
  above); too few in the warehouse refuses the submit and rolls back the production order. Not "issue
  unreserved stock".
- **Q2 decided — built**: once printing started, the unprinted part is stopped (`…/stop`), printed quantity
  kept on the LSX. Money: Sales enters the **amount agreed** with the customer on the existing cancellation
  (`POST /api/v1/orders/{id}/admin-cancellation` or the request approval, `sales-order-cancellations:UPDATE`,
  body `agreedFee {amount, reason, detail}` instead of `retainedPercent`; V20261014000430). Nothing computes
  it; whole VND, > 0, ≤ what was paid (15 BR-05, `ck_order_cancellation_retained`), reason and detail
  required (`ck_order_cancellation_fee`). It reuses `cancellation_retained_amount`, so
  `OrderCancelled.refundableAmount = paid − agreed` and payment raises PENDING refunds for the rest exactly
  as for a percentage — nothing counted twice, nothing charged, captured payments untouched. The internal
  side (units printed, written off) stays on the LSX; no money is put on the LSX.
- **Q7 decided (2026-10-12) — built**: blanks sent to a subcontractor are tracked per subcontractor AND
  sending warehouse. One storage location of kind `SUBCONTRACTOR` per pair, code `<prefix>-SUB-<supplier
  code>` (e.g. `HCM-SUB-INNHANH`), owned by `warehouse.subcontractor_site` (V20261014000470), not pickable,
  not a putaway target, never on the map. Created by the system on the first dispatch
  (`WarehouseService.subcontractorLocation`), so nobody needs a permission to create it — no new role, no new
  grant. The blanks there are IN_PRODUCTION, held for the child (never ATP), with SKU/lot/receipt layer kept.
- **Q8 decided — built**: a subcontractor's goods never become free stock. In the goods receipt's own
  transaction (`SubcontractReceiptListener`, `BEFORE_COMMIT`) each line is held for the child on its INBOUND
  layer (`InventoryService.holdReceived`) and handed to the print shop's PRODUCTION area as IN_PRODUCTION for
  QC (`issueReserved`); `production_subcontract_hold` records which hold came from which receipt line. QC then
  works as in house: a failed cup is scrapped (SCRAP write-off, approval) as a cup, never a blank; completion
  moves the good ones to PACKING and hands them to the sales order and its line.
- **Q9 decided — built**: the warehouse staff enter the receipt count (the goods receipt) and the
  reconciliation (`POST …/{childId}/subcontract-receipts/{receiptId}/reconciliation {spoilt, returned,
  returnLocation?}`, production-material-issues:CREATE — the permission that already sends the blanks out);
  QC confirms quality. Per receipt: the blanks the received goods were printed on leave the subcontractor's
  location at receipt (`CONSUMPTION` ledger line — never counted twice with the goods), spoilt blanks leave
  through a `SUBCONTRACT_LOSS` adjustment (existing approval), surplus returns free (to `returnLocation` or
  where each blank was sent from). What is left stays held at the subcontractor — not received yet is not
  lost. BR-PRD-16: QC of a SUPPLIED_BLANKS child is refused while a receipt is not reconciled. Example (test
  `subcontractReconciliationExample`, 1/50 scale of the user's): 20 sent → 15 good + 3 spoilt + 2 returned.
- **Q6 decided — built (d16990a)**: (a) an agreed fee above what was paid becomes a `CANCELLATION_FEE`
  receivable for the difference (paid 2,000,000, fee 3,000,000 → owes 1,000,000), one per order, nothing
  collected or marked paid; (b) paid above the fee → a PENDING refund request for the excess through the
  existing refund flow (deposit 2,000,000, fee 1,200,000 → 800,000), nothing transferred; equal → nothing.
- **Q3 resolved technically**: printed goods stay held for their order and are `IN_PRODUCTION` (never ATP)
  until they reach PACKING, where they are AVAILABLE and still held.
- **Q4 decided**: production continues (good units, reprint) without waiting for the scrap approval; the
  scrapped units are unusable at once (out of the hold, IN_PRODUCTION); approval deducts once, rejection does
  not make them sellable.
- **Q5 decided — built (76d96f2)**: a SAMPLE production order cancelled or stopped by the manager cancels its
  round (history kept, reason names the LSX); unprinted blanks go back, printed/spoilt stay written off; a new
  round may start, holds blanks again and inherits no customer answer.

Known limitations (existing rules, not changed):
- Two back-office cancellations of one order at once: one cancels; the other fails with 409 from the
  optimistic lock (`OrderServiceImpl.cancel` loads in scope, then locks, and the lock does not refresh the
  entity already loaded), not with `ORDER_NOT_CANCELLABLE`. No double refund (tested). A lock-before-load
  in the order repository would give the clean code; not changed here (order module, every order flow).

Corrected (claims of the 2026-10-12 version that are no longer true):
- FIFO: a second hold or move into a layer already present at the destination is no longer refused —
  V20261014000450 lets exactly the merged row through (`StockItemRepository.saveMerged`, transaction-local
  marker); a replenishment of an old FIFO layer is still refused (`SameReceiptMergeIntegrationTest`).
- Adjustments already carry a layer selector (`StockAdjustment.atLayer(receivedAt)`); write-offs at the print
  shop name their layer. The old "needs a layer selector" note was stale.
- Receipt time: the policy trigger compared with the database clock while the JVM clock can run ahead (a
  Docker Postgres was ~430 ms behind); V20261014000450 allows one minute of skew. No clock was changed, no test
  skipped.
- Fulfilment: every good unit of a reprint or a subcontracted child is handed to the sales order and added to
  its line at completion (`order.api.OrderReservations.addProductionHolds`, e9efad9); only this production
  order's holds, only to its own sales order.
- IN_PRODUCTION holds are closed only by production: `InventoryService.release`, the expiry sweeper and
  re-arming skip them.

## Workshop API (BE 411/418/420/413, built)

| Step | Endpoint | Permission | Effect |
|---|---|---|---|
| start | `POST /api/v1/production/orders/{id}/printing` | production-orders:UPDATE | MATERIAL_ISSUED → PRINTING; SAMPLE round → IN_PRODUCTION |
| output | `POST …/{id}/output {printed, scrap:[{reason, quantity, note}]}` | production-orders:UPDATE | PRINTING → QC (all spoilt: → READY); scrap out of the holds + SCRAP write-off per line and layer (`production_scrap.stock_adjustment_id`) |
| scrap list | `GET …/{id}/scrap` | production-orders:READ | the scrap rows with their write-offs |
| QC | `POST …/{id}/qc-results {passed, failed, note, packingLocation?}` | **production-qc-results:CREATE** (QC_STAFF, WAREHOUSE_MANAGER) | every unit awaiting QC; failed → scrap/write-off; good ≥ planned → COMPLETED (ORDER: unprinted leftovers back free, goods → PACKING still held, `ProductionCompleted` once; SAMPLE: SAMPLE write-off, round READY); short → READY + reprint blanks held now, or ON_HOLD BLANKS_SHORT + `ProductionOrderHeld` (nothing held). Refused for a cancelled sales order |
| QC list | `GET …/{id}/qc-results` | production-qc-results:READ | rounds |
| cancel | `POST …/{id}/cancellation {reason}` | production-orders:APPROVE (manager) | PENDING_PREPRESS/READY/MATERIAL_ISSUED (or ON_HOLD from them) → CANCELLED; issued blanks back to their origin — still held if the sales order still wants them, free otherwise; unissued production holds released |
| stop (Q2) | `POST …/{id}/stop {printed, scrap[], reason}` | production-orders:APPROVE | PRINTING/QC (or ON_HOLD from PRINTING) → CANCELLED; the stopped run's output recorded; printed count kept; good printed units → scrap (OTHER, "printed for a stopped job") and written off; unprinted blanks back |
| order cancelled | `OrderCancelled` listener | — | before printing: cancel as above; PRINTING: ON_HOLD (OTHER) for the manager to stop with the real count; QC: waits for the stop |

QC_STAFF lost production-orders:UPDATE (V20261014000420): 03 §16 gives prepress, start, output and holds to
Production staff; QC holds exactly its own resource. Queue order (Jira 398): list with
`sort=dueDate,asc;createdAt,asc` (no new endpoint). BR-PRD-07 (no design change once printing): no endpoint
changes an LSX's design snapshot/checksum (columns are not updatable) — nothing to block.

## Subcontracting (BE 441; finishing pass 2026-10-13, branch `SPRINT2-finish-lane-a`)

User decision 2026-10-13 (supersedes the 3-step question below it in older versions of this file): a
subcontractor's goods get **one** inspection, the production QC against the approved sample. The warehouse counts
and reconciles them; receiving runs no QC on them and puts none away; they are held for their production order,
outside ATP, IN_PRODUCTION in the warehouse's QUALITY_CONTROL area (not the print shop) until QC; good units go to
PACKING, failed ones are scrapped as cups. Ordinary (STANDARD) receipts are unchanged.

| Step | Endpoint | Permission | Effect |
|---|---|---|---|
| split (G1–G3) | `POST …/{id}/subcontract-split {quantity, subcontractorId, method, unitPrice, currency?, dueDate?}` | production-orders:APPROVE (manager) | BR-PRD-11/12: ORDER, in house, PENDING_PREPRESS/READY/ON_HOLD, 0 < quantity < unissued. Parent keeps its status, planned −quantity. Child SUBCONTRACTED-mode (origin SPLIT), READY or PENDING_PREPRESS; SUBCONTRACT PO DRAFT. Holds: FULL_SERVICE — given up (ATP +q); SUPPLIED_BLANKS — held for the child instead |
| convert (BR-PRD-12) | same endpoint, `quantity` = the whole plan of an order with nothing issued/printed | production-orders:APPROVE | the order itself becomes SUBCONTRACTED (same row and number, no parent), own PO, blanks as for a split |
| send out (G5–G6) | `POST …/{id}/subcontract-dispatch` | production-material-issues:CREATE | BR-PRD-13: PO approved, no amendment waiting, PO open quantity ≥ still owed; READY → SUBCONTRACTED; SUPPLIED_BLANKS: blanks to `<prefix>-SUB-<supplier>` (Q7), missing ones held first |
| dispatch note (BR-PRD-14/15) | `GET …/{id}/subcontract-dispatch-note` | production-material-issues:READ | PO, subcontractor, plan, due/expected dates, prepress-checked PRINT_READY file (fresh link), approved sample id, blanks sent per lot |
| receive back (G7/G9) | goods receipt confirm of the SUBCONTRACT PO | goods-receipts (existing) | receipt line `qc_required = false`, `qcProgress = PRODUCTION_QC`, nothing for putaway, receipt CLOSED; confirmation refused without a QUALITY_CONTROL area (`QC_AREA_MISSING`). Once per receipt: lines held for the child and moved to the QC area (IN_PRODUCTION); SUPPLIED_BLANKS: as many blanks consumed at the subcontractor (Q9). SUBCONTRACTED/QC → QC; a SUBCONTRACTOR_LATE hold ends; another hold stays, QC after the resume |
| reconcile (G8, Q9) | `POST …/{id}/subcontract-receipts/{receiptId}/reconciliation {spoilt, returned, returnLocation?}` | production-material-issues:CREATE | once per receipt; spoilt → `SUBCONTRACT_LOSS` adjustment (approval); returned → free; the rest stays |
| recall (BR-PRD-15) | `POST …/{id}/subcontract-recall {requestId, returned, spoilt, returnLocation?, note?}`, `GET …/subcontract-recalls` | production-material-issues:CREATE / READ | blanks back without a receipt (subcontractor gives the work back, cancellation): returned → free, spoilt → `SUBCONTRACT_LOSS`; idempotent on requestId (other numbers → 409) |
| QC / complete | `POST …/{id}/qc-results` | production-qc-results:CREATE | the one QC against the approved sample; failed → SCRAP write-off; COMPLETED only with every receipt reconciled and no blank left at the subcontractor (BR-PRD-16) → PACKING, held for the order, `ProductionCompleted`, PO closed for production and settled |
| shortfall: resend (G9 "gửi bù") | `POST …/{id}/subcontract-supplement {quantity, reason}` then `POST /api/v1/purchase-orders/{po}/amendment/approval` (or `/rejection {reason}`) | production-orders:APPROVE; purchase-orders:APPROVE, four eyes | DRAFT PO resized; approved PO gets an AMENDMENT revision (line revision, approval row) approved again by someone else; a fully received PO becomes PARTIALLY_RECEIVED so the resend can be received |
| shortfall: in house (G9 "in bù nội bộ") | `POST …/{id}/subcontract-in-house {quantity, reason, packingLocation?}` | production-orders:APPROVE | `quantity` ≤ still owed goes to a new IN_HOUSE ORDER (origin SHORTFALL, parent = the subcontracted one, inherited prepress, blanks held now or ON_HOLD BLANKS_SHORT + `ProductionOrderHeld`); the subcontracted one completes if nothing more is owed (blanks accounted for first) |
| cancel (03 §16) | `POST …/{id}/cancellation {reason, subcontractorFee?, subcontractorFeeReason?}` | production-orders:APPROVE | blanks must be recalled first; sales order live: refused if it has good units (take the rest in house), else CANCELLED + in-house REPLACEMENT; sales order cancelled: good units written off; PO cancelled (nothing received) or closed short; settlement with the fee. When the sales order is cancelled the subcontracted LSX is put ON_HOLD (OTHER) for the manager |
| settlement (BR-PRD-17, G10) | `GET …/{id}/subcontract-settlement`, `POST …/{id}/subcontract-settlement/loss-approval {deductionPerBlank, note?}`; `GET /api/v1/purchase-orders/{po}/subcontract-settlement` | production-orders:READ / APPROVE; purchase-orders:READ | one per SUBCONTRACT PO when its LSX ends: QC-passed units × unit price paid, failed not; blanks lost above the subcontractor's tolerance (% of blanks sent) → AWAITING_APPROVAL until the manager enters the agreed deduction per blank → FINAL; agreed cancellation fee added |
| hold from QC | `POST …/{id}/hold` | production-orders:UPDATE | QC → ON_HOLD (V20261014000700); resume returns to QC |

Still open (business answers pending, coordinator file `SPRINT2-FINISH-LANE-A.md`):
- QA1: how a blank lost above tolerance is priced — implemented as "the approver enters the agreed deduction per
  blank"; nothing else is invented.
- QA2: cancelling a subcontracted LSX while the order is live replaces it in house (implemented default).
- QA3: the dashboard period selects LSX created in it (`periodBasis` says so); activity basis not built.
- A receipt counting more SUPPLIED_BLANKS goods than blanks at the subcontractor is refused (goods printed on our
  blanks cannot exceed the blanks sent) — unchanged.
- Supplier invoices and the 3-way match itself are not implemented in this codebase (Jira 3.4.2, not Sprint 2):
  `procurement.subcontract_settlement` is the basis it will match against.

## Lane C (441/415) collaboration plan

Lane C should start only after this branch has the workflow aggregate (after 418/420), because 433/441
(split → SUBCONTRACTED child, subcontract goods receipt → QC) adds transitions to the same aggregate and
`ck_production_order_branch`. Proposed order: A finishes 407–413 → A publishes the final aggregate API in this
file → C branches from A's checkpoint for 441, then 415 (dashboard: read-only queries over production
tables, can start in parallel on read models only). If the coordinator prefers, A does 441/415 sequentially.

## Progress (update at every checkpoint)

| BE | State | Commit | Tests |
|---|---|---|---|
| 407 | code complete: `GET …/{id}/print-file` (READ), `POST …/{id}/prepress {decision PASS\|FAIL, note}` (UPDATE; PASS re-hashes the stored file, 409 `PRINT_FILE_MISMATCH`; FAIL → ON_HOLD `FILE_REJECTED` + `ProductionOrderHeld`), `POST …/{id}/hold {reason, note}` (UPDATE) from every state before COMPLETED **including QC** (V20261014000700), `POST …/{id}/resume` (APPROVE). Sales/purchasing e-mail on a hold: lane B (`ProductionNotificationListener`, SMTP after commit) | `afb3a58` (QC hold) | `ProductionOrderTest` (QC hold/resume/stop), `PrintRunIntegrationTest.holdFromQc`*, `ProductionWorkflowIntegrationTest` 3 |
| 409 | **done (BE)**: `POST /api/v1/production/orders/{id}/material-issues {requestId, toLocation, reservationId?}` (production-material-issues:CREATE), `GET` same (READ). One scan = one whole hold to a PRODUCTION area of the LSX's warehouse; ORDER holds = the order line's, SAMPLE/reprint holds = the LSX's; reprint/resumed round holds the shortfall at scan time (too few → 409 `INSUFFICIENT_STOCK`, nothing held). BR-PRD-03 cap (`ISSUE_EXCEEDS_REQUIREMENT`), READY → MATERIAL_ISSUED when nothing more is needed. Sample blanks held at submit (Q1) | see git log | `MaterialIssueIntegrationTest` 6 (ORDER multi-hold, same ids still HELD, ATP unchanged, ledger; Q3 release-after-issue stays unsellable; replay; refusals: other order's hold, unpinned, non-PRODUCTION area, before prepress; SAMPLE hold at submit + rollback when short; concurrent scans and twin replays), `ProductionSecurityIntegrationTest.materialIssueOverHttp` |
| 411 | **done (BE)**: start, output with scrap reasons, write-off without waiting (Q4), reprint hold / BLANKS_SHORT | see git log | `PrintRunIntegrationTest` (orderFromPressToPacking, rejectedWriteOffNeverSells, blanksShort), `ProductionOrderTest` +4 |
| 418 | **done (BE)**: QC rounds, single exit owner, QC resource | see git log | `PrintRunIntegrationTest` (QC fail → reprint, concurrentQc), HTTP `printShopStepsOverHttp` |
| 420 | **done (BE)**: ORDER → PACKING held + `ProductionCompleted` once; SAMPLE → SAMPLE write-off + round READY | see git log | `orderFromPressToPacking`, `sampleCompletes`, `concurrentQc` |
| 413 | **done (BE)** (Q2 money: agreed fee on the order cancellation): cancel before printing, stop on the press (Q2 quantities), `OrderCancelled` listener | see git log | `cancelBeforePrinting`, `orderCancelledUnderTheJob`, `stopOnThePress` |
| 415 | code complete: `GET /api/v1/production/dashboard?from&to` (open by status, overdue, printed/scrap/scrap rate of LSX **created** in the period — the answer carries `periodBasis`, `periodFrom`, `periodTo`; QA3 open), `GET …/dashboard/overdue`, `GET …/dashboard/samples-awaiting-approval` (paginated); production-orders:READ | `60ad09b` | `PrintRunIntegrationTest.dashboard`*, HTTP `dashboardOverHttp`* |
| 441 | code complete (see Subcontracting above; QA1/QA2 defaults pending confirmation) | `afb3a58`, `60ad09b` | `ProductionOrderTest` (+5), `PrintRunIntegrationTest.subcontractFullService`*, `subcontractSuppliedBlanks`*, `subcontractReconciliationExample`*, `subcontractReceiptGetsOneQc`*, `wholeOrderConversion`*, `shortfallTakenInHouse`*, `subcontractCancelledAndReplaced`*, `subcontractedOrderOfACancelledOrder`*, `SubcontractPurchasingIntegrationTest`*, `SubcontractMigrationUpgradeTest`*, HTTP `subcontractEndpointsOverHttp`* |
| notification | lane B (`56832ca`): `ProductionOrderHeld` → e-mail to active Sales (FILE_REJECTED, WAITING_CUSTOMER), procurement (BLANKS_SHORT), warehouse managers (others), SMTP after commit. Lane A publishes the event, contract unchanged | lane B | lane B `ProductionNotificationListenerTest` 7 |

## Acceptance → code → test (decisions of 2026-10-12)

| Acceptance | Code | Test |
|---|---|---|
| Q5 cancel a sample LSX: round CANCELLED, history kept, new round allowed, holds again, no inherited answer | `SampleRequest.productionCancelled/nextRound`, `PrintRunService.sampleRoundCancelled` | `PrintRunIntegrationTest.sampleCancelledBeforePrinting`, `sampleStoppedAfterPrinting`, `SampleRequestTest` |
| FIFO second hold/move of one receipt layer; conservation | V20261014000450, `StockItemRepository.saveMerged` | `SameReceiptMergeIntegrationTest` (4) |
| Receipt-time failure reproduced, time consistent | V20261014000450 (1 minute skew) | `SameReceiptMergeIntegrationTest.receiptTime`, `SupplierProcurementIntegrationTest` |
| Fulfilment sees all good goods incl. production holds, no cross-order leak | `InventoryService.handHoldToOrder`, `OrderReservations.addProductionHolds` | `orderFromPressToPacking`, `subcontractFullService` |
| Q6a fee > paid → receivable for the difference, once | `Order.cancel`, `ReceivableService.cancellationOwed`, V20261014000460 | `PrintRunIntegrationTest.agreedFeeAboveWhatWasPaid` |
| Q6b paid > fee → refund request for the excess; equal → nothing | `Order.cancel` (refundable = paid − retained), payment refund flow | `agreedFeeAboveWhatWasPaid`, cancellation tests |
| Q7 per subcontractor + warehouse, not ATP, traceable, no duplicate send | `SubcontractorSiteRepository`, `SubcontractService.sendBlanks`, `WorkshopStock.sendToSubcontractor` | `subcontractSuppliedBlanks` |
| Q8 finished goods stay the child's/order's through receiving and QC | `SubcontractReceiptListener` (BEFORE_COMMIT), `holdReceived`, `WorkshopStock.holdForProductionQc` (QC area) | `subcontractFullService`*, `subcontractReceiptGetsOneQc`* |
| Q9 consumption, spoilt, returned, partial receipts, QC fail ≠ blank | `WorkshopStock.consumeAtSubcontractor/reconcile`, `SubcontractService.reconcile`, V20261014000470 | `subcontractSuppliedBlanks`, `subcontractReconciliationExample` |

## Acceptance → code → test (SCRUM-433/441 finishing pass, 2026-10-13)

`*` = Testcontainers test written; **not run yet** (Docker unavailable on this machine at the time of writing).
Domain, architecture and modularity tests ran green on `60ad09b` (offline Maven on D:).

| Jira 433 / 441 acceptance | Code | Test |
|---|---|---|
| ORDER only (BR-PRD-11) | `ProductionOrder.splitOff/convertToSubcontract` | `ProductionOrderTest.aSplitTakes…`, `theWholeOrderConverts…` |
| partial split; whole LSX converts (BR-PRD-12) | `SubcontractService.split/convert`, `ProductionOrder.wholeOrder` | `wholeOrderConversion`*, `ProductionOrderTest.theWholeOrderConverts…` |
| two modes, blanks moved/released | `SubcontractService.moveHolds` | `subcontractFullService`*, `subcontractSuppliedBlanks`*, `wholeOrderConversion`* |
| SUBCONTRACT PO DRAFT, send only once approved (BR-PRD-13) | `SubcontractPurchasingImpl.create`, `SubcontractService.sendOut` | `subcontractFullService`* |
| dispatch with PRINT_READY file + approved sample (BR-PRD-14/15), blanks to the subcontractor location outside ATP | `SubcontractService.sendBlanks/dispatchNote` | `shortfallTakenInHouse`*, `subcontractSuppliedBlanks`* |
| back in → QC against the approved sample, one QC, no receiving QC (user decision) | `GoodsReceiptServiceImpl.replaceLines/confirm`, `GoodsReceipt.confirm(…, handedToProduction)`, `SubcontractService.onReceipt`, `WorkshopStock.holdForProductionQc` | `subcontractReceiptGetsOneQc`* (SKU with `qc_required`), `subcontractFullService`* |
| partial receipts | `ProductionOrder.receivedFromSubcontractor` | `subcontractFullService`*, `subcontractSuppliedBlanks`* |
| shortfall → resend with PO supplement (approved amendment) | `SubcontractPurchasingImpl.supplement/approveAmendment`, `SubcontractService.sendOut` (open quantity) | `subcontractSuppliedBlanks`*, `SubcontractPurchasingIntegrationTest.lifecycle`* |
| shortfall → in-house child | `ProductionOrder.giveBackToPrintShop/inHouseFor`, `SubcontractService.takeInHouse` | `shortfallTakenInHouse`*, `ProductionOrderTest.aShortfallGoesInHouse…` |
| ProductionCompleted per LSX; order waits for all (BR-PRD-06) | `ProductionCompletion.complete` | `subcontractFullService`*, `shortfallTakenInHouse`* |
| SUBCONTRACTED → ON_HOLD (late) | `ProductionOrder.hold` | `subcontractReceiptGetsOneQc`*, `ProductionOrderTest.goodsReachingAHeldOrder…` |
| SUBCONTRACTED → CANCELLED: recall blanks, PO cancelled, or fee | `SubcontractService.recall/cancel`, `SubcontractPurchasingImpl.closeForProduction` | `subcontractCancelledAndReplaced`*, `subcontractedOrderOfACancelledOrder`* |
| reconciliation sent / good / scrap / unused / loss (422 comment) | `SubcontractService.reconcile/recall`, `SubcontractSettling.settle` | `subcontractReconciliationExample`*, `shortfallTakenInHouse`* |
| loss > tolerance needs manager approval → deduction; QC-failed not paid (BR-PRD-17) | `SubcontractPurchasingImpl.settle/approveLoss`, `procurement.subcontract_settlement` | `SubcontractPurchasingIntegrationTest.settlement`*, `shortfallTakenInHouse`* |
| permissions of the new steps | `ProductionOrderController`, `PurchaseOrderController` | `subcontractEndpointsOverHttp`* |
| upgrade of existing rows | V20261014000700, V20261014000710 | `SubcontractMigrationUpgradeTest`* |
