-- WF-05 BE-47 (audit R-15; CLAUDE.md §7 "state every invariant twice"): a return request's status moves only along
-- ReturnStatus.canTransitionTo, in the database too, as an order's does (tg_order_status_transition). Before this only
-- CHECKs on single columns guarded it, so a manual UPDATE RESOLVED -> RECEIVED or REJECTED -> AWAITING_RETURN passed.
-- ReturnStatusTransitionMirrorTest compares these rows with the Java table.

CREATE TABLE ordering.return_request_status_transition (
    from_status varchar(24) NOT NULL,
    to_status   varchar(24) NOT NULL,
    CONSTRAINT pk_return_request_status_transition PRIMARY KEY (from_status, to_status),
    CONSTRAINT ck_return_request_status_transition_change CHECK (from_status <> to_status)
);

COMMENT ON TABLE ordering.return_request_status_transition IS 'The edges of ReturnStatus.canTransitionTo; tg_return_request_status_transition refuses any other change of a return request''s status.';

INSERT INTO ordering.return_request_status_transition (from_status, to_status) VALUES
    ('REQUESTED', 'AWAITING_RETURN'),
    ('REQUESTED', 'REJECTED'),
    ('REQUESTED', 'CANCELLED'),
    ('AWAITING_RETURN', 'RECEIVED'),
    ('AWAITING_RETURN', 'CANCELLED'),
    ('AWAITING_RETURN', 'EXPIRED'),
    ('EXPIRED', 'RECEIVED'),
    ('CANCELLED', 'RECEIVED'),
    ('RECEIVED', 'SETTLEMENT_PENDING'),
    ('RECEIVED', 'RESOLVED'),
    ('SETTLEMENT_PENDING', 'RESOLVED'),
    ('SETTLEMENT_PENDING', 'RECEIVED');

CREATE FUNCTION ordering.enforce_return_request_transition() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ordering.return_request_status_transition t
                    WHERE t.from_status = OLD.status AND t.to_status = NEW.status) THEN
        RAISE EXCEPTION 'Return % cannot move from % to %', OLD.rma_number, OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_return_request_status_transition BEFORE UPDATE OF status ON ordering.return_request
    FOR EACH ROW EXECUTE FUNCTION ordering.enforce_return_request_transition();
