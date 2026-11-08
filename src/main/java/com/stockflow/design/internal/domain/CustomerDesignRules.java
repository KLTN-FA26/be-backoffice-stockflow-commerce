package com.stockflow.design.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import java.util.UUID;
import java.util.function.BooleanSupplier;

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

    /**
     * Who reads a design (BR-03, B38, plan D7): its owner, while it is not archived; someone who may review designs,
     * while a customer's design waits for review; and someone who reads quote requests, while a customer's design waits
     * for review or is approved and a quote request on it is open - the sales staff answering it (kltn-docs 16 §4.2).
     * A returned design is a draft again, its owner's alone, even with its request still open (B40). Anyone else is
     * answered as if it did not exist.
     *
     * @param customerDesign   whether the design was started by a customer on a template
     * @param rights           what the reader holds beyond ownership
     * @param quoteRequestOpen whether a quote request on the design is open; asked only when that decides
     */
    public static void requireReader(UUID actor, UUID owner, DesignStatus status, boolean customerDesign,
                                     DesignReadRights rights, BooleanSupplier quoteRequestOpen) {
        if (actor == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        boolean owns = actor.equals(owner) && status != DesignStatus.ARCHIVED;
        boolean reviews = rights.reviewer() && customerDesign && status == DesignStatus.PENDING_REVIEW;
        boolean quotes = !owns && !reviews && rights.quotes() && customerDesign
                && (status == DesignStatus.PENDING_REVIEW || status == DesignStatus.APPROVED)
                && quoteRequestOpen.getAsBoolean();
        if (!owns && !reviews && !quotes) {
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
