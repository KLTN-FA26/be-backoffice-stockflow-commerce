-- =============================================================================
-- The checks that an owner or a cover is an active salesperson hold the account still (review finding C2 of the
-- sales-owner work, 2026-11-28). Modules: customer, identity.
--
-- customer.check_sales_owner_assignable and identity.check_staff_cover_stand_in read identity.app_user and
-- identity.user_role with a plain SELECT. A lock of the account committing between that read and the commit of the
-- write left a customer ASSIGNED to a locked account (or a cover by one) for good: the after-commit listener that
-- releases a locked owner's customers had already run and found nothing. Both functions now take the account's row
-- FOR SHARE first. Identity takes the same row FOR UPDATE before it changes an account's status or roles, so one waits
-- for the other: a lock that comes first is seen by the check (which then refuses), a lock that comes second waits
-- for the write and its listener then sees the customer. The same rule is SalesStaff.lockActiveSalesAgent in Java.
-- The check runs in a statement of its own after the lock, so under READ COMMITTED it reads what a lock it waited for
-- wrote.
-- =============================================================================

CREATE OR REPLACE FUNCTION customer.check_sales_owner_assignable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.sales_owner_state = 'ASSIGNED'
       AND (TG_OP = 'INSERT' OR OLD.sales_owner_state <> 'ASSIGNED'
            OR OLD.sales_owner_id IS DISTINCT FROM NEW.sales_owner_id) THEN
        PERFORM 1 FROM identity.app_user WHERE id = NEW.sales_owner_id FOR SHARE;
        IF NOT EXISTS (SELECT 1 FROM identity.app_user u
                         JOIN identity.user_role ur ON ur.user_id = u.id
                         JOIN identity.app_role r ON r.id = ur.role_id
                        WHERE u.id = NEW.sales_owner_id AND u.status = 'ACTIVE' AND r.code = 'SALES_STAFF') THEN
            RAISE EXCEPTION 'A customer''s sales owner must be an active salesperson (customer %)', NEW.id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION identity.check_staff_cover_stand_in() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM identity.app_user WHERE id = NEW.cover_user_id FOR SHARE;
    IF NOT EXISTS (SELECT 1 FROM identity.app_user u
                     JOIN identity.user_role ur ON ur.user_id = u.id
                     JOIN identity.app_role r ON r.id = ur.role_id
                    WHERE u.id = NEW.cover_user_id AND u.status = 'ACTIVE' AND r.code = 'SALES_STAFF') THEN
        RAISE EXCEPTION 'A cover''s stand-in must be an active salesperson (cover %)', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
