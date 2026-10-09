package com.stockflow.warehouse.internal.controller;

import com.stockflow.warehouse.internal.controller.dto.AreaRequests;
import com.stockflow.warehouse.internal.controller.dto.AreaResponse;
import com.stockflow.warehouse.internal.controller.dto.FootprintRequest;
import com.stockflow.warehouse.internal.domain.AreaDetails;
import com.stockflow.warehouse.internal.domain.AreaType;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.LocationSettings;
import com.stockflow.warehouse.internal.service.AreaCommands;
import com.stockflow.warehouse.internal.service.AreaSummary;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.UUID;

/**
 * Area requests to commands and summaries to responses, split the way {@link ShelfWebMapper} is:
 * MapStruct for the response, hand-written commands that build - and so validate - the domain's
 * {@link AreaDetails} before any transaction starts.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
interface AreaWebMapper {

    Footprint toFootprint(FootprintRequest request);

    AreaResponse toResponse(AreaSummary area);

    default AreaCommands.CreateArea toCommand(UUID warehouseId, AreaRequests.CreateArea r) {
        return new AreaCommands.CreateArea(warehouseId, r.code(),
                toDetails(r.type(), r.name(), r.footprint(), r.obstacle(), r.location()));
    }

    default AreaCommands.UpdateArea toCommand(UUID areaId, AreaRequests.UpdateArea r) {
        return new AreaCommands.UpdateArea(areaId,
                toDetails(r.type(), r.name(), r.footprint(), r.obstacle(), r.location()), r.version());
    }

    private AreaDetails toDetails(AreaType type, String name,
                                  FootprintRequest footprint, boolean obstacle, AreaRequests.Location location) {
        return new AreaDetails(type, name, toFootprint(footprint), obstacle,
                location == null ? null : location.storageClass(),
                location == null ? null : new LocationSettings(location.capacityUnits(), location.maxWeight(),
                        location.pickable(), location.putawayTarget()));
    }
}
