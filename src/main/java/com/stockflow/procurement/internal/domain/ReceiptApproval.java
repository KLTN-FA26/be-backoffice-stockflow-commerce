package com.stockflow.procurement.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The warehouse manager's exception approval of a receipt (docs 03 step 5, BR-RCP-001): who asked for it and when, and,
 * once decided, what was decided, by whom, when and why. A value of {@link GoodsReceipt}; a receipt that never needed
 * the manager has none.
 *
 * @param decision {@code null} while the receipt is waiting
 */
public record ReceiptApproval(Instant requestedAt, UUID requestedBy, Decision decision, Instant decidedAt,
                              UUID decidedBy, String reason) {

    /**
     * {@code APPROVED}: the manager accepts the whole count, beyond the tolerance. {@code EXCESS_REJECTED}: the
     * manager refuses the part beyond the tolerance; the lines are cut back to it and the rest is posted.
     */
    public enum Decision { APPROVED, EXCESS_REJECTED }

    public ReceiptApproval {
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(requestedBy, "requestedBy");
    }

    public static ReceiptApproval requested(UUID userId, Instant now) {
        return new ReceiptApproval(now, userId, null, null, null, null);
    }

    public boolean pending() {
        return decision == null;
    }

    ReceiptApproval decided(Decision outcome, UUID by, Instant at, String why) {
        return new ReceiptApproval(requestedAt, requestedBy, Objects.requireNonNull(outcome, "decision"),
                Objects.requireNonNull(at, "at"), Objects.requireNonNull(by, "by"), why);
    }
}
