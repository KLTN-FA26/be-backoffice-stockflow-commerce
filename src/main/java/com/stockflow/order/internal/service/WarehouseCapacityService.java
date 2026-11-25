package com.stockflow.order.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.AssignedWarehouses;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.contracts.WarehouseCapacityTurnedNegative;
import com.stockflow.inventory.api.LocationLoad;
import com.stockflow.inventory.api.OccupiedVolume;
import com.stockflow.inventory.api.WarehouseOccupancy;
import com.stockflow.order.internal.domain.CapacityBoard;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.domain.WarehouseDirectory;
import com.stockflow.order.internal.repository.OpenProductionCartons;
import com.stockflow.warehouse.api.BinCapacity;
import com.stockflow.warehouse.api.CapacityGroup;
import com.stockflow.warehouse.api.WarehouseCapacities;
import com.stockflow.warehouse.api.WarehouseCapacity;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The warehouse capacity board (BE1; kltn-docs 06 §4.6, BR-20, BR-21; 17 §4.2 step 2, §4.3 steps 6-7, BR-25; C40-C43),
 * worked out when it is read and never stored.
 *
 * <p><b>Why here.</b> The board needs three sources - the bins of the map ({@code warehouse :: api}), the cartons in
 * stock and held ({@code inventory :: api}) and the production orders on their way (this module's own tables) - and
 * {@code order} is the module that may read all three and the one that uses the answer: choosing a shipping warehouse,
 * drawing up and sending a production order. {@code warehouse} calls no other module; {@code reporting} already
 * depends on {@code order}, which could then not ask it back.</p>
 *
 * <p><b>One measure of a carton.</b> Every volume - what stands in the warehouse, what is held, what production orders
 * bring - is the carton spec's length x width x height from inventory (06 BR-19), the one putaway fits cartons by. The
 * column rules are {@link CapacityBoard}'s.</p>
 *
 * <p><b>Who sees what.</b> The board's own endpoints ({@link #boards()}, {@link #board(UUID)}) narrow a caller limited to
 * warehouses to theirs. The reads for a shipping-warehouse choice and a production order apply no scope of their own:
 * their callers already decided the caller may see that order or production order.</p>
 *
 * <p><b>Sending</b> ({@link #warnIfSendingTurnsNegative}): under the advisory lock {@code warehouse-capacity:<id>},
 * taken after the sales order and the production order - lock order customer_order, production_order, warehouse
 * capacity - the board is read as it stands before the production order is sent, and the crossing below zero is told
 * once (C42).</p>
 */
@Service
@Transactional(readOnly = true)
public class WarehouseCapacityService {

    private static final Logger log = LoggerFactory.getLogger(WarehouseCapacityService.class);
    /** The weight shown beside the volume, for reference: kilograms to the gram. */
    private static final int KG_SCALE = 3;

    /** A row below the warehouse (06 §4.6 step 4): capacity, occupied and free now; an area has only occupied. */
    @Schema(name = "WarehouseCapacityGroup")
    public record Group(String key, CapacityGroup.Kind kind, UUID id, String code, String name,
                        @Schema(description = "null for an area: areas have no capacity") BigDecimal capacityM3,
                        BigDecimal occupiedM3,
                        @Schema(description = "capacity - occupied; null for an area") BigDecimal freeM3,
                        BigDecimal occupiedPercent, BigDecimal freePercent, int binsWithoutVolume) {
    }

    /**
     * One warehouse's board (06 §4.6 step 3). m³ to three decimals, percentages to two (null without capacity).
     *
     * @param binsWithoutVolume            bins on the layout whose level has no clear height (C40): not counted
     * @param cartonsWithoutSize           cartons standing there whose unit has no size: not counted in occupied
     * @param productionCartonsWithoutSize cartons production orders bring whose SKU has no carton size: not counted in
     *                                     incoming or pending
     * @param groups                       zones, the shelves of no zone, shelves and areas; empty where only the
     *                                     warehouse's own line is wanted
     */
    @Schema(name = "WarehouseCapacityBoard")
    public record Board(UUID warehouseId, String warehouseCode, String warehouseName, boolean active,
                        @Schema(description = "(0, 1]: 0.85 is 85%") BigDecimal utilizationFactor,
                        @Schema(description = "Σ the computed volume of the usable bins, before the factor")
                        BigDecimal binVolumeM3,
                        BigDecimal capacityM3, BigDecimal occupiedM3, BigDecimal occupiedKg, BigDecimal incomingM3,
                        @Schema(description = "capacity - occupied - incoming; may be negative")
                        BigDecimal projectedFreeM3,
                        @Schema(description = "production orders not sent yet (DRAFT; PENDING_APPROVAL on rows of "
                                + "before 2026-11-20)")
                        BigDecimal pendingM3,
                        @Schema(description = "projected free - pending; may be negative")
                        BigDecimal freeIfAllApprovedM3,
                        @Schema(description = "held for orders not delivered yet; for reference, never added to free")
                        BigDecimal outgoingM3,
                        BigDecimal occupiedPercent, BigDecimal projectedFreePercent, int binsWithoutVolume,
                        int cartonsWithoutSize, int productionCartonsWithoutSize, List<Group> groups) {
    }

    /**
     * The board of a production order's receiving warehouse and whether the production order fits it (17 §4.3 steps
     * 6-7, BR-25) - judged without its own cartons, which the board already counts. A warning, never a refusal.
     *
     * @param neededM3 what it still brings (its outstanding cartons); null when a SKU's carton has no size
     */
    @Schema(name = "ProductionOrderCapacity")
    public record ProductionOrderCapacity(Board board, BigDecimal neededM3, boolean exceedsProjected,
                                          boolean exceedsIfAllApproved, BigDecimal shortfallM3) {
    }

    /**
     * One bin's load (#456; kltn-docs 06 §4.6, BR-19, BR-20): the board's columns for a single bin, so a location list
     * can say which bins are empty, nearly full or blocked. Measured as the board measures a shelf - occupied is every
     * carton IN_STOCK there, capacity the bin's volume times the warehouse's utilization factor, and the bins of a shelf
     * add up to the shelf's row. Advisory, computed when read, never stored.
     */
    @Schema(name = "BinOccupancy")
    public record BinOccupancy(
            String locationCode,
            String shelfCode,
            @Schema(description = "The bin's effective status: its own, or its shelf's or warehouse's when those are "
                    + "not ACTIVE") String status,
            @Schema(description = "It may hold cartons now. An unusable bin offers no room: capacity, free and the "
                    + "percentage are null, as the board does not count it") boolean usable,
            int cartons, int skus, int units,
            @Schema(description = "Volume x utilization factor; null without a volume (no clear height) or when not "
                    + "usable") BigDecimal capacityM3,
            BigDecimal occupiedM3,
            BigDecimal occupiedKg,
            @Schema(description = "What putaway trips on their way hold in it (05 BR-16), not in occupied")
            BigDecimal heldByTripsM3,
            @Schema(description = "capacity - occupied, as the board's shelf row; null with capacity") BigDecimal freeM3,
            @Schema(description = "Its weight limit; null for none") BigDecimal maxWeightKg,
            @Schema(description = "maxWeight - occupied weight; null without a limit") BigDecimal freeKg,
            @Schema(description = "occupied / capacity, two decimals; null with capacity") BigDecimal occupiedPercent,
            @Schema(description = "Cartons without a size: they take no volume here, so occupied is short by them")
            int cartonsWithoutSize,
            @Schema(description = "A cycle count still open counts a slot in it") boolean underOpenCount) {
    }

    /** A warehouse's board with what it was worked out from, for the callers in this module. */
    public record Computed(CapacityBoard board, Board view) {
    }

    private final WarehouseCapacities warehouseMaps;
    private final WarehouseOccupancy occupancy;
    private final WarehouseDirectory warehouses;
    private final OpenProductionCartons production;
    private final ApplicationEventPublisher events;

    WarehouseCapacityService(WarehouseCapacities warehouseMaps, WarehouseOccupancy occupancy,
                             WarehouseDirectory warehouses, OpenProductionCartons production,
                             ApplicationEventPublisher events) {
        this.warehouseMaps = warehouseMaps;
        this.occupancy = occupancy;
        this.warehouses = warehouses;
        this.production = production;
        this.events = events;
    }

    // ---- the board's own endpoints ----------------------------------------------------------------------------------

    /** Every active warehouse the caller may see (a caller limited to warehouses, theirs), by code, with its rows. */
    public List<Board> boards() {
        Optional<AssignedWarehouses> limited = WarehouseScope.restriction();
        List<UUID> ids = warehouses.active().stream().map(WarehouseDirectory.Warehouse::id)
                .filter(id -> limited.map(allowed -> allowed.ids().contains(id)).orElse(true)).toList();
        return compute(ids, true).values().stream().map(Computed::view).toList();
    }

    /**
     * One warehouse's board with its rows, active or not.
     *
     * @throws BusinessException {@code OUT_OF_DATA_SCOPE} for a warehouse the caller is not assigned to;
     *         {@code WAREHOUSE_NOT_FOUND}
     */
    public Board board(UUID warehouseId) {
        WarehouseScope.requireWarehouse(warehouseId);
        Computed computed = compute(List.of(warehouseId), true).get(warehouseId);
        if (computed == null) {
            throw new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND, "No warehouse " + warehouseId);
        }
        return computed.view();
    }

    /**
     * The load of every bin of one warehouse, by location code (#456): empty bins too, with zeros; no areas - an area
     * has no capacity (06 BR-19, decided 2026-11-13). Constant in the number of bins: the map's bins in one read,
     * what stands in them and what trips hold in a few grouped reads for the whole warehouse.
     *
     * @throws BusinessException {@code OUT_OF_DATA_SCOPE} for a warehouse the caller is not assigned to;
     *         {@code WAREHOUSE_NOT_FOUND}
     */
    public List<BinOccupancy> binOccupancy(UUID warehouseId) {
        WarehouseScope.requireWarehouse(warehouseId);
        WarehouseCapacity map = warehouseMaps.capacityOf(warehouseId).orElseThrow(() -> new BusinessException(
                ErrorCode.WAREHOUSE_NOT_FOUND, "No warehouse " + warehouseId));
        Map<String, LocationLoad> loads = occupancy.loadsIn(warehouseId, map.prefix());
        List<BinOccupancy> bins = new ArrayList<>();
        for (BinCapacity bin : warehouseMaps.binsOf(warehouseId)) {
            LocationLoad load = loads.get(bin.locationCode());
            BigDecimal occupiedM3 = load == null ? BigDecimal.ZERO : load.occupiedM3();
            BigDecimal occupiedKg = (load == null ? BigDecimal.ZERO : load.occupiedKg())
                    .setScale(KG_SCALE, RoundingMode.HALF_UP);
            CapacityBoard.Level level = CapacityBoard.level(bin.usable() ? bin.volumeM3() : null,
                    map.utilizationFactor(), occupiedM3);
            BigDecimal freeKg = bin.maxWeightKg() == null ? null
                    : bin.maxWeightKg().subtract(occupiedKg).setScale(KG_SCALE, RoundingMode.HALF_UP);
            bins.add(new BinOccupancy(bin.locationCode(), bin.shelfCode(), bin.status(), bin.usable(),
                    load == null ? 0 : load.cartons(), load == null ? 0 : load.skus(), load == null ? 0 : load.units(),
                    level.capacityM3(), level.occupiedM3(), occupiedKg,
                    (load == null ? BigDecimal.ZERO : load.heldByTripsM3()).setScale(CapacityBoard.SCALE,
                            RoundingMode.HALF_UP),
                    level.freeM3(), bin.maxWeightKg(), freeKg, level.occupiedPercent(),
                    load == null ? 0 : load.cartonsWithoutSize(), load != null && load.underOpenCount()));
        }
        return bins;
    }

    // ---- for this module --------------------------------------------------------------------------------------------

    /** The boards of these warehouses, without rows; an unknown id is absent. No scope: see the class comment. */
    public Map<UUID, Computed> boardsOf(Collection<UUID> warehouseIds) {
        return compute(List.copyOf(new LinkedHashSet<>(warehouseIds)), false);
    }

    /** One carton of each SKU, m³; a SKU with no carton size is absent. */
    public Map<String, BigDecimal> cartonVolumes(Collection<String> skus) {
        return skus.isEmpty() ? Map.of() : occupancy.cartonVolumesM3(Set.copyOf(skus));
    }

    /** The capacity of each production order's receiving warehouse and the production order's check, by its id. */
    public Map<UUID, ProductionOrderCapacity> ofProductionOrders(Collection<ProductionOrder> productionOrders) {
        if (productionOrders.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Computed> boards = boardsOf(productionOrders.stream().map(ProductionOrder::receivingWarehouseId)
                .toList());
        Set<String> skus = new HashSet<>();
        productionOrders.forEach(p -> p.lines().forEach(line -> skus.add(line.sku())));
        Map<String, BigDecimal> volumes = cartonVolumes(skus);
        Map<UUID, ProductionOrderCapacity> out = new LinkedHashMap<>();
        for (ProductionOrder p : productionOrders) {
            Computed computed = boards.get(p.receivingWarehouseId());
            if (computed == null) {
                continue;
            }
            BigDecimal volume = outstandingVolume(p, volumes);
            boolean pending = isPending(p.status());
            CapacityBoard.CapacityCheck check = computed.board().check(volume,
                    p.sentAndOpen() ? volume : BigDecimal.ZERO, pending ? volume : BigDecimal.ZERO);
            out.put(p.id(), new ProductionOrderCapacity(computed.view(), check.neededM3(), check.exceedsProjected(),
                    check.exceedsIfAllApproved(), check.shortfallM3()));
        }
        return out;
    }

    public ProductionOrderCapacity ofProductionOrder(ProductionOrder productionOrder) {
        return ofProductionOrders(List.of(productionOrder)).get(productionOrder.id());
    }

    /**
     * In the send's transaction, before {@code productionOrder} is sent: when sending it turns its warehouse's
     * projected free room from zero or more to below zero, publishes {@link WarehouseCapacityTurnedNegative} (06 §4.6
     * step 8, C42) - told after commit, once. The board is read under {@code warehouse-capacity:<id>} with the
     * production order still a draft, so its cartons are not yet incoming: "before" is the board, "after" the board
     * less them. A production order of a SKU with no carton size cannot be judged and tells nobody.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void warnIfSendingTurnsNegative(ProductionOrder productionOrder, Instant at) {
        UUID warehouseId = productionOrder.receivingWarehouseId();
        production.lockWarehouseCapacity(warehouseId);
        Computed computed = boardsOf(List.of(warehouseId)).get(warehouseId);
        if (computed == null) {
            return;
        }
        Set<String> skus = new HashSet<>();
        productionOrder.lines().forEach(line -> skus.add(line.sku()));
        BigDecimal volume = outstandingVolume(productionOrder, cartonVolumes(skus));
        computed.board().turnsNegativeWith(volume).ifPresent(shortfall -> {
            log.info("Production order {} takes the projected free room of warehouse {} below zero, by {} m3",
                    productionOrder.code(), computed.view().warehouseCode(), shortfall.toPlainString());
            events.publishEvent(new WarehouseCapacityTurnedNegative(Identifiers.newId(), warehouseId,
                    productionOrder.id(), productionOrder.code(), shortfall, at));
        });
    }

    /**
     * What a production order still brings, m³: its lines' outstanding cartons ({@code cartons - received}) times a
     * carton; zero once it is received, cancelled or closed; null when a line still to come has no carton size.
     */
    public static BigDecimal outstandingVolume(ProductionOrder productionOrder, Map<String, BigDecimal> cartonVolumes) {
        if (!productionOrder.status().open()) {
            return BigDecimal.ZERO;
        }
        BigDecimal volume = BigDecimal.ZERO;
        for (ProductionOrder.Line line : productionOrder.lines()) {
            int outstanding = line.cartons() - line.receivedCartons();
            if (outstanding <= 0) {
                continue;
            }
            BigDecimal carton = cartonVolumes.get(line.sku());
            if (carton == null) {
                return null;
            }
            volume = volume.add(carton.multiply(BigDecimal.valueOf(outstanding)));
        }
        return volume;
    }

    private static boolean isPending(ProductionOrderStatus status) {
        return status == ProductionOrderStatus.DRAFT || status == ProductionOrderStatus.PENDING_APPROVAL;
    }

    // ---- computing -------------------------------------------------------------------------------------------------

    private Map<UUID, Computed> compute(List<UUID> warehouseIds, boolean withGroups) {
        Map<UUID, WarehouseCapacity> maps = new LinkedHashMap<>();
        for (UUID id : warehouseIds) {
            warehouseMaps.capacityOf(id).ifPresent(map -> maps.put(id, map));
        }
        if (maps.isEmpty()) {
            return Map.of();
        }
        List<OpenProductionCartons.Outstanding> outstanding = production.outstanding(maps.keySet());
        Map<String, BigDecimal> volumes = cartonVolumes(outstanding.stream().map(OpenProductionCartons.Outstanding::sku)
                .toList());
        Map<UUID, Computed> out = new LinkedHashMap<>();
        for (WarehouseCapacity map : maps.values()) {
            BigDecimal incoming = BigDecimal.ZERO;
            BigDecimal pending = BigDecimal.ZERO;
            int unsized = 0;
            for (OpenProductionCartons.Outstanding o : outstanding) {
                if (!o.warehouseId().equals(map.warehouseId())) {
                    continue;
                }
                BigDecimal carton = volumes.get(o.sku());
                if (carton == null) {
                    unsized += o.incomingCartons() + o.pendingCartons();
                    continue;
                }
                incoming = incoming.add(carton.multiply(BigDecimal.valueOf(o.incomingCartons())));
                pending = pending.add(carton.multiply(BigDecimal.valueOf(o.pendingCartons())));
            }
            OccupiedVolume occupied = occupancy.occupiedIn(map.prefix());
            BigDecimal outgoing = occupancy.heldVolumeM3(map.prefix());
            CapacityBoard board = new CapacityBoard(map.binVolumeM3(), map.utilizationFactor(), occupied.totalM3(),
                    incoming, pending, outgoing);
            out.put(map.warehouseId(), new Computed(board, view(map, board, occupied, unsized,
                    withGroups ? groups(map, occupied) : List.of())));
        }
        return out;
    }

    private static Board view(WarehouseCapacity map, CapacityBoard board, OccupiedVolume occupied, int unsized,
                              List<Group> groups) {
        return new Board(map.warehouseId(), map.prefix(), map.name(), map.active(), map.utilizationFactor(),
                board.binVolumeM3(), board.capacityM3(), board.occupiedM3(),
                occupied.totalKg().setScale(KG_SCALE, RoundingMode.HALF_UP), board.incomingM3(),
                board.projectedFreeM3(), board.pendingM3(), board.freeIfAllApprovedM3(), board.outgoingM3(),
                board.occupiedPercent(), board.projectedFreePercent(), map.binsWithoutVolume(),
                occupied.cartonsWithoutSize(), unsized, groups);
    }

    /** Each row's occupied volume is what stands at the locations the map puts in it. */
    private static List<Group> groups(WarehouseCapacity map, OccupiedVolume occupied) {
        Map<String, BigDecimal> occupiedByGroup = new HashMap<>();
        occupied.m3ByLocation().forEach((location, volume) -> map.groupsOfLocation().getOrDefault(location, List.of())
                .forEach(key -> occupiedByGroup.merge(key, volume, BigDecimal::add)));
        List<Group> groups = new ArrayList<>();
        for (CapacityGroup group : map.groups()) {
            CapacityBoard.Level level = CapacityBoard.level(group.binVolumeM3(), map.utilizationFactor(),
                    occupiedByGroup.getOrDefault(group.key(), BigDecimal.ZERO));
            groups.add(new Group(group.key(), group.kind(), group.id(), group.code(), group.name(), level.capacityM3(),
                    level.occupiedM3(), level.freeM3(), level.occupiedPercent(), level.freePercent(),
                    group.binsWithoutVolume()));
        }
        return groups;
    }
}
