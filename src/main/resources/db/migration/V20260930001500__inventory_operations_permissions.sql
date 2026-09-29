INSERT INTO identity.permission(id,code,resource,action,description,version,created_at,created_by)
SELECT gen_random_uuid(),'inventory-alerts:'||a,'inventory-alerts',a,'Company-wide inventory alerts',0,now(),'flyway'
FROM unnest(ARRAY['VIEW_PAGE','READ','UPDATE']) a ON CONFLICT(code) DO NOTHING;
INSERT INTO identity.role_permission(id,role_id,permission_id,version,created_at,created_by)
SELECT gen_random_uuid(),r.id,p.id,0,now(),'flyway'
FROM identity.app_role r JOIN identity.permission p ON
    (r.code IN ('WAREHOUSE_MANAGER','INVENTORY_PLANNER') AND p.resource='inventory-alerts')
 OR (r.code='WAREHOUSE_STAFF' AND p.resource='inventory-cycle-counts' AND p.action IN ('VIEW_PAGE','READ','UPDATE'))
WHERE NOT EXISTS(SELECT 1 FROM identity.role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);

CREATE FUNCTION inventory.preserve_stock_adjustment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Posted stock adjustment evidence is immutable' USING ERRCODE='23514';
END $$;
CREATE TRIGGER trg_stock_adjustment_immutable BEFORE UPDATE OR DELETE ON inventory.stock_adjustment
    FOR EACH ROW EXECUTE FUNCTION inventory.preserve_stock_adjustment();
