---
name: jpa-orm-review
description: Use when writing or reviewing any JPA/Hibernate/Spring Data code in this monolith — a repository query, an entity mapping, a @OneToMany/@ManyToOne, a service that loads and iterates entities, a list/detail endpoint, or a @Transactional method. Use when you see LazyInitializationException, a slow list endpoint, query counts that grow with rows, a deadlock, or optimistic-lock/merge surprises. Covers N+1, lazy loading, fetch strategy, pagination, transaction boundaries, locking, and how to MEASURE query counts.
---

# JPA / ORM review — N+1, lazy loading, fetch, transactions, locking

## Overview

The ORM hides SQL, and that is exactly the danger: correct-looking Java emits pathological SQL that
only shows up under real data. Every trap below compiles, passes a happy-path test on three rows,
and falls over in production. **The one habit that catches all of them: count the queries.** A code
review that did not look at the SQL log has not reviewed the persistence.

**Core rule — prove it with data.** Three rows cannot show a fan-out. Seed representative data,
turn on SQL logging, and assert the query count does not grow with the number of rows.

## When to use

- Writing a repository method, an `@Entity`, a relationship, or a service that loads then iterates.
- Building a list or detail endpoint.
- Any `@Transactional` method, any lock, any `save()`.
- Symptoms: `LazyInitializationException`, a list endpoint slow in proportion to rows, a deadlock
  under concurrency, `OptimisticLockException`, or `save()` returning a different instance / doing a
  SELECT before every INSERT.

## The measurement — do this, don't guess

Turn on SQL + binding logs (already wired via `logback-spring.xml` in dev; otherwise
`application-local.yml`):

```yaml
logging.level.org.hibernate.SQL: DEBUG
logging.level.org.hibernate.orm.jdbc.bind: TRACE   # bound parameter values
spring.jpa.properties.hibernate.generate_statistics: true   # prints query/entity counts per session
```

In a `@IntegrationTest` (Postgres via Testcontainers — H2 hides Postgres-specific behaviour), the
airtight check is to assert Hibernate's statement count directly:

```java
var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
stats.setStatisticsEnabled(true);
stats.clear();

service.listSomething(PageRequest.of(0, 5));   // page of 5
long q5 = stats.getPrepareStatementCount();

stats.clear();
service.listSomething(PageRequest.of(0, 25));  // page of 25
long q25 = stats.getPrepareStatementCount();

assertThat(q25).isEqualTo(q5);   // FLAT. If q25 > q5, you have an N+1.
```

**The discriminating assertion is `page_size=5` vs `page_size=25` → identical query count.** If it
grows, a lazy association is being resolved per row.

## N+1 — the default failure

**Cause:** load N aggregates, then touch a `LAZY` association on each → 1 query for the list + N for
the associations.

```java
// ❌ N+1: 1 query for orders, then 1 per order for its lines
List<OrderJpaEntity> orders = repo.findByStatus(SUBMITTED);
for (var o : orders) total += o.getLines().size();   // getLines() is LAZY → a query each

// ✅ fetch the collection in the same query
@Query("select o from OrderJpaEntity o left join fetch o.lines where o.status = :s")
List<OrderJpaEntity> findByStatusWithLines(@Param("s") OrderStatus s);
// or, declaratively:
@EntityGraph(attributePaths = "lines")
List<OrderJpaEntity> findByStatus(OrderStatus s);
```

This repo's own pattern: `StockItemJpaRepository.findByIdWithReservations` is a **fetch join** for
exactly this reason, and the adapter maps entity→domain *inside* the transaction so the domain
object carries no lazy proxies out.

**Do not "fix" N+1 by making everything EAGER** — see below.

## Lazy vs eager — LAZY is the default, EAGER is almost always wrong

- Keep `@ManyToOne(fetch = LAZY)` and `@OneToMany(fetch = LAZY)`. `@ManyToOne` defaults to EAGER —
  **override it to LAZY explicitly.**
- EAGER means the association is fetched on *every* query of that entity, including ones that never
  touch it — a fan-out you cannot turn off. Choose the fetch **per query** (fetch join / entity
  graph / projection), not on the mapping.

**`LazyInitializationException`** = you touched a lazy association after the transaction/session
closed (e.g. in a controller, or after mapping to a DTO outside `@Transactional`). Fixes, in order
of preference:
1. Fetch what you need **inside** the transaction (fetch join / projection), then map to a record.
   This repo does this in the adapter — the domain object leaving the service has no proxies.
2. Do the mapping while the session is open (inside the `@Transactional` service method).
3. **Not** by enabling open-session-in-view (`spring.jpa.open-in-view`) — keep it **false**; it
   papers over the boundary and moves queries into view rendering.

## Fetch strategy — pick the lightest that answers the question

| Need | Use |
|---|---|
| One aggregate + its children, to mutate | fetch join (`join fetch`) or `@EntityGraph` |
| A list where you only display a few columns | **projection** — a record/interface, not the entity |
| A count/sum | `@Query("select count(...)")` — never load rows to count them |
| A list + children for display | projection with a second batched query, or `@BatchSize` |

**Projections are the big lever for list endpoints.** This repo splits read from write:
`findAvailabilityBySku` returns `AvailabilityRow` (a projection), not `StockItemJpaEntity` — planning
puts nothing in the persistence context and loads no collections. Mirror this: **list serves a
projection; detail serves the aggregate.** Never load an aggregate graph to render a table row.

## Collection fetch + pagination — the silent correctness bug

```java
// ❌ join fetch a collection WITH a Pageable:
@Query("select o from OrderJpaEntity o left join fetch o.lines")
Page<OrderJpaEntity> findAllWithLines(Pageable p);
```

Hibernate cannot page a collection fetch in SQL, so it fetches **the entire result set into memory**
and paginates there — logged as `HHH000104: firstResult/maxResults specified with collection fetch;
applying in memory`. On a large table this loads everything.

**Fix:** page the root ids first, then fetch children for that page:
```java
Page<UUID> ids = repo.findIdsBy(status, pageable);          // cheap, real SQL paging
List<OrderJpaEntity> page = repo.findWithLinesByIdIn(ids.getContent());  // fetch join, no paging
```
(The `@BatchSize(size = N)` annotation on the collection is the lighter alternative when a fetch
join isn't warranted — it turns N+1 into N/size queries.)

## Pagination & sorting — never unbounded, never raw client sort

- Every list endpoint pages through `Pages.of(...)`; **never accept an unbounded `size`** — a picker
  that thinks it asked for 25 gets handed the whole table.
- Sort only through `SortWhitelist.of(...)`; never pass the client's `sort` string into a query
  (injection + sorting on an unindexed/lazy column).
- Order by a **stable tiebreaker including the primary key**, or rows repeat or vanish across pages
  when the sort column has duplicates.

## Transaction boundaries

- `@Transactional` lives on the **application service only** (`ArchitectureTest` enforces it) — not
  on a controller (starts too early), not on a repository (a transaction per call, so a multi-step
  operation is no longer atomic).
- Read methods: `@Transactional(readOnly = true)` — lets Hibernate skip dirty-checking/flush.
- **`@Transactional` on a non-public or self-invoked method does nothing** — the proxy can't
  intercept it, silently. `verify.py` #14 catches non-public; self-invocation needs a second bean
  (see `IdempotencyTransactions`).
- State each invariant **twice**: in the aggregate (good error message) and as a DB `CHECK` (what a
  bad migration or manual UPDATE cannot bypass) — `adding-a-module.md` §4.1.

## Locking & concurrency

- **Pessimistic write lock must actually re-read.** A `@Lock(PESSIMISTIC_WRITE)` query returns the
  cached instance if the entity is already in the persistence context, so the lock is decorative and
  you reserve against pre-lock numbers. This repo's `findByIdForUpdate` calls
  `entityManager.refresh(entity, PESSIMISTIC_WRITE)` deliberately — re-read under lock is what makes
  the decision correct.
- **Order your locks** to avoid deadlock: two transactions taking the same two rows in opposite
  order deadlock at the database. `InventoryServiceImpl` sorts its plan by stock-item id so every
  transaction acquires in the same order. Do the same in any multi-row update.
- **Optimistic locking** via `@Version` on `BaseEntity` guards the aggregate. Catch
  `OptimisticLockException` and surface it as a `409 Conflict`, don't let it 500.

## Entity mapping traps

- **`@Enumerated(EnumType.STRING)` always.** Ordinal breaks the day someone reorders the enum
  constants (`verify.py` #9 enforces STRING).
- **The `save()` merge trap.** `BaseEntity` implements `Persistable` with an application-assigned id
  and a `@Version`; Spring Data's `isNew()` then answers false and `save()` always *merges* — a
  SELECT before every INSERT and a returned instance that is not the one you passed. This repo's
  `StockItemRepositoryAdapter.save()` looks the row up and copies onto the managed instance for this
  reason; follow that pattern rather than a blind `jpa.save(toNewEntity(x))`.
- **`@Table` must have a migration** (`verify.py` #12) — schema is owned by Flyway, never
  `ddl-auto: update`.
- **No cross-schema FK, no cross-schema JOIN** (ADR-0005). A module reads its own schema only;
  reach another module through its `api`, not its tables.
- **Batch writes:** for bulk inserts set `hibernate.jdbc.batch_size` and keep ids
  time-ordered (`Identifiers.newId()`) so inserts don't scatter across the index; a random UUID PK
  fragments B-tree inserts as the table grows (`ArchitectureTest.persistenceLayerUsesTimeOrderedIdentifiers`).

## Review checklist

- [ ] Ran the endpoint at `page_size=5` and `page_size=25` — query count is **flat** (no N+1).
- [ ] Every `@ManyToOne`/`@OneToMany` is `LAZY`; fetch chosen per query, not EAGER on the mapping.
- [ ] List endpoints return a **projection**, detail returns the aggregate — no aggregate graph per row.
- [ ] No `join fetch` of a collection together with `Pageable` (check the log for `HHH000104`).
- [ ] Paging via `Pages`, sorting via `SortWhitelist`, order includes the primary key, size bounded.
- [ ] `@Transactional` only on public service methods; queries are `readOnly = true`.
- [ ] `spring.jpa.open-in-view = false`; nothing touches a lazy association outside the transaction.
- [ ] Pessimistic reads `refresh` under the lock; multi-row updates lock in a fixed id order.
- [ ] Enums are `EnumType.STRING`; `save()` follows the load-and-copy pattern; every `@Table` has a migration.
- [ ] Invariants exist both on the aggregate and as a DB `CHECK`.
