package com.stockflow.fulfillment.internal.controller.dto;

import com.stockflow.fulfillment.internal.domain.UnloadReasonCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Calling off one vehicle that is being loaded (SCRUM-474 UNLOAD-DESIGN point 7): a reason code and a note.
 *
 * @param reasonCode         XE_HONG, SAI_XE, KIEN_KHONG_VUA or KHAC (400 otherwise)
 * @param removedShipmentIds optional: shipments of the vehicle's manifest taken off with it (kltn-docs 09 §4.2 step 9,
 *                           BR-14 - damaged, or an order's installment before delivery unpaid); they leave the plan and
 *                           the vehicle's new load carries the rest on a recalculated layout
 */
public record ControlledCancelRequest(@NotNull UnloadReasonCode reasonCode, @NotBlank @Size(max = 500) String note,
                                      @Size(max = 200) List<@NotNull UUID> removedShipmentIds) {
}
