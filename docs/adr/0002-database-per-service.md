# ADR-0002: One database per service

> **Superseded by [ADR-0005](0005-modular-monolith.md) (2026-09-07).** Replaced by one database
> with a schema per module. The ownership rules below survive intact — no cross-boundary JOIN, no
> cross-boundary foreign key — they are now enforced by review and `ModularityTest` rather than by
> the network.


- **Status:** Accepted
- **Date:** 2026-09-07

## Decision

Every service owns a private PostgreSQL database (`sf_inventory`, `sf_order`, and so on).
No service is given credentials to another service's database. There are no cross-service JOINs.
Schemas are managed by Flyway and live inside the owning service's module.

## Why this is the most important boundary

Sharing one database is the fastest way to turn fourteen services into a **distributed monolith**:
they still have to be deployed together, they still break together, but now they also pay the full
network cost of microservices. It is also the first thing a review panel will probe.

## Consequences we have to handle

**Reading another service's data.** Three options, chosen per situation:

| Situation | Approach |
|---|---|
| Needs to be real time, dependency acceptable | Feign call behind a circuit breaker |
| Needed often, a few seconds of lag acceptable | Subscribe to events, keep a local read copy |
| Needed for reporting | `reporting-service` builds a read model from the event stream |

**Data captured at transaction time.** An order stores a *copy* of the product name and price as
they were at checkout, not a pointer into `product-service`. Tomorrow's price change must not
alter yesterday's invoice - that is a business requirement, not a technical shortcut.

**No cross-service transactions.** See ADR-0003.
