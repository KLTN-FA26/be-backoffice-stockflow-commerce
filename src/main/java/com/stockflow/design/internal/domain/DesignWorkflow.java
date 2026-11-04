package com.stockflow.design.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import java.util.Objects;
import java.util.UUID;

/** A customer decision never substitutes for a technical review. */
public final class DesignWorkflow {
    private DesignWorkflow() { }

    public static void requireVersion(long actual, long expected) {
        if (actual != expected) { fail("The design changed; refresh before continuing"); }
    }

    public static void requireReview(DesignStatus status, UUID current, UUID expected,
                                     UUID actor, UUID owner, UUID editor, UUID reviewer) {
        if (status != DesignStatus.DRAFT || current == null || !current.equals(expected)) {
            fail("Review requires the current draft artifact");
        }
        if (!Objects.equals(actor, reviewer) || Objects.equals(actor, owner) || Objects.equals(actor, editor)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "An assigned independent technical reviewer is required");
        }
    }

    public static void requireConfirmation(DesignStatus status, UUID current, UUID expected,
                                           boolean passed, UUID actor, UUID owner) {
        if (!Objects.equals(actor, owner) || actor == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Only the customer owner may confirm this design");
        }
        if (status != DesignStatus.SUBMITTED || current == null || !current.equals(expected) || !passed) {
            fail("The current artifact must have a valid technical review and confirmation request");
        }
    }

    private static void fail(String message) { throw new BusinessException(ErrorCode.CONFLICT, message); }
}
