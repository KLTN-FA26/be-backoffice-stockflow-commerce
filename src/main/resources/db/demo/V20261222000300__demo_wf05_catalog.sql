-- =============================================================================
-- Demo WF-05 (seed gap G-4): each WF-05 customer's printed cup, drawn on WF02's blank LY-GIAY-DEMO-12OZ (product
-- LY-GIAY-DEMO, tax class STANDARD: every product the demo sells has one, decision file §4.2) - the approved design,
-- the SKU issued on it (01 BR-03), its carton of 1,000 cups and the customer's own price, 1,200 VND a cup. Done the way
-- V20261128000200 §4 does it. Demo only (db/demo).
-- =============================================================================

CREATE FUNCTION pg_temp.wid(key TEXT) RETURNS UUID LANGUAGE sql IMMUTABLE AS
$$ SELECT md5('demo:wf05:' || key)::uuid $$;

CREATE FUNCTION pg_temp.at(days_ago INTEGER, hhmi TEXT) RETURNS TIMESTAMPTZ LANGUAGE sql STABLE AS
$$ SELECT ((((now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date) - days_ago) + hhmi::time) AT TIME ZONE 'Asia/Ho_Chi_Minh' $$;

CREATE FUNCTION pg_temp.staff(username TEXT) RETURNS UUID LANGUAGE sql STABLE AS
$$ SELECT id FROM identity.app_user WHERE username = $1 $$;

CREATE TEMP TABLE w_sku (code TEXT PRIMARY KEY, name TEXT, username TEXT, position INTEGER) ON COMMIT DROP;
INSERT INTO w_sku VALUES ('MOC', 'Quán cà phê Mộc', 'demo.kh.moc', 41), ('LA', 'Quán trà Lá', 'demo.kh.la', 42);

INSERT INTO design.design_draft (id, customer_id, product_id, name, spec, status, owner_user_id, reviewed_by,
                                 review_notes, current_artifacts, variant_id, sku, template_id, template_code,
                                 review_requested_at, reviewed_at, version, created_at, created_by)
SELECT pg_temp.wid('design:' || code), pg_temp.wid('customer:' || code), md5('demo:wf02:product:LY-GIAY-DEMO')::uuid,
       'Ly giấy 12oz logo ' || name, 'Logo giữa thân ly, 2 màu Pantone', 'APPROVED', pg_temp.wid('user:' || username),
       pg_temp.staff('demo.sales'), 'Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm', '{}'::jsonb,
       md5('demo:wf02:variant:LY-GIAY-DEMO-12OZ')::uuid, 'LY-GIAY-DEMO-12OZ',
       md5('demo:wf02:template:LY-GIAY-DEMO-12OZ')::uuid, 'STD', pg_temp.at(85, '10:00'), pg_temp.at(84, '14:00'), 0,
       pg_temp.at(85, '09:00'), pg_temp.wid('user:' || username)::text
  FROM w_sku;

INSERT INTO design.design_decision (id, draft_id, actor_id, decision, notes, draft_version, version, created_at,
                                    created_by)
SELECT pg_temp.wid('design-decision:' || s.code || ':' || d.n), pg_temp.wid('design:' || s.code),
       CASE WHEN d.n = 2 THEN pg_temp.staff('demo.sales') ELSE pg_temp.wid('user:' || s.username) END, d.decision,
       d.notes, d.n, 0, pg_temp.at(d.day, d.hhmi), 'flyway'
  FROM w_sku s
 CROSS JOIN (VALUES (0, 'CREATED', 'Tạo thiết kế', 85, '09:00'),
                    (1, 'SUBMITTED_FOR_REVIEW', 'Gửi duyệt thiết kế', 85, '10:00'),
                    (2, 'APPROVED', 'Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm', 84, '14:00'))
            AS d (n, decision, notes, day, hhmi);

INSERT INTO design.design_artifact (id, draft_id, storage_key, original_name, content_type, size_bytes, stored_at,
                                    checksum, artifact_role, version, created_at, created_by)
SELECT pg_temp.wid('artifact:' || code), pg_temp.wid('design:' || code),
       'design-renders/demo/ly-giay-12oz-' || lower(code) || '.pdf', 'print_ready.pdf', 'application/pdf', 51200,
       pg_temp.at(85, '09:45'), encode(sha256(convert_to('demo:wf05:artifact:' || code, 'UTF8')), 'hex'),
       'PRINT_READY', 0, pg_temp.at(85, '09:45'), pg_temp.wid('user:' || username)::text
  FROM w_sku;

UPDATE design.design_draft d
   SET current_artifact_id = pg_temp.wid('artifact:' || s.code),
       current_artifacts = jsonb_build_object('PRINT_READY', pg_temp.wid('artifact:' || s.code))
  FROM w_sku s
 WHERE d.id = pg_temp.wid('design:' || s.code);

-- The snapshot's checksum is the manifest's (DesignSnapshotJpaEntity.manifestChecksum), as V20261124000600 computes it.
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, artifact_id, confirmed_by,
                                    reviewed_by, spec, review_notes, artifact_manifest, version, created_at, created_by)
SELECT pg_temp.wid('snapshot:' || s.code), a.draft_id,
       encode(sha256(convert_to('PRINT_READY:' || a.id || ':' || a.checksum || E'\n', 'UTF8')), 'hex'), a.storage_key,
       pg_temp.at(84, '14:00'), a.id, pg_temp.wid('user:' || s.username), pg_temp.staff('demo.sales'),
       'Logo giữa thân ly, 2 màu Pantone', 'Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm',
       jsonb_build_array(jsonb_build_object('artifactId', a.id, 'role', a.artifact_role, 'storageKey', a.storage_key,
                                            'originalName', a.original_name, 'contentType', a.content_type,
                                            'sizeBytes', a.size_bytes, 'checksum', a.checksum)),
       0, pg_temp.at(84, '14:00'), pg_temp.staff('demo.sales')::text
  FROM w_sku s JOIN design.design_artifact a ON a.id = pg_temp.wid('artifact:' || s.code);

-- tg_variant_inventory_item makes each SKU's inventory item, as the catalogue makes it.
INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position,
                              owner_customer_id, design_snapshot_id, source_variant_id, version, created_at, created_by)
SELECT pg_temp.wid('variant:' || code), md5('demo:wf02:product:LY-GIAY-DEMO')::uuid, 'LY-GIAY-DEMO-12OZ-' || code,
       'Ly giấy 2 lớp in logo - 12 oz - ' || name, 'ACTIVE', FALSE, 'OWNED-SKU:LY-GIAY-DEMO-12OZ-' || code, position,
       pg_temp.wid('customer:' || code), pg_temp.wid('snapshot:' || code),
       md5('demo:wf02:variant:LY-GIAY-DEMO-12OZ')::uuid, 0, pg_temp.at(84, '14:05'), 'system'
  FROM w_sku;

-- Its carton: 1,000 cups, 60 x 40 x 50 cm, 8.6 kg (01 BR-03) - the unit it ships in and the only one it is sold in.
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, weight_kg, length_cm,
                                       width_cm, height_cm, active, default_shipping, version, created_at, created_by)
SELECT pg_temp.wid('unit:' || s.code), i.id, 'THUNG', 1000, FALSE, 8.6, 60, 40, 50, TRUE, TRUE, 0,
       pg_temp.at(84, '14:10'), pg_temp.staff('demo.sales')::text
  FROM w_sku s JOIN inventory.inventory_items i ON i.sku = 'LY-GIAY-DEMO-12OZ-' || s.code;

INSERT INTO product.variant_sales_units (id, variant_id, uom_conversion_id, version, created_at, created_by)
SELECT pg_temp.wid('sales-unit:' || code), pg_temp.wid('variant:' || code), pg_temp.wid('unit:' || code), 0,
       pg_temp.at(84, '14:10'), pg_temp.staff('demo.sales')::text
  FROM w_sku;

-- The customer's own price (13 BR-05): 1,200 VND a cup from 20 cartons - the decision file's contract, 80 cartons of
-- 1,000 cups at 1,200, is 96,000,000 VND of goods.
INSERT INTO catalog.customer_price (id, customer_id, sku, currency, note, version, created_at, created_by)
SELECT pg_temp.wid('price:' || code), pg_temp.wid('customer:' || code), 'LY-GIAY-DEMO-12OZ-' || code, 'VND',
       'Giá riêng theo hợp đồng nguyên tắc (giá một ly)', 0, pg_temp.at(84, '15:00'),
       pg_temp.staff('demo.sales')::text
  FROM w_sku;

INSERT INTO catalog.customer_price_tier (id, customer_price_id, min_quantity, unit_price, version, created_at,
                                         created_by)
SELECT pg_temp.wid('price-tier:' || code || ':20'), pg_temp.wid('price:' || code), 20, 1200, 0,
       pg_temp.at(84, '15:00'), pg_temp.staff('demo.sales')::text
  FROM w_sku;

DROP FUNCTION pg_temp.wid(TEXT);
DROP FUNCTION pg_temp.at(INTEGER, TEXT);
DROP FUNCTION pg_temp.staff(TEXT);
