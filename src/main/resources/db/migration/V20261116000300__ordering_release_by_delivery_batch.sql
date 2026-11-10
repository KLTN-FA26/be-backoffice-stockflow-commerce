-- =============================================================================
-- 4B-8: an order is released, picked and handed over delivery batch by delivery batch (kltn-docs 17 §4.5, BR-03,
-- BR-28, BR-29; 07 BR-07; 09 BR-04, BR-18; 15 BR-08).
--
-- Why. The signed contract fixes a delivery schedule (14 BR-23): batches 1..n, each with its cartons per order line and
-- a delivery window. Until now the order went to the warehouse whole: every carton held before release, one pick task,
-- and a handover gated by every installment before delivery. The docs ship it batch by batch:
--   * cartons held for a line count for the earliest batch first, and each batch is released on its own by the
--     warehouse manager of the shipping warehouse, once its own cartons are held (BR-03, BR-29);
--   * each released batch is one pick task (07 BR-07);
--   * a batch's shipment is handed over only from the first day of its delivery window and only once the installments
--     paid before delivery that are tied to it (or to no batch) are paid (09 BR-04, BR-18; 15 BR-08);
--   * Sales moves the window of a batch none of whose cartons left, with a reason, a note and the customer's consent
--     on record (BR-28). The signed contract stays as it was signed; the window in force is the latest change.
--
-- What this adds:
--   1. the permission sales-order-releases:UPDATE (release, warehouse manager + System Admin). Cluster 2's
--      V20261115000200 (team plan 5-7) creates the same row and grants; both are written ON CONFLICT DO NOTHING so the
--      final build runs either or both;
--   2. ordering.delivery_batch_hold: which delivery batch each hold of the order stands for. A hold is taken for one
--      batch's cartons of one line, and its allocations after release carry the batch on;
--   3. ordering.delivery_batch_release: when, where and by whom each batch was released, in schedule order;
--   4. ordering.order_shipment.delivery_batch_id: the batch a shipment handed over belongs to;
--   5. ordering.delivery_batch_window_change: the history of window changes (append-only);
--   6. fulfillment.pick.delivery_batch_id: one pick per (order, batch); uk_fulfillment_pick_order becomes
--      uk_fulfillment_pick_order_batch (NULLS NOT DISTINCT: an order without a schedule keeps one pick).
--
-- Data: no backfill. Orders already released keep their one pick and their shipments without a batch: they are worked
-- as before (QD-1: the database is rebuilt on deploy). Nothing is archived.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. Release permission (same row and grants as cluster 2's V20261115000200)
-- ---------------------------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'sales-order-releases:UPDATE', 'sales-order-releases', 'UPDATE',
        'Release an order to a warehouse: its stock is allocated on bins there (kltn-docs 17 BR-03)', 0, NOW(),
        'flyway')
ON CONFLICT (code) DO NOTHING;

WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
      JOIN identity.permission permission ON permission.code = 'sales-order-releases:UPDATE'
     WHERE role.code IN ('WAREHOUSE_MANAGER', 'SYSTEM_ADMIN')
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);


-- ---------------------------------------------------------------------------------------------
-- 2. Which batch a hold stands for
-- ---------------------------------------------------------------------------------------------
CREATE TABLE ordering.delivery_batch_hold (
    reservation_id    UUID        NOT NULL,
    delivery_batch_id UUID        NOT NULL,
    order_id          UUID        NOT NULL,
    order_line_id     UUID        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_delivery_batch_hold PRIMARY KEY (reservation_id),
    CONSTRAINT fk_delivery_batch_hold_reservation FOREIGN KEY (reservation_id)
        REFERENCES inventory.stock_reservation (id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_delivery_batch_hold_batch FOREIGN KEY (delivery_batch_id)
        REFERENCES ordering.delivery_batch (id) ON DELETE RESTRICT,
    CONSTRAINT fk_delivery_batch_hold_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_delivery_batch_hold_line FOREIGN KEY (order_line_id)
        REFERENCES ordering.order_line (id) ON DELETE CASCADE
);
CREATE INDEX ix_delivery_batch_hold_batch ON ordering.delivery_batch_hold (delivery_batch_id);
CREATE INDEX ix_delivery_batch_hold_order ON ordering.delivery_batch_hold (order_id);
COMMENT ON TABLE ordering.delivery_batch_hold IS
    '4B-8 (17 §4.2 step 3, BR-29): the delivery batch a hold of the order (a SKU reservation, or an allocation after '
    'release) stands for. A batch of the order''s own contract, a line it carries.';


-- ---------------------------------------------------------------------------------------------
-- 3. Batch releases
-- ---------------------------------------------------------------------------------------------
CREATE TABLE ordering.delivery_batch_release (
    delivery_batch_id UUID        NOT NULL,
    order_id          UUID        NOT NULL,
    seq               SMALLINT    NOT NULL,
    warehouse_id      UUID        NOT NULL,
    released_at       TIMESTAMPTZ NOT NULL,
    released_by       UUID        NOT NULL,
    CONSTRAINT pk_delivery_batch_release PRIMARY KEY (delivery_batch_id),
    CONSTRAINT uk_delivery_batch_release_seq UNIQUE (order_id, seq),
    CONSTRAINT ck_delivery_batch_release_seq CHECK (seq BETWEEN 1 AND 20),
    CONSTRAINT fk_delivery_batch_release_batch FOREIGN KEY (delivery_batch_id)
        REFERENCES ordering.delivery_batch (id) ON DELETE RESTRICT,
    CONSTRAINT fk_delivery_batch_release_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_delivery_batch_release_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse.warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_delivery_batch_release_by FOREIGN KEY (released_by) REFERENCES identity.app_user (id)
);
COMMENT ON TABLE ordering.delivery_batch_release IS
    '4B-8 (17 BR-03, BR-29): a delivery batch released to the shipping warehouse - in schedule order, the batch before '
    'it already handed over, from the order''s shipping warehouse. Written once.';


-- ---------------------------------------------------------------------------------------------
-- 4. The batch of a shipment
-- ---------------------------------------------------------------------------------------------
ALTER TABLE ordering.order_shipment ADD COLUMN delivery_batch_id UUID;
ALTER TABLE ordering.order_shipment ADD CONSTRAINT fk_order_shipment_delivery_batch FOREIGN KEY (delivery_batch_id)
    REFERENCES ordering.delivery_batch (id) ON DELETE RESTRICT;
CREATE INDEX ix_order_shipment_delivery_batch ON ordering.order_shipment (delivery_batch_id)
    WHERE delivery_batch_id IS NOT NULL;


-- ---------------------------------------------------------------------------------------------
-- 5. Window changes (BR-28)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE ordering.delivery_batch_window_change (
    id                  UUID          NOT NULL,
    delivery_batch_id   UUID          NOT NULL,
    order_id            UUID          NOT NULL,
    old_window_start    DATE          NOT NULL,
    old_window_end      DATE          NOT NULL,
    new_window_start    DATE          NOT NULL,
    new_window_end      DATE          NOT NULL,
    reason_code         VARCHAR(24)   NOT NULL,
    note                VARCHAR(1000) NOT NULL,
    evidence_file_key   VARCHAR(512),
    evidence_file_name  VARCHAR(255),
    evidence_message_id UUID,
    changed_by          UUID          NOT NULL,
    changed_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_delivery_batch_window_change PRIMARY KEY (id),
    CONSTRAINT ck_delivery_batch_window_change_window CHECK (new_window_start <= new_window_end
        AND old_window_start <= old_window_end
        AND (new_window_start, new_window_end) IS DISTINCT FROM (old_window_start, old_window_end)),
    CONSTRAINT ck_delivery_batch_window_change_not_past CHECK (
        new_window_start >= (changed_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date),
    CONSTRAINT ck_delivery_batch_window_change_reason CHECK (reason_code IN ('CUSTOMER_REQUEST', 'SELLER_DELAY')),
    CONSTRAINT ck_delivery_batch_window_change_note CHECK (btrim(note) <> ''),
    CONSTRAINT ck_delivery_batch_window_change_evidence CHECK (
        (evidence_file_key IS NULL) = (evidence_file_name IS NULL)
        AND num_nonnulls(evidence_file_key, evidence_message_id) = 1),
    CONSTRAINT fk_delivery_batch_window_change_batch FOREIGN KEY (delivery_batch_id)
        REFERENCES ordering.delivery_batch (id) ON DELETE RESTRICT,
    CONSTRAINT fk_delivery_batch_window_change_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_delivery_batch_window_change_message FOREIGN KEY (evidence_message_id)
        REFERENCES chat.message (id) ON DELETE RESTRICT,
    CONSTRAINT fk_delivery_batch_window_change_by FOREIGN KEY (changed_by) REFERENCES identity.app_user (id)
);
CREATE INDEX ix_delivery_batch_window_change_batch
    ON ordering.delivery_batch_window_change (delivery_batch_id, changed_at);
COMMENT ON TABLE ordering.delivery_batch_window_change IS
    '4B-8 (17 BR-28): a delivery window moved by Sales with the customer''s consent. The latest row of a batch is its '
    'window in force; the signed contract keeps the window it was signed with. Append-only.';

CREATE TRIGGER tg_delivery_batch_window_change_append_only
    BEFORE UPDATE OR DELETE ON ordering.delivery_batch_window_change
    FOR EACH ROW EXECUTE FUNCTION platform.append_only();


-- ---------------------------------------------------------------------------------------------
-- The rules the application keeps, kept again here
-- ---------------------------------------------------------------------------------------------
-- The batch of a hold, a release, a shipment or a window change is one of the order's live contract; a release is in
-- schedule order (the batch before it released and handed over), at the order's shipping warehouse; a window changes
-- only while no shipment of its batch went, never into the past (CHECK) and never out of the schedule's order.
CREATE OR REPLACE FUNCTION ordering.check_delivery_batch_of_order()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
DECLARE
    the_batch   RECORD;
    the_order   RECORD;
    previous     UUID;
    bad_seq      INTEGER;
    starts_on    DATE;
BEGIN
    IF NEW.delivery_batch_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT b.id, b.seq, b.window_start, b.window_end, c.order_id, c.status AS contract_status, c.contract_number
      INTO the_batch
      FROM ordering.delivery_batch b JOIN ordering.sales_contract c ON c.id = b.contract_id
     WHERE b.id = NEW.delivery_batch_id;
    IF NOT FOUND OR the_batch.order_id <> NEW.order_id OR the_batch.contract_status NOT IN ('SIGNED', 'COMPLETED') THEN
        RAISE EXCEPTION 'delivery batch % is not of the signed contract of order %', NEW.delivery_batch_id, NEW.order_id
            USING ERRCODE = 'check_violation';
    END IF;

    IF TG_TABLE_NAME = 'delivery_batch_release' THEN
        SELECT o.shipping_warehouse_id INTO the_order FROM ordering.customer_order o WHERE o.id = NEW.order_id;
        IF NEW.seq <> the_batch.seq THEN
            RAISE EXCEPTION 'release of batch %: seq % is not the batch''s %', NEW.delivery_batch_id, NEW.seq,
                the_batch.seq USING ERRCODE = 'check_violation';
        END IF;
        IF the_order.shipping_warehouse_id IS DISTINCT FROM NEW.warehouse_id THEN
            RAISE EXCEPTION 'release of batch % of order %: not at the order''s shipping warehouse (17 BR-22)',
                the_batch.seq, NEW.order_id USING ERRCODE = 'check_violation';
        END IF;
        IF the_batch.seq > 1 THEN
            SELECT b.id INTO previous FROM ordering.delivery_batch b
              JOIN ordering.sales_contract c ON c.id = b.contract_id
             WHERE c.order_id = NEW.order_id AND c.status IN ('SIGNED', 'COMPLETED') AND b.seq = the_batch.seq - 1;
            IF NOT EXISTS (SELECT 1 FROM ordering.delivery_batch_release r WHERE r.delivery_batch_id = previous)
                OR NOT EXISTS (SELECT 1 FROM ordering.order_shipment s WHERE s.delivery_batch_id = previous) THEN
                RAISE EXCEPTION 'batch % of order % is released after batch % is handed over (17 BR-29)',
                    the_batch.seq, NEW.order_id, the_batch.seq - 1 USING ERRCODE = 'check_violation';
            END IF;
        END IF;
    ELSIF TG_TABLE_NAME = 'order_shipment' THEN
        IF NOT EXISTS (SELECT 1 FROM ordering.delivery_batch_release r WHERE r.delivery_batch_id = NEW.delivery_batch_id)
        THEN
            RAISE EXCEPTION 'a shipment of order % names batch %, which is not released', NEW.order_id, the_batch.seq
                USING ERRCODE = 'check_violation';
        END IF;
        -- 09 BR-04: not handed over before the first day of the batch's window in force.
        starts_on := coalesce((SELECT w.new_window_start FROM ordering.delivery_batch_window_change w
                                   WHERE w.delivery_batch_id = NEW.delivery_batch_id
                                   ORDER BY w.changed_at DESC, w.id DESC LIMIT 1), the_batch.window_start);
        IF TG_OP = 'INSERT' AND starts_on IS NOT NULL
            AND (NEW.handed_over_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date < starts_on THEN
            RAISE EXCEPTION 'batch % of order % is handed over from %, its window in force (09 BR-04)', the_batch.seq,
                NEW.order_id, starts_on USING ERRCODE = 'check_violation';
        END IF;
    ELSIF TG_TABLE_NAME = 'delivery_batch_window_change' THEN
        IF EXISTS (SELECT 1 FROM ordering.order_shipment s WHERE s.delivery_batch_id = NEW.delivery_batch_id) THEN
            RAISE EXCEPTION 'batch % of order %: a carton of it was handed over; its window no longer moves (17 BR-28)',
                the_batch.seq, NEW.order_id USING ERRCODE = 'check_violation';
        END IF;
        -- The windows in force after this change, batch by batch: none starts before the batch before it.
        WITH in_force AS (
            SELECT b.seq,
                   coalesce(CASE WHEN b.id = NEW.delivery_batch_id THEN NEW.new_window_start END,
                            (SELECT w.new_window_start FROM ordering.delivery_batch_window_change w
                              WHERE w.delivery_batch_id = b.id ORDER BY w.changed_at DESC, w.id DESC LIMIT 1),
                            b.window_start) AS window_start
              FROM ordering.delivery_batch b
              JOIN ordering.sales_contract c ON c.id = b.contract_id
             WHERE c.order_id = NEW.order_id AND c.status IN ('SIGNED', 'COMPLETED')
        )
        SELECT later.seq INTO bad_seq
          FROM in_force later JOIN in_force earlier ON earlier.seq = later.seq - 1
         WHERE later.window_start < earlier.window_start
         LIMIT 1;
        IF bad_seq IS NOT NULL THEN
            RAISE EXCEPTION 'order %: batch % would start before batch % (17 BR-28); move the later batches too',
                NEW.order_id, bad_seq, bad_seq - 1 USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER tg_delivery_batch_hold_order BEFORE INSERT OR UPDATE ON ordering.delivery_batch_hold
    FOR EACH ROW EXECUTE FUNCTION ordering.check_delivery_batch_of_order();
CREATE TRIGGER tg_delivery_batch_release_order BEFORE INSERT ON ordering.delivery_batch_release
    FOR EACH ROW EXECUTE FUNCTION ordering.check_delivery_batch_of_order();
CREATE TRIGGER tg_delivery_batch_release_append_only BEFORE UPDATE OR DELETE ON ordering.delivery_batch_release
    FOR EACH ROW EXECUTE FUNCTION platform.append_only();
CREATE TRIGGER tg_order_shipment_delivery_batch BEFORE INSERT OR UPDATE OF delivery_batch_id
    ON ordering.order_shipment
    FOR EACH ROW EXECUTE FUNCTION ordering.check_delivery_batch_of_order();
CREATE TRIGGER tg_delivery_batch_window_change_order BEFORE INSERT ON ordering.delivery_batch_window_change
    FOR EACH ROW EXECUTE FUNCTION ordering.check_delivery_batch_of_order();


-- ---------------------------------------------------------------------------------------------
-- 6. One pick per (order, batch)
-- ---------------------------------------------------------------------------------------------
ALTER TABLE fulfillment.pick ADD COLUMN delivery_batch_id UUID;
ALTER TABLE fulfillment.pick ADD CONSTRAINT fk_pick_delivery_batch FOREIGN KEY (delivery_batch_id)
    REFERENCES ordering.delivery_batch (id) ON DELETE RESTRICT;
ALTER TABLE fulfillment.pick DROP CONSTRAINT uk_fulfillment_pick_order;
ALTER TABLE fulfillment.pick ADD CONSTRAINT uk_fulfillment_pick_order_batch
    UNIQUE NULLS NOT DISTINCT (order_id, delivery_batch_id);
COMMENT ON COLUMN fulfillment.pick.delivery_batch_id IS
    '4B-8 (07 BR-07): the released delivery batch this task picks; null for an order without a delivery schedule.';

-- A task picks a batch that is released, of its own order.
CREATE OR REPLACE FUNCTION fulfillment.check_pick_delivery_batch()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.delivery_batch_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM ordering.delivery_batch_release r
         WHERE r.delivery_batch_id = NEW.delivery_batch_id AND r.order_id = NEW.order_id) THEN
        RAISE EXCEPTION 'pick % of order %: batch % is not a released batch of that order', NEW.id, NEW.order_id,
            NEW.delivery_batch_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER tg_pick_delivery_batch BEFORE INSERT OR UPDATE OF delivery_batch_id, order_id ON fulfillment.pick
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_pick_delivery_batch();
