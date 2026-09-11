# StockFlowCommerce — modular monolith

Inventory management and e-commerce for a furniture manufacturer.
Capstone **FA26SE029** · group **GFA26SE03** · supervisor **Nguyễn Minh Sang**.

One Spring Boot application, one Postgres database, **fourteen modules whose boundaries the build
enforces**. It is not a monolith with hopeful package names — a boundary violation fails
`ModularityTest`, in the pull request.

The previous architecture was fourteen microservices. Why it changed, what that bought and what it
cost, is [ADR-0005](docs/adr/0005-modular-monolith.md) — read that first if you only read one file.

---

## Stack

| | |
|---|---|
| Java | 21 (records, sealed interfaces, pattern matching, virtual threads) |
| Spring Boot | 3.5.0 |
| Spring Modulith | 1.4.1 — module verification, event publication registry, docs generation |
| Database | PostgreSQL 16, one database, one schema per module |
| Migrations | Flyway, timestamped versions |
| Auth | Spring Security OAuth2 resource server (JWT) + action-per-resource permissions |
| Tests | JUnit 5, AssertJ, ArchUnit, Testcontainers, `spring-modulith-starter-test` |
| Build | Maven |

---

## Running it

```bash
cp .env.example .env                 # every variable the app reads, documented
docker compose up -d                 # Postgres, Redis, Elasticsearch, MinIO, Jaeger, Mailpit
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

Or run everything in containers, which is also what proves the Dockerfile still works:

```bash
docker compose --profile app up -d --build
```

| | |
|---|---|
| API docs | http://localhost:8080/swagger-ui.html |
| Module structure (live, from the running app) | http://localhost:8080/actuator/applicationmodules |
| Traces | http://localhost:16686 |
| Captured email | http://localhost:8025 |
| Health, incl. object storage | http://localhost:8080/actuator/health |
| Which build is running | http://localhost:8080/actuator/info |

Swagger UI has an **Authorize** button — paste an access token and every protected endpoint is
callable from the browser.

The `local` profile adds `db/demo`, which seeds stock so the endpoints return something:

```bash
curl "localhost:8080/api/v1/inventory/stock-items/atp?sku=SOFA-3S-GREY"
```

---

## The module map

```
com.stockflow
├── StockFlowApplication.java     @Modulithic(sharedModules = {"common", "contracts"})
│
├── common/          OPEN · value objects, errors, security, auditing, web plumbing
├── contracts/       OPEN · cross-module event records — flat, importing nothing
│
│                 every module below has the same two halves: api/ (public, named interface)
│                 and internal/ (private: controller, service, repository, entity, domain)
│
├── identity/        Users, roles, permissions, sessions              → schema identity
├── customer/        Profiles, addresses, segments                    → schema customer
├── product/         Product master, variants, attributes, BOM        → schema product
├── catalog/         Storefront catalog, pricing, promotions, search  → schema catalog
├── inventory/       Stock, ATP, reservations, counts, transfers      → schema inventory
├── warehouse/       Warehouses, zones, bins, capacity                → schema warehouse
├── order/           Cart, orders, amendments, RMA                    → schema ordering
├── payment/         Payments, deposits, refunds, reconciliation      → schema payment
├── fulfillment/     Picking, packing, shipments, tracking            → schema fulfillment
├── procurement/     Suppliers, POs, goods receipt, QC                → schema procurement
├── design/          Design studio, templates, snapshots, print jobs  → schema design
├── chat/            Conversations, messages, agent assignment        → schema chat
├── notification/    Templates, preferences, delivery log             → schema notification
└── reporting/       Read models and dashboards                       → schema reporting
```

### The layers, and where they live

The usual layers are all here — they sit **inside each module** rather than at the root:

```
com.stockflow.inventory/               <- the module: one business capability
├── package-info.java                     @ApplicationModule(allowedDependencies = {"warehouse :: api"})
│
├── api/                               <- everything other modules may import, and nothing else
│   ├── package-info.java                 @NamedInterface("api")
│   ├── InventoryService.java             the interface. 4 methods.
│   ├── ReserveStockCommand.java          the types in those method signatures
│   ├── ReserveStockResult.java
│   ├── StockAvailability.java
│   └── StockReservation.java
│
└── internal/                          <- invisible to other modules
    ├── controller/   InventoryController, request/response DTOs      (the controller layer)
    ├── service/      InventoryServiceImpl, listeners, schedulers     (the service layer)
    ├── repository/   StockItemJpaRepository, adapters, mappers       (the repository layer)
    ├── entity/       StockItemJpaEntity, ReservationJpaEntity        (the entity layer)
    └── domain/       StockItem, Quantity, StockAllocator             (plain Java. No Spring, no JPA.)
```

`api` and `internal` are the two halves of every module. `api` is a **named interface**: Spring
Modulith exposes a module's base package by default and treats nested packages as internal, so
`api` needs `@NamedInterface("api")` to be public at all — and that is why a dependency on this
module is spelled `"inventory :: api"` rather than `"inventory"`.

What goes in `api` is decided mechanically, not by taste: **the service interface, plus exactly the
types that appear in its method signatures, plus what those drag in.** For `inventory` that is five
files and no others. `StockItem` is the module's most important class and it is *not* there,
because it appears in no signature.

If you are used to `controller/` `service/` `repository/` `entity/` at the top level, this is the
same set of layers with the nesting the other way round: **module first, then layer**.

The reason is the one thing a top-level layout cannot give you. With every controller in one
package, nothing prevents `OrderController` from reaching into stock rows directly; the coupling is
invisible until the day you try to split something out. Here, `order` may only touch
`inventory`'s public API, `@ApplicationModule(allowedDependencies = ...)` states exactly which
modules it may touch, and `ModularityTest` fails the build when the code and that declaration
disagree. `ArchitectureTest` enforces the layering *within* a module on top of that — a controller
that imports an entity does not compile past the test suite.

`domain/` is the layer that has no equivalent in the classic layout, and it is where the business
rules live: pure objects with no framework on them, so `StockItemTest` runs in milliseconds with no
database and no Spring context.

### What `common` already provides

Do not rebuild any of this. [`docs/adding-a-module.md`](docs/adding-a-module.md) is the full table;
the short version:

| | |
|---|---|
| `id` | `Identifiers.newId()` — time-ordered UUIDv7, so inserts append instead of scattering the index |
| `persistence` | `BaseEntity`, `SoftDeletableEntity`, `MoneyEmbeddable`, `SkuConverter`, `BaseJpaRepository`, `Specs`, `Pages`, `SortWhitelist` |
| `security` | permission matrix **and** row-level `DataScope` — `ScopedEntity` + `ScopedJpaRepository` |
| `cache` | Redis, per-cache TTLs in `CacheNames`, `TransactionalCacheEvictor`, survives a Redis outage |
| `idempotency` | any request carrying `Idempotency-Key` becomes safe to retry |
| `ratelimit` | `@RateLimit` on an endpoint, Redis token bucket |
| `audit` | `@Auditable` → append-only `platform.audit_log`, split retention |
| `storage` | `FileStorage` port, S3/MinIO and local, content-type allow-list with magic-number check |
| `lock` | `DistributedLock`, and ShedLock so `@Scheduled` is safe on more than one instance |
| `http` | `RestClientFactory` — timeouts, correlation propagation, call logging |
| `validation` | `@ValidSku`, `@VietnamPhone`, `@NotBlankTrimmed` |
| `web` | correlation ids, request logging, one exception handler for the whole platform |
| `api` | the response envelope: `fieldErrors` a form can place, `correlationId` for support |
| `i18n` | Vietnamese by default, English on `Accept-Language`; every `ErrorCode` translated |
| `config` | Jackson, async MDC propagation, OpenAPI + JWT, locale, MVC, clock |

### And the parts that are not Java

| | |
|---|---|
| `Dockerfile` | multi-stage, layered, non-root, container-aware JVM flags |
| `logback-spring.xml` | readable in dev, JSON with queryable `correlationId` everywhere else |
| `application-prod.yml` | no secrets, no defaults on credentials — a missing one fails startup |
| `.env.example` | every variable, with what breaks if it is wrong |
| `.github/workflows/build.yml` | compile → boundaries → tests → image; migrations checked append-only |
| `.editorconfig`, `.mvn/wrapper` | one style and one Maven version for five people |

Every module has the same shape — see [The layers, and where they live](#the-layers-and-where-they-live)
above for the annotated version:

```
inventory/
├── package-info.java        @ApplicationModule(allowedDependencies = {"warehouse :: api"})
│
├── api/                     @NamedInterface("api") · the only package other modules may import
│
└── internal/                invisible to every other module
    ├── domain/              aggregates, value objects, ports · no Spring, no JPA
    ├── service/             application services, listeners, schedulers · transaction boundary
    ├── repository/          Spring Data repositories, port adapters, mappers
    ├── entity/              JPA entities · the table mapping, nothing else
    └── controller/          REST controllers and their DTOs
```

Dependencies point inwards: `controller → service → domain`, and `repository → entity → domain`.
`api` depends on none of them. `ArchitectureTest` enforces each arrow.

---

## The two rules that make it work

### 1. `internal` is genuinely private

```java
// order/internal/service/OrderServiceImpl.java
import com.stockflow.inventory.api.InventoryService;             // ✅ the published port
import com.stockflow.inventory.internal.domain.StockItem;        // ❌ ModularityTest fails the build
```

The compiler allows both — it is all one jar. The build does not.

### 2. Direct call, or event?

| | Direct call | Event |
|---|---|---|
| **When** | you need the result inside your transaction | you are announcing a fact |
| **Coupling** | caller declares the dependency | publisher knows nobody |
| **Example** | `order → inventory.reserve()` | `payment` publishes `PaymentCaptured` |

`order → inventory` is a call: an order without its stock is a bug, so the two must commit
together. `payment → order` is an event: payment succeeded whatever order does next, and a direct
call would create a cycle, since order already listens to payment.

---

## The central claim, and its test

Placing an order and reserving its stock are **one transaction**:

```java
@Transactional
public OrderSummary placeOrder(PlaceOrderCommand command) {
    Order order = Order.draft(...);
    for (OrderLine line : order.lines()) {
        ReserveStockResult r = inventory.reserve(...);      // joins THIS transaction
        order.attachReservations(line.id(), r.reservationIds());
    }
    order.submit();
    return toSummary(repository.save(order));
}
```

(`reservationIds`, plural: a line for ten units is often drawn from two lots, and each lot is a
separate hold. Keeping only the first would release half the stock on cancellation and leave the
rest held until it expired.)

Under the microservices design this same step needed an outbox row, a Kafka topic, an idempotent
consumer, two response events, a saga state row, a timeout, and a compensating release — and still
left a window in which an order existed with no stock behind it.

`PlaceOrderIntegrationTest.stockIsRolledBackWhenTheOrderFails()` places an order, asserts the stock
is held, throws, and asserts the stock is back. Against a real Postgres. That test is the
architecture's argument, and it is the test that could not have been written before.

---

## Tests

| Class | What it protects |
|---|---|
| `ModularityTest` | module boundaries, declared dependencies, absence of cycles — **the most important file here** |
| `ArchitectureTest` | layering inside a module, plus the traps in the shared base: a soft-deletable entity without `@SQLRestriction`, a scoped entity behind an unscoped repository, a cache named by string literal, a random UUID used as a primary key, a `@Scheduled` job without `@SchedulerLock`, a proxied method that is not public |
| `BaseLayerRegressionTest` | the base-layer defects found in review — entity equality under a lazy proxy, `isNew()` with an application-assigned id, `OWN` scope with no user id, a nested permission guard clearing its caller's scope |
| `StockItemTest`, `OrderTest`, `StockAllocatorTest` | business rules, as plain unit tests with no container — milliseconds, because the domain has no framework on it |
| `PlaceOrderIntegrationTest` | the atomicity claim, against a real Postgres |
| `InventoryEventIntegrationTest` | cross-module event delivery and listener idempotency |
| `InventoryModuleTest` | inventory boots **alone**, with the other thirteen modules absent |
| `MessageBundleTest` | every `ErrorCode` is translated in both bundles, and they agree |
| `ApiEnvelopeTest` | the response shape every client depends on |

Test support in `support/`: `PostgresContainer`, `RedisContainer` (needed for the Lua in the rate
limiter and the lock), `TestUsers` and `WithCurrentUser` for exercising `DataScope` below the HTTP
layer, `RecordedEvents`, `Await`.

```bash
mvn test                              # everything
mvn test -Dtest=ModularityTest        # just the boundaries
mvn verify                            # tests plus the JaCoCo report
```

`tools/verify.py` is a dependency-free static pass over the source tree — package↔directory, module
boundaries, `allowedDependencies`, `@Scheduled` without `@SchedulerLock`, proxied methods that are
not public, and a dozen more. It needs no Maven and no network, runs in under a second, and is the
cheapest thing to put in a pre-commit hook:

```bash
python3 tools/verify.py
```

`ModularityTest` is the one to run after any change to a module's package layout,
`allowedDependencies`, or `@NamedInterface` — it is what actually resolves `"inventory :: api"`
against the code, and nothing else in the build will notice if that resolution is wrong.

Integration tests need Docker running, for Testcontainers.

`.mvn/wrapper/maven-wrapper.properties` pins Maven 3.9.9 for the team. The wrapper *scripts* are not
committed — generate them once and commit them if you want `./mvnw`:

```bash
mvn wrapper:wrapper -Dmaven=3.9.9 -DdistributionType=only-script
```

`ModularityTest.generateDocumentation()` writes C4 diagrams, PlantUML and a per-module canvas to
`target/spring-modulith-docs`. Generated from the code, so it cannot drift from it — which is also
why the report's architecture diagrams come from there rather than from a drawing tool.

---

## Database conventions

One database, `stockflow`; one schema per module. Three rules, and the reasoning is in
[ADR-0005](docs/adr/0005-modular-monolith.md):

1. A module reads and writes **only its own schema**.
2. **No foreign key crosses a schema.** Cross-module references are plain UUID columns.
3. **No JOIN crosses a schema.** Need another module's data? Call its service.

Rule 2 gives up database-level referential integrity across modules and buys the ability to extract
a module later without untangling a web of constraints.

Migrations are timestamped, `V20260901000200__inventory_stock.sql`, not `V2__`. With five people
committing in parallel, sequential numbers collide constantly.

Spring Modulith's `event_publication` table lives on the default search path rather than in a
schema of its own: the JPA event registry maps it to an unqualified name, and moving it would leave
`ddl-auto: validate` hunting for a table that is not where the entity says it is.

Invariants are stated **twice** on purpose — once in the aggregate, once as a `CHECK`. The domain
produces the good error message; the database is what a bad migration or a manual `UPDATE` cannot
talk its way past. `reserved <= on_hand` is the anti-overselling rule, and it is enforced in both
places.

---

## Where to start reading

1. [`docs/adr/0005-modular-monolith.md`](docs/adr/0005-modular-monolith.md) — the decision and its price
2. `inventory/` — the reference module, fully implemented through all four layers
3. `order/internal/service/OrderServiceImpl.java` — the saga collapse, with the distributed version described in the javadoc
4. [`docs/adding-a-module.md`](docs/adding-a-module.md) — the recipe for the next module, and what not to rebuild
5. `docs/business-design/` — data flows, state machines, 50 business rules, per-step storage
6. `ModularityTest` — the twenty lines that keep all of the above true
