-- =============================================================================
-- Demo: the purchase orders and suppliers the demo seeded are settled before purchasing leaves (V20261113000200).
-- That migration refuses to run while a submitted purchase order is still open, and the demo has one:
-- PO-HCM-DEMO-0002 (CONFIRMED, V20261010000110). It received nothing (the demo has no receipt against it), so it is
-- cancelled. PO-HCM-DEMO-0001 (V20260928009000) stays a DRAFT: never submitted, it is archived as it is (and a draft
-- without a revision cannot be cancelled, ck_purchase_orders_revision). The two suppliers (GOVIET, and the print
-- subcontractor INNHANH of V20261010000510) are made inactive. All of it is then archived and dropped by
-- V20261113000200.
--
-- Found by number and code: a later migration re-keyed GOVIET (its id is no longer md5('demo:supplier:GOVIET')).
-- Versioned just below that migration so it runs first; the seeding migrations themselves are not edited (their
-- checksums are recorded wherever the demo ran).
-- =============================================================================

UPDATE procurement.purchase_orders
SET status           = 'CANCELLED',
    cancel_reason    = 'Purchasing removed from the system (demo)',
    version          = version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
WHERE po_number = 'PO-HCM-DEMO-0002'
  AND status NOT IN ('DRAFT', 'RECEIVED', 'CLOSED', 'CANCELLED');

UPDATE procurement.suppliers
SET status           = 'INACTIVE',
    version          = version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
WHERE code IN ('GOVIET', 'INNHANH')
  AND status = 'ACTIVE';
