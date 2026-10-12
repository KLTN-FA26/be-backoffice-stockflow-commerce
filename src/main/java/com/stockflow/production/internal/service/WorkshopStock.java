package com.stockflow.production.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.IssueReservedStockCommand;
import com.stockflow.inventory.api.ReserveProductionStockCommand;
import com.stockflow.inventory.api.ReturnHeldStockCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.api.StockReservation;
import com.stockflow.inventory.api.WriteOffHeldStockCommand;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionRecords;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What the workshop does to stock, through {@code inventory :: api}, in the caller's transaction: every step
 * works on the holds this production order issued to the print shop, which stay live until one of these steps
 * closes them (an order's cancellation does not), so a hold's quantity is always the job's own count of units
 * still there.
 *
 * <p>Units are taken from the holds in the order they were issued — the press uses blanks as they arrive —
 * and given back from the latest. That choice decides only which receipt layer a write-off names; the counts
 * are the order's.</p>
 */
@Component
class WorkshopStock {

    private final ProductionRecords records;
    private final InventoryService inventory;
    private final WarehouseService warehouses;

    WorkshopStock(ProductionRecords records, InventoryService inventory, WarehouseService warehouses) {
        this.records = records;
        this.inventory = inventory;
        this.warehouses = warehouses;
    }

    /** One scrap line of a run or a QC round: how many, why (BR-PRD-04). */
    record Spoilt(ProductionRecords.ScrapReason reason, int quantity, String note) {
    }

    /** A hold issued to the print shop and still live, with the units it covers now. */
    private record Held(ProductionRecords.MaterialIssue issue, int quantity) {
    }

    private List<Held> held(ProductionOrder order) {
        List<ProductionRecords.MaterialIssue> issued = records.issuesOf(order.id());
        Map<UUID, StockReservation> live = inventory.liveHolds(issued.stream()
                        .map(ProductionRecords.MaterialIssue::reservationId).toList()).stream()
                .collect(Collectors.toMap(StockReservation::reservationId, Function.identity()));
        return issued.stream().filter(issue -> live.containsKey(issue.reservationId()))
                .map(issue -> new Held(issue, live.get(issue.reservationId()).quantity())).toList();
    }

    /** Units held in the print shop for this order, printed or not. */
    int inPrintShop(ProductionOrder order) {
        return held(order).stream().mapToInt(Held::quantity).sum();
    }

    /**
     * Each line leaves the holds at once and gets its write-off asked for (SCRAP; decision Q4: production does
     * not wait for the approval), recorded on {@code production_scrap} with the adjustment — one row per line
     * and layer it came from.
     */
    void writeOff(ProductionOrder order, List<Spoilt> lines, UUID by, Instant at) {
        List<Held> pool = new ArrayList<>(held(order));
        int[] left = pool.stream().mapToInt(Held::quantity).toArray();
        for (Spoilt line : lines) {
            int remaining = line.quantity();
            for (int i = 0; i < pool.size() && remaining > 0; i++) {
                int take = Math.min(left[i], remaining);
                if (take == 0) {
                    continue;
                }
                StockAdjustmentSummary adjustment = inventory.writeOffHeld(new WriteOffHeldStockCommand(
                        pool.get(i).issue().reservationId(), order.salesOrderId(), order.id(), take,
                        StockAdjustmentReason.SCRAP, scrapNote(order, line), by));
                records.recordScrap(new ProductionRecords.Scrap(Identifiers.newId(), order.id(), take, line.reason(),
                        line.note(), adjustment.adjustmentId(), at, by));
                left[i] -= take;
                remaining -= take;
            }
            if (remaining > 0) {
                throw new IllegalStateException("Production order %s holds %d unit(s) too few in the print shop"
                        .formatted(order.orderNumber(), remaining));
            }
        }
    }

    private static String scrapNote(ProductionOrder order, Spoilt line) {
        return "%s %s%s".formatted(order.orderNumber(), line.reason(), line.note() == null ? "" : ": " + line.note());
    }

    /**
     * A sample's printed units leave stock as {@code SAMPLE} (V20261010000200: blanks used for samples leave
     * stock through an approved adjustment with their own reason).
     */
    void writeOffSample(ProductionOrder order, int quantity, UUID by) {
        int remaining = quantity;
        for (Held held : held(order)) {
            if (remaining == 0) {
                break;
            }
            int take = Math.min(held.quantity(), remaining);
            inventory.writeOffHeld(new WriteOffHeldStockCommand(held.issue().reservationId(), null, order.id(), take,
                    StockAdjustmentReason.SAMPLE, order.orderNumber() + " sample", by));
            remaining -= take;
        }
        if (remaining > 0) {
            throw new IllegalStateException("Production order %s holds %d unit(s) too few in the print shop"
                    .formatted(order.orderNumber(), remaining));
        }
    }

    /**
     * {@code quantity} unprinted blanks back to the storage locations they were issued from, latest issue first.
     * {@code keepForOrder}: the order still wants them, so its own holds go back with the goods (still held for
     * it); otherwise every hold lets the units go, free.
     */
    void returnUnprinted(ProductionOrder order, int quantity, boolean keepForOrder, UUID by) {
        List<Held> pool = new ArrayList<>(held(order));
        java.util.Collections.reverse(pool);
        java.util.Set<UUID> ownHolds = inventory.productionHolds(order.id()).stream()
                .map(StockReservation::reservationId).collect(Collectors.toSet());
        int remaining = quantity;
        for (Held held : pool) {
            if (remaining == 0) {
                break;
            }
            int take = Math.min(held.quantity(), remaining);
            ProductionRecords.MaterialIssue issue = held.issue();
            boolean orderHold = keepForOrder && order.type() == ProductionOrder.Type.ORDER
                    && !ownHolds.contains(issue.reservationId());
            if (orderHold && take == held.quantity()) {
                inventory.unissueHeld(new IssueReservedStockCommand(issue.reservationId(), order.salesOrderId(),
                        order.id(), issue.fromLocation(), by));
            } else {
                inventory.returnHeld(new ReturnHeldStockCommand(issue.reservationId(), order.salesOrderId(), order.id(),
                        take, issue.fromLocation(), by));
            }
            remaining -= take;
        }
        if (remaining > 0) {
            throw new IllegalStateException("Production order %s holds %d unit(s) too few in the print shop"
                    .formatted(order.orderNumber(), remaining));
        }
    }

    /** Every hold left in the print shop, whole and still held, to a PACKING area (SCRUM-400). */
    String finishToPacking(ProductionOrder order, String packingLocation, UUID by) {
        String packing = packingArea(order, packingLocation);
        for (Held held : held(order)) {
            inventory.finishHeld(new IssueReservedStockCommand(held.issue().reservationId(), order.salesOrderId(),
                    order.id(), packing, by));
        }
        return packing;
    }

    /** The PACKING area named, or the warehouse's only usable one. */
    private String packingArea(ProductionOrder order, String requested) {
        if (requested != null && !requested.isBlank()) {
            StorageLocationView area = warehouses.findLocation(requested)
                    .filter(location -> location.warehouseId().equals(order.work().warehouseId()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.LOCATION_NOT_FOUND,
                            "No location %s in the warehouse of production order %s".formatted(requested,
                                    order.orderNumber())));
            if (area.areaType() != StorageLocationView.AreaType.PACKING || !area.usable()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "%s is not a usable PACKING area".formatted(area.locationCode()));
            }
            return area.locationCode();
        }
        List<StorageLocationView> areas = warehouses.areasOf(order.work().warehouseId(),
                StorageLocationView.AreaType.PACKING).stream().filter(StorageLocationView::usable).toList();
        if (areas.size() != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The warehouse has %d usable PACKING areas; name the one the goods go to".formatted(areas.size()));
        }
        return areas.get(0).locationCode();
    }

    /**
     * Blanks for a reprint round (Jira 398): held now for the production order if the warehouse has them.
     *
     * @return false when it has too few — the caller puts the order ON_HOLD (BLANKS_SHORT) and nothing is held
     */
    boolean holdForReprint(ProductionOrder order, int needed) {
        String warehouse = warehouses.findWarehouse(order.work().warehouseId()).orElseThrow().prefix();
        if (inventory.availableToPromise(order.work().blankSku(), warehouse) < needed) {
            return false;
        }
        inventory.reserveForProduction(new ReserveProductionStockCommand(UUID.nameUUIDFromBytes(
                        ("production-reprint:%s:%d:%d".formatted(order.id(), order.printed(), order.issued()))
                                .getBytes(StandardCharsets.UTF_8)),
                order.work().blankSku(), needed, order.id(), warehouse));
        return true;
    }

    /** Holds of the production order still in storage, released (cancelled before they were issued). */
    void releaseUnissued(ProductionOrder order) {
        inventory.releaseProductionHolds(order.id());
    }
}
