package com.stockflow.common.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Answers "who is this user now": their username, whether they may sign in, and their roles, read from the database
 * when asked. {@link RunAs} builds a background job's authorization from it, as {@link RoleAuthorizationLookup} and
 * {@link UserWarehouseLookup} answer the rest, so a job acting for someone holds what that person holds at that moment:
 * a role revoked, an account disabled or locked takes effect on the job's next run.
 *
 * <p>Implemented by the identity module.</p>
 */
public interface UserAccountLookup {

    /**
     * @return empty when there is no such user
     * @throws RuntimeException when the answer cannot be read; the caller must then refuse rather than guess
     */
    Optional<UserAccount> account(UUID userId);

    /**
     * @param active whether the account may sign in now: ACTIVE, and not locked until a time still to come
     * @param roles  role codes, in no particular order
     */
    record UserAccount(UUID userId, String username, boolean active, List<String> roles) {

        public UserAccount {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }
}
