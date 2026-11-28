-- =============================================================================
-- Covers during leave (sales-owner plan BR-S07, D7). Module: identity.
--
-- "B looks after A's work from day X to day Y": while a cover is in force, everything about A's customers goes to B
-- (ResponsibleSales answers COVER). A cover never changes the owner, and ends by itself on its last day.
--
--   - the stand-in is an ACTIVE account holding SALES_STAFF when the cover is arranged (owner decision D42: a manager
--     coordinates, never covers; a trigger, since it reads the accounts);
--   - from_date <= to_date, at most 60 days counted inclusively (D7);
--   - nobody covers for themselves;
--   - one cover at a time for an absent person: two ACTIVE covers of one user never share a day (an exclusion
--     constraint, so two managers saving at once cannot both win);
--   - CANCELLED says when; a cover is cancelled by hand, or by identity when its stand-in's account is locked or
--     disabled (BR-S08).
-- =============================================================================

CREATE TABLE identity.staff_cover (
    id               UUID          NOT NULL,
    -- The person who is away.
    user_id          UUID          NOT NULL,
    -- The person who stands in.
    cover_user_id    UUID          NOT NULL,
    from_date        DATE          NOT NULL,
    to_date          DATE          NOT NULL,
    reason           VARCHAR(500),
    status           VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    -- Who set it up (the absent Sales themselves, or a manager).
    arranged_by      UUID,
    cancelled_at     TIMESTAMPTZ,
    cancelled_by     UUID,
    -- MANUAL, or COVER_DEACTIVATED when the stand-in's account stopped working.
    cancel_reason    VARCHAR(32),
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_staff_cover PRIMARY KEY (id),
    CONSTRAINT fk_staff_cover_user FOREIGN KEY (user_id) REFERENCES identity.app_user (id),
    CONSTRAINT fk_staff_cover_cover_user FOREIGN KEY (cover_user_id) REFERENCES identity.app_user (id),
    CONSTRAINT fk_staff_cover_arranged_by FOREIGN KEY (arranged_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_staff_cover_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_staff_cover_status CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT ck_staff_cover_days CHECK (from_date <= to_date),
    CONSTRAINT ck_staff_cover_length CHECK (to_date - from_date < 60),
    CONSTRAINT ck_staff_cover_not_self CHECK (user_id <> cover_user_id),
    CONSTRAINT ck_staff_cover_cancelled CHECK (
        (status = 'ACTIVE' AND cancelled_at IS NULL AND cancel_reason IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL
            AND cancel_reason IN ('MANUAL', 'COVER_DEACTIVATED'))),
    CONSTRAINT ex_staff_cover_overlap EXCLUDE USING gist (
        user_id WITH =, daterange(from_date, to_date, '[]') WITH &&) WHERE (status = 'ACTIVE')
);

COMMENT ON TABLE identity.staff_cover IS
    'Who stands in for an absent salesperson, from and to which day (sales-owner plan BR-S07). Never changes a '
    'customer''s owner; at most 60 days; one cover at a time per absent person.';

-- ResponsibleSales: the covers in force on a day for a set of owners.
CREATE INDEX ix_staff_cover_user_days ON identity.staff_cover (user_id, from_date, to_date) WHERE status = 'ACTIVE';
-- A deactivated stand-in's covers are cancelled.
CREATE INDEX ix_staff_cover_cover_user ON identity.staff_cover (cover_user_id) WHERE status = 'ACTIVE';

CREATE FUNCTION identity.check_staff_cover_stand_in() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM identity.app_user u
                     JOIN identity.user_role ur ON ur.user_id = u.id
                     JOIN identity.app_role r ON r.id = ur.role_id
                    WHERE u.id = NEW.cover_user_id AND u.status = 'ACTIVE' AND r.code = 'SALES_STAFF') THEN
        RAISE EXCEPTION 'A cover''s stand-in must be an active salesperson (cover %)', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

-- On arranging only: a stand-in locked later leaves the row, and identity cancels the cover in the same transaction.
CREATE TRIGGER tg_staff_cover_stand_in BEFORE INSERT ON identity.staff_cover
    FOR EACH ROW EXECUTE FUNCTION identity.check_staff_cover_stand_in();
