package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One confirmed return receipt that brought goods of a {@link ReturnRequest} back (WF-05 TH-3): a B2B customer may send
 * the goods of one return in several parcels - four cartons by truck on Monday, two by post on Thursday - and each comes
 * in on a return receipt of its own. Kept so a receipt seen again is recognised (the event is delivered at least once)
 * and so the return can say which receipts brought its goods. Append-only ({@code ordering.return_request_receipt}).
 *
 * @param parcel the receipt's place among the return's, 1 for the first
 */
public record ReturnParcel(UUID goodsReceiptId, int parcel, String receiptNumber, String locationCode,
                           UUID receivedBy, Instant receivedAt) {

    public ReturnParcel {
        Objects.requireNonNull(goodsReceiptId, "goodsReceiptId");
        Objects.requireNonNull(receiptNumber, "receiptNumber");
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(receivedBy, "receivedBy");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (parcel < 1) {
            throw new IllegalArgumentException("A return's parcels are numbered from 1");
        }
    }
}
