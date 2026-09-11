package com.stockflow.common.security;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

/**
 * Thrown when the caller is authenticated but lacks the permission.
 *
 * <p>The message names the missing permission code. A bare "Access denied" costs an administrator
 * half an hour of guessing which of 382 checkboxes to tick; naming {@code stock-items:EXPORT}
 * costs them ten seconds. The code reveals nothing an authenticated user could exploit.</p>
 */
public class PermissionDeniedException extends BusinessException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public PermissionDeniedException(PermissionCode code) {
        super(ErrorCode.FORBIDDEN, "Missing permission: " + code);
    }
}
