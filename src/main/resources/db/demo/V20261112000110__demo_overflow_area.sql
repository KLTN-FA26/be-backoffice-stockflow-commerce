-- =============================================================================
-- DEMO DATA, local profile only: an OVERFLOW area in HCM (kltn-docs 05 BR-13, 06 §4.6; team plan 5-4).
--
-- A putaway completes into it when no bin of the warehouse takes the carton - the warehouse is full, or the carton
-- fits no bin. Pickable, as 05 BR-13 allows for an area declared pickable; not a putaway target
-- (suggestions are bins only, 06 BR-12). On the map at x 30-38, y 30-36, clear of every shelf and area of the demo.
-- =============================================================================

INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, max_weight, is_pickable,
                                        is_putaway_target, status, created_by)
VALUES (md5('demo:loc:HCM-OVF01')::uuid, md5('demo:wh:HCM')::uuid, 'AREA', 'HCM-OVF01', NULL, TRUE, FALSE, 'ACTIVE',
        'flyway');

INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation,
                            is_obstacle, status, created_by)
VALUES (md5('demo:area:HCM-OVF01')::uuid, md5('demo:wh:HCM')::uuid, md5('demo:loc:HCM-OVF01')::uuid, 'OVF01',
        'OVERFLOW', 'Khu tạm khi kho đầy', 30, 30, 8, 6, 0, FALSE, 'ACTIVE', 'flyway');
