package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
 * <p>The database checks a carton against its rows at commit (V20261020000200, V20261113000300): an IN_STOCK carton
 * holds exactly what was counted into it, any other holds nothing. Those checks read
 * without locking, so they are only safe when every writer keeps one lock order (V20261020000100): <b>the SKU
 * advisory lock, then the cartons ({@code FOR UPDATE}, in id order), then their stock rows by id.</b> A caller
 * therefore {@link #lockFor locks} the cartons of the rows it is about to lock - after the SKU lock, before the
 * rows - marks each row it {@link LockedCartons#tookFrom took units from}, and {@link #settle settles} once its
 * rows are saved: each carton taken from is closed CONSUMED, and must be empty - a carton leaves stock whole (team
 * plan 4C-2; kltn-docs 07 BR-03, 11 BR-04), so taking part of one is refused ({@code STOCK_PACKAGE_TAKEN_WHOLE}) and
 * the caller's transaction rolls back. The database says the same: {@code tg_stock_package_transition} refuses to
 * open a carton in stock.</p>
 *
 * <p>Taking from a carton that was not locked first is refused at once ({@link IllegalStateException}): it is the
 * lock order broken, and the commit-time check would otherwise surface the miss as a 500 - or not at all.</p>
 *
 * <p>An approved adjustment writes a carton off or restocks it whole ({@link #adjust}): the same lock order, then its
 * row and its state changed once, in that one call.</p>
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

    /**
     * After the rows are saved: every carton taken from is CONSUMED. One with units left in it was taken from in
     * part - a pick of part of a hold, damage reported on some of its units - which no carton allows any more.
     */
    public void settle(LockedCartons cartons, Instant now) {
        for (StockPackageId id : cartons.taken) {
            StockPackage carton = cartons.locked.get(id);
            int left = stockItems.onHandInStockPackage(id);
            if (left > 0) {
                throw takenInPart(carton, left);
            }
            carton.consume(now);
            stockPackages.save(carton);
        }
    }

    private static BusinessException takenInPart(StockPackage carton, int left) {
        return new BusinessException(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE,
                "%s would keep %d unit(s); a carton is taken whole, never in part".formatted(carton.lpn(), left),
                List.of(), Map.of("lpn", carton.lpn()));
    }

    /**
     * Carton {@code id} written off whole ({@code delta} minus its standard count) or restocked whole (plus it), as an
     * approved adjustment does (kltn-docs 20 §4.3, §4.6 step 7; team plan 4C-3). Call under the SKU lock; the carton
     * and then its row are locked here.
     *
     * <ul>
     *   <li>Restocked: only a LOST carton, found again whole - its standard count back on the row it was emptied from
     *       (rows are never deleted at zero, and a carton is one row, V20261113000300), its lot unchanged (BR-18).</li>
     *   <li>Written off: only a carton in stock, every unit of it, none held for an order - a customer's hold is
     *       never cancelled by a correction; it is released or reallocated first ({@code STOCK_PACKAGE_HELD}). The
     *       carton is then LOST when {@code missingWhenEmpty} (a count did not find it, or it was written off as
     *       lost), CONSUMED otherwise.</li>
     * </ul>
     *
     * <p>Anything but the whole carton is refused ({@code STOCK_PACKAGE_TAKEN_WHOLE}): a request of the history asked
     * for part of one, and is rejected rather than posted. The row and the carton are saved here, so the commit-time
     * check finds them consistent.</p>
     *
     * @return the row changed and the units it gained (positive) or gave (negative) - one ledger line
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
        if (Math.abs(delta) != carton.nominalQty()) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE,
                    "%s holds %d; it is adjusted whole, not by %d".formatted(carton.lpn(), carton.nominalQty(), delta),
                    List.of(), Map.of("lpn", carton.lpn()));
        }
        RowChange change = delta > 0 ? restock(carton, rows, delta) : writeOff(carton, rows, missingWhenEmpty, now);
        if (change.delta() > 0) {
            // Units back in the carton they were counted into: the same receipt, proven by the LPN, so no age is lost.
            // saveMerged tells the FIFO trigger so (V20261017000450), which would otherwise refuse raising an
            // existing layer and ask for a new one - and a carton is one row.
            stockItems.saveMerged(change.row());
        } else {
            stockItems.save(change.row());
        }
        stockPackages.save(carton);
        return List.of(change);
    }

    private static RowChange restock(StockPackage carton, List<StockItem> rows, int units) {
        if (carton.status() != StockPackageStatus.LOST) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION,
                    "%s is %s; only a LOST carton is restocked".formatted(carton.lpn(), carton.status()));
        }
        if (held(rows) > 0) {
            throw new IllegalStateException("LOST stock package %s still holds units".formatted(carton.lpn()));
        }
        carton.found();
        StockItem row = rows.getFirst();
        row.adjustBy(units);
        return new RowChange(row, units);
    }

    private static RowChange writeOff(StockPackage carton, List<StockItem> rows, boolean missingWhenEmpty,
                                      Instant now) {
        if (carton.status() != StockPackageStatus.IN_STOCK) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION,
                    "%s is %s; there is nothing in it to write off".formatted(carton.lpn(), carton.status()));
        }
        StockItem row = rows.stream().filter(r -> !r.onHand().isZero()).findFirst().orElseThrow(() ->
                new IllegalStateException("Stock package %s is in stock and empty".formatted(carton.lpn())));
        if (!row.reserved().isZero()) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_HELD,
                    "%s is held for an order; release or reallocate it before writing it off".formatted(carton.lpn()),
                    List.of(), Map.of("lpn", carton.lpn()));
        }
        int units = row.onHand().value();
        row.adjustBy(-units);
        if (missingWhenEmpty) {
            carton.lose(now);
        } else {
            carton.consume(now);
        }
        return new RowChange(row, -units);
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
