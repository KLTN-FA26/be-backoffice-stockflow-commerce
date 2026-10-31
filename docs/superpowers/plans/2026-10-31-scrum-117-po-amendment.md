# SCRUM-117 PO Amendment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An approved or confirmed STANDARD purchase order can be amended through a revision approved again; the
supplier is sent the amended order when it had been sent; revisions and the timeline become readable.

**Architecture:** Rules in the `PurchaseOrder` aggregate (pure), orchestration in a new
`PurchaseOrderAmendments` service, revision writes and the line update in `PurchaseOrderRepositoryAdapter` SQL
(the line guard trigger accepts a content change only together with the move to the amendment revision). The
supplier notice reuses `PurchaseOrderSent` with a reference of its own. No migration.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Modulith 1.4, JPA/Hibernate + native SQL, PostgreSQL 16,
JUnit 5, Testcontainers, MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-31-scrum-117-po-amendment-design.md` (read it first; section numbers
below are the spec's).

## Global Constraints

- No file under `src/main/resources/db/migration` is added or changed (spec D8; schema is Tú's).
- All identifiers, code and javadoc in English; Vietnamese only in `messages_vi.properties` and kltn-docs.
- Javadoc explains why. Change the javadoc that justified old behaviour when behaviour changes.
- Controller handlers and proxied methods are `public`; `@RequiresPermission` on methods only.
- Lock order: the purchase order row (`findByIdForUpdate`) first, always.
- Primary keys `Identifiers.newId()`; enums `EnumType.STRING`.
- Never `git commit` / `git push` without the repository owner's go-ahead. The commit steps below run only once the
  owner has allowed commits on `feature/SCRUM-117-po-amendment`.
- Run `python3 tools/verify.py` after each task; `mvn test -Dtest=ModularityTest,ArchitectureTest` after any task
  touching packages or module dependencies; one full `mvn verify` at the end (Task 9), with no source edits while it
  runs.

## Review Focus

1. **The first receipt posted while an amendment waits, then the approval.** Expect the approval to answer 409
   `PO_AMENDMENT_NOT_PENDING` (the receipt already withdrew it), never to apply over received goods. → Task 5.
2. **A line ordered in a packing unit amended to a quantity that is not a whole number of it.** Expect
   `PO_LINE_UNIT_NOT_MULTIPLE` naming the line, not the generic 409 of `tg_purchase_order_lines_unit`. → Task 2.
3. **The same amendment request sent twice** (double click, retry). With one `Idempotency-Key`: one revision, the
   same response. Without: the second answers `PO_AMENDMENT_ALREADY_PENDING`. → Task 4.
4. **A `PurchaseOrderSent` publication stored before this change** (no amendment fields) still deserializes and is
   delivered as before. → Task 6.
5. **History of an order in a warehouse the caller is not assigned to.** Expect 403 `OUT_OF_DATA_SCOPE`, the same as `findOne`. → Task 8.

---

### Task 1: Error codes and the aggregate's amendment rules

**Files:**
- Modify: `src/main/java/com/stockflow/common/error/ErrorCode.java` (next to `PURCHASE_ORDER_NOT_EDITABLE`)
- Modify: `src/main/resources/i18n/messages.properties`, `src/main/resources/i18n/messages_vi.properties`
- Modify: `src/main/java/com/stockflow/procurement/internal/domain/PurchaseOrder.java`
- Test: `src/test/java/com/stockflow/procurement/internal/domain/PurchaseOrderAmendmentTest.java`

**Interfaces:**
- Produces `ErrorCode`: `PO_AMENDMENT_NOT_ALLOWED` (409, "This purchase order cannot be amended now"),
  `PO_AMENDMENT_ALREADY_PENDING` (409, "An amendment of this purchase order already waits for a decision"),
  `PO_AMENDMENT_NOT_PENDING` (409, "No amendment of this purchase order waits for a decision"),
  `PO_AMENDMENT_NO_CHANGE` (400, "The amendment changes nothing"). `error.*` keys in both bundles; vi:
  "Không sửa được PO này lúc này", "PO này đã có một bản sửa đang chờ duyệt", "PO này không có bản sửa nào đang
  chờ duyệt", "Bản sửa không thay đổi gì".
- Produces, nested in `PurchaseOrder`:
  - `record AmendmentLine(UUID lineId, Integer quantityOrdered, BigDecimal unitPrice, BigDecimal taxRate, String description)`
    — null price / tax / description = keep the line's.
  - `record AmendmentRequest(LocalDate expectedAt, String note, String reason, List<AmendmentLine> lines)`
  - `record ProposedLine(PoLine before, PoLine after)` with `boolean quantityChanged()`, `boolean quantityIncreased()`,
    `boolean changed()`. `after` is built with the `PoLine` constructor (same id, line no, item, SKU, uom; packing
    unit with the new count) so amounts come from `PoLine.subtotal()/tax()/total()`.
  - `record ProposedAmendment(LocalDate expectedAt, String note, String reason, List<ProposedLine> kept, List<PoLine> removed, Money subtotal, Money taxTotal, Money totalAmount, Map<String, String> changes)`
  - `ProposedAmendment proposeAmendment(AmendmentRequest request)`
  - `void requestAmendment(UUID revisionId)`
  - `void requireAmendmentDecidable()`
  - `void applyApprovedAmendment(UUID revisionId, long revisionNo)`
  - `void dropAmendment()`

- [ ] **Step 1: Write the failing tests** in `PurchaseOrderAmendmentTest` (build orders through `PurchaseOrder(State)`
  as `PurchaseOrderTest` does):
  - `proposeRefusesUnlessStandardApprovedOrConfirmed` — DRAFT, PENDING_APPROVAL, PARTIALLY_RECEIVED, SUBCONTRACT →
    `BusinessException` code `PO_AMENDMENT_NOT_ALLOWED`.
  - `proposeRefusesWhenSupplierRejected` → `PO_AMENDMENT_NOT_ALLOWED`.
  - `proposeRefusesWhenSomethingWasReceived` (a line with `quantityReceived` 1) → `PO_AMENDMENT_NOT_ALLOWED`.
  - `proposeRefusesWhileAnotherWaits` (`pendingRevisionId` set) → `PO_AMENDMENT_ALREADY_PENDING`.
  - `proposeRefusesUnknownCancelledOrDuplicatedLine` → `VALIDATION_FAILED` with a `FieldError` on `lines[i].lineId`.
  - `proposeRefusesNoLineKeptBlankReasonAndFieldRanges` — empty `lines`, blank reason, reason of 501 chars,
    quantity 0 and 1_000_001, price 0, tax 101, expected date before the order date → one `VALIDATION_FAILED` listing
    every field.
  - `proposeRefusesNothingChanged` → `PO_AMENDMENT_NO_CHANGE`.
  - `proposeKeepsBlankPriceTaxAndDescription` — nulls give `after` the line's current values.
  - `proposeRemovesLinesLeftOut` — a 2-line order amended with one line: `removed` holds the other; totals over the
    kept line only.
  - `proposeRecordsChanges` — quantity 10→8 on line 1, line 2 removed, expected date moved: `changes` equals
    `{line1.quantityBefore=10, line1.quantityAfter=8, line2.removed=true, expectedAtBefore=…, expectedAtAfter=…,
    totalAmountBefore=…, totalAmountAfter=…}`.
  - `requestThenApplyMovesPointersAndResetsSupplierResponse` — CONFIRMED with supplier CONFIRMED: after
    `requestAmendment(r)` → `pendingRevisionId()==r`; after `applyApprovedAmendment(r, 2)` → `activeRevisionId()==r`,
    `pendingRevisionId()==null`, `revisionNo()==2`, `supplierConfirmationStatus()==PENDING`,
    `supplierRespondedAt()==null`, status still CONFIRMED.
  - `applyOnApprovedOrderLeavesSupplierResponseNotSent` — APPROVED: stays `NOT_SENT`.
  - `decidableRefusesWithoutPendingOrAfterReceipt` → `PO_AMENDMENT_NOT_PENDING` / `PO_AMENDMENT_NOT_ALLOWED`.
  - `dropClearsPendingOnly`.

- [ ] **Step 2: Run** `mvn test -Dtest=PurchaseOrderAmendmentTest` — expect compilation failure (types missing).

- [ ] **Step 3: Implement** the records and methods in `PurchaseOrder`. Structural faults are collected into one
  `BusinessException(VALIDATION_FAILED, …, List<FieldError>)` like `ProcurementServiceImpl.requirePurchasable`;
  reason ≤ 500 via the existing `requireReason`; a line already `CANCELLED` or `WITHDRAWN` is unknown. A packing-unit
  line's new count is `PackingUnit.wholeUnits(poNumber, quantity, factor)` (Task 2's test pins the error). The
  change keys follow the draft-edit timeline (`key + "Before"` / `key + "After"`). `applyApprovedAmendment` does not
  touch lines: the adapter writes them and the service reloads the order (Task 3).

- [ ] **Step 4: Run** `mvn test -Dtest=PurchaseOrderAmendmentTest,PurchaseOrderTest` — expect PASS.

- [ ] **Step 5: Commit** `feat(procurement): SCRUM-131 amendment rules on the purchase order aggregate`

### Task 2: Catalog and purchasability checks of the changed lines

**Files:**
- Create: `src/main/java/com/stockflow/procurement/internal/service/AmendmentLineChecks.java` (package-private
  `@Component`)
- Test: `src/test/java/com/stockflow/procurement/internal/service/AmendmentLineChecksTest.java` (Mockito)

**Interfaces:**
- Consumes: `PurchaseOrder.ProposedAmendment` (Task 1); `SupplierItemRepository.listedForShare(UUID, Collection<UUID>)`;
  `SupplierItem.orderQuantityViolations(int)`; `ProductService.purchasability(Collection<String>)`.
- Produces: `void require(PurchaseOrder order, PurchaseOrder.ProposedAmendment proposal)` — throws
  `SUPPLIER_TERMS_VIOLATED` (MOQ / pack faults, per line, as `checkLines` reports them), `SUPPLIER_ITEM_NOT_LISTED`,
  `SKU_NOT_PURCHASABLE`, field paths `lines[i].quantityOrdered` / `lines[i].sku` where `i` is the index in the
  request.

- [ ] **Step 1: Write the failing tests:**
  - `termsCheckedOnlyForChangedQuantities` — a repriced line whose SKU is no longer listed passes; the same line with
    a new quantity → `SUPPLIER_ITEM_NOT_LISTED`.
  - `belowMoqAndNotPackMultipleReportedPerLine` — two changed lines, one under MOQ, one not a pack multiple → one
    `SUPPLIER_TERMS_VIOLATED` with both faults.
  - `increaseOfDiscontinuedSkuRefusedDecreaseAllowed` — `purchasability` says `PRODUCT_DISCONTINUED`: increase →
    `SKU_NOT_PURCHASABLE`, decrease → passes, and `purchasability` is called only with the increased SKUs.
  - `packingUnitQuantityMustBeWhole` — Review Focus 2: a line in a 12-unit carton amended to 30 →
    `PO_LINE_UNIT_NOT_MULTIPLE` (thrown by `proposeAmendment`; asserted here end to end with a real
    `PurchaseOrder`).
  - `catalogReadOnceForAllChangedLines` — `listedForShare` invoked once.

- [ ] **Step 2: Run** `mvn test -Dtest=AmendmentLineChecksTest` — expect FAIL.

- [ ] **Step 3: Implement** `AmendmentLineChecks.require`. Javadoc says why `checkLines` is not reused (spec §4).

- [ ] **Step 4: Run** `mvn test -Dtest=AmendmentLineChecksTest` — expect PASS.

- [ ] **Step 5: Commit** `feat(procurement): SCRUM-131 catalog checks of an amendment's changed lines`

### Task 3: Writing, applying and dropping an amendment revision

**Files:**
- Modify: `src/main/java/com/stockflow/procurement/internal/domain/PurchaseOrderRepository.java`
- Modify: `src/main/java/com/stockflow/procurement/internal/repository/PurchaseOrderRepositoryAdapter.java`
- Test: `src/test/java/com/stockflow/procurement/internal/repository/PurchaseOrderAmendmentRepositoryIntegrationTest.java`
  (`@IntegrationTest`, rows from `ReferenceRows` / `SupplierItemFixtures`, as
  `SupplierItemRepositoryAdapterIntegrationTest`)

**Interfaces:**
- Consumes: `PurchaseOrder.ProposedAmendment` (Task 1).
- Produces on `PurchaseOrderRepository`:
  - `UUID recordAmendment(PurchaseOrder order, long revisionNo, UUID actorId, PurchaseOrder.ProposedAmendment proposal)`
    — revision `AMENDMENT` with `snapshot_header` keys of `recordRevision` filled from the proposal plus
    `previousTotalAmount`; one line revision per kept line (`after` values, the line's `uom_conversion_id`, the new
    `unit_qty`); `change_summary` = reason. Live lines untouched.
  - `record PendingAmendment(UUID revisionId, long revisionNo, UUID requestedBy, Instant requestedAt, String reason, BigDecimal totalAmount)`
    nested in `PurchaseOrderRepository`, and `Optional<PendingAmendment> pendingAmendment(PurchaseOrderId id)`.
  - `void applyAmendment(PurchaseOrderId id, UUID revisionId, long revisionNo)` — kept lines from their line revision
    in one UPDATE including `po_revision_id`; live lines absent from the revision (not `CANCELLED`/`WITHDRAWN`) →
    `CANCELLED`; header `expected_date`, `note`, subtotal / tax / total over lines not `CANCELLED`/`WITHDRAWN`,
    `active_revision_id`, `pending_revision_id = NULL`, `revision_no`; then flush and refresh the order and its lines
    (`SubcontractOrderStoreAdapter.refresh` pattern). Does not touch the supplier response (the aggregate does).
  - `void dropAmendment(PurchaseOrderId id)` — `pending_revision_id = NULL`, then refresh.
  - `record RevisionLine(UUID lineId, int lineNo, String sku, int quantityOrdered, BigDecimal unitPrice, BigDecimal taxRate, String description)`
    nested in `PurchaseOrderRepository`, and `List<RevisionLine> revisionLines(UUID revisionId)` ordered by line no
    (used by Tasks 6 and 8).

- [ ] **Step 1: Write the failing tests:**
  - `recordedAmendmentFreezesProposedLinesNotLiveOnes` — after `recordAmendment`, `purchase_order_line_revisions`
    of the new revision hold the proposed quantity and the live `purchase_order_lines` still the old one.
  - `appliedAmendmentPassesTheLineGuard` — CONFIRMED order: `applyAmendment` succeeds (no `check_violation` from
    `guard_po_line_content_change`); lines carry the new values and `po_revision_id`; the removed line is
    `CANCELLED`; `total_amount` = sum of the kept lines (CHECK `ck_purchase_orders_amounts` holds).
  - `appliedAmendmentKeepsPackingUnit` — a carton line: `uom_conversion_id` unchanged, `unit_qty` the new count.
  - `pendingAmendmentReadsBackTheRequest`, `droppedAmendmentClearsThePointer` and
    `revisionLinesReadBackInLineOrder`.

- [ ] **Step 2: Run** `mvn test -Dtest=PurchaseOrderAmendmentRepositoryIntegrationTest` — expect FAIL.

- [ ] **Step 3: Implement** the four methods with native SQL in the adapter, mirroring
  `SubcontractOrderStoreAdapter.requestAmendment` / `approveAmendment` but over N lines; `entityManager.flush()` before
  each statement.

- [ ] **Step 4: Run** the test — expect PASS.

- [ ] **Step 5: Commit** `feat(procurement): SCRUM-131 amendment revisions written and applied in SQL`

### Task 4: The amendment service and its endpoints

**Files:**
- Create: `src/main/java/com/stockflow/procurement/internal/service/PurchaseOrderAmendments.java` (package-private
  class, public `@Transactional` methods, `@Auditable` like the other transitions)
- Create: `src/main/java/com/stockflow/procurement/internal/controller/dto/AmendPurchaseOrderRequest.java`,
  `AmendPurchaseOrderLineRequest.java`
- Modify: `src/main/java/com/stockflow/procurement/internal/controller/PurchaseOrderController.java` (new
  `POST /{purchaseOrderId}/amendments`, `POST /{purchaseOrderId}/amendment/withdrawal`; approval and rejection
  dispatch on `type`)
- Modify: `src/main/java/com/stockflow/procurement/internal/service/SubcontractPurchasingImpl.java` (`CONFLICT` "no
  amendment waiting" → `PO_AMENDMENT_NOT_PENDING`; update `SubcontractPurchasingIntegrationTest` expectations)
- Test: `src/test/java/com/stockflow/procurement/PurchaseOrderAmendmentIntegrationTest.java` (`@SecuredIntegrationTest`,
  helpers as `PurchaseOrderApprovalLimitIntegrationTest`)

**Interfaces:**
- Consumes: Tasks 1–3; `ApprovalLimits.autoApprovalLimit(...)`, `ApprovalLimits.limitOf(...)`;
  `PurchaseOrderRepository.hasReceiptInProgress`, `recordApproval`, `recordAutoApproval`, `recordEvent`.
- Produces:
  - `PurchaseOrderSummary request(UUID purchaseOrderId, long version, PurchaseOrder.AmendmentRequest request, UUID actorId)`
  - `PurchaseOrderSummary approve(UUID purchaseOrderId, UUID approverId)`
  - `PurchaseOrderSummary reject(UUID purchaseOrderId, UUID approverId, String reason)`
  - `PurchaseOrderSummary withdraw(UUID purchaseOrderId, UUID actorId, String reason)`
  - `AmendPurchaseOrderRequest(@NotNull Long version, @NotBlank @Size(max = 500) String reason, LocalDate expectedDate, @Size(max = 1000) String note, @NotEmpty List<@Valid AmendPurchaseOrderLineRequest> lines)`;
    `AmendPurchaseOrderLineRequest(@NotNull UUID lineId, @NotNull Integer quantityOrdered, BigDecimal unitPrice, BigDecimal taxRate, String description)`.
  - A hook for Task 6: after a manual or automatic approval, the service calls
    `void afterApproved(PurchaseOrder order, UUID previousActiveRevisionId, PurchaseOrderRepository.PendingAmendment amendment)`
    — a private method left empty in this task.

- [ ] **Step 1: Write the failing integration tests** (orders brought to APPROVED / CONFIRMED through the API):
  - `withinLimitIsAutoApproved` — requester limit 1_000_000, new total 600_000: 200, `active_revision_id` = new
    revision, approval row `AUTO_APPROVED` with the limit, events `REVISED` then `AUTO_APPROVED`, lines updated.
  - `aboveLimitWaitsForSomeoneElse` — pending; requester approving → `SELF_APPROVAL_NOT_ALLOWED`; approver with
    limit below the new total → `PO_APPROVAL_LIMIT_EXCEEDED`; approver with no limit → 200 and applied.
  - `confirmedOrderSupplierResponseBackToPending` — supplier response CONFIRMED before, PENDING after; status
    CONFIRMED.
  - `rejectKeepsActiveRevisionAndBlocksLaterAutoApproval` — reject with reason; a second request within the limit
    stays pending (`hasBeenRejected`).
  - `withdrawByRequester` — event `WITHDRAWN`, pointer cleared.
  - `refusedWhileReceiptInProgress` — a DRAFT goods receipt exists: request → 409 `PO_AMENDMENT_NOT_ALLOWED`;
    request made before the receipt, approve after → 409 `PO_AMENDMENT_NOT_ALLOWED`.
  - `staleVersion` → `OPTIMISTIC_LOCK`; `nothingChanged` → 400 `PO_AMENDMENT_NO_CHANGE`.
  - `sameRequestTwice` — Review Focus 3: same `Idempotency-Key` → one revision row, identical body; no key → second
    → `PO_AMENDMENT_ALREADY_PENDING`.
  - `removedLineTakesNoReceiptAndOrderStillReceives` — the other line received in full → order `RECEIVED`.
  - `buyerWithoutApproveCannotDecide` → 403 on approval / rejection; `PROCUREMENT_STAFF` may request and withdraw.

- [ ] **Step 2: Run** `mvn test -Dtest=PurchaseOrderAmendmentIntegrationTest` — expect FAIL.

- [ ] **Step 3: Implement** `PurchaseOrderAmendments`: `loadForUpdate` (copy of the `ProcurementServiceImpl` helper
  with `WarehouseScope.requireWarehouse`) → version → `proposeAmendment` → `hasReceiptInProgress` →
  `AmendmentLineChecks.require` → `recordAmendment(nextRevisionNo)` → `requestAmendment` → save → event `REVISED`
  (from = to = status, reason, `proposal.changes()`) → auto-approval via `autoApprovalLimit(order.id(), actorId,
  supplierId, proposal.totalAmount(), now)`. `approve`: `requireAmendmentDecidable` + `hasReceiptInProgress` → four
  eyes on `PendingAmendment.requestedBy` → `limitOf` → `applyAmendment` → reload with `findByIdForUpdate` →
  `applyApprovedAmendment` → save → approval row + event `APPROVED`. Controller dispatch reads the order's `type`
  through `procurementService.findById`.

- [ ] **Step 4: Run** `mvn test -Dtest=PurchaseOrderAmendmentIntegrationTest,SubcontractPurchasingIntegrationTest,PurchaseOrderApprovalLimitIntegrationTest` — expect PASS.

- [ ] **Step 5: Run** `mvn test -Dtest=ModularityTest,ArchitectureTest` and `python3 tools/verify.py` — expect PASS.

- [ ] **Step 6: Commit** `feat(procurement): SCRUM-131 amend an approved purchase order, decided like the original`

### Task 5: The first receipt withdraws a waiting amendment

**Files:**
- Modify: `src/main/java/com/stockflow/procurement/internal/repository/ReceivingPurchaseOrderAdapter.java`
  (`recordProgress`)
- Test: add to `PurchaseOrderAmendmentIntegrationTest`

**Interfaces:**
- Consumes: `PurchaseOrderRepository.dropAmendment` (Task 3), `recordApproval`, `PurchaseOrderEventLog.record`.

- [ ] **Step 1: Write the failing tests:**
  - `firstPostedReceiptWithdrawsPendingAmendment` — Review Focus 1: amendment pending, receipt posted → approval
    row `WITHDRAWN` by the receiving user, event `WITHDRAWN` reason `Goods received (receipt GR-…)`, then approve →
    409 `PO_AMENDMENT_NOT_PENDING`.
  - `subcontractAmendmentSurvivesReceiving` — a SUBCONTRACT supplement pending through a posted receipt is still
    pending.

- [ ] **Step 2: Run** them — expect FAIL.

- [ ] **Step 3: Implement** in `recordProgress`: when the status changes from `CONFIRMED`, the order is STANDARD and
  `pending_revision_id` is set, drop it as above in the same transaction. Javadoc: why receiving withdraws it (D5) and
  why SUBCONTRACT is exempt.

- [ ] **Step 4: Run** `mvn test -Dtest=PurchaseOrderAmendmentIntegrationTest,GoodsReceiptOverToleranceApprovalIntegrationTest` — expect PASS.

- [ ] **Step 5: Commit** `feat(procurement): SCRUM-131 receiving withdraws an amendment still waiting`

### Task 6: Telling the supplier of an approved amendment

**Files:**
- Modify: `src/main/java/com/stockflow/contracts/PurchaseOrderSent.java`
- Modify: `src/main/java/com/stockflow/notification/internal/service/PurchaseOrderNotificationListener.java`,
  `NotificationSender.java` (and its `SupplierPurchaseOrderMessage`), `NotificationServiceImpl.java`
- Modify: `src/main/java/com/stockflow/notification/api/NotificationService.java`
- Modify: `src/main/resources/i18n/messages.properties`, `messages_vi.properties`
- Create: `src/main/java/com/stockflow/procurement/internal/service/SupplierAmendmentNotice.java` (package-private
  `@Component`)
- Create: `src/main/java/com/stockflow/procurement/internal/domain/RevisionDiff.java` (pure) and its test
  `src/test/java/com/stockflow/procurement/internal/domain/RevisionDiffTest.java`
- Modify: `PurchaseOrderAmendments.afterApproved` (Task 4), `SubcontractPurchasingImpl` (both approval paths),
  `SubcontractOrderStoreAdapter.approveAmendment` (supplier response → `PENDING` when `PENDING`/`CONFIRMED`)
- Test: `src/test/java/com/stockflow/notification/internal/service/PurchaseOrderAmendmentNoticeTest.java` (unit,
  listener + sender); add to `PurchaseOrderAmendmentIntegrationTest` and `SubcontractPurchasingIntegrationTest`

**Interfaces:**
- Produces on `PurchaseOrderSent`: components `Integer amendmentRevisionNo, String amendmentReason, List<Change> changes`
  appended after `cancellationReason`; `record Change(int lineNo, String sku, String field, String before, String after)`;
  `boolean amendment()` = `amendmentRevisionNo != null`; `operationReference()` → `purchase-order:{id}:revision:{n}`
  for an amendment; `templateCode()` → `purchase-order.amended`. Every existing constructor kept, passing nulls.
- Produces `Map<UUID, String> NotificationService.purchaseOrderAmendmentStatuses(Collection<UUID> ids)` — latest
  amendment notice per order: `QUEUED` / `DELIVERED` / `RETRYING` / `FAILED`, absent when none.
- Produces `RevisionDiff`: `record LineChange(int lineNo, String sku, String field, String before, String after)`
  (`field` ∈ `quantity`, `unitPrice`, `taxRate`, `description`, `removed`, `added`; `before`/`after` null where
  meaningless) and `static List<LineChange> between(List<PurchaseOrderRepository.RevisionLine> base, List<PurchaseOrderRepository.RevisionLine> target)`,
  ordered by line no then field. Reused by Task 8.
- Produces `void SupplierAmendmentNotice.send(PurchaseOrder order, UUID previousActiveRevisionId, PurchaseOrderRepository.PendingAmendment amendment)`
  — changes from `RevisionDiff.between(revisionLines(previous), revisionLines(amendment.revisionId()))`; builds
  lines from the order's live lines not `CANCELLED`/`WITHDRAWN`, buyer from the latest delivery snapshot
  (else `PoCommunicationProfile.requireBuyer`), channel and recipient as `ProcurementServiceImpl.publishDelivery`;
  publishes only when the order had been sent (`confirmed_at` set) and the supplier response is not `REJECTED`.
- Mail keys: `po.mail.amend.subject` = `Đơn đặt hàng %s đã được sửa đổi (bản %d)`,
  `po.mail.amend.body` (PO number, buyer block, revision, reason, change table, expected date, payment term, lines,
  total), `po.mail.change` = `Dòng %d %s: %s %s → %s`; English equivalents in `messages.properties`.

- [ ] **Step 1: Write the failing tests:**
  - `RevisionDiffTest`: quantity 10→8 → one `quantity` change; a line only in `base` → `removed`; only in `target`
    → `added`; identical lines → empty; prices compared with `compareTo` (1250.0 equals 1250.00).
  - `amendmentNoticeDeliveredForSuppressedOrder` — control `suppressed = true`, another generation: listener still
    sends and logs `SENT` under `purchase-order:{id}:revision:2`.
  - `apiAmendmentCarriesItsOwnIdempotencyKey` — header `Idempotency-Key` = `purchase-order:{id}:revision:2`, body
    `type = "AMENDMENT"`.
  - `oldPublicationStillDeserializes` — Review Focus 4: the JSON of a `PurchaseOrderSent` without the three new
    fields (taken from a current serialization with them removed) reads back with `amendment() == false` and is sent
    as `purchase-order.sent`.
  - Integration: `approvedAmendmentOfConfirmedOrderIsSentToSupplier` — a `PurchaseOrderSent` with
    `amendmentRevisionNo` published (`RecordedEvents`), lines without the removed one;
    `amendmentOfApprovedOrderSendsNothing`; `subcontractSupplementOfConfirmedOrderIsSentAndResetsResponse`.

- [ ] **Step 2: Run** `mvn test -Dtest=RevisionDiffTest,PurchaseOrderAmendmentNoticeTest` — expect FAIL.

- [ ] **Step 3: Implement** the contract change, the listener condition
  (`!event.cancellation() && !event.amendment() && (suppressed || generation mismatch)`), the sender branch, the
  status query (references `LIKE 'purchase-order:' || id || ':revision:%'`, latest by `created_at`),
  `SupplierAmendmentNotice`, and the calls after approval. Javadoc of `PurchaseOrderSent` and of the listener updated
  (they say "cancellation" is the only exception today).

- [ ] **Step 4: Run** `mvn test -Dtest=RevisionDiffTest,PurchaseOrderAmendmentNoticeTest,PurchaseOrderAmendmentIntegrationTest,SubcontractPurchasingIntegrationTest,SupplierProcurementIntegrationTest` — expect PASS.

- [ ] **Step 5: Run** `mvn test -Dtest=ModularityTest,ArchitectureTest` — expect PASS.

- [ ] **Step 6: Commit** `feat(procurement,notification): SCRUM-133 send the amended order to a supplier that had it`

### Task 7: Recovery and confirmation send the order as it stands

**Files:**
- Modify: `src/main/java/com/stockflow/procurement/internal/service/ProcurementServiceImpl.java` (`publishDelivery`
  around the `previous.map(PurchaseOrderSent::lines)` lines)
- Test: add to `PurchaseOrderAmendmentIntegrationTest`

- [ ] **Step 1: Write the failing tests:**
  - `recoveryAfterAmendmentSendsAmendedQuantities` — confirm, make the delivery terminally fail (as
    `SupplierProcurementIntegrationTest` does), amend 10→8 and approve, recover: the published event's line says 8.
  - `recoveryWithoutAmendmentResendsTheSnapshot` — unchanged behaviour.
  - `confirmLeavesCancelledLinesOut` — amend an APPROVED order removing a line, then confirm: the event has one line.

- [ ] **Step 2: Run** them — expect FAIL on the first and third.

- [ ] **Step 3: Implement**: the snapshot's lines are reused only when the active revision is the one named by the
  order's `CONFIRMED` event (`purchase_order_events.po_revision_id`); otherwise lines, total and expected date come
  from the order. Lines built from the order skip `CANCELLED` / `WITHDRAWN`.

- [ ] **Step 4: Run** `mvn test -Dtest=PurchaseOrderAmendmentIntegrationTest,SupplierProcurementIntegrationTest` — expect PASS.

- [ ] **Step 5: Commit** `fix(procurement): SCRUM-133 a resent order carries its approved amendment`

### Task 8: Reading revisions, the timeline and the pending amendment

**Files:**
- Create: `src/main/java/com/stockflow/procurement/internal/repository/PurchaseOrderHistoryRepository.java`
  (native reads, paged in SQL)
- Create: `src/main/java/com/stockflow/procurement/internal/service/PurchaseOrderHistory.java` (read service,
  warehouse scope, diff)
- Create: `src/main/java/com/stockflow/procurement/internal/controller/PurchaseOrderHistoryController.java`
- Create DTOs in `controller/dto`: `PurchaseOrderRevisionResponse`, `PurchaseOrderRevisionDetailResponse`,
  `RevisionChangeResponse`, `PurchaseOrderEventResponse`, `PendingAmendmentResponse`
- Modify: `PurchaseOrderController.response(...)` and `PurchaseOrderWebMapper.toResponse(...)`,
  `dto/PurchaseOrderResponse.java` (`pendingAmendment`, `amendmentDeliveryStatus`)
- Test: `src/test/java/com/stockflow/procurement/PurchaseOrderHistoryIntegrationTest.java`

**Interfaces:**
- Consumes: `PurchaseOrderRepository.pendingAmendment` (Task 3), `NotificationService.purchaseOrderAmendmentStatuses`
  (Task 6), `IdentityService` for names (one call per page).
- Produces endpoints (all `procurement-purchase-orders:READ`):
  `GET /api/v1/purchase-orders/{id}/revisions` → `List<PurchaseOrderRevisionResponse>`;
  `GET /api/v1/purchase-orders/{id}/revisions/{revisionNo}` → `PurchaseOrderRevisionDetailResponse`;
  `GET /api/v1/purchase-orders/{id}/timeline?page=0&size=20` (size ≤ 100) → `PageResponse<PurchaseOrderEventResponse>`.
  Field lists as spec §9. `purpose` `SUBMISSION` when a `SUBMITTED` event names the revision, else `AMENDMENT`;
  `state` `ACTIVE` = header's active, `PENDING` = header's pending, else the last approval decision `REJECTED` /
  `WITHDRAWN`, else `SUPERSEDED`.

- [ ] **Step 1: Write the failing tests:**
  - `revisionsListPurposeAndState` — submit (0), reject, resubmit (1, approved), amend (2, pending): states
    0 `REJECTED`, 1 `ACTIVE`, 2 `PENDING`; purposes `SUBMISSION`, `SUBMISSION`, `AMENDMENT`. After approving 2:
    1 `SUPERSEDED`, 2 `ACTIVE`.
  - `revisionDetailDiffsAgainstLastApproved` — revision 2 against 1: line 1 `CHANGED` quantity 10→8, line 2
    `REMOVED`, header `expectedDate` before/after.
  - `timelineNewestFirstWithPayloadAndNames` — page size 2 of 5 events; `actorName` filled; draft-edit payload as
    stored; a size of 500 → 400.
  - `orderResponseShowsPendingAmendmentAndNoticeStatus`.
  - `historyOutsideWarehouseScopeIsRefusedAsTheOrderIs` — Review Focus 5: a warehouse-bound user of another warehouse gets 404 on
    all three endpoints.

- [ ] **Step 2: Run** `mvn test -Dtest=PurchaseOrderHistoryIntegrationTest` — expect FAIL.

- [ ] **Step 3: Implement.** Diff base: the closest earlier revision with an `APPROVED` or `AUTO_APPROVED` approval
  row, else the immediately earlier revision, else none. Line changes from `RevisionDiff.between` (Task 6); header
  changes (`expectedDate`, `note`, `totalAmount`) from the two `snapshot_header` objects. Names fetched once per response through `identity :: api`.

- [ ] **Step 4: Run** `mvn test -Dtest=PurchaseOrderHistoryIntegrationTest,ModularityTest,ArchitectureTest` — expect PASS.

- [ ] **Step 5: Commit** `feat(procurement): SCRUM-132 read a purchase order's revisions and timeline`

### Task 9: Documentation and the full run

**Files:**
- Modify: `docs/business-design/03-state-machines.md` (§2 "Amendment" paragraph), `05-business-rules.md`
  (BR-PO-004), `04c-all-flows.md` (F-PO step 07 and its ⚠ note) — in the language each file already uses
- Modify (other repository, `D:/FPTU/KLTN/docs`): `docs/warehouse/02-purchase-order/README.md` per spec §13. Never
  stage anything under `slides/`.

- [ ] **Step 1: Edit** the three repo docs: amendment allowed on STANDARD `APPROVED`/`CONFIRMED` before any
  receipt (D1–D6), enforced in `PurchaseOrder.proposeAmendment`, `PurchaseOrderAmendments`,
  `ReceivingPurchaseOrderAdapter.recordProgress`; no `SENT`, no `PurchaseOrderAmended` event — the supplier notice
  is `PurchaseOrderSent` with `amendmentRevisionNo`.

- [ ] **Step 2: Edit** kltn-docs 02 in Vietnamese: new step "Sửa PO đã duyệt" replacing §4.5 step 19; §3 Output;
  BR-02; BR-13 exception; edge case; §5.2 `Confirmed → Pending`; §5.3 `Cancelled` line.

- [ ] **Step 3: Run** `python3 tools/verify.py` — expect all checks OK.

- [ ] **Step 4: Run** `mvn verify` once, nothing edited meanwhile — expect BUILD SUCCESS. Record the test count
  before (from develop) and after.

- [ ] **Step 5: Commit** both repositories separately: `docs(procurement): SCRUM-117 amendment of an approved
  purchase order` — after the owner's go-ahead.
