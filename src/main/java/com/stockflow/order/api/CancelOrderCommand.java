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
 */
public record CancelOrderCommand(CancellationReasonCode reasonCode, String note, BigDecimal retainedPercent,
                                 UUID cancelledBy) {

    public CancelOrderCommand {
        if (reasonCode == null) throw new IllegalArgumentException("reasonCode is required");
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
