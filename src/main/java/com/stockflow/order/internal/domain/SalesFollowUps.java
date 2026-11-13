package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What waits on the customer long enough for Sales to be told (kltn-docs 14 §6: "Khách đã ký nhưng chậm thanh toán đợt
 * ON_SIGNING - Sale nhắc"): a contract sent and not signed, an order signed and waiting for the installment on signing.
 * The newest first, so that a bounded read always reaches the ones that only just crossed the line; those further back
 * were told on an earlier run, and telling them again is a no-op of the inbox.
 */
public interface SalesFollowUps {

    /**
     * A contract the customer has not signed since {@code sentAt}.
     *
     * @param salesUserId who sent it: the salesperson handling it
     */
    record UnsignedContract(UUID contractId, String contractNumber, UUID orderId, String orderNumber,
                            UUID salesUserId, Instant sentAt) { }

    /**
     * An order waiting for the installment on signing since its contract was signed at {@code signedAt}.
     *
     * @param salesUserId who sent its contract, or - for none recorded - who recorded it signed
     */
    record UnpaidOrder(UUID orderId, String orderNumber, UUID contractId, String contractNumber, UUID salesUserId,
                       Instant signedAt) { }

    /** SENT contracts sent at or before {@code sentBy}, the newest first, at most {@code limit}. */
    List<UnsignedContract> unsignedSince(Instant sentBy, int limit);

    /** PENDING_PAYMENT orders whose contract was signed at or before {@code signedBy}, the newest first. */
    List<UnpaidOrder> unpaidSince(Instant signedBy, int limit);
}
