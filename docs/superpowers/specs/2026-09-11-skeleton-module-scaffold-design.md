# Skeleton-module scaffold — design

*2026-09-11 · StockFlowCommerce (FA26SE029)*

## Problem

Eleven of the fourteen business modules are skeletons: folders plus `package-info.java`
only. A developer opening `payment/internal/domain/` sees an empty directory and starts from
a blank file, even though the shape is already decided. The team wants a starter file sitting
in each module so they "just fill it in".

The hard constraint: the fitness functions **are** the architecture. Only `tools/verify.py`
is runnable in this environment (`mvn test` is blocked — no local JDK/Maven and registry pulls
fail under the build account). So whatever is added must keep `verify.py` green and must be
designed not to break `ModularityTest` / `ArchitectureTest` either.

## Decision — "safe-layer" stubs

Add only the layers that cannot trip a fitness function. Deliberately **omit** entity,
controller, repository and migration, because:

- a `@Table` entity requires a matching migration (`verify.py` #12);
- a `@PermissionResource` / `@RequiresPermission` is cross-validated (`verify.py` #10);
- `adding-a-module.md` §4.1 makes "migration first" a business decision, not scaffolding.

Each of those three would force real per-module business decisions (which table, which
permissions, which aggregate fields) and could not be verified here.

### What gets added

Three files per module (`.java`, compilable):

1. `api/<Module>Service.java` — public interface, empty body + guidance javadoc. Leaks nothing
   internal, so it passes `theApiPackageLeaksNothingInternal`.
2. `internal/domain/<Aggregate>.java` — `final class … extends AggregateRoot`, id + private
   constructor + `create(...)` factory + `id()` getter + TODO for invariants/behaviour. No
   framework annotation → passes `domainDoesNotDependOnFrameworks`.
3. `internal/service/<Module>ServiceImpl.java` — `@Service @Transactional`, package-private
   class / public interface, dependency-free constructor, empty body + TODO.

Every file's javadoc points at `docs/adding-a-module.md` and names the three remaining "wiring"
steps (migration → entity + repository adapter → controller + permission).

### Names (derived from each package-info's WBS line; the team may rename freely)

| Module | Service (the port) | Aggregate (domain) |
|---|---|---|
| product | `ProductService` | `Product` |
| warehouse | `WarehouseService` | `Location` |
| identity | `IdentityService` | `User` |
| payment | `PaymentService` | `Payment` |
| procurement | `ProcurementService` | `PurchaseOrder` |
| customer | `CustomerService` | `Customer` |
| design | `DesignService` | `Design` |
| catalog | `CatalogService` | `Listing` |
| chat | `ChatService` | `Conversation` |
| fulfillment | `FulfillmentService` | `Shipment` |

### Two special cases

- **reporting** — a read-model/CQRS module has no write aggregate. It gets `ReportingService`
  + `ReportingServiceImpl` only; **no domain aggregate** (placing one there would be the wrong
  model).
- **notification** — already has `OrderNotificationListener` + `NotificationSender`. Those are
  **kept untouched**. It additionally gets `NotificationService` + `Notification` +
  `NotificationServiceImpl` as a sample, with javadoc noting an event-only module may not need a
  public api and the sample can be deleted.

Total: 10×3 + reporting 2 + notification 3 = **35 new `.java` files**.

## Invariants enforced while writing (the traps this codebase punishes)

- no unused imports, incl. "import used only in javadoc" (`verify.py` #4);
- type name equals file name (#2); balanced braces/parens (#3);
- English identifiers only (#5); nothing left in a module base package (#15);
- every advised method public (#14) — met trivially, the services have no methods yet;
- constructor injection only, never field (`noFieldInjection`);
- domain free of Spring/JPA/Jackson (`domainDoesNotDependOnFrameworks`).

## Verification

- **Runnable here:** `python3 tools/verify.py` must stay green (was green before the change).
- **NOT runnable here, must be run by the team once a Maven image is available:**
  `mvn test -Dtest=ArchitectureTest,ModularityTest` then full `mvn test`. Risk area: 12 new
  empty `@Service` beans load into the Spring context — designed dependency-free so startup is
  unaffected, but this is unverified in this environment.

## Out of scope

Entities, controllers, repositories, migrations, permissions, tests — i.e. everything that
turns a stub into a working vertical slice. That is per-module implementation work, guided by
`docs/adding-a-module.md`, and is left to the team.
