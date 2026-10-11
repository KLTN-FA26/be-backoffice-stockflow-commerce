-- =============================================================================
-- DEMO DATA, local profile only: something to receive (SCRUM-435).
--
--   * HCM gets the QUALITY_CONTROL area the 3-step flow needs (docs 03, 06), and a second
--     QUARANTINE area for rejected goods waiting to go back to the supplier: a lot cannot sit in one
--     location as QUARANTINE and BLOCKED at once (one stock row per SKU, location and lot).
--   * TABLE-OAK-160 requires QC on receipt (3 steps, lot + expiry); SOFA-3S-GREY does not (2 steps).
--   * PO-HCM-DEMO-0002 is CONFIRMED, with the revision, approval and confirmation the schema
--     demands of a PO past DRAFT: submitted by demo.editor, approved and confirmed by
--     demo.approver (four eyes). 10 sofas + 6 tables, 10% VAT.
-- =============================================================================

INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, capacity_units,
                                        max_weight, is_pickable, is_putaway_target, status, created_by)
VALUES
    (md5('demo:loc:HCM-QCA01')::uuid, md5('demo:wh:HCM')::uuid, 'AREA', 'HCM-QCA01', 'OVERSIZE', NULL, NULL, FALSE, FALSE, 'ACTIVE', 'flyway'),
    (md5('demo:loc:HCM-RTV01')::uuid, md5('demo:wh:HCM')::uuid, 'AREA', 'HCM-RTV01', 'OVERSIZE', NULL, NULL, FALSE, FALSE, 'ACTIVE', 'flyway');

INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation,
                            is_obstacle, status, created_by)
VALUES
    (md5('demo:area:HCM-QCA01')::uuid, md5('demo:wh:HCM')::uuid, md5('demo:loc:HCM-QCA01')::uuid, 'QCA01',
     'QUALITY_CONTROL', 'Khu kiểm tra chất lượng', 50, 12, 8, 6, 0, FALSE, 'ACTIVE', 'flyway'),
    (md5('demo:area:HCM-RTV01')::uuid, md5('demo:wh:HCM')::uuid, md5('demo:loc:HCM-RTV01')::uuid, 'RTV01',
     'QUARANTINE', 'Khu hàng lỗi chờ trả NCC', 51, 20, 6, 6, 0, FALSE, 'ACTIVE', 'flyway');

UPDATE inventory.inventory_items SET qc_required = TRUE WHERE sku = 'TABLE-OAK-160';


INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency, order_date,
                                         expected_date, payment_terms, subtotal, tax_total, total_amount, note,
                                         submitted_at, submitted_by, approved_at, approved_by,
                                         confirmed_at, confirmed_by, active_revision_id, created_by)
VALUES (md5('demo:po:2')::uuid, 'PO-HCM-DEMO-0002', 'CONFIRMED', md5('demo:supplier:GOVIET')::uuid,
        md5('demo:wh:HCM')::uuid, 'VND', DATE '2026-10-01', DATE '2026-10-15', 'NET30',
        71000000, 7100000, 78100000, 'Đơn đã chốt, chờ nhận hàng (demo)',
        TIMESTAMPTZ '2026-10-01 09:00+07', md5('demo:user:editor')::uuid,
        TIMESTAMPTZ '2026-10-01 10:00+07', md5('demo:user:approver')::uuid,
        TIMESTAMPTZ '2026-10-01 11:00+07', md5('demo:user:approver')::uuid,
        -- The revision is inserted next; the pointer's foreign key is checked at commit.
        md5('demo:po:2:rev:0')::uuid, 'flyway');

INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header, changed_at,
                                                  changed_by, created_by)
VALUES (md5('demo:po:2:rev:0')::uuid, md5('demo:po:2')::uuid, 0, 'INITIAL',
        '{"poNumber": "PO-HCM-DEMO-0002", "currency": "VND", "totalAmount": 78100000}'::jsonb,
        TIMESTAMPTZ '2026-10-01 09:00+07', md5('demo:user:editor')::uuid, 'flyway');

INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id, ordered_qty,
                                              unit_price, tax_rate, line_subtotal, line_net, line_tax, line_total,
                                              created_by)
VALUES
    (md5('demo:po:2:line:1')::uuid, md5('demo:po:2')::uuid, md5('demo:po:2:rev:0')::uuid, 1,
     md5('demo:item:SOFA-3S-GREY')::uuid, 10, 5000000, 10, 50000000, 50000000, 5000000, 55000000, 'flyway'),
    (md5('demo:po:2:line:2')::uuid, md5('demo:po:2')::uuid, md5('demo:po:2:rev:0')::uuid, 2,
     md5('demo:item:TABLE-OAK-160')::uuid, 6, 3500000, 10, 21000000, 21000000, 2100000, 23100000, 'flyway');

INSERT INTO procurement.purchase_order_approvals (id, po_revision_id, step_no, approver_id, decision, decision_at,
                                                  created_by)
VALUES (md5('demo:po:2:approval:1')::uuid, md5('demo:po:2:rev:0')::uuid, 1, md5('demo:user:approver')::uuid,
        'APPROVED', TIMESTAMPTZ '2026-10-01 10:00+07', 'flyway');

INSERT INTO procurement.purchase_order_events (id, po_id, po_revision_id, action, actor_id, from_status, to_status,
                                               created_at, created_by)
VALUES
    (md5('demo:po:2:event:1')::uuid, md5('demo:po:2')::uuid, md5('demo:po:2:rev:0')::uuid, 'SUBMITTED',
     md5('demo:user:editor')::uuid, 'DRAFT', 'PENDING_APPROVAL', TIMESTAMPTZ '2026-10-01 09:00+07', 'flyway'),
    (md5('demo:po:2:event:2')::uuid, md5('demo:po:2')::uuid, md5('demo:po:2:rev:0')::uuid, 'APPROVED',
     md5('demo:user:approver')::uuid, 'PENDING_APPROVAL', 'APPROVED', TIMESTAMPTZ '2026-10-01 10:00+07', 'flyway'),
    (md5('demo:po:2:event:3')::uuid, md5('demo:po:2')::uuid, md5('demo:po:2:rev:0')::uuid, 'CONFIRMED',
     md5('demo:user:approver')::uuid, 'APPROVED', 'CONFIRMED', TIMESTAMPTZ '2026-10-01 11:00+07', 'flyway');
