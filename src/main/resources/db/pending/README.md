# db/pending — contract migrations written ahead, not applied

Flyway reads `db/migration` (and `db/demo` in local/test). This directory is in neither location, so
nothing here runs. Each file is the **contract** half of an expand/contract change from
`docs/business-design/db-design/2026-09-28-migration-plan.md`: it removes the old tables and adds the foreign keys
that could not exist while the old code was still writing the old tables.

| File | Run after | Does |
|---|---|---|
| `C1__contract_product.sql` | product **and design** code use only the new PIM tables | FK `sku → product.variants` from ordering/catalog/reporting, `design_draft.product_id → products`; drops 8 old product tables |
| `C2__contract_warehouse.sql` | warehouse code maps the map model; every stock row sits in a map location | map columns NOT NULL, drops `code/address_line/city` and `warehouse.location`; FK putaway target and `stock_item.location_code → storage_location` |
| `C3__contract_inventory_items.sql` | C1, and every SKU in stock/putaway has an inventory item | FK `stock_item.sku`, `putaway_task.sku → inventory_items.sku` |
| `C4__contract_procurement.sql` | procurement code maps only the new purchasing tables | putaway source FKs + exactly-one-source CHECK; re-points PR #36 tables; drops 6 old procurement tables |

## Activating one

1. Check its "Run when" header against the code on `develop`.
2. Rename it `V<yyyyMMddHHmm00>__<same description>.sql`, with a version newer than the newest file
   in `db/migration` at that moment (Flyway does not run out of order).
3. Move it into `db/migration`, delete it from here.
4. Run the per-PR checklist in the plan §4: fresh database, database with data, `verify.py`,
   `ModularityTest`/`ArchitectureTest`, boot, curl the affected flows.

Every file starts with an orphan check that stops with the table and row count instead of failing
half way. If it stops, fix the data (or the code that wrote it); never delete the check.
