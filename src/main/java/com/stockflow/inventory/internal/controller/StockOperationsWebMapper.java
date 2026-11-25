package com.stockflow.inventory.internal.controller;

import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.internal.controller.dto.StockAdjustmentResponse;
import com.stockflow.inventory.internal.controller.dto.MovedStockPackageResponse;
import com.stockflow.inventory.internal.controller.dto.StockMovementResponse;
import com.stockflow.inventory.internal.service.StockOperations;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
interface StockOperationsWebMapper {

    MovedStockPackageResponse toResponse(StockPackageView carton);

    // The ledger line and the adjustment are written by hand: they carry the display names of the people they name,
    // which the source types do not have; the caller looks those up once for the whole page (ResponseLabels).

    /** @param names display names by user id, for every person the line names */
    default StockMovementResponse toResponse(StockOperations.LedgerLine line, Map<UUID, String> names) {
        return new StockMovementResponse(line.movementId(), line.type() == null ? null : line.type().name(),
                line.sku(), line.lotNumber(), line.fromLocation(), line.toLocation(), line.quantity(), line.status(),
                line.toStatus(), line.referenceType(), line.referenceId(), line.reason(), line.actorId(),
                line.occurredAt(), line.requestedBy(), line.countedBy(), ResponseLabels.name(names, line.actorId()),
                ResponseLabels.name(names, line.requestedBy()), ResponseLabels.name(names, line.countedBy()),
                line.fromLpn(), line.toLpn());
    }

    /** @param names display names by user id, for every person the adjustment names */
    default StockAdjustmentResponse toResponse(StockAdjustmentSummary adjustment, Map<UUID, String> names) {
        return new StockAdjustmentResponse(adjustment.adjustmentId(), adjustment.number(), adjustment.locationCode(),
                adjustment.sku(), adjustment.lotNumber(),
                adjustment.direction() == null ? null : adjustment.direction().name(),
                adjustment.reason() == null ? null : adjustment.reason().name(), adjustment.note(),
                adjustment.status() == null ? null : adjustment.status().name(), adjustment.requestedBy(),
                adjustment.requestedAt(), adjustment.decidedBy(), adjustment.decidedAt(), adjustment.rejectionReason(),
                adjustment.postedAt(), adjustment.withdrawnAt(), adjustment.version(), adjustment.stockPackageId(),
                adjustment.cycleCountLineId(), adjustment.countedBy(), ResponseLabels.name(names, adjustment.requestedBy()),
                ResponseLabels.name(names, adjustment.decidedBy()), ResponseLabels.name(names, adjustment.countedBy()));
    }

    /** Everyone a ledger line names. */
    static Stream<UUID> people(StockOperations.LedgerLine line) {
        return Stream.of(line.actorId(), line.requestedBy(), line.countedBy());
    }

    /** Everyone an adjustment names. */
    static Stream<UUID> people(StockAdjustmentSummary adjustment) {
        return Stream.of(adjustment.requestedBy(), adjustment.decidedBy(), adjustment.countedBy());
    }
}
