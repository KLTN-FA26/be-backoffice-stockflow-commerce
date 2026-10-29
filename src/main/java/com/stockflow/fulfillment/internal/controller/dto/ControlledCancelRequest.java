package com.stockflow.fulfillment.internal.controller.dto;

import com.stockflow.fulfillment.internal.domain.UnloadReasonCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Calling off one vehicle that is being loaded (SCRUM-474 UNLOAD-DESIGN point 7): a reason code and a note.
 *
 * @param reasonCode XE_HONG, SAI_XE, KIEN_KHONG_VUA or KHAC (400 otherwise)
 */
public record ControlledCancelRequest(@NotNull UnloadReasonCode reasonCode, @NotBlank @Size(max = 500) String note) {
}
