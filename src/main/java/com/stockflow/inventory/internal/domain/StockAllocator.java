package com.stockflow.inventory.internal.domain;


import java.time.Instant;
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
 * drawn on; loose units and a carton another order already holds part of are left alone.</p>
 *
 * <p><b>Cartons of the hold's count only (ADR-0011, owner decision 2026-11-27).</b> A hold says which cartons it wants
 * by their count - the {@code units_per_carton} its order line was quoted in - and the plan is N cartons of exactly
 * that count. A carton of another count is not a candidate at all, larger or smaller: an order quoted in cartons of
 * 1000 takes cartons of 1000 only, even while cartons of 500 of the same SKU stand free. (Until V20261230000500 the
 * plan counted units and took any carton that still fitted in what was needed, skipping a larger one.)</p>
 *
 * <p><b>FIFO, k &lt; N.</b> Cartons go received first, first out, for every SKU (kltn-docs 01 BR-17, 07 BR-03.3): cups
 * and packaging have no expiry, so there is no earliest-expiry order to prefer and no expired carton to skip, and the
 * removal order is not configurable (team plan 5-1). When the warehouse has only k of the N cartons, the plan lists
 * those k and reports the rest short ({@link Plan#shortCartons}); it holds nothing itself. No caller holds k &lt; N
 * yet: a release and a reallocation both refuse or drop a short plan until the order can wait for the rest
 * ({@code AWAITING_STOCK}, team plan 4A-6, which sends the shortfall to a Production Order, 07 BR-03.4).</p>
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
     *                       the carton as it stands, and {@code available} is then the carton's count. Only such a row
     *                       is ever planned
     */
    public record Candidate(
            StockItemId stockItemId,
            LocationId location,
            String lotNumber,
            StockStatus status,
            Quantity available,
            Instant receivedAt,
            UUID stockPackageId,
            boolean wholeCarton
    ) {
        /** Loose stock, for ordering a list in removal order; never planned. */
        public Candidate(StockItemId id, LocationId location, String lotNumber, StockStatus status,
                         Quantity available, Instant receivedAt) {
            this(id, location, lotNumber, status, available, receivedAt, null, false);
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
     * The removal order, as a comparator: FIFO by receipt time. A row with no receipt time (stock that predates timed
     * receipts) sorts after every timed one, as it did before FEFO went: no SKU's order changes under it. The final
     * tie-breaks on location code and id make the plan deterministic: without them, two runs over the same data could
     * produce different orders, and a test asserting on the plan would be flaky.
     */
    public static Comparator<Candidate> comparator() {
        return Comparator.comparing(Candidate::receivedAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
                .thenComparing(c -> c.location().code())
                .thenComparing(c -> c.stockItemId().value());
    }

    /** Whether {@code candidate} is a whole free carton of {@code unitsPerCarton}: the only stock a hold of that count takes. */
    public static boolean isCartonOf(Candidate candidate, int unitsPerCarton) {
        return candidate.wholeCarton() && candidate.status().isReservable()
                && candidate.available().value() == unitsPerCarton;
    }

    /**
     * {@code cartons} whole cartons of {@code unitsPerCarton}, FIFO. Never throws for a shortage: a plan of k &lt; N
     * cartons says so in {@link Plan#shortCartons}.
     */
    public static Plan plan(String sku, List<Candidate> candidates, int cartons, int unitsPerCarton) {
        if (cartons < 1 || unitsPerCarton < 1) {
            throw new IllegalArgumentException("A plan is for at least one carton of at least one unit, not %d of %d"
                    .formatted(cartons, unitsPerCarton));
        }
        List<AllocationLine> lines = candidates.stream()
                .filter(c -> isCartonOf(c, unitsPerCarton))
                .sorted(comparator())
                .limit(cartons)
                .map(AllocationLine::new)
                .toList();
        return new Plan(sku, cartons, unitsPerCarton, lines);
    }

    /**
     * The cartons chosen for a hold, in pick order, and what they leave short.
     *
     * @param cartons        cartons the hold needs
     * @param unitsPerCarton the count of every one of them
     */
    public record Plan(String sku, int cartons, int unitsPerCarton, List<AllocationLine> lines) {

        public Plan {
            Objects.requireNonNull(sku, "sku");
            lines = List.copyOf(lines);
        }

        /** Units the hold needs, for a message: the module's shortages speak units. */
        public int required() {
            return cartons * unitsPerCarton;
        }

        /** Units the chosen cartons hold. */
        public int planned() {
            return lines.size() * unitsPerCarton;
        }

        /** Cartons of the count the warehouse does not have: N − k (07 BR-03.4). */
        public int shortCartons() {
            return cartons - lines.size();
        }

        public boolean isShort() {
            return shortCartons() > 0;
        }

        /**
         * The lines, when they cover everything: for a caller that cannot use k &lt; N cartons.
         *
         * @throws InsufficientStockException naming what the whole cartons cover
         */
        public List<AllocationLine> complete() {
            if (isShort()) {
                throw new InsufficientStockException(sku, required(), planned());
            }
            return lines;
        }
    }

    /** Take this whole carton. */
    public record AllocationLine(Candidate candidate) {

        public AllocationLine {
            Objects.requireNonNull(candidate, "candidate");
            if (!candidate.wholeCarton()) {
                throw new IllegalArgumentException("Only a whole free carton is allocated");
            }
        }

        /** Everything the carton holds: its count. */
        public Quantity quantity() {
            return candidate.available();
        }

        public StockItemId stockItemId() {
            return candidate.stockItemId();
        }
    }
}
