# The Production Coordinator owns production — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The coordinator reviews customer designs and sends production orders without an approval step; the producer's
acceptance takes an optional note; the Sales Manager is told when an order is sent.

**Architecture:** Two migrations (ordering schema; identity grants), a `send` replacing `submit`/`approve`/`returnToDraft`
in the `order` module's `ProductionOrder` / `ProductionOrderService` / controller, an `acceptance_note` column, and one
new `notification` listener on the existing `ProductionOrderSent` event. Design review moves by permission alone.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Modulith 1.4, PostgreSQL 16 (Flyway), JUnit 5 + Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-20-coordinator-owns-production-design.md` — read it first; this plan does not
repeat its reasoning.

## Global Constraints

- **Never `git commit` / `git push` without the repository owner's go-ahead** (CLAUDE.md §7). Tasks end with a
  "stage" step; the owner commits.
- Work in a **git worktree on a new branch `feature/coordinator-owns-production` cut from
  `feature/drop-production-order-price` HEAD (`c15413c4`)** — the spec relies on that commit (no price), which is PR be#383,
  not yet in `develop`. The main checkout has the owner's uncommitted work: do not touch it.
- New migrations, in this order, above every existing one (latest is `V20261209000400`):
  `V20261211000100__ordering_production_order_sent_by_coordinator.sql`,
  `V20261211000200__identity_coordinator_reviews_designs_and_sends.sql`. Never edit an existing migration.
- `check_production_order()` is rewritten from its **latest** body, `V20261207000100__ordering_production_order_no_price.sql`
  (not the baseline's).
- Every role-permission change advances `identity.app_role.version` of each role that gains or loses a grant, in the
  same statement (template: `V20261209000400`; delete grants before a permission row — ON DELETE CASCADE would skip the
  version).
- Historical enum constants stay: `ProductionOrderStatus.PENDING_APPROVAL`, `ErrorCode.PRODUCTION_ORDER_SELF_APPROVAL`
  (and its en/vi bundle entries).
- Identifiers, code and javadoc in English; javadoc explains why. Update every javadoc that says "approved by the Sales
  Manager", "approved, so sent", "BR-23 approver" in the files you touch.
- Fast checks after any change: `PYTHONUTF8=1 python -X utf8 tools/verify.py`; after package / permission changes:
  `mvn test -Dtest=ModularityTest,ArchitectureTest,PermissionMatrixTest`.

## Review Focus

1. A production order cancelled while DRAFT (no `sent_at`, no `sent_by`) must still satisfy the rewritten
   `ck_production_order_sent` — Task 1 QA SQL pins it.
2. Sending a production order whose producer was deactivated after it was drawn up must be refused
   (`PRODUCER_INACTIVE`), as `submit` did — Task 2 test pins it.
3. A stale `version` on send is refused **before** any print file is downloaded or slip stored — Task 2 test pins it.
4. The person who drew the order up can send it (no `PRODUCTION_ORDER_SELF_APPROVAL` any more) — Task 2 test pins it.
5. A redelivered `ProductionOrderSent` tells the Sales Manager once, not twice — Task 5 test pins it.

---

### Task 1: Ordering schema — sent by the coordinator, acceptance note

**Files:**
- Create: `src/main/resources/db/migration/V20261211000100__ordering_production_order_sent_by_coordinator.sql`
- Create: `tools/db/qa/36_production_order_sent_by_coordinator.sql` (follow the style of `tools/db/qa/21_production_orders.sql`)
- Create: `src/test/java/com/stockflow/ProductionOrderSendMigrationUpgradeTest.java` (template:
  `WarehouseCapacityMigrationUpgradeTest`)

**Interfaces:**
- Produces (columns later tasks map): `ordering.production_order.sent_by uuid NULL` (FK `identity.app_user(id)`),
  `ordering.production_order.acceptance_note varchar(500) NULL`; transition `DRAFT→SENT`.

The migration, in this order (header comment says why, citing the spec and 17 §4.3 step 7 / BR-23 rewritten):
1. Add `sent_by`, `acceptance_note`. Backfill `sent_by = approved_by WHERE sent_at IS NOT NULL`.
2. Leftover rows: `UPDATE … SET status='DRAFT', returned_reason='Approval step removed 2026-11-20' WHERE status='PENDING_APPROVAL'`
   (edge `PENDING_APPROVAL→DRAFT` still exists at this point; `ck_production_order_returned` is satisfied), and insert a
   `production_order_status_history` row per order (`from PENDING_APPROVAL to DRAFT`, `changed_by NULL`, same reason).
3. Transitions: insert `('DRAFT','SENT')`; delete `('DRAFT','PENDING_APPROVAL')` and every row with
   `from_status='PENDING_APPROVAL'`.
4. Drop and re-add `ck_production_order_approval`: `(approved_by IS NULL) = (approved_at IS NULL)` and
   `approved_by IS NULL OR approved_by <> drawn_up_by` — nothing about `sent_at`.
5. Drop and re-add `ck_production_order_sent` (read the current definition in the baseline, line ~7698, and keep its
   status logic): `(sent_at IS NULL) = (sent_by IS NULL)`; null in `DRAFT`/`PENDING_APPROVAL`; set in every status
   after them except `CANCELLED`, which may be either.
6. Add `ck_production_order_acceptance_note`: `acceptance_note IS NULL OR (btrim(acceptance_note) <> '' AND status NOT IN ('DRAFT','PENDING_APPROVAL','SENT'))`.
   (`CANCELLED` from SENT has none, from ACCEPTED may have one — the CHECK allows both.)
7. `CREATE OR REPLACE FUNCTION ordering.check_production_order()` from the V20261207000100 body with two changes: the
   write-once block protects `sent_by`, `sent_at` once `OLD.sent_at IS NOT NULL` and the historical
   `approved_by/approved_at/submitted_at` once `OLD.approved_at IS NOT NULL`; `acceptance_note` cannot change once
   `OLD.acceptance_note IS NOT NULL`. Everything else verbatim.
8. Check `check_production_order_slip_sent()` — it reads `p.sent_at IS NOT NULL`; unchanged, say so in the header.

- [ ] **Step 1: Write the QA SQL** — each statement in a `DO`/expect-error block as the existing qa files do:
  `UPDATE` a DRAFT order to `PENDING_APPROVAL` → refused (transition); a SENT order with `sent_by = NULL` → refused
  `ck_production_order_sent`; a CANCELLED order with `sent_at` and `sent_by` NULL → accepted; `acceptance_note = '  '`
  on an ACCEPTED order → refused; `acceptance_note` set on a SENT order → refused; changing a written
  `acceptance_note` → refused by the trigger; changing `sent_by` of a SENT order → refused by the trigger.
- [ ] **Step 2: Write `ProductionOrderSendMigrationUpgradeTest.pendingOrdersGoBackToDraftAndSentOrdersKeepTheirSender`**
  — Flyway `locations("classpath:db/migration","classpath:db/demo")`, `BEFORE = "20261209000400"`,
  `MIGRATION = "20261211000100"`. Before migrating: pick one DRAFT demo production order and move it to
  `PENDING_APPROVAL` with `submitted_at = now()` (raw SQL). After: that order is `DRAFT` with
  `returned_reason = 'Approval step removed 2026-11-20'` and one new history row; every order with `sent_at` has
  `sent_by = approved_by`; `RawSql.refusedBy(db, "ck_production_order_sent", …)` for nulling `sent_by` on one of them.
  Assert `result.migrations` contains exactly `MIGRATION`.
- [ ] **Step 3: Run** `mvn test -Dtest=ProductionOrderSendMigrationUpgradeTest` → FAIL (migration missing).
- [ ] **Step 4: Write the migration** as listed above.
- [ ] **Step 5: Run** `mvn test -Dtest='ProductionOrderSendMigrationUpgradeTest,RepositorySqlQaTest'` → PASS, and
  `python -X utf8 tools/verify.py` → no Flyway collision. (Entity mapping is not changed yet: `ddl-auto: validate`
  tolerates extra columns.)
- [ ] **Step 6: Stage** the three files.

### Task 2: `send` replaces submit / approve / return

**Files:**
- Modify: `src/main/java/com/stockflow/order/internal/domain/ProductionOrder.java` (remove `submit`, `approve`,
  `returnToDraft`; add `send`, field `sentBy`; constructor + accessor)
- Modify: `src/main/java/com/stockflow/order/internal/entity/ProductionOrderJpaEntity.java`,
  `.../repository/ProductionOrderRepositoryAdapter.java` (map `sent_by`)
- Modify: `src/main/java/com/stockflow/order/internal/service/ProductionOrderService.java` (remove `submit`, `approve`,
  `returnToDraft`; add `send`)
- Modify: `src/main/java/com/stockflow/order/internal/controller/ProductionOrderController.java` (remove `/submission`,
  `/approval`, `/return`; add `/sending`; response adds `sentBy`, keeps historical approval fields)
- Modify javadoc only: `contracts/ProductionOrderSent.java` ("sent by the Production Coordinator"),
  `ProductionOrderDispatch.java`, `CapacityBoard.java`, `WarehouseCapacityService.java`, `OpenProductionCartons.java`,
  `SalesOrderCancellationListener.java`, `DelayAlertRepositoryAdapter.java`, `chat/.../ContextResolver.java`,
  `ProductionOrderProgressListener.java` — keep `PENDING_APPROVAL` in their status sets.
- Test: `src/test/java/com/stockflow/order/internal/domain/ProductionOrderTest.java`,
  `src/test/java/com/stockflow/order/ProductionOrderIntegrationTest.java`; call-site migration in every test listed in
  Step 6.

**Interfaces:**
- Consumes: Task 1 columns.
- Produces: `ProductionOrder.send(UUID by, Instant at)`; `ProductionOrder.sentBy(): UUID`;
  `ProductionOrderService.send(UUID id, long version, UUID by): ProductionOrder`;
  `POST /api/v1/production-orders/{id}/sending` body `{ "version": long }`, `production-orders:UPDATE`,
  `ScopeGuards.requireBackOffice`.

`send` (service) = today's `approve` body, minus the self-approval guard, plus `activeProducer(read.producerId())`
before downloads (submit used to check it). Order: read + `visible` → status must be `DRAFT` → version → producer
active → `verifyForPrinting` per snapshot → `prepareSlip` (no lock) → `tx.execute`: `lockedWithOrder`,
`requireWithin(lacking)`, `capacity.warnIfSendingTurnsNegative`, `production.send(by, now)`, save,
`dispatch.sent(saved, slip)`. Keep `@Transactional(propagation = NOT_SUPPORTED)`;
`@Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#id")`.
Domain `send`: `move(SENT, null, by, at, "sent")`, sets `sentBy`, `sentAt`; refuses unless DRAFT
(`PRODUCTION_ORDER_STATUS_INVALID` via `move`'s transition check).

- [ ] **Step 1: Domain tests** in `ProductionOrderTest`: `sendMovesADraftToSentAndRecordsWhoSentIt`
  (status SENT, `sentBy()==by`, `sentAt()==at`, `approvedBy()==null`); `sendRefusesAnOrderThatIsNotADraft` (from SENT:
  `PRODUCTION_ORDER_STATUS_INVALID`); `theCoordinatorWhoDrewItUpMaySendIt` (by = drawnUpBy → SENT). Delete the tests of
  `submit`/`approve`/`returnToDraft`.
- [ ] **Step 2: Integration tests** in `ProductionOrderIntegrationTest` (replace the submit/approve/return tests):
  `sendingDoesWhatApprovalDid` (slip stored, `ProductionOrderSent` published once, sales order AWAITING_STOCK — copy
  the assertions of the current approval test); `sendingRefusesAChangedPrintFileAndStoresNothing`
  (`PRINT_FILE_MISMATCH`, no slip row, still DRAFT); `aStaleVersionIsRefusedBeforeAnyDownload` (`OPTIMISTIC_LOCK`, the
  storage double sees no download); `sendingRefusesAnInactiveProducer` (`PRODUCER_INACTIVE`);
  `theDrawerSendsTheirOwnOrder`; HTTP: `POST …/approval` and `…/submission` answer 404.
- [ ] **Step 3: Run** `mvn test -Dtest='ProductionOrderTest,ProductionOrderIntegrationTest'` → FAIL (compile).
- [ ] **Step 4: Implement** domain, entity/adapter mapping, service, controller as in Interfaces.
- [ ] **Step 5: Run** the same → PASS.
- [ ] **Step 6: Migrate call sites.** Replace every `productionOrders.submit(...)` + `productionOrders.approve(...)`
  pair with one `productionOrders.send(id, version, coordinator)` in: `support/ProductionReceivables`,
  `order/ProductionOrderDispatchFixtures`, `ProductionOrderDispatchIntegrationTest`, `ProductionSampleIntegrationTest`,
  `ProductionScheduleIntegrationTest`, `AwaitingStockIntegrationTest`, `AwaitingStockReceiptIntegrationTest`,
  `WarehouseCapacityIntegrationTest`, `procurement/ProductionReceiptIntegrationTest`; then
  `grep -rn "productionOrders\.\(submit\|approve\|returnToDraft\)(\|/submission\|production-orders/.*/approval" src/test`
  → nothing. Tests that asserted "the manager approves" now assert the coordinator sends; a test that only existed for
  self-approval or return is deleted. Delete the then-unused `manager` fixture users if nothing else uses them.
- [ ] **Step 7: Run** `mvn test -Dtest='*Production*,AwaitingStock*,WarehouseCapacity*'` → PASS; `tools/verify.py` → clean.
- [ ] **Step 8: Stage.**

### Task 3: The acceptance note

**Files:**
- Modify: `ProductionOrder.java` (`accept(LocalDate expectedCompletion, String note, UUID by, Instant at)`, field
  `acceptanceNote`, accessor), entity + adapter (`acceptance_note`), `ProductionOrderService.accept(UUID id, long version,
  LocalDate expectedCompletion, String note, UUID by)`, controller `AcceptRequest` gains
  `@Size(max = 500) String note`, response gains `acceptanceNote`.
- Test: `ProductionOrderTest`, `ProductionOrderIntegrationTest`; update other `accept(` callers (grep).

**Interfaces:**
- Consumes: Task 1 column, Task 2 `send`.
- Produces: `ProductionOrder.acceptanceNote(): String` (null when none).

- [ ] **Step 1: Domain tests**: `acceptKeepsATrimmedNote` (`"  anh Hùng, Zalo  "` → `"anh Hùng, Zalo"`);
  `aBlankNoteIsNoNote` (`"   "` → null); `aNoteOver500CharactersIsRefused` (501 chars → `VALIDATION_FAILED`).
- [ ] **Step 2: Integration test** `theAcceptanceNoteIsReturnedWithTheOrder` (HTTP accept with note → GET detail has it).
- [ ] **Step 3: Run** → FAIL; **Step 4: Implement**; **Step 5: Run**
  `mvn test -Dtest='ProductionOrderTest,ProductionOrderIntegrationTest,ProductionSampleIntegrationTest'` → PASS.
- [ ] **Step 6: Stage.**

### Task 4: Grants — the coordinator reviews designs, approval retired, the send notice's permission added

**Files:**
- Create: `src/main/resources/db/migration/V20261211000200__identity_coordinator_reviews_designs_and_sends.sql`
- Create: `src/test/java/com/stockflow/CoordinatorReviewsDesignsMigrationUpgradeTest.java`
- Create: `tools/db/qa/99_coordinator_owns_production.sql`
- Modify: `src/test/java/com/stockflow/support/PermissionMatrixTest.java` (`SEEDED_HOLDERS` and any endpoint map)
- Modify (javadoc + test fixtures): `design/internal/service/DesignReviewService.java`,
  `DesignReviewController.java`, `DesignReviewRoundController.java`, `CustomerDesignRules.java`, `DesignReadRights`
  usages — wherever "Sales Manager" names the reviewer; `DesignReviewNotificationListenerTest` and design tests that sign
  in a SALES_MANAGER to review now use a PRODUCTION_COORDINATOR (`grep -rln "SALES_MANAGER\|sf.manager" src/test/java/com/stockflow/design`).

The migration:
1. Insert the permission of those told when a production order is sent, `ON CONFLICT (code) DO NOTHING`. Built as
   `production-order-sends:READ`: see the spec's "Built as" note for why not `production-orders:MONITOR`.
2. Grants: `design-designs:APPROVE` → PRODUCTION_COORDINATOR (insert), SALES_MANAGER (delete); `production-order-sends:READ`
   → SALES_MANAGER, SYSTEM_ADMIN; `production-orders:APPROVE` → delete every grant, then the permission row.
3. Whatever else the review screens require that the coordinator lacks: list the `@RequiresPermission` of every
   handler in `DesignReviewController`, `DesignReviewRoundController` and the design artifact / project / preflight
   reads they link to, compare with the coordinator's grants in `PermissionMatrixTest.SEEDED_HOLDERS`, and grant the
   missing ones here (expected: `design-designs:READ` and its `VIEW_PAGE` if one exists). Name each in the header.
4. One `UPDATE identity.app_role SET version = version + 1` over every role that gained or lost a grant (CTE
   `RETURNING role_id` from each insert/delete).

- [ ] **Step 1: Upgrade test** `theCoordinatorReviewsDesignsAndTheManagerIsToldOfSends`: before/after
  (`BEFORE = "20261211000100"`, `MIGRATION = "20261211000200"`): holders of `design-designs:APPROVE` =
  `[PRODUCTION_COORDINATOR, SYSTEM_ADMIN]`; of `production-order-sends:READ` (spec "Built as") = `[SALES_MANAGER, SYSTEM_ADMIN]`; no row
  `production-orders:APPROVE`; versions of PRODUCTION_COORDINATOR, SALES_MANAGER, SYSTEM_ADMIN each +1; CUSTOMER,
  WAREHOUSE_MANAGER unchanged.
- [ ] **Step 2: PermissionMatrixTest**: `"production-orders:APPROVE"` removed; add `"production-order-sends:READ"` (spec "Built as") →
  `{SALES_MANAGER, SYSTEM_ADMIN}` and `"design-designs:APPROVE"` → `{PRODUCTION_COORDINATOR, SYSTEM_ADMIN}` (if the map
  lists it; else add it).
- [ ] **Step 3: QA SQL**: the three holder sets as `SELECT … HAVING` assertions in the style of
  `99_permission_review_a2_a4.sql`.
- [ ] **Step 4: Run** `mvn test -Dtest='CoordinatorReviewsDesignsMigrationUpgradeTest,PermissionMatrixTest'` → FAIL.
- [ ] **Step 5: Write the migration**; switch design test fixtures to the coordinator; javadoc.
- [ ] **Step 6: Run** `mvn test -Dtest='CoordinatorReviewsDesignsMigrationUpgradeTest,PermissionMatrixTest,RepositorySqlQaTest,*Design*'`
  → PASS.
- [ ] **Step 7: Stage.**

### Task 5: The Sales Manager is told when an order is sent

**Files:**
- Create: `src/main/java/com/stockflow/notification/internal/service/ProductionOrderSentOversightListener.java`
  (shape: `ProductionOrderArrivalNotificationListener`)
- Modify: `src/main/resources/i18n/messages*.properties` (en + vi): `inbox.production-order.sent-oversight.title`
  (`"Đơn sản xuất %s đã được gửi"`), `.body` (`"%d thùng, cần hàng %s."`) — and English equivalents.
- Test: `src/test/java/com/stockflow/notification/internal/service/ProductionOrderSentOversightListenerTest.java`
  (shape: `DesignReviewNotificationListenerTest`, Mockito)

**Interfaces:**
- Consumes: `contracts.ProductionOrderSent`; permission `production-order-sends:READ` (Task 4; spec "Built as").
- Produces: `static final String AUDIENCE = "production-order-sends:READ"`; dedupe key
  `"production-order-sent-oversight:" + productionOrderId`; link `"/production/orders/" + id`; severity INFO; inbox only.

`@ApplicationModuleListener public void on(ProductionOrderSent event)`: `staff.activeHolders(AUDIENCE, null)` → user
ids → `inbox.post`. Nobody → log and return.

- [ ] **Step 1: Tests**: `tellsEveryActiveHolderOfMonitor` (two holders → `inbox.post` with both, title contains the
  code, dedupe key as above); `nobodyToTellPostsNothing`; `aRedeliveredEventUsesTheSameKey` (two calls → same dedupe key,
  so `Inbox` posts once — assert the key, the inbox owns the dedupe).
- [ ] **Step 2: Run** `mvn test -Dtest=ProductionOrderSentOversightListenerTest` → FAIL; **Step 3: Implement**;
  **Step 4: Run** → PASS, plus `mvn test -Dtest='MessageBundleTest,ModularityTest'` → PASS.
- [ ] **Step 5: Stage.**

### Task 6: Documents

**Files:**
- Modify: `CLAUDE.md` §4 "Production orders and receiving" — "Sales Manager-approved production orders" → "production
  orders the coordinator sends (no approval step since 2026-11-20)"; the approval lock-order line → "Sending one".
- Modify: `docs/superpowers/plans/2026-11-20-fe-producers-production-orders.md` — replace `/submission`, `/approval`,
  `/return` and `production-orders:APPROVE` with `/sending` (coordinator), `acceptanceNote`, `sentBy`; the design
  review queue is the coordinator's.
- kltn-docs (`~/projects/kltn/docs`, new branch from `origin/develop`): 17 §2 roles, §4.3 steps 7–8, §5.3, BR-23
  (rewritten with the spec's "Why approval goes" table), BR-25 "at send"; 12 step 3, §2 role table and the reviewer note,
  §3 reader table (Sales Manager no longer reads designs awaiting review); 00 §2.1; `open-questions` row; each with
  `✅ Chốt lại 2026-11-20`. Vietnamese.

- [ ] **Step 1:** edit the three; **Step 2:** `grep -rn "approv" CLAUDE.md docs/superpowers/plans/2026-11-20-fe-producers-production-orders.md`
  shows no stale production-order approval; **Step 3:** stage here; leave the kltn-docs branch uncommitted for the owner.

### Task 7: Whole-suite verification

- [ ] **Step 1:** `PYTHONUTF8=1 python -X utf8 tools/verify.py` → clean.
- [ ] **Step 2:** `mvn test` (Docker up) → green; any red is investigated, not skipped.
- [ ] **Step 3:** `git -C <worktree> status` and `git diff --cached --stat` shown to the owner with a proposed commit
  message per task (no Claude attribution trailer); the owner commits.
