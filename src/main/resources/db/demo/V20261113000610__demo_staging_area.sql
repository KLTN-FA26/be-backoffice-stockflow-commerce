-- =============================================================================
-- DEMO DATA, local and test profiles only: a staging area for HCM (team plan 4C-5, kltn-docs 06 §4.5, 07 §1).
--
-- A pick task with destination STAGING moves its cartons here and keeps them for the order until the warehouse manager
-- moves the task to dispatch. Neither pickable nor a putaway target (06 BR-12, BR-18; tg_area_staging). Placed on the
-- free floor below the print shop (PRD01, 26..38 x 12..20).
-- =============================================================================

INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, capacity_units,
                                        max_weight, is_pickable, is_putaway_target, status, created_by)
VALUES (md5('demo:loc:HCM-STG01')::uuid, md5('demo:wh:HCM')::uuid, 'AREA', 'HCM-STG01', 'OVERSIZE', NULL, NULL,
        FALSE, FALSE, 'ACTIVE', 'flyway');

INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation,
                            is_obstacle, status, created_by)
VALUES (md5('demo:area:HCM-STG01')::uuid, md5('demo:wh:HCM')::uuid, md5('demo:loc:HCM-STG01')::uuid, 'STG01',
        'STAGING', 'Khu chờ xuất', 26, 22, 12, 6, 0, FALSE, 'ACTIVE', 'flyway');
