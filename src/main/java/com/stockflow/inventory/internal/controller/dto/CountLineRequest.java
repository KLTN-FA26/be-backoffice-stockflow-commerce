package com.stockflow.inventory.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * What was found on one line: a quantity, or - for a sealed carton - whether it is there. Exactly one: a carton's
 * scan stands for the quantity counted into it at receipt (#125), and a quantity next to it could only contradict it.
 */
@Schema(description = "Count one line: countedQty, or present for a sealed carton - exactly one")
public record CountLineRequest(

        @Schema(example = "37")
        @Min(value = 0, message = "countedQty must not be negative")
        @Max(value = 1_000_000, message = "countedQty is at most 1,000,000")
        Integer countedQty,

        @Schema(description = "A sealed carton scanned (true) or not found (false)")
        Boolean present
) {

    @JsonIgnore
    @AssertTrue(message = "Send exactly one of countedQty and present")
    public boolean isExactlyOne() {
        return (countedQty == null) != (present == null);
    }
}
