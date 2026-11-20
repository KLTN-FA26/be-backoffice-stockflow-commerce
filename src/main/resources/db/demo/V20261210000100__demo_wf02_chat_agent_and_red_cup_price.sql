-- =============================================================================
-- Demo: the demo customer's seeded conversations answered by a Sales person who can sign in, and a price for the red
-- cup (2026-11-20, WF-02 re-check round 3, item A5). Demo only (db/demo).
--
-- 1. V20261124000500 gave its showcase conversations to 'demo.sales.chat', a no-login author (status DISABLED). The
--    red cup's design is attached to one of them ('Đặt in cốc giấy 12oz'), so every card about that design - a quote
--    request among them - landed in a conversation nobody could act on: Sales got CHAT_NOT_ASSIGNEE on "Lập báo giá"
--    and on "Chuyển cho Sale khác", and only a chat supervisor could hand it over. The open ones go to 'demo.sales',
--    the Sales person every WF-02 demo signs in as. A closed conversation keeps its agent (tg_conversation_lifecycle
--    refuses to reassign one, and it is history).
-- 2. The red cup (LY-GIAY-DEMO-12OZ-DO, added by V20261206000100) had no customer price, so a quote drawn up from its
--    request had no tiers and no MOQ: an unpriced line the back office could not open. It gets the blue cup's tiers
--    (13 BR-05: by cartons, the first tier the MOQ), as Sales Managers set them for one customer's cups.
-- =============================================================================

UPDATE chat.conversation
SET assigned_agent_id = (SELECT id FROM identity.app_user WHERE username = 'demo.sales'),
    assigned_at       = NOW(),
    version           = version + 1,
    last_modified_at  = NOW(),
    last_modified_by  = 'flyway'
WHERE assigned_agent_id = 'c4a70000-0000-4000-8000-000000000001'
  AND status <> 'CLOSED'
  AND EXISTS (SELECT 1 FROM identity.app_user WHERE username = 'demo.sales');

INSERT INTO catalog.customer_price (id, customer_id, sku, currency, note, version, created_at, created_by)
SELECT md5('demo:wf02:price:LY-GIAY-DEMO-12OZ-DO')::uuid, 'c0000000-0000-4000-8000-000000000001',
       'LY-GIAY-DEMO-12OZ-DO', 'VND', 'Bảng giá nội bộ theo số thùng (giá một ly)', 0, NOW(), 'flyway'
WHERE EXISTS (SELECT 1 FROM product.variants WHERE sku = 'LY-GIAY-DEMO-12OZ-DO')
ON CONFLICT (customer_id, sku) DO NOTHING;

INSERT INTO catalog.customer_price_tier (id, customer_price_id, min_quantity, unit_price, version, created_at, created_by)
SELECT md5('demo:wf02:price-tier:DO:' || t.min_quantity)::uuid, p.id, t.min_quantity, t.unit_price, 0, NOW(), 'flyway'
FROM catalog.customer_price p
         CROSS JOIN (VALUES (20, 1250), (50, 1150), (100, 1050)) AS t(min_quantity, unit_price)
WHERE p.id = md5('demo:wf02:price:LY-GIAY-DEMO-12OZ-DO')::uuid
ON CONFLICT DO NOTHING;
