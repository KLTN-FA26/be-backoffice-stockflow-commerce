# Data Ownership

One rule: **exactly one service may write an entity.** Everyone else reads it through an API or
keeps a replica built from events, and a replica is never the place a decision is made.

## Ownership matrix

| Entity | Owner | Replicated in | Kept in sync by |
|---|---|---|---|
| Product, Variant, SKU | `product-service` | `catalog-service` (published only), `inventory-service` (sku + tracking flags) | `ProductPublished`, `ProductUpdated` |
| Print configuration, 3D model | `product-service` | `design-service` | `ProductPrintConfigUpdated` |
| Category, PLP/PDP content, SEO | `catalog-service` | — | — |
| Supplier, Purchase Order | `procurement-service` | — | — |
| Goods Receipt, QC result | `procurement-service` | `inventory-service` (quantities only) | `GoodsReceived`, `QcCompleted` |
| Supplier Invoice, three-way match | `procurement-service` | `reporting-service` | `InvoiceMatched` |
| Warehouse, Zone/Aisle/Rack/Level/Bin | `warehouse-service` | `inventory-service` (location code only) | `LocationCreated`, `LocationDeactivated` |
| Slotting rules, Putaway task | `warehouse-service` | — | — |
| Stock on hand, reservation, allocation | `inventory-service` | `catalog-service` (ATP per SKU) | `StockLevelChanged` |
| Pick / Pack / Shipment | `fulfillment-service` | `order-service` (status only) | `PickCompleted`, `ShipmentDispatched` |
| Design draft, Design snapshot | `design-service` | `order-service` (snapshot id + checksum only) | `DesignConfirmed` |
| Cart, Order, Order line, RMA | `order-service` | `reporting-service` | `OrderPlaced`, `OrderStatusChanged` |
| Payment, Refund | `payment-service` | `order-service` (status only) | `PaymentCaptured`, `PaymentFailed`, `RefundCompleted` |
| Customer, Address, Segment | `customer-service` | `order-service` (name + address snapshot at order time) | `CustomerUpdated` |
| Conversation, Message | `chat-service` | — | — |
| User, Role, Permission | `identity-service` | every service (via JWT) | `PermissionsChanged` |

## The three rules that make this hold

**1. An order stores a copy, not a pointer.** Product name, unit price, customer name and
shipping address are **snapshotted onto the order line at checkout**. Tomorrow's price change
must not alter yesterday's invoice. This is a business requirement, not a caching trick.

**2. A replica is read-only and may be stale.** `catalog-service` shows an ATP figure that may be
a second out of date; that is acceptable for a product page. It is *not* acceptable for the
reservation decision, which is why checkout calls `inventory-service` synchronously rather than
trusting the catalog's copy.

**3. Nobody joins across databases.** If a report needs order lines and stock movements together,
`reporting-service` builds that read model from the event stream. There is no query that touches
two schemas.

## The one place replication is forbidden

**Design snapshots are never copied.** `order-service` stores only the snapshot id and its
checksum; the artifact itself stays in `design-service` object storage. A copied snapshot is a
snapshot that can silently diverge from what the customer approved — and the whole point of the
artifact is that it cannot.
