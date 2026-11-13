-- =============================================================================
-- Demo: WF-02 ready to walk through (kltn-docs 14; the demo scripts DS-02a and DS-02b of the WF-02 review,
-- 2026-11-13). Demo only (db/demo). Ids are md5('demo:wf02:...')::uuid (DemoData.demoId computes the same).
--
-- Sign-in accounts for the presenter - the published demo password of tools/db/seed (Stockflow@2026), the same hash:
--     demo.khachhang (CUSTOMER, the portal account of the demo customer "Khách hàng demo"),
--     demo.sales (SALES_STAFF, in charge of the two conversations below), demo.ketoan (ACCOUNTANT).
-- They are what makes this location unfit for any server someone else can reach, as the rest of db/demo already is.
--
-- DS-02a - quote from an approved design to a confirmed order. Preconditions only; the presenter clicks the rest:
--   * the demo customer may buy on credit: credit profile 500,000,000 VND, 30 days (paying a batch after delivery is
--     credit, 15 BR-11); a legal profile and shipping / billing addresses, which a contract and an order need;
--   * a printable cup (product LY-GIAY-DEMO, blank LY-GIAY-DEMO-12OZ with its print template STD) and the customer's
--     design "Ly giấy 12oz logo xanh" on it, APPROVED, with its confirmed snapshot and the customer's own SKU
--     LY-GIAY-DEMO-12OZ-XANH drawn on it (01 BR-03): sold by the carton of 1,000 cups (packing unit THUNG, the SKU's only
--     sales unit);
--   * the customer's own price for that SKU (13 BR-05, catalog.customer_price): from 20 cartons 1,250 VND a cup, from
--     50 cartons 1,150, from 100 cartons 1,050 - the MOQ is the first tier, 20 cartons. The SKU was never produced, so
--     its first quote carries a plate fee (14 BR-17): the system adds the line, Sales types its amount on the quote
--     (the demo uses 1,500,000 VND; no table holds a fee price). VAT 10% (the product is STANDARD);
--   * conversation "Báo giá ly logo xanh", about the design, assigned to demo.sales: the customer's "request a quote"
--     card lands here, and so does the quote's card.
--   Click path: demo.khachhang asks a quote on the design (e.g. 60 cartons) -> demo.sales presses CREATE_QUOTE on the
--   card, types the plate fee, sends -> demo.khachhang accepts on the QUOTE card -> demo.sales converts, draws the
--   contract (2 batches; ON_SIGNING / BEFORE_DELIVERY batch 1 / AFTER_DELIVERY batch 2), sends, uploads the signed scan
--   -> demo.ketoan records the ON_SIGNING receipt -> the order is CONFIRMED by itself.
--
-- DS-02b - the same customer's order SO-20261120-900001 (quote QT-20261120-9001, CONVERTED) in PENDING_CONTRACT under
--   contract HD-20261120-900001-1, SENT: 60 cartons at 1,150 = 69,000,000 + plate fee 1,500,000, VAT 10% 7,050,000,
--   total 77,550,000 VND; batches 1 and 2 of 30 cartons each, three weeks and five weeks out; instalments ON_SIGNING
--   30% = 23,265,000, BEFORE_DELIVERY batch 1 = 27,142,500, AFTER_DELIVERY batch 2 (30 days) = 27,142,500 - adding up
--   to the total with tax. No payment.installment yet: they open when the contract is signed. Conversation
--   "Hợp đồng SO-20261120-900001", about the order and the contract, carries the contract's card (OPEN).
--   The contract's sent document is named, not stored: db/demo has no files, so its download answers not found until
--   the contract is sent again.
--
-- Loaded with every trigger on, in the order the application writes the rows - the deferred checks of the contract,
-- the quote's plate fee and the order's status see the state this file leaves, as at the end of the application's own
-- transactions. Nothing here is a snapshot of trigger output: the inventory items and base units of the two variants
-- are made by tg_variant_inventory_item, as the catalogue makes them.
-- =============================================================================

-- ---------------------------------------------------------------------------------------------
-- 1. Accounts
-- ---------------------------------------------------------------------------------------------
INSERT INTO identity.app_user (id, username, email, password_hash, full_name, status, version, created_at, created_by)
VALUES (md5('demo:wf02:user:demo.khachhang')::uuid, 'demo.khachhang', 'demo.khachhang@stockflow.local',
        '{bcrypt}$2a$10$pJyMk7pxJnqmYnhXEyDxMO37YZy2oSDSBtAY40vsZuDmfN6kLvg6y', 'Nguyễn Thị Khách (demo)', 'ACTIVE', 0,
        NOW() - INTERVAL '30 days', 'flyway'),
       (md5('demo:wf02:user:demo.sales')::uuid, 'demo.sales', 'demo.sales@stockflow.local',
        '{bcrypt}$2a$10$pJyMk7pxJnqmYnhXEyDxMO37YZy2oSDSBtAY40vsZuDmfN6kLvg6y', 'Trần Văn Sales (demo)', 'ACTIVE', 0,
        NOW() - INTERVAL '30 days', 'flyway'),
       (md5('demo:wf02:user:demo.ketoan')::uuid, 'demo.ketoan', 'demo.ketoan@stockflow.local',
        '{bcrypt}$2a$10$pJyMk7pxJnqmYnhXEyDxMO37YZy2oSDSBtAY40vsZuDmfN6kLvg6y', 'Lê Thị Kế Toán (demo)', 'ACTIVE', 0,
        NOW() - INTERVAL '30 days', 'flyway');

INSERT INTO identity.user_role (id, user_id, role_id, version, created_at, created_by)
SELECT md5('demo:wf02:user-role:' || u.username)::uuid, md5('demo:wf02:user:' || u.username)::uuid, r.id, 0,
       NOW() - INTERVAL '30 days', 'flyway'
  FROM (VALUES ('demo.khachhang', 'CUSTOMER'), ('demo.sales', 'SALES_STAFF'), ('demo.ketoan', 'ACCOUNTANT'))
           AS u (username, role)
  JOIN identity.app_role r ON r.code = u.role;

-- ---------------------------------------------------------------------------------------------
-- 2. The demo customer: its portal account, tax code, legal profile, addresses and credit
-- ---------------------------------------------------------------------------------------------
UPDATE customer.customer
   SET user_id = md5('demo:wf02:user:demo.khachhang')::uuid, tax_code = '0399990001',
       last_modified_at = NOW() - INTERVAL '30 days', last_modified_by = 'flyway'
 WHERE id = 'c0000000-0000-4000-8000-000000000001';

-- The invoice e-mail is the customer's contact e-mail, as V20261120000100 gave every profile it found.
INSERT INTO customer.customer_legal_profile (id, customer_id, registered_address, representative_name,
                                             representative_title, invoice_email, version, created_at, created_by)
VALUES (md5('demo:wf02:legal')::uuid, 'c0000000-0000-4000-8000-000000000001',
        '12 Nguyễn Huệ, Phường Bến Nghé, TP. Hồ Chí Minh', 'Nguyễn Thị Khách', 'Giám đốc',
        'demo.customer@stockflow.local', 0, NOW() - INTERVAL '30 days', 'flyway');

INSERT INTO customer.address (id, customer_id, type, recipient_name, phone, line1, ward, province, country, is_default,
                              ward_code, province_code, version, created_at, created_by)
VALUES (md5('demo:wf02:address:SHIPPING')::uuid, 'c0000000-0000-4000-8000-000000000001', 'SHIPPING',
        'Nguyễn Thị Khách', '0900000001', '12 Nguyễn Huệ', 'Phường Bến Nghé', 'TP. Hồ Chí Minh', 'VN', TRUE, '26734',
        '79', 0, NOW() - INTERVAL '30 days', 'flyway'),
       (md5('demo:wf02:address:BILLING')::uuid, 'c0000000-0000-4000-8000-000000000001', 'BILLING',
        'Khách hàng demo', '0900000001', '12 Nguyễn Huệ', 'Phường Bến Nghé', 'TP. Hồ Chí Minh', 'VN', TRUE, '26734',
        '79', 0, NOW() - INTERVAL '30 days', 'flyway');

INSERT INTO customer.credit_profile (id, customer_id, credit_limit, credit_term_days, currency, approved_by, approved_at,
                                     note, version, created_at, created_by)
VALUES (md5('demo:wf02:credit')::uuid, 'c0000000-0000-4000-8000-000000000001', 500000000, 30, 'VND',
        md5('demo:wf02:user:demo.ketoan')::uuid, NOW() - INTERVAL '29 days',
        'Hạn mức demo: thanh toán đợt sau giao hàng trong 30 ngày', 0, NOW() - INTERVAL '29 days', 'flyway');

-- ---------------------------------------------------------------------------------------------
-- 3. A printable cup: the product, its blank and print template
-- ---------------------------------------------------------------------------------------------
INSERT INTO product.products (id, code, name, slug, short_description, tax_class, kind, status, submitted_by,
                              submitted_at, approved_by, approved_at, published_at, ever_published, version, created_at,
                              created_by)
VALUES (md5('demo:wf02:product:LY-GIAY-DEMO')::uuid, 'LY-GIAY-DEMO', 'Ly giấy 2 lớp in logo (demo)', 'ly-giay-demo',
        'Ly giấy 2 lớp, in theo thiết kế của khách hàng.', 'STANDARD', 'CUSTOMIZABLE', 'PUBLISHED',
        md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '28 days', '78608dc8-b899-0fc5-96b0-d181d2488ae8',
        NOW() - INTERVAL '27 days', NOW() - INTERVAL '27 days', TRUE, 0, NOW() - INTERVAL '28 days', 'flyway');

INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position, version,
                              created_at, created_by)
VALUES (md5('demo:wf02:variant:LY-GIAY-DEMO-12OZ')::uuid, md5('demo:wf02:product:LY-GIAY-DEMO')::uuid,
        'LY-GIAY-DEMO-12OZ', 'Ly giấy 2 lớp in logo - 12 oz', 'ACTIVE', TRUE, 'DUNG_TICH=12OZ', 0, 0,
        NOW() - INTERVAL '28 days', 'flyway');

-- The catalog shows a published product only with a category and an image approved by someone other than its
-- uploader (CatalogProjectionService, ProductPublicationAdapter.approvedMedia):
-- without them the cup would stay out of the storefront and the projection would retry it forever.
INSERT INTO product.categories (id, parent_id, code, name, slug, path, depth, sort_order, is_active, version,
                                created_at, created_by)
VALUES (md5('demo:wf02:category:LY_GIAY')::uuid, NULL, 'LY_GIAY', 'Ly giấy in logo', 'ly-giay-in-logo', '/LY_GIAY',
        0, 0, TRUE, 0, NOW() - INTERVAL '28 days', 'flyway');

INSERT INTO product.product_categories (id, product_id, category_id, is_primary, version, created_at, created_by)
VALUES (md5('demo:wf02:product-category:LY-GIAY-DEMO')::uuid, md5('demo:wf02:product:LY-GIAY-DEMO')::uuid,
        md5('demo:wf02:category:LY_GIAY')::uuid, TRUE, 0, NOW() - INTERVAL '28 days', 'flyway');

INSERT INTO product.media (id, variant_id, kind, url, renditions, alt_text, sort_order, is_primary, is_published,
                           published_at, published_by, version, created_at, created_by)
VALUES (md5('demo:wf02:media:LY-GIAY-DEMO-12OZ')::uuid, md5('demo:wf02:variant:LY-GIAY-DEMO-12OZ')::uuid, 'IMAGE',
        'https://placehold.co/800x600?text=LY-GIAY-DEMO-12OZ', '[]', 'Ly giấy 2 lớp in logo 12 oz', 0, TRUE, TRUE,
        NOW() - INTERVAL '27 days', '78608dc8-b899-0fc5-96b0-d181d2488ae8', 0, NOW() - INTERVAL '28 days',
        'flyway');

INSERT INTO product.customization_templates (id, variant_id, code, name, min_dpi, color_mode, allowed_file_formats,
                                             max_file_size_mb, max_colors, is_active, model_params_json, version,
                                             created_at, created_by)
VALUES (md5('demo:wf02:template:LY-GIAY-DEMO-12OZ')::uuid, md5('demo:wf02:variant:LY-GIAY-DEMO-12OZ')::uuid, 'STD',
        'Mẫu in thân ly 12 oz', 300, 'CMYK', ARRAY['image/png', 'image/jpeg'], 20, 4, TRUE,
        '{"kind": "parametric-cup", "topDiameterMm": 90, "bottomDiameterMm": 60, "heightMm": 110, "rimMm": 3, "seamMm": 8, "bandTopOffsetMm": 6, "bandBottomOffsetMm": 8, "material": "paper-white"}'::jsonb,
        0, NOW() - INTERVAL '28 days', 'flyway');

INSERT INTO product.print_areas (id, template_id, code, name, x_mm, y_mm, width_mm, height_mm, safe_margin_mm,
                                 bleed_mm, shape_json, uv_mapping_json, version, created_at, created_by)
VALUES (md5('demo:wf02:print-area:LY-GIAY-DEMO-12OZ')::uuid, md5('demo:wf02:template:LY-GIAY-DEMO-12OZ')::uuid,
        'BODY', 'Thân ly', 10, 10, 200, 80, 3, 2, '{"type": "annular-sector"}'::jsonb, '{"meshPart": "body"}'::jsonb, 0,
        NOW() - INTERVAL '28 days', 'flyway');

-- ---------------------------------------------------------------------------------------------
-- 4. The customer's approved design and the SKU issued on it (12 §4.5; 01 BR-03)
-- ---------------------------------------------------------------------------------------------
INSERT INTO design.design_draft (id, customer_id, product_id, name, spec, status, owner_user_id, reviewed_by,
                                 review_notes, current_artifacts, variant_id, sku, template_id, template_code,
                                 review_requested_at, reviewed_at, version, created_at, created_by)
VALUES (md5('demo:wf02:design')::uuid, 'c0000000-0000-4000-8000-000000000001',
        md5('demo:wf02:product:LY-GIAY-DEMO')::uuid, 'Ly giấy 12oz logo xanh',
        'Logo xanh giữa thân ly, 2 màu Pantone', 'APPROVED', md5('demo:wf02:user:demo.khachhang')::uuid,
        md5('demo:wf02:user:demo.sales')::uuid, 'Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm', '{}'::jsonb,
        md5('demo:wf02:variant:LY-GIAY-DEMO-12OZ')::uuid, 'LY-GIAY-DEMO-12OZ',
        md5('demo:wf02:template:LY-GIAY-DEMO-12OZ')::uuid, 'STD', NOW() - INTERVAL '25 days',
        NOW() - INTERVAL '24 days', 0, NOW() - INTERVAL '26 days', 'flyway');

INSERT INTO design.design_decision (id, draft_id, actor_id, decision, notes, draft_version, version, created_at,
                                    created_by)
VALUES (md5('demo:wf02:design-decision:0')::uuid, md5('demo:wf02:design')::uuid,
        md5('demo:wf02:user:demo.khachhang')::uuid, 'CREATED', 'Tạo thiết kế', 0, 0, NOW() - INTERVAL '26 days',
        'flyway'),
       (md5('demo:wf02:design-decision:1')::uuid, md5('demo:wf02:design')::uuid,
        md5('demo:wf02:user:demo.khachhang')::uuid, 'SUBMITTED_FOR_REVIEW', 'Gửi duyệt thiết kế', 1, 0,
        NOW() - INTERVAL '25 days', 'flyway'),
       (md5('demo:wf02:design-decision:2')::uuid, md5('demo:wf02:design')::uuid,
        md5('demo:wf02:user:demo.sales')::uuid, 'APPROVED', 'Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm', 2, 0,
        NOW() - INTERVAL '24 days', 'flyway');

-- Its print file, as the studio stored it: the design module counts a snapshot as the design's approved one only with a
-- primary artifact (DesignSnapshotJpaRepository.snapshotsOf), and without it the quote request would name no SKU. The
-- row names a storage key; db/demo carries no files, so downloading the print file answers not found.
INSERT INTO design.design_artifact (id, draft_id, storage_key, original_name, content_type, size_bytes, stored_at,
                                    checksum, artifact_role, version, created_at, created_by)
VALUES (md5('demo:wf02:artifact:PRINT_READY')::uuid, md5('demo:wf02:design')::uuid,
        'design-renders/demo/ly-giay-12oz-logo-xanh.pdf', 'print_ready.pdf', 'application/pdf', 48213,
        NOW() - INTERVAL '25 days', encode(sha256(convert_to('demo:wf02:artifact:PRINT_READY', 'UTF8')), 'hex'),
        'PRINT_READY', 0, NOW() - INTERVAL '25 days', 'flyway');

UPDATE design.design_draft
   SET current_artifact_id = md5('demo:wf02:artifact:PRINT_READY')::uuid,
       current_artifacts = jsonb_build_object('PRINT_READY', md5('demo:wf02:artifact:PRINT_READY')::uuid)
 WHERE id = md5('demo:wf02:design')::uuid;

-- The snapshot's checksum is the manifest's, as DesignSnapshotJpaEntity.manifestChecksum computes it:
-- sha256 of "ROLE:artifactId:checksum\n" per entry.
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, artifact_id, confirmed_by,
                                    reviewed_by, spec, review_notes, artifact_manifest, version, created_at, created_by)
SELECT md5('demo:wf02:snapshot')::uuid, a.draft_id,
       encode(sha256(convert_to('PRINT_READY:' || a.id || ':' || a.checksum || E'\n', 'UTF8')), 'hex'), a.storage_key,
       NOW() - INTERVAL '24 days', a.id, md5('demo:wf02:user:demo.khachhang')::uuid,
       md5('demo:wf02:user:demo.sales')::uuid, 'Logo xanh giữa thân ly, 2 màu Pantone',
       'Đạt: 300 DPI, CMYK, chừa lề an toàn 3 mm',
       jsonb_build_array(jsonb_build_object('artifactId', a.id, 'role', a.artifact_role, 'storageKey', a.storage_key,
                                            'originalName', a.original_name, 'contentType', a.content_type,
                                            'sizeBytes', a.size_bytes, 'checksum', a.checksum)),
       0, NOW() - INTERVAL '24 days', 'flyway'
  FROM design.design_artifact a
 WHERE a.id = md5('demo:wf02:artifact:PRINT_READY')::uuid;

INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature, position,
                              owner_customer_id, design_snapshot_id, source_variant_id, version, created_at, created_by)
VALUES (md5('demo:wf02:variant:LY-GIAY-DEMO-12OZ-XANH')::uuid, md5('demo:wf02:product:LY-GIAY-DEMO')::uuid,
        'LY-GIAY-DEMO-12OZ-XANH', 'Ly giấy 2 lớp in logo - 12 oz - logo xanh', 'ACTIVE', FALSE,
        'OWNED-SKU:LY-GIAY-DEMO-12OZ-XANH', 10, 'c0000000-0000-4000-8000-000000000001', md5('demo:wf02:snapshot')::uuid,
        md5('demo:wf02:variant:LY-GIAY-DEMO-12OZ')::uuid, 0, NOW() - INTERVAL '24 days', 'flyway');

-- Its carton: 1,000 cups, 60 x 40 x 50 cm (01 BR-03), the unit it ships in and the only one it is sold in (BR-11).
UPDATE inventory.inventory_items SET weight_kg = 0.008, length_cm = 9, width_cm = 9, height_cm = 11
 WHERE sku IN ('LY-GIAY-DEMO-12OZ', 'LY-GIAY-DEMO-12OZ-XANH');

INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, weight_kg, length_cm,
                                       width_cm, height_cm, default_purchase, active, default_shipping, version,
                                       created_at, created_by)
SELECT md5('demo:wf02:unit:LY-GIAY-DEMO-12OZ-XANH')::uuid, i.id, 'THUNG', 1000, FALSE, 8.6, 60, 40, 50, FALSE, TRUE,
       TRUE, 0, NOW() - INTERVAL '24 days', 'flyway'
  FROM inventory.inventory_items i
 WHERE i.sku = 'LY-GIAY-DEMO-12OZ-XANH';

INSERT INTO product.variant_sales_units (id, variant_id, uom_conversion_id, version, created_at, created_by)
VALUES (md5('demo:wf02:sales-unit:LY-GIAY-DEMO-12OZ-XANH')::uuid, md5('demo:wf02:variant:LY-GIAY-DEMO-12OZ-XANH')::uuid,
        md5('demo:wf02:unit:LY-GIAY-DEMO-12OZ-XANH')::uuid, 0, NOW() - INTERVAL '24 days', 'flyway');

-- The customer's own price for it, by cartons (13 BR-05): the first tier is the MOQ.
INSERT INTO catalog.customer_price (id, customer_id, sku, currency, note, version, created_at, created_by)
VALUES (md5('demo:wf02:price')::uuid, 'c0000000-0000-4000-8000-000000000001', 'LY-GIAY-DEMO-12OZ-XANH', 'VND',
        'Bảng giá nội bộ theo số thùng (giá một ly)', 0, NOW() - INTERVAL '23 days', 'flyway');

INSERT INTO catalog.customer_price_tier (id, customer_price_id, min_quantity, unit_price, version, created_at, created_by)
VALUES (md5('demo:wf02:price-tier:20')::uuid, md5('demo:wf02:price')::uuid, 20, 1250, 0, NOW() - INTERVAL '23 days',
        'flyway'),
       (md5('demo:wf02:price-tier:50')::uuid, md5('demo:wf02:price')::uuid, 50, 1150, 0, NOW() - INTERVAL '23 days',
        'flyway'),
       (md5('demo:wf02:price-tier:100')::uuid, md5('demo:wf02:price')::uuid, 100, 1050, 0, NOW() - INTERVAL '23 days',
        'flyway');

-- ---------------------------------------------------------------------------------------------
-- 5. DS-02b: the quote, sent and accepted at 60 cartons (the 50-carton tier), with the plate fee
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, revision, valid_until, terms, sent_at,
                            sent_by, responded_at, responded_by, requested_delivery_date, version, created_at,
                            created_by)
VALUES (md5('demo:wf02:quote')::uuid, 'QT-20261120-9001', md5('demo:wf02:quote-request')::uuid,
        'c0000000-0000-4000-8000-000000000001', 'ACCEPTED', 1, NOW() + INTERVAL '8 days',
        'Giá đã gồm in 2 màu; giao theo đợt trong hợp đồng.', NOW() - INTERVAL '6 days' + INTERVAL '3 hours',
        md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '5 days',
        md5('demo:wf02:user:demo.khachhang')::uuid, CURRENT_DATE + 21, 2, NOW() - INTERVAL '6 days', 'flyway');

INSERT INTO ordering.quote_line (id, quote_id, line_no, sku, uom_conversion_id, cartons, units_per_carton, tax_class,
                                 tax_rate, accepted_cartons, accepted_unit_price)
VALUES (md5('demo:wf02:quote-line')::uuid, md5('demo:wf02:quote')::uuid, 1, 'LY-GIAY-DEMO-12OZ-XANH',
        md5('demo:wf02:unit:LY-GIAY-DEMO-12OZ-XANH')::uuid, 60, 1000, 'STANDARD', 10, 60, 1150);

INSERT INTO ordering.quote_price_tier (quote_line_id, min_quantity, unit_price)
VALUES (md5('demo:wf02:quote-line')::uuid, 20, 1250),
       (md5('demo:wf02:quote-line')::uuid, 50, 1150),
       (md5('demo:wf02:quote-line')::uuid, 100, 1050);

INSERT INTO ordering.quote_fee_line (id, quote_id, kind, sku, amount, tax_class, tax_rate)
VALUES (md5('demo:wf02:quote-fee')::uuid, md5('demo:wf02:quote')::uuid, 'PLATE_FEE', 'LY-GIAY-DEMO-12OZ-XANH', 1500000,
        'STANDARD', 10);

INSERT INTO ordering.quote_status_history (id, quote_id, revision, from_status, to_status, reason, offer, changed_at,
                                           changed_by)
VALUES (md5('demo:wf02:quote-history:0')::uuid, md5('demo:wf02:quote')::uuid, 1, NULL, 'DRAFT', NULL, NULL,
        NOW() - INTERVAL '6 days', md5('demo:wf02:user:demo.sales')::uuid),
       (md5('demo:wf02:quote-history:1')::uuid, md5('demo:wf02:quote')::uuid, 1, 'DRAFT', 'SENT', NULL,
        jsonb_build_object('revision', 1,
                           'validUntil', to_char((NOW() + INTERVAL '8 days') AT TIME ZONE 'UTC',
                                                 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                           'terms', 'Giá đã gồm in 2 màu; giao theo đợt trong hợp đồng.',
                           'requestedDeliveryDate', to_char(CURRENT_DATE + 21, 'YYYY-MM-DD'),
                           'lines', jsonb_build_array(jsonb_build_object(
                               'lineNo', 1, 'sku', 'LY-GIAY-DEMO-12OZ-XANH',
                               'uomConversionId', md5('demo:wf02:unit:LY-GIAY-DEMO-12OZ-XANH')::uuid,
                               'cartons', 60, 'unitsPerCarton', 1000, 'taxClass', 'STANDARD', 'taxRate', 10,
                               'tiers', jsonb_build_array(jsonb_build_object('minQuantity', 20, 'unitPrice', 1250),
                                                          jsonb_build_object('minQuantity', 50, 'unitPrice', 1150),
                                                          jsonb_build_object('minQuantity', 100, 'unitPrice', 1050)))),
                           'fees', jsonb_build_array(jsonb_build_object(
                               'kind', 'PLATE_FEE', 'sku', 'LY-GIAY-DEMO-12OZ-XANH', 'amount', 1500000,
                               'taxClass', 'STANDARD', 'taxRate', 10))),
        NOW() - INTERVAL '6 days' + INTERVAL '3 hours', md5('demo:wf02:user:demo.sales')::uuid),
       (md5('demo:wf02:quote-history:2')::uuid, md5('demo:wf02:quote')::uuid, 1, 'SENT', 'ACCEPTED', NULL, NULL,
        NOW() - INTERVAL '5 days', md5('demo:wf02:user:demo.khachhang')::uuid);

-- ---------------------------------------------------------------------------------------------
-- 6. DS-02b: the order converted from it - 60 cartons and the plate fee, with VAT
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, tax_amount,
                                     currency, placed_at, contact_name, contact_email, contact_phone,
                                     shipping_recipient_name, shipping_phone, shipping_line1, shipping_ward_code,
                                     shipping_ward_name, shipping_province_code, shipping_province_name,
                                     shipping_country_code, billing_recipient_name, billing_phone, billing_line1,
                                     billing_ward_code, billing_ward_name, billing_province_code, billing_province_name,
                                     billing_country_code, version, created_at, created_by)
VALUES (md5('demo:wf02:order')::uuid, 'SO-20261120-900001', 'c0000000-0000-4000-8000-000000000001',
        md5('demo:wf02:order-request')::uuid, 'DRAFT', 77550000, 7050000, 'VND', NOW() - INTERVAL '4 days',
        'Nguyễn Thị Khách', 'demo.customer@stockflow.local', '0900000001', 'Nguyễn Thị Khách', '0900000001',
        '12 Nguyễn Huệ', '26734', 'Phường Bến Nghé', '79', 'TP. Hồ Chí Minh', 'VN', 'Khách hàng demo', '0900000001',
        '12 Nguyễn Huệ', '26734', 'Phường Bến Nghé', '79', 'TP. Hồ Chí Minh', 'VN', 0, NOW() - INTERVAL '4 days',
        'flyway');

INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, requested_delivery_date,
                                 uom_conversion_id, cartons, units_per_carton, tax_rate, tax_amount)
VALUES (md5('demo:wf02:order-line')::uuid, md5('demo:wf02:order')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 60000, 1150, 'VND',
        CURRENT_DATE + 21, md5('demo:wf02:unit:LY-GIAY-DEMO-12OZ-XANH')::uuid, 60, 1000, 10, 6900000);

INSERT INTO ordering.order_fee_line (id, order_id, kind, sku, amount, tax_rate, tax_amount, currency)
VALUES (md5('demo:wf02:order-fee')::uuid, md5('demo:wf02:order')::uuid, 'PLATE_FEE', 'LY-GIAY-DEMO-12OZ-XANH', 1500000,
        10, 150000, 'VND');

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
VALUES (md5('demo:wf02:order-history:0')::uuid, md5('demo:wf02:order')::uuid, NULL, 'DRAFT',
        'Chuyển từ báo giá QT-20261120-9001', md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '4 days',
        NOW() - INTERVAL '4 days', 'flyway');

UPDATE ordering.quote
   SET status = 'CONVERTED', converted_order_id = md5('demo:wf02:order')::uuid, converted_at = NOW() - INTERVAL '4 days',
       version = 3
 WHERE id = md5('demo:wf02:quote')::uuid;

INSERT INTO ordering.quote_status_history (id, quote_id, revision, from_status, to_status, reason, changed_at,
                                           changed_by)
VALUES (md5('demo:wf02:quote-history:3')::uuid, md5('demo:wf02:quote')::uuid, 1, 'ACCEPTED', 'CONVERTED',
        'SO-20261120-900001', NOW() - INTERVAL '4 days', md5('demo:wf02:user:demo.sales')::uuid);

-- ---------------------------------------------------------------------------------------------
-- 7. DS-02b: its contract - two batches, three instalments adding up to the total with tax - sent
-- ---------------------------------------------------------------------------------------------
INSERT INTO ordering.sales_contract (id, contract_number, order_id, status, seller_name, seller_tax_code,
                                     seller_address, seller_representative, seller_representative_title, buyer_name,
                                     buyer_tax_code, buyer_address, buyer_representative, buyer_representative_title,
                                     free_sample_rounds, version, created_at, created_by)
VALUES (md5('demo:wf02:contract')::uuid, 'HD-20261120-900001-1', md5('demo:wf02:order')::uuid, 'DRAFT',
        'DEMO STOCKFLOW CO LTD', '0000000000', '1 Demo Street, Ho Chi Minh City', 'Demo Director', 'Director',
        'Khách hàng demo', '0399990001', '12 Nguyễn Huệ, Phường Bến Nghé, TP. Hồ Chí Minh', 'Nguyễn Thị Khách',
        'Giám đốc', 2, 0, NOW() - INTERVAL '3 days', 'flyway');

INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
VALUES (md5('demo:wf02:batch:1')::uuid, md5('demo:wf02:contract')::uuid, 1, CURRENT_DATE + 21, CURRENT_DATE + 23),
       (md5('demo:wf02:batch:2')::uuid, md5('demo:wf02:contract')::uuid, 2, CURRENT_DATE + 35, CURRENT_DATE + 37);

INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
VALUES (md5('demo:wf02:batch-line:1')::uuid, md5('demo:wf02:batch:1')::uuid, md5('demo:wf02:order-line')::uuid, 30),
       (md5('demo:wf02:batch-line:2')::uuid, md5('demo:wf02:batch:2')::uuid, md5('demo:wf02:order-line')::uuid, 30);

INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, percent, amount, due_days,
                                                   delivery_batch_id)
VALUES (md5('demo:wf02:plan:1')::uuid, md5('demo:wf02:contract')::uuid, 1, 'Đặt cọc khi ký', 'ON_SIGNING', 30,
        23265000, 3, NULL),
       (md5('demo:wf02:plan:2')::uuid, md5('demo:wf02:contract')::uuid, 2, 'Thanh toán trước giao đợt 1',
        'BEFORE_DELIVERY', NULL, 27142500, NULL, md5('demo:wf02:batch:1')::uuid),
       (md5('demo:wf02:plan:3')::uuid, md5('demo:wf02:contract')::uuid, 3, 'Thanh toán sau giao đợt 2',
        'AFTER_DELIVERY', NULL, 27142500, 30, md5('demo:wf02:batch:2')::uuid);

UPDATE ordering.sales_contract
   SET status = 'SENT', sent_at = NOW() - INTERVAL '2 days', sent_by = md5('demo:wf02:user:demo.sales')::uuid,
       sent_document_key = 'documents/demo/HD-20261120-900001-1.pdf', version = 1
 WHERE id = md5('demo:wf02:contract')::uuid;

UPDATE ordering.customer_order SET status = 'PENDING_CONTRACT', version = 1 WHERE id = md5('demo:wf02:order')::uuid;

INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           created_at, created_by)
VALUES (md5('demo:wf02:order-history:1')::uuid, md5('demo:wf02:order')::uuid, 'DRAFT', 'PENDING_CONTRACT',
        'Đã gửi hợp đồng HD-20261120-900001-1', md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '2 days',
        NOW() - INTERVAL '2 days', 'flyway');

-- ---------------------------------------------------------------------------------------------
-- 8. The chat: DS-02a's conversation about the design, DS-02b's about the order and its contract
-- ---------------------------------------------------------------------------------------------
INSERT INTO chat.conversation (id, customer_id, subject, status, assigned_agent_id, assigned_at, opened_by_type,
                               opened_by, status_changed_at, first_response_due_at, resolution_due_at, version,
                               created_at, created_by)
VALUES (md5('demo:wf02:conversation:ds02a')::uuid, 'c0000000-0000-4000-8000-000000000001', 'Báo giá ly logo xanh',
        'ASSIGNED', md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '2 hours', 'CUSTOMER',
        md5('demo:wf02:user:demo.khachhang')::uuid, NOW() - INTERVAL '2 hours',
        NOW() - INTERVAL '2 hours' + INTERVAL '15 minutes', NOW() + INTERVAL '2 days', 0, NOW() - INTERVAL '2 hours',
        'flyway'),
       (md5('demo:wf02:conversation:ds02b')::uuid, 'c0000000-0000-4000-8000-000000000001',
        'Hợp đồng SO-20261120-900001', 'ASSIGNED', md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '2 days',
        'AGENT', md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '2 days',
        NOW() - INTERVAL '2 days' + INTERVAL '15 minutes', NOW() + INTERVAL '1 day', 0, NOW() - INTERVAL '2 days',
        'flyway');

INSERT INTO chat.conversation_context (id, conversation_id, kind, design_id, order_id, contract_id, is_primary,
                                       attached_by, attached_at, version, created_at, created_by)
VALUES (md5('demo:wf02:context:ds02a:design')::uuid, md5('demo:wf02:conversation:ds02a')::uuid, 'DESIGN',
        md5('demo:wf02:design')::uuid, NULL, NULL, TRUE, md5('demo:wf02:user:demo.khachhang')::uuid,
        NOW() - INTERVAL '2 hours', 0, NOW() - INTERVAL '2 hours', 'flyway'),
       (md5('demo:wf02:context:ds02b:order')::uuid, md5('demo:wf02:conversation:ds02b')::uuid, 'ORDER', NULL,
        md5('demo:wf02:order')::uuid, NULL, TRUE, md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '2 days', 0,
        NOW() - INTERVAL '2 days', 'flyway'),
       (md5('demo:wf02:context:ds02b:contract')::uuid, md5('demo:wf02:conversation:ds02b')::uuid, 'CONTRACT', NULL, NULL,
        md5('demo:wf02:contract')::uuid, FALSE, md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '2 days', 0,
        NOW() - INTERVAL '2 days', 'flyway');

INSERT INTO chat.message (id, conversation_id, seq, kind, visibility, sender_type, sender_id, body, card_kind,
                          card_payload, card_state, context_id, sent_at, version, created_at, created_by)
VALUES
    (md5('demo:wf02:message:ds02a:1')::uuid, md5('demo:wf02:conversation:ds02a')::uuid, 1, 'TEXT', 'CUSTOMER',
     'CUSTOMER', md5('demo:wf02:user:demo.khachhang')::uuid,
     'Chào Sale, mẫu ly logo xanh đã được duyệt rồi. Bên mình muốn đặt in khoảng 60 thùng, gửi báo giá giúp mình nhé.',
     NULL, NULL, NULL, NULL, NOW() - INTERVAL '2 hours', 0, NOW() - INTERVAL '2 hours', 'flyway'),
    (md5('demo:wf02:message:ds02a:2')::uuid, md5('demo:wf02:conversation:ds02a')::uuid, 2, 'TEXT', 'CUSTOMER',
     'AGENT', md5('demo:wf02:user:demo.sales')::uuid,
     'Dạ chào anh/chị. Anh/chị bấm "Yêu cầu báo giá" trên thiết kế, em lên báo giá ngay ạ.', NULL, NULL, NULL, NULL,
     NOW() - INTERVAL '2 hours' + INTERVAL '8 minutes', 0, NOW() - INTERVAL '2 hours', 'flyway'),
    (md5('demo:wf02:message:ds02b:1')::uuid, md5('demo:wf02:conversation:ds02b')::uuid, 1, 'TEXT', 'CUSTOMER',
     'AGENT', md5('demo:wf02:user:demo.sales')::uuid,
     'Em gửi anh/chị hợp đồng HD-20261120-900001-1: giao 2 đợt, mỗi đợt 30 thùng; đặt cọc 30% khi ký.', NULL, NULL,
     NULL, NULL, NOW() - INTERVAL '2 days', 0, NOW() - INTERVAL '2 days', 'flyway'),
    (md5('demo:wf02:message:ds02b:2')::uuid, md5('demo:wf02:conversation:ds02b')::uuid, 2, 'CARD', 'CUSTOMER',
     'AGENT', md5('demo:wf02:user:demo.sales')::uuid, 'Hợp đồng HD-20261120-900001-1', 'CONTRACT',
     (SELECT jsonb_build_object(
                 'id', c.id, 'contractId', c.id, 'number', c.contract_number, 'orderId', o.id,
                 'orderNumber', o.order_number, 'status', c.status, 'totalAmount', o.total_amount::bigint,
                 'currency', 'VND', 'freeSampleRounds', c.free_sample_rounds,
                 'paymentPlan', (SELECT jsonb_agg(jsonb_strip_nulls(jsonb_build_object(
                                            'seq', i.seq, 'name', i.name, 'milestone', i.milestone,
                                            'amount', i.amount::bigint, 'percent', i.percent, 'dueDays', i.due_days,
                                            'deliveryBatchSeq', b.seq)) ORDER BY i.seq)
                                   FROM ordering.contract_payment_installment i
                                   LEFT JOIN ordering.delivery_batch b ON b.id = i.delivery_batch_id
                                  WHERE i.contract_id = c.id),
                 'sentAt', to_char(c.sent_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
                 'version', c.version,
                 'documentPath', '/api/v1/orders/me/' || o.id || '/contract/document')
        FROM ordering.sales_contract c JOIN ordering.customer_order o ON o.id = c.order_id
       WHERE c.id = md5('demo:wf02:contract')::uuid),
     'OPEN', md5('demo:wf02:context:ds02b:contract')::uuid, NOW() - INTERVAL '2 days' + INTERVAL '1 minute', 0,
     NOW() - INTERVAL '2 days', 'flyway'),
    (md5('demo:wf02:message:ds02b:3')::uuid, md5('demo:wf02:conversation:ds02b')::uuid, 3, 'TEXT', 'CUSTOMER',
     'CUSTOMER', md5('demo:wf02:user:demo.khachhang')::uuid,
     'Mình nhận được hợp đồng rồi. Cho mình hỏi đợt 2 giao khoảng ngày nào?', NULL, NULL, NULL, NULL,
     NOW() - INTERVAL '1 day', 0, NOW() - INTERVAL '1 day', 'flyway');

UPDATE chat.conversation
   SET status = 'ACTIVE', status_changed_at = NOW() - INTERVAL '2 hours' + INTERVAL '8 minutes',
       first_responded_at = NOW() - INTERVAL '2 hours' + INTERVAL '8 minutes', last_message_seq = 2,
       last_message_at = NOW() - INTERVAL '2 hours' + INTERVAL '8 minutes',
       last_customer_message_at = NOW() - INTERVAL '2 hours'
 WHERE id = md5('demo:wf02:conversation:ds02a')::uuid;
UPDATE chat.conversation
   SET status = 'ACTIVE', status_changed_at = NOW() - INTERVAL '2 days',
       first_responded_at = NOW() - INTERVAL '2 days', last_message_seq = 3, last_message_at = NOW() - INTERVAL '1 day',
       last_customer_message_at = NOW() - INTERVAL '1 day'
 WHERE id = md5('demo:wf02:conversation:ds02b')::uuid;

INSERT INTO chat.participant_read (id, conversation_id, user_id, reader_type, last_read_seq, last_read_message_id,
                                   last_read_at, version, created_at, created_by)
VALUES (md5('demo:wf02:read:ds02a:sales')::uuid, md5('demo:wf02:conversation:ds02a')::uuid,
        md5('demo:wf02:user:demo.sales')::uuid, 'STAFF', 2, md5('demo:wf02:message:ds02a:2')::uuid,
        NOW() - INTERVAL '2 hours' + INTERVAL '8 minutes', 0, NOW() - INTERVAL '2 hours', 'flyway'),
       (md5('demo:wf02:read:ds02b:customer')::uuid, md5('demo:wf02:conversation:ds02b')::uuid,
        md5('demo:wf02:user:demo.khachhang')::uuid, 'CUSTOMER', 3, md5('demo:wf02:message:ds02b:3')::uuid,
        NOW() - INTERVAL '1 day', 0, NOW() - INTERVAL '1 day', 'flyway');

INSERT INTO chat.conversation_event (id, conversation_id, type, actor_type, actor_id, occurred_at, from_status,
                                     to_status, context_id, message_id, target_user_id, note, detail, version,
                                     created_at, created_by)
VALUES
    (md5('demo:wf02:event:ds02a:1')::uuid, md5('demo:wf02:conversation:ds02a')::uuid, 'OPENED', 'CUSTOMER',
     md5('demo:wf02:user:demo.khachhang')::uuid, NOW() - INTERVAL '2 hours', NULL, NULL, NULL, NULL, NULL, NULL, NULL,
     0, NOW() - INTERVAL '2 hours', 'flyway'),
    (md5('demo:wf02:event:ds02a:2')::uuid, md5('demo:wf02:conversation:ds02a')::uuid, 'ASSIGNED', 'SYSTEM', NULL,
     NOW() - INTERVAL '2 hours', NULL, NULL, NULL, NULL, md5('demo:wf02:user:demo.sales')::uuid, NULL, NULL, 0,
     NOW() - INTERVAL '2 hours', 'flyway'),
    (md5('demo:wf02:event:ds02a:3')::uuid, md5('demo:wf02:conversation:ds02a')::uuid, 'CONTEXT_ATTACHED', 'CUSTOMER',
     md5('demo:wf02:user:demo.khachhang')::uuid, NOW() - INTERVAL '2 hours', NULL, NULL,
     md5('demo:wf02:context:ds02a:design')::uuid, NULL, NULL, NULL, NULL, 0, NOW() - INTERVAL '2 hours', 'flyway'),
    (md5('demo:wf02:event:ds02a:4')::uuid, md5('demo:wf02:conversation:ds02a')::uuid, 'FIRST_RESPONSE', 'AGENT',
     md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '2 hours' + INTERVAL '8 minutes', NULL, NULL, NULL, NULL,
     NULL, NULL, NULL, 0, NOW() - INTERVAL '2 hours', 'flyway'),
    (md5('demo:wf02:event:ds02b:1')::uuid, md5('demo:wf02:conversation:ds02b')::uuid, 'OPENED', 'AGENT',
     md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '2 days', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0,
     NOW() - INTERVAL '2 days', 'flyway'),
    (md5('demo:wf02:event:ds02b:2')::uuid, md5('demo:wf02:conversation:ds02b')::uuid, 'CONTEXT_ATTACHED', 'AGENT',
     md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '2 days', NULL, NULL,
     md5('demo:wf02:context:ds02b:order')::uuid, NULL, NULL, NULL, NULL, 0, NOW() - INTERVAL '2 days', 'flyway'),
    (md5('demo:wf02:event:ds02b:3')::uuid, md5('demo:wf02:conversation:ds02b')::uuid, 'CONTEXT_ATTACHED', 'AGENT',
     md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '2 days', NULL, NULL,
     md5('demo:wf02:context:ds02b:contract')::uuid, NULL, NULL, NULL, NULL, 0, NOW() - INTERVAL '2 days', 'flyway'),
    (md5('demo:wf02:event:ds02b:4')::uuid, md5('demo:wf02:conversation:ds02b')::uuid, 'FIRST_RESPONSE', 'AGENT',
     md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '2 days', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0,
     NOW() - INTERVAL '2 days', 'flyway'),
    (md5('demo:wf02:event:ds02b:5')::uuid, md5('demo:wf02:conversation:ds02b')::uuid, 'CARD_SENT', 'AGENT',
     md5('demo:wf02:user:demo.sales')::uuid, NOW() - INTERVAL '2 days' + INTERVAL '1 minute', NULL, NULL,
     md5('demo:wf02:context:ds02b:contract')::uuid, md5('demo:wf02:message:ds02b:2')::uuid, NULL, NULL,
     '{"card":"CONTRACT"}'::jsonb, 0, NOW() - INTERVAL '2 days', 'flyway');
