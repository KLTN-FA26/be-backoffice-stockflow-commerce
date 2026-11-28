package com.stockflow.order.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Sales closed the rest of a partly fulfilled order (17 §4.8 step 33; owner decisions WF4-CLOSE-HISTORY-1113 and
 * WF4-CLOSE-HIST-SCHEMA-1115): one entry of the order's history of closures, kept whether or not the closure moved the
 * order's status.
 *
 * @param reasonCode the closure's {@link CancellationReasonCode}
 * @param note       what staff wrote, or null
 * @param closedBy   who closed it; null when the system did
 * @param feeAmount  the fee Sales kept with this closure (17 §4.8 step 33), as entered; null when it kept none
 * @param feeBearer  who bears that fee (D50); null exactly when {@code feeAmount} is
 */
public record RemainderClosure(CancellationReasonCode reasonCode, String note, UUID closedBy, Instant closedAt,
                               java.math.BigDecimal feeAmount, CancellationFeeBearer feeBearer) {

    /** A closure that kept no fee. */
    public RemainderClosure(CancellationReasonCode reasonCode, String note, UUID closedBy, Instant closedAt) {
        this(reasonCode, note, closedBy, closedAt, null, null);
    }
}
