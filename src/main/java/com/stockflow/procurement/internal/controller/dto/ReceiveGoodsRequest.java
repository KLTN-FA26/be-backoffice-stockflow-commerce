package com.stockflow.procurement.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

@Schema(description = "Record goods received against a purchase order")
public record ReceiveGoodsRequest(

        @NotEmpty(message = "at least one line is required")
        @Valid
        List<ReceiveGoodsLineRequest> lines
) {
}
