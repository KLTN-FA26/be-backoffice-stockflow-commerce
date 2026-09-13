package com.stockflow.product.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.UUID;

/**
 * BR-PRD-003: "The approver of a product must not be the person who submitted it." Thrown by
 * {@link Product#approve}.
 */
public class SelfApprovalNotAllowedException extends BusinessException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public SelfApprovalNotAllowedException(ProductId productId, UUID approverId) {
        super(ErrorCode.SELF_APPROVAL_NOT_ALLOWED,
                "User %s submitted product %s and cannot also approve it".formatted(approverId, productId));
    }
}
