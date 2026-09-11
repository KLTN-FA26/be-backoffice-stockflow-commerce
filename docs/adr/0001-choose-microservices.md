# ADR-0001: Adopt a microservices architecture for StockFlowCommerce

> **Superseded by [ADR-0005](0005-modular-monolith.md) (2026-09-07).** The fourteen bounded
> contexts identified here were kept; they became modules in one application rather than separate
> services. The context and the domain analysis below still stand — only the deployment topology
> changed.


- **Status:** Accepted
- **Date:** 2026-09-07
- **Project:** FA26SE029 - GFA26SE03

## Context

The BRD describes two business areas with very different rhythms and data shapes:

- **Warehouse (WMS):** internal transactions, few concurrent users, strong consistency
  requirements, and several long-running stateful workflows (PO to Receipt to Putaway to Picking).
- **E-commerce storefront:** large and unpredictable read traffic, heavy search, load driven by
  marketing campaigns.

On top of that, `design-service` (2D canvas, live 3D preview, preflight) has a completely
different resource profile: CPU and file I/O heavy, and unrelated to everything else.

## Decision

Split the system into **fourteen services along bounded contexts**, each with its own database,
communicating synchronously over REST/OpenFeign and asynchronously over Kafka.

## Rationale

1. **Scale where the load actually is.** `catalog-service` and `design-service` need replicas
   during a sales campaign; `procurement-service` never does.
2. **Divide the work across five people.** Each member owns two or three services, and the
   contract between people is an API and an event, not a shared file.
3. **Fault isolation.** A slow payment gateway must not stop warehouse staff from scanning goods.

## The price we knowingly accept

- No ACID transaction spanning business operations: we need sagas (see ADR-0003).
- Substantially higher operational cost: 14 services plus 3 platform services plus Kafka plus
  14 databases.
- Harder debugging: distributed tracing is mandatory from day one, not an afterthought.

## Alternative considered and rejected

**Modular monolith.** Far cheaper to operate and, honestly, sufficient for the user volume of a
capstone project. Rejected because the learning objective of the capstone is distributed
architecture, and because `design-service` has a resource profile unlike anything else here.

> In the defence, if the panel asks "why not a monolith?", this is the answer: give the specific
> reason from the Rationale section, then state the price openly. That answer scores better than
> claiming microservices are simply more modern.
