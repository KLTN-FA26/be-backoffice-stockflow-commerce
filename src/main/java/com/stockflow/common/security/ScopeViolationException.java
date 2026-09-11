package com.stockflow.common.security;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

/**
 * The caller may perform this action, but not on these rows — or the system could not work out
 * which rows they are entitled to, and refused rather than guessing.
 *
 * <p>Deliberately distinct from {@link PermissionDeniedException}. That one means "you cannot do
 * this at all"; this one means "you can, but not here". They need different messages in the UI and
 * different follow-up: one is a role change, the other is a warehouse or team assignment.</p>
 *
 * <p>Maps to {@link ErrorCode#OUT_OF_DATA_SCOPE} (403).</p>
 */
public class ScopeViolationException extends BusinessException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public ScopeViolationException(String message) {
        super(ErrorCode.OUT_OF_DATA_SCOPE, message);
    }
}
