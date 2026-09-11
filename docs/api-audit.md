# API audit — endpoints for the whole flow

Every REST endpoint the 25 business flows need, by module. Derived from
`docs/business-design/04c-all-flows.md` and the URL/permission conventions in
`docs/adding-a-module.md`.

## How to read this

- **Status** — `✅` implemented in code today (only `inventory` and `order` have controllers);
  `⬜` proposed, derived from the flow docs, to build with the module. Names follow the repo's
  conventions but the team confirms them when building.
- **Action** — the RBAC action from `common/security/Action`: `VIEW_PAGE, READ, CREATE, UPDATE,
  DELETE, APPROVE, EXPORT`. `public` = no token (auth, storefront, signed webhooks). Every page also
  implies `VIEW_PAGE`; only the data action is listed. Scope (`OWN`/`WAREHOUSE`/`ALL`) is noted where
  it matters.
- **Convention reminder** — base path `/api/v1/<resource>`; a state transition is a `POST` to a
  **sub-resource** (`POST /orders/{id}/cancellation`), never `PATCH …?status=`. One word per concept
  across table, domain, api and URL.

## Two things this list is NOT

1. **Not the event flow.** The flow diagrams draw dashed arrows for events (`OrderPlaced`,
   `PaymentFailed`, `StockReserved`…). In this monolith (ADR-0005) those are **in-process
   `@ApplicationModuleListener`s and direct `api` calls, not HTTP** — they have no endpoint and are
   listed separately under *Non-REST steps* at the end. Do not build a controller for them.
2. **Not a promise of scope.** 2 of 14 modules are implemented; the other 12 are skeletons. The rows
   for them are the *target*, one screen/use-case per row.

---

## inventory  (F-CART · F-FUL · F-TRF · F-CNT · F-EXP · X-RESV · F-RCP)

| Status | Method | Path | Purpose | Flow | Action |
|---|---|---|---|---|---|
| ✅ | GET | `/api/v1/inventory/stock-items?sku=` | Availability per location, FEFO order | F-CART | READ · WAREHOUSE |
| ✅ | GET | `/api/v1/inventory/stock-items/atp?sku=` | Available-to-promise across locations | F-CART/F-CAT | READ |
| ✅ | POST | `/api/v1/inventory/reservations` | Hold stock for an order line | F-CART | CREATE |
| ✅ | POST | `/api/v1/inventory/reservations/{id}/consumption` | Deduct on pick confirmation | F-FUL | UPDATE · WAREHOUSE |
| ✅ | DELETE | `/api/v1/inventory/reservations/{id}` | Release a hold | F-PAY/X-RESV | DELETE |
| ⬜ | POST | `/api/v1/inventory/allocations` | Reservation → allocation (FEFO), on release | F-FUL | CREATE · WAREHOUSE |
| ⬜ | POST | `/api/v1/inventory/stock-items/{id}/quarantine-release` | QC pass: QUARANTINE → AVAILABLE | F-RCP | APPROVE · WAREHOUSE |
| ⬜ | POST | `/api/v1/inventory/stock-items/{id}/block` | Block/damage/expire a lot | F-EXP | UPDATE · WAREHOUSE |
| ⬜ | GET | `/api/v1/inventory/stock-movements?sku=&location=` | Movement ledger (append-only) | all | READ · EXPORT |
| ⬜ | POST | `/api/v1/inventory/transfer-orders` | Create inter-warehouse transfer | F-TRF | CREATE |
| ⬜ | POST | `/api/v1/inventory/transfer-orders/{id}/approval` | Approve by value threshold | F-TRF | APPROVE |
| ⬜ | POST | `/api/v1/inventory/transfer-orders/{id}/issue` | Issue from source → IN_TRANSIT | F-TRF | UPDATE · WAREHOUSE |
| ⬜ | POST | `/api/v1/inventory/transfer-orders/{id}/receipt` | Receive at destination → COMPLETED | F-TRF | UPDATE · WAREHOUSE |
| ⬜ | POST | `/api/v1/inventory/cycle-counts` | Plan a count | F-CNT | CREATE · WAREHOUSE |
| ⬜ | PUT | `/api/v1/inventory/cycle-counts/{id}/lines` | Enter counted quantities | F-CNT | UPDATE · WAREHOUSE |
| ⬜ | POST | `/api/v1/inventory/cycle-counts/{id}/posting` | Post after variance review | F-CNT | APPROVE · WAREHOUSE |
| ⬜ | POST | `/api/v1/inventory/stock-adjustments` | Adjust on-hand (needs approval) | F-CNT/F-TRF | APPROVE · WAREHOUSE |

## order  (F-CART · F-PAY · F-FUL · F-RMA · F-SHP · X-SHORT · X-CHK)

| Status | Method | Path | Purpose | Flow | Action |
|---|---|---|---|---|---|
| ⬜ | POST | `/api/v1/carts/items` | Add item to cart | F-CART | CREATE · OWN |
| ⬜ | GET | `/api/v1/carts/{sessionId}` | Read the cart | F-CART | READ · OWN |
| ⬜ | PUT | `/api/v1/carts/items/{id}` | Change quantity | F-CART | UPDATE · OWN |
| ⬜ | DELETE | `/api/v1/carts/items/{id}` | Remove item | F-CART | DELETE · OWN |
| ⬜ | POST | `/api/v1/carts/{id}/voucher` | Apply a voucher | F-CART | UPDATE · OWN |
| ✅ | POST | `/api/v1/orders` | Place order + reserve stock, one transaction | F-CART | CREATE · OWN |
| ✅ | GET | `/api/v1/orders/{id}` | Look up one order | all | READ · OWN |
| ✅ | POST | `/api/v1/orders/{id}/cancellation` | Cancel + release reservations | F-CART | UPDATE · OWN |
| ⬜ | GET | `/api/v1/orders` | List/search orders | all | READ · OWN/ALL · EXPORT |
| ⬜ | POST | `/api/v1/orders/{id}/release` | Release to fulfilment → READY_TO_FULFILL | F-FUL | UPDATE · WAREHOUSE |
| ⬜ | POST | `/api/v1/orders/{id}/hold-resolution` | Resolve an ON_HOLD (short / design mismatch) | X-SHORT/X-CHK | APPROVE |
| ⬜ | POST | `/api/v1/rmas` | Open a return/exchange | F-RMA | CREATE · OWN |
| ⬜ | POST | `/api/v1/rmas/{id}/approval` | Approve the RMA (BR-RMA-002) | F-RMA | APPROVE |
| ⬜ | GET | `/api/v1/rmas/{id}` | RMA status | F-RMA | READ · OWN |

## product  (F-PRD)

| Status | Method | Path | Purpose | Flow | Action |
|---|---|---|---|---|---|
| ⬜ | POST | `/api/v1/products` | Create product + variants (DRAFT) | F-PRD | CREATE |
| ⬜ | GET | `/api/v1/products` · `/{id}` | List / detail of product master | F-PRD | READ · EXPORT |
| ⬜ | PUT | `/api/v1/products/{id}` | Edit while DRAFT | F-PRD | UPDATE |
| ⬜ | POST | `/api/v1/products/{id}/submission` | Submit for approval → PENDING_APPROVAL | F-PRD | UPDATE |
| ⬜ | POST | `/api/v1/products/{id}/approval` | Approve (different user, BR-PRD-003) | F-PRD | APPROVE |
| ⬜ | POST | `/api/v1/products/{id}/publication` | Publish → PUBLISHED (emits ProductPublished) | F-PRD | UPDATE |

## catalog  (F-CAT · storefront)  — read model, fed by events

| Status | Method | Path | Purpose | Flow | Action |
|---|---|---|---|---|---|
| ⬜ | GET | `/api/v1/catalog/products` | PLP: list/filter published products | F-CAT | public |
| ⬜ | GET | `/api/v1/catalog/products/{slug}` | PDP: one product + stock *band* (not exact) | F-CAT | public |
| ⬜ | GET | `/api/v1/catalog/search?q=` | Search (Elasticsearch-backed) | F-CAT | public |

## procurement  (F-PO · F-RCP · F-MTC · F-RMA return receipt)

| Status | Method | Path | Purpose | Flow | Action |
|---|---|---|---|---|---|
| ⬜ | POST | `/api/v1/purchase-orders` | Create PO (DRAFT) | F-PO | CREATE |
| ⬜ | GET | `/api/v1/purchase-orders` · `/{id}` | List / detail | F-PO | READ · EXPORT |
| ⬜ | PUT | `/api/v1/purchase-orders/{id}` | Edit while DRAFT | F-PO | UPDATE |
| ⬜ | POST | `/api/v1/purchase-orders/{id}/submission` | Submit → PENDING_APPROVAL | F-PO | UPDATE |
| ⬜ | POST | `/api/v1/purchase-orders/{id}/approval` | Approve (limit ≥ PO value, BR-PO-002) | F-PO | APPROVE |
| ⬜ | POST | `/api/v1/purchase-orders/{id}/dispatch` | Send to supplier → SENT | F-PO | UPDATE |
| ⬜ | POST | `/api/v1/purchase-orders/{id}/confirmation` | Supplier confirmed → CONFIRMED | F-PO | UPDATE |
| ⬜ | POST | `/api/v1/purchase-orders/{id}/revisions` | Amend after receipt (BR-PO-004) | F-PO | CREATE |
| ⬜ | POST | `/api/v1/goods-receipts` | Open receipt from PO (DRAFT) | F-RCP | CREATE · WAREHOUSE |
| ⬜ | PUT | `/api/v1/goods-receipts/{id}/lines` | Enter qty / lot / expiry | F-RCP | UPDATE · WAREHOUSE |
| ⬜ | POST | `/api/v1/goods-receipts/{id}/photos` | Upload evidence photos | F-RCP | UPDATE · WAREHOUSE |
| ⬜ | POST | `/api/v1/goods-receipts/{id}/posting` | Post → GoodsReceived (QC pass / no QC) | F-RCP | APPROVE · WAREHOUSE |
| ⬜ | GET | `/api/v1/qc-tasks` | Pending QC queue | F-RCP | READ |
| ⬜ | POST | `/api/v1/qc-tasks/{id}/result` | Record QC: PASS / QUARANTINE / REJECT | F-RCP | APPROVE |
| ⬜ | POST | `/api/v1/supplier-invoices` | Enter invoice (DRAFT) | F-MTC | CREATE |
| ⬜ | POST | `/api/v1/supplier-invoices/{id}/matching` | Run 3-way match | F-MTC | UPDATE |
| ⬜ | POST | `/api/v1/supplier-invoices/{id}/override` | Override out-of-tolerance (BR-INV-003) | F-MTC | APPROVE |
| ⬜ | POST | `/api/v1/supplier-invoices/{id}/payment-approval` | Approve for payment | F-MTC | APPROVE |

## warehouse  (F-PUT · F-REP · location master)

| Status | Method | Path | Purpose | Flow | Action |
|---|---|---|---|---|---|
| ⬜ | GET | `/api/v1/warehouse/locations` · CRUD | Warehouse map / slotting config | F-PUT | READ/CREATE/UPDATE · WAREHOUSE |
| ⬜ | GET | `/api/v1/warehouse/putaway-tasks` | Putaway queue | F-PUT | READ · WAREHOUSE |
| ⬜ | GET | `/api/v1/warehouse/putaway-tasks/{id}/suggestions` | Ranked location suggestions + reason | F-PUT | READ · WAREHOUSE |
| ⬜ | POST | `/api/v1/warehouse/putaway-tasks/{id}/assignment` | Assign to a worker | F-PUT | UPDATE · WAREHOUSE |
| ⬜ | POST | `/api/v1/warehouse/putaway-tasks/{id}/completion` | Complete with actual location | F-PUT | UPDATE · WAREHOUSE |
| ⬜ | GET | `/api/v1/warehouse/replenishment-tasks` | Replenishment queue | F-REP | READ · WAREHOUSE |
| ⬜ | POST | `/api/v1/warehouse/replenishment-tasks/{id}/completion` | Complete a replenishment move | F-REP | UPDATE · WAREHOUSE |
| ⬜ | GET/PUT | `/api/v1/warehouse/replenishment-rules` | Min/max thresholds per pick location | F-REP | READ/UPDATE · WAREHOUSE |

## design  (F-DSG · X-CHK)

| Status | Method | Path | Purpose | Flow | Action |
|---|---|---|---|---|---|
| ⬜ | POST | `/api/v1/designs` | Create draft (canvas JSON) | F-DSG | CREATE · OWN |
| ⬜ | GET | `/api/v1/designs/{id}` | Read draft | F-DSG | READ · OWN |
| ⬜ | PUT | `/api/v1/designs/{id}` | Edit while DRAFT | F-DSG | UPDATE · OWN |
| ⬜ | POST | `/api/v1/designs/{id}/assets` | Upload customer image | F-DSG | UPDATE · OWN |
| ⬜ | POST | `/api/v1/designs/{id}/preflight` | Run preflight (DPI/bleed/colour) | F-DSG | UPDATE · OWN |
| ⬜ | POST | `/api/v1/designs/{id}/confirmation` | Confirm → immutable snapshot + checksum | F-DSG | CREATE · OWN |
| ⬜ | GET | `/api/v1/designs/snapshots/{id}` | Read a locked snapshot (checksum verify) | X-CHK | READ |

## payment  (F-PAY · F-REF)

| Status | Method | Path | Purpose | Flow | Action |
|---|---|---|---|---|---|
| ⬜ | POST | `/api/v1/payments` | Initiate a payment for an order | F-PAY | CREATE · OWN |
| ⬜ | GET | `/api/v1/payments/{id}` | Payment status | F-PAY | READ · OWN |
| ⬜ | POST | `/api/v1/payments/webhook/{gateway}` | **Signed** gateway callback → capture/fail | F-PAY | public (signature-verified) |
| ⬜ | POST | `/api/v1/payments/{id}/capture` | Bank-transfer capture on reconciliation | F-PAY | APPROVE |
| ⬜ | POST | `/api/v1/refunds` | Request a refund | F-REF | CREATE |
| ⬜ | POST | `/api/v1/refunds/{id}/approval` | Approve within limit (BR-PAY-003) | F-REF | APPROVE |
| ⬜ | POST | `/api/v1/settlements/import` | Import gateway settlement file | F-REF | CREATE |
| ⬜ | POST | `/api/v1/cod-remittances` | Record carrier COD remittance | F-PAY | CREATE |

## fulfillment  (F-FUL · F-SHP · X-SHORT · F-RTO · F-RMA return)

| Status | Method | Path | Purpose | Flow | Action |
|---|---|---|---|---|---|
| ⬜ | GET | `/api/v1/fulfillment/pick-lists` | Pick queue | F-FUL | READ · WAREHOUSE |
| ⬜ | POST | `/api/v1/fulfillment/pick-lists/{id}/completion` | Confirm picked → StockDeducted | F-FUL | UPDATE · WAREHOUSE |
| ⬜ | POST | `/api/v1/fulfillment/pick-lines/{id}/short` | Report a short pick | X-SHORT | UPDATE · WAREHOUSE |
| ⬜ | POST | `/api/v1/fulfillment/packages` | Pack (after design checksum verify) | F-FUL | CREATE · WAREHOUSE |
| ⬜ | POST | `/api/v1/fulfillment/shipments` | Create shipment | F-SHP | CREATE · WAREHOUSE |
| ⬜ | POST | `/api/v1/fulfillment/shipments/{id}/label` | Request carrier label + AWB | F-SHP | UPDATE · WAREHOUSE |
| ⬜ | POST | `/api/v1/fulfillment/shipments/webhook/{carrier}` | **Signed** carrier status webhook | F-SHP/F-RTO | public (signature-verified) |
| ⬜ | POST | `/api/v1/fulfillment/shipments/{id}/pod` | Proof of delivery → OrderDelivered | F-SHP | UPDATE · WAREHOUSE |
| ⬜ | POST | `/api/v1/fulfillment/return-shipments` | Return label for an approved RMA | F-RMA | CREATE · WAREHOUSE |

## identity  (P-PERM · auth · users/roles)

| Status | Method | Path | Purpose | Flow | Action |
|---|---|---|---|---|---|
| ⬜ | POST | `/api/v1/auth/login` | Authenticate, issue JWT | — | public |
| ⬜ | POST | `/api/v1/auth/refresh` | Refresh token | — | public |
| ⬜ | POST | `/api/v1/auth/logout` | Revoke refresh token | — | authenticated |
| ⬜ | GET | `/oauth2/jwks` | Public keys the app validates tokens with | P-PERM | public |
| ⬜ | GET/POST/PUT | `/api/v1/users` · `/{id}` | User admin | — | READ/CREATE/UPDATE · ALL |
| ⬜ | GET | `/api/v1/roles` · CRUD | Role admin | — | READ/CREATE/UPDATE |
| ⬜ | GET | `/api/v1/rbac/matrix` | Permission matrix (RoleMatrixView) | P-PERM | READ |
| ⬜ | POST | `/api/v1/rbac/grants` | Grant/revoke (resource, action) to a role | P-PERM | APPROVE |

## reporting  (P-RPT · F-MTC · F-REF)  — read-only, built from events

| Status | Method | Path | Purpose | Flow | Action |
|---|---|---|---|---|---|
| ⬜ | GET | `/api/v1/reports/kpi-daily` | Warehouse/finance KPIs | P-RPT | READ · EXPORT |
| ⬜ | GET | `/api/v1/reports/ap-aging` | Accounts-payable aging | F-MTC | READ · EXPORT |
| ⬜ | GET | `/api/v1/reports/revenue` | Revenue read model | F-REF | READ · EXPORT |
| ⬜ | GET | `/api/v1/reports/inventory` | Stock KPIs | P-RPT | READ · EXPORT |

## chat  (P-CHAT)

| Status | Method | Path | Purpose | Flow | Action |
|---|---|---|---|---|---|
| ⬜ | WS | `/ws/chat` | Live conversation (WebSocket) | P-CHAT | authenticated |
| ⬜ | POST | `/api/v1/conversations` | Open a conversation | P-CHAT | CREATE · OWN |
| ⬜ | GET | `/api/v1/conversations` · `/{id}` | List / read a conversation | P-CHAT | READ |
| ⬜ | POST | `/api/v1/conversations/{id}/messages` | Send a message (rich content) | P-CHAT | CREATE |
| ⬜ | POST | `/api/v1/conversations/{id}/assignment` | Route to a sales agent | P-CHAT | UPDATE |
| ⬜ | POST | `/api/v1/conversations/{id}/resolution` | Close → RESOLVED | P-CHAT | UPDATE |

## notification  (P-NOTI)  — mostly event-driven

| Status | Method | Path | Purpose | Flow | Action |
|---|---|---|---|---|---|
| ⬜ | GET | `/api/v1/notifications` | In-app inbox for the current user | P-NOTI | READ · OWN |
| ⬜ | POST | `/api/v1/notifications/{id}/resend` | Manual resend after a failed send | P-NOTI | UPDATE |

---

## Non-REST steps of the flows (do NOT build controllers for these)

These carry the flow but have **no HTTP endpoint** — they are in-process listeners, scheduled jobs,
or shared infrastructure. Listed so the flow coverage is honest.

| Kind | Where | Trigger | Flow |
|---|---|---|---|
| Event listener | `inventory` ← `payment` | `PaymentFailed` → release reservation | F-PAY |
| Event listener | `inventory`/`fulfillment` ← `order` | `OrderReleased` → allocate / pick list | F-FUL |
| Event listener | `catalog` ← `product`,`inventory` | `ProductPublished`, `StockLevelChanged` → update read model | F-CAT |
| Event listener | `warehouse` ← `inventory` | `GoodsReceived` → putaway task + suggestions | F-PUT |
| Event listener | `reporting` ← everyone | project events into flat read tables | P-RPT |
| Event listener | `notification` ← everyone | render + send on domain events | P-NOTI |
| Scheduled job | `inventory` `ReservationSweeper` | every minute: expire held reservations | X-RESV |
| Scheduled job | `inventory` | nightly 02:00: mark expired lots | F-EXP |
| Scheduled job | `warehouse` | threshold sweep: raise replenishment tasks | F-REP |
| Scheduled job | `procurement`/`payment` | audit/idempotency purge (cron) | platform |
| Startup | every module | register `@PermissionResource` catalogue | P-PERM |

## Coverage summary

- **Implemented today:** 8 endpoints (5 inventory + 3 order). Marked `✅`.
- **Proposed across the other 12 modules + the rest of inventory/order:** ~90 endpoints, `⬜`.
- **Two signed webhooks** (payment gateway, shipping carrier) are the only public write endpoints —
  they verify a signature and never trust the body's status blindly (BR-PAY-001).
- Every `⬜` row is a *target derived from the flow docs*, not a committed contract. Confirm names and
  split when you build the module, following `docs/adding-a-module.md`.
