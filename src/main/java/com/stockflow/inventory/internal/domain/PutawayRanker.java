package com.stockflow.inventory.internal.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Chooses and orders the bins a received carton or loose quantity may be put away into (SCRUM-92).
 *
 * <p>Pure on purpose: the candidates come from the warehouse module and the occupancy from the stock ledger, and
 * this class knows neither, so the rule is one function of three plain inputs. {@link #fits} is the same
 * capacity and weight rule the ranking filters with, so a bin the clerk picks by hand is judged exactly as
 * strictly as one we suggest - a manual choice must not be a way round BR-SLT-001 / BR-SLT-003.</p>
 *
 * <p>A bin with no recorded capacity is unlimited and one with no recorded weight limit is never weighed;
 * the weight rule also needs the unit weight, otherwise there is nothing to add up.</p>
 */
public final class PutawayRanker {

    /** Why a bin is suggested; the declaration order is the rank (BR-SLT-004: consolidate before spreading). */
    public enum Reason { SAME_LOT, SAME_SKU, EMPTY, HAS_ROOM }

    public record Bin(String locationCode, Integer capacityUnits, BigDecimal maxWeightKg) {}

    public record Occupancy(int units, BigDecimal weightKg, boolean holdsSku, boolean holdsLot) {
        public static final Occupancy EMPTY = new Occupancy(0, BigDecimal.ZERO, false, false);
    }

    /** units: what must fit (a carton's units; 1 for a loose task); unitWeightKg may be null. */
    public record Need(int units, BigDecimal unitWeightKg) {}

    public record Suggestion(String locationCode, Reason reason, Integer freeUnits, BigDecimal freeWeightKg) {}

    public static final int MAX_SUGGESTIONS = 10;

    /** Larger free room first; null is unlimited and so sorts before every number. */
    private static final Comparator<Integer> MORE_ROOM_FIRST = Comparator.nullsFirst(Comparator.reverseOrder());

    private static final Comparator<Suggestion> ORDER = Comparator
            .comparing(Suggestion::reason)
            .thenComparing(Suggestion::freeUnits, MORE_ROOM_FIRST)
            .thenComparing(Suggestion::locationCode);

    private PutawayRanker() {
    }

    /** Up to {@link #MAX_SUGGESTIONS} bins that fit, best first; a bin missing from {@code occupancy} is empty. */
    public static List<Suggestion> rank(Need need, List<Bin> bins, Map<String, Occupancy> occupancy) {
        return bins.stream()
                .filter(bin -> fits(need, bin, occupancy.getOrDefault(bin.locationCode(), Occupancy.EMPTY)))
                .map(bin -> suggest(bin, occupancy.getOrDefault(bin.locationCode(), Occupancy.EMPTY)))
                .sorted(ORDER)
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    /** The hard capacity / weight rule alone, for a bin the clerk chose (spec §6 step 4). */
    public static boolean fits(Need need, Bin bin, Occupancy occupancy) {
        if (bin.capacityUnits() != null && bin.capacityUnits() - occupancy.units() < need.units()) {
            return false;
        }
        if (bin.maxWeightKg() != null && need.unitWeightKg() != null) {
            BigDecimal after = occupancy.weightKg().add(need.unitWeightKg().multiply(BigDecimal.valueOf(need.units())));
            return after.compareTo(bin.maxWeightKg()) <= 0;
        }
        return true;
    }

    private static Suggestion suggest(Bin bin, Occupancy occupancy) {
        Reason reason = occupancy.holdsLot() ? Reason.SAME_LOT
                : occupancy.holdsSku() ? Reason.SAME_SKU
                : occupancy.units() == 0 ? Reason.EMPTY
                : Reason.HAS_ROOM;
        Integer freeUnits = bin.capacityUnits() == null ? null : bin.capacityUnits() - occupancy.units();
        BigDecimal freeWeight = bin.maxWeightKg() == null ? null : bin.maxWeightKg().subtract(occupancy.weightKg());
        return new Suggestion(bin.locationCode(), reason, freeUnits, freeWeight);
    }
}
