package com.stockflow.order.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/**
 * One warehouse's line of the capacity board (kltn-docs 06 §4.6, BR-20, BR-21; 17 BR-25; C40-C43), worked out when it is
 * read and never stored. Every column comes from six inputs:
 *
 * <ul>
 *   <li><b>Capacity</b> = the computed volume of the warehouse's usable bins x its utilization factor (C43; areas have
 *       no capacity, SCRUM-666).</li>
 *   <li><b>Occupied</b> = the cartons standing anywhere in it, areas included (C40).</li>
 *   <li><b>Incoming</b> = what production orders sent to producers still have to deliver there (SENT, ACCEPTED,
 *       SAMPLE_PENDING, IN_PRODUCTION, PARTIALLY_RECEIVED - {@link ProductionOrder#sentAndOpen()}).</li>
 *   <li><b>Projected free</b> = capacity - occupied - incoming, negative when the warehouse is short of room.</li>
 *   <li><b>Pending</b> = production orders not sent yet (DRAFT, PENDING_APPROVAL): shown apart and taken out only of
 *       <b>free if all approved</b> = projected free - pending, since they may never be sent.</li>
 *   <li><b>Outgoing</b> = what is held for orders not delivered yet, for reference only: never added to free room.</li>
 *   <li>The two percentages are of capacity; with no capacity there is nothing to be a share of, so they are null.</li>
 * </ul>
 *
 * <p>Volumes are m³ to three decimals (a litre): each input is rounded once, here, so every column adds up exactly to
 * the ones it is made of. Percentages have two decimals.</p>
 *
 * @param binVolumeM3       Σ the computed volume of the usable bins, before the factor
 * @param utilizationFactor (0, 1]
 */
public record CapacityBoard(BigDecimal binVolumeM3, BigDecimal utilizationFactor, BigDecimal occupiedM3,
                            BigDecimal incomingM3, BigDecimal pendingM3, BigDecimal outgoingM3) {

    static final int SCALE = 3;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public CapacityBoard {
        Objects.requireNonNull(utilizationFactor, "utilizationFactor");
        if (utilizationFactor.signum() <= 0 || utilizationFactor.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("utilizationFactor is in (0, 1]: " + utilizationFactor);
        }
        binVolumeM3 = m3(binVolumeM3);
        occupiedM3 = m3(occupiedM3);
        incomingM3 = m3(incomingM3);
        pendingM3 = m3(pendingM3);
        outgoingM3 = m3(outgoingM3);
    }

    public BigDecimal capacityM3() {
        return capacity(binVolumeM3, utilizationFactor);
    }

    public BigDecimal projectedFreeM3() {
        return capacityM3().subtract(occupiedM3).subtract(incomingM3);
    }

    public BigDecimal freeIfAllApprovedM3() {
        return projectedFreeM3().subtract(pendingM3);
    }

    /** Null when the warehouse has no capacity. */
    public BigDecimal occupiedPercent() {
        return percent(occupiedM3, capacityM3());
    }

    /** May be negative; null when the warehouse has no capacity. */
    public BigDecimal projectedFreePercent() {
        return percent(projectedFreeM3(), capacityM3());
    }

    /**
     * BR-25 / 06 §4.6 step 5: does {@code neededM3} fit? Compared with the board as it would be without what is being
     * judged - its own cartons already counted as incoming ({@code ownIncomingM3}: a production order sent) or pending
     * ({@code ownPendingM3}: a draft, one awaiting approval) - so nothing is counted twice: "không tính chính đơn đang
     * lập". Only a warning, never a refusal (BR-21).
     *
     * @param neededM3 null when a carton's size is unknown: nothing can be judged, no warning; zero needs no room,
     *                 however short the warehouse is
     */
    public CapacityCheck check(BigDecimal neededM3, BigDecimal ownIncomingM3, BigDecimal ownPendingM3) {
        BigDecimal projected = projectedFreeM3().add(m3(ownIncomingM3));
        BigDecimal ifAllApproved = freeIfAllApprovedM3().add(m3(ownIncomingM3)).add(m3(ownPendingM3));
        if (neededM3 == null) {
            return new CapacityCheck(null, projected, ifAllApproved, false, false, BigDecimal.ZERO.setScale(SCALE));
        }
        BigDecimal needed = m3(neededM3);
        if (needed.signum() <= 0) {
            // Nothing to make room for - a warehouse holding every line, a production order received or closed - fits
            // even a warehouse already short.
            return new CapacityCheck(needed, projected, ifAllApproved, false, false, BigDecimal.ZERO.setScale(SCALE));
        }
        BigDecimal shortfall = needed.subtract(projected).max(BigDecimal.ZERO);
        return new CapacityCheck(needed, projected, ifAllApproved, needed.compareTo(projected) > 0,
                needed.compareTo(ifAllApproved) > 0, shortfall);
    }

    /**
     * 06 §4.6 step 8, C42: sending {@code sentM3} more to the warehouse - a production order approved, so sent - turns
     * its projected free room from zero or more to below zero. Only that crossing is told, never a warehouse already
     * short, so the manager hears once and not on every production order after it.
     *
     * @return how far below zero it goes, when it crosses; empty otherwise, and when {@code sentM3} is unknown
     */
    public Optional<BigDecimal> turnsNegativeWith(BigDecimal sentM3) {
        if (sentM3 == null) {
            return Optional.empty();
        }
        BigDecimal before = projectedFreeM3();
        BigDecimal after = before.subtract(m3(sentM3));
        return before.signum() >= 0 && after.signum() < 0 ? Optional.of(after.negate()) : Optional.empty();
    }

    /**
     * A row below the warehouse - a zone, a shelf, an area (06 §4.6 step 4): only capacity, occupied, free now
     * (capacity - occupied) and their percentages, because production orders and holds name a warehouse, not a shelf.
     *
     * @param binVolumeM3 null for an area, which has no capacity: then only occupied is known
     */
    public static Level level(BigDecimal binVolumeM3, BigDecimal utilizationFactor, BigDecimal occupiedM3) {
        BigDecimal occupied = m3(occupiedM3);
        if (binVolumeM3 == null) {
            return new Level(null, occupied, null, null, null);
        }
        BigDecimal capacity = capacity(m3(binVolumeM3), utilizationFactor);
        BigDecimal free = capacity.subtract(occupied);
        return new Level(capacity, occupied, free, percent(occupied, capacity), percent(free, capacity));
    }

    /** A row below the warehouse; capacity, free and the percentages are null for an area. */
    public record Level(BigDecimal capacityM3, BigDecimal occupiedM3, BigDecimal freeM3, BigDecimal occupiedPercent,
                        BigDecimal freePercent) {
    }

    /**
     * What {@link #check} found.
     *
     * @param projectedFreeM3      the room it was compared with: projected free without its own cartons
     * @param freeIfAllApprovedM3  the same once every other production order awaiting approval is sent
     * @param exceedsProjected     it needs more than {@code projectedFreeM3}: warn
     * @param exceedsIfAllApproved it needs more than {@code freeIfAllApprovedM3}: warn that other production orders
     *                             pending for the warehouse would leave too little
     * @param shortfallM3          how much it needs beyond {@code projectedFreeM3}; zero when it fits
     */
    public record CapacityCheck(BigDecimal neededM3, BigDecimal projectedFreeM3, BigDecimal freeIfAllApprovedM3,
                                boolean exceedsProjected, boolean exceedsIfAllApproved, BigDecimal shortfallM3) {
    }

    private static BigDecimal capacity(BigDecimal binVolume, BigDecimal factor) {
        return binVolume.multiply(factor).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return null;
        }
        return part.multiply(HUNDRED).divide(whole, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal m3(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(SCALE) : value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
