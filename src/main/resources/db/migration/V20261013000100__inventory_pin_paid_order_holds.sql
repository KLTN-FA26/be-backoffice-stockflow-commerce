-- =============================================================================
-- Inventory: a hold expires only while its order waits for payment (SCRUM-465).
-- Docs: kltn-docs 14 BR-07 ("reservation tồn có thời hạn với đơn chờ trả trước / chờ cọc; hết hạn
-- chưa thanh toán → giải phóng, đơn Cancelled"), glossary "Reservation / Giữ hàng".
--
-- Until now every hold expired 30 minutes after checkout, paid or not, so the sweeper could release
-- the stock of an order that had already paid: the same goods could be sold twice, and the pick
-- would later fail to consume a hold that no longer existed.
--
-- expires_at NULL now means "pinned": held until the stock is consumed or the order releases it.
-- An EXPIRED hold must have had a deadline.
--
-- Holds already on orders that left PENDING_PAYMENT (paid, released, in production, in fulfilment,
-- on hold) are pinned here, so the fix also covers orders placed before it.
-- =============================================================================

ALTER TABLE inventory.stock_reservation
    ALTER COLUMN expires_at DROP NOT NULL,
    ADD CONSTRAINT ck_stock_reservation_expired_had_deadline
        CHECK (status <> 'EXPIRED' OR expires_at IS NOT NULL);

UPDATE inventory.stock_reservation r
SET expires_at = NULL
FROM ordering.customer_order o
WHERE r.order_id = o.id
  AND r.status = 'HELD'
  AND o.status IN ('PAID', 'IN_PRODUCTION', 'READY_TO_FULFILL', 'IN_FULFILMENT', 'ON_HOLD');
