package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One step of the fake Ahamove (see {@code FakeAhamove.Step}); only the details that step reads are used.
 *
 * @param step         ASSIGNING, ACCEPT, PICKUP, EN_ROUTE, DELIVER, FAIL, COMPLETE, RETURNING, RETURNED or
 *                     CARRIER_CANCEL
 * @param stop         DELIVER, FAIL: the drop-off, 1 for the first; omitted: the next one not settled
 * @param reason       FAIL: Ahamove's wording of the failure; CARRIER_CANCEL: why
 * @param driverName   ACCEPT
 * @param driverPhone  ACCEPT
 * @param vehiclePlate ACCEPT: kept on the fake order only (Ahamove's callback carries no plate)
 */
public record FakeAhamoveStepRequest(@NotBlank String step, @Min(1) @Max(999) Integer stop,
                                     @Size(max = 200) String reason, @Size(max = 120) String driverName,
                                     @Size(max = 32) String driverPhone, @Size(max = 32) String vehiclePlate) {
}
