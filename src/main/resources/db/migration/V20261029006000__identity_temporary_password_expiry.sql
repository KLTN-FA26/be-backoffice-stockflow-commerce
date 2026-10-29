-- =============================================================================
-- Identity: a temporary password expires 72 hours after it is set (SCRUM-474 FIRST-LOGIN, owner decision
-- 29/10/2026). An administrator-set password (account creation, administrator reset) carries
-- must_change_password = TRUE; temporary_password_set_at says since when, so that sign-in can refuse it
-- once 72 hours have passed. The expiry itself is judged in User#signIn, not stored.
--
-- Accounts already flagged when this runs have NULL here, and NULL means "not expired": they stay valid
-- until the user changes the password or an administrator resets it (which stamps the time). They are still
-- forced to change it - that rule is the flag, not this column.
-- =============================================================================

ALTER TABLE identity.app_user
    ADD COLUMN temporary_password_set_at TIMESTAMPTZ;

COMMENT ON COLUMN identity.app_user.temporary_password_set_at IS
    'When the current administrator-set password was set; NULL once the user replaced it, and for accounts flagged before this column existed (never expire).';
