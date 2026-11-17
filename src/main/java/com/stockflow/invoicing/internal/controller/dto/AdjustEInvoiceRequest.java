package com.stockflow.invoicing.internal.controller.dto;

import com.stockflow.common.validation.NotBlankTrimmed;
import com.stockflow.invoicing.internal.domain.CorrectionBasis;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * An adjustment of an issued e-invoice (WF-05 E4; TT 91/2026/TT-BTC Điều 10).
 *
 * <ul>
 *   <li>{@code basis} ERROR: the invoice was wrong. {@code buyer} may put the tax code right; with no {@code lines} and
 *       a changed buyer the adjustment changes no money and carries one note line.</li>
 *   <li>{@code basis} RETURN: goods returned. {@code returnRequestId} is required, every line reduces, and no more is
 *       returned than the invoice still holds of the order line.</li>
 *   <li>{@code basis} TRADE_DISCOUNT: a discount by volume or revenue. Every line reduces; {@code listedInvoiceIds}
 *       are the buyer's other issued invoices the discount was earned on.</li>
 * </ul>
 */
public record AdjustEInvoiceRequest(
        @NotNull CorrectionBasis basis,
        @NotBlankTrimmed @Size(max = 500) String reason,
        @Valid CorrectionBuyerRequest buyer,
        @Size(max = 200) List<@Valid @NotNull CorrectionLineRequest> lines,
        UUID returnRequestId,
        @Size(max = 200) List<@NotNull UUID> listedInvoiceIds
) {
}
