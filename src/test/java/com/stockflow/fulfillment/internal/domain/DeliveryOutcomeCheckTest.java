package com.stockflow.fulfillment.internal.domain;

import com.stockflow.fulfillment.internal.domain.DeliveryOutcomeCheck.LoadFacts;
import com.stockflow.fulfillment.internal.domain.DeliveryOutcomeCheck.Problem;
import com.stockflow.fulfillment.internal.domain.DeliveryOutcomeCheck.ShipmentFacts;
import com.stockflow.fulfillment.internal.domain.DeliveryOutcomeCheck.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The line-by-line check of a delivery-outcome file (WF-05 fix plan item 5): each way a line is refused, in the order
 * the rules are tried, and what an accepted line carries on to its carrier event.
 */
class DeliveryOutcomeCheckTest {

    private static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z");
    private static final String AT = "2026-11-12 09:00";
    private static final UUID CARRIER = UUID.randomUUID();
    private static final ShipmentFacts S1 = new ShipmentFacts(UUID.randomUUID(), "SO-1-S1", ShipmentStatus.HANDED_OVER);
    private static final ShipmentFacts S2 = new ShipmentFacts(UUID.randomUUID(), "SO-2-S1", ShipmentStatus.HANDED_OVER);
    private static final LoadFacts LOAD = load("LD-1", LoadStatus.DISPATCHED, S1, S2);

    private static LoadFacts load(String code, LoadStatus status, ShipmentFacts... shipments) {
        return new LoadFacts(UUID.randomUUID(), code, CARRIER, status, List.of(shipments));
    }

    private static DeliveryOutcomeFile.Row row(int line, String waybill, String outcome, String reason, String at,
                                               String note, String shipment) {
        return new DeliveryOutcomeFile.Row(line, waybill, outcome, reason, at, null, note, shipment);
    }

    private static Verdict one(DeliveryOutcomeFile.Row row, LoadFacts... holders) {
        return DeliveryOutcomeCheck.check(List.of(row), Map.of("WB-1", List.of(holders)), NOW).get(0);
    }

    @Test
    @DisplayName("an accepted line names its vehicle and shipment, the outcome and the time - a local time read in the "
            + "business's zone")
    void acceptsAGoodLine() {
        Verdict delivered = one(row(2, "WB-1", "delivered", null, AT, null, "SO-1-S1"), LOAD);

        assertThat(delivered.accepted()).isTrue();
        assertThat(delivered.changes()).isTrue();
        assertThat(delivered.load()).isEqualTo(LOAD);
        assertThat(delivered.shipmentId()).isEqualTo(S1.id());
        assertThat(delivered.kind()).isEqualTo(CarrierEventKind.DELIVERED);
        // 09:00 in Asia/Ho_Chi_Minh.
        assertThat(delivered.reportedAt()).isEqualTo(Instant.parse("2026-11-12T02:00:00Z"));

        Verdict wholeVehicle = one(row(2, "WB-1", "DELIVERED", null, "2026-11-12T09:00:00+07:00", null, null), LOAD);
        assertThat(wholeVehicle.accepted()).isTrue();
        assertThat(wholeVehicle.shipmentId()).isNull();
    }

    @Test
    @DisplayName("the times a spreadsheet writes are read: ISO with an offset, ISO local, with a space, day first")
    void readsTheTimesASpreadsheetWrites() {
        Instant expected = Instant.parse("2026-11-09T03:15:00Z");
        assertThat(DeliveryOutcomeCheck.time("2026-11-09T10:15:00+07:00")).isEqualTo(expected);
        assertThat(DeliveryOutcomeCheck.time("2026-11-09T03:15:00Z")).isEqualTo(expected);
        assertThat(DeliveryOutcomeCheck.time("2026-11-09T10:15")).isEqualTo(expected);
        assertThat(DeliveryOutcomeCheck.time("2026-11-09 10:15")).isEqualTo(expected);
        assertThat(DeliveryOutcomeCheck.time("2026-11-09 10:15:00")).isEqualTo(expected);
        assertThat(DeliveryOutcomeCheck.time("9/11/2026 10:15")).isEqualTo(expected);
        assertThat(DeliveryOutcomeCheck.time("09/11/2026 10:15:00")).isEqualTo(expected);
        assertThat(DeliveryOutcomeCheck.time("yesterday")).isNull();
        assertThat(DeliveryOutcomeCheck.time("2026-11-09")).isNull();
        assertThat(DeliveryOutcomeCheck.time("31/02/2026 10:15")).isNull();
    }

    @Test
    @DisplayName("a failed delivery names an internal reason outright, or gives the carrier's words for its table to "
            + "map; with none, or OTHER and no note, it is refused")
    void aFailedDeliveryGivesItsReason() {
        Verdict outright = one(row(2, "WB-1", "DELIVERY_FAILED", "refused", AT, null, "SO-1-S1"), LOAD);
        assertThat(outright.accepted()).isTrue();
        assertThat(outright.reason()).isEqualTo(DeliveryFailureReason.REFUSED);
        assertThat(outright.wording()).isNull();

        Verdict worded = one(row(2, "WB-1", "DELIVERY_FAILED", "Khách không nhận", AT, null, "SO-1-S1"), LOAD);
        assertThat(worded.accepted()).isTrue();
        assertThat(worded.reason()).isNull();
        assertThat(worded.wording()).isEqualTo("Khách không nhận");

        assertThat(one(row(2, "WB-1", "DELIVERY_FAILED", null, AT, null, "SO-1-S1"), LOAD).problem())
                .isEqualTo(Problem.REASON_REQUIRED);
        assertThat(one(row(2, "WB-1", "DELIVERY_FAILED", "OTHER", AT, null, "SO-1-S1"), LOAD).problem())
                .isEqualTo(Problem.REASON_REQUIRED);
        assertThat(one(row(2, "WB-1", "DELIVERY_FAILED", "OTHER", AT, "xe hỏng giữa đường", "SO-1-S1"), LOAD)
                .accepted()).isTrue();
    }

    @Test
    @DisplayName("a line is refused for what it lacks or writes wrongly, before any vehicle is looked at")
    void refusesALineOnItsOwn() {
        assertThat(one(row(2, null, "DELIVERED", null, AT, null, null), LOAD).problem())
                .isEqualTo(Problem.MISSING_FIELDS);
        assertThat(one(row(2, "WB-1", null, null, AT, null, null), LOAD).problem()).isEqualTo(Problem.MISSING_FIELDS);
        assertThat(one(row(2, "WB-1", "DELIVERED", null, null, null, null), LOAD).problem())
                .isEqualTo(Problem.MISSING_FIELDS);
        assertThat(one(row(2, "WB-1", "DELIVERED", null, AT, "x".repeat(501), null), LOAD).problem())
                .isEqualTo(Problem.TEXT_TOO_LONG);
        assertThat(one(row(2, "WB-1", "SHIPPED", null, AT, null, null), LOAD).problem())
                .isEqualTo(Problem.INVALID_OUTCOME);
        assertThat(one(row(2, "WB-1", "RETURNED", null, AT, null, null), LOAD).problem())
                .isEqualTo(Problem.INVALID_OUTCOME);
        assertThat(one(row(2, "WB-1", "DELIVERED", null, "sáng nay", null, null), LOAD).problem())
                .isEqualTo(Problem.INVALID_TIME);
        // 10:06 local is six minutes after NOW; five are a clock's allowance.
        assertThat(one(row(2, "WB-1", "DELIVERED", null, "2026-11-12 10:06", null, null), LOAD).problem())
                .isEqualTo(Problem.TIME_IN_FUTURE);
        assertThat(one(row(2, "WB-1", "DELIVERED", null, "2026-11-12 10:04", null, null), LOAD).accepted()).isTrue();
    }

    @Test
    @DisplayName("a number no vehicle the importer sees has, one two vehicles on the road have, a vehicle that has not "
            + "left, a shipment that is not on it")
    void refusesALineForItsVehicle() {
        assertThat(one(row(2, "WB-9", "DELIVERED", null, AT, null, null), LOAD).problem())
                .isEqualTo(Problem.WAYBILL_NOT_FOUND);
        assertThat(one(row(2, "WB-1", "DELIVERED", null, AT, null, null)).problem())
                .isEqualTo(Problem.WAYBILL_NOT_FOUND);
        assertThat(one(row(2, "WB-1", "DELIVERED", null, AT, null, null), LOAD,
                load("LD-2", LoadStatus.DISPATCHED, S2)).problem()).isEqualTo(Problem.WAYBILL_AMBIGUOUS);

        LoadFacts loading = load("LD-3", LoadStatus.LOADING, S1);
        Verdict notLeft = one(row(2, "WB-1", "DELIVERED", null, AT, null, null), loading);
        assertThat(notLeft.problem()).isEqualTo(Problem.LOAD_NOT_DISPATCHED);
        assertThat(notLeft.load()).isEqualTo(loading);
        // The number of a vehicle not yet gone and of one on the road: the one on the road is meant.
        assertThat(one(row(2, "WB-1", "DELIVERED", null, AT, null, null), loading, LOAD).load()).isEqualTo(LOAD);
        assertThat(one(row(2, "WB-1", "DELIVERED", null, AT, null, null), load("LD-4", LoadStatus.DISPATCHED))
                .problem()).isEqualTo(Problem.LOAD_NOT_DISPATCHED);

        assertThat(one(row(2, "WB-1", "DELIVERED", null, AT, null, "SO-9-S1"), LOAD).problem())
                .isEqualTo(Problem.SHIPMENT_NOT_ON_LOAD);
    }

    @Test
    @DisplayName("an outcome the shipment's state does not take is refused; one it already has is accepted and changes "
            + "nothing; delivered on a retry after a failure is allowed (09 §5)")
    void checksTheShipmentsState() {
        ShipmentFacts delivered = new ShipmentFacts(UUID.randomUUID(), "SO-3-S1", ShipmentStatus.DELIVERED);
        ShipmentFacts failed = new ShipmentFacts(UUID.randomUUID(), "SO-4-S1", ShipmentStatus.DELIVERY_FAILED);
        ShipmentFacts returned = new ShipmentFacts(UUID.randomUUID(), "SO-5-S1", ShipmentStatus.RETURNED);
        LoadFacts load = load("LD-5", LoadStatus.DISPATCHED, delivered, failed, returned);

        assertThat(one(row(2, "WB-1", "DELIVERY_FAILED", "REFUSED", AT, null, "SO-3-S1"), load).problem())
                .isEqualTo(Problem.OUTCOME_NOT_ALLOWED);
        assertThat(one(row(2, "WB-1", "DELIVERED", null, AT, null, "SO-5-S1"), load).problem())
                .isEqualTo(Problem.OUTCOME_NOT_ALLOWED);
        // A line about the whole vehicle is about each shipment on it: one that cannot take the outcome refuses it.
        assertThat(one(row(2, "WB-1", "DELIVERED", null, AT, null, null), load).problem())
                .isEqualTo(Problem.OUTCOME_NOT_ALLOWED);

        Verdict already = one(row(2, "WB-1", "DELIVERED", null, AT, null, "SO-3-S1"), load);
        assertThat(already.accepted()).isTrue();
        assertThat(already.changes()).isFalse();
        Verdict retried = one(row(2, "WB-1", "DELIVERED", null, AT, null, "SO-4-S1"), load);
        assertThat(retried.accepted()).isTrue();
        assertThat(retried.changes()).isTrue();
    }

    @Test
    @DisplayName("two lines about one shipment are both refused, as is a line about a shipment beside one about its "
            + "whole vehicle; a line refused for something else makes no other line a repeat")
    void refusesRepeatsInTheFile() {
        List<Verdict> sameShipment = DeliveryOutcomeCheck.check(List.of(
                row(2, "WB-1", "DELIVERED", null, AT, null, "SO-1-S1"),
                row(3, "WB-1", "DELIVERY_FAILED", "REFUSED", AT, null, "SO-1-S1"),
                row(4, "WB-1", "DELIVERED", null, AT, null, "SO-2-S1")), Map.of("WB-1", List.of(LOAD)), NOW);
        assertThat(sameShipment).extracting(Verdict::problem)
                .containsExactly(Problem.DUPLICATE_IN_FILE, Problem.DUPLICATE_IN_FILE, null);

        List<Verdict> wholeAndOne = DeliveryOutcomeCheck.check(List.of(
                row(2, "WB-1", "DELIVERED", null, AT, null, null),
                row(3, "WB-1", "DELIVERED", null, AT, null, "SO-2-S1")), Map.of("WB-1", List.of(LOAD)), NOW);
        assertThat(wholeAndOne).extracting(Verdict::problem)
                .containsExactly(Problem.DUPLICATE_IN_FILE, Problem.DUPLICATE_IN_FILE);

        List<Verdict> oneBad = DeliveryOutcomeCheck.check(List.of(
                row(2, "WB-1", "SHIPPED", null, AT, null, "SO-1-S1"),
                row(3, "WB-1", "DELIVERED", null, AT, null, "SO-1-S1")), Map.of("WB-1", List.of(LOAD)), NOW);
        assertThat(oneBad).extracting(Verdict::problem).containsExactly(Problem.INVALID_OUTCOME, null);
    }
}
