package com.stockflow.order.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Cancel an order from the back office (SCRUM-460).
 *
 * @param note            required when {@code reasonCode} is OTHER
 * @param retainedPercent share of the money received kept for work already done (kltn-docs 17 §4.4,
 *                        15 §4.4); null or 0 keeps nothing
 * @param cancelledBy     the member of staff deciding
 * @param agreedFee       instead of a percentage, the amount agreed with the customer (decision Q2); null
 *                        for none
 * @param feeBearer       who bears the fee, whichever form it takes (D46, D50): required when {@code retainedPercent}
 *                        or {@code agreedFee} keeps something, null when nothing is kept. An agreed fee carries its
 *                        own bearer ({@link CancellationFee#bearer()}); leave this null then, or equal to it - it is
 *                        what a share, which has no object of its own, is given
 */
public record CancelOrderCommand(CancellationReasonCode reasonCode, String note, BigDecimal retainedPercent,
                                 UUID cancelledBy, CancellationFee agreedFee, CancellationFeeBearer feeBearer) {

    public CancelOrderCommand {
        if (reasonCode == null) throw new IllegalArgumentException("reasonCode is required");
        if (agreedFee != null) {
            if (feeBearer != null && feeBearer != agreedFee.bearer()) {
                throw new IllegalArgumentException("The bearer of an agreed fee is the fee's own");
            }
            feeBearer = agreedFee.bearer();
        }
    }

    /** With an agreed fee (which names its bearer) or none; a share needs the form with {@code feeBearer}. */
    public CancelOrderCommand(CancellationReasonCode reasonCode, String note, BigDecimal retainedPercent,
                              UUID cancelledBy, CancellationFee agreedFee) {
        this(reasonCode, note, retainedPercent, cancelledBy, agreedFee, null);
    }

    public CancelOrderCommand(CancellationReasonCode reasonCode, String note, BigDecimal retainedPercent,
                              UUID cancelledBy) {
        this(reasonCode, note, retainedPercent, cancelledBy, null, null);
    }

    /**
     * A free-text reason from a caller written before reason codes: a known code at its start is
     * taken as the code ({@code "PAYMENT_FAILED: card declined"}), anything else is OTHER with the
     * text as its note.
     */
    public static CancelOrderCommand fromText(String reason, UUID cancelledBy) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A cancellation must record a reason");
        }
        String text = reason.trim();
        for (CancellationReasonCode code : CancellationReasonCode.values()) {
            if (text.equals(code.name())) {
                return new CancelOrderCommand(code, null, null, cancelledBy);
            }
            if (text.startsWith(code.name() + ":")) {
                return new CancelOrderCommand(code, text.substring(code.name().length() + 1).trim(), null, cancelledBy);
            }
        }
        return new CancelOrderCommand(CancellationReasonCode.OTHER, text, null, cancelledBy);
    }
}
