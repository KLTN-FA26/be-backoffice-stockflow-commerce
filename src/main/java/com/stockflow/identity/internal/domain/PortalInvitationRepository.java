package com.stockflow.identity.internal.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link PortalInvitation}. Callers serialise every change to one account's
 * invitations by holding that account's row lock ({@link UserRepository#findByIdForUpdate}) first;
 * the partial unique index "one open invitation per account" is the database's backstop.
 */
public interface PortalInvitationRepository {

    /** The account's open invitation (neither consumed nor revoked), expired or not. */
    Optional<PortalInvitation> findOpenByUserId(UUID userId);

    Optional<PortalInvitation> findById(UUID id);

    /**
     * Writes the invitation and flushes, so a revocation reaches the database before its replacement
     * is inserted - Hibernate would otherwise order the INSERT first and trip the open-invitation index.
     */
    PortalInvitation save(PortalInvitation invitation);
}
