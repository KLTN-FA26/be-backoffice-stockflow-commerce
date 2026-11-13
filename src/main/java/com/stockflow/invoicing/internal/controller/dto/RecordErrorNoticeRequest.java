package com.stockflow.invoicing.internal.controller.dto;

import com.stockflow.common.validation.NotBlankTrimmed;
import com.stockflow.invoicing.internal.domain.ErrorNotice;
import jakarta.validation.constraints.Size;

/**
 * A 04/SS-HĐĐT notice of an issued e-invoice (WF-05 E4; TT 91/2026/TT-BTC Điều 10 khoản 1 điểm a): the buyer name
 * or address as it should have been - at least one, different from the invoice - and why. {@code subject} is
 * BUYER_NAME_ADDRESS when left out; WITH_CORRECTION (a notice beside an adjustment or replacement, with no name or
 * address) is accepted only where the deployment turns it on.
 */
public record RecordErrorNoticeRequest(
        ErrorNotice.Subject subject,
        @NotBlankTrimmed @Size(max = 500) String reason,
        @Size(max = 200) String correctBuyerName,
        @Size(max = 500) String correctBuyerAddress
) {
}
