package com.stockflow.design.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import java.util.UUID;

/**
 * The rules of a design a customer makes on their own (kltn-docs 12 BR-01, BR-03, BR-16, B37).
 */
public final class CustomerDesignRules {

    /** Drafts a customer may hold at once (B37). Approved, pending and archived designs do not count. */
    public static final int MAX_DRAFTS = 50;

    private CustomerDesignRules() {
    }

    /**
     * Only its owner sees a customer's design while it is theirs alone, and nobody sees an archived one (BR-03, B38):
     * anyone else is answered as if it did not exist, so a design's id tells them nothing.
     */
    public static void requireOwner(UUID actor, UUID owner, DesignStatus status) {
        if (actor == null || !actor.equals(owner) || status == DesignStatus.ARCHIVED) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    /** Only a draft is renamed, laid out or deleted (§4.1, BR-07, BR-08, BR-16). */
    public static void requireDraft(DesignStatus status) {
        if (status != DesignStatus.DRAFT) {
            throw new BusinessException(ErrorCode.DESIGN_NOT_DRAFT,
                    "This design is " + status + "; only a draft can be changed");
        }
    }

    /** A customer starting another draft while already holding {@code drafts} of them. */
    public static void requireRoomForDraft(long drafts) {
        if (drafts >= MAX_DRAFTS) {
            throw new BusinessException(ErrorCode.DESIGN_DRAFT_LIMIT_REACHED,
                    "A customer holds at most " + MAX_DRAFTS + " drafts; delete one first");
        }
    }
}
