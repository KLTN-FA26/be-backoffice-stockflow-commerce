-- =============================================================================
-- Demo WF-05, second round (demo-run 24/11/2026, seed gaps G-8, G-9; defect D-5). Its own location, db/demo-wf05,
-- loaded only by the WF-05 demo (runbook §1.3 adds classpath:db/demo-wf05 to spring.flyway.locations), never by the
-- integration tests: these files leave e-invoices waiting at a provider, orders on credit hold, an installment overdue
-- and orders the nightly settlement will complete, close or liquidate - every job a test drives by hand would find
-- them. Ids are md5('demo:wf05:...')::uuid like V20261222000200..0500, whose customers, SKUs and carrier they use;
-- days are counted back from the Vietnam business day the database is built on (Q-13: rebuilt right before the demo).
--
--   * demo.sales2 (SALES_STAFF): TH-5. A return Sales open for the customer is approved by somebody else
--     (RETURN_SELF_APPROVAL), and SALES_MANAGER holds no sales-rmas permission by design; a second member of Sales is
--     how the four eyes are shown. Same role, same grants - nothing granted to any role here.
--   * HĐ-7: "Ly tre in logo (demo)" (LY-TRE-DEMO), a product whose tax class is unknown (product.products.tax_class
--     NULL: the column allows it, the catalogue screens no longer do - a product from before the classes), with Mộc's
--     own printed SKU LY-TRE-DEMO-MOC on it, drawn up as V20261222000300 draws LY-GIAY-DEMO-12OZ-MOC. Its delivered
--     order's invoice is taxed at the highest rate and held back (TAX_CLASS_UNKNOWN, WF-05 Q-21).
-- =============================================================================

CREATE FUNCTION pg_temp.wid(key TEXT) RETURNS UUID LANGUAGE sql IMMUTABLE AS
$$ SELECT md5('demo:wf05:' || key)::uuid $$;

CREATE FUNCTION pg_temp.at(days_ago INTEGER, hhmi TEXT) RETURNS TIMESTAMPTZ LANGUAGE sql STABLE AS
$$ SELECT ((((now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date) - days_ago) + hhmi::time) AT TIME ZONE 'Asia/Ho_Chi_Minh' $$;

CREATE FUNCTION pg_temp.staff(username TEXT) RETURNS UUID LANGUAGE sql STABLE AS
$$ SELECT id FROM identity.app_user WHERE username = $1 $$;

-- ---------------------------------------------------------------------------------------------
-- 1. The second member of Sales (TH-5): the published demo password, as every demo account
-- ---------------------------------------------------------------------------------------------
INSERT INTO identity.app_user (id, username, email, password_hash, full_name, status, version, created_at, created_by)
VALUES (pg_temp.wid('user:demo.sales2'), 'demo.sales2', 'demo.sales2@stockflow.local',
        '{bcrypt}$2a$10$pJyMk7pxJnqmYnhXEyDxMO37YZy2oSDSBtAY40vsZuDmfN6kLvg6y', 'Phạm Thị Sales Hai (demo)', 'ACTIVE',
        0, pg_temp.at(60, '08:00'), 'flyway');

INSERT INTO identity.user_role (id, user_id, role_id, version, created_at, created_by)
SELECT pg_temp.wid('user-role:demo.sales2'), pg_temp.wid('user:demo.sales2'), r.id, 0, pg_temp.at(60, '08:00'), 'flyway'
  FROM identity.app_role r WHERE r.code = 'SALES_STAFF';

-- ---------------------------------------------------------------------------------------------
-- 2. HĐ-7: the bamboo-fibre cup with no tax class, and Mộc's printed SKU on it
-- ---------------------------------------------------------------------------------------------
INSERT INTO product.products (id, code, name, slug, short_description, tax_class, kind, status, submitted_by,
                              submitted_at, approved_by, approved_at, published_at, ever_published, version, created_at,
                              created_by)
VALUES (pg_temp.wid('product:LY-TRE-DEMO'), 'LY-TRE-DEMO', 'Ly tre in logo (demo)', 'ly-tre-demo',
        'Ly sợi tre, in theo thiết kế của khách hàng. Sản phẩm cũ, chưa gán nhóm thuế.', NULL, 'CUSTOMIZABLE',
        'PUBLISHED', pg_temp.staff('demo.sales'), pg_temp.at(120, '09:00'), pg_temp.staff('demo.truongkd'),
        pg_temp.at(119, '09:00'), pg_temp.at(119, '09:00'), TRUE, 0, pg_temp.at(120, '09:00'), 'flyway');

INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, created_by)
VALUES (pg_temp.wid('variant:LY-TRE-DEMO-12OZ'), pg_temp.wid('product:LY-TRE-DEMO'), 'LY-TRE-DEMO-12OZ',
        'Ly tre in logo - 12 oz', 'ACTIVE', TRUE, 'DUNG_TICH=12OZ', 0, 0, pg_temp.at(120, '09:00'), 'flyway');

INSERT INTO product.product_categories (id, product_id, category_id, is_primary, version, created_at, created_by)
VALUES (pg_temp.wid('product-category:LY-TRE-DEMO'), pg_temp.wid('product:LY-TRE-DEMO'),
        md5('demo:wf02:category:LY_GIAY')::uuid, TRUE, 0, pg_temp.at(120, '09:00'), 'flyway');

INSERT INTO product.media (id, variant_id, kind, url, renditions, alt_text, sort_order, is_primary, is_published,
                           published_at, published_by, version, created_at, created_by)
VALUES (pg_temp.wid('media:LY-TRE-DEMO-12OZ'), pg_temp.wid('variant:LY-TRE-DEMO-12OZ'), 'IMAGE',
        'https://placehold.co/800x600?text=LY-TRE-DEMO-12OZ', '[]', 'Ly tre in logo 12 oz', 0, TRUE, TRUE,
        pg_temp.at(119, '09:00'), pg_temp.staff('demo.truongkd'), 0, pg_temp.at(120, '09:00'), 'flyway');

INSERT INTO product.customization_templates (id, variant_id, code, name, min_dpi, color_mode, allowed_file_formats,
                                             max_file_size_mb, max_colors, is_active, model_params_json, version,
                                             created_at, created_by)
VALUES (pg_temp.wid('template:LY-TRE-DEMO-12OZ'), pg_temp.wid('variant:LY-TRE-DEMO-12OZ'), 'STD',
        'Mẫu in thân ly tre 12 oz', 300, 'CMYK', ARRAY['image/png', 'image/jpeg'], 20, 4, TRUE,
        '{"kind": "parametric-cup", "topDiameterMm": 90, "bottomDiameterMm": 60, "heightMm": 110, "rimMm": 3, "seamMm": 8, "bandTopOffsetMm": 6, "bandBottomOffsetMm": 8, "material": "paper-white"}'::jsonb,
        0, pg_temp.at(120, '09:00'), 'flyway');

INSERT INTO product.print_areas (id, template_id, code, name, x_mm, y_mm, width_mm, height_mm, safe_margin_mm,
                                 bleed_mm, shape_json, uv_mapping_json, version, created_at, created_by)
VALUES (pg_temp.wid('print-area:LY-TRE-DEMO-12OZ'), pg_temp.wid('template:LY-TRE-DEMO-12OZ'), 'BODY', 'Thân ly', 10,
        10, 200, 80, 3, 2, '{"type": "annular-sector"}'::jsonb, '{"meshPart": "body"}'::jsonb, 0,
        pg_temp.at(120, '09:00'), 'flyway');

-- Mộc's design on it, approved by Sales, and the SKU issued on it (01 BR-03), as V20261222000300.
INSERT INTO design.design_draft (id, customer_id, product_id, name, spec, status, owner_user_id, reviewed_by,
                                 review_notes, current_artifacts, variant_id, sku, template_id, template_code,
                                 review_requested_at, reviewed_at, version, created_at, created_by)
VALUES (pg_temp.wid('design:TRE-MOC'), pg_temp.wid('customer:MOC'), pg_temp.wid('product:LY-TRE-DEMO'),
        'Ly tre 12oz logo Quán cà phê Mộc', 'Logo giữa thân ly, 1 màu', 'APPROVED', pg_temp.wid('user:demo.kh.moc'),
        pg_temp.staff('demo.sales'), 'Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm', '{}'::jsonb,
        pg_temp.wid('variant:LY-TRE-DEMO-12OZ'), 'LY-TRE-DEMO-12OZ', pg_temp.wid('template:LY-TRE-DEMO-12OZ'), 'STD',
        pg_temp.at(80, '10:00'), pg_temp.at(79, '14:00'), 0, pg_temp.at(80, '09:00'),
        pg_temp.wid('user:demo.kh.moc')::text);

INSERT INTO design.design_decision (id, draft_id, actor_id, decision, notes, draft_version, version, created_at,
                                    created_by)
SELECT pg_temp.wid('design-decision:TRE-MOC:' || d.n), pg_temp.wid('design:TRE-MOC'),
       CASE WHEN d.n = 2 THEN pg_temp.staff('demo.sales') ELSE pg_temp.wid('user:demo.kh.moc') END, d.decision,
       d.notes, d.n, 0, pg_temp.at(d.day, d.hhmi), 'flyway'
  FROM (VALUES (0, 'CREATED', 'Tạo thiết kế', 80, '09:00'),
               (1, 'SUBMITTED_FOR_REVIEW', 'Gửi duyệt thiết kế', 80, '10:00'),
               (2, 'APPROVED', 'Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm', 79, '14:00')) AS d (n, decision, notes, day, hhmi);

INSERT INTO design.design_artifact (id, draft_id, storage_key, original_name, content_type, size_bytes, stored_at,
                                    checksum, artifact_role, version, created_at, created_by)
VALUES (pg_temp.wid('artifact:TRE-MOC'), pg_temp.wid('design:TRE-MOC'), 'design-renders/demo/ly-tre-12oz-moc.pdf',
        'print_ready.pdf', 'application/pdf', 51200, pg_temp.at(80, '09:45'),
        encode(sha256(convert_to('demo:wf05:artifact:TRE-MOC', 'UTF8')), 'hex'), 'PRINT_READY', 0,
        pg_temp.at(80, '09:45'), pg_temp.wid('user:demo.kh.moc')::text);

UPDATE design.design_draft
   SET current_artifact_id = pg_temp.wid('artifact:TRE-MOC'),
       current_artifacts = jsonb_build_object('PRINT_READY', pg_temp.wid('artifact:TRE-MOC'))
 WHERE id = pg_temp.wid('design:TRE-MOC');

INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, artifact_id, confirmed_by,
                                    reviewed_by, spec, review_notes, artifact_manifest, version, created_at, created_by)
SELECT pg_temp.wid('snapshot:TRE-MOC'), a.draft_id,
       encode(sha256(convert_to('PRINT_READY:' || a.id || ':' || a.checksum || E'\n', 'UTF8')), 'hex'), a.storage_key,
       pg_temp.at(79, '14:00'), a.id, pg_temp.wid('user:demo.kh.moc'), pg_temp.staff('demo.sales'),
       'Logo giữa thân ly, 1 màu', 'Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm',
       jsonb_build_array(jsonb_build_object('artifactId', a.id, 'role', a.artifact_role, 'storageKey', a.storage_key,
                                            'originalName', a.original_name, 'contentType', a.content_type,
                                            'sizeBytes', a.size_bytes, 'checksum', a.checksum)),
       0, pg_temp.at(79, '14:00'), pg_temp.staff('demo.sales')::text
  FROM design.design_artifact a WHERE a.id = pg_temp.wid('artifact:TRE-MOC');

INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position,
                              owner_customer_id, design_snapshot_id, source_variant_id, version, created_at, created_by)
VALUES (pg_temp.wid('variant:TRE-MOC'), pg_temp.wid('product:LY-TRE-DEMO'), 'LY-TRE-DEMO-MOC',
        'Ly tre in logo - 12 oz - Quán cà phê Mộc', 'ACTIVE', FALSE, 'OWNED-SKU:LY-TRE-DEMO-MOC', 41,
        pg_temp.wid('customer:MOC'), pg_temp.wid('snapshot:TRE-MOC'), pg_temp.wid('variant:LY-TRE-DEMO-12OZ'), 0,
        pg_temp.at(79, '14:05'), 'system');

INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, weight_kg, length_cm,
                                       width_cm, height_cm, active, default_shipping, version, created_at, created_by)
SELECT pg_temp.wid('unit:TRE-MOC'), i.id, 'THUNG', 1000, FALSE, 8.6, 60, 40, 50, TRUE, TRUE, 0,
       pg_temp.at(79, '14:10'), pg_temp.staff('demo.sales')::text
  FROM inventory.inventory_items i WHERE i.sku = 'LY-TRE-DEMO-MOC';

INSERT INTO product.variant_sales_units (id, variant_id, uom_conversion_id, version, created_at, created_by)
VALUES (pg_temp.wid('sales-unit:TRE-MOC'), pg_temp.wid('variant:TRE-MOC'), pg_temp.wid('unit:TRE-MOC'), 0,
        pg_temp.at(79, '14:10'), pg_temp.staff('demo.sales')::text);

INSERT INTO catalog.customer_price (id, customer_id, sku, currency, note, version, created_at, created_by)
VALUES (pg_temp.wid('price:TRE-MOC'), pg_temp.wid('customer:MOC'), 'LY-TRE-DEMO-MOC', 'VND',
        'Giá riêng theo hợp đồng nguyên tắc (giá một ly)', 0, pg_temp.at(79, '15:00'),
        pg_temp.staff('demo.sales')::text);

INSERT INTO catalog.customer_price_tier (id, customer_price_id, min_quantity, unit_price, version, created_at,
                                         created_by)
VALUES (pg_temp.wid('price-tier:TRE-MOC:2'), pg_temp.wid('price:TRE-MOC'), 2, 1200, 0, pg_temp.at(79, '15:00'),
        pg_temp.staff('demo.sales')::text);

DROP FUNCTION pg_temp.wid(TEXT);
DROP FUNCTION pg_temp.at(INTEGER, TEXT);
DROP FUNCTION pg_temp.staff(TEXT);
