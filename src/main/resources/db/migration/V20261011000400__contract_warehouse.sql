-- =============================================================================
-- CONTRACT C2 - warehouse: the map model becomes the only location model.
-- Activated from db/pending by the legacy-tables removal: the code no longer maps the old tables,
-- and V20261011000200 carried their rows over (and archived them in platform.legacy_archive).
-- The orphan check below stops with the table and the row count instead of failing half way.
-- =============================================================================

DO $$
DECLARE
    orphans BIGINT;
    check_sql TEXT;
BEGIN
    FOREACH check_sql IN ARRAY ARRAY[
        'warehouse.warehouse without prefix/address/map|SELECT count(*) FROM warehouse.warehouse WHERE prefix IS NULL OR address IS NULL OR map_unit IS NULL',
        'inventory.stock_item.location_code|SELECT count(*) FROM inventory.stock_item c WHERE NOT EXISTS (SELECT 1 FROM warehouse.storage_location p WHERE p.location_code = c.location_code)',
        'warehouse.putaway_task.target_location_id|SELECT count(*) FROM warehouse.putaway_task c WHERE c.target_location_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM warehouse.storage_location p WHERE p.id = c.target_location_id)'
    ] LOOP
        EXECUTE split_part(check_sql, '|', 2) INTO orphans;
        IF orphans > 0 THEN
            RAISE EXCEPTION 'C2: % row(s) in % do not fit the map model yet', orphans, split_part(check_sql, '|', 1);
        END IF;
    END LOOP;
END $$;

-- The frame is now mandatory; the flat columns go.
ALTER TABLE warehouse.warehouse
    ALTER COLUMN prefix     SET NOT NULL,
    ALTER COLUMN address    SET NOT NULL,
    ALTER COLUMN map_unit   SET NOT NULL,
    ALTER COLUMN map_width  SET NOT NULL,
    ALTER COLUMN map_height SET NOT NULL,
    DROP CONSTRAINT ck_warehouse_map_complete,
    DROP COLUMN code,
    DROP COLUMN address_line,
    DROP COLUMN city;

-- Putaway targets a storage location (a bin or a storage area) - the gap SCRUM-92 left open.
ALTER TABLE warehouse.putaway_task
    DROP CONSTRAINT fk_putaway_target_location,
    ADD CONSTRAINT fk_putaway_task_target_location FOREIGN KEY (target_location_id)
        REFERENCES warehouse.storage_location (id) ON DELETE RESTRICT;

DROP TABLE warehouse.location;

-- Stock sits in a location of the map.
ALTER TABLE inventory.stock_item
    ADD CONSTRAINT fk_stock_item_location FOREIGN KEY (location_code)
        REFERENCES warehouse.storage_location (location_code) ON DELETE RESTRICT;
