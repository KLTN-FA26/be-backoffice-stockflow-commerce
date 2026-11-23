package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when a return request becomes RECEIVED and waits for Sales to propose its refund
 * - or to refuse it with a reason (WF-05 BE-24, Q-4; kltn-docs 17 §4.10 step 40). Nothing else tells them: a return
 * left RECEIVED keeps its order RETURNED, so the order never completes or closes.
 *
 * <p>It is published whatever moved the return there, which a parcel's {@link ReturnReceived} alone does not cover:
 * {@code cause} is {@code GOODS_BACK} (the last approved units came back), {@code GOODS_COMPLETE} (Sales said no more is
 * coming), {@code GOODS_DEADLINE_PASSED} (the deadline passed with part of the goods back),
 * {@code RECEIVED_AFTER_DEADLINE} / {@code RECEIVED_AFTER_WITHDRAWAL} (a parcel of an expired or withdrawn claim came
 * anyway), {@code LATE_PARCEL} (a parcel came while the refund waited for its approver, who no longer has it) or
 * {@code REFUND_SENT_BACK} (the approver sent the proposal back). A consumer keys it by return and {@code at}: the same
 * return may wait for a proposal more than once.</p>
 *
 * <p>Consumers receive it in-process through {@code @ApplicationModuleListener}; see {@code package-info.java} for the
 * rules on changing this contract.</p>
 */
public record ReturnAwaitingRefund(
        UUID returnRequestId,
        String rmaNumber,
        UUID orderId,
        String orderNumber,
        UUID customerId,
        String cause,
        Instant at
) {
}
