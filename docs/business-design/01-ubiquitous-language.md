# Ubiquitous Language

Eleven terms that look obvious and are not. Every one of them has already caused a production
bug in some warehouse system, usually because two developers assumed different definitions.

## Inventory quantities

| Term | Definition | Not to be confused with |
|---|---|---|
| **On hand** | Physically present at a location, counted, regardless of who has claimed it. | Available |
| **Reserved** | A *soft*, time-limited hold taken at checkout against a SKU. Not tied to a location. Expires and auto-releases (WBS 3.14.5.2). | Allocated |
| **Allocated** | A *hard* commitment of specific units at a specific location to a specific pick task. Created when the order is released to the warehouse. Does not expire. | Reserved |
| **Available (ATP)** | `on_hand − reserved − allocated`, counted only over stock whose condition is GOOD. The number the storefront shows. WBS 3.6.4.1. | On hand |
| **In transit** | Units that have left the source warehouse and not yet been received at the destination. Owned by neither warehouse's on-hand figure. | On hand at either end |

> **Why reserved and allocated must stay separate.** A customer at checkout holds stock for
> fifteen minutes without anybody knowing which shelf it will come from — that is a reservation.
> A picker walking to aisle A-01 with a pick list holds three specific units on that shelf — that
> is an allocation. Collapse them and either the storefront oversells, or the picker arrives to
> find the stock gone.

## Stock condition versus stock commitment

WBS 3.6.1.3 lists five "inventory status categories":
`Available / Allocated / Reserved / Quarantine / In-Transit`.

Those five are **not one dimension**. They mix three:

| Dimension | Values | Question it answers |
|---|---|---|
| **Condition** | GOOD, QUARANTINE, DAMAGED, EXPIRED | Is this stock sellable at all? |
| **Commitment** | quantities: on hand, reserved, allocated | How much of it is already promised? |
| **Location state** | at a location, or in transit | Where is it right now? |

Modelled as one field, the combinations become impossible to express: stock can be *quarantined
and reserved* (QC failed after a customer checked out), or *damaged and in transit*. Modelled as
three, every combination is representable and no state is unreachable.

**This document therefore uses `condition` as the single enum, and quantities as numbers.**
The BRD's five "categories" are a *view* computed from those, not a stored field.

## Documents and workflow

| Term | Definition |
|---|---|
| **Design snapshot** | An immutable JSON artifact of a customer-approved design, plus its checksum. Once confirmed it is never edited — a change produces a new snapshot. WBS 3.12.5. |
| **Three-way match** | Agreement between PO, Goods Receipt and Supplier Invoice on quantity and price, within tolerance. WBS 3.4.3. |
| **Tolerance** | The configured percentage by which a receipt or an invoice may differ from the PO before it needs human approval. WBS 3.3.8.1, 3.4.3.4. |
| **Wave** | A batch of orders released to the floor together so picks can be routed as one trip. WBS 3.7.2. |
| **Golden zone** | Shelf heights between roughly knee and shoulder, where picking is fastest. Fast-moving SKUs are slotted here. WBS 3.5.3.1. |
| **FEFO** | First-Expired-First-Out. Picking rule for lot-tracked goods; it overrides FIFO whenever an expiry date exists. |

## Words to avoid

| Do not write | Write instead | Why |
|---|---|---|
| "stock status" | `condition`, or the specific quantity | Hides which of the three dimensions is meant |
| "confirm the order" | the exact transition, e.g. `PENDING_PAYMENT → CONFIRMED` | "Confirm" is used by the customer, sales and the warehouse for three different events |
| "cancel" | `CANCELLED` (order), `CLOSED_SHORT` (PO), `VOID` (invoice) | Different entities, different consequences |
| "quantity" alone | `quantityOrdered`, `quantityReceived`, `quantityOnHand` | The single most common source of off-by-one bugs in a WMS |
