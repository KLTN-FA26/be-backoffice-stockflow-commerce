package com.stockflow.identity.internal.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Pending persistence port, intentionally without a bean or in-memory production fallback.
 * All operations join the caller's transaction. Lock a stable customer/invitation row before
 * create, resend or consume, including when no invitation exists yet. A concurrent reader must
 * refresh state after acquiring the lock. The DB must enforce one invitation per customer and
 * a unique digest. Save compares the loaded version and returns the advanced persisted version.
 */
public interface PortalInvitationRepository {
    Optional<PortalInvitation> findByCustomerIdForUpdate(UUID customerId);
    PortalInvitation save(PortalInvitation invitation);
}
