package com.stockflow.order.internal.controller.dto;

import com.stockflow.order.internal.domain.CustodyNoteRow;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Goods kept on a custody note, as the warehouse's list shows them (WF-05 Q-11, TH-6; audit round 3, RAD-3): what was
 * kept, on which return, and how it ends. {@code choice} null: Sales have not recorded the customer's choice yet, the
 * goods wait; {@code endedAt} set: they left as chosen.
 */
@Schema(name = "CustodyNote")
public record CustodyNoteResponse(
        @Schema(description = "The custody note (a CUSTODY photo of the return); its photo is GET "
                + "/returns/{returnRequestId}/evidence/{evidenceId}/download-url") UUID evidenceId,
        UUID returnRequestId, String rmaNumber, UUID orderId, String orderNumber, UUID customerId, UUID warehouseId,
        String sku, String lpn, int quantity, String note,
        @Schema(description = "Who took the goods in, and when") UUID keptBy, Instant keptAt,
        @Schema(allowableValues = {"SEND_BACK", "DESTROY_ON_BEHALF", "TAKEN_BY_RETURN"},
                description = "What the customer chose, as Sales recorded it; null until then") String choice,
        String choiceNote, UUID takenByReturnId, String takenByRmaNumber, UUID chosenBy, Instant chosenAt,
        @Schema(description = "When the goods left as chosen; null while kept") Instant endedAt, UUID endedBy,
        String endedNote) {

    public static CustodyNoteResponse of(CustodyNoteRow r) {
        return new CustodyNoteResponse(r.evidenceId(), r.returnRequestId(), r.rmaNumber(), r.orderId(),
                r.orderNumber(), r.customerId(), r.warehouseId(), r.sku(), r.lpn(), r.quantity(), r.note(), r.keptBy(),
                r.keptAt(), r.choice(), r.choiceNote(), r.takenByReturnId(), r.takenByRmaNumber(), r.chosenBy(),
                r.chosenAt(), r.endedAt(), r.endedBy(), r.endedNote());
    }
}
