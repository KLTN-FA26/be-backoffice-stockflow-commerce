package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>payment</b> module when the accountant records a refund made for an order, or voids one (team plan
 * 4D-3; kltn-docs 15 §4.5 steps 16-17, 17 §4.10 step 40): where the order's refund stands now - what its settlements
 * made refundable and what the live records refunded.
 *
 * <p>The <b>order</b> module takes it as the moment a returned order may be refunded in full: once everything to refund
 * is refunded and none of its returns is still open, RETURNED moves on to REFUNDED or PARTIALLY_REFUNDED. Totals, not a
 * delta, so a consumer that sees two of them in any order, or one twice, decides on the state the payment module was
 * in; it reads the standing again under the order's lock anyway.</p>
 *
 * <p>A void says so (TT-4): {@code voidedRecordId} names the record voided, and a REFUNDED / PARTIALLY_REFUNDED order
 * goes back to RETURNED, waiting for its refund again. The void's reason is typed text and stays with payment, which
 * owns it: the order asks for it by the id ({@code PaymentService.refundVoidReason}) to put it in its history. One
 * event for both, not a second type, so every consumer of a refund's standing keeps hearing every change of it. Null
 * for a record made - and in a publication stored before the field existed, which is then read as a record made, as it
 * was.</p>
 *
 * @param voidedRecordId the refund record this void voided; null when a record was made
 */
public record OrderRefundRecorded(
        UUID orderId,
        String orderNumber,
        BigDecimal refundable,
        BigDecimal refunded,
        String currency,
        UUID recordedBy,
        Instant recordedAt,
        UUID voidedRecordId
) {

    /** Whether this announces a void rather than a record made. */
    public boolean voided() {
        return voidedRecordId != null;
    }
}
