package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.RemovalStrategy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain service: decides <b>which cartons</b> an order line is given, and in what order.
 *
 * <p>A domain service rather than a method on {@link StockItem} because the decision spans several
 * aggregates — no single stock item can see the others. It is deliberately not in the application
 * layer either: the order of removal is a business rule, not orchestration, and the business would want it tested
 * on its own.</p>
 *
 * <p><b>Whole cartons only (kltn-docs 07 BR-03, 20 §5.6; team plan 4C-2).</b> An order line is given whole LPN
 * cartons of the SKU, never part of one: a carton is not opened, and a picker takes it by its LPN. So only a
 * candidate that is its carton's entire, sealed content with nothing of it held ({@link Candidate#wholeCarton}) is
 * drawn on; loose units and a carton another order already holds part of are left alone. Every carton of a SKU holds
 * the SKU's standard count (01 BR-11, 03 BR-13), so N cartons in any order are the line's quantity.</p>
 *
 * <p><b>FIFO, k &lt; N.</b> Cartons go in the SKU's removal order - received first, first out (finished goods have no
 * expiry, 07 BR-03.3), or earliest expiry first for a SKU whose policy says so - and the plan takes them while each fits
 * in what is still needed. When the warehouse has only k of the N cartons, the plan lists those k and reports the
 * rest short ({@link Plan#shortUnits}); it holds nothing itself. No caller holds k &lt; N yet: a release, a
 * reallocation and a damage replacement all refuse or drop a short plan until the order can wait for the rest
 * ({@code AWAITING_STOCK}, team plan 4A-6, which sends the shortfall to a Production Order, 07 BR-03.4). A carton
 * larger than what is still needed is never broken to fill the gap: it is skipped, and the gap stays short.</p>
 *
 * <p><b>It plans over {@link Candidate} projections, not over loaded aggregates.</b> That is a
 * correctness requirement, not a performance one. If the planner read whole {@code StockItem}
 * aggregates, those entities would be sitting in the persistence context, and the subsequent
 * {@code SELECT ... FOR UPDATE} would return the cached copies rather than the freshly locked
 * rows — so the availability re-check under the lock would run against pre-lock data and could
 * still oversell. Planning over a projection keeps the locked read the first read.</p>
 *
 * <p>Stateless and free of Spring annotations: it is constructed where it is used, and tested by
 * calling it.</p>
 */
public final class StockAllocator {

    private StockAllocator() {
    }

    /**
     * What the planner needs to know about one stock item, and nothing more.
     *
     * @param available      available to promise at the moment of reading — already stale by the time
     *                       the lock is taken, which is why {@link StockItem#reserve} checks again
     * @param stockPackageId the carton the row is in; null for loose stock
     * @param wholeCarton    the row is its carton's entire, sealed content and nothing of it is held: taking it takes
     *                       the carton as it stands. Only such a row is ever planned
     */
    public record Candidate(
            StockItemId stockItemId,
            LocationId location,
            String lotNumber,
            LocalDate expiryDate,
            StockStatus status,
            Quantity available,
            Instant receivedAt,
            UUID stockPackageId,
            boolean wholeCarton
    ) {
        /** Loose stock, for ordering a list by the removal strategy; never planned. */
        public Candidate(StockItemId id, LocationId location, String lotNumber, LocalDate expiryDate,
                         StockStatus status, Quantity available, Instant receivedAt) {
            this(id, location, lotNumber, expiryDate, status, available, receivedAt, null, false);
        }

        public Candidate {
            Objects.requireNonNull(stockItemId, "stockItemId");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(available, "available");
            if (wholeCarton && stockPackageId == null) {
                throw new IllegalArgumentException("Only a row in a carton can be a whole carton");
            }
        }
    }

    /**
     * The removal order, as a comparator: FIFO by receipt time, or for a FEFO SKU the earliest expiry first and then
     * receipt time. Unknown dates sort last. The final tie-breaks on location code and id make the plan deterministic:
     * without them, two runs over the same data could produce different orders, and a test asserting on the plan
     * would be flaky.
     */
    public static Comparator<Candidate> comparator(RemovalStrategy strategy) {
        Comparator<Candidate> receipt = Comparator.comparing(Candidate::receivedAt,
                Comparator.nullsLast(Comparator.naturalOrder()));
        Comparator<Candidate> primary = strategy == RemovalStrategy.FIFO ? receipt
                : Comparator.comparing(Candidate::expiryDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
                    .thenComparing(receipt);
        return primary.thenComparing(c -> c.location().code()).thenComparing(c -> c.stockItemId().value());
    }

    /**
     * Whole cartons for {@code required} units, in the SKU's removal order. Never throws for a shortage: a plan of
     * k &lt; N cartons says so in {@link Plan#shortUnits}.
     *
     * @throws BusinessException {@code INVENTORY_POLICY_STOCK_CONFLICT} when a FIFO SKU has a sellable carton with no
     *         receipt time: FIFO cannot place it, and guessing would ship the wrong carton first
     */
    public static Plan plan(String sku, List<Candidate> candidates, Quantity required, RemovalStrategy strategy,
                            LocalDate today) {
        List<Candidate> cartons = candidates.stream()
                .filter(Candidate::wholeCarton)
                .filter(c -> c.status().isReservable())
                .filter(c -> !c.available().isZero())
                .filter(c -> c.expiryDate() == null || !c.expiryDate().isBefore(today))
                .toList();
        if (strategy == RemovalStrategy.FIFO && cartons.stream().anyMatch(c -> c.receivedAt() == null)) {
            throw new BusinessException(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT);
        }
        List<AllocationLine> lines = new ArrayList<>();
        int outstanding = required.value();
        for (Candidate carton : cartons.stream().sorted(comparator(strategy)).toList()) {
            if (outstanding == 0) {
                break;
            }
            if (carton.available().value() <= outstanding) {
                lines.add(new AllocationLine(carton, carton.available()));
                outstanding -= carton.available().value();
            }
        }
        return new Plan(sku, required.value(), lines);
    }

    /**
     * The cartons chosen for a line, in pick order, and what they leave short.
     *
     * @param required units the line needs
     */
    public record Plan(String sku, int required, List<AllocationLine> lines) {

        public Plan {
            Objects.requireNonNull(sku, "sku");
            lines = List.copyOf(lines);
        }

        /** Units the chosen cartons hold. */
        public int planned() {
            return lines.stream().mapToInt(line -> line.quantity().value()).sum();
        }

        /** Units no whole carton covers: N − k cartons' worth (07 BR-03.4). */
        public int shortUnits() {
            return required - planned();
        }

        public boolean isShort() {
            return shortUnits() > 0;
        }

        /**
         * The lines, when they cover everything: for a caller that cannot use k &lt; N cartons.
         *
         * @throws InsufficientStockException naming what the whole cartons cover
         */
        public List<AllocationLine> complete() {
            if (isShort()) {
                throw new InsufficientStockException(sku, required, planned());
            }
            return lines;
        }
    }

    /** Take this whole carton, {@code quantity} being everything it holds. */
    public record AllocationLine(Candidate candidate, Quantity quantity) {

        public AllocationLine {
            Objects.requireNonNull(candidate, "candidate");
            if (quantity == null || quantity.isZero()) {
                throw new IllegalArgumentException("An allocation line of zero units is meaningless");
            }
        }

        public StockItemId stockItemId() {
            return candidate.stockItemId();
        }
    }
}
