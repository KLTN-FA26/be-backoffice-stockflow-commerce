package com.stockflow.fulfillment.internal.controller.dto;

import com.stockflow.fulfillment.internal.domain.UnloadReasonCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Calling off a whole load plan after loading started (SCRUM-474 UNLOAD-DESIGN point 7): the version read, a reason code
 * and a note.
 *
 * @param reasonCode XE_HONG, SAI_XE, KIEN_KHONG_VUA or KHAC (400 otherwise)
 */
public record ControlledPlanCancelRequest(@NotNull Long expectedVersion, @NotNull UnloadReasonCode reasonCode,
                                          @NotBlank @Size(max = 500) String note) {
}
