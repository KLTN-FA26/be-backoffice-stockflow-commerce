package com.stockflow.inventory.internal.controller.dto;

/** A carton after a move: where it is now, and what it holds. */
public record MovedStockPackageResponse(String lpn, String sku, String unitOfMeasure, Integer quantity,
                                        String locationCode, String status) {
}
