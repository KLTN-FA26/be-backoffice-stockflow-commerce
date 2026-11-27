package com.stockflow.assistant.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/** Why an order is not moving (contract §1.1): what holds it up, who must act, and what was not checked. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record NextStepsResponse(
        String orderNumber,
        @Schema(description = "The order's status code", example = "PACKED") String status,
        @Schema(description = "The status in the reader's language", example = "Packed") String statusLabel,
        @Schema(description = "What holds the order up, in the order the checks run: contract, payment on signing, "
                + "hold, handover, stock, production. Empty for an order no longer live")
        List<Blocker> blockers,
        @Schema(description = "Checks the caller's permissions do not reach, or that failed: HANDOVER, "
                + "PRODUCTION_ORDERS, STOCK_HELD, INSTALLMENTS (a payment reason whose amount is hidden). Show that "
                + "this part was not checked, never that nothing blocks it")
        List<String> notChecked,
        @Schema(description = "A live order with no reason found and nothing left unchecked. False for an order no "
                + "longer live (handed over whole, delivered, cancelled, closed): its status says where it is")
        boolean movingNormally,
        @Schema(description = "More reasons were found than the 20 listed") boolean blockersTruncated) {

    /** One reason the order does not move on. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Blocker(
            @Schema(example = "INSTALLMENT_DUE", allowableValues = {"CONTRACT_NOT_SIGNED",
                    "SIGNING_INSTALLMENT_UNPAID", "ON_HOLD", "INSTALLMENT_DUE", "DELIVERY_WINDOW_NOT_OPEN",
                    "HANDOVER_REFUSED", "NO_SHIPPING_WAREHOUSE", "STOCK_NOT_HELD", "PRODUCTION_NOT_RECEIVED"})
            String code,
            @Schema(description = "Who must act", allowableValues = {"CUSTOMER", "SALES", "ACCOUNTANT", "WAREHOUSE",
                    "PRODUCTION", "CARRIER", "SALES_MANAGER"})
            String waitingOn,
            @Schema(description = "From the message bundle, never model text") String title,
            String detail,
            String nextStep,
            @Schema(description = "The delivery batch concerned; null for the whole order") Integer batchSeq,
            @Schema(description = "INSTALLMENT_DUE, for a caller who may read instalments; null otherwise")
            BigDecimal amountDue,
            @Schema(description = "The back-office page to act on") String href) {
    }
}
