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
| BR-SLT-003 | Putaway never takes a storage location past its `capacity_units`, nor a shelf level past its `max_weight` / `usable_height`. How full a location is is derived from stock, never stored. | slotting hard constraints ([module 06 BR-01](https://github.com/KLTN-FA26/docs/blob/b11945a/docs/warehouse/06-warehouse-map-slotting/README.md)), SCRUM-91 | 3.5.1.4 |
| BR-SLT-004 | Every slotting suggestion must carry a stated reason, and the reason is stored with the task. | `SlottingEngine.suggest()` | 3.5.3.3 |

### The warehouse map (module 06, BR-06 → BR-14)

The rules themselves are in [module 06 §6](https://github.com/KLTN-FA26/docs/blob/b11945a/docs/warehouse/06-warehouse-map-slotting/README.md); only where each is enforced is listed
here. Every layout change takes the warehouse's row lock first, because BR-06 and BR-07 span
several aggregates and no constraint can state them.

| Id | In short | Enforced in |
|---|---|---|
| BR-06 | Everything inside the map frame | `Warehouse.requireOnMap`, `Warehouse.resizeMap` |
| BR-07 | Shelves and areas do not overlap; bins stay inside their shelf, apart on a level | `Placement.requireClear`, `Shelf` |
| BR-08 | A shelf with a pickable bin has a pick face | `Shelf`, `PickFaces`; the "face not blocked by a wall" half is SCRUM-91 |
| BR-09 | Distances are walking distances, through open doors only | routing, SCRUM-91 (`Boundary` holds the walls and doors) |
| BR-10 | `location_code` = `HCM-A01-2-B` / `HCM-QC01`, unique system-wide | `LocationCode`, `CodePart`; `uk_storage_location_code`, `ck_storage_location_code` |
| BR-11 | Stock only in a bin or a storage area, never `NON_STORAGE` | `AreaDetails`, `Area`; `ck_area_storage` |
| BR-12 | Picking only from pickable bins | picking, not built yet |
| BR-13 | Prefix, shelf, level, bin and area codes never change | entities (`updatable = false`); `tg_storage_location_immutable` and the other `*_immutable` triggers |
| BR-14 | Bin autogeneration on empty levels | `Shelf.generateBins`, `BinGrid` |

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
| BR-ORD-004 | Custom-printed lines already in production are not refunded on cancellation. | `Order.cancel()`: the share kept (`retainedPercent`) is decided by Sales / the coordinator when cancelling or approving a request (SCRUM-460) | 3.17.4.2 |
| BR-ORD-009 | Every cancellation records a reason code (OTHER needs a note) and is announced as `OrderCancelled`, so the money and the warehouse work follow (kltn-docs 17 BR-03). | `Order.cancel()`, CHECK `ck_order_cancellation_code` | 3.17.4 |
| BR-ORD-005 | An order completes automatically 7 days after delivery when no RMA is open. | scheduled sweeper | 3.17.1.1 |
| BR-ORD-006 | *(On hold 2026-10-06 — vouchers not used in B2B.)* A voucher is validated again at order creation, not only when it is applied to the cart. | `Order.place()` | 3.14.3.3 |
| BR-ORD-007 | Only an ACTIVE B2B customer can receive quotes or place orders; there is no guest checkout. Every line quantity is ≥ the MOQ of the blank / quote. | `Order.place()`, `Quote.accept()` | 3.14.6 |
| BR-ORD-008 | A quote can be accepted only within its validity; a new design needs an APPROVED sample first. | `Quote.accept()` | 3.14.7 |
| BR-PAY-001 | A gateway callback with an invalid signature is rejected and logged; it never changes payment status. | payment webhook adapter | 3.15.1.3 |
| BR-PAY-002 | An order moves to CONFIRMED when captured amount ≥ the required deposit, which for a full-payment order equals the order total. | `Order.recordPayment()` (SCRUM-460), CHECK `ck_order_confirmed_basis` | 3.15.5.2 |
| BR-PAY-003 | A refund above the approver's limit cannot be issued; it must be escalated. | `Refund.approve()` | 3.15.6.2 |
| BR-PAY-004 | *(On hold 2026-10-06 — no retail COD.)* A COD payment is only captured at carrier reconciliation, never at checkout. | reconciliation use case | 3.15.4.2 |
| BR-PAY-005 | A credit order is CONFIRMED only when outstanding balance + order total ≤ the customer's credit limit; above it the order is ON_HOLD (reason CREDIT) until an authorised approver releases it. Outstanding balance = total − paid of the customer's live credit orders not yet delivered (orders still waiting for a credit decision excluded), plus the open receivables of delivered ones (SCRUM-431). The customer row is locked for the check, so two checkouts are decided one after the other. | `OrderServiceImpl.placeOrder` → `Order.submitOnCredit`, `CreditHoldService` (approve / refuse), `ordering.order_credit_check` (SCRUM-427) | 3.15.7 |
| BR-PAY-007 | A customer is sold only on the payment terms their commercial terms allow; without terms, prepaid only. Terms are set by the credit approver (kltn-docs 18 BR-02). | `OrderServiceImpl.placeOrder`, `customer.credit_profile` (SCRUM-427) | 3.15.7 |
| BR-PAY-006 | A bank transfer is recorded as captured only after accounting matches it to the bank statement. | payment confirmation use case | 3.15.3 |
| BR-RMA-001 | A return can only be opened within the configured return window after delivery. | `Rma.request()` | 3.17.5.1 |
| BR-RMA-002 | Custom-printed items are not returnable unless defective. | `Rma.approve()` | 3.17.5.2 |

## Production — `production` (new 2026-10-06)

| Id | Rule | Enforced in | WBS |
|---|---|---|---|
| BR-PRD-01 | Exactly one production order per custom order line or sample request; a redelivered event creates nothing new. | release listener (idempotent on source id) | 3.25.2 |
| BR-PRD-02 | Nothing is printed before prepress passes and the file checksum matches the locked design snapshot. | `ProductionOrder.passPrepress()` | 3.25.3 |
| BR-PRD-03 | Blanks are issued only against a production order and never above its remaining need. | `ProductionOrder.issueBlanks()` | 3.25.4 |
| BR-PRD-04 | Good + scrap = printed; scrap always has a reason and leaves stock through a reasoned adjustment. | `ProductionOrder.recordOutput()` | 3.25.5 |
| BR-PRD-05 | A production order completes only after QC; an ORDER production order is checked against the customer-approved sample. | `ProductionOrder.passQc()` | 3.25.6 |
| BR-PRD-06 | An order leaves IN_PRODUCTION only when every production order of the order is completed (or its line cancelled). | order listener on `ProductionCompleted` | 3.25.7 |
| BR-PRD-07 | The design cannot change once a production order is PRINTING. | `Order.replaceDesign()` | 3.25.5 |
| BR-PRD-08 | A line with a new design (no APPROVED sample for design + blank) cannot be released to production. | `Order.release()` | 3.25.2 |
| BR-PRD-09 | A deposit order is released to production only after the deposit is received. | `Order.release()` | 3.15.5 |
| BR-PRD-10 | Cancelling while PRINTING follows the order cancellation policy: refused, or cancelled with a production fee; printed items are scrap. | `ProductionOrder.cancel()` | 3.25.8 |
| BR-PRD-11 | Only an ORDER production order can be subcontracted; a SAMPLE is always printed in-house. | `ProductionOrder.splitForSubcontracting()` | 3.25.10 |
| BR-PRD-12 | A split takes only quantity not yet issued, from a parent in PENDING_PREPRESS, READY or ON_HOLD; 0 < split < unissued quantity (sending everything converts the order itself instead of creating a child). | `ProductionOrder.splitForSubcontracting()` + `CHECK` on quantities | 3.25.10 |
| BR-PRD-13 | The subcontractor is an active supplier flagged as a print subcontractor; a child is sent out only once its `SUBCONTRACT` PO is approved. | `ProductionOrder.sendToSubcontractor()` via `procurement :: api` | 3.25.10 |
| BR-PRD-14 | The file sent out is the prepress-checked `PRINT_READY` artifact; after sending, the design is locked as if printing. | `ProductionOrder.sendToSubcontractor()` | 3.25.10 |
| BR-PRD-15 | Blanks sent to a subcontractor stay our stock at a virtual subcontractor location, never count towards ATP, and leave it only through a goods receipt or a reasoned adjustment. | `inventory` move + ATP query excludes subcontractor locations | 3.25.10 |
| BR-PRD-16 | A subcontracted child completes only after its goods receipt is posted, the blank reconciliation is done (SUPPLIED_BLANKS) and QC passes the full quantity. | `ProductionOrder.passQc()` | 3.25.10 |
| BR-PRD-17 | Loss above the subcontractor's tolerance needs warehouse-manager approval and becomes a deduction on the subcontract invoice; units failing QC are not paid. | reconciliation in `procurement` receipt + 3-way match | 3.25.10, 3.4.2 |

## Customer and access — `customer-service`, `identity-service`

| Id | Rule | Enforced in | WBS |
|---|---|---|---|
| BR-CUS-001 | Two customers with the same phone or the same email are flagged as duplicates for manual merge; they are never merged automatically. | duplicate detection job | 3.18.7.1 |
| BR-CUS-002 | A right-to-be-forgotten request anonymises the customer but preserves order records, because financial records must be retained. | `Customer.anonymise()` | 3.18.5.3 |
| BR-CUS-003 | A blacklisted customer cannot place an order; existing orders are unaffected. | `Order.place()` guard | 3.18.6.1 |
| BR-SEC-001 | Every endpoint that changes state requires a permission; an endpoint without one fails startup. | `PermissionCatalogScanner` | 3.19.1 |
| BR-SEC-002 | Warehouse staff may only act on warehouses they are assigned to. | `DataScope.WAREHOUSE` filter | 3.19.7.2 |
