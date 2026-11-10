-- =============================================================================
-- The carton check and labelling station (team plan 4C-6; kltn-docs 08 §4.1-§4.4, §5.1, §5.2, BR-01..BR-11; 17 BR-12,
-- BR-16). It rebuilds packing: the LPN carton the producer packed IS the shipping carton (08 BR-02), so nothing is
-- packed again. A warehouse worker starts the order's check session, scans the LPN of every carton picked for the task,
-- has the system put the carton spec's size and weight on it, prints and sticks one shipping label per carton, and
-- completes: the labelled cartons become one shipment READY_TO_DISPATCH.
--
-- What this states in the database (each rule is first in the code named after it):
--   1. fulfillment.pack is the check session, one per task: PENDING -> IN_PROGRESS -> COMPLETED, or CANCELLED with its
--      order (08 §5.1). The repack station's PACKING and PACKED are those same two steps and are renamed to the
--      documented names. CartonCheck.
--   2. fulfillment.package is also the shipping carton (08 §5.2): a row naming a picked pick line and its LPN, TO_CHECK
--      -> CHECKED -> LABELED, or REMOVED from TO_CHECK, CHECKED, or LABELED while in no shipment. A carton row is never
--      a repack parcel and the reverse (ck_package_kind); it names a carton picked for its own task, starts TO_CHECK
--      and keeps its carton, line and session (tg_package_carton); one row per pick line (uk_package_pick_line), and a
--      carton is checked for one order at a time (uk_package_lpn_checking). A LABELED carton has its size, weight and
--      volumetric weight (BR-07, BR-09) and a label version, bumped by every reprint (BR-08). A REMOVED carton says why:
--      DAMAGED by a worker (08 §4.2 step 4), or ORDER_CANCELLED by the system (BR-04). ShippingCarton.
--      Only SEALED (repack) or LABELED (carton) rows go into a shipment, and the load plan and manifest triggers take
--      either (check_load_package, check_load_plan_package).
--   3. A pick line whose carton is live at the station is not put back: only a REMOVED carton goes to the put-back queue
--      (tg_pick_line_carton_removed). PickList.putBack.
--   4. ordering.customer_order.first_labeled_at: set in the transaction that labels the order's first carton, never
--      changed afterwards. From then the order is not cancelled (ck_order_labeled_not_cancelled) and its delivery
--      address does not change (tg_order_label_lock) - 08 BR-04, 17 BR-12, BR-16. A LABELED carton exists only for an
--      order so locked (tg_package_label_locks_order, at commit). Order.recordFirstLabel.
--
-- Changes data: pack statuses are renamed; an order already packed by the repack station counts as labelled from when it
-- was packed (that was its lock); repack parcels of a session still in progress that are in no shipment are voided, so
-- the session goes on at the new station. Nothing is archived and nothing stops the upgrade: the owner's decision QD-1
-- (2026-11-10) rebuilds the database on deploy, so only the demo data of earlier migrations has to pass. The repack
-- station's own tables and columns stay until team plan 4C-7.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. The check session
-- ---------------------------------------------------------------------------------------------
ALTER TABLE fulfillment.pack
    DROP CONSTRAINT ck_pack_status,
    DROP CONSTRAINT ck_pack_started,
    DROP CONSTRAINT ck_pack_packed;

UPDATE fulfillment.pack
   SET status = CASE status WHEN 'PACKING' THEN 'IN_PROGRESS' ELSE 'COMPLETED' END,
       version = version + 1, last_modified_at = NOW(), last_modified_by = 'V20261113000700'
 WHERE status IN ('PACKING', 'PACKED');

ALTER TABLE fulfillment.pack
    ADD CONSTRAINT ck_pack_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    ADD CONSTRAINT ck_pack_started CHECK (status NOT IN ('IN_PROGRESS', 'COMPLETED') OR started_at IS NOT NULL),
    ADD CONSTRAINT ck_pack_packed CHECK (status <> 'COMPLETED' OR packed_at IS NOT NULL);

-- QD-1: whatever the repack station left half done is undone, so a session in progress goes on as a carton check.
UPDATE fulfillment.package p
   SET status = 'VOIDED', voided_at = NOW(), version = p.version + 1, last_modified_at = NOW(),
       last_modified_by = 'V20261113000700'
  FROM fulfillment.pack k
 WHERE k.id = p.pack_id AND k.status IN ('PENDING', 'IN_PROGRESS') AND p.status IN ('OPEN', 'SEALED')
   AND p.shipment_id IS NULL;


-- ---------------------------------------------------------------------------------------------
-- 2. The shipping carton
-- ---------------------------------------------------------------------------------------------
ALTER TABLE fulfillment.package
    ADD COLUMN pick_line_id   UUID,
    ADD COLUMN lpn            VARCHAR(30),
    ADD COLUMN checked_at     TIMESTAMPTZ,
    ADD COLUMN checked_by     UUID,
    ADD COLUMN labeled_at     TIMESTAMPTZ,
    ADD COLUMN labeled_by     UUID,
    ADD COLUMN label_version  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN removed_at     TIMESTAMPTZ,
    ADD COLUMN removed_by     UUID,
    ADD COLUMN removal_reason VARCHAR(16),
    ADD COLUMN removal_note   VARCHAR(500);

COMMENT ON COLUMN fulfillment.package.pick_line_id IS
    '4C-6: the pick line that took this shipping carton; null for a parcel of the repack station (removed in 4C-7)';
COMMENT ON COLUMN fulfillment.package.lpn IS
    '4C-6: the LPN of the carton the producer packed - the shipping carton itself (kltn-docs 08 BR-02)';
COMMENT ON COLUMN fulfillment.package.label_version IS
    '4C-6: 0 before the first shipping label, then 1, plus one for every reprint: only the latest is valid (08 BR-08)';

ALTER TABLE fulfillment.package
    ADD CONSTRAINT fk_package_pick_line FOREIGN KEY (pick_line_id) REFERENCES fulfillment.pick_line (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_package_lpn FOREIGN KEY (lpn) REFERENCES inventory.stock_package (lpn),
    ADD CONSTRAINT fk_package_checked_by FOREIGN KEY (checked_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_package_labeled_by FOREIGN KEY (labeled_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT fk_package_removed_by FOREIGN KEY (removed_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT uk_package_pick_line UNIQUE (pick_line_id);

CREATE UNIQUE INDEX uk_package_lpn_checking ON fulfillment.package (lpn) WHERE status IN ('TO_CHECK', 'CHECKED');

ALTER TABLE fulfillment.package
    DROP CONSTRAINT ck_package_status,
    DROP CONSTRAINT ck_package_shipped_sealed;

ALTER TABLE fulfillment.package
    ADD CONSTRAINT ck_package_status CHECK (status IN
        ('OPEN', 'SEALED', 'VOIDED', 'TO_CHECK', 'CHECKED', 'LABELED', 'REMOVED')),
    -- A repack parcel or a shipping carton, never a mix: a carton is one CARTON, no kit, never sealed nor voided.
    ADD CONSTRAINT ck_package_kind CHECK (
        (pick_line_id IS NULL AND lpn IS NULL AND status IN ('OPEN', 'SEALED', 'VOIDED')
            AND checked_at IS NULL AND labeled_at IS NULL AND removed_at IS NULL AND label_version = 0)
        OR (pick_line_id IS NOT NULL AND lpn IS NOT NULL AND status IN ('TO_CHECK', 'CHECKED', 'LABELED', 'REMOVED')
            AND package_type = 'CARTON' AND kit_group_id IS NULL AND sealed_at IS NULL AND voided_at IS NULL)),
    ADD CONSTRAINT ck_package_checked CHECK ((checked_at IS NULL) = (checked_by IS NULL)
        AND (status NOT IN ('CHECKED', 'LABELED') OR checked_at IS NOT NULL)),
    -- BR-07, BR-09: a label carries the carton spec's size and weight and the volumetric weight; BR-08: its version.
    ADD CONSTRAINT ck_package_labeled CHECK (label_version >= 0
        AND (labeled_at IS NULL) = (labeled_by IS NULL) AND (labeled_at IS NULL) = (label_version = 0)
        AND (status <> 'LABELED' OR (labeled_at IS NOT NULL AND checked_at IS NOT NULL AND weight_kg IS NOT NULL
            AND length_cm IS NOT NULL AND width_cm IS NOT NULL AND height_cm IS NOT NULL
            AND dim_weight_kg IS NOT NULL))),
    -- 08 §4.2 step 4: a worker removes a damaged carton, saying so; BR-04: the system removes the cartons of an order
    -- cancelled before its first label.
    ADD CONSTRAINT ck_package_removed CHECK ((status = 'REMOVED') = (removed_at IS NOT NULL)
        AND (removed_at IS NULL) = (removal_reason IS NULL)
        AND (removal_reason IS NULL OR removal_reason IN ('DAMAGED', 'ORDER_CANCELLED'))
        AND (removal_reason IS DISTINCT FROM 'DAMAGED' OR removed_by IS NOT NULL)
        AND (removal_note IS NULL OR btrim(removal_note) <> '')),
    -- 09: only a sealed parcel or a labelled carton ships; a carton removed while labelled was in no shipment (08 §5.2).
    ADD CONSTRAINT ck_package_shipped CHECK (shipment_id IS NULL OR status IN ('SEALED', 'VOIDED', 'LABELED'));

-- A carton row names a carton picked for its own task, starts TO_CHECK, keeps its carton, line and session, and moves
-- only along 08 §5.2.
CREATE FUNCTION fulfillment.check_package_carton()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    line      fulfillment.pick_line%ROWTYPE;
    pack_pick UUID;
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.pick_line_id IS DISTINCT FROM OLD.pick_line_id OR NEW.lpn IS DISTINCT FROM OLD.lpn
            OR (OLD.pick_line_id IS NOT NULL AND NEW.pack_id IS DISTINCT FROM OLD.pack_id)) THEN
        RAISE EXCEPTION 'tg_package_carton: package % keeps its carton, pick line and check session', OLD.label_code
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_carton';
    END IF;
    IF NEW.pick_line_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO line FROM fulfillment.pick_line WHERE id = NEW.pick_line_id;
        SELECT pick_id INTO pack_pick FROM fulfillment.pack WHERE id = NEW.pack_id;
        IF line.pick_id IS DISTINCT FROM pack_pick OR line.status IS DISTINCT FROM 'PICKED'
                OR upper(line.lpn) IS DISTINCT FROM upper(NEW.lpn) THEN
            RAISE EXCEPTION 'tg_package_carton: carton % is not a carton picked for this task (08 BR-05)', NEW.lpn
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_carton';
        END IF;
        IF NEW.status <> 'TO_CHECK' THEN
            RAISE EXCEPTION 'tg_package_carton: carton % starts TO_CHECK, not %', NEW.lpn, NEW.status
                USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_carton';
        END IF;
    ELSIF NEW.status IS DISTINCT FROM OLD.status AND NOT (
            (OLD.status = 'TO_CHECK' AND NEW.status IN ('CHECKED', 'REMOVED'))
            OR (OLD.status = 'CHECKED' AND NEW.status IN ('LABELED', 'REMOVED'))
            OR (OLD.status = 'LABELED' AND NEW.status = 'REMOVED')) THEN
        RAISE EXCEPTION 'tg_package_carton: carton % does not go from % to % (08 §5.2)', NEW.lpn, OLD.status, NEW.status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_carton';
    ELSIF OLD.status = 'LABELED' AND NEW.status = 'LABELED' AND NEW.label_version < OLD.label_version THEN
        RAISE EXCEPTION 'tg_package_carton: the label of carton % is reprinted forward only', NEW.lpn
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_carton';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_package_carton
    BEFORE INSERT OR UPDATE ON fulfillment.package
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_package_carton();

-- The shipping parcels the load plan and the manifest take: a sealed repack parcel or a labelled carton.
CREATE OR REPLACE FUNCTION fulfillment.check_load_package()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    p fulfillment.package%ROWTYPE;
BEGIN
    IF NOT NEW.active OR (TG_OP = 'UPDATE' AND OLD.active) THEN
        RETURN NEW;
    END IF;
    SELECT * INTO p FROM fulfillment.package WHERE id = NEW.package_id;
    IF p.status NOT IN ('SEALED', 'LABELED') OR p.shipment_id IS DISTINCT FROM NEW.shipment_id THEN
        RAISE EXCEPTION 'package % is not a sealed parcel or labelled carton of shipment %', p.label_code,
            NEW.shipment_id USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM fulfillment.dispatch_load_shipment ls
                    JOIN fulfillment.dispatch_load l ON l.id = ls.load_id
                   WHERE ls.load_id = NEW.load_id AND ls.active AND ls.shipment_id = NEW.shipment_id
                     AND l.status IN ('PLANNED', 'VEHICLE_BOOKED', 'LOADING')) THEN
        RAISE EXCEPTION 'package % is not on an open manifest with its shipment', p.label_code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION fulfillment.check_load_plan_package()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    p    fulfillment.package%ROWTYPE;
    s    fulfillment.shipment%ROWTYPE;
    plan fulfillment.load_plan%ROWTYPE;
BEGIN
    IF NOT NEW.active OR (TG_OP = 'UPDATE' AND OLD.active) THEN
        RETURN NEW;
    END IF;
    SELECT * INTO p FROM fulfillment.package WHERE id = NEW.package_id;
    SELECT * INTO plan FROM fulfillment.load_plan WHERE id = NEW.load_plan_id;
    IF p.status NOT IN ('SEALED', 'LABELED') OR p.weight_kg IS NULL OR p.weight_kg <= 0 OR p.length_cm IS NULL
        OR p.length_cm <= 0 OR p.width_cm IS NULL OR p.width_cm <= 0 OR p.height_cm IS NULL OR p.height_cm <= 0
        OR p.shipment_id IS DISTINCT FROM NEW.shipment_id THEN
        RAISE EXCEPTION 'tg_load_plan_package_check: package % is not a sealed or labelled, measured parcel of its '
            'shipment', p.label_code USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_load_plan_package_check';
    END IF;
    SELECT * INTO s FROM fulfillment.shipment WHERE id = NEW.shipment_id;
    IF s.status <> 'READY_TO_DISPATCH' OR s.warehouse_id <> plan.warehouse_id THEN
        RAISE EXCEPTION 'tg_load_plan_package_check: shipment % is not waiting for dispatch at the plan''s warehouse',
            s.code USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_load_plan_package_check';
    END IF;
    RETURN NEW;
END
$$;


-- ---------------------------------------------------------------------------------------------
-- 3. A carton live at the station is not put back
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION fulfillment.check_pick_line_carton_removed()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM fulfillment.package p WHERE p.pick_line_id = NEW.id AND p.status <> 'REMOVED') THEN
        RAISE EXCEPTION 'tg_pick_line_carton_removed: carton % is at the check station; remove it there first', NEW.lpn
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_pick_line_carton_removed';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_pick_line_carton_removed
    BEFORE UPDATE OF status ON fulfillment.pick_line
    FOR EACH ROW WHEN (NEW.status = 'PUT_BACK' AND OLD.status IS DISTINCT FROM 'PUT_BACK')
    EXECUTE FUNCTION fulfillment.check_pick_line_carton_removed();


-- ---------------------------------------------------------------------------------------------
-- 4. The first labelled carton locks the order
-- ---------------------------------------------------------------------------------------------
ALTER TABLE ordering.customer_order ADD COLUMN first_labeled_at TIMESTAMPTZ;

COMMENT ON COLUMN ordering.customer_order.first_labeled_at IS
    '4C-6: when the first shipping label of the order was printed; from then it is not cancelled and its delivery '
    'address does not change (kltn-docs 08 BR-04, 17 BR-12, BR-16)';

-- An order the repack station packed was locked from then (decision 2026-10-17); it stays locked.
UPDATE ordering.customer_order
   SET first_labeled_at = packed_at
 WHERE packed_at IS NOT NULL AND status <> 'CANCELLED';

ALTER TABLE ordering.customer_order
    ADD CONSTRAINT ck_order_labeled_not_cancelled CHECK (first_labeled_at IS NULL OR status <> 'CANCELLED');

CREATE FUNCTION ordering.guard_order_label_lock()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.first_labeled_at IS NULL THEN
        RETURN NEW;
    END IF;
    IF NEW.first_labeled_at IS DISTINCT FROM OLD.first_labeled_at THEN
        RAISE EXCEPTION 'tg_order_label_lock: order % was first labelled at %; that does not change',
            OLD.order_number, OLD.first_labeled_at
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_order_label_lock';
    END IF;
    IF ROW(NEW.shipping_recipient_name, NEW.shipping_phone, NEW.shipping_line1, NEW.shipping_line2,
           NEW.shipping_ward_code, NEW.shipping_ward_name, NEW.shipping_province_code, NEW.shipping_province_name,
           NEW.shipping_country_code, NEW.shipping_postal_code)
       IS DISTINCT FROM
       ROW(OLD.shipping_recipient_name, OLD.shipping_phone, OLD.shipping_line1, OLD.shipping_line2,
           OLD.shipping_ward_code, OLD.shipping_ward_name, OLD.shipping_province_code, OLD.shipping_province_name,
           OLD.shipping_country_code, OLD.shipping_postal_code) THEN
        RAISE EXCEPTION 'tg_order_label_lock: order % has a labelled carton; its delivery address does not change '
            '(kltn-docs 17 BR-16)', OLD.order_number
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_order_label_lock';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_order_label_lock
    BEFORE UPDATE ON ordering.customer_order
    FOR EACH ROW EXECUTE FUNCTION ordering.guard_order_label_lock();

-- A labelled carton belongs to an order the label locked: checked at commit, since the label and the order's lock are
-- written by two modules in one transaction.
CREATE FUNCTION fulfillment.check_package_label_locks_order()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    locked TIMESTAMPTZ;
    order_no VARCHAR;
BEGIN
    SELECT o.first_labeled_at, o.order_number INTO locked, order_no
      FROM fulfillment.pack k
      JOIN fulfillment.pick p ON p.id = k.pick_id
      JOIN ordering.customer_order o ON o.id = p.order_id
     WHERE k.id = NEW.pack_id;
    IF locked IS NULL THEN
        RAISE EXCEPTION 'tg_package_label_locks_order: carton % is labelled but order % is not locked (08 BR-04)',
            NEW.lpn, order_no USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_label_locks_order';
    END IF;
    RETURN NEW;
END
$$;

CREATE CONSTRAINT TRIGGER tg_package_label_locks_order
    AFTER INSERT OR UPDATE OF status ON fulfillment.package
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.status = 'LABELED')
    EXECUTE FUNCTION fulfillment.check_package_label_locks_order();
