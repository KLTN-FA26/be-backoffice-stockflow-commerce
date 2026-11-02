-- =============================================================================
-- Identity: a temporary password expires 72 hours after it is set (SCRUM-474 FIRST-LOGIN, owner decision
-- 29/10/2026). An administrator-set password (account creation, administrator reset) carries
-- must_change_password = TRUE; temporary_password_set_at says since when, so that sign-in can refuse it
-- once 72 hours have passed. The expiry itself is judged in User#signIn, not stored.
--
-- Accounts already flagged when this runs have no known time the temporary password was set. Owner decision
-- PWD-LEGACY of 02/11/2026: they expire 72 hours after this migration runs (the deploy), so the UPDATE below
-- stamps them with now(); the 72 hours are then counted by User#signIn like for any other account. They are
-- still forced to change the password - that rule is the flag, not this column.
-- =============================================================================

ALTER TABLE identity.app_user
    ADD COLUMN temporary_password_set_at TIMESTAMPTZ;

-- Accounts flagged before this column existed: the temporary password is treated as set now, so it expires
-- 72 hours after the deploy. Customers and accounts without the flag are untouched.
UPDATE identity.app_user
SET temporary_password_set_at = now()
WHERE must_change_password = TRUE
  AND temporary_password_set_at IS NULL;

COMMENT ON COLUMN identity.app_user.temporary_password_set_at IS
    'When the current administrator-set password was set; NULL once the user replaced it. Accounts flagged before this column existed were stamped with the migration time (they expire 72 hours after the deploy).';
