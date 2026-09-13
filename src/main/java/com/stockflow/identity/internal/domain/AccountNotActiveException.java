package com.stockflow.identity.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

/**
 * Thrown by {@link User#signIn} when the account is {@code LOCKED} or {@code DISABLED}.
 *
 * <p>Deliberately distinct from a generic "invalid credentials" response: at this point the
 * password has already been verified, so the caller has proven they own the account, and
 * revealing the specific reason it cannot sign in is standard practice (not the username
 * enumeration risk a wrong-password case would be).</p>
 */
public class AccountNotActiveException extends BusinessException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public AccountNotActiveException(UserId userId, UserStatus status) {
        super(ErrorCode.ACCOUNT_NOT_ACTIVE,
                "Account %s cannot sign in while %s".formatted(userId, status));
    }
}
