package com.stockflow.fulfillment.internal.controller;

import com.stockflow.fulfillment.internal.controller.dto.LoadEstimateRequest;
import com.stockflow.fulfillment.internal.controller.dto.LoadEstimateResponse;
import com.stockflow.fulfillment.internal.controller.dto.TrialLoadEstimateRequest;
import com.stockflow.fulfillment.internal.controller.dto.TrialLoadEstimateResponse;
import com.stockflow.fulfillment.internal.domain.loadplanning.SelectionMode;
import com.stockflow.fulfillment.internal.service.LoadEstimates;
import org.springframework.stereotype.Component;

import java.util.List;

/** Load estimates to and from their wire format (SCRUM-287 PR EST). */
@Component
class LoadEstimateWebMapper {

    LoadEstimates.CreateEstimate toCommand(LoadEstimateRequest r) {
        List<LoadEstimates.Source> sources = r.sources().stream()
                .map(s -> new LoadEstimates.Source(LoadEstimates.SourceType.valueOf(s.type()), s.id())).toList();
        return new LoadEstimates.CreateEstimate(sources, r.warehouseId(), r.vehicleTypeIds() == null ? List.of() : r.vehicleTypeIds(),
                r.selectionMode() == null ? null : SelectionMode.valueOf(r.selectionMode()));
    }

    LoadEstimateResponse toResponse(LoadEstimates.LoadEstimateView v) {
        return new LoadEstimateResponse(v.id(), v.sourceType().name(), v.sourceId(), v.warehouseId(), v.status(),
                v.selectionMode().name(), v.vehicleTypeIds(), v.lines(), v.missing(), v.incomplete(), v.warnings(), v.vehicles(),
                v.unplaced(), v.meta(), v.createdAt(), v.createdBy());
    }

    LoadEstimates.TrialEstimate toCommand(TrialLoadEstimateRequest r) {
        List<LoadEstimates.TrialLine> lines = r.lines().stream()
                .map(l -> new LoadEstimates.TrialLine(l.name(), l.group(), l.lengthMm(), l.widthMm(), l.heightMm(),
                        l.quantity(), l.rotatable() == null || l.rotatable()))
                .toList();
        return new LoadEstimates.TrialEstimate(lines, r.vehicleTypeIds() == null ? List.of() : r.vehicleTypeIds(),
                r.selectionMode() == null ? null : SelectionMode.valueOf(r.selectionMode()));
    }

    TrialLoadEstimateResponse toResponse(LoadEstimates.TrialView v) {
        return new TrialLoadEstimateResponse(true, v.selectionMode().name(), v.vehicleTypeIds(), v.vehicles(),
                v.unplaced(), v.meta());
    }
}
