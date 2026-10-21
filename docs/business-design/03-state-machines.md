# State Machines

Eighteen machines. For each: the diagram, then a transition table giving the **trigger**, the
**actor allowed to fire it**, the **guard** that must hold, and the **event emitted**.

Rules that apply to every machine on this page:

- A transition not listed is **forbidden**, and the aggregate must reject it — not the controller.
- Terminal states are marked. Nothing leaves them; a correction creates a new document.
- Every transition that other services care about emits an event. If no event is listed, the
  transition is private to the owning service.
- Guards are business rules and carry a `BR-` id from `05-business-rules.md`.

---

## 1. Product — `product-service`

WBS 3.1.1.3 (Draft → Pending → Approved), 3.1.8.1 (discontinuation), SCRUM-461 (suspension, brand, reason, history).

```mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> PENDING_APPROVAL: submit
    PENDING_APPROVAL --> DRAFT: reject
    PENDING_APPROVAL --> APPROVED: approve
    APPROVED --> PUBLISHED: publish to catalog
    PUBLISHED --> APPROVED: unpublish
    APPROVED --> INACTIVE: suspend
    PUBLISHED --> INACTIVE: suspend
    INACTIVE --> APPROVED: resume
    APPROVED --> DISCONTINUED: discontinue
    PUBLISHED --> DISCONTINUED: discontinue
    INACTIVE --> DISCONTINUED: discontinue
    DISCONTINUED --> [*]
```

| From | To | Trigger | Actor | Guard | Event |
|---|---|---|---|---|---|
| — | DRAFT | create | E-commerce admin | SKU unique (BR-PRD-002) | — |
| DRAFT | PENDING_APPROVAL | submit | E-commerce admin | all mandatory fields present, brand included (BR-PRD-001) | — |
| PENDING_APPROVAL | APPROVED | approve | E-commerce admin (approver) | approver ≠ submitter (BR-PRD-003) | `ProductApproved` |
| PENDING_APPROVAL | DRAFT | reject | E-commerce admin (approver) | rejection reason given | — |
| APPROVED | PUBLISHED | publish | E-commerce admin | has price, image, category (BR-PRD-004) | `ProductPublished` |
| PUBLISHED | APPROVED | unpublish | E-commerce admin | — | `ProductUnpublished` |
| APPROVED / PUBLISHED | INACTIVE | suspend | `product-lifecycle:APPROVE` (System Admin only, kltn-docs 01 §5.1) | reason given (BR-PRD-007) | `CatalogListingChanged` |
| INACTIVE | APPROVED | resume | `product-lifecycle:APPROVE` (System Admin only, kltn-docs 01 §5.1) | — (publish again to show it) | `CatalogListingChanged` |
| APPROVED / PUBLISHED / INACTIVE | DISCONTINUED | discontinue | `product-lifecycle:APPROVE` (System Admin only, kltn-docs 01 §5.1) | reason given (BR-PRD-007). Open POs and unshipped orders are **warned about, not blocked** (BR-PRD-005) | `ProductDiscontinued` (with the reason) |

**INACTIVE** (kltn-docs 01 §5.1): not shown on the storefront, no new purchase order lines; orders and
receipts already running continue. Its variants and images are still maintained.

**DISCONTINUED is terminal.** Every variant becomes `OBSOLETE` (kltn-docs 01 §5.2: shipped and adjusted
only to clear the stock). Existing stock stays sellable-through, goods on open purchase orders are received
as usual, but no new purchase order line takes the SKU. Only a DRAFT product is deleted (BR-PRD-008).
Every move is written to `product.product_change_log` with its from/to status, reason and actor.

---

## 2. Purchase Order — `procurement-service`

WBS 3.2.4.3 names `Draft → Confirmed → Received → Closed`; approval (3.2.2) and amendment
(3.2.5) add the states between.

```mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> PENDING_APPROVAL: submit
    PENDING_APPROVAL --> DRAFT: reject
    PENDING_APPROVAL --> APPROVED: approve
    APPROVED --> SENT: send to supplier
    SENT --> CONFIRMED: supplier confirms
    SENT --> CANCELLED: cancel
    CONFIRMED --> PARTIALLY_RECEIVED: first receipt posted
    CONFIRMED --> RECEIVED: full receipt posted
    PARTIALLY_RECEIVED --> PARTIALLY_RECEIVED: another receipt
    PARTIALLY_RECEIVED --> RECEIVED: open qty reaches zero
    PARTIALLY_RECEIVED --> CLOSED_SHORT: close short
    RECEIVED --> CLOSED: invoice matched and approved
    CLOSED --> [*]
    CLOSED_SHORT --> [*]
    CANCELLED --> [*]
```

| From | To | Trigger | Actor | Guard | Event |
|---|---|---|---|---|---|
| — | DRAFT | create | Procurement staff | supplier active, ≥1 line | — |
| DRAFT | PENDING_APPROVAL | submit | Procurement staff | total ≥ 0, MOQ respected (BR-PO-001) | — |
| PENDING_APPROVAL | APPROVED | approve | approver per threshold | approver's limit ≥ PO total (BR-PO-002) | `PurchaseOrderApproved` |
| PENDING_APPROVAL | DRAFT | reject | approver | reason given | — |
| APPROVED | SENT | send | Procurement staff | supplier has email or API endpoint | `PurchaseOrderSent` |
| SENT | CONFIRMED | supplier confirms | Procurement staff (records it) | — | `PurchaseOrderConfirmed` |
| SENT | CANCELLED | cancel | Procurement staff | nothing received yet | `PurchaseOrderCancelled` |
| CONFIRMED / PARTIALLY_RECEIVED | PARTIALLY_RECEIVED | receipt posted | system | receipt total < ordered | — |
| CONFIRMED / PARTIALLY_RECEIVED | RECEIVED | receipt posted | system | open qty = 0, within over-receipt tolerance (BR-RCP-001) | `PurchaseOrderFullyReceived` |
| PARTIALLY_RECEIVED | CLOSED_SHORT | close short | Procurement staff | reason given; remaining qty written off | `PurchaseOrderClosedShort` |
| RECEIVED | CLOSED | invoice approved | Accountant | three-way match passed or override approved (BR-INV-003) | `PurchaseOrderClosed` |

**Amendment (WBS 3.2.5)** is not a state. Amending a PO in `SENT` or later creates
**revision n+1** of the same PO number and emits `PurchaseOrderAmended`; the previous revision is
retained for the audit trail. Amendment is forbidden once any receipt exists (BR-PO-004).

---

## 3. Goods Receipt — `procurement-service`

**Changed 2026-10-09** to the receiving docs (KLTN-FA26/docs 03 Receipt, 05 Putaway, commit
`084365d`) and migration `V20260929000250`. Receiving is a 2-step or 3-step flow, chosen per SKU by
the inventory item's **QC required** flag (snapshotted on the receipt line):

| Flow | SKU | Path |
|---|---|---|
| 3 steps | `qc_required` | supplier → Area `RECEIVING` → Area `QUALITY_CONTROL` → bin |
| 2 steps | not QC-required | supplier → Area `RECEIVING` → bin |

```mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> CONFIRMED: confirm counts
    DRAFT --> CANCELLED: cancel
    CONFIRMED --> IN_QC: a QC-required line has no verdict yet
    CONFIRMED --> IN_PUTAWAY: no line needs QC
    IN_QC --> IN_PUTAWAY: every QC line has a verdict
    IN_QC --> CLOSED: nothing accepted is left to put away
    IN_PUTAWAY --> CLOSED: putaway done, rejected returned, quarantine decided
    CLOSED --> [*]
    CANCELLED --> [*]
```

| From | To | Trigger | Actor | Guard | Event |
|---|---|---|---|---|---|
| — | DRAFT | create from PO | Warehouse staff | PO `CONFIRMED` / `PARTIALLY_RECEIVED` (BR-RCP-001) | — |
| DRAFT | CONFIRMED | confirm | Warehouse staff | lot / expiry where required (BR-RCP-003); over-receipt within tolerance, else manager approval | `GoodsReceived` |
| DRAFT | CANCELLED | cancel | Warehouse staff | never confirmed (`ck_goods_receipts_cancelled`) | — |
| CONFIRMED | IN_QC | — | system | ≥1 line with `qc_required`; a **move-to-QC task** (`move_task.origin = RECEIPT_QC`) is created per such line | — |
| CONFIRMED | IN_PUTAWAY | — | system | no line needs QC; putaway tasks from `RECEIVING` | — |
| IN_QC | IN_PUTAWAY | last QC verdict | QC staff | QC only on goods already in the QC area (BR-08); accepted + quarantined + rejected = moved quantity | `QcCompleted` |
| IN_PUTAWAY / IN_QC | CLOSED | — | system | every line handled | — |

**What confirming does to inventory.** Stock rows appear in the `RECEIVING` area in condition
**INBOUND** (machine 5). A QC-required line then moves `RECEIVING → QUALITY_CONTROL` (still INBOUND);
QC splits it: **accepted** → putaway task from the QC area; **quarantine** → Area `QUARANTINE`,
condition QUARANTINE; **rejected** → Area `QUARANTINE`, condition BLOCKED, return to vendor. A
confirmed receipt is never deleted; mistakes are reversals or adjustments with a reason (BR-05).

---

## 4. Supplier Invoice — `procurement-service`

WBS 3.4.3 (three-way match), 3.4.4 (exceptions), 3.4.5.2 (Pending/Paid).

```mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> MATCHING: link PO and receipt
    MATCHING --> MATCHED: within tolerance
    MATCHING --> EXCEPTION: outside tolerance
    EXCEPTION --> MATCHED: override approved
    EXCEPTION --> DISPUTED: raise with supplier
    DISPUTED --> MATCHING: supplier issues credit note
    MATCHED --> APPROVED_FOR_PAYMENT: approve
    APPROVED_FOR_PAYMENT --> PAID: payment recorded
    PAID --> [*]
    DRAFT --> VOID: void
    EXCEPTION --> VOID: void
    VOID --> [*]
```

| From | To | Trigger | Actor | Guard | Event |
|---|---|---|---|---|---|
| — | DRAFT | enter invoice | Accountant | supplier invoice number unique per supplier (BR-INV-001) | — |
| DRAFT | MATCHING | link | Accountant | at least one PO and one receipt linked | — |
| MATCHING | MATCHED | auto | system | qty and price within tolerance (BR-INV-002) | `InvoiceMatched` |
| MATCHING | EXCEPTION | auto | system | outside tolerance | `InvoiceMatchException` |
| EXCEPTION | MATCHED | override | Accountant with approval limit | approver's limit ≥ variance (BR-INV-003) | `InvoiceOverrideApproved` |
| EXCEPTION | DISPUTED | dispute | Accountant | reason recorded | — |
| DISPUTED | MATCHING | credit note posted | Accountant | credit note linked | — |
| MATCHED | APPROVED_FOR_PAYMENT | approve | Accountant | within payment approval limit | `InvoiceApprovedForPayment` |
| APPROVED_FOR_PAYMENT | PAID | record payment | Accountant | — | `SupplierInvoicePaid` |

---

## 5. Stock condition — `inventory-service`

This machine governs the **condition** dimension only. Quantities (on hand / reserved /
allocated) are numbers on the same record, not states — see `01-ubiquitous-language.md`.
Stored as `inventory.stock_item.status`; `GOOD` in older text is `AVAILABLE`.

```mermaid
stateDiagram-v2
    [*] --> INBOUND: receipt confirmed (RECEIVING / QUALITY_CONTROL area)
    INBOUND --> AVAILABLE: putaway (QC accepted, or not required)
    INBOUND --> QUARANTINE: QC puts on hold
    INBOUND --> BLOCKED: QC rejects
    QUARANTINE --> AVAILABLE: re-inspected and accepted, then put away
    QUARANTINE --> BLOCKED: re-inspected and rejected
    BLOCKED --> [*]: returned to vendor
    AVAILABLE --> DAMAGED: damage reported
    AVAILABLE --> EXPIRED: expiry date passes
    AVAILABLE --> QUARANTINE: recall or re-inspection
    DAMAGED --> [*]: written off
    EXPIRED --> [*]: written off
```

| From | To | Trigger | Actor | Guard | Event |
|---|---|---|---|---|---|
| — | INBOUND | receipt confirmed | system | — | `StockReceived` |
| INBOUND | AVAILABLE | putaway completed | Warehouse staff | QC accepted or not required (docs 05 BR-01) | `StockPutAway` |
| INBOUND | QUARANTINE / BLOCKED | QC verdict | QC staff | reason given | `QcCompleted` |
| QUARANTINE | AVAILABLE | re-inspection accepts | QC staff | then putaway from the `QUARANTINE` area | `StockReleasedFromQuarantine` |
| QUARANTINE | BLOCKED | re-inspection rejects | QC staff | reason given | `StockBlocked(BLOCKED)` |
| BLOCKED | left stock | return to vendor | Procurement staff | movement out of the warehouse | `ReturnToVendorRaised` |
| AVAILABLE | DAMAGED | report damage | Warehouse staff | reason and photo (BR-STK-003) | `StockBlocked(DAMAGED)` |
| AVAILABLE | EXPIRED | nightly sweep | system | `expiry_date < today` (BR-STK-004) | `StockBlocked(EXPIRED)` |
| AVAILABLE | QUARANTINE | recall | Warehouse manager | reason given | `StockBlocked(QUARANTINE)` |
| DAMAGED / EXPIRED | written off | write-off | Warehouse manager | approved adjustment (BR-STK-005) | `StockWrittenOff` |

**Only AVAILABLE counts towards ATP.** That single sentence is the reason this dimension is separate
from the quantity buckets: inbound, quarantined and blocked stock is on hand, occupies its location, appears in a
physical count, and is invisible to the storefront.

**Reserved stock whose condition turns bad.** If AVAILABLE stock with an outstanding reservation moves
to DAMAGED or EXPIRED, the reservation is *not* silently dropped — `inventory-service` emits
`ReservationImpaired`, and `order-service` puts the order on hold with reason
`INVENTORY_ISSUE` (WBS 3.17.6.1). Dropping it quietly is how a paid order never ships.

---

## 6. Stock reservation — `inventory-service`

WBS 3.14.5.1 (time-limited) and 3.14.5.2 (expiry and auto-release).

```mermaid
stateDiagram-v2
    [*] --> HELD: reserve at checkout
    HELD --> ALLOCATED: order released to warehouse
    HELD --> RELEASED: cancelled, payment failed, or expired
    ALLOCATED --> CONSUMED: picked and deducted
    ALLOCATED --> RELEASED: order cancelled before pick
    RELEASED --> [*]
    CONSUMED --> [*]
```

| From | To | Trigger | Actor | Guard | Event |
|---|---|---|---|---|---|
| — | HELD | reserve | system, at checkout | ATP ≥ requested (BR-STK-001) | `StockReserved` |
| HELD | RELEASED | expiry sweep | system | `expires_at < now` (BR-STK-002) | `StockReservationReleased(RESERVATION_EXPIRED)` |
| HELD | RELEASED | payment failed | system | — | `StockReservationReleased(PAYMENT_FAILED)` |
| HELD | RELEASED | customer cancels | Customer / Sales staff | order not yet released | `StockReservationReleased(ORDER_CANCELLED)` |
| HELD | ALLOCATED | order released | Order coordinator | a location and lot chosen by FEFO (BR-STK-006) | `StockAllocated` |
| ALLOCATED | CONSUMED | pick confirmed | Warehouse staff | picked qty = allocated qty | `StockDeducted` |
| ALLOCATED | RELEASED | cancel after allocation | Order coordinator | not yet picked; put-back task raised (WBS 3.7.7.2) | `StockReservationReleased(ORDER_CANCELLED)` |

**As built (SCRUM-152, 2026-10-17).** Both kinds live in `inventory.stock_reservation`. A SKU reservation
(`stock_item_id` NULL) is the HELD row above: checkout, payment pin/re-arm, expiry sweep, cancellation. Release
to a warehouse turns it ALLOCATED and writes one allocation per bin and lot (`stock_item_id` set,
`allocated_from_id` = the reservation, `expires_at` NULL); each allocation then goes HELD → CONSUMED (pick) or
RELEASED (cancel, or `SHORT_PICKED` when X-SHORT reallocates it elsewhere). The order line swaps the reservation id
for the allocation ids. Production holds and checkout holds placed before this change are stock-item rows that
were never SKU reservations; they keep their old lifecycle.

**The reservation clock is the most under-specified part of the BRD.** WBS 3.14.5.1 says
"time-limited" without saying how long. See `06-open-questions.md`, OQ-03.

---

## 7. Putaway task — `warehouse-service`

WBS 3.3.6 (generation), 3.5.4 (management).

```mermaid
stateDiagram-v2
    [*] --> CREATED: receipt posted
    CREATED --> ASSIGNED: assign to staff
    ASSIGNED --> IN_PROGRESS: staff scans first item
    IN_PROGRESS --> COMPLETED: destination scanned and confirmed
    IN_PROGRESS --> EXCEPTION: suggested location unusable
    EXCEPTION --> IN_PROGRESS: manager overrides location
    CREATED --> CANCELLED: cancel
    COMPLETED --> [*]
    CANCELLED --> [*]
```

| From | To | Trigger | Actor | Guard | Event |
|---|---|---|---|---|---|
| — | CREATED | receipt posted | system | slotting engine returns ≥1 suggestion (BR-SLT-001) | `PutawayTaskCreated` |
| CREATED | ASSIGNED | assign | Warehouse manager, or auto | staff assigned to that warehouse | — |
| ASSIGNED | IN_PROGRESS | scan item | Warehouse staff | scanned SKU matches the task | — |
| IN_PROGRESS | COMPLETED | confirm location | Warehouse staff | scanned location = suggested, or override approved (BR-SLT-002) | `PutawayCompleted` |
| IN_PROGRESS | EXCEPTION | report blocked | Warehouse staff | reason given | — |
| EXCEPTION | IN_PROGRESS | override | Warehouse manager | new location passes hard constraints (BR-SLT-003) | — |

`PutawayCompleted` is what moves stock from the receiving area to a real bin. Until then, the
stock exists with the location code of a `RECEIVING` area (e.g. `HCM-RCV01`) and is **not**
pickable.

---

## 8. Design snapshot — `design-service`

WBS 3.12.5 (immutable artifact), 3.12.4 (preflight), 3.8.3 (verification at packing).

```mermaid
stateDiagram-v2
    [*] --> DRAFT: customer starts designing
    DRAFT --> DRAFT: edit and save
    DRAFT --> PREFLIGHT_FAILED: preflight rejects
    PREFLIGHT_FAILED --> DRAFT: customer fixes
    DRAFT --> CONFIRMED: customer confirms
    CONFIRMED --> LOCKED: attached to a placed order
    LOCKED --> [*]
    DRAFT --> ABANDONED: 30 days idle
    ABANDONED --> [*]
```

| From | To | Trigger | Actor | Guard | Event |
|---|---|---|---|---|---|
| — | DRAFT | open the studio | Customer / Sales staff | SKU has a print configuration | — |
| DRAFT | PREFLIGHT_FAILED | confirm attempted | system | DPI, bleed, safe area or colour mode fails (BR-DSG-001) | — |
| DRAFT | CONFIRMED | confirm | Customer / Sales staff | preflight passed | `DesignConfirmed` |
| CONFIRMED | LOCKED | order placed | system | order line references this snapshot | — |
| DRAFT | ABANDONED | idle sweep | system | untouched 30 days and not on an order | — |

**A CONFIRMED snapshot is never edited.** "Changing the design" creates a **new snapshot** and
re-points the order line, which is allowed only while the order is in `PENDING_PAYMENT` or
`CONFIRMED` (BR-DSG-002). After that the print file may already be at the press.

The checksum stored on the order line is compared again at packing (WBS 3.8.3). A mismatch is not
a warning — it stops the pack and raises an exception, because it means the parcel does not
contain what the customer approved.

---

## 9. Order — `order-service`

The central machine. WBS 3.17.1.1 spells it out:
`Pending Payment → Confirmed → Ready to Fulfill → In Production → Picking → Packed → Shipped →
Delivered → Completed/Cancelled`. Hold (3.17.6) and RMA (3.17.5) attach to it.

**Changed 2026-10-06** (B2B only, production module): an order is a wholesale order created from
an accepted quote or on the customer portal. A **credit** order skips `PENDING_PAYMENT` — it is
`CONFIRMED` within the customer's credit limit, `ON_HOLD` (reason `CREDIT`) above it. Release
sends print lines to production **before** the order is ready to fulfil: `CONFIRMED →
IN_PRODUCTION → READY_TO_FULFILL`, and the order leaves `IN_PRODUCTION` only when every production
order of the order is completed (machine 16).

**Implemented 2026-10-19 (G3-2, SCRUM-239/246/247).** The statuses of kltn-docs 17 §5, written twice: the
exhaustive switch `OrderStatus.canTransitionTo` and the table `ordering.order_status_transition`, which the trigger
`tg_order_status_transition` enforces on every `UPDATE` of a status (the two are compared by
`OrderStatusTest`, `OrderLifecycleIntegrationTest` and `OrderLifecycleMigrationUpgradeTest`). `IN_FULFILMENT` is gone
(rows became `PICKING`, or `PACKED` when a package was completed); there is no `PAYMENT_FAILED` (G3-Q2: a bank
transfer that never comes is a cancellation when the hold runs out). Every change writes a history row with who made
it and why (`order_status_history.actor_id`, `reason`).

```mermaid
stateDiagram-v2
    [*] --> PENDING_PAYMENT: prepay / deposit order placed
    [*] --> CONFIRMED: credit order within limit
    [*] --> ON_HOLD: credit order over limit (hold CREDIT)
    PENDING_PAYMENT --> CONFIRMED: paid in full / deposit in
    CONFIRMED --> IN_PRODUCTION: released, has print lines
    CONFIRMED --> READY_TO_FULFILL: released, no print lines
    IN_PRODUCTION --> READY_TO_FULFILL: every production order completed
    READY_TO_FULFILL --> PICKING: task created (startPicking)
    READY_TO_FULFILL --> PACKED: printed goods only (17 §4.2.6)
    PICKING --> PACKED: everything packed (markPacked)
    PICKING --> PARTIALLY_FULFILLED: part handed over
    PACKED --> PARTIALLY_FULFILLED: part handed over
    PACKED --> SHIPPED: everything handed over
    PARTIALLY_FULFILLED --> PACKED: the rest packed
    PARTIALLY_FULFILLED --> SHIPPED: the rest handed over / closed
    PARTIALLY_FULFILLED --> DELIVERED: rest closed, every shipment arrived
    PARTIALLY_FULFILLED --> DELIVERY_FAILED: rest closed, a shipment failed
    PARTIALLY_FULFILLED --> RETURNED: rest closed, everything came back
    SHIPPED --> DELIVERED: every shipment arrived
    SHIPPED --> DELIVERY_FAILED: all have an outcome, one failed
    SHIPPED --> PARTIALLY_FULFILLED: a shipment came back
    DELIVERY_FAILED --> DELIVERED: delivered on a retry
    DELIVERY_FAILED --> RETURNED: back at the warehouse (RTO)
    DELIVERY_FAILED --> PARTIALLY_FULFILLED: one came back, others arrived
    DELIVERED --> COMPLETED: return window passed, paid, no RMA open
    DELIVERED --> RETURN_REQUESTED: RMA
    RETURN_REQUESTED --> RETURNED
    RETURN_REQUESTED --> DELIVERED: RMA refused (decision 2026-10-21)
    RETURNED --> REFUNDED
    RETURNED --> PARTIALLY_REFUNDED
    COMPLETED --> CLOSED: after 30 days
    REFUNDED --> CLOSED: after 30 days
    PARTIALLY_REFUNDED --> CLOSED: after 30 days
    CONFIRMED --> ON_HOLD
    IN_PRODUCTION --> ON_HOLD
    READY_TO_FULFILL --> ON_HOLD
    PICKING --> ON_HOLD
    PARTIALLY_FULFILLED --> ON_HOLD
    PACKED --> ON_HOLD
    ON_HOLD --> CONFIRMED: back where held from / credit approved
    ON_HOLD --> PENDING_PAYMENT: credit refused, re-termed
    PENDING_PAYMENT --> CANCELLED
    CONFIRMED --> CANCELLED
    IN_PRODUCTION --> CANCELLED
    READY_TO_FULFILL --> CANCELLED
    PICKING --> CANCELLED
    ON_HOLD --> CANCELLED
    CANCELLED --> [*]
    CLOSED --> [*]
```

| From | To | Called by | Guard |
|---|---|---|---|
| READY_TO_FULFILL | PICKING | fulfillment `createTask` → `OrderService.startPicking` (same transaction, A1) | released; IN_PRODUCTION is a no-op (stock lines picked in parallel, 17 §4.2.5) |
| READY_TO_FULFILL / PICKING / PARTIALLY_FULFILLED | PACKED | fulfillment `completePacking` → `markPacked` | not ON_HOLD (resolve first); records `packed_at` |
| PICKING / PACKED / PARTIALLY_FULFILLED | PARTIALLY_FULFILLED / SHIPPED | G4-2a handover → `recordHandover(shipment, lines)` | `ORDER_BALANCE_NOT_PAID` when money is due before the goods leave (G3-Q4); units ≤ what is left; everything at once from PICKING is refused (pack first) |
| PACKED | SHIPPED | `markShipped` (whole order, no shipment recorded) | as above |
| SHIPPED … | DELIVERED / DELIVERY_FAILED / RETURNED / PARTIALLY_FULFILLED | `recordShipmentDelivered / …DeliveryFailed / …Returned` per shipment; `POST /orders/{id}/delivery`, `/delivery-failure` for an order without shipments (`ORDER_DELIVERY_VIA_SHIPMENT` otherwise, G4-Q5) | failure needs a reason (09 BR-02) |
| PARTIALLY_FULFILLED | the shipments' status | `POST /orders/{id}/remainder-closure` (Sales / coordinator) | the unshipped units are closed (`order_line.cancelled_quantity`), their holds released, `OrderRemainderClosed` asks payment to refund what was received above the order's new worth |
| CONFIRMED … PACKED, PARTIALLY_FULFILLED | ON_HOLD | `POST /orders/{id}/hold` (coordinator), design mismatch at packing (system), credit check (system) | reasons PAYMENT, ADDRESS, INVENTORY, CUSTOMER, FRAUD, OTHER (with note) by hand; CREDIT, DESIGN_MISMATCH by the system |
| ON_HOLD | `status_before_hold` | `POST /orders/{id}/hold/resolution` | note required; a CREDIT hold → `ORDER_CREDIT_HOLD_USE_APPROVAL`; the trigger refuses leaving a hold anywhere else |
| DELIVERED | COMPLETED | `OrderSettlementJob` nightly | `delivered_at` + `stockflow.order.return-window-days` (7, BR-ORD-005) passed, nothing owed, no return request open |
| COMPLETED / REFUNDED / PARTIALLY_REFUNDED | CLOSED | `OrderSettlementJob` | `stockflow.order.close-after-days` (30, G3-Q1) passed |

**Split shipments (decision G4-Q3, 2026-10-19).** The order records each shipment it is told of
(`ordering.order_shipment`, `_line`): the units, their value, the outcome. A credit order bills each delivered
shipment (`OrderDelivered.shipmentId`, `billedAmount` → one `payment.receivable` per shipment); the credit exposure
counts, per order, what is still unbilled - total less closed units less billed less money paid outside receivables -
for the statuses `OrderStatus.countsTowardCreditExposure` names, plus the open receivables, so a split order is counted
once and a new status cannot leak out of the limit. A deposit order's balance policy (before / on delivery / credit)
comes with G2-3; until then nothing is billed per shipment for it and nothing blocks its handover.

### Who may cancel, and until when

WBS 3.17.4.1 says the customer may cancel "before shipment". That is too loose to implement —
cancelling during `IN_PRODUCTION` means a cup has already been printed with the customer's
artwork and cannot be resold.

| Order state | Customer may cancel | Sales/coordinator may cancel | Consequence |
|---|---|---|---|
| PENDING_PAYMENT | yes | yes | release reservations |
| CONFIRMED | yes | yes | release reservations, refund in full |
| READY_TO_FULFILL | no — must request | yes | release allocations, raise put-back task |
| IN_PRODUCTION | no — must request | yes, with reason | production orders not yet printing are cancelled and blanks returned; printed items are **not refunded** (BR-ORD-004, BR-PRD-10) |
| PICKING | no | yes, with reason | put-back task (WBS 3.7.7.2) |
| PACKED and later | no | no — must go through RMA | — |

**Refund and fee (decision 2026-10-17, audit of #109).**
- **Before printing** - no production order of the order has printed or been sent to a subcontractor
  (`customer_order.printing_started_at` is empty) - a cancellation is **refunded in full** and keeps nothing:
  neither an agreed amount nor a share (`CANCELLATION_FEE_BEFORE_PRINTING`). A stock-only order is never printed,
  so it is always refunded in full.
- **From the first print run** (or work sent out) Sales enters the amount **agreed with the customer**, with its
  reason and detail. It may be **above what was paid** (a deposit): the money received pays it first and the rest
  is owed as one `CANCELLATION_FEE` receivable; a payment of that receivable counts as kept (retained = the fee,
  at most what was paid). It is **never above the order total**. Below what was paid the rest is refunded through
  the refund process. `ck_order_fee_after_printing`, `ck_order_fee_within_total` say it again in the table.
- **No cancellation by the system once printing started** (decision 2026-10-17): a failed payment or an unpaid
  hold running out does not cancel such an order; it stays as it is and Sales and the order coordinators are told
  (`OrderCancellationReferred`, in-app) to settle the fee, the blanks and the printed goods and to cancel by hand
  if they must (`CANCELLATION_NEEDS_STAFF` for any cancellation without a person).
- **PACKED and later** is enforced on every path, including an approved request and a payment failure: once a
  package of the order is completed (`customer_order.packed_at`) the order goes through a return (RMA). Printing
  started and package completed are recorded by production and fulfillment under the order's row lock, so a
  cancellation either comes before them (and the print or the package is refused) or after them.

**Implemented 2026-10-10 (SCRUM-460).**
- **One state for "may go ahead":** the code has `CONFIRMED` (kltn-docs 17 §5); `PAID` became `CONFIRMED`.
  The money is the order's `paid_amount`, from which the payment status of kltn-docs 15 §5.2 (UNPAID /
  PARTIALLY_PAID / ON_CREDIT / PAID) is derived. A prepaid order is confirmed when paid in full, a
  deposit order when its deposit is in (15 BR-02). Money arriving later never moves the order.
- **Every cancellation names a reason code** — CUSTOMER_REQUEST, PAYMENT_NOT_RECEIVED, PAYMENT_FAILED,
  OUT_OF_STOCK, SUSPECTED_FRAUD, CREDIT_REJECTED, DUPLICATE_ORDER, PRODUCTION_ISSUE, or OTHER with a note.
- **When the customer cancels directly:** in DRAFT / PENDING_PAYMENT / CONFIRMED, at once. From
  IN_PRODUCTION / READY_TO_FULFILL / PICKING / ON_HOLD (no package completed) the customer opens a **cancellation
  request**, one pending at a time, with one of their own reasons (CUSTOMER_REQUEST, DUPLICATE_ORDER, OTHER; G3-2).
  The portal shows the latest request and its decision.
- **Who decides a request:** Sales or the coordinator approve or reject it with
  `sales-order-cancellations:UPDATE` (kltn-docs 17 §2), the permission that also cancels from the back
  office.
- **Fee kept:** approving or cancelling from the back office may keep a percentage of what was paid
  (17 §4.4, 15 §4.4, never more than paid — 15 BR-05) - since 2026-10-17 only once printing started; see
  "Refund and fee" above for the agreed amount.
- **`OrderCancelled` is published on every path.**
  - Payment cancels awaited payments and asks back the refundable amount as PENDING refunds.
  - Fulfillment cancels the pick and pack, flagging picked goods for put-back.
  - Production (SCRUM-393) cancels the production orders not yet printing and holds the others; the
    contract is `contracts.OrderCancelled`.

### Order-line status

Order lines carry their own status, because a partially shortable order is normal.

`PENDING → ALLOCATED → PICKED → SHORT → PACKED → SHIPPED → RETURNED`

A line goes `SHORT` when the picker cannot find the allocated quantity (WBS 3.7.5). The order
does **not** fail — it goes `ON_HOLD` with reason `INVENTORY_ISSUE`, and the coordinator either
re-allocates from another location or splits the order.

---

## 10. Payment — `payment-service`

WBS 3.15. Covers gateway, bank transfer, COD, deposit, refund.

```mermaid
stateDiagram-v2
    [*] --> INITIATED
    INITIATED --> AUTHORIZED: gateway authorises
    INITIATED --> FAILED: declined
    INITIATED --> EXPIRED: customer never paid
    AUTHORIZED --> CAPTURED: captured
    AUTHORIZED --> VOIDED: authorisation released
    CAPTURED --> PARTIALLY_REFUNDED: partial refund
    CAPTURED --> REFUNDED: full refund
    PARTIALLY_REFUNDED --> REFUNDED: remainder refunded
    FAILED --> [*]
    EXPIRED --> [*]
    VOIDED --> [*]
    REFUNDED --> [*]
```

| From | To | Trigger | Actor | Guard | Event |
|---|---|---|---|---|---|
| — | INITIATED | checkout | system | order in PENDING_PAYMENT | `PaymentInitiated` |
| INITIATED | AUTHORIZED | gateway callback | system | signature valid (BR-PAY-001) | — |
| INITIATED | FAILED | gateway callback | system | — | `PaymentFailed` |
| INITIATED | EXPIRED | timeout sweep | system | unpaid past the window | `PaymentFailed` |
| AUTHORIZED | CAPTURED | capture | system, or Accountant for bank transfer | amount ≤ authorised | `PaymentCaptured` |
| CAPTURED | PARTIALLY_REFUNDED / REFUNDED | refund | Accountant | within refund approval limit (BR-PAY-003) | `RefundCompleted` |

**COD is not a payment until the carrier remits.** A COD order is `CONFIRMED` with a payment in
`INITIATED`; it becomes `CAPTURED` only at reconciliation, after the carrier hands the cash over
(WBS 3.15.4). Treating COD as paid at checkout is how the books stop balancing.

**A deposit is a payment for part of the total.** The order moves to `CONFIRMED` when
`sum(captured) ≥ deposit_required` (BR-PAY-002), not when it equals the order total.

### Receivable (credit orders) — implemented 2026-10-10 (SCRUM-431)

kltn-docs 15 §4.3 and §5.3. One receivable per delivered credit order, for what the order still owes.

```mermaid
stateDiagram-v2
    [*] --> OPEN: order delivered (due = delivery day + days to pay, Saigon)
    OPEN --> PARTIALLY_PAID: transfer allocated, part
    OPEN --> PAID: transfer allocated, all
    PARTIALLY_PAID --> PAID: transfer allocated, rest
    OPEN --> OVERDUE: nightly run, past due
    PARTIALLY_PAID --> OVERDUE: nightly run, past due
    OVERDUE --> PAID: paid in full
    PAID --> [*]
```

- **Overdue stays overdue.** A part payment keeps the receivable `OVERDUE` until it is paid in full. Being overdue is what blocks new credit and release (15 §4.3 step 4).
- **Transfers.** The accountant records each transfer from the bank statement, once per reference (BR-04, BR-07). It pays the receivables the customer named, otherwise the earliest due first (BR-09). Whatever is left stays as the customer's credit and pays their next receivable as it opens.
- **The order learns of every payment.** Each allocation is published as `PaymentCaptured` (source `RECEIVABLE_ALLOCATION`), so the order's `paidAmount` follows.
- **Allocations are append-only** (BR-06).


---

## 11. Shipment and load — `fulfillment-service`

WBS 3.9. **The carrier is a third party outside the system** (decided 2026-10-06, docs 09): no carrier
API, no carrier waybill, no tracking. The system records the handover and the outcome the carrier
reports back; nothing in between.

```mermaid
stateDiagram-v2
    [*] --> READY_TO_DISPATCH: packed
    READY_TO_DISPATCH --> HANDED_OVER: loaded onto the truck, manifest signed
    READY_TO_DISPATCH --> CANCELLED: order cancelled before handover
    HANDED_OVER --> DELIVERED: carrier reports delivered
    HANDED_OVER --> DELIVERY_FAILED: carrier reports not delivered
    DELIVERY_FAILED --> DELIVERED: carrier delivered on a retry
    DELIVERY_FAILED --> RETURNED: parcel back at the warehouse
    DELIVERED --> [*]
    RETURNED --> [*]
    CANCELLED --> [*]
```

| From | To | Trigger | Actor | Guard |
|---|---|---|---|---|
| READY_TO_DISPATCH | HANDED_OVER | handover | Warehouse staff | parcel is on a load that is being dispatched; scanned onto that vehicle |
| HANDED_OVER | DELIVERED / DELIVERY_FAILED | outcome reported | Order coordinator | failure carries a reason (absent, refused, wrong address, lost) |
| DELIVERY_FAILED | RETURNED | parcel received back | Warehouse staff | through the returns receipt, never straight into stock |

A **load** is one vehicle (truck or container) and the parcels placed on it:

```mermaid
stateDiagram-v2
    [*] --> PLANNED: load plan accepted
    PLANNED --> VEHICLE_BOOKED: vehicle booked with the carrier (outside the system)
    PLANNED --> CANCELLED: parcels go back to READY_TO_DISPATCH
    VEHICLE_BOOKED --> LOADING: vehicle arrived
    LOADING --> DISPATCHED: every planned parcel scanned on, manifest signed
    DISPATCHED --> [*]
    CANCELLED --> [*]
```

The load plan respects the vehicle's internal volume and payload, and never puts a `FRAGILE`
parcel under a heavier one (docs 09 BR-02).

`RETURNED` triggers a **re-putaway** (WBS 3.9.7.2): the goods return to stock through the
same putaway machine, in condition GOOD if the parcel is intact and QUARANTINE otherwise.

> **Implemented (G4-5, `V20261021000300`):** `fulfillment.shipment` carries the six statuses above
> (old `PENDING` → `READY_TO_DISPATCH`, `DISPATCHED` → `HANDED_OVER`), a number per order
> (`<order>-S<n>`), its warehouse, delivery mode, package count, COD amount (09 BR-04) and the
> handover / outcome / cancellation facts, each step also a `MANUAL` `shipment_event` row naming who
> recorded it (09 BR-02). The domain is `fulfillment.internal.domain.Shipment`; G4-5 creates shipments
> (READY_TO_DISPATCH) and cancels them with their order, G4-2a hands them over, SCRUM-294/295 record
> outcomes. Still to come (G4-1/G4-2a): the vehicle-type catalogue, the load plan and the load.
> `tracking_number` and carrier-sourced events are unused (no carrier API).

**Packing session (G4-5, kltn-docs 08 §5).** One per task (`fulfillment.pack`):
`PENDING → PACKING` (a packer takes the order; another packer is refused, a manager is not) `→ PACKED`
(nothing the order wants is left unshipped), `PACKING ⇄ ON_HOLD` (a printed design does not match its
snapshot, BR-DSG-003; QC or a manager re-verifies and packing is back in progress — docs 08
"Verification Failed → In Progress"), `→ CANCELLED` with the order. Its parcels (`fulfillment.package`)
go `OPEN → SEALED` (weight and size required, 08 BR-03) `→` in a shipment; before that a parcel may be
re-opened or `VOIDED`. Every scan is kept (`fulfillment.package_scan`, append-only). Completing ships
every sealed parcel not yet shipped as one shipment; part of the order may ship first (split shipments,
G4-Q3; a mixed order's stock lines first, decision 2026-10-21) with a reason, the rest later.

---

## 12. RMA — `order-service`

WBS 3.17.5.

```mermaid
stateDiagram-v2
    [*] --> REQUESTED
    REQUESTED --> APPROVED: sales approves
    REQUESTED --> REJECTED: outside policy
    APPROVED --> AWAITING_RETURN: return instructions sent
    AWAITING_RETURN --> RECEIVED: parcel arrives
    AWAITING_RETURN --> EXPIRED: never returned
    RECEIVED --> INSPECTED: QC inspects
    INSPECTED --> REFUNDED: refund issued
    INSPECTED --> EXCHANGED: replacement shipped
    INSPECTED --> REJECTED: goods not as described
    REFUNDED --> [*]
    EXCHANGED --> [*]
    REJECTED --> [*]
    EXPIRED --> [*]
```

| From | To | Trigger | Actor | Guard | Event |
|---|---|---|---|---|---|
| — | REQUESTED | open RMA | Customer / Sales staff | order DELIVERED, within window (BR-RMA-001) | `RmaRequested` |
| REQUESTED | APPROVED | approve | Sales staff | reason in policy; **custom-printed items are not returnable unless defective** (BR-RMA-002) | `RmaApproved` |
| APPROVED | AWAITING_RETURN | send return instructions | system | customer told to write the RMA number on the parcel; no return label (carriers are outside the system) | — |
| AWAITING_RETURN | RECEIVED | parcel arrives | Warehouse staff | RMA number on parcel | `RmaGoodsReceived` |
| RECEIVED | INSPECTED | inspect | QC staff | condition recorded | `RmaInspected` |
| INSPECTED | REFUNDED | refund | Accountant | within refund limit | `RefundCompleted` |
| INSPECTED | EXCHANGED | ship replacement | Order coordinator | replacement stock available | `ExchangeShipped` |

Returned goods re-enter stock in condition **QUARANTINE**, never GOOD. Whether they become
sellable again is a QC decision, not an automatic one.

---

## 13. Transfer order — `inventory-service`

WBS 3.10.

```mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> PENDING_APPROVAL: submit
    PENDING_APPROVAL --> DRAFT: reject
    PENDING_APPROVAL --> APPROVED: approve
    APPROVED --> PICKING: source starts picking
    PICKING --> ISSUED: stock issued at source
    ISSUED --> IN_TRANSIT: shipment leaves
    IN_TRANSIT --> RECEIVING: arrives at destination
    RECEIVING --> COMPLETED: quantities match
    RECEIVING --> DISCREPANCY: quantities differ
    DISCREPANCY --> COMPLETED: resolved and adjusted
    DRAFT --> CANCELLED: cancel
    APPROVED --> CANCELLED: cancel
    COMPLETED --> [*]
    CANCELLED --> [*]
```

| From | To | Trigger | Actor | Guard | Event |
|---|---|---|---|---|---|
| — | DRAFT | create | Inventory planner | source ≠ destination | — |
| DRAFT | PENDING_APPROVAL | submit | Inventory planner | source ATP ≥ requested | — |
| PENDING_APPROVAL | APPROVED | approve | Warehouse manager | value within approval threshold (BR-TRF-001) | `TransferApproved` |
| APPROVED | PICKING | start | Warehouse staff | stock reserved at source, FEFO (WBS 3.10.3.2) | `TransferPickStarted` |
| PICKING | ISSUED | issue | Warehouse staff | picked = approved qty | `TransferIssued` |
| ISSUED | IN_TRANSIT | dispatch | Warehouse staff | — | `TransferInTransit` |
| IN_TRANSIT | RECEIVING | arrival | Warehouse staff (destination) | — | — |
| RECEIVING | COMPLETED | confirm | Warehouse staff | received = issued | `TransferCompleted` |
| RECEIVING | DISCREPANCY | confirm with variance | Warehouse staff | variance logged (WBS 3.10.6.1) | `TransferDiscrepancyRaised` |
| DISCREPANCY | COMPLETED | resolve | Warehouse manager | write-off approved (WBS 3.10.6.3) | `TransferCompleted` |

**In-transit stock belongs to neither warehouse's on-hand.** Between `ISSUED` and `COMPLETED` the
units sit in a dedicated in-transit bucket. Leaving them on the source's on-hand lets the source
oversell them; adding them to the destination early lets the destination sell goods that are on a
truck. This is the single most common transfer bug.

---

## 14. Cycle count — `inventory-service`

WBS 3.6.6.

`PLANNED → COUNTING → VARIANCE_REVIEW → APPROVED → POSTED`, plus `CANCELLED` from PLANNED.

A count with zero variance may skip `VARIANCE_REVIEW` and post directly. Any variance needs
manager approval (WBS 3.6.5.2) before it touches stock — a cycle count is the one place where an
unapproved number can silently rewrite inventory.

---

## 15. Chat conversation — `chat-service`

WBS 3.16.2.2 names the states: `OPEN → RESOLVED`, with `ESCALATED` in between.

`OPEN → ASSIGNED → RESOLVED`, `ASSIGNED → ESCALATED → ASSIGNED`, `RESOLVED → OPEN` on a customer
reply within 24 hours. SLA timers (WBS 3.16.6) run on `OPEN` and `ESCALATED` only.

---

## 16. Production order — `production`

New 2026-10-06. One per custom order line (`ORDER`) or per sample request (`SAMPLE`). Both kinds
run the same workshop steps; only the source and the end differ. Business doc:
`kltn-docs/docs/warehouse/19-production`.

**Subcontracting** (added 2026-10-06, §4.4 of the business doc): when the in-house shop cannot meet
the due date, the warehouse manager splits part of an `ORDER` production order into a **child**
production order with `execution = SUBCONTRACTED`, linked to its parent and to a `SUBCONTRACT`
purchase order. The child skips `MATERIAL_ISSUED` / `PRINTING` and goes
`READY → SUBCONTRACTED → QC`. Splitting does not change the parent's state, only its quantity.
Two modes, chosen per split: `SUPPLIED_BLANKS` (we send the blanks; they stay our stock at a
virtual subcontractor location, outside ATP) and `FULL_SERVICE` (the subcontractor sources the
blanks; we buy finished goods).

```mermaid
stateDiagram-v2
    [*] --> PENDING_PREPRESS: created from release / sample request
    PENDING_PREPRESS --> READY: prepress passed
    READY --> MATERIAL_ISSUED: blanks issued to PRODUCTION area
    MATERIAL_ISSUED --> PRINTING: print started
    PRINTING --> QC: printed + scrap recorded
    QC --> COMPLETED: good quantity reached
    QC --> READY: shortfall → reprint
    READY --> SUBCONTRACTED: subcontracted child sent out (PO approved)
    SUBCONTRACTED --> QC: subcontract goods receipt posted
    PENDING_PREPRESS --> ON_HOLD
    READY --> ON_HOLD
    MATERIAL_ISSUED --> ON_HOLD
    PRINTING --> ON_HOLD
    SUBCONTRACTED --> ON_HOLD
    QC --> ON_HOLD
    ON_HOLD --> PENDING_PREPRESS: resume (back to the state it left)
    PENDING_PREPRESS --> CANCELLED
    READY --> CANCELLED
    MATERIAL_ISSUED --> CANCELLED: blanks returned
    SUBCONTRACTED --> CANCELLED: blanks recalled, PO cancelled
    COMPLETED --> [*]
    CANCELLED --> [*]
```

| From | To | Trigger | Actor | Guard | Event |
|---|---|---|---|---|---|
| — | PENDING_PREPRESS | release / sample submitted | system | one per source line (BR-PRD-01) | — |
| PENDING_PREPRESS | READY | prepress pass | Production staff | file checksum = locked snapshot (BR-PRD-02) | — |
| READY | MATERIAL_ISSUED | blanks scanned out | Warehouse staff | issued ≤ remaining need (BR-PRD-03) | — |
| MATERIAL_ISSUED | PRINTING | start | Production staff | — | — |
| PRINTING | QC | record output | Production staff | good + scrap = printed; scrap has a reason (BR-PRD-04) | — |
| QC | COMPLETED | QC pass | QC staff | good quantity = required; ORDER compared with the approved sample (BR-PRD-05) | `ProductionCompleted` (ORDER) |
| QC | READY | shortfall | QC staff | more blanks reserved; none → ON_HOLD | — |
| PENDING_PREPRESS / READY / ON_HOLD | *(unchanged)* + child PENDING_PREPRESS or READY | split for subcontracting | Warehouse manager | ORDER only (BR-PRD-11); 0 < split < quantity not yet issued (BR-PRD-12); active subcontractor; child inherits the parent's prepress result | — |
| READY | SUBCONTRACTED | send to subcontractor (child only) | Warehouse staff | `SUBCONTRACT` PO approved (BR-PRD-13); SUPPLIED_BLANKS: blanks scanned to the subcontractor location (BR-PRD-15) | — |
| SUBCONTRACTED | QC | subcontract goods receipt posted | system | SUPPLIED_BLANKS: blank reconciliation done, excess loss approved (BR-PRD-16, 17) | — |
| any before COMPLETED | ON_HOLD | hold | Production staff / Warehouse manager | reason: bad file, out of blanks, machine down, waiting for customer, subcontractor late | — |
| PENDING_PREPRESS / READY / MATERIAL_ISSUED | CANCELLED | cancel | Warehouse manager | issued blanks returned to stock | — |
| SUBCONTRACTED | CANCELLED | cancel | Warehouse manager | blanks recalled from the subcontractor, PO cancelled (or fee paid per BR-PRD-10) | — |

Once `PRINTING` or `SUBCONTRACTED`, the design cannot change (BR-PRD-07, BR-PRD-14) and
cancellation follows BR-PRD-10.

Decided 2026-10-13: a subcontractor's goods get **one** inspection, the production QC against the approved sample.
The warehouse counts the receipt and reconciles the blanks; receiving runs no QC of its own on a `SUBCONTRACT` PO and
puts nothing away; the goods stay held for their production order, outside ATP, in the QUALITY_CONTROL area until
QC. A subcontracted order cancelled while its sales order still wants the goods is replaced by an in-house child
(origin `REPLACEMENT`); a shortfall taken back to the print shop is an in-house child with origin `SHORTFALL`. A shortfall on a subcontracted child goes back to `READY`, from
where the manager either sends more to the same subcontractor (PO supplement) or splits the gap
into a new in-house child.

---

## 17. Sample request — `production`

New 2026-10-06. Mandatory for a new design (design + blank pair without an approved sample).

```mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> SUBMITTED: sent to the workshop
    SUBMITTED --> IN_PRODUCTION
    IN_PRODUCTION --> READY: sample production order COMPLETED
    READY --> SENT_TO_CUSTOMER: sample handed over (off-system)
    SENT_TO_CUSTOMER --> APPROVED: customer approves
    SENT_TO_CUSTOMER --> CHANGES_REQUESTED: customer asks for changes
    CHANGES_REQUESTED --> [*]: next round is a new request (round + 1)
    DRAFT --> CANCELLED
    APPROVED --> [*]
    CANCELLED --> [*]
```

| From | To | Trigger | Actor | Guard | Event |
|---|---|---|---|---|---|
| DRAFT | SUBMITTED | submit | Sales staff | confirmed design snapshot; blanks reservable | — |
| READY | SENT_TO_CUSTOMER | mark sent | Sales staff | — | — |
| SENT_TO_CUSTOMER | APPROVED | approve | Customer (portal) / Sales staff on their behalf | — | `SampleApproved` |
| SENT_TO_CUSTOMER | CHANGES_REQUESTED | request changes | Customer / Sales staff | feedback recorded | — |

`APPROVED` locks the design for the design + blank pair and becomes the QC reference for order
production (BR-PRD-05, BR-PRD-08).

---

## 18. Quote — `order`

New 2026-10-06 (B2B only). Partly built in PR #38 (`DesignQuote`).

```mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> SENT: sent to the customer
    SENT --> ACCEPTED: customer accepts
    SENT --> CHANGES_REQUESTED: customer asks for changes
    CHANGES_REQUESTED --> DRAFT: revised
    SENT --> EXPIRED: validity ends
    ACCEPTED --> CONVERTED: sales order created
    ACCEPTED --> CANCELLED: a SKU on it stopped (Sales, reason)
    DRAFT --> CANCELLED
    CONVERTED --> [*]
    EXPIRED --> [*]
    CANCELLED --> [*]
```

| From | To | Trigger | Actor | Guard | Event |
|---|---|---|---|---|---|
| DRAFT | SENT | send | Sales staff | discount within the sales staff's limit, or approved | — |
| SENT | ACCEPTED | accept | Customer (portal) | within validity; every new design has an APPROVED sample (BR-PRD-08) | — |
| ACCEPTED | CONVERTED | create order | Sales staff or order coordinator (`sales-quote-conversions:CREATE`; editing a quote is Sales' - `sales-quotes:UPDATE`, which the coordinator does not hold, decision 2026-10-17) | lines reserved (BR-ORD-001) | `OrderPlaced` |
| ACCEPTED | CANCELLED | cancel | Sales staff | a SKU on the quote is INACTIVE/DISCONTINUED, so it can never be converted; reason required and kept in the history; the customer still sees it; a new quote follows if the goods are still wanted (decision 2026-10-17) | — |
| SENT | EXPIRED | validity ends | system | — | — |

