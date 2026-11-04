package com.stockflow.procurement.internal.controller.dto;

@io.swagger.v3.oas.annotations.media.Schema(name = "PurchaseOrderStatusCount",
        description = "One bucket of the PO status dashboard")
public record PurchaseOrderStatusCountResponse(String status, long count) {
}
