-- =============================================================================
-- Design projects (kltn-docs 12 §4.9-§4.11, §5.4; BR-17..BR-22; B42, B43; decided 2026-11-06): a customer gathers
-- designs of one brand identity in a project, keeps the logos and pictures they reuse in its library, submits several
-- drafts for review at once, and asks for one quote over several approved designs.
--
--   * design_project - one customer's project (BR-17): a name, an optional description, ACTIVE or ARCHIVED (closed for
--     good, B42). design_draft.project_id puts a design in at most one project, of the same customer (the composite
--     foreign key); a design waiting for review cannot leave its project or move to another
--     (tg_design_draft_project_while_pending) - joining one is not leaving.
--     At most 20 designs a project, archived ones not counted (B42), is the service's, under the project's row lock.
--   * design_project_asset - the project's library (BR-18, B43): files uploaded once, checked as a design's images
--     are; placing one on a design copies the file into the design (design_asset, upload_key "<user>:asset:library:
--     <library file>"), so removing it from the library leaves every design that used it as it is. At most 30 a
--     project, under the project's lock.
--   * design_review_round, design_review_round_design - a batch submitted together (BR-19), shown to the Sales Manager
--     as one item; each design is decided on its own and the round closes once every one is (BR-20).
--   * design_project_quote_request, design_project_quote_request_line - one quote asked over several approved designs
--     of a project, the cartons of each and one delivery date (BR-21).
--   * design_project_event - the project's history (§5.4), append-only.
-- =============================================================================

CREATE TABLE design.design_project
(
    id               UUID          NOT NULL,
    customer_id      UUID          NOT NULL,
    owner_user_id    UUID          NOT NULL,
    name             VARCHAR(200)  NOT NULL,
    description      VARCHAR(2000),
    status           VARCHAR(20)   NOT NULL,
    archived_at      TIMESTAMPTZ,
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_design_project PRIMARY KEY (id),
    -- What a design names with its project, so that both belong to one customer.
    CONSTRAINT uk_design_project_customer UNIQUE (id, customer_id),
    CONSTRAINT fk_design_project_customer FOREIGN KEY (customer_id) REFERENCES customer.customer (id),
    CONSTRAINT fk_design_project_owner FOREIGN KEY (owner_user_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_design_project_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_design_project_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_design_project_archived CHECK ((status = 'ARCHIVED') = (archived_at IS NOT NULL))
);

CREATE INDEX ix_design_project_owner ON design.design_project (owner_user_id, status, created_at DESC, id DESC);
CREATE INDEX ix_design_project_customer ON design.design_project (customer_id);


ALTER TABLE design.design_draft
    ADD COLUMN project_id UUID,
    ADD CONSTRAINT fk_design_draft_project FOREIGN KEY (project_id, customer_id)
        REFERENCES design.design_project (id, customer_id);

CREATE INDEX ix_design_draft_project ON design.design_draft (project_id) WHERE project_id IS NOT NULL;

-- BR-17: a design waiting for review neither leaves its project nor moves to another.
CREATE FUNCTION design.reject_project_change_while_pending() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.status = 'PENDING_REVIEW' AND OLD.project_id IS NOT NULL
        AND NEW.project_id IS DISTINCT FROM OLD.project_id THEN
        RAISE EXCEPTION 'A design waiting for review cannot change project'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_design_draft_project_while_pending';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_design_draft_project_while_pending
    BEFORE UPDATE OF project_id ON design.design_draft
    FOR EACH ROW EXECUTE FUNCTION design.reject_project_change_while_pending();


CREATE TABLE design.design_project_asset
(
    id            UUID         NOT NULL,
    project_id    UUID         NOT NULL,
    storage_key   VARCHAR(500) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    checksum      VARCHAR(64)  NOT NULL,
    width_px      INTEGER      NOT NULL,
    height_px     INTEGER      NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_design_project_asset PRIMARY KEY (id),
    CONSTRAINT fk_design_project_asset_project FOREIGN KEY (project_id) REFERENCES design.design_project (id),
    -- As design_asset: the files a design takes, so that each can be placed on one.
    CONSTRAINT ck_design_project_asset_key CHECK (storage_key LIKE 'design-assets/%'),
    CONSTRAINT ck_design_project_asset_type CHECK (content_type IN ('image/png', 'image/jpeg', 'image/webp')),
    CONSTRAINT ck_design_project_asset_size CHECK (size_bytes > 0 AND size_bytes <= 52428800),
    CONSTRAINT ck_design_project_asset_checksum CHECK (checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_design_project_asset_pixels
        CHECK (width_px > 0 AND height_px > 0 AND width_px::BIGINT * height_px <= 40000000)
);

CREATE INDEX ix_design_project_asset_project ON design.design_project_asset (project_id, created_at DESC, id DESC);


CREATE TABLE design.design_review_round
(
    id               UUID         NOT NULL,
    project_id       UUID         NOT NULL,
    submitted_by     UUID         NOT NULL,
    submitted_at     TIMESTAMPTZ  NOT NULL,
    closed_at        TIMESTAMPTZ,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_design_review_round PRIMARY KEY (id),
    CONSTRAINT fk_design_review_round_project FOREIGN KEY (project_id) REFERENCES design.design_project (id),
    CONSTRAINT fk_design_review_round_submitted_by FOREIGN KEY (submitted_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_design_review_round_closed CHECK (closed_at IS NULL OR closed_at >= submitted_at)
);

-- The Sales Manager's queue of rounds, the longest waiting first.
CREATE INDEX ix_design_review_round_open ON design.design_review_round (submitted_at, id) WHERE closed_at IS NULL;
CREATE INDEX ix_design_review_round_project ON design.design_review_round (project_id);

CREATE TABLE design.design_review_round_design
(
    round_id   UUID        NOT NULL,
    draft_id   UUID        NOT NULL,
    outcome    VARCHAR(20),
    decided_at TIMESTAMPTZ,

    CONSTRAINT pk_design_review_round_design PRIMARY KEY (round_id, draft_id),
    CONSTRAINT fk_design_review_round_design_round FOREIGN KEY (round_id) REFERENCES design.design_review_round (id),
    CONSTRAINT fk_design_review_round_design_draft FOREIGN KEY (draft_id) REFERENCES design.design_draft (id),
    CONSTRAINT ck_design_review_round_design_outcome CHECK (outcome IS NULL OR outcome IN ('APPROVED', 'RETURNED')),
    CONSTRAINT ck_design_review_round_design_decided CHECK ((outcome IS NULL) = (decided_at IS NULL))
);

-- A design waits in one round at a time.
CREATE UNIQUE INDEX uk_design_review_round_design_waiting ON design.design_review_round_design (draft_id)
    WHERE outcome IS NULL;


CREATE TABLE design.design_project_quote_request
(
    id                    UUID        NOT NULL,
    project_id            UUID        NOT NULL,
    requested_by          UUID        NOT NULL,
    desired_delivery_date DATE,
    version               BIGINT      NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL,
    created_by            VARCHAR(100),
    last_modified_at      TIMESTAMPTZ,
    last_modified_by      VARCHAR(100),

    CONSTRAINT pk_design_project_quote_request PRIMARY KEY (id),
    CONSTRAINT fk_design_project_quote_request_project FOREIGN KEY (project_id) REFERENCES design.design_project (id),
    CONSTRAINT fk_design_project_quote_request_requested_by
        FOREIGN KEY (requested_by) REFERENCES identity.app_user (id),
    -- As design_quote_request: not before the day it was asked, in Vietnam.
    CONSTRAINT ck_design_project_quote_request_delivery_date CHECK (desired_delivery_date IS NULL
        OR desired_delivery_date >= (created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)
);

CREATE INDEX ix_design_project_quote_request_list
    ON design.design_project_quote_request (created_at DESC, id DESC);
CREATE INDEX ix_design_project_quote_request_project ON design.design_project_quote_request (project_id);
CREATE INDEX ix_design_project_quote_request_requested_by ON design.design_project_quote_request (requested_by);

CREATE TABLE design.design_project_quote_request_line
(
    request_id UUID    NOT NULL,
    draft_id   UUID    NOT NULL,
    cartons    INTEGER NOT NULL,

    CONSTRAINT pk_design_project_quote_request_line PRIMARY KEY (request_id, draft_id),
    CONSTRAINT fk_design_project_quote_request_line_request
        FOREIGN KEY (request_id) REFERENCES design.design_project_quote_request (id),
    CONSTRAINT fk_design_project_quote_request_line_draft FOREIGN KEY (draft_id) REFERENCES design.design_draft (id),
    CONSTRAINT ck_design_project_quote_request_line_cartons CHECK (cartons >= 1)
);

CREATE INDEX ix_design_project_quote_request_line_draft ON design.design_project_quote_request_line (draft_id);


CREATE TABLE design.design_project_event
(
    id         UUID          NOT NULL,
    project_id UUID          NOT NULL,
    actor_id   UUID          NOT NULL,
    event      VARCHAR(40)   NOT NULL,
    notes      VARCHAR(4000) NOT NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_design_project_event PRIMARY KEY (id),
    CONSTRAINT fk_design_project_event_project FOREIGN KEY (project_id) REFERENCES design.design_project (id),
    CONSTRAINT fk_design_project_event_actor FOREIGN KEY (actor_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_design_project_event_type CHECK (event IN ('PROJECT_CREATED', 'ASSET_ADDED', 'ASSET_REMOVED',
                                                              'BATCH_PREFLIGHT_FAILED', 'BATCH_SUBMITTED',
                                                              'BATCH_CLOSED', 'PROJECT_QUOTE_REQUESTED',
                                                              'PROJECT_ARCHIVED'))
);

CREATE INDEX ix_design_project_event_history ON design.design_project_event (project_id, created_at DESC, id DESC);
CREATE INDEX ix_design_project_event_actor ON design.design_project_event (actor_id);

CREATE FUNCTION design.reject_project_event_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'A design project''s history is append-only';
END;
$$;

CREATE TRIGGER immutable_design_project_event
    BEFORE UPDATE OR DELETE ON design.design_project_event
    FOR EACH ROW EXECUTE FUNCTION design.reject_project_event_mutation();
