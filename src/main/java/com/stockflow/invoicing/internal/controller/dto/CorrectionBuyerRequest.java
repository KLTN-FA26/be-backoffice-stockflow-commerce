package com.stockflow.invoicing.internal.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * The buyer as a correction of an error is to name it. A field left out stays as the corrected invoice has it; the tax
 * code is the one most often put right here (TT 91 Điều 10 khoản 1 điểm b).
 */
public record CorrectionBuyerRequest(
        @Size(max = 200) String name,
        @Size(max = 20) String taxCode,
        @Size(max = 500) String address,
        @Size(max = 320) String email
) {
}
