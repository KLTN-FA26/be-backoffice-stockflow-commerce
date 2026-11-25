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
        String endedNote,
        @Schema(description = "The customer's registered name; null when the customer record is gone")
        String customerName,
        @Schema(description = "The code the customer is known by; set where customerName is") String customerCode,
        @Schema(description = "The code (prefix) of the warehouse keeping the goods", example = "HCM")
        String warehouseCode,
        @Schema(description = "That warehouse's name") String warehouseName,
        @Schema(description = "Who took the goods in; null when unknown") String keptByName,
        @Schema(description = "Who recorded the choice; null when chosenBy is null or unknown") String chosenByName,
        @Schema(description = "Who carried it out; null when endedBy is null or unknown") String endedByName) {

    /**
     * With the names beside the ids: {@code people} display names by user id, {@code customer} and {@code warehouse}
     * the row's own (null when unknown) - all read once for the page by the caller.
     */
    public static CustodyNoteResponse of(CustodyNoteRow r, java.util.Map<UUID, String> people,
                                         com.stockflow.customer.api.CustomerLabel customer,
                                         com.stockflow.warehouse.api.WarehouseView warehouse) {
        return new CustodyNoteResponse(r.evidenceId(), r.returnRequestId(), r.rmaNumber(), r.orderId(),
                r.orderNumber(), r.customerId(), r.warehouseId(), r.sku(), r.lpn(), r.quantity(), r.note(), r.keptBy(),
                r.keptAt(), r.choice(), r.choiceNote(), r.takenByReturnId(), r.takenByRmaNumber(), r.chosenBy(),
                r.chosenAt(), r.endedAt(), r.endedBy(), r.endedNote(),
                customer == null ? null : customer.name(), customer == null ? null : customer.code(),
                warehouse == null ? null : warehouse.prefix(), warehouse == null ? null : warehouse.name(),
                name(people, r.keptBy()), name(people, r.chosenBy()), name(people, r.endedBy()));
    }

    private static String name(java.util.Map<UUID, String> people, UUID id) {
        return id == null ? null : people.get(id);
    }
}
