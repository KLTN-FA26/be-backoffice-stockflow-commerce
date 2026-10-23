package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.internal.domain.InsufficientStockException;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Keeps a stock package (a carton) true to its rows wherever stock is taken out of one (SCRUM-473, #141).
 *
 * <p>The database checks a carton against its rows at commit (V20261020000200): an IN_STOCK carton holds
 * something, any other holds nothing, an unopened one holds exactly what was counted into it. Those checks read
 * without locking, so they are only safe when every writer keeps one lock order (V20261020000100): <b>the SKU
 * advisory lock, then the cartons ({@code FOR UPDATE}, in id order), then their stock rows by id.</b> A caller
 * therefore {@link #lockFor locks} the cartons of the rows it is about to lock - after the SKU lock, before the
 * rows - marks each row it {@link LockedCartons#tookFrom took units from}, and {@link #settle settles} once its
 * rows are saved: each carton taken from is opened, and closed CONSUMED when nothing is left in it.</p>
 *
 * <p>Taking from a carton that was not locked first is refused at once ({@link IllegalStateException}): it is the
 * lock order broken, and the commit-time check would otherwise surface the miss as a 500 - or not at all.</p>
 *
 * <p>A count's adjustment changes a carton as a whole ({@link #adjust}): the same lock order, then the units spread
 * over its rows and the carton's state changed once, in that one call - two separately approved adjustments of
 * one carton would pass through a state the commit-time check refuses.</p>
 */
@Component
class StockPackageContents {

    private final StockPackageRepository stockPackages;
    private final StockItemRepository stockItems;

    StockPackageContents(StockPackageRepository stockPackages, StockItemRepository stockItems) {
        this.stockPackages = stockPackages;
        this.stockItems = stockItems;
    }

    /** The cartons of these rows, locked in id order; none when every row is loose. Call under the SKU lock. */
    public LockedCartons lockFor(Collection<StockItem> rows) {
        Set<StockPackageId> ids = rows.stream().map(StockItem::stockPackageId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return new LockedCartons(Map.of());
        }
        return new LockedCartons(stockPackages.findAllByIdForUpdate(ids).stream()
                .collect(Collectors.toMap(StockPackage::id, Function.identity())));
    }

    /** After the rows are saved: every carton taken from is opened, or CONSUMED once empty. */
    public void settle(LockedCartons cartons, Instant now) {
        for (StockPackageId id : cartons.taken) {
            StockPackage carton = cartons.locked.get(id);
            carton.contentsTaken(stockItems.onHandInStockPackage(id), now);
            stockPackages.save(carton);
        }
    }

    /**
     * The contents of carton {@code id} changed by {@code delta}, as an approved adjustment changes them (SCRUM-164,
     * spec 2026-10-23 section 6). Call under the SKU lock; the carton and then its rows, by id, are locked here.
     *
     * <ul>
     *   <li>Written up: a LOST carton is found first, then the units go onto its first row - rows are never deleted
     *       at zero, so a lost carton still has the row it was emptied from. An IN_STOCK one must already be open:
     *       an unopened carton holds exactly what was counted into it.</li>
     *   <li>Written down: the units come from its rows in id order, each giving at most what no hold covers. A
     *       shortfall that would cut into a hold is refused whole ({@link InsufficientStockException}), as for loose
     *       stock: a customer's hold is never cancelled by a correction; the holds are reallocated first.</li>
     *   <li>Then, by what is left: nothing, and {@code missingWhenEmpty} (a count that found nothing found the carton
     *       missing) - LOST; nothing otherwise - CONSUMED; something after a write-down - opened.</li>
     * </ul>
     *
     * <p>The rows and the carton are saved here, so the commit-time check finds them consistent in every branch.</p>
     *
     * @return each row changed and the units it gained (positive) or gave (negative), in id order - one ledger line
     *         each
     */
    public List<RowChange> adjust(StockPackageId id, int delta, boolean missingWhenEmpty, Instant now) {
        if (delta == 0) {
            throw new IllegalArgumentException("An adjustment changes the quantity");
        }
        StockPackage carton = stockPackages.findAllByIdForUpdate(List.of(id)).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No stock package " + id.value()));
        List<StockItem> rows = stockItems.findIdsInStockPackage(id).stream()
                .sorted(Comparator.comparing(rowId -> rowId.value().toString()))
                .map(rowId -> stockItems.findByIdForUpdate(rowId).orElseThrow(() ->
                        new IllegalStateException("Stock row %s of %s vanished".formatted(rowId.value(), carton.lpn()))))
                .toList();
        if (rows.isEmpty()) {
            throw new IllegalStateException("Stock package %s has no stock row to adjust".formatted(carton.lpn()));
        }
        List<RowChange> changes = delta > 0
                ? fill(carton, rows, delta)
                : drain(carton, rows, -delta, missingWhenEmpty, now);
        changes.forEach(change -> stockItems.save(change.row()));
        stockPackages.save(carton);
        return changes;
    }

    private static List<RowChange> fill(StockPackage carton, List<StockItem> rows, int units) {
        if (carton.status() == StockPackageStatus.LOST) {
            carton.found();
        } else if (carton.status() != StockPackageStatus.IN_STOCK) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION,
                    "%s is %s; nothing is put back into it".formatted(carton.lpn(), carton.status()));
        } else if (carton.unopened()) {
            // A count confirms an unopened carton by its scan; it never finds more in one than was counted into it.
            throw new IllegalStateException("%s is unopened and holds what was counted into it".formatted(carton.lpn()));
        }
        int after = held(rows) + units;
        if (after > carton.receivedQty()) {
            // Rule 5 of V20261022000100, said first: units found above the count are loose stock.
            throw new IllegalStateException("%s was counted in at %d and cannot hold %d"
                    .formatted(carton.lpn(), carton.receivedQty(), after));
        }
        StockItem first = rows.getFirst();
        first.adjustBy(units);
        return List.of(new RowChange(first, units));
    }

    private static List<RowChange> drain(StockPackage carton, List<StockItem> rows, int units,
                                         boolean missingWhenEmpty, Instant now) {
        if (carton.status() != StockPackageStatus.IN_STOCK) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION,
                    "%s is %s; there is nothing in it to write down".formatted(carton.lpn(), carton.status()));
        }
        // Checked before any row changes, so a refusal leaves every row as it was read.
        int free = rows.stream().mapToInt(row -> row.movable().value()).sum();
        if (free < units) {
            throw new InsufficientStockException(rows.getFirst().sku().code(), units, free);
        }
        List<RowChange> changes = new ArrayList<>();
        int remaining = units;
        for (StockItem row : rows) {
            int take = Math.min(remaining, row.movable().value());
            if (take > 0) {
                row.adjustBy(-take);
                changes.add(new RowChange(row, -take));
                remaining -= take;
            }
            if (remaining == 0) {
                break;
            }
        }
        if (held(rows) == 0) {
            if (missingWhenEmpty) {
                carton.lose(now);
            } else {
                carton.consume(now);
            }
        } else if (carton.unopened()) {
            carton.open();
        }
        return changes;
    }

    private static int held(List<StockItem> rows) {
        return rows.stream().mapToInt(row -> row.onHand().value()).sum();
    }

    /** One row of a carton changed by {@link #adjust}: {@code delta} units in (positive) or out (negative). */
    record RowChange(StockItem row, int delta) {
    }

    /** The cartons a caller locked, and which of them it has taken units from. */
    static final class LockedCartons {

        private final Map<StockPackageId, StockPackage> locked;
        private final Set<StockPackageId> taken = new LinkedHashSet<>();

        private LockedCartons(Map<StockPackageId, StockPackage> locked) {
            this.locked = locked;
        }

        /** {@code row} gave up units; nothing to do for loose stock. */
        void tookFrom(StockItem row) {
            if (row.isLoose()) {
                return;
            }
            if (!locked.containsKey(row.stockPackageId())) {
                throw new IllegalStateException("Stock package %s was not locked before its row %s was taken from"
                        .formatted(row.stockPackageId(), row.id()));
            }
            taken.add(row.stockPackageId());
        }
    }
}
