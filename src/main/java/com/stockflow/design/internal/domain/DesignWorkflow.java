package com.stockflow.design.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

/** What every write of a design checks first: the version the user saw (kltn-docs 12 BR-11). */
public final class DesignWorkflow {
    private DesignWorkflow() { }

    public static void requireVersion(long actual, long expected) {
        if (actual != expected) {
            throw new BusinessException(ErrorCode.CONFLICT, "The design changed; refresh before continuing");
        }
    }
}
