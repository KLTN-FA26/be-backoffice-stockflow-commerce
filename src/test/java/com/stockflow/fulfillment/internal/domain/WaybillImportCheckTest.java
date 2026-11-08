package com.stockflow.fulfillment.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each line of a waybill import checked against its load (kltn-docs 09 §4.4 step 2, BR-25, E18): the reason a line is
 * refused, and NEW / OVERWRITE / SAME for one that applies.
 */
class WaybillImportCheckTest {

    private static final UUID GHN = UUID.randomUUID();
    private static final UUID AHA = UUID.randomUUID();

    private static WaybillImportCheck.LoadFacts load(String code, LoadStatus status, UUID carrier, String number) {
        return new WaybillImportCheck.LoadFacts(UUID.randomUUID(), code, status, carrier, carrier == AHA, number,
                true);
    }

    private static WaybillImportFile.Row row(int line, String code, String number) {
        return new WaybillImportFile.Row(line, code, number, null, null, null);
    }

    private static List<String> results(List<WaybillImportCheck.Verdict> verdicts) {
        return verdicts.stream().map(v -> v.row().line() + ":" + (v.problem() == null ? v.outcome() : v.problem()))
                .toList();
    }

    @Test
    @DisplayName("a line applies as NEW, OVERWRITE (\"sẽ ghi đè\") or SAME to a booked, loading load of a carrier "
            + "that takes typed numbers")
    void applying() {
        Map<String, WaybillImportCheck.LoadFacts> loads = Map.of(
                "LD-1", load("LD-1", LoadStatus.VEHICLE_BOOKED, GHN, null),
                "LD-2", load("LD-2", LoadStatus.LOADING, GHN, "WB-OLD"),
                "LD-3", load("LD-3", LoadStatus.VEHICLE_BOOKED, GHN, "WB-3"));

        List<WaybillImportCheck.Verdict> verdicts = WaybillImportCheck.check(List.of(row(2, "LD-1", "WB-1"),
                row(3, "LD-2", "WB-2"), row(4, "LD-3", "WB-3")), loads, Map.of(GHN, Map.of("WB-3", held("LD-3"))),
                null);

        assertThat(results(verdicts)).containsExactly("2:NEW", "3:OVERWRITE", "4:SAME");
        assertThat(verdicts).allMatch(WaybillImportCheck.Verdict::applies);
        // On the import an overwrite applies only when the dispatcher confirmed it after the preview (BR-25).
        assertThat(results(WaybillImportCheck.check(List.of(row(3, "LD-2", "WB-2")), loads, Map.of(), Set.of())))
                .containsExactly("3:OVERWRITE_NOT_CONFIRMED");
        assertThat(results(WaybillImportCheck.check(List.of(row(3, "LD-2", "WB-2")), loads, Map.of(), Set.of(3))))
                .containsExactly("3:OVERWRITE");
    }

    private static WaybillImportCheck.Holder held(String code) {
        return new WaybillImportCheck.Holder(code, true);
    }

    @Test
    @DisplayName("every reason a line is refused, each line on its own")
    void refusals() {
        WaybillImportCheck.LoadFacts outside = new WaybillImportCheck.LoadFacts(UUID.randomUUID(), "LD-X",
                LoadStatus.VEHICLE_BOOKED, GHN, false, null, false);
        Map<String, WaybillImportCheck.LoadFacts> loads = Map.of(
                "LD-P", load("LD-P", LoadStatus.PLANNED, null, null),
                "LD-C", load("LD-C", LoadStatus.CANCELLED, GHN, null),
                "LD-D", load("LD-D", LoadStatus.DISPATCHED, GHN, "WB-D"),
                "LD-A", load("LD-A", LoadStatus.VEHICLE_BOOKED, AHA, null),
                "LD-R", load("LD-R", LoadStatus.VEHICLE_BOOKED, GHN, null),
                "LD-F1", load("LD-F1", LoadStatus.VEHICLE_BOOKED, GHN, null),
                "LD-F2", load("LD-F2", LoadStatus.LOADING, GHN, null),
                "LD-T", load("LD-T", LoadStatus.VEHICLE_BOOKED, GHN, null),
                "LD-X", outside);

        List<WaybillImportCheck.Verdict> verdicts = WaybillImportCheck.check(List.of(
                row(2, null, "WB-1"),
                row(3, "LD-P", null),
                row(4, "LD-P", "WB 4"),
                new WaybillImportFile.Row(5, "LD-P", "WB-5", "5".repeat(21), null, null),
                row(6, "LD-NONE", "WB-6"),
                row(7, "LD-X", "WB-7"),
                row(8, "LD-R", "WB-8"),
                row(9, "LD-R", "WB-9"),
                row(10, "LD-P", "WB-10"),
                row(11, "LD-C", "WB-11"),
                row(12, "LD-D", "WB-12"),
                row(13, "LD-A", "WB-13"),
                row(14, "LD-F1", "WB-SAME"),
                row(15, "LD-F2", "WB-SAME"),
                row(16, "LD-T", "WB-HELD")), loads, Map.of(GHN, Map.of("WB-HELD", held("LD-OTHER"))), null);

        // Lines 3-5 name LD-P but are refused on their own: they do not make line 10 a repeat.
        assertThat(results(verdicts)).containsExactly("2:MISSING_FIELDS", "3:MISSING_FIELDS", "4:INVALID_WAYBILL",
                "5:INVALID_VEHICLE_DETAILS", "6:LOAD_NOT_FOUND", "7:LOAD_OUT_OF_SCOPE", "8:LOAD_REPEATED",
                "9:LOAD_REPEATED", "10:LOAD_NOT_BOOKED", "11:LOAD_CANCELLED", "12:LOAD_LEFT",
                "13:WAYBILL_FROM_CARRIER_API", "14:DUPLICATE_IN_FILE", "15:DUPLICATE_IN_FILE",
                "16:DUPLICATE_IN_CARRIER");
        assertThat(verdicts.get(14).holder()).isEqualTo("LD-OTHER");
        assertThat(verdicts.get(5).load()).isNull();
        assertThat(verdicts).noneMatch(WaybillImportCheck.Verdict::applies);
    }

    @Test
    @DisplayName("the same number for two carriers is not a duplicate (E19); a load holding its own number is SAME")
    void numbersAreCarriers() {
        UUID other = UUID.randomUUID();
        Map<String, WaybillImportCheck.LoadFacts> loads = Map.of(
                "LD-1", load("LD-1", LoadStatus.VEHICLE_BOOKED, GHN, null),
                "LD-2", load("LD-2", LoadStatus.VEHICLE_BOOKED, other, "WB-1"));

        assertThat(results(WaybillImportCheck.check(List.of(row(2, "LD-1", "WB-1"), row(3, "LD-2", "WB-1")), loads,
                Map.of(other, Map.of("WB-1", held("LD-2"))), null))).containsExactly("2:NEW", "3:SAME");
    }

    @Test
    @DisplayName("a load of another warehouse is never named: not as the line's load, not as the number's holder, and "
            + "its line makes no other line a duplicate")
    void otherWarehousesStayUnseen() {
        WaybillImportCheck.LoadFacts outside = new WaybillImportCheck.LoadFacts(UUID.randomUUID(), "LD-X",
                LoadStatus.VEHICLE_BOOKED, GHN, false, null, false);
        Map<String, WaybillImportCheck.LoadFacts> loads = Map.of(
                "LD-1", load("LD-1", LoadStatus.VEHICLE_BOOKED, GHN, null),
                "LD-2", load("LD-2", LoadStatus.VEHICLE_BOOKED, GHN, null),
                "LD-X", outside);

        List<WaybillImportCheck.Verdict> verdicts = WaybillImportCheck.check(List.of(row(2, "LD-X", "WB-1"),
                row(3, "LD-1", "WB-1"), row(4, "LD-2", "WB-HIDDEN"), row(5, "LD-2", null)), loads,
                Map.of(GHN, Map.of("WB-HIDDEN", new WaybillImportCheck.Holder("LD-ELSEWHERE", false))), null);

        assertThat(results(verdicts)).containsExactly("2:LOAD_OUT_OF_SCOPE", "3:NEW", "4:DUPLICATE_IN_CARRIER",
                "5:MISSING_FIELDS");
        assertThat(verdicts.get(0).load()).isNull();
        assertThat(verdicts.get(2).holder()).isNull();
    }
}
