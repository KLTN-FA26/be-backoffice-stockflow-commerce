-- SCRUM-193/209: the customer's "my receivables" list shows the order number. The payment module may not read the order
-- module (order depends on payment), so the number is copied onto the receivable when it is opened from the order
-- event. Nullable: a row opened before this column is backfilled below; the application tolerates a missing value.
ALTER TABLE payment.receivable ADD COLUMN order_number VARCHAR(32);

UPDATE payment.receivable r
SET order_number = o.order_number
FROM ordering.customer_order o
WHERE o.id = r.order_id;
