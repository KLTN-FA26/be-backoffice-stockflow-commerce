# Adding a module

Everything the platform already provides, and the order to do things in. Follow it and a new module
inherits security, caching, auditing, idempotency, paging and error handling without writing any of
them.

Read [ADR-0005](adr/0005-modular-monolith.md) first if you have not. This document assumes you know
why the boundaries exist.

---

## 0. Before any code

Two decisions, and both are hard to change later.

**What is the aggregate root?** The thing that must be consistent in one transaction. Everything
inside it is loaded and saved with it and has no repository of its own.

**Who may this module depend on?** Write it in `package-info.java` and keep it as short as you can.
Every name you add is a coupling `ModularityTest` will then permit for ever.

```java
/**
 * <b>Suppliers, purchase orders, goods receipt, QC</b>
 *
 * <p>WBS 3.11 · database schema {@code procurement}</p>
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"product :: api", "warehouse :: api"})
package com.stockflow.procurement;
```

Note the `:: api` suffix. Each module publishes its API as a **named interface** (see `## 1. The
shape`), and a bare `"product"` would refer to the module's base package instead — which holds
nothing but `package-info.java`, so the dependency would resolve to nothing and the import would be
rejected. Always name the interface.

`common` and `contracts` are available to everyone and are not listed.

---

## 1. The shape

```
procurement/
├── package-info.java              @ApplicationModule(allowedDependencies = {"product :: api"})
│
├── api/                            the PUBLIC API. Other modules see only this package.
│   ├── package-info.java           @NamedInterface("api")  <- without it the package is PRIVATE
│   ├── ProcurementService.java    ─┐ Records and interfaces only.
│   ├── PlacePurchaseOrderCommand.java │ Never an entity, never a domain object.
│   ├── PurchaseOrderSummary.java   │
│   └── PurchaseOrderStatus.java   ─┘
│
└── internal/                       invisible to every other module
    ├── domain/          aggregate, value objects, repository PORT. No Spring, no JPA.
    ├── service/         application services, listeners, schedulers. The transaction boundary.
    ├── entity/          JPA entities. The table mapping, and nothing else.
    ├── repository/      Spring Data repositories, port adapters, entity <-> domain mappers.
    └── controller/      REST controllers and their request/response DTOs.
```

`inventory` is the worked example — every layer is implemented there. Copy its structure.

### Deciding what goes in `api` and what goes in `internal`

There is a mechanical rule, so this never has to be an opinion:

> **`api` holds the service interface, plus exactly the types that appear in its method
> signatures, plus the types those drag in. Everything else goes in `internal`.**

Check it on `inventory`. The interface is:

```java
int                      availableToPromise(Sku sku);
List<StockAvailability>  availabilityOf(Sku sku);
ReserveStockResult       reserve(ReserveStockCommand command);
void                     release(UUID reservationId, String reason);
```

Types named there: `Sku` (from `common`), `StockAvailability`, `ReserveStockResult`,
`ReserveStockCommand`. Open `ReserveStockResult` and it holds a `List<StockReservation>`, which
drags in `StockReservation`. That is five files, and five files are what `inventory/api` contains.

`StockItem` — the aggregate, the most important class in the module — is not among them, because it
appears in no signature. Neither does `Quantity`, `ReservationStatus`, or `StockItemRepository`.

The rule also tells you when something has to *move*. `release` currently takes a `String reason`,
which is why `internal/domain/ReleaseReason` can stay private. Change the signature to
`release(UUID, ReleaseReason)` and that enum must move to `api` the same day — no discussion needed.

If you find yourself wanting to put an entity or an aggregate in a signature, that is a design
signal, not a reason to widen `api`. Handing another module an aggregate lets it call behaviour on
that aggregate outside a transaction and outside its invariants. Return a record instead.

**Two failure modes this layout has, both caught by the build:**

| Mistake | What catches it |
|---|---|
| Forgot `@NamedInterface("api")` | the first module to import from your `api` fails `ModularityTest` |
| Left a class loose in the module base package | `verify.py` reports `STRAY PUBLIC TYPE` |
| `api` type references something in `internal` | `ArchitectureTest.theApiPackageLeaksNothingInternal` |

---

## 2. Naming

Three rules, and knowing which one applies is the whole of it.

### Rule 1 — a class with a framework role carries that role as a suffix

If Spring, JPA or the HTTP layer treats the class specially, say so in the name. The suffix is not
decoration: it tells the reader what the lifecycle is, whether a proxy wraps it, and whether calling
a method on it can open a transaction.

| Role | Pattern | In this codebase |
|---|---|---|
| Module entry point | `<Module>Service` | `InventoryService`, `OrderService` |
| Its implementation | `<Module>ServiceImpl` | `InventoryServiceImpl`, `OrderServiceImpl` |
| REST controller | `<Aggregate>Controller` | `InventoryController`, `OrderController` |
| Spring Data repository | `<Aggregate>JpaRepository` | `StockItemJpaRepository` |
| JPA entity | `<Aggregate>JpaEntity` | `StockItemJpaEntity`, `OrderJpaEntity` |
| Port implementation | `<Aggregate>RepositoryAdapter` | `StockItemRepositoryAdapter` |
| Entity ⇄ domain mapper | `<Aggregate>PersistenceMapper` | `StockItemPersistenceMapper` |
| DTO ⇄ api mapper | `<Module>WebMapper` | `InventoryWebMapper`, `OrderWebMapper` |
| Query projection | `<Noun>Row` | `AvailabilityRow` |
| Scheduled job | `<Noun>Sweeper` / `<Noun>Retention` | `ReservationSweeper`, `AuditRetention` |
| Event listener | `<SourceModule>EventListener` | `PaymentEventListener` |
| Event publisher | `<Module>EventPublisher` | `OrderEventPublisher` |

The module prefix earns its place on `Service` and `Controller` because at a call site you see the
bare class name and there are fourteen of each. It does **not** earn its place anywhere the name is
already unambiguous — `com.stockflow.inventory.api.InventoryAvailability` says "inventory" twice.

### Rule 2 — a value carries no suffix at all

Aggregates, value objects, and the records crossing a boundary are named for **what they are**, in
the language of the business. No `...Dto`, no `...Model`, no `...VO`.

```java
StockItem item;        // "the stock on one shelf"      NOT StockItemModel
Quantity quantity;     // "a quantity"                  NOT QuantityValueObject
StockAvailability row; // "what is available"           NOT AvailabilityDto
```

The second column is what the code is *about*; the first is what Java is doing. A domain that reads
like a warehouse is the entire reason `internal/domain` has no framework on it, and a naming
convention that reintroduces Java vocabulary throws that away for nothing.

Three suffixes are the exception, because they name a **position in a use case** rather than a
technology — which is information the reader needs and cannot get elsewhere:

| Position | Pattern | Example |
|---|---|---|
| Input to a use case | `<Verb><Noun>Command` | `ReserveStockCommand`, `PlaceOrderCommand` |
| Outcome of that use case | `<Verb><Noun>Result` | `ReserveStockResult` |
| Read model returned by a query | `<Noun>Summary` | `OrderSummary` |
| HTTP request / response body | `<Verb><Noun>Request`, `<Noun>Response` | `ReserveStockRequest`, `ReservationResponse` |

A `Command` and its `Result` **must share a stem**, so the pair is visible at a glance:

```java
ReserveStockResult reserve(ReserveStockCommand command);   // ✅ obviously a pair
ReservationResult  reserve(ReserveStockCommand command);   // ❌ two vocabularies, one operation
```

### Rule 3 — one word per concept, across every layer

Pick the word once and use it in the migration, the domain, the api and the URL. This codebase says
**reservation** everywhere:

```
inventory.stock_reservation      table
Reservation, ReservationStatus   domain
StockReservation                 api
/reservations                    URL
StockReserved                    event
```

A second word for the same thing — "hold" alongside "reservation" — costs every future reader a
lookup to confirm they are the same, and the answer is never written down anywhere. The tell is
usually a field: a type called `StockHold` whose first component is `reservationId` has already
admitted which word won.

This is the rule that is cheapest to follow on day one and most expensive to fix on day ninety, and
no test can enforce it.

## 3. What you get for free

| Need | Use | Do not |
|---|---|---|
| Primary key | `Identifiers.newId()` | `UUID.randomUUID()` — fragments the index |
| Entity base | `extends BaseEntity` | hand-roll id, version, equals/hashCode |
| Hide instead of delete | `extends SoftDeletableEntity` | see the two traps in its javadoc |
| Money column | `MoneyEmbeddable` + `@AttributeOverrides` | a `BigDecimal` with no currency |
| SKU column | `@Convert(converter = SkuConverter.class)` | a bare `String` nothing validates |
| Repository | `extends BaseJpaRepository<E>` | plain `JpaRepository` — no `Specification` support |
| Row-level access | `extends ScopedJpaRepository<E>` + `implements ScopedEntity` | filter by hand in each query |
| Filters | `Specs.eq/contains/in` | `if (x != null) spec = spec.and(...)` chains |
| Paging | `Pages.of(...)`, `Pages.toResponse(...)` | accept an unbounded `size` |
| Sorting | `SortWhitelist.of(...)` | pass the client's `sort` string through |
| Errors | throw `NotFoundException`, `ConflictException`, `BusinessException` | build `ResponseEntity` in a controller |
| Permissions | `@PermissionResource` + `@RequiresPermission` | check roles inside a service |
| Caching | `@Cacheable(CacheNames.PRODUCTS)` | a string literal, or caching stock |
| Cache invalidation | `TransactionalCacheEvictor.evictAfterCommit(...)` | `@CacheEvict` — evicts before commit |
| Audit | `@Auditable(action = ..., resourceType = ...)` | write to `audit_log` yourself |
| Throttling | `@RateLimit(limit = 5, perSeconds = 60)` | |
| Distributed job | `@SchedulerLock(name = "...")` on `@Scheduled` | assume one instance |
| Ad-hoc lock | `DistributedLock` | — but prefer a row lock, see its javadoc |
| Files | `FileStorage` | write to the filesystem |
| Outbound HTTP | `RestClientFactory.forService(...)` | `RestClient.create()` — no timeout |
| Time | inject `Clock` | `Instant.now()` — untestable |
| The signed-in user | `@AuthenticatedUser CurrentUser user` | inject `CurrentUserProvider` and unwrap |
| Responses | `ApiResponse.ok(...)`, `Pages.toResponse(...)` | build your own shape |
| User-facing messages | add a key to `i18n/messages*.properties` | a hard-coded English string |
| Phone / SKU / text | `@VietnamPhone`, `@ValidSku`, `@NotBlankTrimmed` | `@Pattern` copied per DTO |

---

## 4. Step by step

### 4.1 Migration first

`src/main/resources/db/migration/V<yyyyMMddHHmmss>__procurement_purchase_order.sql`

Timestamped, not `V7__`. With five people committing in parallel, sequential numbers collide daily.

```sql
CREATE TABLE procurement.purchase_order
(
    id               UUID         NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,   -- BaseEntity
    created_at       TIMESTAMPTZ  NOT NULL,             -- AuditableEntity
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),
    ...
    CONSTRAINT pk_purchase_order PRIMARY KEY (id)
);
```

Three rules from ADR-0005: your module writes only its own schema, no foreign key crosses a schema,
no JOIN crosses a schema.

State every invariant **twice** — once in the aggregate, once as a `CHECK`. The domain gives the
good error message; the constraint is what a bad migration or a manual `UPDATE` cannot talk its way
past.

### 4.2 Domain — no framework

```java
public final class PurchaseOrder extends AggregateRoot {
    // final: aggregates have no subtypes, and it silences the this-escape lint
    private final PurchaseOrderId id;
    private PurchaseOrderStatus status;

    public void approve(UserId approver, Instant now) {
        if (!status.canTransitionTo(APPROVED)) {
            throw new IllegalStateException("...");   // handled as 400
        }
        this.status = APPROVED;
        registerEvent(new PurchaseOrderEvent.Approved(...));
    }
}
```

No annotations. That is what lets `PurchaseOrderTest` run in milliseconds with no container, and
`ArchitectureTest` enforces it.

The repository port lives here too, and speaks the domain's language — no `Page`, no
`Specification`, nothing from Spring Data:

```java
public interface PurchaseOrderRepository extends AggregateRepository<PurchaseOrder, PurchaseOrderId> {
    List<PurchaseOrder> findAwaitingApproval();
}
```

### 4.3 Persistence

```java
@Entity
@Table(name = "purchase_order", schema = "procurement")
class PurchaseOrderJpaEntity extends BaseEntity implements ScopedEntity {

    static final PurchaseOrderJpaEntity SCOPE_PROTOTYPE = new PurchaseOrderJpaEntity();

    @Enumerated(EnumType.STRING)          // never ORDINAL
    @Column(name = "status", nullable = false, length = 32)
    private PurchaseOrderStatus status;

    @Override public String ownerAttribute()     { return "buyerId"; }
    @Override public String warehouseAttribute() { return "warehouseCode"; }
}
```

`BaseEntity` is for **aggregate root** tables. Child entities inside an aggregate keep a plain
`@Id` and no version — they are only ever written through their root, whose version already guards
the whole aggregate.

Mapper hand-written; adapter implements the port and is the only class that speaks both languages.

### 4.4 Application — the transaction boundary

```java
@Service
@Transactional
class ProcurementServiceImpl implements ProcurementService {
    // package-private class, public interface: nobody can bypass the port
}
```

Three things that are easy to get wrong and cost hours:

- **`@Transactional` on a package-private method does nothing.** Spring's proxy advice applies to
  public methods only. It fails silently — the method runs in whatever transaction the caller had.
  The same trap applies to `@RequiresPermission` and `@Auditable`: **controller handler methods must
  be `public`**, or the guard is skipped and the endpoint has no authorisation at all.
- **A method calling its own `@Transactional` method bypasses the proxy.** Different propagation
  needs a different bean; see `IdempotencyTransactions` for the pattern.
- **Direct call or event?** Call when you need the result inside your transaction; publish an event
  when you are announcing a fact and do not care who reacts. `OrderServiceImpl` and
  `order.PaymentEventListener` document both sides.

### 4.5 Web

```java
@RestController
@RequestMapping("/api/v1/purchase-orders")
@PermissionResource(
        code = ProcurementResources.PURCHASE_ORDERS,   // hyphens only: [a-z0-9-]{2,64}
        group = "Procurement", label = "Purchase orders",
        route = "/procurement/purchase-orders",
        apiPath = "/api/v1/purchase-orders",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.APPROVE})
class PurchaseOrderController {

    @PostMapping("/{id}/approval")
    @RequiresPermission(resource = ProcurementResources.PURCHASE_ORDERS,
            action = Action.APPROVE, scope = DataScope.WAREHOUSE)
    @Auditable(action = AuditAction.APPROVE, resourceType = "purchase-order", resourceId = "#id")
    ApiResponse<Void> approve(@PathVariable UUID id) { ... }
}
```

`@Auditable` on a controller method commits its entry in its own transaction; on the
`@Transactional` service method it commits with the change. For approvals and money, prefer the
service method — see `AuditAspect`.

`@RequiresPermission` does two things: it checks the permission, and it establishes the
`DataScope` that `ScopedJpaRepository` reads much further down. Forget it on a list endpoint and the
scoped query throws — loudly, on the first call, which is the point.

`PermissionCatalogValidator` fails startup if a guard names a resource no `@PermissionResource`
declares. A permission nobody can grant is an endpoint nobody can reach.

### 4.6 Tests

| Write | For |
|---|---|
| plain JUnit on the aggregate | every business rule — cheap, so write many |
| `@ApplicationModuleTest` | the module boots alone, with no undeclared dependency |
| `@IntegrationTest` + `PostgresContainer` | anything about transactions, SQL or event delivery |
| `+ RedisContainer` | cache, rate limiting, distributed lock — their correctness is in Lua |
| `TestUsers` + `WithCurrentUser` | data scope, without going through HTTP |

Adding an `ErrorCode`? Add its key to **both** `i18n/messages.properties` and `messages_vi.properties`
in the same commit — `MessageBundleTest` fails otherwise, which is the only thing that catches a
forgotten translation (a missing key falls back to English silently at runtime).

`ModularityTest` and `ArchitectureTest` run automatically. If either fails, the message names the
class and the boundary — read it before changing the rule.

---

## 5. Checklist

- [ ] `package-info.java` with the shortest `allowedDependencies` that works, each entry `"<module> :: api"`
- [ ] `api/package-info.java` carries `@NamedInterface("api")`, and the module base package holds only `package-info.java`
- [ ] Names follow `## 2. Naming` — role suffix where there is a role, plain nouns for values, `Command`/`Result` sharing a stem, one word per concept
- [ ] Migration: timestamped, own schema, no cross-schema FK, invariants as `CHECK`
- [ ] Aggregate is `final`, has no framework annotation, protects its invariants
- [ ] Entity extends `BaseEntity`; enums are `EnumType.STRING`
- [ ] `ScopedEntity` + `ScopedJpaRepository` if rows belong to someone
- [ ] `@SQLRestriction` if soft-deletable, and unique indexes are partial on `deleted_at IS NULL`
- [ ] `@Transactional` methods are **public**
- [ ] `Clock` injected, not `Instant.now()`
- [ ] `@PermissionResource` + `@RequiresPermission(scope = ...)` on every endpoint
- [ ] Paging through `Pages`, sorting through `SortWhitelist`
- [ ] `@Auditable` on approvals, money, permissions, exports, deletes
- [ ] `mvn test` green, including `ModularityTest` and `ArchitectureTest`

---

## 6. When something does not fit

The base is a starting point, not a cage. If a module genuinely needs something different — a
different lock strategy, a cache with unusual semantics, a repository that cannot be scoped —
that is fine. Write down **why**, in the class javadoc or a new ADR, and change the fitness function
deliberately rather than working around it.

What is not fine is disabling `ModularityTest` or `ArchitectureTest` to unblock a build. Those two
files are the only thing standing between this design and a ball of mud; the moment they are
advisory, ADR-0005 is void.
