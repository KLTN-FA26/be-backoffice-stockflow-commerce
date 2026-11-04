-- =============================================================================
-- CONTRACT C0 - validate every foreign key that V20260928004000 had to leave NOT VALID.
-- NOT APPLIED YET. See README.md in this directory for how to activate it.
--
-- Run when: tools/db/orphan_check.sql reports 0 for every "live" key on every environment that
-- ran V20260928004000 (on a fresh database nothing is left NOT VALID and this is a no-op).
-- Unlike V20260928004000 it does not tolerate a failure: a key still violated stops it here.
-- =============================================================================

DO $$
DECLARE
    fk RECORD;
BEGIN
    FOR fk IN SELECT conrelid::regclass AS tbl, conname FROM pg_constraint
               WHERE contype = 'f' AND NOT convalidated LOOP
        EXECUTE format('ALTER TABLE %s VALIDATE CONSTRAINT %I', fk.tbl, fk.conname);
    END LOOP;
END $$;
