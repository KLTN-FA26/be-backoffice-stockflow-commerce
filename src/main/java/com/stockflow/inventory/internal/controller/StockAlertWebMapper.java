package com.stockflow.inventory.internal.controller;

import com.stockflow.inventory.internal.controller.dto.StockAlertResponse;
import com.stockflow.inventory.internal.controller.dto.UnconfiguredSkuResponse;
import com.stockflow.inventory.internal.service.StockAlerts;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
interface StockAlertWebMapper {

    StockAlertResponse toResponse(StockAlerts.AlertView view);

    UnconfiguredSkuResponse toResponse(StockAlerts.UnconfiguredView view);
}
