-- =============================================================================
-- Demo: the seeded draft contracts name the seller as the demo profiles now do (2026-11-20, WF-02 re-check).
-- Demo only (db/demo).
--
-- The demo seeds (V20261124000600, V20261128000200, V20261203000100) drew their contracts up with the English stand-in
-- seller 'DEMO STOCKFLOW CO LTD', '1 Demo Street, Ho Chi Minh City', 'Demo Director', 'Director'. A contract drafted
-- in the application takes the seller from stockflow.seller, whose local and dev values are now Vietnamese, so on one
-- demo the DS-02a contract and the seeded DS-02b one named the seller two ways. Only the seeded drafts are rewritten:
-- a draft has not been shown to the customer, while a sent or signed contract keeps the seller it was sent with, as a
-- real one would. Same values as application-local.yml, application-dev.yml and deploy/docker-compose.yml (whose
-- fallbacks reach the dev profile on the VPS); still obviously not a real company.
-- =============================================================================

UPDATE ordering.sales_contract
SET seller_name                 = 'CÔNG TY TNHH STOCKFLOW',
    seller_address              = 'Số 1 Đường Demo, Phường Bến Nghé, TP. Hồ Chí Minh',
    seller_representative       = 'Nguyễn Văn Demo',
    seller_representative_title = 'Giám đốc',
    version                     = version + 1,
    last_modified_at            = NOW(),
    last_modified_by            = 'flyway'
WHERE status = 'DRAFT'
  AND seller_name = 'DEMO STOCKFLOW CO LTD'
  AND seller_address = '1 Demo Street, Ho Chi Minh City';
