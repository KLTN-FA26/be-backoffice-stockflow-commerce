package com.stockflow.inventory.internal.controller;

import com.stockflow.inventory.internal.controller.dto.MoveSuggestionResponse;
import com.stockflow.inventory.internal.controller.dto.PutawaySuggestionResponse;
import com.stockflow.inventory.internal.controller.dto.StockPackageDetailResponse;
import com.stockflow.inventory.internal.controller.dto.StockPackageLocationLineResponse;
import com.stockflow.inventory.internal.controller.dto.StockPackageResponse;
import com.stockflow.inventory.internal.domain.PutawayRanker;
import com.stockflow.inventory.internal.domain.StockPackageBalance;
import com.stockflow.inventory.internal.service.MoveSuggestionService;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
interface StockPackageWebMapper {

    /** The carton's own properties, flattened beside its contents. */
    @Mapping(target = ".", source = "carton")
    @Mapping(target = "openedOnReturn", expression = "java(detail.carton().openedOnReturn())")
    StockPackageDetailResponse toResponse(StockPackageBalance.Detail detail);

    StockPackageDetailResponse.Content toResponse(StockPackageBalance.Content content);

    StockPackageLocationLineResponse toResponse(StockPackageBalance.LocationLine line);

    @Mapping(target = "openedOnReturn", expression = "java(carton.openedOnReturn())")
    StockPackageResponse toResponse(StockPackageBalance carton);

    StockPackageResponse.Hold toResponse(StockPackageBalance.Hold hold);

    StockPackageResponse.OpenTask toResponse(StockPackageBalance.OpenTask task);

    /** The bins a group of cartons may be moved into (issue #412), each in putaway's own shape. */
    MoveSuggestionResponse toResponse(MoveSuggestionService.MoveSuggestions suggestions);

    PutawaySuggestionResponse toResponse(PutawayRanker.Suggestion suggestion);
}
