# SCRUM-669 Warehouse capacity board — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /api/v1/warehouses/{id}/capacity` returns the capacity board of kltn-docs 06 §4.6 (bins only, decided
occupancy rule), plus a per-warehouse utilisation factor.

**Architecture:** approach A — `warehouse :: api` answers the bin/area layout with volumes and the factor,
`inventory :: api` answers loads at locations and outbound holds, `order` adds its production orders and does the
arithmetic in a pure `CapacityBoard`. No cross-schema SQL.

**Tech Stack:** Spring Boot 3.5, Spring Modulith 1.4, JdbcTemplate/JPA, Flyway, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-16-scrum-669-capacity-board-design.md`

## Global Constraints

- No SQL reads another module's schema (ADR-0005).
- Migration `V20261124000800__warehouse_capacity_board.sql` (newest on develop is `V20261124000700`).
- `utilization_factor numeric(5,4) NOT NULL DEFAULT 1`, `CHECK (utilization_factor > 0 AND utilization_factor <= 1)`.
- Permission `warehouse-capacity:READ` to `WAREHOUSE_MANAGER`, `SALES_STAFF`, `SALES_MANAGER`,
  `PRODUCTION_COORDINATOR`, `SYSTEM_ADMIN`; advance `app_role.version` of each in the same file.
- Occupied = cartons at bins + `RECEIVING`/`OVERFLOW`/`QUARANTINE` areas; `STAGING` and `DISPATCH` listed, not counted.
- Incoming statuses `SENT, ACCEPTED, SAMPLE_PENDING, IN_PRODUCTION, PARTIALLY_RECEIVED`; pending `DRAFT,
  PENDING_APPROVAL`.
- Outbound = every `HELD` reservation of the warehouse (SKU holds by `warehouse_code`; allocations by their stock row's
  location prefix); reference only.
- Factor applies to warehouse, zone and shelf capacity, m³ and kg.
- English code and javadoc; Vietnamese only in the vi bundle and kltn-docs.

## Review Focus

1. A SKU with no measured carton spec in Incoming → counted as cartons, 0 m³, `unmeasuredCartons` > 0 (CapacityBoardTest).
2. A warehouse with every bin BLOCKED → capacity 0, percentages null, no division error (CapacityBoardTest).
3. A shelf with no zone → grouped under the "no zone" row (CapacityBoardTest).
4. A held SKU whose units are not a multiple of its carton count → cartons rounded up (inventory query test).
5. A warehouse manager reading another warehouse → 403 (integration test).

---

### Task 1: migration, factor on the warehouse, layout api
- [ ] Migration (column, CHECK, permission, version bump) + `tools/db/qa/70_capacity_board.sql`.
- [ ] `Warehouse` gains `utilizationFactor` (rehydration constructor, `register` = 1, `changeUtilizationFactor(BigDecimal)`
      refusing ≤ 0 or > 1 with `DomainChecks.invalid`); entity, mapper, `WarehouseSummary`, `UpdateWarehouseCommand`,
      `UpdateWarehouseRequest` (`@DecimalMin(value="0", inclusive=false) @DecimalMax("1") BigDecimal utilizationFactor`,
      null = unchanged).
- [ ] `warehouse/api/CapacityLayout` + `WarehouseService.capacityLayout(UUID)` → `Optional<CapacityLayout>` built from
      `WarehouseLayoutService.layoutOf` and the aggregate's factor.

### Task 2: inventory reads
- [ ] `inventory/api/LocationLoad(int cartons, BigDecimal volumeM3, BigDecimal weightKg, int unmeasuredCartons)`;
      `InventoryService.loadAt(Collection<String>) → Map<String, LocationLoad>`; `outboundAt(String warehouseCode) →
      LocationLoad`; `InventoryControlService.cartonSpecs(Collection<Sku>) → Map<Sku, CartonSpec>`.
- [ ] SQL in `BinOccupancyQuery` (loads, outbound) and `InventoryPolicyRepository` (specs in one read).

### Task 3: order
- [ ] `ProductionOrderRepository.dueAt(UUID warehouseId) → List<DueCartons(String sku, ProductionOrderStatus status,
      long cartons)>`.
- [ ] Pure `order/internal/domain/CapacityBoard` (`static CapacityBoard compute(...)`) + `CapacityBoardTest`.
- [ ] `CapacityBoardService.boardOf(UUID)` (read-only tx, `WarehouseScope.requireWarehouse`, 404
      `WAREHOUSE_NOT_FOUND`) and `CapacityBoardController` with `@PermissionResource(code = "warehouse-capacity")`.

### Task 4: tests and docs
- [ ] `CapacityBoardIntegrationTest` against demo HCM: numbers by hand, SENT vs DRAFT vs RECEIVED, BLOCKED bin, factor
      0.85, 403 for another warehouse's manager; `PermissionMatrixTest` entry.
- [ ] kltn-docs 06 §4.6 + C40 (staging excluded), Jira comment on SCRUM-626.

### Task 5: verify
- [ ] `tools/verify.py`; `mvn -o clean test -Dtest=<new + touched>`; then full `mvn test` once in the background.
