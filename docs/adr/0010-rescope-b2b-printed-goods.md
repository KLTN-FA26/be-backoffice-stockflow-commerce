# ADR-0010 — Rescope: B2B only, printed goods from outside producers, whole cartons, full truckloads

* **Status:** Accepted — amends [ADR-0005](0005-modular-monolith.md) (its *reason* and its examples; the decision
  stands) and supersedes the business analysis under `docs/business-design/`
* **Date:** 2026-11-11 (decisions taken 2026-10-06 to 2026-11-11)
* **Deciders:** GFA26SE03 (5 members) · Supervisor: Nguyễn Minh Sang · repository owner for QD-1
* **Project:** StockFlowCommerce (FA26SE029)

---

## Context

The first design was a web shop with in-house printing: a customer filled a cart, paid online, and the business made
the goods. The business analysis was then rewritten (the **kltn-docs** repository, which is now the source of truth) for
what the business actually is: a finished-goods warehouse and **B2B** sales platform for cups and packaging printed to
wholesale customers' designs. Printing is done by **producers outside the system**; goods arrive printed, in cartons.
The code was brought into line in the packages of `docs/superpowers/plans/2026-11-07-rescope/11-team-plan.md`
(2a–2f, 3, 4A–4D, 5). This ADR records what changed so a reader of ADR-0005 is not misled.

## Decision

**What was removed** (each by a migration that cleans leftover rows and drops; no archive — QD-1, the VPS database is
rebuilt on deploy; historical enum constants stay):

| Removed | Why |
|---|---|
| in-house production, blanks, subcontracting | printing is outside the system (V20261108000100) |
| purchasing: purchase orders, suppliers, supplier invoices | goods arrive against a production order, not a purchase |
| QC at the warehouse, `QUALITY_CONTROL` area | the warehouse counts cartons; bad goods go to quarantine whole |
| transfers between warehouses | stock never changes warehouse (V20261108000300) |
| expiry, FEFO, low-stock alerts, configurable stock policy | cups and packaging do not expire; FIFO always (5-1, 5-5) |
| loose and opened stock, repack station, per-item damage reports, design check at packing | stock is whole LPN cartons only; the carton is the shipping carton (4C-1..4C-7, 5-6) |
| storefront prices, cart, checkout, online payment, COD, parcel carriers | Sales quote; customers pay by bank transfer against contract installments; every order is a full truckload (5-2, 5-3, V20261108000400) |
| money ledger, per-order payment terms | an order has no money of its own, only its contract's installments (4B-10, 4B-11) |
| the old return flow (QC, replacement order, credit) | returned cartons go to quarantine; refund and adjustment invoice (4D-1..4D-4) |
| roles `ORDER_COORDINATOR`, `INVENTORY_PLANNER`, `PROCUREMENT_STAFF` | seven roles remain; the permissions moved (5-5, 5-7, 5-8) |

**What replaced it:** quotes and sales contracts with delivery batches and payment plans (4B); installments, payment
receipts and refund records in `payment`; production orders sent to producers, with print sample rounds, schedule and
delay alerts (4A); receiving against a production order and return receipts (`procurement`, name kept — the rename is
the optional 4A-10); whole-carton holds, FIFO allocation, count, adjustment and quarantine by carton (4C, 4D); picking
only by LPN with an optional staging area; a carton check and labelling station; load planning with Ahamove booking and
webhooks (3). Every module has code now; the "skeleton" status in earlier notes is gone.

## What now justifies the in-transaction design

ADR-0005 justified one transaction by **checkout**: place an order and reserve its stock together, or oversell. Checkout
no longer exists, and the old 30-minute reservation with its expiry sweep went with it. The reason has not gone; it has
moved to the step where the business **promises goods to a customer's order**:

* Sales choose the shipping warehouse of a signed contract's order and the order holds the whole cartons available there
  (`ShippingWarehouseService.choose` → `InventoryService.holdAtWarehouse`). A hold is real stock promised to a real
  contract: if the order failed to persist and the hold survived, those cartons would be unsellable for a customer who
  has no order; if the order persisted and the hold did not, two contracts would be promised the same cartons. Neither
  window can be closed by a saga, only narrowed — the argument of ADR-0005 §2 is unchanged.
* The same holds for the other multi-module writes that must be all-or-nothing: receiving a production order's goods
  into stock and advancing the production order; a cancellation's settlement and the stock it releases; a return
  receipt and the carton it brings back; a pick scan and the deduction.
* What is gone is the *urgency* (a customer waiting at a checkout, a hold that lapses) — not the invariant. Holds now have
  no deadline, a shortage is shown and topped up instead of refused, and the distributed version would still need the
  outbox, response events and compensation that ADR-0003 described.
* Events remain right where a module announces a fact and the other side may take its time: payment receipts to the
  order, putaway to the receipt, a returned carton to the return request, carrier webhooks to shipments.

## Superseded and kept

| Document | State |
|---|---|
| ADR-0005 | **Decision stands.** Its Context point 1 and its examples (`OrderServiceImpl.placeOrder`, `PaymentCaptured`, "failed checkout", `PlaceOrderIntegrationTest`) describe the shop that no longer exists; read them as the history of why, and this ADR for the current instance. The reservation sweeper still runs but only for reservations that carry a deadline (those made before holds lost it). |
| ADR-0001..0003 | still superseded by ADR-0005 |
| ADR-0007 | stands (cross-schema foreign keys) |
| `docs/business-design/**` | superseded by kltn-docs; kept as history, with a banner on its index files. Its `db-design/schema.dbml` is current (generated from the migrated database) |
| `CLAUDE.md` rule "the schema is owned by Tú; do not add migrations" | replaced: the team writes migrations, the schema owner reviews them, versions are newer than everything merged |

## Consequences

* The schema changed a great deal in nine days; every removal has an upgrade test and adversarial SQL, but the
  highest-risk places are the ones tests reach last: ordering of migrations from parallel branches (three were repaired
  in the final merge) and fixtures that build a world the migrations cannot produce.
* A reader must learn behaviour from the code and `CLAUDE.md` §4, then the kltn-docs flow, and treat anything in
  `docs/business-design/` as a pointer to history.
* Not decided here: renaming `procurement` to `receiving` (4A-10, optional), a warehouse capacity table, wave picking,
  customer e-mail (switched off by configuration, not deleted).
