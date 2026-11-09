-- =============================================================================
-- 4A-4 (kltn-docs 17 §4.3 steps 8-10, §5.3, BR-24; 13 BR-03, BR-13; 14 BR-18; team plan 4A-4, QD-8): print sample
-- rounds, and the producer's acceptance with its expected completion day.
--
--   * ordering.production_order.expected_completion_date - the day the producer says the goods will be done, recorded
--     by the Production Coordinator when the producer accepts (17 §4.3 step 8) and corrected later. The column and
--     ck_production_order_expected_completion are word for word those of 4A-7 (V20261110000500), which reads the
--     column for AT_RISK: whichever of the two merges second drops its own copy of them.
--   * ck_production_order_accepted: ACCEPTED and SAMPLE_PENDING always carry that day - both are reached only through
--     the acceptance, which records it (17 §5.3: "Accepted - đơn vị sản xuất đã xác nhận nhận đơn và ngày hoàn thành dự
--     kiến"). From IN_PRODUCTION on it may be missing: goods that arrive while it is still SENT start mass printing
--     with no acceptance recorded (03 BR-15).
--   * ck_production_order_sample_pending: only a production order that wants a print sample waits for one (BR-24).
--   * ordering.production_order_sample_round - one round of print samples (17 §4.3 step 9): its number, the reprint
--     reason the coordinator classified (round 2 on), the day a physical sample was sent to the customer, and the
--     customer's one answer - APPROVED, or REPRINT_REQUESTED with a note (13 BR-13: one answer per round, never
--     changed). A new round only follows a reprint request; round 1 is opened from ACCEPTED, and opening it is what
--     moves the production order to SAMPLE_PENDING (tg_production_order_sample). Only the customer who owns the
--     production order answers - checked against the customer's portal account (13 BR-13).
--   * ordering.production_order_sample_media - the photos and videos of a round, in the object store (category
--     print-samples). Kept as uploaded: what the customer answered on is what stays.
--   * tg_production_order_sample: with a print sample to approve, mass printing starts only when the customer approves
--     the latest round (BR-24) - never from SENT or ACCEPTED, the coordinator's way for an order without one - and
--     SAMPLE_PENDING is entered only once round 1 is there.
--   * production-samples (VIEW_PAGE, READ, APPROVE): the storefront's "print samples to approve" (13 §4.3 step 5) -
--     the customer, scope OWN. The coordinator's steps are production-orders:UPDATE, which they hold already.
--
-- The free sample rounds are not stored: they are counted (round 1, and every round reprinted for a CUSTOMER_CHANGE -
-- 17 §4.3 step 9, PRINT_DEFECT rounds are not the customer's) and compared with the contract's free_sample_rounds
-- (14 BR-18) when read. Going past them warns and never blocks, so nothing here refuses it.
--
-- Nothing is back-filled: no production order is ACCEPTED or SAMPLE_PENDING where this runs - nothing could move one
-- there before this package, and no seed draws one up. The new trigger rules check transitions only, so a production
-- order already IN_PRODUCTION is left as it is.
-- =============================================================================

ALTER TABLE ordering.production_order
    ADD COLUMN expected_completion_date DATE;

-- 17 §4.3 step 8: the producer gives the day when it accepts the order - never before it was sent.
ALTER TABLE ordering.production_order
    ADD CONSTRAINT ck_production_order_expected_completion CHECK (expected_completion_date IS NULL
        OR status NOT IN ('DRAFT', 'PENDING_APPROVAL', 'SENT'));

COMMENT ON COLUMN ordering.production_order.expected_completion_date IS
    'The day the producer expects the goods done (17 §4.3 step 8), from ACCEPTED on; AT_RISK when after need_by_date.';

ALTER TABLE ordering.production_order
    ADD CONSTRAINT ck_production_order_accepted CHECK (status NOT IN ('ACCEPTED', 'SAMPLE_PENDING')
        OR expected_completion_date IS NOT NULL);

ALTER TABLE ordering.production_order
    ADD CONSTRAINT ck_production_order_sample_pending CHECK (status <> 'SAMPLE_PENDING' OR sample_approval_required);

CREATE TABLE ordering.production_order_sample_round
(
    id                      UUID         NOT NULL,
    production_order_id     UUID         NOT NULL,
    -- 17 §4.3 step 9: 1, 2, ... - each round after a reprint request.
    round_no                INTEGER      NOT NULL,
    -- Why it was printed again, classified by the coordinator (2026-11-07): PRINT_DEFECT is the producer's fault and is
    -- not counted against the contract's free rounds; CUSTOMER_CHANGE is. Round 1 is no reprint.
    reprint_reason          VARCHAR(16),
    -- Optional: a physical sample sent to the customer, and when.
    physical_sample_sent_on DATE,
    opened_by               UUID         NOT NULL,
    opened_at               TIMESTAMPTZ  NOT NULL,
    -- 13 BR-13: the customer's one answer.
    result                  VARCHAR(20),
    -- The customer writes only when asking for a reprint (17 §4.3 step 9, 2026-11-05).
    customer_note           VARCHAR(500),
    answered_by             UUID,
    answered_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_production_order_sample_round PRIMARY KEY (id),
    CONSTRAINT uk_production_order_sample_round UNIQUE (production_order_id, round_no),
    CONSTRAINT fk_production_order_sample_round_order FOREIGN KEY (production_order_id)
        REFERENCES ordering.production_order (id),
    CONSTRAINT fk_production_order_sample_round_opened_by FOREIGN KEY (opened_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_production_order_sample_round_answered_by FOREIGN KEY (answered_by)
        REFERENCES identity.app_user (id),
    CONSTRAINT ck_production_order_sample_round_no CHECK (round_no BETWEEN 1 AND 100),
    CONSTRAINT ck_production_order_sample_round_reprint CHECK ((round_no = 1) = (reprint_reason IS NULL)
        AND (reprint_reason IS NULL OR reprint_reason IN ('PRINT_DEFECT', 'CUSTOMER_CHANGE'))),
    CONSTRAINT ck_production_order_sample_round_result CHECK (result IS NULL
        OR result IN ('APPROVED', 'REPRINT_REQUESTED')),
    CONSTRAINT ck_production_order_sample_round_answered CHECK ((result IS NULL) = (answered_at IS NULL)
        AND (result IS NULL) = (answered_by IS NULL) AND (answered_at IS NULL OR answered_at >= opened_at)),
    -- A reprint request says what to change; an approval says nothing.
    CONSTRAINT ck_production_order_sample_round_note CHECK (
        COALESCE(result = 'REPRINT_REQUESTED', FALSE) = (customer_note IS NOT NULL)
        AND (customer_note IS NULL OR btrim(customer_note) <> ''))
);

CREATE INDEX ix_production_order_sample_round_waiting ON ordering.production_order_sample_round (production_order_id)
    WHERE result IS NULL;

COMMENT ON TABLE ordering.production_order_sample_round IS
    'Print sample rounds of a production order that wants one (kltn-docs 17 §4.3 step 9, BR-24): opened by the '
    'Production Coordinator with photos / videos, answered once by the customer who owns it (13 BR-13).';

CREATE TABLE ordering.production_order_sample_media
(
    id              UUID          NOT NULL,
    sample_round_id UUID          NOT NULL,
    storage_key     VARCHAR(512)  NOT NULL,
    file_name       VARCHAR(255)  NOT NULL,
    content_type    VARCHAR(100)  NOT NULL,
    size_bytes      BIGINT        NOT NULL,
    uploaded_by     UUID          NOT NULL,
    uploaded_at     TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_production_order_sample_media PRIMARY KEY (id),
    CONSTRAINT uk_production_order_sample_media_key UNIQUE (storage_key),
    CONSTRAINT fk_production_order_sample_media_round FOREIGN KEY (sample_round_id)
        REFERENCES ordering.production_order_sample_round (id),
    CONSTRAINT fk_production_order_sample_media_by FOREIGN KEY (uploaded_by) REFERENCES identity.app_user (id),
    -- FileCategory.PRINT_SAMPLE: its prefix and its types.
    CONSTRAINT ck_production_order_sample_media_key CHECK (storage_key LIKE 'print-samples/%'),
    CONSTRAINT ck_production_order_sample_media_type CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp',
                                                                             'video/mp4', 'video/quicktime',
                                                                             'video/webm')),
    CONSTRAINT ck_production_order_sample_media_size CHECK (size_bytes > 0),
    CONSTRAINT ck_production_order_sample_media_name CHECK (btrim(file_name) <> '')
);

CREATE INDEX ix_production_order_sample_media_round ON ordering.production_order_sample_media (sample_round_id);

-- A round is opened on a production order that wants a print sample: round 1 from ACCEPTED, each later one in
-- SAMPLE_PENDING after the round before it was answered with a reprint request. What it is never changes; the customer
-- who owns the production order answers it once, while it waits for the customer (13 BR-13); a physical sample is
-- recorded until then.
CREATE FUNCTION ordering.check_production_order_sample_round() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    po       RECORD;
    previous TEXT;
    owner    UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'sample round %: rounds are kept', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    SELECT p.code, p.status, p.sample_approval_required, p.customer_id INTO po
      FROM ordering.production_order p WHERE p.id = NEW.production_order_id;
    IF TG_OP = 'INSERT' THEN
        IF NOT po.sample_approval_required THEN
            RAISE EXCEPTION 'production order %: it has no print sample to approve (BR-24)', po.code
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.result IS NOT NULL THEN
            RAISE EXCEPTION 'production order %: a round opens waiting for the customer', po.code
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.round_no = 1 AND po.status <> 'ACCEPTED' THEN
            RAISE EXCEPTION 'production order %: round 1 is uploaded once the producer accepted it (17 §5.3)', po.code
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.round_no > 1 THEN
            IF po.status <> 'SAMPLE_PENDING' THEN
                RAISE EXCEPTION 'production order %: a new round is opened while its sample is pending', po.code
                    USING ERRCODE = 'check_violation';
            END IF;
            SELECT r.result INTO previous FROM ordering.production_order_sample_round r
             WHERE r.production_order_id = NEW.production_order_id AND r.round_no = NEW.round_no - 1;
            IF previous IS DISTINCT FROM 'REPRINT_REQUESTED' THEN
                RAISE EXCEPTION 'production order %: round % follows a reprint request of the round before', po.code,
                    NEW.round_no USING ERRCODE = 'check_violation';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.production_order_id IS DISTINCT FROM OLD.production_order_id
        OR NEW.round_no IS DISTINCT FROM OLD.round_no OR NEW.reprint_reason IS DISTINCT FROM OLD.reprint_reason
        OR NEW.opened_by IS DISTINCT FROM OLD.opened_by OR NEW.opened_at IS DISTINCT FROM OLD.opened_at THEN
        RAISE EXCEPTION 'production order %: what a sample round is does not change', po.code
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.result IS NOT NULL AND (NEW.result IS DISTINCT FROM OLD.result
        OR NEW.customer_note IS DISTINCT FROM OLD.customer_note OR NEW.answered_by IS DISTINCT FROM OLD.answered_by
        OR NEW.answered_at IS DISTINCT FROM OLD.answered_at
        OR NEW.physical_sample_sent_on IS DISTINCT FROM OLD.physical_sample_sent_on) THEN
        RAISE EXCEPTION 'production order %: round % is answered, once (13 BR-13)', po.code, OLD.round_no
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.result IS NULL AND NEW.physical_sample_sent_on IS DISTINCT FROM OLD.physical_sample_sent_on
        AND po.status <> 'SAMPLE_PENDING' THEN
        RAISE EXCEPTION 'production order %: a physical sample is recorded while its sample is pending', po.code
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.result IS NULL AND NEW.result IS NOT NULL THEN
        IF po.status <> 'SAMPLE_PENDING' THEN
            RAISE EXCEPTION 'production order %: its sample is answered while it is pending (13 BR-13)', po.code
                USING ERRCODE = 'check_violation';
        END IF;
        SELECT c.user_id INTO owner FROM customer.customer c WHERE c.id = po.customer_id;
        IF owner IS NULL OR NEW.answered_by IS DISTINCT FROM owner THEN
            RAISE EXCEPTION 'production order %: its sample is answered by the customer who owns it (13 BR-13)',
                po.code USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_production_order_sample_round
    BEFORE INSERT OR UPDATE OR DELETE ON ordering.production_order_sample_round
    FOR EACH ROW EXECUTE FUNCTION ordering.check_production_order_sample_round();

-- The photos and videos of a round are added while it waits for the customer and kept as uploaded.
CREATE FUNCTION ordering.check_production_order_sample_media() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    answered TEXT;
    po_status TEXT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'sample media %: kept as uploaded', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    SELECT r.result, p.status INTO answered, po_status
      FROM ordering.production_order_sample_round r JOIN ordering.production_order p ON p.id = r.production_order_id
     WHERE r.id = NEW.sample_round_id;
    -- Round 1's photos come with it, while the production order is still ACCEPTED; later ones while it is pending.
    IF po_status NOT IN ('ACCEPTED', 'SAMPLE_PENDING') THEN
        RAISE EXCEPTION 'sample round %: photos and videos are added while its sample is pending', NEW.sample_round_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF answered IS NOT NULL THEN
        RAISE EXCEPTION 'sample round %: answered, its photos and videos are those the customer saw',
            NEW.sample_round_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_production_order_sample_media
    BEFORE INSERT OR UPDATE OR DELETE ON ordering.production_order_sample_media
    FOR EACH ROW EXECUTE FUNCTION ordering.check_production_order_sample_media();

-- At commit: a round shows the customer at least one photo or video (17 §4.3 step 9: "tải ảnh / video mẫu").
CREATE FUNCTION ordering.check_production_order_sample_round_has_media() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ordering.production_order_sample_media m WHERE m.sample_round_id = NEW.id) THEN
        RAISE EXCEPTION 'sample round %: it shows at least one photo or video', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER tg_production_order_sample_round_has_media
    AFTER INSERT ON ordering.production_order_sample_round
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ordering.check_production_order_sample_round_has_media();

-- BR-24 on the production order: SAMPLE_PENDING once round 1 is there; with a print sample to approve, mass printing
-- starts from SAMPLE_PENDING when the customer approved a round - never from SENT or ACCEPTED, the way of an order
-- without one. Its own trigger, beside tg_production_order, which it leaves as 4A-2 wrote it.
CREATE FUNCTION ordering.check_production_order_sample() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RETURN NEW;
    END IF;
    IF NEW.status = 'SAMPLE_PENDING' AND NOT EXISTS (SELECT 1 FROM ordering.production_order_sample_round r
                                                      WHERE r.production_order_id = NEW.id AND r.round_no = 1) THEN
        RAISE EXCEPTION 'production order %: its sample is pending once round 1 is uploaded (17 §4.3 step 9)', NEW.code
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status = 'IN_PRODUCTION' AND NEW.sample_approval_required THEN
        IF OLD.status <> 'SAMPLE_PENDING' THEN
            RAISE EXCEPTION 'production order %: with a print sample to approve, mass printing starts when the customer approves it (BR-24)',
                NEW.code USING ERRCODE = 'check_violation';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM ordering.production_order_sample_round r
                        WHERE r.production_order_id = NEW.id AND r.result = 'APPROVED') THEN
            RAISE EXCEPTION 'production order %: no round of its print sample is approved (BR-24)', NEW.code
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_production_order_sample
    BEFORE UPDATE ON ordering.production_order
    FOR EACH ROW EXECUTE FUNCTION ordering.check_production_order_sample();

-- Permissions --------------------------------------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), 'production-samples:' || a.action, 'production-samples', a.action, 0, NOW(), 'flyway'
  FROM unnest(ARRAY['VIEW_PAGE', 'READ', 'APPROVE']) AS a(action)
ON CONFLICT (code) DO NOTHING;

WITH wanted (role_code, actions) AS (
    VALUES
        -- 13 BR-13: the customer sees and answers their own print samples (scope OWN, checked by the service).
        ('CUSTOMER',     'VIEW_PAGE,READ,APPROVE'),
        ('SYSTEM_ADMIN', 'VIEW_PAGE,READ,APPROVE')
), granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM wanted
      JOIN identity.app_role role ON role.code = wanted.role_code
      JOIN identity.permission permission
        ON permission.resource = 'production-samples'
       AND permission.action = ANY (string_to_array(wanted.actions, ','))
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- Grants are cached per role version (ADR-0008): advance only the roles whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT role_id FROM granted);
