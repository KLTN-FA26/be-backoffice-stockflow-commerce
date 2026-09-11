# Business Rules Catalog

Every rule has an id, an owning service, the exact place it is enforced, and a test hook. The id
is what a code comment, a test name and a report section all cite, so a reviewer can follow one
rule from the BRD to the line that enforces it.

**Enforcement point matters more than the wording.** A rule enforced in a controller is a rule
that a Kafka consumer bypasses. Rules that protect an invariant belong in the aggregate.

## Product — `product-service`

| Id | Rule | Enforced in | WBS |
|---|---|---|---|
| BR-PRD-001 | A product cannot be submitted for approval unless name, category, base UoM, at least one variant and dimensions are present. | `Product.submit()` | 3.1.9.1 |
| BR-PRD-002 | SKU codes are unique across the whole catalogue, including discontinued products. | DB unique index + `Sku` value object | 3.1.2.4 |
| BR-PRD-003 | The approver of a product must not be the person who submitted it. | `Product.approve()` | 3.1.1.3 |
| BR-PRD-004 | A product cannot be published without a selling price, at least one image and a category. | `Product.publish()` | 3.1.6.1 |
| BR-PRD-005 | A product cannot be discontinued while an open PO line or an unshipped order line references it. | `product-service` domain service, checks via events | 3.1.8.1 |
| BR-PRD-006 | Expiry tracking cannot be switched off once stock with expiry dates exists. | `Product.updateInventoryControl()` | 3.1.5.3 |

## Procurement — `procurement-service`

| Id | Rule | Enforced in | WBS |
|---|---|---|---|
| BR-PO-001 | A PO line quantity must meet the supplier's MOQ for that SKU. | `PurchaseOrder.addLine()` | 3.1.4.2 |
| BR-PO-002 | A PO may only be approved by someone whose approval limit is at least the PO total. | `PurchaseOrder.approve()` | 3.2.2.1 |
| BR-PO-003 | Two POs to the same supplier, same SKU, same delivery date are flagged as possible duplicates. | `procurement-service` query on create | 3.2.8.2 |
| BR-PO-004 | A PO cannot be amended once any receipt has been posted against it. | `PurchaseOrder.amend()` | 3.2.5.1 |
| BR-RCP-001 | Received quantity may exceed the ordered quantity by at most the configured tolerance (default 5%); beyond that the receipt cannot be posted without warehouse-manager approval. | `GoodsReceipt.post()` | 3.3.8.1 |
| BR-RCP-002 | A blind receipt (no PO) requires a reason and warehouse-manager approval before posting. | `GoodsReceipt.post()` | 3.3.1.2 |
| BR-RCP-003 | For a lot-tracked SKU, counting cannot be finished without a lot number; for an expiry-tracked SKU, without an expiry date. | `GoodsReceipt.finishCounting()` | 3.3.3.1 |
| BR-RCP-004 | An expiry date earlier than today, or later than today plus the product's maximum shelf life, is rejected at entry. | `ReceiptLine` value object | 3.3.8.2 |
| BR-INV-001 | Supplier invoice numbers are unique per supplier. | DB unique index | 3.4.1 |
| BR-INV-002 | Three-way match passes when quantity and unit price agree with the PO and the receipt within the configured tolerance. | `SupplierInvoice.match()` | 3.4.3 |
| BR-INV-003 | A match exception can only be overridden by someone whose approval limit is at least the variance amount. | `SupplierInvoice.override()` | 3.4.4.3 |

## Warehouse and slotting — `warehouse-service`

| Id | Rule | Enforced in | WBS |
|---|---|---|---|
| BR-SLT-001 | A location that violates any hard constraint (hazmat, temperature, single-SKU, incompatible neighbour) is eliminated before scoring, never merely down-ranked. | `SlottingEngine.suggest()` | 3.5.2.3 |
| BR-SLT-002 | Putting away to a location other than the suggested one requires a reason; outside the ranked list it requires manager approval. | `PutawayTask.complete()` | 3.5.4.3 |
| BR-SLT-003 | A location's occupied weight and volume may never exceed its configured capacity. | `Location` aggregate | 3.5.1.4 |
| BR-SLT-004 | Every slotting suggestion must carry a stated reason, and the reason is stored with the task. | `SlottingEngine.suggest()` | 3.5.3.3 |

## Inventory — `inventory-service`

| Id | Rule | Enforced in | WBS |
|---|---|---|---|
| BR-STK-001 | Stock may only be reserved when `ATP ≥ requested`, where `ATP = on_hand − reserved − allocated` over stock in condition GOOD. | `StockItem.reserve()` | 3.6.4.1 |
| BR-STK-002 | A reservation expires after the configured window and is released automatically. | scheduled sweeper | 3.14.5.2 |
| BR-STK-003 | Marking stock damaged requires a reason and at least one photo. | `StockItem.block()` | 3.6.5.1 |
| BR-STK-004 | Stock whose expiry date has passed is moved to condition EXPIRED nightly and drops out of ATP immediately. | scheduled sweeper | 3.6.7.2 |
| BR-STK-005 | Any inventory adjustment beyond the configured value threshold requires warehouse-manager approval before it is posted. | `StockAdjustment.post()` | 3.6.5.2 |
| BR-STK-006 | Allocation picks the lot with the earliest expiry date first (FEFO); where no expiry exists, the earliest receipt date (FIFO). | `StockAllocator` domain service | 3.10.3.2 |
| BR-STK-007 | `on_hand ≥ reserved + allocated` at all times, for every stock row. Enforced in the aggregate **and** as a database CHECK constraint. | `StockItem` + DDL | — |
| BR-TRF-001 | A transfer above the configured value threshold needs multi-level approval. | `TransferOrder.approve()` | 3.10.2.2 |
| BR-TRF-002 | `on_hand(source) + in_transit + on_hand(destination)` is constant for the lifetime of a transfer. | integration test assertion | 3.10.4.1 |

## Design — `design-service`

| Id | Rule | Enforced in | WBS |
|---|---|---|---|
| BR-DSG-001 | A design cannot be confirmed while preflight fails: image DPI below the configured minimum, artwork outside the safe area, or an unembedded font. | `DesignDraft.confirm()` | 3.12.4 |
| BR-DSG-002 | A confirmed snapshot is immutable. Changing a design creates a new snapshot, and re-pointing an order line is only allowed while the order is in PENDING_PAYMENT or CONFIRMED. | `DesignSnapshot` + `Order.replaceDesign()` | 3.12.5.1 |
| BR-DSG-003 | Packing cannot be completed when the snapshot checksum on the order line differs from the artifact in storage. | `Package.complete()` | 3.8.3 |

## Order and payment — `order-service`, `payment-service`

| Id | Rule | Enforced in | WBS |
|---|---|---|---|
| BR-ORD-001 | An order is only created after **every** line has been reserved. A partially reserved order is never persisted. | checkout use case, one transaction | 3.14.5.1 |
| BR-ORD-002 | An order can only be released to a warehouse that can fulfil every line from its own stock, unless the coordinator explicitly splits it. | `Order.release()` | 3.17.2.2 |
| BR-ORD-003 | Cancellation windows by state and actor are exactly as tabulated in `03-state-machines.md`, machine 9. | `Order.cancel()` | 3.17.4.1 |
| BR-ORD-004 | Custom-printed lines already in production are not refunded on cancellation. | `Order.cancel()` | 3.17.4.2 |
| BR-ORD-005 | An order completes automatically 7 days after delivery when no RMA is open. | scheduled sweeper | 3.17.1.1 |
| BR-ORD-006 | A voucher is validated again at order creation, not only when it is applied to the cart. | `Order.place()` | 3.14.3.3 |
| BR-PAY-001 | A gateway callback with an invalid signature is rejected and logged; it never changes payment status. | payment webhook adapter | 3.15.1.3 |
| BR-PAY-002 | An order moves to CONFIRMED when captured amount ≥ the required deposit, which for a full-payment order equals the order total. | `Order.confirmPayment()` | 3.15.5.2 |
| BR-PAY-003 | A refund above the approver's limit cannot be issued; it must be escalated. | `Refund.approve()` | 3.15.6.2 |
| BR-PAY-004 | A COD payment is only captured at carrier reconciliation, never at checkout. | reconciliation use case | 3.15.4.2 |
| BR-RMA-001 | A return can only be opened within the configured return window after delivery. | `Rma.request()` | 3.17.5.1 |
| BR-RMA-002 | Custom-printed items are not returnable unless defective. | `Rma.approve()` | 3.17.5.2 |

## Customer and access — `customer-service`, `identity-service`

| Id | Rule | Enforced in | WBS |
|---|---|---|---|
| BR-CUS-001 | Two customers with the same phone or the same email are flagged as duplicates for manual merge; they are never merged automatically. | duplicate detection job | 3.18.7.1 |
| BR-CUS-002 | A right-to-be-forgotten request anonymises the customer but preserves order records, because financial records must be retained. | `Customer.anonymise()` | 3.18.5.3 |
| BR-CUS-003 | A blacklisted customer cannot place an order; existing orders are unaffected. | `Order.place()` guard | 3.18.6.1 |
| BR-SEC-001 | Every endpoint that changes state requires a permission; an endpoint without one fails startup. | `PermissionCatalogScanner` | 3.19.1 |
| BR-SEC-002 | Warehouse staff may only act on warehouses they are assigned to. | `DataScope.WAREHOUSE` filter | 3.19.7.2 |
