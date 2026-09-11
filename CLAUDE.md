# StockFlowCommerce — working notes for Claude Code

FPT University capstone **FA26SE029**, group **GFA26SE03**, 5 members, supervisor Nguyễn Minh Sang.
A warehouse + e-commerce platform for a Vietnamese furniture business.

Read this before touching anything. It is short on purpose; it says what is not obvious from the
code, and points at the code for the rest.

---

## 1. Read this first: what has and has not been verified

**`mvn test` has never run against this code.** Not once. The environment it was written in had no
access to Maven Central, so nothing was ever resolved, compiled by Maven, or executed by JUnit.

What *was* done instead, and what it is worth:

| Check | Covers | Does not cover |
|---|---|---|
| `javac` against a ~300-file hand-written stub classpath | every type resolves, every signature is real | anything the stubs got wrong |
| `tools/verify.py` — **committed, run it** | package↔path, module boundaries, `allowedDependencies`, unused imports, `@Scheduled` without `@SchedulerLock`, non-public proxied methods, Flyway version collisions, entity↔migration, `@NamedInterface` | anything needing a running context |
| standalone runtime assertions, run outside Maven | UUIDv7 monotonicity under 16 threads, data-scope arithmetic, cache key generation, validators, the API envelope | Spring wiring, JPA mapping, SQL |

The runtime assertions were throwaway harnesses and are not in the repo; what survived them is
`src/test/java/com/stockflow/common/BaseLayerRegressionTest.java`, which pins the same cases as
proper JUnit and *will* run under Maven.

So: **treat a green reading of this codebase as unproven.** The highest-value thing you can do is
get `mvn test` to run and report what actually breaks. Expect the failures to cluster in Spring
context startup and JPA mapping, because those are exactly what nothing above could reach.

```bash
python3 tools/verify.py                   # no Maven, no network, under a second
mvn test                                  # everything
mvn test -Dtest=ModularityTest            # module boundaries only — run after ANY package change
mvn test -Dtest=ArchitectureTest          # 18 layering/convention rules
mvn verify                                # tests + JaCoCo
docker compose up -d                      # postgres, redis, elasticsearch, minio, jaeger, mailpit
docker compose --profile app up           # the above plus the application container
```

Integration tests need Docker (Testcontainers: Postgres + Redis).

The Maven wrapper *scripts* are not committed; `.mvn/wrapper/maven-wrapper.properties` pins 3.9.9.
Generate them with `mvn wrapper:wrapper -Dmaven=3.9.9 -DdistributionType=only-script` if you want
`./mvnw`.

---

## 2. What is actually built

**2 of 14 business modules are implemented end to end, 1 is partial, 11 are empty skeletons.** A
skeleton is a `package-info.java` with `@ApplicationModule`, an `api/` package holding only its own
`package-info.java`, and the five empty layer directories — 7 files, all of them `package-info`.
They exist so the module map and the dependency declarations are real, and so adding a module is a
copy rather than a decision.

| Module | State |
|---|---|
| `inventory` | Implemented end to end. **This is the reference module — copy it.** |
| `order` | Implemented end to end, demonstrates the collapsed saga |
| `common`, `contracts` | Implemented: the shared base layer (~9,400 LOC) and the event records |
| `notification` | **Partial** — `OrderNotificationListener` (listens to `OrderPlaced`, `PaymentFailed`, `ShipmentDispatched`) and `NotificationSender`. No api, no persistence. |
| the other 11 | Skeleton only |

The shared base layer in `common/` is finished and deliberately thorough — security, caching,
idempotency, rate limiting, auditing, storage, locking, validation, i18n, the API envelope, the
exception handler. **Do not rebuild any of it inside a module.** `README.md` has the table of what
is there; `docs/adding-a-module.md` §3 has the "use this, not that" list.

Migrations: 5 Flyway files + 1 demo seed, 10 tables. Nine are split across `inventory` (2),
`ordering` (4) and `platform` (3). The tenth, `event_publication`, is deliberately unqualified in
`public` — Spring Modulith owns it and `V20260901000400` explains why it gets no schema.

---

## 3. Architecture in one screen

Modular monolith on Spring Boot 3.5 / Spring Modulith 1.4 / Java 21 / PostgreSQL 16.
One database, **one schema per module**, no cross-schema foreign key, no cross-schema JOIN.

```
com.stockflow.inventory/
├── package-info.java     @ApplicationModule(allowedDependencies = {"warehouse :: api"})
├── api/                  @NamedInterface("api")  ← the ONLY package other modules may import
│   InventoryService, ReserveStockCommand, ReserveStockResult, StockAvailability, StockReservation
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

`docs/business-design/04c-all-flows.md` has all 25 business flows in Vietnamese — 18 `F-*` main
flows, 3 `X-*` exception flows, 4 `P-*` platform flows — with the state machines in
`03-state-machines.md` and the 50 rules in `05-business-rules.md`. Two are implemented in code; here
is where they live.

### Checkout — the flow the whole architecture exists to justify

`OrderServiceImpl.placeOrder` (`order/internal/service/`), one `@Transactional` method:

```
1. findByRequestId  → already placed? return it. (domain-level idempotency)
2. nextOrderNumber  → from a sequence row, own short transaction
3. build OrderLines with DETERMINISTIC ids derived from (requestId, index)
4. Order.draft(...)
5. for each line:
      inventory.reserve(ReserveStockCommand(deterministicRequestId(requestId, lineId), ...))
      order.attachReservations(lineId, result.reservationIds())   ← ALL ids, not the first
6. order.submit()
7. repository.save(order)
8. events.publishEventsOf(order)   → OrderPlaced
```

Step 5 is the point. `inventory.reserve` is a **plain Java call inside this transaction**. If the
order fails to persist, the reservation rolls back with it — no saga, no compensating action, no
reservation timeout. ADR-0003 describes the distributed version this replaced; ADR-0005 is the
decision itself. The deterministic ids in steps 3 and 5 are what make a retry of the whole request
converge on the same rows instead of creating a second order.

`reservationIds()` returns **every** id: a line drawn from two lots produces two reservations, and
returning only the first would strand the rest until the sweeper expired them. This was a real
defect; `ReserveStockResult` now carries a `List<StockReservation>`.

### Reserve — `InventoryServiceImpl.reserve`

```
0. replay check  ← MUST be first. Planning before checking either double-holds or 409s falsely.
1. plan          ← StockAllocator.plan over a projection, FEFO (earliest expiry first)
2. sort the plan by stock item id   ← deadlock avoidance: two checkouts touching the same two
                                       lots in opposite orders deadlock at the database
3. for each line: findByIdForUpdate (PESSIMISTIC_WRITE + entityManager.refresh) → locked.reserve()
4. return ReserveStockResult with one StockReservation per lot
```

`findByIdForUpdate` refreshes deliberately: returning the persistence-context copy would make the
pessimistic lock decorative. The plan's numbers are stale by step 3 by design — the re-read under
lock is what makes the decision correct.

### Payment failure → release

`PaymentEventListener.on(PaymentFailed)` in `inventory/internal/service/`, annotated
`@ApplicationModuleListener` (= `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` +
`@Transactional(REQUIRES_NEW)`). Asynchronous on purpose: a slow release must not delay payment, and
a failed release must not roll back the payment. Idempotent by construction — releasing an already
closed reservation is a no-op.

Spring Modulith's **event publication registry** replaces the transactional outbox: an event that a
listener failed to process stays in `event_publication` and is retried. That table is migration
`V20260901000400`.

### Expiry sweep

`ReservationSweeper.releaseExpiredReservations`, every minute, `@SchedulerLock` so it runs on one
instance only. Bounded batches.

### Pick confirmation

`InventoryController.consume` — `POST /api/v1/inventory/reservations/{reservationId}/consumption`.
Designed to be called by `fulfillment` over **HTTP** rather than as a Java call, and the controller
javadoc explains why: unlike the checkout reservation, recording a pick that already happened
physically does not need to be atomic with anything on the caller's side. Note that `fulfillment` is
still a skeleton, so today this endpoint has no in-repo caller — the HTTP choice is design intent,
not observed behaviour.

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
| `docs/business-design/04c-all-flows.md` | all 25 business flows (18 `F-*`, 3 `X-*`, 4 `P-*`) |
| `docs/business-design/05-business-rules.md` | the 50 rules, each with its enforcement point |
| `docs/business-design/06-open-questions.md` | what is deliberately undecided |
| `inventory/**` | the worked example. Every layer is implemented. |
| `tools/verify.py` | the fast static pass; its module docstring lists all 15 checks |
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
