-- =============================================================================
-- The customer no longer places orders (kltn-docs 14 BR-01, BR-02; V20261113000100 took its sales-orders:CREATE):
-- Sales draws an order up from a quote the customer accepted. The CUSTOMER role's description still said "order";
-- it now says what the role does, as Role.CUSTOMER does in the code.
--
-- Only the seeded text is replaced: a description an administrator wrote is theirs and is left alone. No grant
-- changes, so app_role.version does not move (ADR-0008: the version keys cached grants, not the description).
-- =============================================================================
UPDATE identity.app_role
SET description = 'Browse, design, request and accept quotes, track orders'
WHERE code = 'CUSTOMER'
  AND description = 'Browse, design, order and track';
