package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
    private final LocationDirectory locations;

    StockPackageContents(StockPackageRepository stockPackages, StockItemRepository stockItems,
                         LocationDirectory locations) {
        this.stockPackages = stockPackages;
        this.stockItems = stockItems;
        this.locations = locations;
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
     * part - a pick of part of a hold - which no carton allows any more.
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
     *       (rows are never deleted at zero, and a carton is one row, V20261113000300), its lot unchanged (BR-18), as
     *       AVAILABLE stock unless it is in a QUARANTINE area ({@link #restockLost}).</li>
     *   <li>Written off: only a carton in stock, every unit of it. A customer's hold is never cancelled by a
     *       correction: a carton held for an order is refused ({@code STOCK_PACKAGE_HELD}) - released or reallocated
     *       first - with one exception, {@code giveUpHoldsBy}: a count's shortage (20 §4.2 step 8, 07 §4.4). The carton is
     *       not there, so what it held for an order is a shortage: the holds are given up ({@code SHORT_PICKED}, as a
     *       reallocation does) and announced ({@code StockHeldCartonWrittenOff}) for fulfillment to turn the order's
     *       pick line SHORT and hold the order, after the commit. The carton is then LOST when
     *       {@code missingWhenEmpty} (a count did not find it, or it was written off as lost), CONSUMED
     *       otherwise.</li>
     * </ul>
     *
     * <p>Anything but the whole carton is refused ({@code STOCK_PACKAGE_TAKEN_WHOLE}): a request of the history asked
     * for part of one, and is rejected rather than posted. The row and the carton are saved here, so the commit-time
     * check finds them consistent.</p>
     *
     * @return the row changed and the units it gained (positive) or gave (negative) - one ledger line
     */
    public List<RowChange> adjust(StockPackageId id, int delta, boolean missingWhenEmpty, Instant now) {
        return adjust(id, delta, missingWhenEmpty, null, now);
    }

    /**
     * As {@link #adjust(StockPackageId, int, boolean, Instant)}; with {@code giveUpHoldsBy} (who decided the shortage), a
     * write-off gives up the holds on the carton instead of refusing them. The caller publishes the row's events after
     * the call.
     */
    public List<RowChange> adjust(StockPackageId id, int delta, boolean missingWhenEmpty, UUID giveUpHoldsBy,
                                  Instant now) {
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
        RowChange change = delta > 0
                ? restockLost(carton, rows, delta)
                : writeOff(carton, rows, missingWhenEmpty, giveUpHoldsBy, now);
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

    /**
     * A picked carton back into stock whole, at {@code to} (kltn-docs 07 BR-05, 20 §4.6 steps 5-6): its order was
     * cancelled after the pick. The carton goes CONSUMED → IN_STOCK whole ({@link StockPackage#restock}); its row
     * follows it and gets its units back, so it is the same carton, LPN and receipt layer it was. Call under the SKU
     * lock; the carton and then its row are locked here (the global order: carton before stock row).
     *
     * @throws BusinessException {@code STOCK_PACKAGE_TAKEN_WHOLE} when {@code units} is not what was counted into
     *         it; {@code INVALID_STOCK_PACKAGE_TRANSITION} when it is not CONSUMED
     */
    public StockItem restock(StockPackageId id, com.stockflow.inventory.internal.domain.StockItemId rowId,
                             com.stockflow.inventory.internal.domain.LocationId to, int units) {
        return restock(id, rowId, to, units, false);
    }

    /**
     * As {@link #restock(StockPackageId, com.stockflow.inventory.internal.domain.StockItemId,
     * com.stockflow.inventory.internal.domain.LocationId, int)}; with {@code quarantine}, the units come back QUARANTINE
     * instead of AVAILABLE: a carton the check station removed as damaged (team plan 4C-6, kltn-docs 08 §4.2 step 4).
     */
    public StockItem restock(StockPackageId id, com.stockflow.inventory.internal.domain.StockItemId rowId,
                             com.stockflow.inventory.internal.domain.LocationId to, int units, boolean quarantine) {
        StockPackage carton = stockPackages.findAllByIdForUpdate(List.of(id)).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No stock package " + id.value()));
        StockItem row = stockItems.findByIdForUpdate(rowId).orElseThrow(() ->
                new IllegalStateException("Stock row %s of %s vanished".formatted(rowId.value(), carton.lpn())));
        if (carton.receivedQty() == null || units != carton.receivedQty() || !row.onHand().isZero()) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE, ("%s comes back into stock whole, %s "
                    + "unit(s); not %d").formatted(carton.lpn(), carton.receivedQty(), units), List.of(),
                    Map.of("lpn", carton.lpn()));
        }
        carton.restock(to);
        row.followPackageTo(to);
        row.putBack(com.stockflow.inventory.internal.domain.Quantity.of(units));
        if (quarantine) {
            row.quarantineInPackage();
        }
        // The carton first: saveMerged flushes before its own statement, so the carton stands at its new place before
        // the caller's ledger line names it there (check_stock_movement_package reads it on insert).
        stockPackages.save(carton);
        // Units back in the carton they were counted into: the same receipt layer, so the FIFO trigger is told it is
        // a merge (V20261017000450), as for any write-up of a carton.
        stockItems.saveMerged(row);
        return row;
    }

    /**
     * A carton that left stock whole comes back on a return receipt (team plan 4D-2; kltn-docs 03 §4.2 step 12,
     * BR-03, BR-14): CONSUMED → IN_STOCK at the QUARANTINE area {@code to}, sealed, with its LPN
     * ({@link StockPackage#restock}); its one row follows it and gets the carton's count back, QUARANTINE, in its own
     * lot ({@link StockItem#receiveReturnInPackage}). Call under the SKU lock, with every carton the caller brings
     * back already locked (the global order: the cartons, then their rows); the carton and then its row are locked
     * here.
     *
     * @throws BusinessException {@code INVALID_STOCK_PACKAGE_TRANSITION} when it is not CONSUMED - in stock already,
     *         never received, lost or voided; {@code STOCK_PACKAGE_TAKEN_WHOLE} when it has no single empty row to
     *         come back into (part of it was written off or turned up elsewhere: it cannot come back whole)
     */
    public StockItem returnIntoQuarantine(StockPackageId id, com.stockflow.inventory.internal.domain.LocationId to) {
        StockPackage carton = stockPackages.findAllByIdForUpdate(List.of(id)).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No stock package " + id.value()));
        if (carton.status() != StockPackageStatus.CONSUMED) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION, ("%s is %s; only a carton that "
                    + "left stock comes back on a return").formatted(carton.lpn(), carton.status()), List.of(),
                    Map.of("lpn", carton.lpn()));
        }
        List<com.stockflow.inventory.internal.domain.StockItemId> rowIds = stockItems.findIdsInStockPackage(id);
        StockItem row = rowIds.size() != 1 ? null : stockItems.findByIdForUpdate(rowIds.get(0)).orElse(null);
        if (row == null || carton.receivedQty() == null || !row.onHand().isZero() || !row.reserved().isZero()) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE, ("%s cannot come back whole: it is not "
                    + "one empty row of %s unit(s)").formatted(carton.lpn(), carton.receivedQty()), List.of(),
                    Map.of("lpn", carton.lpn()));
        }
        carton.restock(to);
        row.followPackageTo(to);
        row.receiveReturnInPackage(com.stockflow.inventory.internal.domain.Quantity.of(carton.receivedQty()));
        // The carton first, as in restock: it stands at its new place before the ledger line names it there.
        stockPackages.save(carton);
        stockItems.saveMerged(row);
        return row;
    }

    /**
     * A LOST carton found again, whole, on its own row. It comes back as sellable stock (AVAILABLE), whatever status the
     * row had when the carton went missing (review 4C-3, L4): a carton found is a carton on the shelf, and kept INBOUND,
     * BLOCKED or DAMAGED it would sit silently out of ATP with nothing to move it on. The one exception is a carton
     * recorded in a QUARANTINE area: it comes back QUARANTINE, out of ATP until a manager releases it (20 §4.8, BR-21) -
     * a carton is never sold out of quarantine by being found.
     */
    private RowChange restockLost(StockPackage carton, List<StockItem> rows, int units) {
        if (carton.status() != StockPackageStatus.LOST) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION,
                    "%s is %s; only a LOST carton is restocked".formatted(carton.lpn(), carton.status()));
        }
        if (held(rows) > 0) {
            throw new IllegalStateException("LOST stock package %s still holds units".formatted(carton.lpn()));
        }
        carton.found();
        StockItem row = rows.getFirst();
        row.comeBackAs(locations.isQuarantineArea(carton.location()) ? StockStatus.QUARANTINE : StockStatus.AVAILABLE);
        row.adjustBy(units);
        return new RowChange(row, units);
    }

    private static RowChange writeOff(StockPackage carton, List<StockItem> rows, boolean missingWhenEmpty,
                               UUID giveUpHoldsBy, Instant now) {
        if (carton.status() != StockPackageStatus.IN_STOCK) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION,
                    "%s is %s; there is nothing in it to write off".formatted(carton.lpn(), carton.status()));
        }
        StockItem row = rows.stream().filter(r -> !r.onHand().isZero()).findFirst().orElseThrow(() ->
                new IllegalStateException("Stock package %s is in stock and empty".formatted(carton.lpn())));
        if (!row.reserved().isZero() && giveUpHoldsBy == null) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_HELD,
                    "%s is held for an order; release or reallocate it before writing it off".formatted(carton.lpn()),
                    List.of(), Map.of("lpn", carton.lpn()));
        }
        if (!row.reserved().isZero()) {
            row.giveUpHoldsOfWrittenOffCarton(giveUpHoldsBy, now);
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

        /** The locked carton {@code id}; empty when it was not among those locked. */
        java.util.Optional<StockPackage> carton(StockPackageId id) {
            return java.util.Optional.ofNullable(id == null ? null : locked.get(id));
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
