package com.stockflow.inventory.internal.domain;


import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Domain service: decides <b>which cartons</b> an order line is given, and in what order.
 *
 * <p>A domain service rather than a method on {@link StockPackage} because the decision spans several
 * aggregates — no single carton can see the others. It is deliberately not in the application
 * layer either: the order of removal is a business rule, not orchestration, and the business would want it tested
 * on its own.</p>
 *
 * <p><b>Whole cartons only (kltn-docs 07 BR-03, 20 §5.6; team plan 4C-2).</b> An order line is given whole LPN
 * cartons of the SKU, never part of one: a carton is not opened, and a picker takes it by its LPN. A candidate is a
 * free carton - AVAILABLE, with no live allocation; a carton another order holds is not offered at all.</p>
 *
 * <p><b>Cartons of the hold's count only (ADR-0011, owner decision 2026-11-27).</b> A hold says which cartons it wants
 * by their count - the {@code units_per_carton} its order line was quoted in - and the plan is N cartons of exactly
 * that count. A carton of another count is not a candidate at all, larger or smaller: an order quoted in cartons of
 * 1000 takes cartons of 1000 only, even while cartons of 500 of the same SKU stand free. (Until V20261231000500 the
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
 * correctness requirement, not a performance one. If the planner read whole {@code StockPackage}
 * aggregates, those entities would be sitting in the persistence context, and the subsequent
 * {@code SELECT ... FOR UPDATE} would return the cached copies rather than the freshly locked
 * rows — so the re-check under the lock would run against pre-lock data and could
 * still give one carton to two orders. Planning over a projection keeps the locked read the first read.</p>
 *
 * <p>Stateless and free of Spring annotations: it is constructed where it is used, and tested by
 * calling it.</p>
 */
public final class StockAllocator {

    private StockAllocator() {
    }

    /**
     * What the planner needs to know about one free carton, and nothing more.
     *
     * <p>Free at the moment of reading — already stale by the time the lock is taken, which is why
     * {@link StockPackage#allocate} checks again.</p>
     *
     * @param unitsPerCarton the carton's count ({@code nominal_qty}): a hold takes cartons of its own count only
     * @param receivedAt     the FIFO date; null when it is not known, which sorts last
     */
    public record Candidate(
            StockPackageId stockPackageId,
            LocationId location,
            String lotNumber,
            StockStatus status,
            int unitsPerCarton,
            Instant receivedAt
    ) {
        public Candidate {
            Objects.requireNonNull(stockPackageId, "stockPackageId");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(status, "status");
            if (unitsPerCarton < 1) {
                throw new IllegalArgumentException("A carton holds at least one unit, not " + unitsPerCarton);
            }
        }

        /** Units this carton would give: its count while it is sellable. */
        public Quantity available() {
            return status.isReservable() ? Quantity.of(unitsPerCarton) : Quantity.ZERO;
        }
    }

    /**
     * The removal order, as a comparator: FIFO by receipt time. A carton with no receipt time (stock that predates
     * timed receipts; ADR-0011 keeps the date unknown rather than invent one) sorts after every timed one. The final
     * tie-breaks on location code and id make the plan deterministic: without them, two runs over the same data could
     * produce different orders, and a test asserting on the plan would be flaky.
     */
    public static Comparator<Candidate> comparator() {
        return Comparator.comparing(Candidate::receivedAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
                .thenComparing(c -> c.location().code())
                .thenComparing(c -> c.stockPackageId().value());
    }

    /** Whether {@code candidate} is a sellable carton of {@code unitsPerCarton}: the only stock a hold of that count takes. */
    public static boolean isCartonOf(Candidate candidate, int unitsPerCarton) {
        return candidate.status().isReservable() && candidate.unitsPerCarton() == unitsPerCarton;
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
        }

        /** Everything the carton holds: its count. */
        public Quantity quantity() {
            return candidate.available();
        }

        public StockPackageId stockPackageId() {
            return candidate.stockPackageId();
        }
    }
}
