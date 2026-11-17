package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.domain.BusinessCalendar;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Checks each line of a delivery-outcome file against the vehicle its waybill number names (WF-05 fix plan item 5: a
 * number no vehicle has, an outcome the shipment's state does not take, a repeat). Pure: the service reads the facts.
 *
 * <p>In order, a line is an error when: its waybill number, outcome or time is missing; a cell is longer than what it
 * is kept in; the outcome is neither {@code DELIVERED} nor {@code DELIVERY_FAILED}; the time cannot be read, or is
 * later than now; a failed delivery gives no reason (or gives {@code OTHER} with no note); no vehicle the importer may
 * see has the number - of the carrier named, when the line names one; several vehicles on the road have it, which
 * happens only across carriers, so naming the carrier settles it; the vehicle has not left (or none of its shipments
 * has); the time is before the vehicle left - a year typed wrongly would otherwise date the delivery, and everything
 * counted from it, before the handover; the shipment named did not leave on it; another line is about the same shipment; a shipment it is about cannot take
 * the outcome (09 §5: a delivered one is not failed afterwards). Otherwise it is accepted - and
 * {@link Verdict#changes()} says whether anything is still to change.</p>
 *
 * <p>An accepted line is not yet applied: it becomes a carrier event, and the event's own rules decide it (a repeat, a
 * report older than the last one applied, an outcome corrected by hand).</p>
 */
public final class DeliveryOutcomeCheck {

    /** A clock a few minutes ahead of ours is a clock, not a report from the future. */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final int WAYBILL_MAX = 64;
    private static final int SHIPMENT_CODE_MAX = 48;
    private static final int CARRIER_CODE_MAX = 32;
    private static final int RECIPIENT_MAX = 120;
    /** The failure note of a shipment (and of its order) is 500 characters. */
    private static final int TEXT_MAX = 500;

    /** Strict: the 31st of February is a typing mistake, not the 28th. */
    private static final List<DateTimeFormatter> LOCAL_TIMES = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm[:ss]").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d/M/uuuu HH:mm[:ss]").withResolverStyle(ResolverStyle.STRICT));

    private DeliveryOutcomeCheck() {
    }

    /** Why a line is not applied. */
    public enum Problem {
        MISSING_FIELDS, TEXT_TOO_LONG, INVALID_OUTCOME, INVALID_TIME, TIME_IN_FUTURE, REASON_REQUIRED,
        WAYBILL_NOT_FOUND, WAYBILL_AMBIGUOUS, LOAD_NOT_DISPATCHED, TIME_BEFORE_DISPATCH, SHIPMENT_NOT_ON_LOAD,
        DUPLICATE_IN_FILE, OUTCOME_NOT_ALLOWED
    }

    /** A shipment that left on a vehicle. */
    public record ShipmentFacts(UUID id, String code, ShipmentStatus status) {
    }

    /**
     * A vehicle holding a waybill number, of a warehouse the importer may see, and the shipments on it.
     *
     * @param carrierCode  its carrier's code, by which a line tells it from another carrier's vehicle with the number
     * @param dispatchedAt when it left; no outcome of it is earlier
     */
    public record LoadFacts(UUID loadId, String code, UUID carrierId, String carrierCode, LoadStatus status,
                            Instant dispatchedAt, List<ShipmentFacts> shipments) {
    }

    /**
     * A line's verdict.
     *
     * @param problem    why it is not applied; null when it is accepted
     * @param changes    accepted, and a shipment it is about does not have the outcome yet
     * @param load       its vehicle when exactly one was found; null otherwise
     * @param shipmentId the shipment it names; null for a line about the whole vehicle
     * @param kind       DELIVERED or DELIVERY_FAILED; null when the outcome could not be read
     * @param reportedAt when it happened; null when the time could not be read
     * @param reason     the internal reason the line names outright (a failed delivery); null when it gives the
     *                   carrier's own words, which the carrier's table maps ({@code wording})
     * @param wording    the carrier's words for the failure; null when {@code reason} is given or it was delivered
     */
    public record Verdict(DeliveryOutcomeFile.Row row, Problem problem, boolean changes, LoadFacts load,
                          UUID shipmentId, CarrierEventKind kind, Instant reportedAt, DeliveryFailureReason reason,
                          String wording) {

        public boolean accepted() {
            return problem == null;
        }
    }

    /** What a line says once its cells are read; nothing here needs a vehicle. */
    private record Parsed(Problem problem, CarrierEventKind kind, Instant reportedAt, DeliveryFailureReason reason,
                          String wording) {
    }

    /**
     * @param loadsByWaybill for each waybill number of the file, the vehicles holding it that the importer may see
     * @param now            the time no report may be later than
     */
    public static List<Verdict> check(List<DeliveryOutcomeFile.Row> rows, Map<String, List<LoadFacts>> loadsByWaybill,
                                      Instant now) {
        List<Verdict> first = new ArrayList<>();
        // Only a line good on its own counts towards another being a repeat.
        Map<UUID, Integer> wholeLoad = new HashMap<>();
        Map<UUID, Integer> perLoad = new HashMap<>();
        Map<UUID, Integer> perShipment = new HashMap<>();
        for (DeliveryOutcomeFile.Row row : rows) {
            Verdict verdict = alone(row, loadsByWaybill, now);
            first.add(verdict);
            if (verdict.accepted()) {
                perLoad.merge(verdict.load().loadId(), 1, Integer::sum);
                if (verdict.shipmentId() == null) {
                    wholeLoad.merge(verdict.load().loadId(), 1, Integer::sum);
                } else {
                    perShipment.merge(verdict.shipmentId(), 1, Integer::sum);
                }
            }
        }
        List<Verdict> verdicts = new ArrayList<>();
        for (Verdict v : first) {
            if (!v.accepted()) {
                verdicts.add(v);
                continue;
            }
            UUID load = v.load().loadId();
            // A line about the whole vehicle is about each of its shipments: any other line of the vehicle repeats it.
            boolean repeated = v.shipmentId() == null ? perLoad.get(load) > 1
                    : perShipment.get(v.shipmentId()) > 1 || wholeLoad.getOrDefault(load, 0) > 0;
            verdicts.add(repeated ? new Verdict(v.row(), Problem.DUPLICATE_IN_FILE, false, v.load(), v.shipmentId(),
                    v.kind(), v.reportedAt(), v.reason(), v.wording()) : v);
        }
        return verdicts;
    }

    /** The line judged without the other lines. */
    private static Verdict alone(DeliveryOutcomeFile.Row row, Map<String, List<LoadFacts>> loadsByWaybill,
                                 Instant now) {
        Parsed parsed = parse(row, now);
        if (parsed.problem() != null) {
            return error(row, parsed.problem(), null, null, parsed);
        }
        List<LoadFacts> holders = loadsByWaybill.getOrDefault(row.waybillNo(), List.of()).stream()
                .filter(l -> row.carrierCode() == null || row.carrierCode().equalsIgnoreCase(l.carrierCode())).toList();
        if (holders.isEmpty()) {
            return error(row, Problem.WAYBILL_NOT_FOUND, null, null, parsed);
        }
        List<LoadFacts> left = holders.stream().filter(l -> l.status() == LoadStatus.DISPATCHED).toList();
        if (left.isEmpty()) {
            return error(row, Problem.LOAD_NOT_DISPATCHED, holders.size() == 1 ? holders.get(0) : null, null, parsed);
        }
        if (left.size() > 1) {
            return error(row, Problem.WAYBILL_AMBIGUOUS, null, null, parsed);
        }
        LoadFacts load = left.get(0);
        if (load.dispatchedAt() != null && parsed.reportedAt().isBefore(load.dispatchedAt())) {
            return error(row, Problem.TIME_BEFORE_DISPATCH, load, null, parsed);
        }
        List<ShipmentFacts> targets = load.shipments();
        UUID shipmentId = null;
        if (row.shipmentCode() != null) {
            targets = load.shipments().stream().filter(s -> s.code().equals(row.shipmentCode())).toList();
            if (targets.isEmpty()) {
                return error(row, Problem.SHIPMENT_NOT_ON_LOAD, load, null, parsed);
            }
            shipmentId = targets.get(0).id();
        } else if (targets.isEmpty()) {
            return error(row, Problem.LOAD_NOT_DISPATCHED, load, null, parsed);
        }
        ShipmentStatus target = parsed.kind() == CarrierEventKind.DELIVERED ? ShipmentStatus.DELIVERED
                : ShipmentStatus.DELIVERY_FAILED;
        boolean changes = false;
        for (ShipmentFacts shipment : targets) {
            if (shipment.status() == target) {
                continue;
            }
            if (!shipment.status().canTransitionTo(target)) {
                return error(row, Problem.OUTCOME_NOT_ALLOWED, load, shipmentId, parsed);
            }
            changes = true;
        }
        return new Verdict(row, null, changes, load, shipmentId, parsed.kind(), parsed.reportedAt(), parsed.reason(),
                parsed.wording());
    }

    private static Parsed parse(DeliveryOutcomeFile.Row row, Instant now) {
        if (row.waybillNo() == null || row.outcome() == null || row.reportedAt() == null) {
            return new Parsed(Problem.MISSING_FIELDS, null, null, null, null);
        }
        if (tooLong(row.waybillNo(), WAYBILL_MAX) || tooLong(row.shipmentCode(), SHIPMENT_CODE_MAX)
                || tooLong(row.carrierCode(), CARRIER_CODE_MAX)
                || tooLong(row.recipient(), RECIPIENT_MAX) || tooLong(row.note(), TEXT_MAX)
                || tooLong(row.failureReason(), TEXT_MAX)) {
            return new Parsed(Problem.TEXT_TOO_LONG, null, null, null, null);
        }
        CarrierEventKind kind = switch (row.outcome().toUpperCase(Locale.ROOT)) {
            case "DELIVERED" -> CarrierEventKind.DELIVERED;
            case "DELIVERY_FAILED" -> CarrierEventKind.DELIVERY_FAILED;
            default -> null;
        };
        if (kind == null) {
            return new Parsed(Problem.INVALID_OUTCOME, null, null, null, null);
        }
        Instant at = time(row.reportedAt());
        if (at == null) {
            return new Parsed(Problem.INVALID_TIME, kind, null, null, null);
        }
        if (at.isAfter(now.plus(CLOCK_SKEW))) {
            return new Parsed(Problem.TIME_IN_FUTURE, kind, at, null, null);
        }
        if (kind == CarrierEventKind.DELIVERED) {
            return new Parsed(null, kind, at, null, null);
        }
        if (row.failureReason() == null) {
            return new Parsed(Problem.REASON_REQUIRED, kind, at, null, null);
        }
        DeliveryFailureReason reason = reason(row.failureReason());
        if (reason == DeliveryFailureReason.OTHER && row.note() == null) {
            // "Another reason" says what it was (the shipment's own rule).
            return new Parsed(Problem.REASON_REQUIRED, kind, at, reason, null);
        }
        return new Parsed(null, kind, at, reason, reason == null ? row.failureReason() : null);
    }

    /** One of the six internal reasons written outright; null for the carrier's own words. */
    private static DeliveryFailureReason reason(String cell) {
        try {
            return DeliveryFailureReason.valueOf(cell.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException carrierWording) {
            return null;
        }
    }

    /**
     * ISO-8601 with an offset, or a local date and time - {@code 2026-11-09T10:15}, {@code 2026-11-09 10:15},
     * {@code 9/11/2026 10:15}, seconds allowed - taken in the business's time zone; null when it is none of these.
     */
    static Instant time(String cell) {
        try {
            return OffsetDateTime.parse(cell).toInstant();
        } catch (DateTimeParseException notOffset) {
            // a local time, below
        }
        for (DateTimeFormatter format : LOCAL_TIMES) {
            try {
                return LocalDateTime.parse(cell, format).atZone(BusinessCalendar.ZONE).toInstant();
            } catch (DateTimeParseException next) {
                // the next format
            }
        }
        return null;
    }

    private static Verdict error(DeliveryOutcomeFile.Row row, Problem problem, LoadFacts load, UUID shipmentId,
                                 Parsed parsed) {
        return new Verdict(row, problem, false, load, shipmentId, parsed.kind(), parsed.reportedAt(), parsed.reason(),
                parsed.wording());
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }
}
