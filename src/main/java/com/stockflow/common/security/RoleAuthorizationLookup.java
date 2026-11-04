package com.stockflow.common.security;

import java.util.Collection;

/**
 * Port the resource server asks "what may holders of these roles do", once per request.
 *
 * <p>The token names the caller's roles and nothing more (ADR-0008): permissions are resolved here,
 * on the server, so that ticking or unticking a permission on the admin screen reaches the very next
 * request instead of waiting for every token already issued to expire.</p>
 *
 * <p>Implemented by the identity module, which owns roles and grants; declared here for the same
 * reason as {@link ActiveSessionCheck} — the security chain is part of the shared base layer and
 * must not depend on a business module.</p>
 */
public interface RoleAuthorizationLookup {

    /**
     * @param roleCodes role codes as carried in the token; unknown codes contribute nothing
     * @return the union of what the roles grant — never null
     * @throws RuntimeException when neither the cache nor the database can answer. The caller must
     *         then refuse the request: an unanswerable authorisation question is never a yes.
     */
    ResolvedAuthorization resolve(Collection<String> roleCodes);
}
