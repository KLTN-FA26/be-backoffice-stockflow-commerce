-- =============================================================================
-- Functions the legacy-tables removal left behind with nothing calling them.
--
-- guard_sent_delivery_date and capture_receipt_completion were trigger functions on
-- procurement.purchase_order. C4 (V20261011000600) dropped that table and its triggers with it, but
-- a function does not depend on the triggers that call it, so both survived. They read columns and
-- statuses of the old table (SENT, sent_at, receipt_completed_at) that procurement.purchase_orders
-- does not have, so no trigger could ever use them again.
--
-- slug_of existed for the bridge triggers (V20261009000100) and the one-off carry-over
-- (V20261011000200), both gone. Slugs.of in the product module is the one implementation left.
--
-- IF EXISTS: an environment that hand-dropped one of them must not fail here.
-- =============================================================================

DROP FUNCTION IF EXISTS procurement.guard_sent_delivery_date();
DROP FUNCTION IF EXISTS procurement.capture_receipt_completion();
DROP FUNCTION IF EXISTS product.slug_of(TEXT);
