package com.stockflow.inventory.internal.controller;

import com.stockflow.inventory.internal.controller.dto.StockPackageDetailResponse;
import com.stockflow.inventory.internal.controller.dto.StockPackageLocationLineResponse;
import com.stockflow.inventory.internal.controller.dto.StockPackageResponse;
import com.stockflow.inventory.internal.service.StockPackageLookup;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
interface StockPackageWebMapper {

    StockPackageDetailResponse toResponse(StockPackageLookup.Detail detail);

    StockPackageDetailResponse.Content toResponse(StockPackageLookup.Content content);

    StockPackageLocationLineResponse toResponse(StockPackageLookup.LocationLine line);

    StockPackageResponse toResponse(StockPackageLookup.Carton carton);
}
