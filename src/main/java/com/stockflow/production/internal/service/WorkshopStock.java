package com.stockflow.production.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.ConsumeHeldStockCommand;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.IssueReservedStockCommand;
import com.stockflow.inventory.api.IssueReservedStockResult;
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

    /**
     * A live hold of this production order's goods, with the units it covers now and where it was issued from
     * (null for goods received from a subcontractor).
     */
    private record Held(UUID reservationId, String fromLocation, int quantity) {
    }

    /**
     * The goods this job works on: in house, the holds it issued to the print shop; for a subcontracted child, the
     * finished goods held for it on receipt and waiting in QC (SCRUM-441, decision Q8) — its blanks at the
     * subcontractor are not its goods but its supplies ({@link #blanksAtSubcontractor}).
     */
    private List<Held> held(ProductionOrder order) {
        if (order.mode() == ProductionOrder.Mode.SUBCONTRACTED) {
            List<UUID> received = records.subcontractHoldsOf(order.id());
            return inventory.liveHolds(received).stream()
                    .map(hold -> new Held(hold.reservationId(), null, hold.quantity())).toList();
        }
        return issued(order);
    }

    private List<Held> issued(ProductionOrder order) {
        List<ProductionRecords.MaterialIssue> issued = records.issuesOf(order.id());
        Map<UUID, StockReservation> live = inventory.liveHolds(issued.stream()
                        .map(ProductionRecords.MaterialIssue::reservationId).toList()).stream()
                .collect(Collectors.toMap(StockReservation::reservationId, Function.identity()));
        return issued.stream().filter(issue -> live.containsKey(issue.reservationId()))
                .map(issue -> new Held(issue.reservationId(), issue.fromLocation(),
                        live.get(issue.reservationId()).quantity())).toList();
    }

    /** Blanks sent to the subcontractor for a SUPPLIED_BLANKS child and not accounted for yet. */
    int blanksAtSubcontractor(ProductionOrder order) {
        return issued(order).stream().mapToInt(Held::quantity).sum();
    }

    /**
     * G5, decision Q7: {@code holds} — blanks held for a SUPPLIED_BLANKS child — go with their goods to the
     * subcontractor's location for the child's warehouse, IN_PRODUCTION: still ours, still the child's, out of
     * available-to-promise. One {@code production_material_issue} row per hold, keyed by the hold, so a hold is
     * sent once whatever retries.
     *
     * @return the blanks sent
     */
    int sendToSubcontractor(ProductionOrder child, String subcontractorLocation, List<UUID> holds, UUID by,
                            Instant at) {
        int sent = 0;
        for (UUID hold : holds) {
            UUID requestId = UUID.nameUUIDFromBytes(("subcontract-send:" + hold).getBytes(StandardCharsets.UTF_8));
            if (records.findIssue(requestId).isPresent()) {
                continue;
            }
            IssueReservedStockResult moved = inventory.issueReserved(new IssueReservedStockCommand(hold, null,
                    child.id(), subcontractorLocation, by));
            records.recordIssue(new ProductionRecords.MaterialIssue(Identifiers.newId(), child.id(), requestId,
                    moved.reservationId(), moved.sku(), moved.lotNumber(), moved.fromLocation(), moved.toLocation(),
                    moved.quantity(), moved.movementId(), at, by));
            sent += moved.quantity();
        }
        return sent;
    }

    /**
     * Decision Q9, in the receipt's transaction: the blanks the received goods were printed on leave the
     * subcontractor's location (CONSUMPTION) — the goods are in the receipt now, and counting both would double
     * the stock.
     */
    void consumeAtSubcontractor(ProductionOrder order, int used, String receipt, UUID by) {
        takeFromSubcontractor(order, used, "receipt " + receipt, hold -> take -> inventory.consumeHeld(new ConsumeHeldStockCommand(
                hold.reservationId(), order.salesOrderId(), order.id(), take, "Printed into receipt " + receipt, by)));
    }

    /**
     * Decision Q9, one receipt reconciled by the warehouse, or blanks recalled without a receipt ({@code source}
     * says which): {@code spoilt} blanks are written off as SUBCONTRACT_LOSS through the adjustment approval (G8),
     * {@code returned} come back free — to {@code returnLocation}, or where each was sent from. Whatever is left
     * stays held at the subcontractor: not received yet is not lost.
     */
    void reconcile(ProductionOrder order, int spoilt, int returned, String returnLocation, String source, UUID by) {
        takeFromSubcontractor(order, spoilt, source, hold -> take -> inventory.writeOffHeld(new WriteOffHeldStockCommand(
                hold.reservationId(), order.salesOrderId(), order.id(), take, StockAdjustmentReason.SUBCONTRACT_LOSS,
                "%s spoilt at the subcontractor (%s)".formatted(order.orderNumber(), source), by)));
        takeFromSubcontractor(order, returned, source, hold -> take -> inventory.returnHeld(new ReturnHeldStockCommand(
                hold.reservationId(), order.salesOrderId(), order.id(), take,
                returnLocation == null || returnLocation.isBlank() ? hold.fromLocation() : returnLocation, by)));
    }

    /** {@code quantity} blanks out of the child's holds at the subcontractor, in the order they were sent. */
    private void takeFromSubcontractor(ProductionOrder order, int quantity, String source,
                                       Function<Held, java.util.function.IntConsumer> step) {
        int remaining = quantity;
        for (Held held : issued(order)) {
            if (remaining == 0) {
                break;
            }
            int take = Math.min(held.quantity(), remaining);
            step.apply(held).accept(take);
            remaining -= take;
        }
        if (remaining > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The %s accounts for %d blank(s) more than the subcontractor holds for production order %s"
                            .formatted(source, remaining, order.orderNumber()));
        }
    }

    /**
     * Decision Q8 and the user's QC decision of 2026-10-13: a received line's goods, held for the child on their
     * INBOUND layer, go to the warehouse's QUALITY_CONTROL area — where received goods wait for inspection — as
     * IN_PRODUCTION: still the child's, never sellable, and a cup failing the one production QC is scrapped as a cup
     * (SCRAP), never put back as a blank. They do not pass through the print shop: they were printed elsewhere.
     * The area is the warehouse's first usable QUALITY_CONTROL area by location code (receiving already refused the
     * receipt of a warehouse without one).
     */
    void holdForProductionQc(ProductionOrder child, UUID hold, UUID by) {
        inventory.issueReserved(new IssueReservedStockCommand(hold, null, child.id(), qcArea(child), by));
    }

    private String qcArea(ProductionOrder child) {
        return warehouses.areasOf(child.work().warehouseId(), StorageLocationView.AreaType.QUALITY_CONTROL).stream()
                .filter(StorageLocationView::usable)
                .map(StorageLocationView::locationCode)
                .sorted()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.QC_AREA_MISSING,
                        "The warehouse of production order %s has no usable QUALITY_CONTROL area for the received goods"
                                .formatted(child.orderNumber())));
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
                        pool.get(i).reservationId(), order.salesOrderId(), order.id(), take,
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
            inventory.writeOffHeld(new WriteOffHeldStockCommand(held.reservationId(), null, order.id(), take,
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
            boolean orderHold = keepForOrder && order.type() == ProductionOrder.Type.ORDER
                    && !ownHolds.contains(held.reservationId());
            if (orderHold && take == held.quantity()) {
                inventory.unissueHeld(new IssueReservedStockCommand(held.reservationId(), order.salesOrderId(),
                        order.id(), held.fromLocation(), by));
            } else {
                inventory.returnHeld(new ReturnHeldStockCommand(held.reservationId(), order.salesOrderId(), order.id(),
                        take, held.fromLocation(), by));
            }
            remaining -= take;
        }
        if (remaining > 0) {
            throw new IllegalStateException("Production order %s holds %d unit(s) too few in the print shop"
                    .formatted(order.orderNumber(), remaining));
        }
    }

    /** Where finished goods went, and which holds went there. */
    record HandOver(String packingLocation, List<UUID> holds) {
    }

    /** Every hold left in the print shop, whole and still held, to a PACKING area (SCRUM-400). */
    HandOver finishToPacking(ProductionOrder order, String packingLocation, UUID by) {
        String packing = packingArea(order, packingLocation);
        List<UUID> finished = new ArrayList<>();
        for (Held held : held(order)) {
            inventory.finishHeld(new IssueReservedStockCommand(held.reservationId(), order.salesOrderId(),
                    order.id(), packing, by));
            finished.add(held.reservationId());
        }
        return new HandOver(packing, finished);
    }

    /** The usable PACKING areas of the order's warehouse, any of which a completing QC round may name. */
    List<StorageLocationView> packingAreas(ProductionOrder order) {
        return warehouses.areasOf(order.work().warehouseId(), StorageLocationView.AreaType.PACKING).stream()
                .filter(StorageLocationView::usable).toList();
    }

    /**
     * The finished goods this production order held itself (a reprint round, a subcontracted child) become the
     * sales order's, same holds: returns their ids for the order line. The order's own checkout holds already are.
     * Only this production order's holds move, and only to its own sales order — never another's.
     */
    List<UUID> handFinishedGoodsToOrder(ProductionOrder order, List<UUID> finished) {
        java.util.Set<UUID> own = inventory.productionHolds(order.id()).stream().map(StockReservation::reservationId)
                .collect(Collectors.toSet());
        List<UUID> handed = finished.stream().filter(own::contains).toList();
        handed.forEach(id -> inventory.handHoldToOrder(id, order.id(), order.salesOrderId()));
        return handed;
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
