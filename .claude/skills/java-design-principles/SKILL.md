---
name: java-design-principles
description: Use when writing or reviewing Java in this modular monolith — adding a service, controller, aggregate, or interface; when a class starts doing several jobs, logic is copy-pasted across modules, an interface grows fat, or a design feels over-engineered. Covers SOLID, DRY, KISS, YAGNI and reuse-first, grounded in this codebase.
---

# Java design principles — SOLID / DRY / KISS / reuse-first

## Overview

This is the judgment layer the fitness functions cannot check. `ModularityTest` and
`ArchitectureTest` enforce *where* code lives; they do not tell you whether a class does one job,
whether an interface is the right width, or whether you just rebuilt something `common/` already
provides. That is what this skill is for.

**Core principle:** the cheapest code to maintain is the code you did not write. Reuse before you
add; keep each unit doing one thing; solve today's problem, not an imagined one. Every rule below
has a worked example already in the tree — copy the example, do not reinvent the principle.

## When to use

- Adding any class with a framework role (`...Service`, `...Controller`, `...Adapter`, `...Impl`).
- A method or class is growing past ~1 screen, or you're adding a third `if` branch to it.
- You're about to copy a block from another module, or write validation/caching/paging/error
  handling by hand.
- An interface is gaining a method that only one of its callers needs.
- A design has more indirection than the problem seems to need — or a comment says "for future
  flexibility".

## Reuse-first — check `common/` BEFORE writing (this catches the most waste)

`common/` is a finished ~9,400-LOC base layer. Rebuilding any of it inside a module is the single
most common waste here. Before writing infrastructure, find it in this table (full list:
`docs/adding-a-module.md` §3):

| You're about to write… | Use instead |
|---|---|
| id generation | `Identifiers.newId()` (UUIDv7) — never `UUID.randomUUID()` for a PK |
| entity id/version/equals | `extends BaseEntity` |
| soft delete | `extends SoftDeletableEntity` (+ `@SQLRestriction`, see its javadoc) |
| money field | `MoneyEmbeddable` |
| SKU field | `@Convert(converter = SkuConverter.class)` |
| repository with filters | `extends BaseJpaRepository<E>` |
| row-level access | `extends ScopedJpaRepository<E>` + `implements ScopedEntity` |
| null-safe filter chains | `Specs.eq/contains/in` |
| paging / sorting | `Pages.of(...)`, `SortWhitelist.of(...)` |
| errors | throw `NotFoundException` / `ConflictException` / `BusinessException` |
| permissions | `@PermissionResource` + `@RequiresPermission` — never a role check in a service |
| caching / invalidation | `@Cacheable(CacheNames.X)` + `TransactionalCacheEvictor` |
| audit | `@Auditable(...)` |
| current time | inject `Clock` — never `Instant.now()` |
| the signed-in user | `@AuthenticatedUser CurrentUser user` |
| response envelope | `ApiResponse.ok(...)`, `Pages.toResponse(...)` |

**Rule:** if a capability is cross-cutting (security, caching, audit, id, time, money, paging), it
belongs in `common/` and almost certainly already exists. Adding a second copy in a module is the
DRY violation to catch first.

## SOLID, as this codebase already does it

Each principle has a live example — read it, then match it.

**S — Single Responsibility.** One class, one reason to change.
- `InventoryController` converts HTTP↔command and nothing else (`ArchitectureTest` forbids it
  touching a repository or domain).
- `InventoryServiceImpl` orchestrates a transaction: load → call domain → save → publish. It holds
  **no business rule** — every "may I?" lives on `StockItem`.
- `StockItemRepositoryAdapter` is "the only place that speaks both languages" (entity ⇄ domain).
- Smell: a controller with an `if` about business state; a service method computing an invariant; an
  entity with a `reserve()` method. Each means a responsibility landed in the wrong layer.

**O — Open/Closed.** Extend without editing.
- New behaviour arrives as a new `@ApplicationModuleListener` reacting to an existing event
  (`PaymentEventListener`), not as an edit to the publisher. `contracts/` events are the extension
  seam.

**L — Liskov.** A subtype must honour the base contract.
- Aggregates are `final` on purpose — no subtype can weaken an invariant checked in the constructor.
  Don't reach for inheritance to share aggregate code; compose value objects instead.

**I — Interface Segregation.** No client depends on a method it never calls. **This is the most
instructive example in the repo:**
- `consume()` is NOT on `InventoryService` (the cross-module api). It lives on a separate narrow
  interface `StockConsumption`, because only the pick-confirmation caller needs it. Widening
  `InventoryService` with it would force every module that reserves stock to depend on a method
  about picking.
- Rule: when only one caller needs a new method, give it its own interface — don't fatten the port.

**D — Dependency Inversion.** Depend on abstractions you own.
- The domain declares the port (`StockItemRepository`, plain Java in `internal/domain`); the adapter
  in `internal/repository` implements it. The domain never imports Spring Data.
- Always **constructor injection**, never field injection (`ArchitectureTest.noFieldInjection`) — it
  makes dependencies visible and the class testable without a container.

## DRY — with the one exception that matters

- Real duplication (the same rule, the same infra) → extract to `common/` or a shared value object.
- **One word per concept across every layer** (`adding-a-module.md` §2, rule 3):
  `reservation` in the table, `Reservation` in the domain, `StockReservation` in api,
  `/reservations` in the URL. A second word for one thing is a DRY violation no test can catch.
- **Do NOT over-DRY across a module boundary.** Two modules that happen to have a similar record
  must each keep their own — merging them couples the modules and is exactly what the boundary
  exists to prevent. Shared meaning goes through a `contracts/` event or a `common/` value object,
  never by one module importing another's type.

## KISS / YAGNI

- The whole architecture's headline is a KISS move: the distributed saga (outbox + Kafka + saga
  orchestrator + compensating action + timeout) was **collapsed to one `@Transactional` method**
  (`OrderServiceImpl.placeOrder`, ADR-0005). Prefer the simplest thing the single database already
  guarantees.
- Don't add a strategy interface, a config flag, or a generic `<T>` for a case that does not exist
  yet. `docs/adding-a-module.md` §0: "keep `allowedDependencies` as short as you can — every name is
  a coupling for ever." The same instinct applies to every abstraction.
- If you must deviate from the base for a real reason, write **why** in the class javadoc or a new
  ADR (`adding-a-module.md` §6). Cleverness without a recorded reason is the thing to cut.

## Boilerplate — records and MapStruct remove it; Lombok is not used here

**No Lombok. It is not a dependency, and it must not become one.** Java 21 already removes the
boilerplate Lombok exists for, with better tools — adding Lombok is the mess, not the fix.

- **Data classes → `record`.** Every DTO, command, result, event and value object is a record
  (`ApiResponse`, `ReserveStockCommand`, `StockAvailability`). A record gives accessors, `equals`,
  `hashCode`, `toString` and a canonical constructor natively — exactly what `@Data`/`@Value`
  generate. Lombok on top is a second tool for a solved problem.
- **Injection → an explicit constructor**, not `@RequiredArgsConstructor`. Two or three lines,
  greppable, field list visible. `ArchitectureTest.noFieldInjection` already forbids the field
  injection Lombok would tempt you into.
- **Why not add it anyway:** Lombok is an annotation processor that rewrites the AST. It needs an
  IDE plugin per developer, it clashes with the MapStruct and config processors this build already
  runs (AP-ordering "works on my machine" bugs), and `@Data`/`@ToString` on a JPA entity drag lazy
  associations into `equals`/`toString` → `LazyInitializationException` and N+1. The saving is a few
  constructor lines; the cost is real.

**Mapping: MapStruct where the shapes line up, hand-written where they do not.** This is "pick the
right mapper", not "avoid mappers":

- **MapStruct** (`@Mapper(componentModel = "spring", unmappedTargetPolicy = ERROR)`) for
  **field-for-field DTO ↔ DTO** in the web layer (`InventoryWebMapper`). `ERROR` turns a forgotten
  field into a compile error instead of a `null` found in production.
- **Hand-written** (static `final` class) for **entity ↔ domain** (`StockItemPersistenceMapper`) and
  for DTO mappings whose shapes do not line up (`OrderWebMapper`). Two reasons: rehydration must go
  through the aggregate's constructor so its invariants are **re-checked** — a generated mapper sets
  properties and silently bypasses that guard, and could rebuild a broken aggregate from a corrupt
  row; and when the mapping is not 1:1 the `@Mapping` overrides end up longer and less readable than
  the plain code.
- Rule of thumb: **structurally identical → MapStruct; needs translation logic, or must pass through
  a guarded constructor → hand-write it.**

## Common mistakes

| Mistake | Fix |
|---|---|
| Business rule creeping into a service or controller | Move it onto the aggregate; the service only orchestrates |
| Copying a block from another module | Extract to `common/`, or share via a `contracts/` event — never import the other module's internal |
| Fattening `InventoryService` so one caller can reach a method | New narrow interface (see `StockConsumption`) |
| Field injection / `@Autowired` on a field | Constructor injection |
| `UUID.randomUUID()` as a primary key | `Identifiers.newId()` |
| A flag/generic/strategy "for future flexibility" | Delete it until the second case is real (YAGNI) |
| A new word for an existing concept | Reuse the one word already used in the migration and domain |
| Reaching for Lombok to "cut boilerplate" | Use a `record` (data) or an explicit constructor (injection); Lombok is not a dependency here |
| Forcing MapStruct onto an entity↔domain mapping | Hand-write it — a generated mapper bypasses the aggregate's invariant check |

## Review checklist

- [ ] Does each new class have exactly one reason to change?
- [ ] Did I check the `common/` table before writing any infrastructure?
- [ ] Are business rules on the aggregate, orchestration in the service, mapping in the adapter,
      HTTP in the controller?
- [ ] Constructor injection everywhere; no `@Autowired` field, no Lombok?
- [ ] Data types are records; no `@Data`/`@Value`/`@Builder` from Lombok anywhere?
- [ ] Mapping: MapStruct only where field-for-field; entity↔domain hand-written through the constructor?
- [ ] Is every new interface as narrow as its callers need?
- [ ] One word per concept, matching the migration and domain?
- [ ] Did I add any abstraction with no second use case today? Remove it.
- [ ] For any deliberate deviation from the base: is the reason written down?
