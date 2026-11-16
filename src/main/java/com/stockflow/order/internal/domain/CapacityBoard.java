package com.stockflow.order.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A warehouse's capacity board (kltn-docs 06 §4.6, BR-19, BR-20): will the goods on their way still find room on its
 * bins? Computed when read, never stored. Every figure the board shows is worked out here, from plain inputs, so the
 * rules are unit-tested without a database.
 *
 * <p>The rules decided for it (owner, 2026-11-13 to 16; design {@code 2026-11-16-scrum-669-capacity-board-design}):</p>
 * <ul>
 *   <li><b>Only a bin has capacity</b> (06 BR-19): Σ the computed volume of the usable bins, times the warehouse's
 *       utilisation factor - at every level, so zone rows add up to the warehouse's. A bin with no volume (a legacy
 *       level without a clear height) adds nothing and is counted.</li>
 *   <li><b>Occupied</b> is what occupies, or will need, a bin: cartons in any bin, whatever its status, and in every
 *       area but {@code STAGING} and {@code DISPATCH} - cartons there were picked and are leaving. Those areas are still
 *       listed with what stands in them.</li>
 *   <li><b>Incoming</b> (production orders sent and not fully received) is taken from expected free space; <b>pending
 *       approval</b> (drafts) only from "free if all approved"; <b>outbound</b> is shown for reference and never added
 *       back: when held cartons leave is unknown, and goods may arrive first.</li>
 * </ul>
 */
public record CapacityBoard(UUID warehouseId, String prefix, BigDecimal utilizationFactor,
                            BigDecimal capacityM3, BigDecimal capacityKg,
                            Load occupied, Load incoming, Load pendingApproval, Load outbound,
                            BigDecimal expectedFreeM3, BigDecimal freeIfAllApprovedM3, BigDecimal freeKg,
                            BigDecimal occupiedPercent, BigDecimal expectedFreePercent, BigDecimal occupiedKgPercent,
                            int binsWithoutVolume, int binsWithoutWeightLimit, long unmeasuredCartons,
                            List<Section> zones, List<Section> shelves, List<AreaRow> areas) {

    /** Areas whose cartons are leaving and never go back to a bin (07 §4.3, 08): listed, not counted. */
    static final Set<AreaKind> LEAVING = Set.of(AreaKind.STAGING, AreaKind.DISPATCH);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal CM3_PER_M3 = BigDecimal.valueOf(1_000_000);

    /** Cartons, their volume (m³) and full weight (kg); {@code unmeasuredCartons} of them have no full size. */
    public record Load(long cartons, BigDecimal volumeM3, BigDecimal weightKg, long unmeasuredCartons) {

        public static final Load NONE = new Load(0, BigDecimal.ZERO, BigDecimal.ZERO, 0);

        public Load {
            Objects.requireNonNull(volumeM3, "volumeM3");
            Objects.requireNonNull(weightKg, "weightKg");
        }

        public Load plus(Load other) {
            return new Load(cartons + other.cartons, volumeM3.add(other.volumeM3), weightKg.add(other.weightKg),
                    unmeasuredCartons + other.unmeasuredCartons);
        }
    }

    /** What an area is for, as far as the board cares; the warehouse's area types, one for one. */
    public enum AreaKind { RECEIVING, QUARANTINE, DISPATCH, STAGING, OVERFLOW, PRODUCTION }

    /**
     * A bin of the warehouse.
     *
     * @param volumeM3    computed; null when unknown
     * @param maxWeightKg declared load limit; null when none
     * @param usable      effective status ACTIVE: only these count towards capacity
     */
    public record BinSpace(String locationCode, UUID zoneId, String zoneName, String shelfCode, BigDecimal volumeM3,
                           BigDecimal maxWeightKg, boolean usable) {
    }

    public record AreaSpace(String locationCode, String code, String name, AreaKind kind) {
    }

    /** One carton of a SKU: its volume (m³) and full weight (kg), either null when not declared. */
    public record CartonSize(BigDecimal volumeM3, BigDecimal weightKg) {

        /** From a carton spec's centimetres; null size when any side is missing. */
        public static CartonSize ofCentimetres(BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm,
                                               BigDecimal weightKg) {
            BigDecimal volume = lengthCm == null || widthCm == null || heightCm == null ? null
                    : lengthCm.multiply(widthCm).multiply(heightCm).divide(CM3_PER_M3);
            return new CartonSize(volume, weightKg);
        }
    }

    /** Cartons of a SKU still due on production orders; {@code awaitingApproval} for DRAFT / PENDING_APPROVAL. */
    public record Due(String sku, boolean awaitingApproval, long cartons) {
    }

    /**
     * A zone's or a shelf's row: Capacity, Occupied and current free space (Capacity − Occupied). {@code zoneId} and
     * {@code name} are null on the "no zone" row; {@code shelfCode} is null on a zone row.
     */
    public record Section(UUID zoneId, String name, String shelfCode, BigDecimal capacityM3, BigDecimal capacityKg,
                          Load occupied, BigDecimal freeM3, BigDecimal freeKg, BigDecimal occupiedPercent) {
    }

    /** An area's row: what stands there, and whether it counts as occupied. Areas have no capacity (06 BR-19). */
    public record AreaRow(String locationCode, String code, String name, AreaKind kind, Load occupied,
                          boolean countsAsOccupied) {
    }

    /**
     * @param loads what stands at each location (absent = nothing)
     */
    public record Inputs(UUID warehouseId, String prefix, BigDecimal utilizationFactor, List<BinSpace> bins,
                         List<AreaSpace> areas, Map<String, Load> loads, Load outbound, List<Due> due,
                         Map<String, CartonSize> cartonSizes) {
    }

    public static CapacityBoard compute(Inputs in) {
        BigDecimal factor = in.utilizationFactor();
        Map<String, Load> loads = in.loads();

        BigDecimal rawM3 = BigDecimal.ZERO;
        BigDecimal rawKg = BigDecimal.ZERO;
        int withoutVolume = 0;
        int withoutWeight = 0;
        Load occupied = Load.NONE;
        for (BinSpace bin : in.bins()) {
            occupied = occupied.plus(loads.getOrDefault(bin.locationCode(), Load.NONE));
            if (!bin.usable()) {
                continue;
            }
            if (bin.volumeM3() == null) {
                withoutVolume++;
            } else {
                rawM3 = rawM3.add(bin.volumeM3());
            }
            if (bin.maxWeightKg() == null) {
                withoutWeight++;
            } else {
                rawKg = rawKg.add(bin.maxWeightKg());
            }
        }

        List<AreaRow> areaRows = new ArrayList<>();
        for (AreaSpace area : in.areas()) {
            Load load = loads.getOrDefault(area.locationCode(), Load.NONE);
            boolean counts = !LEAVING.contains(area.kind());
            if (counts) {
                occupied = occupied.plus(load);
            }
            areaRows.add(new AreaRow(area.locationCode(), area.code(), area.name(), area.kind(), load, counts));
        }

        Load incoming = Load.NONE;
        Load pending = Load.NONE;
        for (Due due : in.due()) {
            Load load = cartonsOf(due.cartons(), in.cartonSizes().get(due.sku()));
            if (due.awaitingApproval()) {
                pending = pending.plus(load);
            } else {
                incoming = incoming.plus(load);
            }
        }

        BigDecimal capacityM3 = rawM3.multiply(factor);
        BigDecimal capacityKg = rawKg.multiply(factor);
        BigDecimal expectedFree = capacityM3.subtract(occupied.volumeM3()).subtract(incoming.volumeM3());
        BigDecimal freeIfAll = expectedFree.subtract(pending.volumeM3());
        long unmeasured = occupied.unmeasuredCartons() + incoming.unmeasuredCartons()
                + pending.unmeasuredCartons() + in.outbound().unmeasuredCartons();

        return new CapacityBoard(in.warehouseId(), in.prefix(), factor, capacityM3, capacityKg,
                occupied, incoming, pending, in.outbound(), expectedFree, freeIfAll,
                capacityKg.subtract(occupied.weightKg()),
                percent(occupied.volumeM3(), capacityM3), percent(expectedFree, capacityM3),
                percent(occupied.weightKg(), capacityKg),
                withoutVolume, withoutWeight, unmeasured,
                sections(in.bins(), loads, factor, true), sections(in.bins(), loads, factor, false), areaRows);
    }

    /** {@code cartons} of a carton size; an unknown size counts every carton as unmeasured, 0 m³ and 0 kg. */
    static Load cartonsOf(long cartons, CartonSize size) {
        if (cartons <= 0) {
            return Load.NONE;
        }
        BigDecimal count = BigDecimal.valueOf(cartons);
        BigDecimal volume = size == null || size.volumeM3() == null ? null : size.volumeM3().multiply(count);
        BigDecimal weight = size == null || size.weightKg() == null ? BigDecimal.ZERO : size.weightKg().multiply(count);
        return new Load(cartons, volume == null ? BigDecimal.ZERO : volume, weight, volume == null ? cartons : 0);
    }

    /** Value as a share of capacity, in percent to one decimal; null when there is no capacity to share. */
    static BigDecimal percent(BigDecimal value, BigDecimal capacity) {
        if (capacity.signum() == 0) {
            return null;
        }
        return value.multiply(HUNDRED).divide(capacity, 1, RoundingMode.HALF_UP);
    }

    /** One row per zone (bins of shelves in it, "no zone" last) or per shelf, in the order the bins came. */
    private static List<Section> sections(List<BinSpace> bins, Map<String, Load> loads, BigDecimal factor,
                                          boolean byZone) {
        record Key(UUID zoneId, String shelfCode) { }
        Map<Key, List<BinSpace>> groups = new LinkedHashMap<>();
        for (BinSpace bin : bins) {
            groups.computeIfAbsent(new Key(bin.zoneId(), byZone ? null : bin.shelfCode()), k -> new ArrayList<>())
                    .add(bin);
        }
        List<Section> out = new ArrayList<>();
        groups.forEach((key, members) -> {
            BigDecimal m3 = BigDecimal.ZERO;
            BigDecimal kg = BigDecimal.ZERO;
            Load occupied = Load.NONE;
            for (BinSpace bin : members) {
                occupied = occupied.plus(loads.getOrDefault(bin.locationCode(), Load.NONE));
                if (bin.usable() && bin.volumeM3() != null) {
                    m3 = m3.add(bin.volumeM3());
                }
                if (bin.usable() && bin.maxWeightKg() != null) {
                    kg = kg.add(bin.maxWeightKg());
                }
            }
            BigDecimal capacityM3 = m3.multiply(factor);
            BigDecimal capacityKg = kg.multiply(factor);
            out.add(new Section(key.zoneId(), members.getFirst().zoneName(), key.shelfCode(), capacityM3, capacityKg,
                    occupied, capacityM3.subtract(occupied.volumeM3()), capacityKg.subtract(occupied.weightKg()),
                    percent(occupied.volumeM3(), capacityM3)));
        });
        if (byZone) {
            out.sort(Comparator.comparing((Section s) -> s.zoneId() == null));
        }
        return out;
    }
}
