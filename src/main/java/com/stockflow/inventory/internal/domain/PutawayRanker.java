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
 * <p>A bin with no volume (its level has no clear height, legacy data) has no volume limit and ranks after every
 * measured bin of its group (06 §4.4, §4.6); one with no weight limit is never weighed, and neither is a carton whose
 * weight nobody recorded.</p>
 */
public final class PutawayRanker {

    /** Why a bin is suggested; the declaration order is the rank (BR-SLT-004: consolidate before spreading). */
    public enum Reason { SAME_LOT, SAME_SKU, EMPTY, HAS_ROOM }

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

    public static final int MAX_SUGGESTIONS = 10;

    /** Larger free room first; a bin without a volume after every measured one (06 §4.4, decided 2026-11-08). */
    private static final Comparator<BigDecimal> MORE_ROOM_FIRST = Comparator.nullsLast(Comparator.reverseOrder());

    private static final Comparator<Suggestion> ORDER = Comparator
            .comparing(Suggestion::reason)
            .thenComparing(Suggestion::freeVolumeM3, MORE_ROOM_FIRST)
            .thenComparing(Suggestion::locationCode);

    private PutawayRanker() {
    }

    /** Up to {@link #MAX_SUGGESTIONS} bins that take the carton, best first; a bin missing from {@code occupancy} is empty. */
    public static List<Suggestion> rank(Need need, List<Bin> bins, Map<String, Occupancy> occupancy) {
        return bins.stream()
                .filter(bin -> fits(need, bin, occupancy.getOrDefault(bin.locationCode(), Occupancy.EMPTY)))
                .map(bin -> suggest(bin, occupancy.getOrDefault(bin.locationCode(), Occupancy.EMPTY)))
                .sorted(ORDER)
                .limit(MAX_SUGGESTIONS)
                .toList();
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
                : Reason.HAS_ROOM;
        BigDecimal freeVolume = bin.capacityM3() == null ? null : bin.capacityM3().subtract(occupancy.volumeM3());
        BigDecimal freeWeight = bin.maxWeightKg() == null ? null : bin.maxWeightKg().subtract(occupancy.weightKg());
        return new Suggestion(bin.locationCode(), reason, freeVolume, freeWeight);
    }
}
