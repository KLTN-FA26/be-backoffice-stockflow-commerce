package com.stockflow.inventory.internal.controller;

import com.stockflow.inventory.internal.controller.dto.TransferOrderResponse;
import com.stockflow.inventory.internal.controller.dto.TransferOrderRowResponse;
import com.stockflow.inventory.internal.service.TransferOrders;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
interface TransferOrderWebMapper {

    TransferOrderResponse toResponse(TransferOrders.Transfer transfer);

    TransferOrderResponse.Line toResponse(TransferOrders.Line line);

    TransferOrderRowResponse toResponse(TransferOrders.Row row);
}
