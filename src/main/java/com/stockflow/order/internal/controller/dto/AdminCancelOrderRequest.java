package com.stockflow.order.internal.controller.dto;

import com.stockflow.order.api.CancellationReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * SCRUM-242/WBS 3.17.4, SCRUM-460: unlike the customer-facing cancellation, an admin/sales one must
 * always record why — a reason code, with a note when it is OTHER.
 *
 * @param reason the free-text reason this endpoint took before reason codes; read as the code when
 *               it starts with one, otherwise as OTHER with the text as its note. Ignored when
 *               {@code reasonCode} is given.
 */
@Schema(description = "Cancel any order as Sales or the coordinator")
public record AdminCancelOrderRequest(

        @Schema(example = "OUT_OF_STOCK")
        CancellationReasonCode reasonCode,

        @Size(max = 1000, message = "note is at most 1000 characters")
        String note,

        @Schema(description = "Share of the money received kept for work already done, 0-100 (kltn-docs 17 §4.4)",
                example = "20")
        @DecimalMin(value = "0.0", message = "retainedPercent is at least 0")
        @DecimalMax(value = "100.0", message = "retainedPercent is at most 100")
        BigDecimal retainedPercent,

        @Schema(deprecated = true, description = "Free text, before reasonCode existed")
        @Size(max = 500, message = "reason is at most 500 characters")
        String reason
) {
}
