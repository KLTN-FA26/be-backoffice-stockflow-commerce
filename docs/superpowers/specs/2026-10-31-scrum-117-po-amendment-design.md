# SCRUM-117 Amending an approved purchase order — design

*2026-10-31 · StockFlowCommerce (FA26SE029) · owner: Phương*

Jira: [SCRUM-117](https://minh7n3.atlassian.net/browse/SCRUM-117) PO Amendment & Revision Control (WBS 3.2.5), sub-tasks
SCRUM-131 revision creation, SCRUM-132 change log & audit trail, SCRUM-133 supplier notification of changes.
Branch `feature/SCRUM-117-po-amendment` from `origin/develop` @ 878a1be8.

A STANDARD purchase order can be changed only while it is DRAFT. Once it is approved, the only way to change what
was ordered is to cancel it (nothing received yet) or close it short, and order again under a new number. This
design lets an approved or confirmed STANDARD order be amended through a revision approved again, tells the
supplier when the order had already been sent, and exposes the revision history and the timeline that are
written today and never read.

## 1. What the ticket says, and what is true

The ticket text was written before the procurement schema existed and is wrong on two points:

| Ticket | Code on `develop` |
|---|---|
| "No revision/version field exists yet beyond the optimistic-lock `version`" | `purchase_order_revisions`, `purchase_order_line_revisions`, `active_revision_id` / `pending_revision_id` exist since `V20260928002200`. Every submit writes a revision (0, then +1 for each resubmission after a rejection). |
| "Only amend a PO before it reaches `SENT`" | There is no `SENT` (decision D4: `CONFIRMED` = locked and sent). Before approval the order is DRAFT and already editable in place (SCRUM-462). |

The three sources on amendment disagreed:

| Source | Says |
|---|---|
| kltn-docs 02 §4.5 step 19, BR-02 (2026-10-28) | A confirmed STANDARD order cannot be changed; only SUBCONTRACT orders have a supplement. |
| kltn-docs 02 before 2026-10-28 | After `Confirmed`, any change of SKU / quantity / price creates a PO revision. |
| `docs/business-design/03-state-machines.md` §2, BR-PO-004, `04c` F-PO step 07 | Amending from `SENT` on creates revision n+1 and emits `PurchaseOrderAmended`; forbidden once a receipt exists. Enforced by `PurchaseOrder.amend()`, which does not exist. |

The 2026-10-28 wording described what the code did not have yet ("Align … with the code"); it was not a business
decision. The decisions below settle it.

## 2. What exists

| | State |
|---|---|
| Revisions | `PurchaseOrderRepositoryAdapter.recordRevision` freezes the header (`snapshot_header` JSONB) and every live line, then points the lines at it. `kind` is `INITIAL` for 0 and `AMENDMENT` for every later number, resubmissions included. Revisions, line revisions, approvals and events are append-only (`V20260928002400`). |
| Line guard | `guard_po_line_content_change` (`V20261020000400`): outside DRAFT a line's content changes only in the same UPDATE that moves `po_revision_id` to an `AMENDMENT` revision of its own order that froze exactly that content. `po_revision_id` is not mapped in `PoLineJpaEntity`. |
| SUBCONTRACT amendment | `SubcontractPurchasingImpl.supplement` → `SubcontractOrderStoreAdapter.requestAmendment` (pending revision in SQL) → auto-approved within the requester's limit or decided by `approveAmendment` / `rejectAmendment` (four eyes, approver's limit). Endpoints `POST /{id}/amendment/approval` and `/amendment/rejection`. |
| Timeline | `purchase_order_events`: every transition, and draft edits as `REVISED` with before/after in `payload`. Allowed actions include `REVISED`, `APPROVED`, `AUTO_APPROVED`, `REJECTED`, `WITHDRAWN`. **No endpoint reads it.** |
| Supplier delivery | `PurchaseOrderSent` → `PurchaseOrderNotificationListener`. Operation reference `purchase-order:{id}` (`…:cancellation` for the cancellation notice), also the supplier API's `Idempotency-Key`. `prepareSupplierDelivery` refuses once that reference was sent successfully; recording the supplier's response sets `po_delivery_control.suppressed`, and the listener skips any non-cancellation event of a suppressed order. Mail texts are keys of `i18n/messages*.properties`, not database rows. |
| Receiving | `ReceivingPurchaseOrder.statusAfter` already counts a `CLOSED` or `CANCELLED` line as nothing left to receive, and only `OPEN` / `PARTIALLY_RECEIVED` lines take receipts. |
| Approval limits | `ApprovalLimits.autoApprovalLimit(order, holder, supplier, total, now)` and `limitOf`; the subcontract amendment already uses them (SCRUM-124 rule 5). |

## 3. Decisions taken on 2026-10-31

| # | Question | Decision |
|---|---|---|
| D1 | May an approved STANDARD order be amended? | **Yes**, while it is `APPROVED` or `CONFIRMED`, nothing has been received and no goods receipt is being counted or waits for approval. |
| D2 | What may an amendment change? | Line quantity, unit price, tax rate and description; removing a line; the expected delivery date and the order note. **Not**: adding a line, the unit a line is ordered in, the receiving warehouse, the supplier, the currency. |
| D3 | Supplier side | When the amendment of an order already sent is approved, the system sends the amended order to the supplier on its own, and the supplier's response returns to `PENDING`: they agreed to the previous revision, not to this one. |
| D4 | Approval | As the SUBCONTRACT amendment: auto-approved when the new total is within the requester's limit (`autoApprovalLimit`); otherwise someone else decides, whose own limit, when configured, covers the new total. One pending amendment per order. |
| D5 | Goods arriving while an amendment waits | Receiving is never blocked; a receipt counts against the revision in force. Approving the amendment is refused while a receipt is in progress or once anything was received, and the pending amendment is withdrawn when the first receipt is posted. |
| D6 | Order rejected by the supplier | **Not amendable** (kltn-docs: cancel and order elsewhere). BR-13 stays "answered once" except for D3. |
| D7 | Architecture | Rules in the `PurchaseOrder` aggregate, orchestration in a new `PurchaseOrderAmendments` service, line content applied in SQL. The SUBCONTRACT path (`SubcontractOrderStoreAdapter`) is not rewritten; it shares limits, the supplier notice and the read side. |
| D8 | Migration | **None.** Event actions, the line guard and the revision tables already allow everything here. |

## 4. Components

```
client ──POST /purchase-orders/{id}/amendments──────────▶ PurchaseOrderController
       ──POST /{id}/amendment/approval|rejection|withdrawal ─▶ (dispatch on the order's type)
                                                             ├─ STANDARD    → PurchaseOrderAmendments (new)
                                                             └─ SUBCONTRACT → SubcontractAmendments (existing)
PurchaseOrderAmendments
   ├─ PurchaseOrder (aggregate): requestAmendment / applyApprovedAmendment / dropAmendment
   ├─ AmendmentLineChecks (new): catalog terms (BR-08) and purchasability (BR-01) of the changed lines
   ├─ ApprovalLimits
   ├─ PurchaseOrderRepository: recordAmendment, pendingAmendment, applyAmendment, dropAmendment
   └─ SupplierAmendmentNotice (new): PurchaseOrderSent(amendment) when the order had been sent
receiving (ReceivingPurchaseOrderAdapter) ──first receipt posted──▶ withdraw a pending STANDARD amendment
client ──GET /{id}/revisions, /{id}/revisions/{no}, /{id}/timeline──▶ PurchaseOrderHistoryController (new)
```

`PurchaseOrderAmendments` is its own class because `ProcurementServiceImpl` is already 47.8K. The draft checker
`checkLines` is not reused: it resolves SKUs, sizes packing units and takes catalog prices, none of which an
amendment does (the SKU, the unit and the price are the line's), and it refuses every non-purchasable line where an
amendment refuses only an increase. `AmendmentLineChecks` instead reuses the rules themselves —
`SupplierItemRepository.listedForShare`, `SupplierItem.orderQuantityViolations`, `ProductService.purchasability`,
`PackingUnit.wholeUnits` — so nothing is copied.

## 5. Requesting an amendment

`POST /api/v1/purchase-orders/{purchaseOrderId}/amendments` — `procurement-purchase-orders:UPDATE`

```json
{
  "version": 7,
  "reason": "Supplier can deliver 800 only before Tet",
  "expectedDate": "2026-11-20",
  "note": "…",
  "lines": [
    { "lineId": "…", "quantityOrdered": 800, "unitPrice": 1250.00, "taxRate": 8, "description": "…" }
  ]
}
```

- `lines` is **every line kept**, as in a draft edit. A live line missing from it is removed. A line without a
  `lineId`, or with the id of a line of another order, or of a line already `CANCELLED` / `WITHDRAWN`, is refused.
- A blank `unitPrice` / `taxRate` / `description` keeps the current value (never the catalog price: the price was
  agreed when the order was approved). `expectedDate` and `note` are required as they should read afterwards.
- `quantityOrdered` is in the stock unit. A line ordered in a packing unit stays in that unit and its quantity
  must remain a whole number of it (`PO_LINE_UNIT_NOT_MULTIPLE`).

In one transaction:

1. `loadForUpdate` (`SELECT … FOR UPDATE` on the order, warehouse scope), then `version` compared
   (`OPTIMISTIC_LOCK`).
2. `PurchaseOrder.requireAmendable()`: type STANDARD; status `APPROVED` or `CONFIRMED`; supplier response not
   `REJECTED`; no pending revision; no line with a received quantity. Then `hasReceiptInProgress` must be false.
3. Validation, every fault reported at once, per line and field:
   - reason required (≤ 500); at least one line kept; something must change (`PO_AMENDMENT_NO_CHANGE`);
   - quantity 1–1,000,000; for a line whose quantity changes, MOQ and pack multiple (BR-08) as in `checkLines`;
   - a line whose quantity **increases** must still be purchasable and listed in the supplier's catalog (BR-01:
     `SKU_NOT_PURCHASABLE`, `SUPPLIER_ITEM_NOT_LISTED`). Lowering, repricing or removing a line of a discontinued
     SKU stays possible, and a decreased line whose SKU left the supplier catalog passes without any terms check (kltn-docs edge case: goods already ordered are still received);
   - unit price > 0, tax rate 0–100, expected date ≥ order date (a past date only warns, BR-06).
4. `recordAmendment`: revision `AMENDMENT` n+1 with the header snapshot of the **proposed** order (same keys as
   `recordRevision`, plus `previousTotalAmount`) and one line revision per kept line with the proposed content
   and the line's current unit; `change_summary` = the reason. The live lines are not touched.
5. `order.requestAmendment(revisionId, revisionNo)` → `pending_revision_id`; event `REVISED`
   (status unchanged), reason, payload = the changed facts before/after (same shape as the draft edit:
   `line3.quantityBefore`, `line3.quantityAfter`, `line5.removed`, `expectedAtBefore`, …).
6. `autoApprovalLimit(order, requester, supplier, newTotal, now)`: present → approved now as in §6 with an
   `AUTO_APPROVED` approval row carrying the limit, and event `AUTO_APPROVED`.

Response: the order (`PurchaseOrderResponse`) with its `pendingAmendment` (§9) or already amended.

## 6. Deciding

**Approve** — `POST /{id}/amendment/approval` (`APPROVE`, existing endpoint; STANDARD now handled):

1. `loadForUpdate`; a pending revision must exist (`PO_AMENDMENT_NOT_PENDING`).
2. The approver is not the requester (`SELF_APPROVAL_NOT_ALLOWED`); their limit, when configured in the order's
   currency on the day, is ≥ the new total (`PO_APPROVAL_LIMIT_EXCEEDED`).
3. Re-checked under the lock: nothing received, no receipt in progress, supplier response not `REJECTED`
   (`PO_AMENDMENT_NOT_ALLOWED`). Line rules are not re-run: they were checked when the revision was written and
   the revision is immutable.
4. `applyAmendment(order, revision)` — one SQL statement per concern, then a refresh of the order and its lines
   (same reason as `SubcontractOrderStoreAdapter.refresh`):
   - kept lines: `ordered_qty`, `unit_qty`, `unit_price`, `tax_rate`, `note`, the line amounts **and**
     `po_revision_id` from the line revision, in the same UPDATE (the line guard accepts nothing else);
   - removed lines (live, not in the revision): `status = 'CANCELLED'`;
   - header: `expected_date`, `note`, subtotal / tax / total recomputed over lines not `CANCELLED` or
     `WITHDRAWN`, `active_revision_id`, `pending_revision_id = NULL`, `revision_no`.
5. `order.applyApprovedAmendment(...)`: when the order is `CONFIRMED` and the supplier response is `PENDING` or
   `CONFIRMED`, it becomes `PENDING` (response time, reference and note cleared; the previous answer is kept in the
   event payload). Status unchanged.
6. Approval row `APPROVED`; event `APPROVED` (from = to = current status) with the new total.
7. If the order had been sent (§8): the amended order goes to the supplier. Approval fails (409, rolled back) when
   the supplier has no delivery contact: the same rule as sending, and a supplier cannot leave ACTIVE while it has
   open orders (BR-11), so the approver fixes the contact first.

**Reject** — `POST /{id}/amendment/rejection` (`APPROVE`, existing): reason required; `pending_revision_id = NULL`;
approval row and event `REJECTED`. The order keeps its active revision. A rejection makes later amendments of this
order ineligible for auto-approval (`hasBeenRejected`, SCRUM-124 rule 3).

**Withdraw** — `POST /{id}/amendment/withdrawal` (`UPDATE`, new): reason required; anyone who may update the
order, the requester included. Approval row and event `WITHDRAWN`.

**Withdrawn by cancelling or closing** — `ProcurementServiceImpl.cancel` and `closeShort` (and force close) drop a
waiting STANDARD amendment in the same transaction (decision `WITHDRAWN`, reason `Order cancelled` / `Order closed`):
a pending pointer on a terminal order shows a decision nobody can make sensibly.

**Withdrawn by receiving** — where `ReceivingPurchaseOrderAdapter` moves a STANDARD order out of `CONFIRMED`
(the first receipt posted), in the same transaction and under the order lock receiving already holds: a pending
amendment is dropped with decision `WITHDRAWN`, actor the person posting the receipt, reason
`Goods received (receipt GR-…)`. A SUBCONTRACT amendment is left alone: its supplement is meant for orders being
received.

Lock order everywhere: the purchase order row first, then anything else. Receiving takes the same row, so the
approval and the first receipt never interleave.

## 7. The aggregate

`PurchaseOrder` gains, with no persistence in it:

- `requireAmendable()` — the D1/D6 conditions it can see (type, status, supplier response, pending pointer,
  received quantities); `InvalidPurchaseOrderTransitionException` → `PO_AMENDMENT_NOT_ALLOWED` /
  `PO_AMENDMENT_ALREADY_PENDING`.
- `requestAmendment(UUID revisionId, long revisionNo)` — sets the pending pointer.
- `applyApprovedAmendment(UUID revisionId, long revisionNo)` — pointers, revision number, the supplier response
  back to `PENDING` (D3). The content is applied by the adapter (§6.4) and read back by the refresh.
- `dropAmendment()` — clears the pending pointer.

Invariants stated twice: the conditions above, and in the database `ck_purchase_orders_revisions_differ`,
`check_po_revision_pointers`, the line guard and the append-only triggers (all existing).

## 8. Telling the supplier

**When**: an approved amendment (STANDARD, or SUBCONTRACT — today it tells nobody) of an order that had been sent,
i.e. `CONFIRMED` / `PARTIALLY_RECEIVED` / `RECEIVED` with a supplier response other than `REJECTED`. An amendment
of an `APPROVED` order sends nothing: the supplier has not seen the order, and confirming it later sends the
current content. For a SUBCONTRACT order the response returns to `PENDING` too, in the SQL of
`SubcontractOrderStoreAdapter.approveAmendment`, and the notice is published by `SubcontractPurchasingImpl` after
both the manual and the automatic approval.

**Contract** `PurchaseOrderSent` gains `Integer amendmentRevisionNo` (null for the order itself and the
cancellation), `String amendmentReason` and `List<Change> changes` (`lineNo`, `sku`, `field`, `before`, `after`),
with the existing compatibility constructors kept so publications already stored in `event_publication` still
deserialize. `lines` carries the order as amended, `CANCELLED` lines left out. `changes` lists the header changes
first (`expectedDate`, `note`; `lineNo` and `sku` null), then the line changes, from the same `RevisionDiff` the
history shows; `field` is a code on the event and on the API channel.

- `operationReference()` = `purchase-order:{id}:revision:{n}` for an amendment — a reference of its own, so
  neither "already sent successfully" nor the supplier API's `Idempotency-Key` swallows it.
- `templateCode()` = `purchase-order.amended`.
- `PurchaseOrderNotificationListener` treats an amendment like a cancellation: not skipped for
  `suppressed` or for another delivery generation.
- `NotificationSender`: e-mail keys `po.mail.amend.subject` / `po.mail.amend.body` / `po.mail.change` /
  `po.mail.change.line` / `po.mail.change.header` and the field labels `po.mail.field.*` (vi and default bundles):
  the full order, the revision number, the reason, the before/after table with the field codes put into words. API
  channel: `type = "AMENDMENT"` with `revisionNo` and `changes`, codes as they are.
- `NotificationService.purchaseOrderAmendmentStatuses(ids)` → `PurchaseOrderResponse.amendmentDeliveryStatus`
  (latest attempt of the highest revision attempted, with that revision: `DELIVERED` / `RETRYING` / `FAILED`; the
  controller shows it only when that revision is the order's active one, so it is absent until the active
  amendment's notice was first attempted and there is no `QUEUED`).
- `NotificationService.supersedeSupplierDelivery(id, channel, recipient)`, called right before the notice is
  published: an own delivery of the order that has not succeeded and is not suppressed or terminally failed gets
  its generation advanced (the listener drops the older events) and the new generation closed by one terminal
  attempt `SUPERSEDED_BY_AMENDMENT`, so the pre-amendment order cannot reach the supplier after the notice and a
  recovery, which needs a terminal current generation, still resends the order as it stands.
- No recovery for a failed amendment notice in this round (§12). The supplier response is `PENDING` again, which
  is the cue to reach the supplier.

**Fix — recovery after an amendment.** `recoverDelivery` resends the lines of the last snapshot. When an amendment
has been approved since the order was sent (the active revision differs from the revision named by the
`CONFIRMED` event), it sends the active revision's content instead of the stale quantities. `confirm` also leaves
`CANCELLED` lines out of what it sends.

## 9. Reading the history

New `PurchaseOrderHistoryController`, `procurement-purchase-orders:READ`, warehouse scope as `findOne`
(another warehouse's order answers what `findOne` answers: 403 `OUT_OF_DATA_SCOPE`):

- `GET /api/v1/purchase-orders/{id}/revisions` — newest first: `revisionNo`, `purpose` (`SUBMISSION` for revision
  0 or when a `SUBMITTED` event names it, else `AMENDMENT`), `state` (`ACTIVE`, `PENDING`, `REJECTED`, `WITHDRAWN`,
  `SUPERSEDED`; `PENDING` only while the order can still decide it - a submission of a `PENDING_APPROVAL` order, an
  amendment of an `APPROVED` / `CONFIRMED` / `PARTIALLY_RECEIVED` / `RECEIVED` one), `changedBy` (id and name),
  `changedAt`, `reason`, `totalAmount`, decision (`decidedBy`, `decidedAt`, `decision`, `reason`, auto-approval
  limit when there was one).
- `GET /api/v1/purchase-orders/{id}/revisions/{revisionNo}` — the header snapshot and its lines (line no, SKU,
  description, unit, quantity, price, tax, amounts), and `changes` against the closest earlier **approved**
  revision (the immediately earlier one when none was approved): per line `CHANGED` with fields before/after,
  `REMOVED`, `ADDED` (only possible between submissions), and header fields.
- `GET /api/v1/purchase-orders/{id}/timeline?page&size` — `purchase_order_events` newest first: action,
  from/to status, actor (id and name), reason, revision number, `payload` as stored, time. Size ≤ 100.

`PurchaseOrderResponse` gains `pendingAmendment` (`revisionNo`, `requestedBy`, `requestedAt`, `reason`,
`newTotalAmount`; only on an order in those four statuses - a `CANCELLED` or `CLOSED` order may keep a pointer
nobody can decide any more) and `amendmentDeliveryStatus`. Both are assembled in the controller next to the delivery
statuses, as the cancellation status is; `PurchaseOrderSummary` (in `procurement :: api`) does not change. Reads are plain JPQL/native projections in
`internal/repository`, paged by the database; the actor names are fetched once per page through `identity :: api`
(no N+1).

## 10. Errors

| Code | HTTP | When |
|---|---|---|
| `PO_AMENDMENT_NOT_ALLOWED` (new) | 409 | Not STANDARD, not `APPROVED`/`CONFIRMED`, supplier `REJECTED`, something received, receipt in progress |
| `PO_AMENDMENT_ALREADY_PENDING` (new) | 409 | Another amendment waits |
| `PO_AMENDMENT_NOT_PENDING` (new) | 409 | Approve / reject / withdraw with none waiting (also adopted by the SUBCONTRACT path, which says `CONFLICT` today) |
| `PO_AMENDMENT_NO_CHANGE` (new) | 400 | The request changes nothing |
| `VALIDATION_FAILED` | 400 | Reason missing, no line kept, unknown line, field ranges |
| `PO_LINE_BELOW_MOQ`, `PO_LINE_NOT_PACK_MULTIPLE`, `PO_LINE_UNIT_NOT_MULTIPLE`, `SUPPLIER_TERMS_VIOLATED`, `SKU_NOT_PURCHASABLE`, `SUPPLIER_ITEM_NOT_LISTED` | 409 | As for a draft edit, per line |
| `SELF_APPROVAL_NOT_ALLOWED`, `PO_APPROVAL_LIMIT_EXCEEDED` | 409 | Decision rules |
| `OPTIMISTIC_LOCK` | 409 | Stale `version` |
| `PURCHASE_ORDER_NOT_FOUND` | 404 | Unknown or outside the caller's warehouses |

New codes get `error.*` entries in both message bundles. All three write endpoints are `@Auditable` and go
through the idempotency filter like the other PO transitions.

## 11. Tests

- **Domain** (`PurchaseOrderAmendmentTest`): each refusal of `requireAmendable`; request / apply / drop move the
  pointers; the supplier response returns to `PENDING` from `PENDING` and `CONFIRMED`, never from `REJECTED`.
- **Line rules** (unit): no change, removal of every line, quantity increase of a discontinued SKU refused and
  decrease allowed, MOQ / pack multiple only on changed quantities, packing-unit whole numbers.
- **Integration** (Testcontainers, `support/DemoData` / `ReferenceRows`):
  - request → auto-approved within the limit; request → pending → approved by someone else; self-approval and
    limit refusals; reject; withdraw; second request while one waits;
  - the line guard accepts the applied amendment; totals and the CHECK `ck_purchase_orders_amounts` hold; a
    removed line is `CANCELLED`, takes no receipt, and does not hold the order out of `RECEIVED`;
  - approval refused with a receipt in progress; the first posted receipt withdraws the pending amendment; a
    SUBCONTRACT amendment survives receiving;
  - an approved amendment of a confirmed order publishes `PurchaseOrderSent` with reference
    `…:revision:n` and resets the supplier response; of an approved order publishes nothing;
  - recovery after an amendment sends the amended quantities; a SUBCONTRACT supplement of a confirmed order
    notifies the subcontractor;
  - history: revisions with purpose and state, detail diff, timeline paging, warehouse scope.
- **Notification**: the listener delivers an amendment notice of a suppressed order and keeps it apart from the
  order's own delivery generations.
- **Fitness**: `tools/verify.py`, `ModularityTest`, `ArchitectureTest` while working; one full `mvn verify` at the
  end, with no source edits while it runs.

## 12. Out of scope

- Adding a line, changing the unit, the warehouse or the supplier of an approved order (D2).
- Amending an order the supplier rejected (D6).
- Recovering a failed amendment notice (a later ticket if the team needs it).
- FE screens (Hưng): the amendment form, the pending-amendment banner with approve / reject / withdraw, the
  revision and timeline tabs.

## 13. Docs to update with the code

- **kltn-docs `warehouse/02-purchase-order`** (the `slides/` folder is not touched): a "Sửa PO đã duyệt" step
  replacing §4.5 step 19; §3 Output; BR-02; the BR-13 exception; the edge case "đổi số lượng sau Confirmed"
  (the warehouse still cannot change); §5.2 `Confirmed → Pending` on an approved amendment; §5.3 the new meaning
  of a `Cancelled` line.
- **Repo**: `docs/business-design/03-state-machines.md` §2 amendment paragraph (no `SENT`, no
  `PurchaseOrderAmended`); `05-business-rules.md` BR-PO-004 rewritten with its real enforcement points;
  `04c-all-flows.md` F-PO step 07 and its ⚠ note.
- **Jira**: SCRUM-117's description replaced by this scope (after the owner agrees); SCRUM-131 = request /
  decide / apply, SCRUM-132 = history and timeline, SCRUM-133 = supplier notice and the two subcontract fixes.

## 14. People to tell

- **Võ** — the notification module changes (§8).
- **Hưng** — the FE work (§12).
- **Tú** — confirmation that no migration is needed (D8).
