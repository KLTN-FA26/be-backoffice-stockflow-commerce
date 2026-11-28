-- =============================================================================
-- A bulk transfer's request id names one request (review finding P1 of the sales-owner work, 2026-11-28). Module:
-- customer.
--
-- POST /customers/sales-owner-transfers is idempotent on requestId: the batch row is claimed with ON CONFLICT DO
-- NOTHING and a retry answers what the first request did. A request sent with an id already used for a different body
-- got that first result back, as if it had been done. The batch now keeps what its request asked, hashed (SHA-256 hex
-- of the source, the targets in order, the customers named or "all", the reason, the note, temporary and notify): the
-- same body is a retry, any other body is refused SALES_OWNER_TRANSFER_REQUEST_REUSED (409). Null for the batches made
-- before (they cannot be told apart and keep answering as they did) and for returns, whose id is derived from the batch
-- they return.
-- =============================================================================

ALTER TABLE customer.sales_owner_transfer_batch
    ADD COLUMN request_fingerprint VARCHAR(64);

ALTER TABLE customer.sales_owner_transfer_batch
    ADD CONSTRAINT ck_sales_owner_transfer_batch_fingerprint
        CHECK (request_fingerprint IS NULL OR request_fingerprint ~ '^[0-9a-f]{64}$');

COMMENT ON COLUMN customer.sales_owner_transfer_batch.request_fingerprint IS
    'SHA-256 (hex) of what the request asked; a request id sent again with another body is refused (finding P1).';
