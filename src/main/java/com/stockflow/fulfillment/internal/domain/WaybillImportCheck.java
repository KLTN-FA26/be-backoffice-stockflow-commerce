package com.stockflow.fulfillment.internal.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Checks each line of a waybill import against the loads it names (kltn-docs 09 §4.4 step 2, BR-25; E18: the valid
 * lines are applied, the others kept with their reason). Pure: the service reads the facts, under the loads' locks
 * when it applies.
 *
 * <p>In order, a line is an error when: its load code or number is missing; the number, plate, driver or phone is
 * malformed; no load has the code, or the load is of a warehouse the importer is not assigned to; another line names
 * the same load; the load has no carrier booked, is cancelled or has left; its carrier gives numbers through its
 * booking API; another line gives the same number for the same carrier ("kể cả giữa các dòng trong file"); another
 * live load of the carrier holds the number; it would overwrite a number the dispatcher did not confirm (BR-25: an
 * overwrite is shown in the preview first). Otherwise it is {@link Outcome#NEW}, {@link Outcome#OVERWRITE} or
 * {@link Outcome#SAME}.</p>
 *
 * <p>Only a line that names a load the importer may see, with every field well formed, counts towards another line
 * being a repeat or a duplicate: a line refused for something else neither refuses a good line nor tells the importer
 * anything about a load of another warehouse.</p>
 */
public final class WaybillImportCheck {

    private WaybillImportCheck() {
    }

    /** What a line comes to. */
    public enum Outcome { NEW, OVERWRITE, SAME, ERROR }

    /** Why a line is not applied. */
    public enum Problem {
        MISSING_FIELDS, INVALID_WAYBILL, INVALID_VEHICLE_DETAILS, LOAD_NOT_FOUND, LOAD_OUT_OF_SCOPE, LOAD_REPEATED,
        LOAD_NOT_BOOKED, LOAD_CANCELLED, LOAD_LEFT, WAYBILL_FROM_CARRIER_API, DUPLICATE_IN_FILE, DUPLICATE_IN_CARRIER,
        OVERWRITE_NOT_CONFIRMED
    }

    /**
     * What a line needs to know of its load.
     *
     * @param inScope       of a warehouse the importer is assigned to
     * @param apiBooking    its carrier books through an API and gives the number itself
     * @param waybillNo     the number it has, if any
     */
    public record LoadFacts(UUID loadId, String code, LoadStatus status, UUID carrierId, boolean apiBooking,
                            String waybillNo, boolean inScope) {
    }

    /** A live load holding a number: its code, and whether the importer may be told it. */
    public record Holder(String code, boolean visible) {
    }

    /**
     * A line's verdict.
     *
     * @param load    its load when one has the code and is in scope; null otherwise
     * @param problem why it is not applied; null unless {@link Outcome#ERROR}
     * @param holder  for {@link Problem#DUPLICATE_IN_CARRIER}, the code of the load holding the number when the
     *                importer may see it; null otherwise
     */
    public record Verdict(WaybillImportFile.Row row, Outcome outcome, Problem problem, LoadFacts load, String holder) {

        public boolean applies() {
            return outcome != Outcome.ERROR;
        }
    }

    /**
     * @param loads               the loads the file names, by code (a code missing here names no load)
     * @param taken               for each carrier, the numbers live loads hold, to their holder
     * @param confirmedOverwrites the lines whose overwrite the dispatcher confirmed after the preview; null on the
     *                            preview itself, where an overwrite is shown, not refused
     */
    public static List<Verdict> check(List<WaybillImportFile.Row> rows, Map<String, LoadFacts> loads,
                                      Map<UUID, Map<String, Holder>> taken, Set<Integer> confirmedOverwrites) {
        Map<String, Integer> perLoad = new HashMap<>();
        Map<String, Integer> perNumber = new HashMap<>();
        for (WaybillImportFile.Row row : rows) {
            LoadFacts load = row.loadCode() == null ? null : loads.get(row.loadCode());
            if (early(row, load) == null) {
                perLoad.merge(load.code(), 1, Integer::sum);
                if (load.carrierId() != null) {
                    perNumber.merge(load.carrierId() + "|" + row.waybillNo(), 1, Integer::sum);
                }
            }
        }
        List<Verdict> verdicts = new ArrayList<>();
        for (WaybillImportFile.Row row : rows) {
            verdicts.add(verdict(row, loads, taken, perLoad, perNumber, confirmedOverwrites));
        }
        return verdicts;
    }

    /** The problems a line has on its own, before any other line or load is looked at; null when it has none. */
    private static Problem early(WaybillImportFile.Row row, LoadFacts load) {
        if (row.loadCode() == null || row.waybillNo() == null) {
            return Problem.MISSING_FIELDS;
        }
        if (!DispatchLoad.WAYBILL.matcher(row.waybillNo()).matches()) {
            return Problem.INVALID_WAYBILL;
        }
        if (tooLong(row.vehiclePlate(), 20) || tooLong(row.driverName(), 120) || tooLong(row.driverPhone(), 32)) {
            return Problem.INVALID_VEHICLE_DETAILS;
        }
        if (load == null) {
            return Problem.LOAD_NOT_FOUND;
        }
        return load.inScope() ? null : Problem.LOAD_OUT_OF_SCOPE;
    }

    private static Verdict verdict(WaybillImportFile.Row row, Map<String, LoadFacts> loads,
                                   Map<UUID, Map<String, Holder>> taken, Map<String, Integer> perLoad,
                                   Map<String, Integer> perNumber, Set<Integer> confirmedOverwrites) {
        LoadFacts load = row.loadCode() == null ? null : loads.get(row.loadCode());
        Problem early = early(row, load);
        if (early != null) {
            return error(row, early, null);
        }
        if (perLoad.getOrDefault(load.code(), 0) > 1) {
            return error(row, Problem.LOAD_REPEATED, load);
        }
        if (load.status() == LoadStatus.CANCELLED) {
            return error(row, Problem.LOAD_CANCELLED, load);
        }
        if (load.status() == LoadStatus.DISPATCHED) {
            return error(row, Problem.LOAD_LEFT, load);
        }
        if (load.carrierId() == null || load.status() == LoadStatus.PLANNED) {
            return error(row, Problem.LOAD_NOT_BOOKED, load);
        }
        if (load.apiBooking()) {
            return error(row, Problem.WAYBILL_FROM_CARRIER_API, load);
        }
        if (perNumber.getOrDefault(load.carrierId() + "|" + row.waybillNo(), 0) > 1) {
            return error(row, Problem.DUPLICATE_IN_FILE, load);
        }
        Holder holder = taken.getOrDefault(load.carrierId(), Map.of()).get(row.waybillNo());
        if (holder != null && !holder.code().equals(load.code())) {
            return new Verdict(row, Outcome.ERROR, Problem.DUPLICATE_IN_CARRIER, load,
                    holder.visible() ? holder.code() : null);
        }
        Outcome outcome = load.waybillNo() == null ? Outcome.NEW
                : Objects.equals(load.waybillNo(), row.waybillNo()) ? Outcome.SAME : Outcome.OVERWRITE;
        if (outcome == Outcome.OVERWRITE && confirmedOverwrites != null
                && !confirmedOverwrites.contains(row.line())) {
            return error(row, Problem.OVERWRITE_NOT_CONFIRMED, load);
        }
        return new Verdict(row, outcome, null, load, null);
    }

    private static Verdict error(WaybillImportFile.Row row, Problem problem, LoadFacts load) {
        return new Verdict(row, Outcome.ERROR, problem, load, null);
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }
}
