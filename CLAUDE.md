# StockFlowCommerce — working notes for Claude Code

FPT University capstone **FA26SE029**, group **GFA26SE03**, 5 members, supervisor Nguyễn Minh Sang.
A finished-goods warehouse and B2B sales platform for a Vietnamese business whose cups and packaging
are printed to its wholesale customers' designs (decided 2026-10-06: B2B only). Since 2026-11-07 the
printing is done by producers outside the system: goods arrive printed, in cartons, and are picked from
stock like any other; the in-house production module is gone (`V20261108000100`). Stock never changes
warehouse: transfer orders between warehouses are gone too (`V20261108000300`). Every order ships as a full
truckload through a load plan and nothing is collected on delivery: parcel shipping, the delivery-mode choice and
COD are gone (`V20261108000400`). Printed goods arrive carrying the customer's design, so packing checks only the SKU
and the count: the design check at packing is gone (`V20261108000500`).
The business is now a quote → contract → installments → production-order → receiving → pick-by-carton → truckload
chain (ADR-0010 records the rescope and what it superseded; the business analysis is the kltn-docs repository).

Read this before touching anything. It is short on purpose; it says what is not obvious from the
code, and points at the code for the rest.

---

## 1. Read this first: what has been verified

**The suite runs.** `mvn test` resolves, compiles and executes under Maven; on the integration tip of the rescope it
is `<FULL_SUITE_COUNT>` tests (unit, integration against Testcontainers Postgres + Redis, migration upgrade tests,
and the fitness functions). Earlier versions of this file said it had never run; that was true until the first
Maven-enabled build and is no longer. Do not "restore" the old caution: when a test is red, it is information.

What each layer of checking covers, and what it does not:

| Check | Covers | Does not cover |
|---|---|---|
| `tools/verify.py` — **run it first** | package↔path, module boundaries, `allowedDependencies`, unused imports, `@Scheduled` without `@SchedulerLock`, non-public proxied methods, Flyway version collisions, entity↔migration, `@NamedInterface` | anything needing a running context |
| `ModularityTest`, `ArchitectureTest`, `PermissionMatrixTest`, `MessageBundleTest` | module boundaries, layering and base-layer traps, the permission matrix, en/vi error bundles | behaviour |
| unit + integration tests | domain rules, Spring wiring, JPA mapping, SQL, locking | what nobody wrote a test for |
| `*MigrationUpgradeTest` | a migration run over demo-like data: schema and remaining rows afterwards | the migrations that were not changed |
| `tools/db/qa/run.sh <db>` | adversarial SQL against a freshly migrated database: every CHECK / trigger refuses what it should | application code |

A green run is evidence, not proof: the failures this stack produces are mostly silent (see §5), and a single-request
happy path passes most of them. Fixtures matter: a test that builds a world the migrations cannot produce proves
nothing, so use `support/` (`DemoData`, `ReferenceRows`, `CartonStationSupport`, …) rather than inventing rows.

```bash
PYTHONUTF8=1 python -X utf8 tools/verify.py   # no Maven, no network, under a second
mvn test                                  # everything (needs Docker)
mvn test -Dtest=ModularityTest            # module boundaries only — run after ANY package change
mvn test -Dtest=ArchitectureTest          # 19 layering/convention rules
mvn verify                                # tests + JaCoCo
docker compose up -d                      # postgres, redis, minio, jaeger, mailpit
docker compose --profile app up           # the above plus the application container
```

The Maven wrapper *scripts* are not committed; `.mvn/wrapper/maven-wrapper.properties` pins 3.9.9.
Generate them with `mvn wrapper:wrapper -Dmaven=3.9.9 -DdistributionType=only-script` if you want
`./mvnw`.

---

## 2. What is actually built

All 14 business modules have code; there are no skeletons left. `inventory` is still the **reference module — copy
its shape** — but read the module's `package-info.java` before assuming what it owns, because several were
reshaped by the rescope (ADR-0010).

| Module | Holds |
|---|---|
| `inventory` | stock only as whole LPN cartons, holds and allocations, FIFO, putaway, counts and adjustments, quarantine, ledger |
| `warehouse` | warehouses, zones, bins (capacity in m³, `STAGING`, `QUARANTINE`, `OVERFLOW`), putaway tasks |
| `order` | quotes, sales contracts, orders, delivery batches, shipping-warehouse choice and release, production orders and producers, cancellations, return requests |
| `payment` | installments of signed contracts, payment receipts, credit position, settlements, refund records (no money ledger) |
| `fulfillment` | pick lists, staging, carton check and labelling, shipments, load plans, dispatch, carriers (Ahamove), proof of delivery |
| `procurement` | goods receipts against production orders and return receipts (name kept; purchasing is gone) |
| `product`, `catalog`, `design`, `customer`, `identity`, `chat`, `notification`, `reporting` | as their `package-info` says; `chat` and `reporting` are the smallest, check before assuming a feature exists |
| `common`, `contracts` | the shared base layer and the event records |

The shared base layer in `common/` is finished and deliberately thorough — security, caching,
idempotency, rate limiting, auditing, storage, locking, validation, i18n, the API envelope, the
exception handler. **Do not rebuild any of it inside a module.** `README.md` has the table of what
is there; `docs/adding-a-module.md` §3 has the "use this, not that" list.

Migrations: the schema lives in `src/main/resources/db/migration`; `docs/business-design/db-design/schema.dbml`
is generated from the migrated database. Removed features were dropped by migration, with no archive (the VPS data
is rebuilt on deploy, owner decision QD-1); historical enum constants stay. `event_publication` stays unqualified in
`public` because Spring Modulith owns it (`V20260901000400` explains why).

---

## 3. Architecture in one screen

Modular monolith on Spring Boot 3.5 / Spring Modulith 1.4 / Java 21 / PostgreSQL 16.
One database, **one schema per module**, cross-schema references are real foreign keys (ADR-0007,
replacing ADR-0005's plain UUIDs), no cross-schema JOIN.

```
com.stockflow.inventory/
├── package-info.java     @ApplicationModule(allowedDependencies = {"warehouse :: api", "warehouse :: spi", "identity :: api"})
├── api/                  @NamedInterface("api")  ← the ONLY package other modules may import
│   InventoryService, HoldStockCommand, HoldStockResult, StockAvailability, StockReservation, …
└── internal/             invisible to other modules
    ├── controller/       REST controllers + request/response DTOs
    ├── service/          application services, listeners, schedulers · transaction boundary
    ├── repository/       Spring Data repositories, port adapters, entity⇄domain mappers
    ├── entity/           JPA entities — the table mapping and nothing else
    └── domain/           aggregates, value objects, repository PORTS · no Spring, no JPA
```

Three things to internalise:

1. **`api` is a named interface, not a plain package.** Spring Modulith exposes a module's *base*
   package by default and treats nested ones as internal, so `api/package-info.java` carries
   `@NamedInterface("api")` — without it the package is private. This is why every dependency is
   written `"inventory :: api"` and not `"inventory"`. A bare `"inventory"` points at the base
   package, which holds only `package-info.java`.

2. **What belongs in `api` is decided mechanically:** the service interface, plus exactly the types
   appearing in its method signatures, plus what those drag in. Nothing else. `StockItem` is the
   module's most important class and it is *not* in `api`, because it appears in no signature.
   Handing another module an aggregate lets it call behaviour outside a transaction and outside the
   invariants. `docs/adding-a-module.md` §1 works this through.

3. **`javac` does not enforce any of this.** It is all one jar; every boundary is enforced by
   `ModularityTest` (module boundaries, `allowedDependencies`, cycles) and `ArchitectureTest`
   (layering inside a module, plus the base-layer traps). Those two tests are the architecture.
   Never disable one to unblock a build.

Cross-module communication is either a **direct call** on the `api` interface (synchronous, joins
the caller's transaction) or an **event** from `contracts/` (asynchronous, after commit). Event
records live in `contracts` rather than in the publisher so two modules listening to each other do
not form a cycle.

### Naming

`docs/adding-a-module.md` §2 is the full convention. The short version: a class with a framework
role carries that role as a suffix (`InventoryController`, `StockItemJpaEntity`,
`OrderRepositoryAdapter`); a value carries none (`StockItem`, `Quantity`, `StockAvailability`) —
no `...Dto`, no `...Model`. A `Command` and its `Result` share a stem. One word per concept across
migration, domain, api and URL.

### Language

**All identifiers, executable code and javadoc are in English, without exception.** Vietnamese is
correct and expected in exactly two places: `docs/business-design/**` (the business analysis, which
the team and the supervisor read) and the `_vi` i18n bundles / demo seed data. Do not "fix" those
into English, and do not let Vietnamese leak into code.

Javadoc in this codebase explains **why**, not what. When you change behaviour, change the javadoc
that justified the old behaviour — several defects in §5 were found precisely because a comment
claimed something the code no longer did.

---

## 4. The flows, in code

The business analysis is the **kltn-docs** repository (`docs/`: 14 quotation and order, 15 payment, 17 order
management, 03 receipt, 05 putaway, 07 picking, 08 packing, 09 shipping, 20 inventory control, …). It wins over
`docs/business-design/**` here, which is the pre-rescope analysis and carries a "superseded" banner. Learn behaviour
from the code and these notes, not from the old docs. Where the flows live:

### Quotation → contract → order

There is no cart and no checkout (team plan 5-2, 4B-10). Sales draw up a quote for a customer's own SKUs, by the
carton; the customer accepts it; `QuoteService.accept` checks, under the quote's row lock, the version the customer
saw, the validity and the payment term, and `convert` calls `OrderServiceImpl.placeQuotedOrder` in the same
transaction: a DRAFT order that holds nothing, with a request id derived from the quote, so a second call returns the
same order. `SalesContracts` (`order/internal/service/`) draws the contract up for that order (delivery batches,
payment plan, free sample rounds), sends it and records it signed; the installments live in `payment`. Money reaches
the order as an event: `PaymentEventListener` (`@ApplicationModuleListener`) takes `PaymentReceiptRecorded` /
`PaymentReceiptVoided`, because a receipt the accountant recorded must not wait for, or be rolled back by, what the
order does next. `accept` does not convert: `convert` is Sales' own step afterwards. At signing an order takes one of three routes to
CONFIRMED (`Order.contractSigned`, `confirmByInstallments`, `approveCredit`): payment due at signing, so
PENDING_PAYMENT until the receipts pay those installments; nothing due at signing, so CONFIRMED at once; or a failed
credit check, so ON_HOLD(CREDIT) until it is approved, then the same rule. A void the accountant agreed to puts a
confirmed order ON_HOLD (15 BR-07, BR-13). There is no money ledger and no "payment failed" event: an order has no money of
its own, only its contract's installments. Money that must share a transaction (a cancellation's settlement, the
handover's check) is a direct call through `payment :: api`.

Spring Modulith's **event publication registry** replaces the transactional outbox: an event a listener failed to
process stays in `event_publication` and is retried (migration `V20260901000400`).

### Holding an order's stock — what still justifies the single transaction

When Sales chooses the shipping warehouse, `ShippingWarehouseService.choose` holds the order's stock there, one
`@Transactional` method (ADR-0010 says why the in-transaction design survived the rescope):

```
1. the order row FOR UPDATE
2. inventory.prepareReservation(skus)   ← SKU advisory locks, sorted
3. for each line still short:
      inventory.holdAtWarehouse(HoldStockCommand(deterministic requestId, ...))   ← whole cartons, what is there
      order.holdMore(lineId, result.reservationIds())   ← ALL ids, not the first
4. repository.save(order)
```

`holdAtWarehouse` is a **plain Java call inside this transaction**: if the order fails to persist, the hold rolls
back with it. Holds are SKU-level (`SkuReservation`, no bin) and have **no deadline**; a shortage is not refused, it
is shown per delivery batch and topped up when goods are put away (`topUpWaiting`). A hold of a delivery-scheduled
order is recorded per batch (`DeliveryBatchRecords`), so a batch is released with exactly its own holds.
`reservationIds()` returns every id: a line held from several cartons has several holds, and keeping only the first
would strand the rest. Deterministic request ids make a retry converge on the same holds.

`OrderReleaseService.release` (or task creation) then allocates: SKU locks (sorted) → the order's holds `FOR UPDATE` →
`StockAllocator.plan` over projections → each stock row locked once, in id order (`findByIdForUpdate` refreshes, or the
pessimistic lock would be decorative) → `StockItem.allocate`; the order line swaps its hold ids for the allocation ids.
The plan gives **whole sealed LPN cartons only**, FIFO by real `received_at` for every SKU — there is no expiry, no
FEFO, no loose or opened stock (kltn-docs 01 BR-17, 07 BR-03; team plan 5-1, 4C-2). A carton larger than what is
still needed is skipped, never broken. Allocations are `stock_reservation` rows with `allocated_from_id`; a pick line
consumes exactly one. A delivery-scheduled order is released one batch at a time (17 BR-29).

There are no expiring reservations and no sweeper: the manual reservation endpoint, `InventoryService.reserve` and
`ReservationSweeper` with its TTL are removed (a V20261117 migration releases leftover expiring holds). Stock is held
only for a signed contract's order and allocated at release. The whole-carton rule is stated twice, in the aggregate
and as a CHECK on `inventory.stock_item` (stock is never loose).

### Production orders and receiving

Stock the business does not have is not bought: Sales Manager-approved **production orders** (`ProductionOrderService`,
`order/internal/service/`) are sent to producers outside the system, one per delivery batch, for what the batch lacks
after the stock held for it. The order waits `AWAITING_STOCK` / `IN_PRODUCTION`. The print sample rounds
(`ProductionSampleService`), the schedule and the delay alerts are in the same module. Every write locks the sales
order first and the production order after it.

Goods come back printed, in cartons, and are received against the production order: `GoodsReceiptServiceImpl`
(`procurement`) works through `order :: api` `ProductionOrderReceiving`. A receipt takes whole cartons of each line's
standard count, in the production order's lot, never more than the line orders on all its live receipts (03 BR-02), so
nothing waits for approval. Cartons are labelled while a DRAFT receipt is counted (`replaceLines` →
`InventoryService.countReceivingPackages`, which reconciles the line's RECEIVING cartons through the pure
`ReceivingPackagePlan`: new → LPN, listed → recount, missing → VOID); `confirm` is one transaction that makes the
cartons INBOUND stock, advances the production order and sends events after commit. There is no QC step (03 BR-07)
and no purchase order any more. Lock order, everywhere:

```
customer_order → production_order → goods receipt FOR UPDATE → SKU advisory locks (sorted) → stock_package FOR UPDATE → stock rows by id
```

Cartons move and change status whole, a list of LPNs per call (`moveStockPackages` / `reclassifyStockPackages`). A loose
`move` / `reclassify` and a carton move may share a reference; the loose replay lookup
(`StockMovementLog.findLooseByReference`) ignores ledger lines naming a carton, or it would take the carton's line for
the loose move's earlier attempt and skip it. `LpnSequence.reserve` is `REQUIRES_NEW` so a sequence row held until
commit cannot deadlock two receipts; it draws a line's LPNs as one block. Gaps in LPNs are the accepted price.
Looking cartons up is `StockPackageLookup` (`/inventory/stock-packages/...`), one SQL statement per kind of fact. There
is deliberately no endpoint to mark a carton LOST or found: that writes stock off, so it is only an approved count's
adjustment.

### Putaway

One task per carton, put away whole (05 BR-08). The task aggregate (`PutawayTask`) lives in `warehouse` behind
`warehouse :: api`; `PutawayTaskCreator` opens tasks when a receipt is confirmed, or when a warehouse manager releases a
carton from quarantine (4D-1); `PutawayService.confirm` moves the carton in one `@Transactional` method. Candidates are
usable bins flagged `is_putaway_target`, ranked by the pure `PutawayRanker`; a bin must fit the carton and its volume
(m³, 5-4), else it goes to `OVERFLOW`.

```
lock order: task row → bin advisory lock (putaway-bin:<code>) → SKU advisory lock → stock_package FOR UPDATE → stock rows by id
```

The bin lock exists because only putaway checks bin capacity and occupancy is bin-wide across SKUs. Confirmation is
synchronous on purpose (a 200 means the goods are in the bin). Each task that becomes DONE publishes `PutawayCompleted`;
`procurement`'s `PutawayCompletedListener` calls `GoodsReceipt.closeIfPutAway` under `findByIdForUpdate`, and putting
goods away tops up the orders waiting for stock (above). Loose tasks are history: V20261111000110 boxed all loose stock.

### Picking, only by LPN — through `inventory :: consumption`

`PickListService.confirm` (`fulfillment/internal/service/`), one `@Transactional` method:

```
1. lockPick           ← SELECT … FOR UPDATE on fulfillment.pick. ALWAYS first: pick, then stock row,
                        never the reverse (cancelling an order locks stock rows in its own transaction
                        and touches the pick only later, in the OrderCancelled listener)
2. scope + assignee   ← WarehouseScope.requireWarehouse(pick.warehouseId), then only the assignee
3. PickList.confirm   ← domain: location, then the line's own LPN only (07 BR-10); no quantity, the carton is whole (BR-04); same scan again = done
4. PickConsumption    ← inventory, same transaction: consumeForPick locks the stock row, hold → CONSUMED, on_hand falls,
                        one stock_movement(PICK, PICK_LINE) row, StockDeducted published. Idempotent per pick line
                        (the ledger row is looked up first; uk_stock_movement_pick_line is the last defence)
```

`PickConsumption` lives in `inventory/consumption/`, a second named interface next to `api`; only `fulfillment` lists
`"inventory :: consumption"` (`InventoryConsumptionBoundaryTest` pins that): deducting stock is not something any
module may do. A short pick is per carton with a reason (`NOT_FOUND` / `DAMAGED`): the line SHORT, the order ON_HOLD, a
blind count of the carton. Damage is handled whole, by carton (short pick, the check station's removal into quarantine,
a quarantined carton released or written off); the unit-level damage report went with 5-6. A carton picked for an order
cancelled afterwards goes back whole, scanned by LPN (`POST /fulfillment/tasks/{id}/pick-lines/{lineId}/put-back`).

**The staging area (4C-5; 07 §4.3; 20 §4.9).** A task has a `destination`, chosen while it is PENDING (as is its
assignee): `DISPATCH` deducts each carton at the scan; `STAGING` calls `PickConsumption.stage` instead — the carton moves
whole to the task's staging area with its allocation still HELD, the line is STAGED and the task completes STAGED. The
warehouse manager moves the whole task to dispatch (`POST /fulfillment/tasks/{id}/dispatch-transfer`, refused while
the order is ON_HOLD): one bulk `consumeStaged` for every staged line (SKU locks sorted, then cartons, then rows); a carton
no longer IN_STOCK in the staging area is refused, not shipped. Stock in a STAGING area is out of ATP, never allocated,
never moved by hand (`StockOperationsServiceImpl.requireMovableBetween`, `tg_stock_movement_staging`), and written
down by a count or an adjustment but never up. Lock order of the move to dispatch: pick → order → SKU locks → carton →
row. A top-up (`POST …/top-up`) plans lines for holds no line has yet, in the same task.

`POST /api/v1/inventory/reservations/{reservationId}/consumption` still exists for operations and runs the same
implementation; nothing in the repo calls it — picking never goes over HTTP.

### Carton check and labelling (4C-6)

The LPN carton is the shipping carton (08 BR-02): nothing is packed again, and the design is not checked (printed goods
arrive carrying it). `CartonCheckService` works a task's `fulfillment.pack` row as the check session (`PENDING →
IN_PROGRESS → COMPLETED`, `CANCELLED`) and one `fulfillment.package` row per PICKED pick line as its carton (`TO_CHECK →
CHECKED → LABELED`, or `REMOVED`); the rules are on the pure `CartonCheck`. Labelling takes the SKU's carton spec
(`InventoryControlService.cartonSpec`) and volumetric weight; the first label calls `OrderService.recordFirstLabel`,
which locks cancellation and the delivery address. Completion makes the LABELED cartons one shipment. A carton removed
as damaged goes whole into a QUARANTINE area (`PickConsumption.putBackToQuarantine`). The repack station (parcels, kits,
`package_line`) went with 4C-7.

### Shipping a full truckload, and Ahamove

Every order ships as a full truckload through a load plan (`LoadPlanService`: plan row → shipments → dispatch loads, in
that order); parcel carriers, delivery-mode choice and COD are gone. A carrier with a booking API gets its order made
by the system: `CarrierBookingService` (Ahamove v3, `AhamoveGateway`) books in three steps so that no row lock or
connection is held while the carrier answers — claim (`REQUESTED`, one live claim per load), call, then book the load
under its locks or record the refusal. A call that does not come back is **not a refusal**: the claim becomes
`UNCONFIRMED` and keeps the load until a dispatcher has checked the carrier's portal, because a second order would send
a second truck. `CarrierBookingSweeper` cancels, with no transaction open, orders at carriers that no load wants any
more. Carrier webhooks (`CarrierWebhookController`) are kept first and applied afterwards in short transactions
(`CarrierEventService`: received → matched → applied, a refusal is NEEDS_REVIEW); the error list shows calls left RECEIVED.

### Cycle count — by carton, a thin document over the adjustment (SCRUM-146, 4C-3)

The count lives in `inventory`: aggregate `CycleCount` (`PLANNED → IN_PROGRESS → COMPLETED`, `CANCELLED`)
with one line per LPN carton (`CountSlot`), planning in `CycleCountService`, counting and submission in
`CycleCountCountingService`. A carton is scanned present or marked not found — never a quantity (20 BR-14); "booked" is
read from the carton under its lock when the line is counted, so nothing is frozen while people count. `submit` is one
`@Transactional` method: count row `FOR UPDATE` → SKU advisory locks in sorted order → cartons `FOR UPDATE` → every
counted line judged again (BR-09, else `CYCLE_COUNT_STALE` rolls the whole submission back) → `COMPLETED` →
`StockOperations.requestCountVariance` per differing line: one pending `COUNT_VARIANCE` of the whole carton. At most 500
lines (BR-12). Nothing posts here: approval is the adjustment's own `PENDING_APPROVAL → POSTED`. A carton on an open count,
with a pending `COUNT_VARIANCE`, or with an adjustment asked by hand still pending is busy (`CountBusy`, one predicate
for `claim`; short picks reuse a count through `findHolding`). A blind counter learns the books neither from the view nor
from a refusal (BR-07).

### Returns

Goods that come back never go straight into stock. A return receipt (`ReturnReceiptService`,
`/api/v1/goods-receipts/returns`, 4D-2) is opened for one shipment the carrier returned or one approved return request,
into a QUARANTINE area; it shares `procurement.goods_receipts` but is its own aggregate (`ReturnReceipt`, with
`@SQLRestriction("receipt_type = 'RETURN'")`; `GoodsReceiptJpaEntity` excludes those rows). Cartons are scanned by LPN and
checked against `fulfillment :: api` `ShipmentCartons`. Confirming is one transaction: receipt `FOR UPDATE` →
`CartonReturns.receive` (SKU locks → cartons → rows; each carton CONSUMED → IN_STOCK sealed, its row QUARANTINE in its own
lot) → receipt `CLOSED` (no putaway) → `ReturnedGoodsReceived` after commit. A warehouse manager then releases a
quarantined carton to putaway or writes it off (4D-1).

A return request (`ReturnRequestService`, 4D-3) is approved on its photos and its goods always come back (17 BR-18):
`REQUESTED → AWAITING_RETURN →` (receipt confirmed) `RECEIVED → SETTLEMENT_PENDING → RESOLVED`; `ReturnedGoodsListener`
takes the receipt event after commit, idempotent on the receipt id. No QC, no replacement order, no credit: Sales
propose a refund per line, a second person approves, and `PaymentService.settleReturn` settles it in the same
transaction as an `order_settlement` of kind `GOODS_RETURNED`; the accountant records refunds (4B-9), and each record
publishes `OrderRefundRecorded`.

---

## 5. Known traps — do not reintroduce these

Review rounds during construction found and fixed a long list of defects, all of which compiled
cleanly and most of which passed a single-request happy-path test. That history is not recorded in
the repo, so take the list below as guidance rather than as an audit trail — but every item in it is
verifiable against the code and most are pinned by `BaseLayerRegressionTest`. The pattern matters
more than the list: in this stack, the dangerous failures are silent.

**Spring proxying — the annotation is ignored, nothing warns**
- A non-public `@Scheduled` / `@ApplicationModuleListener` method is not advised. `verify.py` and
  `ArchitectureTest.proxiedMethodsArePublic` check this.
- A controller handler that is not public may not get `@RequiresPermission` applied — an endpoint
  with no authorisation at all.
- Self-invocation bypasses the proxy entirely.
- Package-private `@Transactional` works only under CGLIB. `IdempotencyTransactions` and
  `AuditWriter` are package-private *classes* with *public* methods for exactly this reason.

**Caching**
- `CacheConfig` calls `transactionAware()`, so every cache is wrapped in
  `TransactionAwareCacheDecorator` — which defers `put`, `evict` *and* `clear` to after commit.
  Inside an after-commit callback you must use `evictIfPresent` / `invalidate`, the two methods that
  pass through; `evict` would register a second synchronization the commit loop never runs, and the
  eviction would vanish while the log claimed success.
- `new GenericJackson2JsonRedisSerializer(mapper)` does **not** enable default typing. Without it,
  the second call to any `@Cacheable` method throws `ClassCastException: LinkedHashMap`. The cache
  uses a mapper copy with a restricted `PolymorphicTypeValidator`.
- `CacheConfig` keeps `proxyBeanMethods` ON. With it off, `CachingConfigurer` produces two
  `CacheManager` instances.

**Security / data scope**
- `Specs.eq` returns `all()` for a null value — right for an optional filter, catastrophic for a
  scope. `OWN` scope with a null user id now throws instead of matching every row.
- `@RequiresPermission` is `METHOD`-only. On a TYPE it compiles, passes startup validation and
  enforces nothing, because the pointcut is `@annotation`, not `@within`.
- The guard saves and restores the scope rather than clearing it, so a nested guarded call does not
  wipe its caller's scope.
- `stockflow.security.enabled=false` conditions the guard away, which means no scope is ever set and
  every scoped query would 403. `UnsecuredDataScopeFilter` supplies a system scope in that profile;
  `PermissiveSecurityConfig` does the same job one layer up at the filter chain. Turning security
  "off" without both makes the application *stricter*, not looser.
- `CurrentUserProvider.current()` must never throw. `RequestLoggingFilter` calls it from a
  `finally` block, where an exception replaces the response the application was about to send and
  escapes `@RestControllerAdvice` entirely; `AuditAspect` calls it around a business operation it
  must not fail. A JWT whose `sub` is not a UUID, or whose `scope_level` is unrecognised, is an
  ordinary token shape, not a reason to break the response.
- **Permissions and scope are not in the token** (ADR-0008). They are resolved per request from
  Redis (`RoleAuthorizationCache`, keyed by role + `app_role.version`) and carried on
  `StockflowAuthenticationToken`. Never read a `permissions` or `scope_level` claim — a token issued
  before the change still has them, and they are stale by design.
- **Any write to `identity.role_permission` must advance `app_role.version` in the same
  transaction** — the service does it with `advanceVersion`; a migration that changes seeded grants
  must `UPDATE identity.app_role SET version = version + 1` for those roles, or holders keep the old
  grants for up to an hour. Never `DEL` authorisation keys to "invalidate": moving the version is the
  invalidation, and a delete reopens the race the version exists to close.

**Persistence**
- `BaseEntity.equals`/`hashCode` are final, so they read through `getId()`, never the `id` field —
  on an uninitialised proxy the inherited field is null and an entity would not equal its own proxy.
- `BaseEntity` implements `Persistable`. With an application-assigned id and a primitive
  `@Version`, Spring Data's `isNew()` answers false for everything and `save()` always merges: a
  SELECT before every INSERT, and a returned instance that is not the one passed in.
- Hikari needs `auto-commit: false` alongside `provider_disables_autocommit: true`, or
  `@Transactional` rollback becomes a no-op.

**HTTP**
- The idempotency filter stores only settled outcomes. `isStorable` excludes 408, 409, 423, 425,
  429 and every 5xx, which release the claim instead — caching a "try again" response makes the
  retry it asks for impossible for the full 24-hour window.
- An `IN_PROGRESS` claim has a 5-minute lease (`CLAIM_TIMEOUT`), because the cleanup runs in a
  `finally` that a killed JVM never reaches.
- `GlobalExceptionHandler` lists framework exception types explicitly. Anything missing is swallowed
  by the `Exception.class` catch-all and turns a 400/404/503 into a 500 with an ERROR stack trace.
  Server-fault messages are never returned to the client — they carry filesystem paths and upstream
  service names.
- `CorrelationIdFilter` is at `HIGHEST_PRECEDENCE`, outside Spring Security's `-100`, or 401/403
  bodies carry no correlation id.

---

## 6. Where to look

| | |
|---|---|
| `README.md` | stack, running it, module map, what `common` provides, the two rules |
| `docs/adding-a-module.md` | the checklist for a new module: shape, naming, step by step |
| `docs/adr/0005-modular-monolith.md` | why this is not microservices; ADRs 0001–0003 are what it replaced |
| `docs/adr/0010-rescope-b2b-printed-goods.md` | the 2026-11 rescope: what was removed, what replaced it, why the in-transaction design still holds |
| kltn-docs repository (`docs/`) | **the business analysis, source of truth**: flows, state machines, business rules, open questions |
| `docs/superpowers/plans/2026-11-07-rescope/` | the rescope plans and team plan (Vietnamese), package ids (4A-2, 4C-4, …) used in comments |
| `docs/business-design/` | pre-rescope analysis, kept for history and marked superseded; `db-design/schema.dbml` is current, generated from the migrated database by `tools/db/gen_dbml.py` |
| `tools/db/` | `orphan_check.sql`, the adversarial schema checks `qa/run.sh`, the DBML generator |
| `inventory/**` | the worked example. Every layer is implemented. |
| `tools/verify.py` | the fast static pass; its module docstring lists its checks |
| `.claude/skills/java-design-principles/` | SOLID/DRY/KISS/reuse-first, with a live example in the tree for each — the judgment the fitness functions cannot check |
| `.claude/skills/jpa-orm-review/` | N+1, lazy loading, fetch strategy, pagination, locking, transaction boundaries, and how to MEASURE query counts |

## 7. House rules for changes

- Run `ModularityTest` after any change to packages, `allowedDependencies` or `@NamedInterface`.
  Nothing else in the build notices when a named-interface reference is wrong.
- Never return a JPA entity from a controller, and never put one in `api`.
- State every invariant twice: once in the aggregate, once as a `CHECK` constraint.
- `EnumType.STRING` always. An ordinal enum breaks the day someone reorders the constants.
- Primary keys are `Identifiers.newId()` (UUIDv7). A random UUID scatters inserts across the index.
- If a fitness function is genuinely wrong, change it and write down why — in the class javadoc or a
  new ADR. Deleting a rule to make a build pass is the one thing that is not allowed.
- **Migrations are written by whoever builds the feature; the schema owner (Tú) reviews every one before merge.**
  Add them to `db/migration` with the feature's code (`ddl-auto: validate` means an entity change and its
  migration go in the same PR). Only add: never edit or delete a migration that has merged (Flyway validates
  checksums, and `develop` deploys), and never edit a demo migration — write a new one. Version
  `VyyyyMMddNNNNNN__<schema>_<what>.sql`, **newer than every migration on every branch already merged**, because
  Flyway does not run out of order; a branch carrying older numbers renames before merging, and `verify.py` catches
  duplicates. Removing a feature: clean the leftover rows in the migration (cancel or delete), then drop; no archive and
  no "refuse while rows are in progress" (the VPS database is rebuilt on deploy, owner decision QD-1); keep historical
  enum constants; rewrite SQL functions that mention dropped objects in the same file (Postgres does not track
  dependencies in function bodies); a write to `identity.role_permission` advances `app_role.version` in the same
  file. Every drop or CHECK change gets a lean `*MigrationUpgradeTest` and adversarial SQL under `tools/db/qa/`.
  Read `docs/business-design/db-design/README.md` before writing an entity (its "no one writes migrations" line is
  from September and no longer true).
- Cross-module references are foreign keys (ADR-0007). Validate a reference in the service first
  (404/409 with a real error code); tests use `support/DemoData` or `support/ReferenceRows`, never an
  invented id for another module's row.
- **Never `git commit` or `git push` without asking the repository owner first.** Editing the working
  tree is fine; putting changes into history or onto the remote requires an explicit go-ahead. This
  applies to any automated assistant working in this repo.
