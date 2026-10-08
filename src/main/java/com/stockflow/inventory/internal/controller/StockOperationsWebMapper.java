package com.stockflow.inventory.internal.controller;

import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.api.StockMove;
import com.stockflow.inventory.internal.controller.dto.StockAdjustmentResponse;
import com.stockflow.inventory.internal.controller.dto.StockMoveResponse;
import com.stockflow.inventory.internal.controller.dto.StockMovementResponse;
import com.stockflow.inventory.internal.service.StockOperations;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
interface StockOperationsWebMapper {

    StockMoveResponse toResponse(StockMove move);

    StockMovementResponse toResponse(StockOperations.LedgerLine line);

    StockAdjustmentResponse toResponse(StockAdjustmentSummary adjustment);
}
