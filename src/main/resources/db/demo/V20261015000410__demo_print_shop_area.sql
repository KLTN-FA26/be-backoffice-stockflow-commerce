-- =============================================================================
-- DEMO DATA, local profile only: the print shop of warehouse HCM, a PRODUCTION area (SCRUM-397), so blanks
-- have somewhere to be issued to. Free floor at x 26-38, y 12-20 of the 60 x 40 map, clear of the demo shelves and areas and of every
-- footprint the warehouse tests place (shelves at x 14-30, y 0-10 and around x 20, y 25-33).
-- =============================================================================

INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, capacity_units,
                                        max_weight, is_pickable, is_putaway_target, status, created_by)
VALUES (md5('demo:loc:HCM-PRD01')::uuid, md5('demo:wh:HCM')::uuid, 'AREA', 'HCM-PRD01', 'OVERSIZE', NULL, NULL,
        FALSE, FALSE, 'ACTIVE', 'flyway');

INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation,
                            is_obstacle, status, created_by)
VALUES (md5('demo:area:HCM-PRD01')::uuid, md5('demo:wh:HCM')::uuid, md5('demo:loc:HCM-PRD01')::uuid, 'PRD01',
        'PRODUCTION', 'Xưởng in', 26, 12, 12, 8, 0, FALSE, 'ACTIVE', 'flyway');
