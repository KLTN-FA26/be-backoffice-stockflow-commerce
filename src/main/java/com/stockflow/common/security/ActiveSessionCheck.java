package com.stockflow.common.security;

import java.util.UUID;

/**
 * Port the resource server asks whether the session a token belongs to is still usable.
 *
 * <p>Implemented by the identity module, which owns sessions; declared here so the security chain
 * (part of the shared base layer) does not depend on a business module.</p>
 */
public interface ActiveSessionCheck {

    /** True while the session exists, has not been revoked and has not expired. */
    boolean isActive(UUID sessionId);
}
