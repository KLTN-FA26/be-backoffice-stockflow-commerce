-- =============================================================================
-- Demo: the two demo producers lose their "demo" wording (owner, 2026-11-19). A production order slip prints its
-- producer's name and code (4A-3, design docs/superpowers/specs/2026-11-19-production-order-slip-layout-design.md), and
-- this is a capstone: the papers it shows must read as real. Demo only (db/demo).
--
-- Why a new file: V20261128000200 seeded them as DEMO-INDN / DEMO-INLX "... (demo)" with stockflow.local addresses, and
-- a demo migration that has run is never edited. Matched on the code it gave them: its ids are pg_temp helpers of that
-- file. Production orders reference a producer by id, so renaming the code moves nothing else.
--
-- The other "(demo)" names of the demo data (customers, products, ...) are left for a change of their own.
-- =============================================================================

UPDATE ordering.producer
   SET code = 'INDN',
       name = 'Công ty TNHH In Bao Bì Đồng Nai',
       email = 'sanxuat@inbaobidongnai.vn',
       version = version + 1,
       last_modified_at = now(),
       last_modified_by = 'flyway'
 WHERE code = 'DEMO-INDN';

UPDATE ordering.producer
   SET code = 'INLX',
       name = 'Xưởng In Ly Giấy Long Xuyên',
       email = 'donhang@inlylongxuyen.vn',
       version = version + 1,
       last_modified_at = now(),
       last_modified_by = 'flyway'
 WHERE code = 'DEMO-INLX';
