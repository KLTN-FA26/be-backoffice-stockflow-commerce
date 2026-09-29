# ADR-0007: Foreign keys across module schemas

Status: accepted (2026-09-28). Supersedes rule 3 of [ADR-0005](0005-modular-monolith.md) ("no
foreign key crosses a schema") and rule 2 of the README's database conventions. Every other rule of
ADR-0005 stands.

## Context

ADR-0005 kept each module's schema free of incoming foreign keys so that a module could later be
extracted into its own database without untangling constraints. Cross-module references were plain
UUID or SKU columns, and "referential integrity across modules is the application's job".

Two things changed:

- **The trade was not paying for itself.** Extraction is a hypothetical; dangling references are
  not. The database-design QA round of 27-28/9 inserted an order line for a SKU that does not
  exist, a reservation for an order that was never saved, a stock row in a location no map knows
  about - and the database accepted all of them. Each one is a support ticket the application has
  to prevent on every code path, forever, with nothing underneath it.
- **The thesis review reads the schema, not the code.** A diagram of 14 islands joined by "the
  application checks it" invites exactly the question the team cannot answer from the diagram.

The single-transaction property ADR-0005 bought (checkout reserves stock and saves the order
atomically) is unaffected: foreign keys live inside the same database and the same transaction.

## Decision

1. A reference from one module's table to another module's table is a real foreign key.
   Migrations `V20260928004000` (between tables the code already writes) and the contract steps in
   `src/main/resources/db/pending/` (towards the new product, warehouse, inventory-item and
   purchasing tables) add them.
2. Rule 1 of ADR-0005 is unchanged: **a module writes only its own schema.** A foreign key is a
   read-side guarantee; it gives no module the right to insert into another's tables.
3. Rule 3 is unchanged: **no JOIN crosses a schema in application code.** Need another module's
   data? Call its `api`. (The database checks the key; the code still asks the owner.)
4. Keys are single-column. Where "the referenced row must belong to the same parent" matters (an
   option of the variant's own product, a receipt line of the receipt's own PO), a trigger states
   it; composite keys were rejected by the team for readability (QA notes §1).
5. `ON DELETE` is `RESTRICT` across modules. Business rows are closed or cancelled, never deleted;
   a cascade across a module boundary would let one module erase another's history.
6. A key whose referencing row is written before the referenced row in the same transaction is
   `DEFERRABLE INITIALLY DEFERRED`. Today that is `inventory.stock_reservation.order_id` and
   `ordering.order_line_reservation.reservation_id`: `OrderServiceImpl.placeOrder` reserves stock
   before it saves the order.
7. On a database that already holds rows, a new key is added `NOT VALID` and then validated; a key
   whose old rows fail stays `NOT VALID` with a warning instead of stopping the application
   (`tools/db/orphan_check.sql`, `db/pending/C0__validate_foreign_keys.sql`).

## Consequences

- Extracting a module now means dropping its incoming keys first. That is a one-migration job,
  listed by `SELECT conname FROM pg_constraint WHERE confrelid::regclass::text LIKE '<schema>.%'`,
  and it is the cost we accept for integrity today.
- A foreign-key violation surfaces as `DataIntegrityViolationException`, which
  `GlobalExceptionHandler` maps to 409 `DUPLICATE_KEY` with an ERROR log. That log line is correct:
  it means the application let a bad reference through its own checks. Validate references in the
  service (404/409 with a real error code) before the database has to.
- Test data must reference real rows. Integration tests that placed orders for a random customer
  id now use `support/DemoData.CUSTOMER_ID`, seeded by `db/demo/V20260928009000`.
- `ModularityTest` and `ArchitectureTest` are unaffected: they check code dependencies, and a
  database key creates none.
