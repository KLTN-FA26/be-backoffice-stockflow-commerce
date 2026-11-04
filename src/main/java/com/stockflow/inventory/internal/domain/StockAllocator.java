package com.stockflow.inventory.internal.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Domain service: decides <b>which</b> stock items a quantity is drawn from, and in what order.
 *
 * <p>A domain service rather than a method on {@link StockItem} because the decision spans several
 * aggregates — no single stock item can see the others. It is deliberately not in the application
 * layer either: FEFO is a business rule, not orchestration, and the business would want it tested
 * on its own.</p>
 *
 * <p><b>FEFO — first expired, first out.</b> Stock with a shelf life (inks, adhesives, coated
 * paperboard blanks) must ship the oldest usable lot first, otherwise stock ages out on the shelf and
 * is written off. Ties break on the smaller remainder, which drains partial lots and frees
 * locations instead of leaving a scatter of two-unit remnants across the warehouse.</p>
 *
 * <p><b>Cartons (SCRUM-473).</b> Expiry, or receipt under FIFO, always decides first. Only inside the group where the
 * need runs out - stock equal on those - does the carton a row is in matter: whole cartons for the round part, the
 * remainder from opened cartons and loose units, a sealed carton broken last. Earlier groups are taken entirely, so
 * there is nothing for the rule to choose there; letting it reach across groups would ship a later lot to keep a
 * carton sealed, which is exactly what FEFO forbids.</p>
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
     *                       the carton as it stands. A carton spread over several rows has no whole row.
     */
    public record Candidate(
            StockItemId stockItemId,
            LocationId location,
            String lotNumber,
            LocalDate expiryDate,
            StockStatus status,
            Quantity available,
            java.time.Instant receivedAt,
            java.util.UUID stockPackageId,
            boolean wholeCarton
    ) {
        public Candidate(StockItemId id, LocationId location, String lotNumber, LocalDate expiryDate,
                         StockStatus status, Quantity available) {
            this(id, location, lotNumber, expiryDate, status, available, null);
        }

        /** Loose stock. */
        public Candidate(StockItemId id, LocationId location, String lotNumber, LocalDate expiryDate,
                         StockStatus status, Quantity available, java.time.Instant receivedAt) {
            this(id, location, lotNumber, expiryDate, status, available, receivedAt, null, false);
        }

        public Candidate {
            java.util.Objects.requireNonNull(stockItemId, "stockItemId");
            java.util.Objects.requireNonNull(location, "location");
            java.util.Objects.requireNonNull(status, "status");
            java.util.Objects.requireNonNull(available, "available");
            if (wholeCarton && stockPackageId == null) {
                throw new IllegalArgumentException("Only a row in a carton can be a whole carton");
            }
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
                    .thenComparing(candidate -> candidate.location().code())
                    .thenComparing(candidate -> candidate.stockItemId().value());

    public static Comparator<Candidate> comparator(com.stockflow.inventory.api.RemovalStrategy strategy) {
        Comparator<Candidate> receipt = Comparator.comparing(Candidate::receivedAt,
                Comparator.nullsLast(Comparator.naturalOrder()));
        Comparator<Candidate> primary = strategy == com.stockflow.inventory.api.RemovalStrategy.FIFO ? receipt
                : Comparator.comparing(Candidate::expiryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(receipt);
        return primary.thenComparing(c -> c.available().value()).thenComparing(c -> c.location().code())
                .thenComparing(c -> c.stockItemId().value());
    }

    /**
     * Split {@code required} units across the candidates in FEFO order.
     *
     * @return one plan line per stock item to draw from, in the order they should be reserved
     * @throws InsufficientStockException if the candidates together cannot cover {@code required}
     */
    public static List<AllocationLine> plan(String sku, List<Candidate> candidates, Quantity required) {
        return allocate(sku, candidates, required, FEFO, Candidate::expiryDate);
    }

    public static List<AllocationLine> plan(String sku, List<Candidate> candidates, Quantity required,
            com.stockflow.inventory.api.RemovalStrategy strategy, LocalDate today) {
        List<Candidate> usable = candidates.stream()
                .filter(c -> c.expiryDate() == null || !c.expiryDate().isBefore(today)).toList();
        if (strategy == com.stockflow.inventory.api.RemovalStrategy.FIFO
                && usable.stream().anyMatch(c -> c.status().isReservable() && !c.available().isZero() && c.receivedAt() == null)) {
            throw new com.stockflow.common.error.BusinessException(com.stockflow.common.error.ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT);
        }
        Function<Candidate, Object> group = strategy == com.stockflow.inventory.api.RemovalStrategy.FIFO
                ? Candidate::receivedAt
                : c -> java.util.Arrays.asList(c.expiryDate(), c.receivedAt());
        return allocate(sku, usable, required, comparator(strategy), group);
    }

    /**
     * Walks the groups of equal {@code group} key in {@code comparator} order. Every group before the one where the
     * need runs out is taken whole, so the order inside it changes nothing; the last one is drawn in carton order.
     */
    private static List<AllocationLine> allocate(String sku, List<Candidate> candidates, Quantity required,
                                                Comparator<Candidate> comparator, Function<Candidate, Object> group) {
        List<Candidate> sellable = candidates.stream()
                .filter(candidate -> candidate.status().isReservable())
                .filter(candidate -> !candidate.available().isZero())
                .sorted(comparator)
                .toList();

        int totalAvailable = sellable.stream().mapToInt(c -> c.available().value()).sum();
        if (totalAvailable < required.value()) {
            throw new InsufficientStockException(sku, required.value(), totalAvailable);
        }

        List<AllocationLine> plan = new ArrayList<>();
        int outstanding = required.value();
        int start = 0;
        while (outstanding > 0) {
            Object key = group.apply(sellable.get(start));
            int end = start;
            while (end < sellable.size() && Objects.equals(group.apply(sellable.get(end)), key)) {
                end++;
            }
            List<Candidate> members = sellable.subList(start, end);
            int groupTotal = members.stream().mapToInt(c -> c.available().value()).sum();
            outstanding = take(groupTotal <= outstanding ? members : inCartonOrder(members, outstanding),
                    outstanding, plan);
            start = end;
        }
        return List.copyOf(plan);
    }

    /**
     * The last group's order (SCRUM-473): whole cartons, largest first, while each fits in what is still needed; then
     * opened cartons and loose units, smallest first, so remnants are drained; then the whole cartons left, smallest
     * first - the first of them is broken for the rest. A carton another order holds part of counts as opened: that
     * order's pick opens it anyway. This never comes up short: every whole carton left after the first step is larger
     * than what is still needed.
     */
    private static List<Candidate> inCartonOrder(List<Candidate> members, int needed) {
        Comparator<Candidate> place = Comparator.<Candidate, String>comparing(c -> c.location().code())
                .thenComparing(c -> c.stockItemId().value());
        Comparator<Candidate> smallest = Comparator.<Candidate>comparingInt(c -> c.available().value()).thenComparing(place);
        List<Candidate> whole = members.stream().filter(Candidate::wholeCarton)
                .sorted(Comparator.<Candidate>comparingInt(c -> c.available().value()).reversed().thenComparing(place))
                .toList();
        List<Candidate> ordered = new ArrayList<>();
        List<Candidate> broken = new ArrayList<>();
        int left = needed;
        for (Candidate carton : whole) {
            if (carton.available().value() <= left) {
                ordered.add(carton);
                left -= carton.available().value();
            } else {
                broken.add(carton);
            }
        }
        members.stream().filter(c -> c.stockPackageId() != null && !c.wholeCarton()).sorted(smallest).forEach(ordered::add);
        members.stream().filter(c -> c.stockPackageId() == null).sorted(smallest).forEach(ordered::add);
        broken.stream().sorted(smallest).forEach(ordered::add);
        return ordered;
    }

    /** Draws on {@code from} in order until nothing is outstanding; returns what still is. */
    private static int take(List<Candidate> from, int outstanding, List<AllocationLine> plan) {
        for (Candidate candidate : from) {
            if (outstanding == 0) {
                break;
            }
            int take = Math.min(outstanding, candidate.available().value());
            plan.add(new AllocationLine(candidate, Quantity.of(take)));
            outstanding -= take;
        }
        return outstanding;
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
