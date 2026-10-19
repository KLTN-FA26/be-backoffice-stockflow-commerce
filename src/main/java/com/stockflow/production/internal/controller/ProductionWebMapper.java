package com.stockflow.production.internal.controller;

import com.stockflow.production.internal.controller.dto.ProductionOrderResponse;
import com.stockflow.production.internal.controller.dto.ProductionOrderRowResponse;
import com.stockflow.production.internal.controller.dto.SampleRequestResponse;
import com.stockflow.production.internal.controller.dto.SampleRequestRowResponse;
import com.stockflow.production.internal.service.ProductionOrders;
import com.stockflow.production.internal.service.SampleRequests;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
interface ProductionWebMapper {

    SampleRequestResponse toResponse(SampleRequests.Sample sample);

    SampleRequestResponse.Specification toResponse(SampleRequests.Draft draft);

    SampleRequestResponse.ProductionOrderRef toResponse(SampleRequests.ProductionOrderRef order);

    SampleRequestResponse.Round toResponse(SampleRequests.Round round);

    SampleRequestRowResponse toResponse(SampleRequests.Row row);

    ProductionOrderRowResponse toResponse(ProductionOrders.Row row);

    ProductionOrderResponse toResponse(ProductionOrders.Detail detail);

    ProductionOrderResponse.StatusChange toResponse(ProductionOrders.StatusChange change);

    ProductionOrderResponse.Subcontract toResponse(ProductionOrders.Subcontract subcontract);
}
