package com.stockflow.common.security;

import java.util.UUID;

/**
 * Port the security chain asks whether an account still has an administrator-set password it must
 * replace before doing anything else (SCRUM-474 FIRST-LOGIN).
 *
 * <p>Implemented by the identity module, which owns accounts; declared here so the chain (part of the
 * shared base layer) does not depend on a business module, the same split as {@link ActiveSessionCheck}.
 * It is read on every request, deliberately uncached: a cache would keep refusing a user who has just
 * changed their password, and keep admitting one whose password an administrator has just reset.</p>
 */
public interface PasswordChangeRequirement {

    /** True while the account carries the "must change password" flag; false for an unknown account. */
    boolean isRequired(UUID userId);
}
