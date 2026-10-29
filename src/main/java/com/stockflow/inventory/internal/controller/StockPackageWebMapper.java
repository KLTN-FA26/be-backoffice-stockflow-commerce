package com.stockflow.inventory.internal.controller;

import com.stockflow.inventory.internal.controller.dto.StockPackageDetailResponse;
import com.stockflow.inventory.internal.controller.dto.StockPackageLocationLineResponse;
import com.stockflow.inventory.internal.controller.dto.StockPackageResponse;
import com.stockflow.inventory.internal.domain.StockPackageBalance;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
interface StockPackageWebMapper {

    /** The carton's own properties, flattened beside its contents. */
    @Mapping(target = ".", source = "carton")
    StockPackageDetailResponse toResponse(StockPackageBalance.Detail detail);

    StockPackageDetailResponse.Content toResponse(StockPackageBalance.Content content);

    StockPackageLocationLineResponse toResponse(StockPackageBalance.LocationLine line);

    StockPackageResponse toResponse(StockPackageBalance carton);
}
