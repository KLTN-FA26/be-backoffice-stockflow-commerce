# ADR-0005 — Replace the microservices split with a modular monolith on one database

* **Status:** Accepted — supersedes [ADR-0001](0001-choose-microservices.md) and
  [ADR-0002](0002-database-per-service.md); largely supersedes
  [ADR-0003](0003-saga-and-transactional-outbox.md)
* **Date:** 2026-09-07
* **Deciders:** GFA26SE03 (5 members) · Supervisor: Nguyễn Minh Sang
* **Project:** StockFlowCommerce (FA26SE029)

---

## Context

ADR-0001 chose fourteen Spring Boot services with a database each, Kafka between them, Eureka for
discovery, a Spring Cloud gateway and a config server. That design is defensible on paper and it is
what large e-commerce platforms actually run. Building it surfaced four problems that are about
*this* project rather than about microservices in general.

**1. The cost was landing on accidental complexity, not on features.**
Placing an order is the system's central operation. Distributed, it needed: an outbox table and a
relay thread; a Kafka topic; an idempotent consumer in inventory; two response events; a consumer
for each in order; a saga state row to represent "asked, not yet answered"; a timeout for the
answer that never comes; and a compensating release for the reservation that succeeded after the
order had already been abandoned by that timeout. Roughly 600 lines across two services, none of
which a customer can see, all of it to approximate what one database transaction does for free.
The remaining thirteen flows would each need their own version.

**2. There is a failure mode we could not close.**
Between "order committed" and "stock reserved" there is a window. A crash inside it leaves an order
with no stock, or stock held for no order. The saga narrows the window; it cannot remove it,
because two databases cannot commit together without a distributed transaction coordinator, which
nobody sane runs. For a system whose entire purpose is not overselling furniture, an
unclosable oversell window is the wrong thing to have designed in.

**3. The team is five people for one semester.**
Independent deployability — the benefit that pays for all of this — is worth having when separate
teams release on separate schedules. We are one team, releasing together, and we will be for the
life of this project. We were paying the full price of microservices for a benefit we cannot use.

**4. The local stack did not fit on the team's laptops.**
Kafka, Zookeeper, Eureka, the config server, the gateway and fourteen Postgres instances came to
roughly 10 GB of RAM before any application code ran. On a 16 GB laptop that leaves nothing for an
IDE, so in practice people ran three services and stubbed the rest — which meant the integration
problems the architecture was meant to surface early were surfacing late instead.

## Decision

**One Spring Boot application, one Postgres database, fourteen modules enforced by
Spring Modulith 1.4 — not one big package.**

1. **One module per bounded context**, as a top-level package under `com.stockflow`. Each declares
   its allowed dependencies in `package-info.java`. Types directly in the module package are its
   public API; everything under `internal` is invisible to other modules.
2. **`ModularityTest` fails the build** on a boundary violation, an undeclared dependency, or a
   cycle. This is the load-bearing decision: without it the modules are a naming convention, and
   the design decays one convenient import at a time.
3. **One database, one schema per module.** No foreign key and no JOIN crosses a schema; a
   cross-module reference is a plain UUID column. Referential integrity across modules is the
   application's job.
4. **Cross-module calls are direct Java calls through the published interface** when the caller
   needs the result inside its transaction, and **events** when a module is announcing a fact and
   does not care who reacts. `OrderServiceImpl.placeOrder` calls `InventoryService.reserve`;
   payment announces `PaymentCaptured` and does not know that order and notification both listen.
5. **Spring Modulith's event publication registry replaces the outbox and Kafka.** The publication
   row is written in the publisher's own commit and deleted when the listener completes;
   `republish-outstanding-events-on-restart` redelivers whatever a crash interrupted.
6. **The domain layer stays free of Spring and JPA**, with a separate persistence model and a
   hand-written mapper, so the aggregates can be unit-tested with no container.

## Consequences

### What this buys

**The oversell window closes.** Order and reservation are one transaction. If anything throws, both
roll back. `PlaceOrderIntegrationTest.stockIsRolledBackWhenTheOrderFails` demonstrates it against a
real Postgres — the test that could not have been written under ADR-0001.

**Roughly 600 lines of saga and outbox machinery deleted**, and with them a class of bug — lost
compensations, duplicate consumption, half-applied sagas — that is genuinely hard to test for.

**Debugging is a stack trace again.** A failed checkout used to mean correlating logs across three
services and a Kafka topic. It is now one exception with one stack trace.

**The local stack is ~1.5 GB.** Everyone can run the whole system, so integration problems surface
on the branch rather than in the final week.

**Refactoring across modules is a compiler problem.** Renaming a field in a contract used to be a
coordinated release of two services plus a compatibility window. It is now one commit that either
compiles or does not.

### What this costs

**No independent deployment or scaling.** Every change redeploys everything. If the catalog needs
ten instances, all fourteen modules get ten instances. Accepted: the expected load is a capstone
demonstration, and vertical scaling covers it many times over.

**Shared failure domain.** An OutOfMemoryError in reporting takes down checkout. Mitigated by
`statement_timeout`, a bounded connection pool, and keeping the batch jobs small — not eliminated.

**Shared connection pool.** A runaway reporting query can starve the pool that checkout needs.
`statement_timeout=10s` is the current mitigation; if reporting grows, the next step is a read
replica with its own datasource, not a separate service.

**One technology stack.** Every module is Java 21 and Spring Boot. This is not currently a
constraint anyone is feeling.

**The discipline is voluntary in a way it was not before.** Under microservices, a boundary
violation was impossible — there was a network in the way. Here it is one import away, and
`ModularityTest` is the only thing standing between this design and a ball of mud. If that test is
ever disabled to "unblock the build", this ADR is void.

### The exit, if it is ever needed

The migration path back out is deliberately kept open, and it is why the module boundaries are
enforced rather than merely documented. To extract a module — say inventory grows to need its own
scaling:

1. Its public interface (`InventoryService`) already exists and is already the only way in.
2. Its schema already has no incoming foreign keys.
3. The direct call becomes an HTTP or gRPC client behind the same interface; the caller does not
   change.
4. The saga that this ADR deleted comes back **for that one flow only**, because the atomicity
   guarantee is genuinely lost at that point — which is the reason
   `InventoryServiceImpl.reserve`'s javadoc says so explicitly rather than leaving it to be
   rediscovered.

Steps 1–3 are days of work per module because the boundary was maintained all along. That is the
difference between a modular monolith and a monolith, and it is the whole reason for the extra
ceremony.

## Alternatives considered

| Option | Why not |
|---|---|
| **Keep the microservices** | Full cost, no usable benefit for a five-person single-release team, and an oversell window we could not close. |
| **Plain monolith, one package tree** | Cheapest to start, and there would be no boundaries left to extract along in six months. The enforced module structure is most of the value here. |
| **Monolith, one database, no schemas** | Loses the ownership signal. "Who writes `customer_address`" needs an answer the database can back up. |
| **Modular monolith, database per module** | Keeps schema isolation but loses the single transaction — the main thing being bought. |
| **Hybrid: monolith plus one or two extracted services** | Reasonable at scale, premature now. The exit path above allows it later without a rewrite. |

## Notes on specific choices

* **Schema `ordering`, module `order`.** `ORDER` is a reserved word in SQL; a schema by that name
  would need quoting in every statement. Only the schema carries the workaround.
* **Migrations are timestamped** (`V20260901000200__...`), not sequentially numbered. With five
  people committing migrations in parallel, `V3__` collides constantly; a timestamp does not.
* **The reservation sweeper assumes a single instance.** Two instances would both sweep; the row
  locks keep that correct but wasteful. Add ShedLock before scaling past one instance.
* **`contracts` is a single OPEN module**, rather than an `events` package inside each publisher.
  If `order` named `payment`'s event type and `payment` named `order`'s, the two modules would
  depend on each other and `ModularityTest` would reject the build. A neutral module both may
  depend on breaks the cycle.
