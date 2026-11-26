package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.internal.domain.AdjustmentDirection;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.ReturnedCartonOwnership;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The whole-carton decisions that take a carton out of stock or bring it back: an approved adjustment
 * ({@link #adjust}), a put-back of a picked carton ({@link #restock}), a return receipt ({@link #returnIntoQuarantine})
 * and its reversal ({@link #reverseReturn}) (SCRUM-473, #141; team plan 4C-2, 4C-3, 4D-2).
 *
 * <p>The carton is the stock (ADR-0011, V20261230000600), so each of these is one change of one aggregate: there is no
 * stock row to keep true to its carton any more, nothing to settle after a pick ({@code StockPackage
 * #consumeReservation} takes the carton out with its hold), and no commit-time check of the two against each other.
 * What is left here is the lock and the refusal with an error code where the aggregate would only throw.</p>
 *
 * <p><b>Lock order</b>, the one every writer of a carton keeps: the SKU advisory lock (the caller's), then the carton
 * {@code FOR UPDATE}, read after the lock. Each method here locks its carton and saves it; nothing is locked after
 * it.</p>
 */
@Component
class StockPackageContents {

    private final StockPackageRepository stockPackages;
    private final LocationDirectory locations;
    private final ReturnedCartonOwnership ownership;

    StockPackageContents(StockPackageRepository stockPackages, LocationDirectory locations,
                         ReturnedCartonOwnership ownership) {
        this.stockPackages = stockPackages;
        this.locations = locations;
        this.ownership = ownership;
    }

    /**
     * Carton {@code id} written off whole or restocked whole, as an approved adjustment does (kltn-docs 20 §4.3, §4.6
     * step 7; team plan 4C-3). Call under the SKU lock; the carton is locked here.
     *
     * <ul>
     *   <li>Restocked: only a LOST carton, found again whole - the very carton, its lot and FIFO date unchanged
     *       (BR-18), as AVAILABLE stock unless it stands in a QUARANTINE area ({@link #restockLost}).</li>
     *   <li>Written off: only a carton in stock. A customer's hold is never cancelled by a correction: a carton held
     *       for an order is refused ({@code STOCK_PACKAGE_HELD}) - released or reallocated first - with one exception,
     *       {@code giveUpHoldsBy}: a count's shortage (20 §4.2 step 8, 07 §4.4). The carton is not there, so what it
     *       held for an order is a shortage: the hold is given up ({@code SHORT_PICKED}, as a reallocation does) and
     *       announced ({@code StockHeldCartonWrittenOff}) for fulfillment to turn the order's pick line SHORT and hold
     *       the order, after the commit. The carton is then LOST when {@code missingWhenEmpty} (a count did not find
     *       it, or it was written off as lost), CONSUMED otherwise.</li>
     * </ul>
     *
     * <p>The adjustment names a direction and no quantity (ADR-0011): the units moved are the carton's standard count.
     * The carton is saved here; the caller publishes its events and writes the ledger line from the change.</p>
     */
    public CartonChange adjust(StockPackageId id, AdjustmentDirection direction, boolean missingWhenEmpty,
                               Instant now) {
        return adjust(id, direction, missingWhenEmpty, null, now);
    }

    /**
     * As {@link #adjust(StockPackageId, AdjustmentDirection, boolean, Instant)}; with {@code giveUpHoldsBy} (who
     * decided the shortage), a write-off gives up the hold on the carton instead of refusing it.
     */
    public CartonChange adjust(StockPackageId id, AdjustmentDirection direction, boolean missingWhenEmpty,
                               UUID giveUpHoldsBy, Instant now) {
        Objects.requireNonNull(direction, "direction");
        StockPackage carton = lock(id);
        requireCompanys(carton);
        CartonChange change = direction == AdjustmentDirection.RESTOCK
                ? restockLost(carton)
                : writeOff(carton, missingWhenEmpty, giveUpHoldsBy, now);
        stockPackages.save(carton);
        return change;
    }

    /**
     * A picked carton back into stock whole, at {@code to} (kltn-docs 07 BR-05, 20 §4.6 steps 5-6): its order was
     * cancelled after the pick (AVAILABLE), or the check station removed it as damaged ({@code quarantine}: QUARANTINE,
     * team plan 4C-6, kltn-docs 08 §4.2 step 4). CONSUMED → IN_STOCK ({@link StockPackage#restock}): the same carton,
     * LPN, lot and FIFO date it was. The status is named here because a carton out of stock has none of its own
     * (ADR-0011): a pick only ever takes AVAILABLE stock, so that is what a put-back gives back. Call under the SKU
     * lock; the carton is locked and saved here, before the caller's ledger line names it at its new place
     * ({@code check_stock_movement_package} reads it on insert).
     *
     * @throws BusinessException {@code INVALID_STOCK_PACKAGE_TRANSITION} when it is not CONSUMED
     */
    public StockPackage restock(StockPackageId id, LocationId to, boolean quarantine) {
        StockPackage carton = lock(id);
        carton.restock(to, quarantine ? StockStatus.QUARANTINE : StockStatus.AVAILABLE);
        return stockPackages.saveNow(carton);
    }

    /**
     * A carton that left stock whole comes back on a return receipt (team plan 4D-2; kltn-docs 03 §4.2 step 12,
     * BR-03, BR-04, BR-14): CONSUMED → IN_STOCK at the QUARANTINE area {@code to}, sealed, with its LPN and in its own
     * lot, QUARANTINE - out of ATP until a warehouse manager releases it or writes it off (20 §4.8): nobody has looked
     * at the goods yet. Call under the SKU lock; the carton is locked and saved here.
     *
     * @throws BusinessException {@code INVALID_STOCK_PACKAGE_TRANSITION} when it is not CONSUMED - in stock already,
     *         never received, lost or voided
     */
    public StockPackage returnIntoQuarantine(StockPackageId id, LocationId to) {
        StockPackage carton = lock(id);
        if (carton.status() != StockPackageStatus.CONSUMED) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION, ("%s is %s; only a carton that "
                    + "left stock comes back on a return").formatted(carton.lpn(), carton.status()), List.of(),
                    Map.of("lpn", carton.lpn()));
        }
        carton.restock(to, StockStatus.QUARANTINE);
        return stockPackages.saveNow(carton);
    }

    /**
     * WF-05 Q-4 (H1): a carton back on a return whose refund is undecided is not the company's to write off, count short
     * or release yet - the refund may still leave it the customer's - and one held for the customer is not in the books
     * at all. Every adjustment of a carton (requested, approved, a count's variance) asks here.
     *
     * @throws BusinessException {@code STOCK_PACKAGE_DECISION_PENDING}, naming the carton's {@code returnOwnership}
     */
    void requireCompanys(StockPackage carton) {
        ownership.owned(carton.id()).filter(owned -> owned.ownership() == ReturnedCartonOwnership.Ownership.PENDING
                || owned.ownership().customers()).ifPresent(owned -> {
                    // The details name the return (audit r3 RAD-4): the screen says "waiting for the refund of RMA-..."
                    // rather than another decision under way.
                    throw new BusinessException(ErrorCode.STOCK_PACKAGE_DECISION_PENDING, ("%s came back on a return and "
                            + "is %s: it is the company's to decide on only once the return's refund made it so")
                            .formatted(carton.lpn(), owned.ownership()), List.of(), Map.of("lpn", carton.lpn(),
                            "returnOwnership", owned.ownership().name(), "returnRequestId", owned.returnRequestId()));
                });
    }

    /**
     * A carton a return receipt put into quarantine leaves the books again (WF-05 Q-4): its return refunded nothing, so
     * the goods were never agreed back and are the customer's. IN_STOCK → CONSUMED where it stands
     * ({@link StockPackage#consume}) - the whole carton, QUARANTINE, held for nobody. The caller writes the reversing
     * ledger line from the change returned. Call under the SKU lock; the carton is locked and saved here.
     *
     * @throws BusinessException {@code INVALID_STOCK_PACKAGE_TRANSITION} when it is not in stock;
     *         {@code STOCK_PACKAGE_TAKEN_WHOLE} when it is not in quarantine, or is held for an order
     */
    CartonChange reverseReturn(StockPackageId id, Instant now) {
        StockPackage carton = lock(id);
        if (carton.status() != StockPackageStatus.IN_STOCK) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION, ("%s is %s; only a carton in stock "
                    + "leaves it again").formatted(carton.lpn(), carton.status()), List.of(),
                    Map.of("lpn", carton.lpn()));
        }
        if (carton.stockStatus() != StockStatus.QUARANTINE || carton.isHeld()) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE, ("%s cannot leave whole: it is %s, not a "
                    + "carton in quarantine held for nobody").formatted(carton.lpn(), carton.stockStatus()), List.of(),
                    Map.of("lpn", carton.lpn()));
        }
        CartonChange change = new CartonChange(carton, -carton.nominalQty(), carton.stockStatus(), carton.location());
        carton.consume(now);
        stockPackages.save(carton);
        return change;
    }

    /**
     * A LOST carton found again, whole. It comes back as sellable stock (AVAILABLE), whatever status it had when it
     * went missing (review 4C-3, L4; a carton out of stock keeps none, ADR-0011): a carton found is a carton on the
     * shelf, and kept INBOUND, BLOCKED or DAMAGED it would sit silently out of ATP with nothing to move it on. The one
     * exception is a carton recorded in a QUARANTINE area: it comes back QUARANTINE, out of ATP until a manager
     * releases it (20 §4.8, BR-21) - a carton is never sold out of quarantine by being found.
     */
    private CartonChange restockLost(StockPackage carton) {
        if (carton.status() != StockPackageStatus.LOST) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION,
                    "%s is %s; only a LOST carton is restocked".formatted(carton.lpn(), carton.status()));
        }
        carton.found(locations.isQuarantineArea(carton.location()) ? StockStatus.QUARANTINE : StockStatus.AVAILABLE);
        return new CartonChange(carton, carton.nominalQty(), carton.stockStatus(), carton.location());
    }

    private static CartonChange writeOff(StockPackage carton, boolean missingWhenEmpty, UUID giveUpHoldsBy,
                                         Instant now) {
        if (carton.status() != StockPackageStatus.IN_STOCK) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION,
                    "%s is %s; there is nothing in it to write off".formatted(carton.lpn(), carton.status()));
        }
        if (carton.isHeld() && giveUpHoldsBy == null) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_HELD,
                    "%s is held for an order; release or reallocate it before writing it off".formatted(carton.lpn()),
                    List.of(), Map.of("lpn", carton.lpn()));
        }
        // What the ledger line says of the carton is read before it leaves stock: afterwards it has no stock status.
        CartonChange change = new CartonChange(carton, -carton.nominalQty(), carton.stockStatus(), carton.location());
        if (giveUpHoldsBy != null) {
            carton.giveUpHoldsOfWrittenOffCarton(giveUpHoldsBy, now);
        }
        if (missingWhenEmpty) {
            carton.lose(now);
        } else {
            carton.consume(now);
        }
        if (giveUpHoldsBy != null) {
            // A count's shortage (WF4-COUNT-EDGE a): the warehouse has a carton fewer; holds by SKU are recomputed.
            carton.announceCountWriteOff(giveUpHoldsBy);
        }
        return change;
    }

    private StockPackage lock(StockPackageId id) {
        return stockPackages.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalStateException("No stock package " + id.value()));
    }

    /**
     * One carton changed whole: {@code delta} units in (positive, its standard count restocked) or out (negative,
     * written off or handed back). {@code status} and {@code location} are the stock status and the place the ledger
     * line names: those the carton had before it left stock, or has now that it is back - a carton out of stock has no
     * stock status to ask afterwards.
     */
    record CartonChange(StockPackage carton, int delta, StockStatus status, LocationId location) {
    }
}
