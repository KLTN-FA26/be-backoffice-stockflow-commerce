package com.stockflow.procurement.internal.domain;

import java.time.Instant;

/**
 * Whether a carton of a return request's source may still come back on its receipt, and if not why (WF-05 Q-11, Q-18,
 * Q-4; BE-22). One place for the rules the scan refuses by and the screen lists candidates by, so the two cannot drift:
 * {@link #ofCarton} is the carton's own state, {@link #ofUnits} the return's approval against what already came back.
 *
 * <p>Advisory on the screen only: the scan decides again under the receipt's lock, and the database checks the same
 * ({@code check_return_receipt_carton}).</p>
 */
public enum ReturnCartonEligibility {

    /** Its label fits in what the return still awaits of its SKU: it may come back sealed, whole. */
    RECEIVABLE,
    /**
     * Its label is above what the return still awaits of its SKU, which is not none: it comes back only counted, opened,
     * at no more than that - a carton is never split (Q-11 add-on), so sealed it is refused.
     */
    COUNT_REQUIRED,
    /** Already on this receipt: a recount or a removal, not a new scan. */
    ON_THIS_RECEIPT,
    /** Of a SKU the return did not approve: the customer's, kept in custody on the return request (BE-22). */
    SKU_NOT_APPROVED,
    /** The return's approved units of its SKU are all back or on this draft already. */
    APPROVED_QUANTITY_REACHED,
    /** Not out of the warehouse: back already on an earlier receipt, or never left ({@code RETURN_CARTON_IN_WAREHOUSE}). */
    IN_WAREHOUSE,
    /** Came back opened on an earlier receipt and was destroyed: never taken back again (Q-18). */
    RETURNED_OPENED,
    /** The customer's, held for them or destroyed on their behalf after a return that refunded nothing (Q-4). */
    CUSTOMER_GOODS,
    /** No carton with this LPN in the books. */
    NOT_FOUND;

    /** One the warehouse may work on now: offered on the screen, scanned without a refusal for what it is. */
    public boolean candidate() {
        return this == RECEIVABLE || this == COUNT_REQUIRED || this == ON_THIS_RECEIPT;
    }

    /**
     * The carton's own state, in the order the scan refuses: not CONSUMED, then opened on an earlier return, then the
     * customer's. Null when nothing in the carton itself stops it coming back.
     *
     * @param status         its stock status ({@code inventory.stock_package.status}); a carton that left is CONSUMED
     * @param returnOpenedAt when an earlier return receipt found it opened, null for never
     * @param customersGoods held for the customer or destroyed on their behalf
     */
    public static ReturnCartonEligibility ofCarton(String status, Instant returnOpenedAt, boolean customersGoods) {
        if (!"CONSUMED".equals(status)) {
            return IN_WAREHOUSE;
        }
        if (returnOpenedAt != null) {
            return RETURNED_OPENED;
        }
        return customersGoods ? CUSTOMER_GOODS : null;
    }

    /**
     * A carton of {@code label} units against what the return approved of its SKU and still awaits ({@code remaining}:
     * approved less every unit of it on the return's receipts not cancelled, this draft included).
     */
    public static ReturnCartonEligibility ofUnits(long approved, long remaining, int label) {
        if (approved <= 0) {
            return SKU_NOT_APPROVED;
        }
        if (remaining <= 0) {
            return APPROVED_QUANTITY_REACHED;
        }
        return label <= remaining ? RECEIVABLE : COUNT_REQUIRED;
    }

    /**
     * The most units the carton may come back with on this receipt: its label when it fits, what is still awaited when
     * only an opened count fits, none when it may not come back.
     */
    public static int maxReceivable(ReturnCartonEligibility eligibility, long remaining, int label) {
        return switch (eligibility) {
            case RECEIVABLE -> label;
            case COUNT_REQUIRED -> (int) Math.min(label, Math.max(0, remaining));
            default -> 0;
        };
    }
}
