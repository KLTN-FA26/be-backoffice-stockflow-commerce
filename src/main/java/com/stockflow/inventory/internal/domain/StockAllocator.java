package com.stockflow.inventory.internal.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Domain service: decides <b>which</b> stock items a quantity is drawn from, and in what order.
 *
 * <p>A domain service rather than a method on {@link StockItem} because the decision spans several
 * aggregates — no single stock item can see the others. It is deliberately not in the application
 * layer either: FEFO is a business rule, not orchestration, and the business would want it tested
 * on its own.</p>
 *
 * <p><b>FEFO — first expired, first out.</b> Furniture components with a shelf life (adhesives,
 * finishes, foam) must ship the oldest usable lot first, otherwise stock ages out on the shelf and
 * is written off. Ties break on the smaller remainder, which drains partial lots and frees
 * locations instead of leaving a scatter of two-unit remnants across the warehouse.</p>
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
     * @param available available to promise at the moment of reading — already stale by the time
     *                  the lock is taken, which is why {@link StockItem#reserve} checks again
     */
    public record Candidate(
            StockItemId stockItemId,
            LocationId location,
            String lotNumber,
            LocalDate expiryDate,
            StockStatus status,
            Quantity available
    ) {

        public Candidate {
            java.util.Objects.requireNonNull(stockItemId, "stockItemId");
            java.util.Objects.requireNonNull(location, "location");
            java.util.Objects.requireNonNull(status, "status");
            java.util.Objects.requireNonNull(available, "available");
        }
    }

    /**
     * The picking policy, as a comparator.
     *
     * <p>Items with no expiry date sort last, so dated stock always goes first. The final tie-break
     * on location code is there to make the plan deterministic: without it, two runs over the same
     * data could produce different orders, and a test asserting on the plan would be flaky.</p>
     */
    public static final Comparator<Candidate> FEFO =
            Comparator.<Candidate, LocalDate>comparing(
                            Candidate::expiryDate,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(candidate -> candidate.available().value())
                    .thenComparing(candidate -> candidate.location().code());

    /**
     * Split {@code required} units across the candidates in FEFO order.
     *
     * @return one plan line per stock item to draw from, in the order they should be reserved
     * @throws InsufficientStockException if the candidates together cannot cover {@code required}
     */
    public static List<AllocationLine> plan(String sku, List<Candidate> candidates, Quantity required) {
        List<Candidate> sellable = candidates.stream()
                .filter(candidate -> candidate.status().isReservable())
                .filter(candidate -> !candidate.available().isZero())
                .sorted(FEFO)
                .toList();

        int totalAvailable = sellable.stream().mapToInt(c -> c.available().value()).sum();
        if (totalAvailable < required.value()) {
            throw new InsufficientStockException(sku, required.value(), totalAvailable);
        }

        List<AllocationLine> plan = new ArrayList<>();
        int outstanding = required.value();
        for (Candidate candidate : sellable) {
            if (outstanding == 0) {
                break;
            }
            int take = Math.min(outstanding, candidate.available().value());
            plan.add(new AllocationLine(candidate, Quantity.of(take)));
            outstanding -= take;
        }
        return List.copyOf(plan);
    }

    /** Take {@code quantity} units from this specific stock item. */
    public record AllocationLine(Candidate candidate, Quantity quantity) {

        public AllocationLine {
            java.util.Objects.requireNonNull(candidate, "candidate");
            if (quantity == null || quantity.isZero()) {
                throw new IllegalArgumentException("An allocation line of zero units is meaningless");
            }
        }

        public StockItemId stockItemId() {
            return candidate.stockItemId();
        }
    }
}
