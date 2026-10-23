package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.internal.domain.CountSlot;
import com.stockflow.inventory.internal.domain.CycleCount;
import com.stockflow.inventory.internal.domain.CycleCountLine;
import com.stockflow.inventory.internal.domain.CycleCountRepository;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.domain.WarehouseDirectory;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.service.CycleCounts.AddCountLineCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The counter's side of a cycle count (spec §5, §6): counting a line, adding a slot found on the shelf, and
 * submitting. Reached only through {@link CycleCountService}, which owns the {@link CycleCounts} contract.
 *
 * <p><b>Lock order</b>, the one every writer of stock keeps (#125): the count row ({@code FOR UPDATE}), then the SKU
 * advisory lock, then - adding a line - the slot lock a plan takes, then the carton ({@code FOR UPDATE}), then the
 * stock rows (the book quantity is read {@code FOR SHARE}). Taking the count row first serialises everything done to
 * one count; taking the SKU before the carton is what an approval of the same carton does, so the two cannot wait on
 * each other in a circle.</p>
 *
 * <p>Package-private class with public methods: the transactional proxy advises public methods only.</p>
 */
@Service
class CycleCountCounting {

    private static final Logger log = LoggerFactory.getLogger(CycleCountCounting.class);

    private final CycleCountRepository counts;
    private final CycleCountViews views;
    private final InventoryPolicyRepository policies;
    private final StockPackageRepository stockPackages;
    private final StockItemRepository stockItems;
    private final StockOperations operations;
    private final WarehouseDirectory warehouseDirectory;
    private final Clock clock;

    CycleCountCounting(CycleCountRepository counts, CycleCountViews views, InventoryPolicyRepository policies,
                       StockPackageRepository stockPackages, StockItemRepository stockItems, StockOperations operations,
                       WarehouseDirectory warehouseDirectory, Clock clock) {
        this.counts = counts;
        this.views = views;
        this.policies = policies;
        this.stockPackages = stockPackages;
        this.stockItems = stockItems;
        this.operations = operations;
        this.warehouseDirectory = warehouseDirectory;
        this.clock = clock;
    }

    /**
     * A carton line takes a quantity, or {@code present} when the carton is still sealed: the count at receipt is
     * trusted (#125), so scanning it is the count. An opened carton has no trusted figure and must be counted.
     */
    @Transactional
    public CycleCountView count(UUID id, UUID lineId, Integer countedQty, Boolean present,
                                UUID actor, boolean mayDecide) {
        if ((countedQty == null) == (present == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Send exactly one of countedQty and present");
        }
        CycleCount count = lockForCounting(id, actor, mayDecide);
        requireOpen(count);
        CycleCountLine line = count.lines().stream().filter(l -> l.id().equals(lineId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No such line on this count"));
        CountSlot slot = line.slot();
        policies.lock(slot.sku().code());
        int counted;
        if (slot.isCarton()) {
            StockPackage carton = lockCarton(slot.stockPackageId());
            requireAt(carton, slot.location());
            counted = cartonCount(carton, countedQty, present);
        } else {
            if (present != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "present is for a sealed carton; count loose stock in units");
            }
            counted = countedQty;
        }
        count.count(lineId, counted, counts.bookQuantity(slot), actor, clock.instant());
        return views.of(counts.save(count), mayDecide);
    }

    /**
     * A found loose slot must already have a row in that bin (spec D6): goods of a SKU and lot the bin has no record
     * of were put in the wrong place, and the fix is a move, not a write-up that would leave the stock they came from
     * overstated. A found carton must be where it is recorded, for the same reason - a LOST one included.
     */
    @Transactional
    public CycleCountView addLine(UUID id, AddCountLineCommand command) {
        CycleCount count = lockForCounting(id, command.actor(), command.mayDecide());
        requireOpen(count);
        String prefix = warehouseDirectory.prefixOf(count.warehouseId()).orElseThrow();
        String lpn = upper(command.lpn());
        String locationCode = upper(command.locationCode());
        CountSlot slot;
        int counted;
        if (lpn != null) {
            StockPackage found = stockPackages.findByLpn(lpn).orElseThrow(() -> new BusinessException(
                    ErrorCode.STOCK_PACKAGE_NOT_FOUND, "No carton with LPN " + lpn));
            StockItem content = contentOf(found);
            policies.lock(content.sku().code());
            requireFree(count, CountSlot.carton(found.location(), content.sku(), content.lotNumber(), found.id()));
            StockPackage carton = lockCarton(found.id());
            if (!prefix.equals(carton.location().warehouseCode())) {
                throw wrongLocation(carton, "warehouse " + prefix);
            }
            if (locationCode != null) {
                requireAt(carton, new LocationId(locationCode));
            }
            slot = CountSlot.carton(carton.location(), content.sku(), content.lotNumber(), carton.id());
            counted = cartonCount(carton, command.countedQty(), null);
        } else {
            LocationId location = new LocationId(Objects.requireNonNull(locationCode, "locationCode"));
            if (!prefix.equals(location.warehouseCode())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "%s is not a location of warehouse %s".formatted(location, prefix));
            }
            Sku sku = new Sku(Objects.requireNonNull(command.sku(), "sku"));
            slot = CountSlot.loose(location, sku, command.lotNumber());
            policies.lock(sku.code());
            String lot = slot.lotNumber();
            boolean layer = stockItems.findLooseBySkuAndLocation(sku, location).stream()
                    .anyMatch(item -> Objects.equals(CountSlot.loose(location, sku, item.lotNumber()).lotNumber(), lot));
            if (!layer) {
                throw new BusinessException(ErrorCode.CYCLE_COUNT_UNKNOWN_LAYER,
                        "%s holds no %s%s; goods found in the wrong bin are moved, not adjusted"
                                .formatted(location, sku, lot == null ? "" : " lot " + lot));
            }
            requireFree(count, slot);
            counted = command.countedQty();
        }
        count.addLine(slot, counted, counts.bookQuantity(slot), command.actor(), clock.instant());
        return views.of(counts.save(count), command.mayDecide());
    }

    /**
     * Each differing line becomes pending {@code COUNT_VARIANCE} adjustment(s), all in this transaction: a refused
     * line - stock left the slot after it was counted ({@code CYCLE_COUNT_STALE}) - rolls back the whole submission,
     * so the count stays open and no half of it waits for approval.
     *
     * <p>The lines are asked in SKU order, then by slot: each request takes its SKU's advisory lock, and two
     * submissions taking the same two SKUs in opposite orders would deadlock.</p>
     */
    @Transactional
    public CycleCountView submit(UUID id, UUID actor, boolean mayDecide) {
        CycleCount count = lockForCounting(id, actor, mayDecide);
        if (!count.submit(clock.instant())) {
            return views.of(count, mayDecide);
        }
        String note = "Count " + count.number();
        List<CycleCountLine> differing = count.lines().stream()
                .filter(line -> line.delta() != 0)
                .sorted(Comparator.comparing((CycleCountLine line) -> line.slot().sku().code())
                        .thenComparing(line -> line.slot().lockKey()))
                .toList();
        int asked = 0;
        for (CycleCountLine line : differing) {
            asked += operations.requestCountVariance(line.id(), line.slot(), line.delta(), actor, note).size();
        }
        CycleCount saved = counts.save(count);
        log.info("Cycle count {} submitted by {}: {} line(s) differ, {} adjustment(s) asked", count.number(), actor,
                differing.size(), asked);
        return views.of(saved, mayDecide);
    }

    private CycleCount lockForCounting(UUID id, UUID actor, boolean mayDecide) {
        CycleCount count = counts.findByIdForUpdate(id).orElseThrow(() -> new BusinessException(
                ErrorCode.NOT_FOUND, "No cycle count with id " + id));
        WarehouseScope.requireWarehouse(count.warehouseId());
        count.requireCounter(actor, mayDecide);
        return count;
    }

    /**
     * The aggregate refuses a closed count too, but only at the end; checked first so a closed count is answered as
     * closed, not with whatever the carton checks on the way would have said.
     */
    private static void requireOpen(CycleCount count) {
        if (!count.status().isOpen()) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_NOT_OPEN,
                    "Cycle count %s is %s".formatted(count.number(), count.status()));
        }
    }

    /** The carton re-read under its lock: its place and status are what the decision rests on. */
    private StockPackage lockCarton(StockPackageId id) {
        return stockPackages.findAllByIdForUpdate(List.of(id)).stream().findFirst().orElseThrow(() ->
                new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND, "No carton with id " + id.value()));
    }

    /**
     * The SKU and lot of a carton, from its rows: the carton itself holds no quantity and no lot (V20261020000100).
     * A LOST carton's rows are kept at zero, which is what lets a count find it again.
     */
    private StockItem contentOf(StockPackage carton) {
        List<StockItem> rows = stockItems.findIdsInStockPackage(carton.id()).stream()
                .map(stockItems::findById).flatMap(Optional::stream).toList();
        return rows.stream().filter(row -> row.onHand().value() > 0).findFirst()
                .or(() -> rows.stream().findFirst())
                .orElseThrow(() -> new BusinessException(ErrorCode.CYCLE_COUNT_UNKNOWN_LAYER,
                        "Carton %s has never held stock".formatted(carton.lpn())));
    }

    private int cartonCount(StockPackage carton, Integer countedQty, Boolean present) {
        int counted;
        if (present != null) {
            if (!carton.unopened()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Carton %s has been opened; count it in units".formatted(carton.lpn()));
            }
            counted = present ? Objects.requireNonNullElse(carton.receivedQty(), 0) : 0;
        } else {
            counted = countedQty;
        }
        // V20261022000100 rule 5: a carton never holds more than was counted into it. Units above that are loose.
        if (carton.receivedQty() != null && counted > carton.receivedQty()) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_OVERFILLED,
                    "Carton %s was received with %d; count the %d above it as loose stock"
                            .formatted(carton.lpn(), carton.receivedQty(), counted - carton.receivedQty()));
        }
        if (counted > 0 && carton.status() != StockPackageStatus.IN_STOCK && carton.status() != StockPackageStatus.LOST) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Carton %s is %s and cannot hold stock".formatted(carton.lpn(), carton.status()));
        }
        return counted;
    }

    private static void requireAt(StockPackage carton, LocationId location) {
        if (!carton.location().equals(location)) {
            throw wrongLocation(carton, location.code());
        }
    }

    private static BusinessException wrongLocation(StockPackage carton, String expected) {
        return new BusinessException(ErrorCode.STOCK_PACKAGE_WRONG_LOCATION,
                "Carton %s is recorded at %s, not %s; move it before counting it here"
                        .formatted(carton.lpn(), carton.location(), expected));
    }

    /** Another open count, or a variance still pending approval, holds the slot (the plan's rule). */
    private void requireFree(CycleCount count, CountSlot slot) {
        Map<CountSlot, String> busy = counts.claim(List.of(slot));
        String holder = busy.get(slot);
        if (holder != null && !holder.equals(count.number())) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_SLOT_BUSY,
                    "%s is on count %s".formatted(slot.lockKey(), holder));
        }
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
