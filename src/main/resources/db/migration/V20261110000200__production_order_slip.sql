-- 4A-3 (kltn-docs 17 §4.3 step 7, BR-23): the production order slip. Approving a production order sends it: the
-- system draws the slip (PDF) the producer works from and sends it through the producer's channel - e-mailed with
-- time-limited links to it and to the print files, or handed over by the coordinator. The slip is kept as it was sent,
-- one per production order, so what the producer was told can always be shown again and checked byte for byte: the
-- SHA-256 recorded here is the stored object's, and a download is refused when the object no longer hashes to it.

CREATE TABLE ordering.production_order_slip
(
    production_order_id UUID         NOT NULL,
    -- FileCategory.DOCUMENT, under a key the store chose; never built by hand.
    storage_key         VARCHAR(512) NOT NULL,
    sha256              CHAR(64)     NOT NULL,
    size_bytes          BIGINT       NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_production_order_slip PRIMARY KEY (production_order_id),
    CONSTRAINT fk_production_order_slip_order FOREIGN KEY (production_order_id)
        REFERENCES ordering.production_order (id),
    CONSTRAINT uk_production_order_slip_key UNIQUE (storage_key),
    CONSTRAINT ck_production_order_slip_key CHECK (storage_key LIKE 'documents/%'),
    CONSTRAINT ck_production_order_slip_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    -- A PDF of at least one byte, within FileCategory.DOCUMENT's 25 MiB.
    CONSTRAINT ck_production_order_slip_size CHECK (size_bytes BETWEEN 1 AND 26214400)
);

COMMENT ON TABLE ordering.production_order_slip IS
    '4A-3: the slip (PDF) a production order was sent with, as sent - one per production order, never changed';

-- The slip is what the producer was sent: written once, never changed or removed.
CREATE FUNCTION ordering.check_production_order_slip_kept() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'production order slip %: a slip is kept as it was sent', OLD.production_order_id
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER tg_production_order_slip_kept
    BEFORE UPDATE OR DELETE ON ordering.production_order_slip
    FOR EACH ROW EXECUTE FUNCTION ordering.check_production_order_slip_kept();

-- At commit: a slip belongs to a production order that was sent - approved, so sent (17 §4.3 step 7). Checked at
-- commit because the approval and its slip are written in one transaction, in whichever order the writer flushes.
CREATE FUNCTION ordering.check_production_order_slip_sent() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ordering.production_order p
                    WHERE p.id = NEW.production_order_id AND p.sent_at IS NOT NULL) THEN
        RAISE EXCEPTION 'production order slip %: only a sent production order has a slip', NEW.production_order_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER tg_production_order_slip_sent
    AFTER INSERT ON ordering.production_order_slip
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ordering.check_production_order_slip_sent();
