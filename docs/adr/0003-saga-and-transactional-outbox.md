# ADR-0003: Sagas and the Transactional Outbox instead of distributed transactions

> **Superseded by [ADR-0005](0005-modular-monolith.md) (2026-09-07).** Order-to-inventory no
> longer needs a saga: it is one transaction. The transactional outbox is replaced by Spring
> Modulith's event publication registry, which solves the same dual-write problem inside a single
> database. Kept for the reasoning, which applies again the day any module is extracted.


- **Status:** Accepted
- **Date:** 2026-09-07

## Problem

The ordering flow touches four services: `order`, `inventory`, `payment`, `fulfillment`. If
payment fails after stock has been reserved, the reservation has to be released, otherwise the
stock is locked forever and the system slowly sells out its own inventory to nobody.

## Decision

1. **Choreographed saga** for the ordering flow: each service reacts to the previous service's
   event and emits its own. There is no central orchestrator.
2. **Compensating actions, not rollbacks:** on failure, `payment-service` emits `PaymentFailed`,
   `inventory-service` hears it and calls `releaseReservation`.
3. **Transactional Outbox** in every service that publishes events.

## Why the outbox is not optional

Without it the code looks like this:

```java
repository.save(stockItem);        // committed to the database
kafkaTemplate.send(event);         // ...and the process dies right here
```

Stock is deducted and nobody knows. The order hangs forever. This is a classic failure that is
very hard to reproduce in testing, so it has to be prevented by architecture rather than by care.

With the outbox, recording the event is an `INSERT` into the **same database, same transaction**
as the business change, so either both exist or neither does. A relay process reads the outbox
table and ships the rows to Kafka afterwards.

Implementation: `common-messaging/OutboxRecorder` (write) and `OutboxRelay` (ship).

## Consequences

- Events are delivered **at-least-once**, so **every** consumer must be idempotent. Use
  `IdempotencyGuard` from `common-messaging`; do not invent a second mechanism.
- The system is **eventually consistent**. The UI has to reflect that: an order shows
  "Processing", not "Confirmed", until the saga completes.
- The polling relay adds roughly one second of latency. If that is too much, replace it with
  Debezium reading the PostgreSQL WAL - which is why CDC appears in the report's glossary.
