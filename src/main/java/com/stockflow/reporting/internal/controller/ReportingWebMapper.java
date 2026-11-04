package com.stockflow.reporting.internal.controller;

import com.stockflow.reporting.api.ReportingService;
import com.stockflow.reporting.internal.controller.dto.OrderStatusCountResponse;
import com.stockflow.reporting.internal.controller.dto.RebuildResponse;
import com.stockflow.reporting.internal.controller.dto.SalesDayResponse;
import com.stockflow.reporting.internal.controller.dto.SalesSummaryResponse;
import com.stockflow.reporting.internal.controller.dto.TopProductResponse;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
interface ReportingWebMapper {

    SalesDayResponse toResponse(ReportingService.SalesDay day);

    TopProductResponse toResponse(ReportingService.TopProduct product);

    OrderStatusCountResponse toResponse(ReportingService.OrderStatusCount count);

    SalesSummaryResponse toResponse(ReportingService.SalesSummary summary);

    SalesSummaryResponse.Totals toResponse(ReportingService.Totals totals);

    RebuildResponse toResponse(ReportingService.RebuildResult result);
}
