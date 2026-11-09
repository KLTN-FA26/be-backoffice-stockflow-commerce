package com.stockflow.inventory.internal.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Chooses and orders the bins a received carton may be put away into (SCRUM-92; kltn-docs 05 §4.2, 06 §4.4).
 *
 * <p>Pure on purpose: the candidates come from the warehouse module and the occupancy from the stock ledger, and
 * this class knows neither, so the rule is one function of three plain inputs. {@link #fits} is the same
 * hard rule the ranking filters with, so a bin the clerk picks by hand is judged exactly as strictly as one we
 * suggest - a manual choice must not be a way round 06 BR-01 / 05 BR-03.</p>
 *
 * <p>The hard rule, in the order {@link #refusal} reports it: the carton fits the bin dimension by dimension, turned
 * on the horizontal if its item allows ({@link CartonFit}); the bin's free volume - its computed volume less the
 * volume of the cartons in it - takes the carton's; and the load stays within the bin's weight limit, every carton
 * weighed full (05 BR-03, decided 2026-11-05). Capacity is volume, never a count of units: a bin of large cartons
 * is full long before one of small ones.</p>
 *
 * <p>The order is the one decided on 2026-11-08 (05 §4.2 step 4, 06 §4.4 step 3), which replaced the 2026-10-28
 * order "same lot, same SKU, empty, any bin, most room first":</p>
 * <ol>
 *   <li>bins with a computed volume before every bin without one (legacy levels without a clear height): those are
 *       not limited, so nothing is known about how full they are;</li>
 *   <li>the group: a bin holding the same lot, then an empty bin (open a clean bin rather than mix lots), then one
 *       holding the same SKU in another lot, then one holding other SKUs;</li>
 *   <li>within the same-lot, same-SKU and other-SKU groups, the bin with less free volume first (best fit: fill the
 *       bin already started); among empty bins, the smallest that still takes the whole rest of the lot - every
 *       carton of the lot with an open putaway task in the warehouse - first, then those that do not, the larger
 *       first (they take the most of it);</li>
 *   <li>the location code.</li>
 * </ol>
 * <p>Among bins without a volume only the group and the code order them.</p>
 *
 * <p>A bin with no weight limit is never weighed, and neither is a carton whose weight nobody recorded.</p>
 */
public final class PutawayRanker {

    /** Why a bin is suggested; the declaration order is the group's rank (05 §4.2 step 4, 2026-11-08). */
    public enum Reason { SAME_LOT, EMPTY, SAME_SKU, OTHER_SKU }

    /** Why a bin is not a place for the carton; what {@link #refusal} answers. */
    public enum Refusal { DOES_NOT_FIT, NO_ROOM }

    /**
     * @param size        the bin's inside, the carton must fit within it
     * @param capacityM3  the bin's computed volume; null when its level has no clear height
     * @param maxWeightKg null when the map sets none
     */
    public record Bin(String locationCode, CartonFit.Space size, BigDecimal capacityM3, BigDecimal maxWeightKg) {}

    /**
     * What a bin holds: {@code units} only to tell an empty bin, {@code volumeM3} the cartons' volumes and
     * {@code weightKg} their full weights.
     */
    public record Occupancy(int units, BigDecimal volumeM3, BigDecimal weightKg, boolean holdsSku, boolean holdsLot) {
        public static final Occupancy EMPTY = new Occupancy(0, BigDecimal.ZERO, BigDecimal.ZERO, false, false);
    }

    /** One carton to put away; {@code weightKg} its full weight, null when nobody recorded one. */
    public record Need(CartonFit.Carton carton, BigDecimal weightKg) {
        public static final Need UNMEASURED = new Need(CartonFit.Carton.UNMEASURED, null);
    }

    /** @param freeVolumeM3 null for a bin without a volume, which is not limited */
    public record Suggestion(String locationCode, Reason reason, BigDecimal freeVolumeM3, BigDecimal freeWeightKg) {}

    /**
     * The rest of the lot: every carton of the carton's lot that has an open putaway task in the warehouse, this one
     * included - what an empty bin should take whole (05 §4.2). A weight nobody recorded counts as nothing.
     */
    public record Lot(BigDecimal volumeM3, BigDecimal weightKg) {
        public static Lot of(Need need) {
            return new Lot(need.carton().volumeM3(), need.weightKg());
        }
    }

    /** A suggestion with what orders it. */
    private record Ranked(Suggestion suggestion, boolean measured, boolean takesTheLot) {}

    public static final int MAX_SUGGESTIONS = 10;

    private static final Comparator<Ranked> ORDER = Comparator
            .comparing((Ranked r) -> !r.measured())
            .thenComparing(r -> r.suggestion().reason())
            .thenComparing(PutawayRanker::withinTheGroup)
            .thenComparing(r -> r.suggestion().locationCode());

    private PutawayRanker() {
    }

    /** {@link #rank(Need, Lot, List, Map)} for a carton whose lot has no other carton to put away. */
    public static List<Suggestion> rank(Need need, List<Bin> bins, Map<String, Occupancy> occupancy) {
        return rank(need, Lot.of(need), bins, occupancy);
    }

    /**
     * Up to {@link #MAX_SUGGESTIONS} bins that take the carton, best first; a bin missing from {@code occupancy} is
     * empty.
     *
     * @param restOfLot what the empty bins are ranked by: the volume and weight of the lot still to put away
     */
    public static List<Suggestion> rank(Need need, Lot restOfLot, List<Bin> bins, Map<String, Occupancy> occupancy) {
        return bins.stream()
                .filter(bin -> fits(need, bin, occupancy.getOrDefault(bin.locationCode(), Occupancy.EMPTY)))
                .map(bin -> {
                    Suggestion s = suggest(bin, occupancy.getOrDefault(bin.locationCode(), Occupancy.EMPTY));
                    return new Ranked(s, bin.capacityM3() != null, takesTheLot(s, restOfLot));
                })
                .sorted(ORDER)
                .limit(MAX_SUGGESTIONS)
                .map(Ranked::suggestion)
                .toList();
    }

    /**
     * The order inside a group of measured bins, as a number to sort ascending: the free volume for best fit; for an
     * empty bin that takes the whole lot its free volume too (the smallest first), for one that does not, the free
     * volume negated after every bin that does (the larger first). Unmeasured bins are ordered by code only.
     */
    private static BigDecimal withinTheGroup(Ranked r) {
        BigDecimal free = r.suggestion().freeVolumeM3();
        if (!r.measured()) {
            return BigDecimal.ZERO;
        }
        if (r.suggestion().reason() != Reason.EMPTY || r.takesTheLot()) {
            return free;
        }
        // After every bin that takes the lot, whatever its size: they rank by a non-negative volume below this.
        return BIG.subtract(free);
    }

    /** Larger than any bin's volume, so an empty bin that misses the lot always sorts after one that takes it. */
    private static final BigDecimal BIG = new BigDecimal("1000000000");

    private static boolean takesTheLot(Suggestion s, Lot lot) {
        boolean volume = s.freeVolumeM3() == null || lot.volumeM3() == null
                || s.freeVolumeM3().compareTo(lot.volumeM3()) >= 0;
        boolean weight = s.freeWeightKg() == null || lot.weightKg() == null
                || s.freeWeightKg().compareTo(lot.weightKg()) >= 0;
        return volume && weight;
    }

    /** The hard rule alone, for a bin the clerk chose (spec §6 step 4). */
    public static boolean fits(Need need, Bin bin, Occupancy occupancy) {
        return refusal(need, bin, occupancy) == null;
    }

    /** Why the bin does not take the carton, size first; null when it does. */
    public static Refusal refusal(Need need, Bin bin, Occupancy occupancy) {
        if (!CartonFit.fits(need.carton(), bin.size())) {
            return Refusal.DOES_NOT_FIT;
        }
        if (bin.capacityM3() != null
                && bin.capacityM3().subtract(occupancy.volumeM3()).compareTo(need.carton().volumeM3()) < 0) {
            return Refusal.NO_ROOM;
        }
        if (bin.maxWeightKg() != null && need.weightKg() != null
                && occupancy.weightKg().add(need.weightKg()).compareTo(bin.maxWeightKg()) > 0) {
            return Refusal.NO_ROOM;
        }
        return null;
    }

    private static Suggestion suggest(Bin bin, Occupancy occupancy) {
        Reason reason = occupancy.holdsLot() ? Reason.SAME_LOT
                : occupancy.holdsSku() ? Reason.SAME_SKU
                : occupancy.units() == 0 ? Reason.EMPTY
                : Reason.OTHER_SKU;
        BigDecimal freeVolume = bin.capacityM3() == null ? null : bin.capacityM3().subtract(occupancy.volumeM3());
        BigDecimal freeWeight = bin.maxWeightKg() == null ? null : bin.maxWeightKg().subtract(occupancy.weightKg());
        return new Suggestion(bin.locationCode(), reason, freeVolume, freeWeight);
    }
}
