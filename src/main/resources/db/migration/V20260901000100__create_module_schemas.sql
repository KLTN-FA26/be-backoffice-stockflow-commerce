-- =============================================================================
-- One database, one schema per module.
--
-- WHY NOT ONE SCHEMA FOR EVERYTHING
--   With every table in `public`, "who owns customer_address" has no answer and any module can
--   join to any table. Six months in, that is a big ball of mud with a modular facade. A schema
--   per module makes ownership a fact the database enforces in code review: a query naming two
--   schemas is visibly a boundary violation.
--
-- WHY NOT ONE DATABASE PER MODULE
--   Because then a single transaction can no longer span order and inventory, and we are back to
--   sagas - which is precisely the complexity this architecture exists to avoid. See
--   docs/adr/0005-modular-monolith.md.
--
-- THE RULES (enforced by review and by ModularityTest, not by the database)
--   1. A module reads and writes only its own schema.
--   2. No foreign key crosses a schema. Cross-module references are plain UUID columns.
--   3. No JOIN crosses a schema. Need another module's data? Call its service.
--
-- Rule 2 costs referential integrity at the database level and buys the ability to extract a
-- module later without untangling a web of constraints. It is a deliberate trade, not an oversight.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS identity;     -- Users, roles, permissions, sessions
CREATE SCHEMA IF NOT EXISTS customer;     -- Customer profiles, addresses, segments
CREATE SCHEMA IF NOT EXISTS product;      -- Product master, variants, attributes, BOM
CREATE SCHEMA IF NOT EXISTS catalog;      -- Storefront catalog, pricing, promotions, search projections
CREATE SCHEMA IF NOT EXISTS inventory;    -- Location-level stock, ATP, reservations, counts, transfers
CREATE SCHEMA IF NOT EXISTS warehouse;    -- Warehouses, zones, bins, capacity
CREATE SCHEMA IF NOT EXISTS ordering;     -- Cart, orders, amendments, RMA (schema is not called "order": reserved word in SQL)
CREATE SCHEMA IF NOT EXISTS payment;      -- Payments, deposits, refunds, reconciliation
CREATE SCHEMA IF NOT EXISTS fulfillment;  -- Picking, packing, shipments, delivery tracking
CREATE SCHEMA IF NOT EXISTS procurement;  -- Suppliers, purchase orders, goods receipt, QC
CREATE SCHEMA IF NOT EXISTS design;       -- Design studio, templates, snapshots, print jobs
CREATE SCHEMA IF NOT EXISTS chat;         -- Conversations, messages, agent assignment
CREATE SCHEMA IF NOT EXISTS notification; -- Templates, preferences, delivery log
CREATE SCHEMA IF NOT EXISTS reporting;    -- Read models and materialised views for dashboards

-- No schema for Spring Modulith: its JPA event publication registry maps to an unqualified
-- table name, so event_publication lives on the default search path. See V20260901000400.
