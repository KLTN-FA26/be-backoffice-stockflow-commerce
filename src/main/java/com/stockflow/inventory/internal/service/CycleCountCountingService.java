package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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

    CycleCountCountingService(CycleCountRepository counts, CycleCountViewMapper views,
                              InventoryPolicyRepository policies, StockPackageRepository stockPackages,
                              StockItemRepository stockItems, StockOperations operations,
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
     * trusted (#125), so scanning it is the count. An opened carton has no trusted figure and must be counted. A
     * carton moved whole since planning is counted where it is now: the line follows it.
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
        policies.lock(line.slot().sku().code());
        int counted;
        if (line.slot().isCarton()) {
            StockPackage carton = lockCarton(line.slot().stockPackageId());
            follow(count, line, carton, prefixOf(count));
            counted = cartonCount(carton, countedQty, present, judgedNow(count, mayDecide));
        } else if (countedQty == null) {
            // Exactly one of the two was sent (checked above), so a loose line without a quantity was sent present.
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "present is for a sealed carton; count loose stock in units");
        } else {
            counted = countedQty;
        }
        count.count(lineId, counted, counts.bookQuantity(line.slot()), actor, clock.instant());
        return views.of(counts.save(count), mayDecide);
    }

    /**
     * A found loose slot must already have a row in that bin (spec D6): goods of a SKU and lot the bin has no record
     * of were put in the wrong place, and the fix is a move, not a write-up that would leave the stock they came from
     * overstated. A found carton is scanned in a bin and must be recorded there, for the same reason - a LOST one
     * included: it is moved (or brought back) first, then counted.
     */
    @Transactional
    public CycleCountView addLine(UUID id, AddCountLineCommand command) {
        CycleCount count = lockForCounting(id, command.actor(), command.mayDecide());
        requireOpen(count);
        String prefix = prefixOf(count);
        String lpn = CycleCountInput.upper(command.lpn());
        LocationId location = CycleCountInput.location("locationCode", command.locationCode());
        if (!prefix.equals(location.warehouseCode())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "%s is not a location of warehouse %s".formatted(location, prefix));
        }
        CountSlot slot;
        int counted;
        if (lpn != null) {
            // A carton of another warehouse is answered as not found, without its whereabouts, and before any lock
            // is taken on its behalf.
            StockPackage found = stockPackages.findByLpn(lpn)
                    .filter(carton -> prefix.equals(carton.location().warehouseCode()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND,
                            "No carton with LPN %s in warehouse %s".formatted(lpn, prefix)));
            StockItem content = contentOf(found);
            policies.lock(content.sku().code());
            requireFree(count, CountSlot.carton(found.location(), content.sku(), content.lotNumber(), found.id()));
            StockPackage carton = lockCarton(found.id());
            if (carton.status() != StockPackageStatus.IN_STOCK && carton.status() != StockPackageStatus.LOST) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "Carton %s is %s and cannot be counted".formatted(carton.lpn(), carton.status()));
            }
            if (!carton.location().equals(location)) {
                throw new BusinessException(ErrorCode.STOCK_PACKAGE_WRONG_LOCATION,
                        "Carton %s is recorded at %s, not %s; move it (or bring it back) before counting it here"
                                .formatted(carton.lpn(), carton.location(), location));
            }
            slot = CountSlot.carton(carton.location(), content.sku(), content.lotNumber(), carton.id());
            counted = cartonCount(carton, command.countedQty(), null, judgedNow(count, command.mayDecide()));
        } else {
            Sku sku = CycleCountInput.sku("sku", command.sku());
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
     * <p>Locks first, in the order of every stock writer: the SKUs of the differing lines in sorted order, then their
     * cartons in id order. Each request then takes its SKU lock again (an advisory lock is re-entrant), so SKUs are
     * never taken out of order and two submissions over the same SKUs cannot deadlock. Under the carton locks a
     * carton line follows its carton once more, so the adjustment names the bin the carton is in now; a whole-carton
     * move never changes what is in it, so the counted delta still holds. A carton that is no longer IN_STOCK or LOST
     * (emptied by a pick since it was counted) is {@code CYCLE_COUNT_STALE} for its line: the variance was judged
     * against contents it no longer has.</p>
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
        List<CycleCountLine> differing = count.lines().stream()
                .filter(line -> line.isCounted() && line.delta() != 0)
                .toList();
        differing.stream().map(line -> line.slot().sku().code()).distinct().sorted().forEach(policies::lock);
        Map<UUID, CycleCountLine> cartonLines = differing.stream().filter(line -> line.slot().isCarton())
                .collect(Collectors.toMap(line -> line.slot().stockPackageId().value(), line -> line));
        if (!cartonLines.isEmpty()) {
            String prefix = prefixOf(count);
            for (StockPackage carton : stockPackages.findAllByIdForUpdate(cartonLines.values().stream()
                    .map(line -> line.slot().stockPackageId()).toList())) {
                CycleCountLine line = cartonLines.get(carton.id().value());
                requireCountable(carton, line);
                follow(count, line, carton, prefix);
            }
        }
        count.submit(clock.instant());
        String note = "Count " + count.number();
        List<CycleCountLine> ordered = differing.stream()
                .sorted(Comparator.comparing((CycleCountLine line) -> line.slot().sku().code())
                        .thenComparing(line -> line.slot().lockKey()))
                .toList();
        int asked = 0;
        for (CycleCountLine line : ordered) {
            asked += operations.requestCountVariance(line.id(), line.slot(), line.delta(), actor, note).size();
        }
        CycleCount saved = counts.save(count);
        log.info("Cycle count {} submitted by {}: {} line(s) differ, {} adjustment(s) asked", count.number(), actor,
                ordered.size(), asked);
        return views.of(saved, mayDecide);
    }

    /**
     * A counted carton still holds, or may again hold, what the count was about: IN_STOCK, or LOST (a count may find
     * it). Emptied (CONSUMED) or voided since, the counted variance no longer describes it - a stale line, named, so
     * the counter knows which one to count again.
     */
    static void requireCountable(StockPackage carton, CycleCountLine line) {
        if (carton.status() != StockPackageStatus.IN_STOCK && carton.status() != StockPackageStatus.LOST) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_STALE,
                    "Line %s: carton %s is %s since it was counted; count the line again"
                            .formatted(line.id(), carton.lpn(), carton.status()));
        }
    }

    /**
     * The line follows its carton to the bin it was moved to whole, inside the count's warehouse. A carton that left
     * the warehouse (a transfer) is no longer this count's to count.
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

    /**
     * {@code present=true} records {@code received_qty}, not the line's expected quantity, though for a sealed carton
     * in stock the two are equal (V20261020000100 holds a sealed carton at what was counted into it): the received
     * quantity is the trusted figure the scan stands for. Only a sealed carton takes {@code present}, so never a LOST
     * one - {@code StockPackage.lose} clears {@code unopened}, since nobody knows what a lost carton still holds; found
     * again, it is counted in units.
     *
     * @param judged whether a figure above {@code received_qty} is refused here ({@link #judgedNow}); otherwise it is
     *               recorded, and approval refuses the surplus it raises ({@code STOCK_PACKAGE_OVERFILLED})
     */
    private int cartonCount(StockPackage carton, Integer countedQty, Boolean present, boolean judged) {
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
        if (judged && carton.receivedQty() != null && counted > carton.receivedQty()) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_OVERFILLED,
                    "Carton %s cannot hold that many; count the units above what it was received with as loose stock"
                            .formatted(carton.lpn()));
        }
        if (counted > 0 && carton.status() != StockPackageStatus.IN_STOCK && carton.status() != StockPackageStatus.LOST) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Carton %s is %s and cannot hold stock".formatted(carton.lpn(), carton.status()));
        }
        return counted;
    }

    /**
     * Whether a carton figure is judged against {@code received_qty} as it is typed. Not for a blind counter: the
     * received quantity of a sealed carton is its book quantity, and a refusal - figures or not - answers "too many"
     * to each guess, so a few tries would find it. Their figure is recorded as given; a surplus above the receipt is
     * then refused at approval, in front of the manager, who rejects it and has the line recounted.
     */
    private static boolean judgedNow(CycleCount count, boolean mayDecide) {
        return mayDecide || !count.blind();
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
}
