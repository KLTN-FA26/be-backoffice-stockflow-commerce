package com.stockflow.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Customer portal accounts that Sales creates and the customer activates (SCRUM-450).
 *
 * <p>Separate from {@link IdentityService} because its callers and its rules are: nobody signs up,
 * an account starts with an invitation instead of a password, and the invitation is the only way
 * to give it one. Every method joins the caller's transaction, so the customer module can create or
 * activate the account and its profile together or not at all.</p>
 *
 * <p>An {@link PortalInvitationLink} carries the raw secret for the e-mail. It must go nowhere else:
 * not into a response, a log line, an event or an audit entry.</p>
 */
public interface PortalAccountService {

    /**
     * Creates a CUSTOMER account that cannot sign in until the invitation is redeemed.
     *
     * @throws com.stockflow.common.error.BusinessException {@code USER_EMAIL_ALREADY_EXISTS} when any
     *         account - staff or customer - already signs in with this e-mail. An invitation is never
     *         attached to an existing account.
     */
    PortalInvitationLink invite(InviteCustomerAccountCommand command);

    /**
     * Issues a new invitation, which makes every earlier link unusable.
     *
     * @throws com.stockflow.common.error.BusinessException {@code USER_NOT_FOUND};
     *         {@code PORTAL_ACCOUNT_ALREADY_ACTIVE}; {@code RATE_LIMITED} within the resend cooldown
     */
    PortalInvitationLink reissue(UUID userId);

    /**
     * The account a token names, judged by its shape alone - nothing is looked up or verified. It
     * lets the caller lock its own rows for that account before {@link #activate} locks the account,
     * so every onboarding path takes its locks in the same order.
     */
    Optional<UUID> accountNamedBy(String token);

    /**
     * Redeems an invitation: sets the password the contact chose. Works once.
     *
     * @return the activated account
     * @throws com.stockflow.common.error.BusinessException {@code PORTAL_INVITATION_INVALID} for a
     *         malformed, unknown, replaced, expired or used token, alike; {@code VALIDATION_FAILED}
     *         for a password the policy refuses (the invitation stays open)
     */
    UUID activate(String token, String password);

    /** The open invitation of an account; empty once the account is activated. */
    Optional<PortalInvitationStatus> invitation(UUID userId);
}
