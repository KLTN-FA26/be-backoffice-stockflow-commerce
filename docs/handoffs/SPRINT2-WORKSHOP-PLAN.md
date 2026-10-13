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

## Subcontracting (BE 441, built)

| Step | Endpoint | Permission | Effect |
|---|---|---|---|
| split (G1–G3) | `POST …/{id}/subcontract-split {quantity, subcontractorId, method, unitPrice, currency?, dueDate?}` | production-orders:APPROVE (manager) | BR-PRD-11/12: ORDER, in house, PENDING_PREPRESS/READY/ON_HOLD, 0 < quantity < unissued. Parent keeps its status, planned −quantity. Child SUBCONTRACTED-mode, READY or PENDING_PREPRESS; SUBCONTRACT PO DRAFT via `procurement :: api`. Holds: FULL_SERVICE — the order's unissued holds give up the quantity (ATP +q); SUPPLIED_BLANKS — q held for the child instead (ATP unchanged) |
| send out (G5–G6) | `POST …/{id}/subcontract-dispatch` | production-material-issues:CREATE (warehouse) | BR-PRD-13: PO approved; READY → SUBCONTRACTED (a second dispatch is refused: not READY). SUPPLIED_BLANKS: the child's unsent holds go to `<prefix>-SUB-<supplier>` (Q7), one `production_material_issue` per hold; after a shortfall the missing blanks are held first (G9 "gửi bù") |
| receive back (G7/G9) | goods receipt confirm (`GoodsReceiptConfirmed`, before commit) | goods-receipts (existing) | once per receipt (`production_subcontract_receipt`); each line held for the child and handed to PRODUCTION for QC (Q8); SUPPLIED_BLANKS: as many blanks consumed at the subcontractor (Q9); SUBCONTRACTED/QC → QC, or READY → QC for the rest after a short QC round |
| reconcile (G8, Q9) | `POST …/{id}/subcontract-receipts/{receiptId}/reconciliation {spoilt, returned, returnLocation?}` | production-material-issues:CREATE | once per receipt (same answer = replay, different = 409); spoilt → `SUBCONTRACT_LOSS` adjustment (approval); returned → free; more than is there → 400; the rest stays |
| receipts | `GET …/{id}/subcontract-receipts` | production-material-issues:READ | received, used, spoilt, returned, reconciled by/at |
| QC / complete | `POST …/{id}/qc-results` | production-qc-results:CREATE | as in house; SUPPLIED_BLANKS refused until every receipt is reconciled (BR-PRD-16); COMPLETED → PACKING, held for the order, on its line, `ProductionCompleted` |

Remaining gaps (not built, need a decision or another lane):
- A receipt line whose SKU requires receiving QC (3-step flow, `qc_required`): the goods are held and handed
  to the print shop at confirmation, so the receiving `move-to-qc` step finds nothing free to move. The demo
  blank SKU is 2-step. Should a SUBCONTRACT PO skip receiving QC (production QC replaces it, as Q9 says), or
  go through both? — business question.
- A receipt counting more SUPPLIED_BLANKS goods than blanks at the subcontractor is refused (the
  confirmation fails with a clear message): goods printed on our blanks cannot exceed the blanks sent.
- BR-PRD-17 tolerance (loss above the subcontractor's tolerance → invoice deduction) is not computed: every
  SUBCONTRACT_LOSS goes through the adjustment approval (stricter), nothing is deducted from the invoice.
- A shortfall beyond the PO quantity (QC failures) needs a PO supplement (procurement); the child then stays
  READY. Cancelling a SUBCONTRACTED child (recall), converting a whole LSX, SUBCONTRACTOR_LATE warnings: not
  built. Blanks still at the subcontractor when the child completes stay held for it there until reconciled.

## Lane C (441/415) collaboration plan

Lane C should start only after this branch has the workflow aggregate (after 418/420), because 433/441
(split → SUBCONTRACTED child, subcontract goods receipt → QC) adds transitions to the same aggregate and
`ck_production_order_branch`. Proposed order: A finishes 407–413 → A publishes the final aggregate API in this
file → C branches from A's checkpoint for 441, then 415 (dashboard: read-only queries over production
tables, can start in parallel on read models only). If the coordinator prefers, A does 441/415 sequentially.

## Progress (update at every checkpoint)

| BE | State | Commit | Tests |
|---|---|---|---|
| 407 | **done (BE)**: `GET …/{id}/print-file` (READ), `POST …/{id}/prepress {decision PASS\|FAIL, note}` (UPDATE; PASS re-hashes the stored file, 409 `PRINT_FILE_MISMATCH`; FAIL → ON_HOLD `FILE_REJECTED` + `ProductionOrderHeld`), `POST …/{id}/hold {reason, note}` (UPDATE), `POST …/{id}/resume` (APPROVE, Warehouse Manager). Lock-before-load on the order row. Not yet: notification delivery to Sales (event published; listener not written) | see git log | `ProductionOrderTest` 8, `ProductionWorkflowIntegrationTest` 3 (real file re-hash, tampered file, concurrent holds) |
| 409 | **done (BE)**: `POST /api/v1/production/orders/{id}/material-issues {requestId, toLocation, reservationId?}` (production-material-issues:CREATE), `GET` same (READ). One scan = one whole hold to a PRODUCTION area of the LSX's warehouse; ORDER holds = the order line's, SAMPLE/reprint holds = the LSX's; reprint/resumed round holds the shortfall at scan time (too few → 409 `INSUFFICIENT_STOCK`, nothing held). BR-PRD-03 cap (`ISSUE_EXCEEDS_REQUIREMENT`), READY → MATERIAL_ISSUED when nothing more is needed. Sample blanks held at submit (Q1) | see git log | `MaterialIssueIntegrationTest` 6 (ORDER multi-hold, same ids still HELD, ATP unchanged, ledger; Q3 release-after-issue stays unsellable; replay; refusals: other order's hold, unpinned, non-PRODUCTION area, before prepress; SAMPLE hold at submit + rollback when short; concurrent scans and twin replays), `ProductionSecurityIntegrationTest.materialIssueOverHttp` |
| 411 | **done (BE)**: start, output with scrap reasons, write-off without waiting (Q4), reprint hold / BLANKS_SHORT | see git log | `PrintRunIntegrationTest` (orderFromPressToPacking, rejectedWriteOffNeverSells, blanksShort), `ProductionOrderTest` +4 |
| 418 | **done (BE)**: QC rounds, single exit owner, QC resource | see git log | `PrintRunIntegrationTest` (QC fail → reprint, concurrentQc), HTTP `printShopStepsOverHttp` |
| 420 | **done (BE)**: ORDER → PACKING held + `ProductionCompleted` once; SAMPLE → SAMPLE write-off + round READY | see git log | `orderFromPressToPacking`, `sampleCompletes`, `concurrentQc` |
| 413 | **done (BE)** (Q2 money: agreed fee on the order cancellation): cancel before printing, stop on the press (Q2 quantities), `OrderCancelled` listener | see git log | `cancelBeforePrinting`, `orderCancelledUnderTheJob`, `stopOnThePress` |
| 415 | **done (BE)**: `GET /api/v1/production/dashboard?from&to` (open by status, overdue, printed/scrap/scrap rate of LSX created in the period, samples awaiting the customer), `GET …/dashboard/overdue`, `GET …/dashboard/samples-awaiting-approval` (paginated, through the existing lists, same warehouse scope); production-orders:READ. Permission resources of 415 already existed (V20261010000500 + this lane's two) | see git log | `PrintRunIntegrationTest.dashboard`, HTTP `dashboardOverHttp` |
| 441 | **done (BE)**: split + PO + holds, dispatch with blanks to the subcontractor location (Q7), receipt held for the child (Q8), consumption + reconciliation (Q9), QC, completion to the order; gaps above | see git log | `ProductionOrderTest`, `PrintRunIntegrationTest.subcontractFullService`, `subcontractSuppliedBlanks`, `subcontractReconciliationExample` |
| notification | **done (BE, log transport like every non-PO template)**: `ProductionOrderHeld` → Sales (FILE_REJECTED, WAITING_CUSTOMER), purchasing (BLANKS_SHORT), workshop (others) | see git log | `ProductionNotificationListenerTest` 3 |

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
| Q8 finished goods stay the child's/order's through receiving and QC | `SubcontractReceiptListener` (BEFORE_COMMIT), `holdReceived`, `handReceivedToPrintShop` | `subcontractFullService` (free at receiving = 0, counted once) |
| Q9 consumption, spoilt, returned, partial receipts, QC fail ≠ blank | `WorkshopStock.consumeAtSubcontractor/reconcile`, `SubcontractService.reconcile`, V20261014000470 | `subcontractSuppliedBlanks`, `subcontractReconciliationExample` |
