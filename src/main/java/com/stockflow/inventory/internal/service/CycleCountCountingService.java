package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.internal.domain.CountSlot;
import com.stockflow.inventory.internal.domain.CycleCount;
import com.stockflow.inventory.internal.domain.CycleCountLine;
import com.stockflow.inventory.internal.domain.CycleCountRepository;
import com.stockflow.inventory.internal.domain.CycleCountStatus;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The counter's side of a cycle count (kltn-docs 20 §4.2 b, c; team plan 4C-3): scanning a carton of a line, adding a
 * carton found that the plan did not list, and submitting. Reached only through {@link CycleCountService}, which owns
 * the {@link CycleCounts} contract.
 *
 * <p>A carton is stated present or not found, never counted in units (BR-14): the count at receipt is the standard
 * count and is trusted, so a scan is the count. Whether the books have the carton at its bin is read when the line is
 * counted (BR-09), from the carton under its lock: IN_STOCK at the line's bin is booked; LOST, or emptied since, is
 * not.</p>
 *
 * <p><b>Lock order</b>, the one every writer of stock keeps (#125): the count row ({@code FOR UPDATE}), then the SKU
 * advisory lock, then - adding a line - the slot lock a plan takes, then the carton ({@code FOR UPDATE}). Taking the
 * count row first serialises everything done to one count; taking the SKU before the carton is what an approval of
 * the same carton does, so the two cannot wait on each other in a circle.</p>
 *
 * <p>Package-private class with public methods: the transactional proxy advises public methods only.</p>
 */
@Service
class CycleCountCountingService {

    private static final Logger log = LoggerFactory.getLogger(CycleCountCountingService.class);

    private final CycleCountRepository counts;
    private final CycleCountViewMapper views;
    private final InventoryPolicyRepository policies;
    private final StockPackageRepository stockPackages;
    private final StockItemRepository stockItems;
    private final StockOperations operations;
    private final WarehouseDirectory warehouseDirectory;
    private final Clock clock;
    /** BR-12, as the plan reads it; not final: a test lowers it to reach the cap without 500 lines. */
    private int maxLines;

    CycleCountCountingService(CycleCountRepository counts, CycleCountViewMapper views,
                              InventoryPolicyRepository policies, StockPackageRepository stockPackages,
                              StockItemRepository stockItems, StockOperations operations,
                              WarehouseDirectory warehouseDirectory, Clock clock,
                              @Value("${stockflow.inventory.cycle-count-max-lines:500}") int maxLines) {
        this.counts = counts;
        this.views = views;
        this.policies = policies;
        this.stockPackages = stockPackages;
        this.stockItems = stockItems;
        this.operations = operations;
        this.warehouseDirectory = warehouseDirectory;
        this.clock = clock;
        this.maxLines = maxLines;
    }

    /**
     * The line's carton scanned at its bin ({@code found}) or not found there. A carton moved whole since planning is
     * counted where it is now: the line follows it (20 §4.2 b6). Only a carton that can hold stock - IN_STOCK, or LOST
     * and found again - is found; one emptied since (picked, written off) is not there to scan.
     */
    @Transactional
    public CycleCountView count(UUID id, UUID lineId, boolean found, UUID actor, boolean mayDecide) {
        CycleCount count = lockForCounting(id, actor, mayDecide);
        requireOpen(count);
        CycleCountLine line = count.lines().stream().filter(l -> l.id().equals(lineId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No such line on this count"));
        count.requireMayCount(line, actor);
        if (!line.slot().isCarton()) {
            // Only a count planned before counts were by carton could hold one, and none is open since V20261113000300.
            throw new BusinessException(ErrorCode.CONFLICT, "Line %s counts loose stock; counts are by carton".formatted(
                    line.id()));
        }
        policies.lock(line.slot().sku().code());
        StockPackage carton = lockCarton(line.slot().stockPackageId());
        if (carton.status() == StockPackageStatus.IN_STOCK) {
            // The line object is the aggregate's own: relocating it moves this very slot.
            follow(count, line, carton, prefixOf(count));
        }
        requireFindable(carton, found);
        count.count(lineId, found, booked(carton, line.slot().location()), carton.nominalQty(), actor,
                clock.instant());
        return views.of(counts.save(count), mayDecide);
    }

    /**
     * A carton scanned at a bin that the plan did not list (20 §4.2 b5). It must be recorded at that bin: one recorded
     * elsewhere is misplaced, which a move puts right, not an adjustment (edge case "lệch vị trí" of 20 §6) - and a
     * LOST one is brought back to its recorded bin and scanned there, which is how it is found again: the line raises
     * the surplus that restocks it.
     */
    @Transactional
    public CycleCountView addLine(UUID id, AddCountLineCommand command) {
        CycleCount count = lockForCounting(id, command.actor(), command.mayDecide());
        requireOpen(count);
        String prefix = prefixOf(count);
        String lpn = CycleCountInput.upper(command.lpn());
        if (lpn == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Scan the carton's LPN");
        }
        LocationId location = CycleCountInput.location("locationCode", command.locationCode());
        if (!prefix.equals(location.warehouseCode())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "%s is not a location of warehouse %s".formatted(location, prefix));
        }
        // A carton of another warehouse is answered as not found, without its whereabouts, and before any lock is
        // taken on its behalf.
        StockPackage seen = stockPackages.findByLpn(lpn)
                .filter(carton -> prefix.equals(carton.location().warehouseCode()))
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND,
                        "No carton with LPN %s in warehouse %s".formatted(lpn, prefix)));
        StockItem content = contentOf(seen);
        policies.lock(content.sku().code());
        requireFree(count, CountSlot.carton(seen.location(), content.sku(), content.lotNumber(), seen.id()));
        StockPackage carton = lockCarton(seen.id());
        if (carton.status() != StockPackageStatus.IN_STOCK && carton.status() != StockPackageStatus.LOST) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Carton %s is %s and cannot be counted".formatted(carton.lpn(), carton.status()));
        }
        if (!carton.location().equals(location)) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_WRONG_LOCATION,
                    "Carton %s is recorded at %s, not %s; move it (or bring it back) before counting it here"
                            .formatted(carton.lpn(), carton.location(), location));
        }
        CountSlot slot = CountSlot.carton(carton.location(), content.sku(), content.lotNumber(), carton.id());
        count.addLine(slot, booked(carton, location), carton.nominalQty(), command.actor(), clock.instant(),
                maxLines);
        return views.of(counts.save(count), command.mayDecide());
    }

    /**
     * Each line whose carton was found against the books - missing (a shortage) or a LOST carton found (a surplus) -
     * becomes one pending {@code COUNT_VARIANCE} adjustment of that whole carton (20 §4.2 c8), all in this transaction:
     * a refused line rolls back the whole submission, so the count stays open and no half of it waits for approval.
     *
     * <p>BR-09: the books are read again under the carton locks, for every counted line - the rule speaks of any line,
     * and a line that raises nothing still records what the shelf held. A line whose carton is no longer as it was
     * judged - moved out of the line's bin, emptied, written off, lost or found since it was counted - is
     * {@code CYCLE_COUNT_STALE}, named, and counted again. The 500-line cap (BR-12) bounds the locked reads.</p>
     *
     * <p>Locks first, in the order of every stock writer: the SKUs of the counted lines in sorted order, then their
     * cartons in id order. Each request then takes its SKU lock again (an advisory lock is re-entrant), so SKUs are
     * never taken out of order and two submissions over the same SKUs cannot deadlock.</p>
     */
    @Transactional
    public CycleCountView submit(UUID id, UUID actor, boolean mayDecide) {
        CycleCount count = lockForCounting(id, actor, mayDecide);
        if (count.status() == CycleCountStatus.COMPLETED) {
            return views.of(count, mayDecide);
        }
        // A cancelled count is answered as closed before any stock lock, as count and addLine answer it - not with
        // whatever a carton check would say about a line nobody will count again.
        requireOpen(count);
        List<CycleCountLine> counted = count.lines().stream()
                .filter(line -> line.isCounted() && line.slot().isCarton())
                .toList();
        counted.stream().map(line -> line.slot().sku().code()).distinct().sorted().forEach(policies::lock);
        Map<UUID, CycleCountLine> byCarton = counted.stream()
                .collect(Collectors.toMap(line -> line.slot().stockPackageId().value(), line -> line));
        if (!byCarton.isEmpty()) {
            for (StockPackage carton : stockPackages.findAllByIdForUpdate(byCarton.values().stream()
                    .map(line -> line.slot().stockPackageId()).toList())) {
                CycleCountLine line = byCarton.get(carton.id().value());
                if (!asJudged(line, carton)) {
                    throw new BusinessException(ErrorCode.CYCLE_COUNT_STALE,
                            "Line %s: carton %s is %s at %s since it was counted; count the line again".formatted(
                                    line.id(), carton.lpn(), carton.status(), carton.location()));
                }
            }
        }
        count.submit(clock.instant());
        String note = "Count " + count.number();
        List<CycleCountLine> ordered = counted.stream().filter(line -> line.delta() != 0)
                .sorted(Comparator.comparing((CycleCountLine line) -> line.slot().sku().code())
                        .thenComparing(line -> line.slot().lockKey()))
                .toList();
        for (CycleCountLine line : ordered) {
            operations.requestCountVariance(line.id(), line.slot(), line.delta(), actor, note);
        }
        CycleCount saved = counts.save(count);
        log.info("Cycle count {} submitted by {}: {} carton(s) differ, as many adjustment(s) asked", count.number(),
                actor, ordered.size());
        return views.of(saved, mayDecide);
    }

    /**
     * A carton still holds, or may again hold, what a recount is about: IN_STOCK, or LOST (a count may find it).
     * Emptied (CONSUMED) or voided since, there is nothing left to count - a stale line, named.
     */
    static void requireCountable(StockPackage carton, CycleCountLine line) {
        if (carton.status() != StockPackageStatus.IN_STOCK && carton.status() != StockPackageStatus.LOST) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_STALE,
                    "Line %s: carton %s is %s since it was counted; count the line again"
                            .formatted(line.id(), carton.lpn(), carton.status()));
        }
    }

    /**
     * The carton is still as the line judged it (BR-09): booked - in stock at the line's bin; a LOST carton found - still
     * LOST; neither booked nor found - still not in stock at that bin.
     */
    private static boolean asJudged(CycleCountLine line, StockPackage carton) {
        if (line.booked()) {
            return booked(carton, line.slot().location());
        }
        return Boolean.TRUE.equals(line.found())
                ? carton.status() == StockPackageStatus.LOST
                : !booked(carton, line.slot().location());
    }

    /** The books have the carton at {@code bin}: in stock there. A LOST, emptied or voided carton is not booked. */
    private static boolean booked(StockPackage carton, LocationId bin) {
        return carton.status() == StockPackageStatus.IN_STOCK && carton.location().equals(bin);
    }

    /** Only a carton that can hold stock is found; any carton can be not found. */
    private static void requireFindable(StockPackage carton, boolean found) {
        if (found && carton.status() != StockPackageStatus.IN_STOCK && carton.status() != StockPackageStatus.LOST) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Carton %s is %s and cannot be found on the shelf".formatted(carton.lpn(), carton.status()));
        }
    }

    /**
     * The line follows its carton to the bin it was moved to whole, inside the count's warehouse. A carton that left
     * the warehouse is no longer this count's to count.
     */
    private static void follow(CycleCount count, CycleCountLine line, StockPackage carton, String prefix) {
        if (carton.location().equals(line.slot().location())) {
            return;
        }
        if (!prefix.equals(carton.location().warehouseCode())) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_WRONG_LOCATION,
                    "Carton %s has left warehouse %s".formatted(carton.lpn(), prefix));
        }
        count.relocateCarton(line.id(), carton.location());
    }

    private String prefixOf(CycleCount count) {
        return warehouseDirectory.prefixOf(count.warehouseId()).orElseThrow();
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
     * The SKU and lot of a carton, from its row (one per carton): the carton itself holds no quantity and no lot
     * (V20261020000100). A LOST carton's row is kept at zero, which is what lets a count find it again.
     */
    private StockItem contentOf(StockPackage carton) {
        return stockItems.findIdsInStockPackage(carton.id()).stream()
                .map(stockItems::findById).flatMap(Optional::stream).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.CYCLE_COUNT_UNKNOWN_LAYER,
                        "Carton %s has never held stock".formatted(carton.lpn())));
    }

    /** Another open count, or a variance still pending approval, holds the carton (the plan's rule, BR-10). */
    private void requireFree(CycleCount count, CountSlot slot) {
        Map<CountSlot, String> busy = counts.claim(List.of(slot));
        String holder = busy.get(slot);
        if (holder != null && !holder.equals(count.number())) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_SLOT_BUSY,
                    "%s is on count %s".formatted(slot.lockKey(), holder));
        }
    }
}
