# Ubiquitous Language

Terms that look obvious and are not. Every one of them has already caused a production
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
> A picker walking to bin `HCM-A01-2-B` with a pick list holds three specific units on that shelf — that
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
| **Golden zone** | Shelf heights between roughly knee and shoulder, where picking is fastest. **A slotting score, not a column or a place:** a pick bin on a low level (by `shelf_level.elevation`) scores higher for fast movers ([module 06 §4.2](https://github.com/KLTN-FA26/docs/blob/b11945a/docs/warehouse/06-warehouse-map-slotting/README.md), SCRUM-91). WBS 3.5.3.1. |
| **FEFO** | First-Expired-First-Out. Picking rule for lot-tracked goods; it overrides FIFO whenever an expiry date exists. |

## The warehouse map

The full model, with its rules BR-06 → BR-14, is [module 06](https://github.com/KLTN-FA26/docs/blob/b11945a/docs/warehouse/06-warehouse-map-slotting/README.md) of the docs
repository; the columns are in [`db-design/schema.dbml`](db-design/schema.dbml) (schema
`warehouse`). Only what a reader of these documents must not get wrong is repeated here.

| Term | Definition | Not to be confused with |
|---|---|---|
| **Shelf → Shelf level → Bin** | A shelf stands on the map; it has numbered levels; a level holds bins, the smallest slot. A shelf may belong to a **zone**, a purely visual grouping with no storage rule. | Aisle, rack — not modelled. An aisle is the empty floor between shelves |
| **Area** | Floor space that is not a shelf: `RECEIVING`, `QUARANTINE`, `PACKING`, `DISPATCH`, `OVERFLOW`, or `NON_STORAGE` (office, walkway). | A zone |
| **Boundary** | A wall or a door segment on the map. Walking distance never crosses a wall, and only crosses an open, passable door (BR-09). | — |
| **Storage location** | A place stock can sit: one row of `warehouse.storage_location`, of kind `BIN` or `AREA`, owned by the bin or by the storage area. A `NON_STORAGE` area has none. Every other module points at this row — by id or by `location_code` — never at a bin or an area. | Bin (the shape on the map); area |
| **Location code** | The full, system-wide code of a storage location (BR-10): `HCM-A01-2-B` for a bin (warehouse prefix, shelf, level, bin; the level has no leading zero), `HCM-QC01` for an area. Every part is `[A-Z0-9]` and never changes once created (BR-13). | The local `code` of a shelf, bin or area (`A01`, `B`, `QC01`) |
| **Effective status** | What a location's status amounts to once its warehouse and, for a bin, its shelf are taken into account: a bin that is `ACTIVE` on a shelf in `MAINTENANCE` cannot be used. Computed when read, never written down. | The location's own `status` |

## Words to avoid

| Do not write | Write instead | Why |
|---|---|---|
| "stock status" | `condition`, or the specific quantity | Hides which of the three dimensions is meant |
| "confirm the order" | the exact transition, e.g. `PENDING_PAYMENT → CONFIRMED` | "Confirm" is used by the customer, sales and the warehouse for three different events |
| "cancel" | `CANCELLED` (order), `CLOSED_SHORT` (PO), `VOID` (invoice) | Different entities, different consequences |
| "quantity" alone | `quantityOrdered`, `quantityReceived`, `quantityOnHand` | The single most common source of off-by-one bugs in a WMS |
