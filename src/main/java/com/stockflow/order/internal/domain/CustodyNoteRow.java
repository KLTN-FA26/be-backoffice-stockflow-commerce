package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One custody note as the warehouse's list shows it (WF-05 Q-11, TH-6; audit round 3, RAD-3): the goods kept, the
 * return and order they came with, and how they end so far. A read model, as a page of the list is a projection and not
 * a return's aggregate; the outcome columns are null until Sales record the customer's choice.
 *
 * @param keptBy who took the goods in (the note's photo's uploader), {@code keptAt} when
 */
public record CustodyNoteRow(UUID evidenceId, UUID returnRequestId, String rmaNumber, UUID orderId, String orderNumber,
                             UUID customerId, UUID warehouseId, String sku, String lpn, int quantity, String note,
                             UUID keptBy, Instant keptAt, String choice, String choiceNote, UUID takenByReturnId,
                             String takenByRmaNumber, UUID chosenBy, Instant chosenAt, Instant endedAt, UUID endedBy,
                             String endedNote) {
}
