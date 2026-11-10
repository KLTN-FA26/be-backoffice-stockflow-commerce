package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.fulfillment.internal.domain.CartonCheck.Carton;
import com.stockflow.fulfillment.internal.domain.CartonCheck.Completion;
import com.stockflow.fulfillment.internal.domain.CartonCheck.PickedCarton;
import com.stockflow.fulfillment.internal.domain.CartonCheck.RemovalReason;
import com.stockflow.fulfillment.internal.domain.CartonCheck.Spec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The carton check session (team plan 4C-6; kltn-docs 08 §4-§5, BR-01...BR-11): both state machines - the session's and
 * the carton's - and each guard in the order the document states it.
 */
class CartonCheckTest {

    private static final Instant NOW = Instant.parse("2026-11-13T08:00:00Z");
    private static final UUID WORKER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();
    private static final UUID LINE_A = UUID.randomUUID();
    private static final UUID LINE_B = UUID.randomUUID();
    private static final Spec SPEC = new Spec(new BigDecimal("60"), new BigDecimal("40"), new BigDecimal("40"),
            new BigDecimal("12.5"), true);
    private static final int DIVISOR = 6000;


    private PickedCarton picked(String lpn, UUID orderLine, String sku, int units) {
        return new PickedCarton(UUID.randomUUID(), lpn, orderLine, sku, "LOT1", units);
    }

    private CartonCheck session(PackStatus status, int lastNo) {
        return new CartonCheck(UUID.randomUUID(), "SO-1", status, null, null, null, null, List.of(), lastNo);
    }

    private CartonCheck pending() {
        return session(PackStatus.PENDING, 0);
    }

    /** Started by WORKER, with cartons A1 and A2 (SKU-A, 10 each, line A) and B1 (SKU-B, 5, line B), all TO_CHECK. */
    private CartonCheck started() {
        CartonCheck check = pending();
        check.start(WORKER, false, NOW);
        check.receive(List.of(picked("LPN-A1", LINE_A, "SKU-A", 10), picked("LPN-A2", LINE_A, "SKU-A", 10),
                picked("LPN-B1", LINE_B, "SKU-B", 5)), UUID::randomUUID);
        return check;
    }

    private static Map<String, Spec> specs() {
        return Map.of("SKU-A", SPEC, "SKU-B", SPEC);
    }

    private static Map<UUID, Integer> wanted(int a, int b) {
        Map<UUID, Integer> wanted = new LinkedHashMap<>();
        wanted.put(LINE_A, a);
        wanted.put(LINE_B, b);
        return wanted;
    }

    private static Map<UUID, String> skus() {
        return Map.of(LINE_A, "SKU-A", LINE_B, "SKU-B");
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    private void labelAll(CartonCheck check) {
        check.check("LPN-A1", WORKER, NOW);
        check.check("LPN-A2", WORKER, NOW);
        check.check("LPN-B1", WORKER, NOW);
        check.label(null, specs(), DIVISOR, WORKER, NOW);
    }

    @Nested
    @DisplayName("receive: the picked cartons become TO_CHECK cartons")
    class Receive {

        @Test
        @DisplayName("numbers continue the order's highest, label code is <order>-C<n>, LPN upper-cased")
        void numbering() {
            CartonCheck check = session(PackStatus.PENDING, 3);
            List<Carton> added = check.receive(List.of(picked("lpn-x1", LINE_A, "SKU-A", 4),
                    picked("LPN-X2", LINE_A, "SKU-A", 4)), UUID::randomUUID);
            assertThat(added).extracting(Carton::packageNo).containsExactly(4, 5);
            assertThat(added).extracting(Carton::labelCode).containsExactly("SO-1-C4", "SO-1-C5");
            assertThat(added).extracting(Carton::lpn).containsExactly("LPN-X1", "LPN-X2");
            assertThat(added).extracting(Carton::status).containsOnly(PackageStatus.TO_CHECK);
            assertThat(check.changedCartons()).hasSize(2);
        }

        @Test
        @DisplayName("again with the same pick lines adds nothing (idempotent)")
        void idempotent() {
            CartonCheck check = pending();
            PickedCarton one = picked("LPN-1", LINE_A, "SKU-A", 4);
            assertThat(check.receive(List.of(one), UUID::randomUUID)).hasSize(1);
            assertThat(check.receive(List.of(one), UUID::randomUUID)).isEmpty();
            assertThat(check.cartons()).hasSize(1);
        }

        @Test
        @DisplayName("a COMPLETED or CANCELLED session receives nothing")
        void closedSessionsReceiveNothing() {
            assertThat(session(PackStatus.COMPLETED, 0).receive(List.of(picked("LPN-1", LINE_A, "SKU-A", 1)),
                    UUID::randomUUID)).isEmpty();
            assertThat(session(PackStatus.CANCELLED, 0).receive(List.of(picked("LPN-1", LINE_A, "SKU-A", 1)),
                    UUID::randomUUID)).isEmpty();
        }

        @Test
        @DisplayName("a carton holds at least one unit")
        void emptyCarton() {
            assertThatThrownBy(() -> picked("LPN-1", LINE_A, "SKU-A", 0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("session state machine: PENDING -> IN_PROGRESS -> COMPLETED, or CANCELLED (08 §5.1)")
    class SessionStates {

        @Test
        @DisplayName("start: PENDING becomes IN_PROGRESS with who started it and when")
        void start() {
            CartonCheck check = pending();
            assertThat(check.start(WORKER, false, NOW)).isTrue();
            assertThat(check.status()).isEqualTo(PackStatus.IN_PROGRESS);
            assertThat(check.startedBy()).isEqualTo(WORKER);
            assertThat(check.startedAt()).isEqualTo(NOW);
            assertThat(check.sessionChanged()).isTrue();
        }

        @Test
        @DisplayName("start again by the owner or a manager is answered as it is; anyone else gets PACKING_TAKEN")
        void startAgain() {
            CartonCheck check = pending();
            check.start(WORKER, false, NOW);
            check.saved();
            assertThat(check.start(WORKER, false, NOW.plusSeconds(5))).isFalse();
            assertThat(check.start(MANAGER, true, NOW.plusSeconds(5))).isFalse();
            assertThat(check.startedBy()).isEqualTo(WORKER);
            assertThat(check.sessionChanged()).isFalse();
            assertCode(() -> check.start(OTHER, false, NOW), ErrorCode.PACKING_TAKEN);
        }

        @Test
        @DisplayName("a cancelled session cannot be started: PACK_STATE_CONFLICT")
        void startCancelled() {
            assertCode(() -> session(PackStatus.CANCELLED, 0).start(WORKER, false, NOW), ErrorCode.PACK_STATE_CONFLICT);
        }

        @Test
        @DisplayName("requireWorkable: PENDING, COMPLETED and CANCELLED sessions are not worked; the owner and a manager are")
        void workable() {
            assertCode(() -> pending().requireWorkable(WORKER, false), ErrorCode.PACK_STATE_CONFLICT);
            assertCode(() -> session(PackStatus.COMPLETED, 0).requireWorkable(WORKER, false),
                    ErrorCode.PACK_STATE_CONFLICT);
            assertCode(() -> session(PackStatus.CANCELLED, 0).requireWorkable(WORKER, true),
                    ErrorCode.PACK_STATE_CONFLICT);
            CartonCheck check = started();
            check.requireWorkable(WORKER, false);
            check.requireWorkable(MANAGER, true);
            assertCode(() -> check.requireWorkable(OTHER, false), ErrorCode.PACKING_TAKEN);
        }

        @Test
        @DisplayName("completed in full: COMPLETED with who and when; a partial completion leaves it IN_PROGRESS")
        void shipInFullAndInPart() {
            CartonCheck full = started();
            labelAll(full);
            Completion all = full.complete(false, null, false, wanted(20, 5), skus());
            full.ship(all, UUID.randomUUID(), WORKER, NOW.plusSeconds(60));
            assertThat(full.status()).isEqualTo(PackStatus.COMPLETED);
            assertThat(full.completedBy()).isEqualTo(WORKER);
            assertThat(full.completedAt()).isEqualTo(NOW.plusSeconds(60));

            CartonCheck part = started();
            labelAll(part);
            Completion some = part.complete(true, "rest later", true, wanted(30, 5), skus());
            assertThat(some.partial()).isTrue();
            part.ship(some, UUID.randomUUID(), WORKER, NOW);
            assertThat(part.status()).isEqualTo(PackStatus.IN_PROGRESS);
            assertThat(part.completedAt()).isNull();
        }

        @Test
        @DisplayName("a COMPLETED session cannot be completed again, and is not started anew")
        void completedStays() {
            CartonCheck check = session(PackStatus.COMPLETED, 0);
            assertThat(check.start(WORKER, false, NOW)).isFalse();
            assertCode(() -> check.complete(false, null, false, Map.of(), Map.of()), ErrorCode.PACK_STATE_CONFLICT);
        }
    }

    @Nested
    @DisplayName("check: scanning an LPN (08 §4.2 step 3, BR-05)")
    class Check {

        @Test
        @DisplayName("TO_CHECK becomes CHECKED with who and when")
        void scan() {
            CartonCheck check = started();
            CartonCheck.Scan scan = check.check("LPN-A1", WORKER, NOW);
            assertThat(scan.repeated()).isFalse();
            assertThat(scan.carton().status()).isEqualTo(PackageStatus.CHECKED);
            assertThat(scan.carton().checkedBy()).isEqualTo(WORKER);
            assertThat(scan.carton().checkedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("a repeat changes nothing; lower case and spaces are tolerated")
        void repeat() {
            CartonCheck check = started();
            check.check("LPN-A1", WORKER, NOW);
            check.saved();
            CartonCheck.Scan again = check.check("  lpn-a1 ", OTHER, NOW.plusSeconds(9));
            assertThat(again.repeated()).isTrue();
            assertThat(again.carton().checkedBy()).isEqualTo(WORKER);
            assertThat(again.carton().checkedAt()).isEqualTo(NOW);
            assertThat(check.changedCartons()).isEmpty();
        }

        @Test
        @DisplayName("a carton of no pick line of this task: CARTON_NOT_IN_TASK, and nothing is written")
        void unknownLpn() {
            CartonCheck check = started();
            check.saved();
            assertCode(() -> check.check("LPN-ELSEWHERE", WORKER, NOW), ErrorCode.CARTON_NOT_IN_TASK);
            assertCode(() -> check.check(null, WORKER, NOW), ErrorCode.CARTON_NOT_IN_TASK);
            assertThat(check.changedCartons()).isEmpty();
        }

        @Test
        @DisplayName("a removed carton is not scanned: PACK_STATE_CONFLICT")
        void removed() {
            CartonCheck check = started();
            check.remove("LPN-A1", null, WORKER, NOW);
            assertCode(() -> check.check("LPN-A1", WORKER, NOW), ErrorCode.PACK_STATE_CONFLICT);
        }

        @Test
        @DisplayName("a LABELED carton scanned again is a repeat")
        void labeledRepeat() {
            CartonCheck check = started();
            labelAll(check);
            assertThat(check.check("LPN-A1", WORKER, NOW).repeated()).isTrue();
            assertThat(check.carton("LPN-A1").orElseThrow().status()).isEqualTo(PackageStatus.LABELED);
        }
    }

    @Nested
    @DisplayName("label: BR-07 carton spec, BR-08 only a checked carton, BR-09 volumetric weight")
    class Label {

        @Test
        @DisplayName("with no LPN named, every CHECKED carton is labelled: spec size, full weight, version 1")
        void labelsAllChecked() {
            CartonCheck check = started();
            check.check("LPN-A1", WORKER, NOW);
            check.check("LPN-B1", WORKER, NOW);
            List<Carton> labelled = check.label(null, specs(), DIVISOR, MANAGER, NOW.plusSeconds(3));
            assertThat(labelled).extracting(Carton::lpn).containsExactlyInAnyOrder("LPN-A1", "LPN-B1");
            Carton a1 = check.carton("LPN-A1").orElseThrow();
            assertThat(a1.status()).isEqualTo(PackageStatus.LABELED);
            assertThat(a1.labelVersion()).isEqualTo(1);
            assertThat(a1.labeledBy()).isEqualTo(MANAGER);
            assertThat(a1.labeledAt()).isEqualTo(NOW.plusSeconds(3));
            assertThat(a1.lengthCm()).isEqualByComparingTo("60");
            assertThat(a1.weightKg()).isEqualByComparingTo("12.5");
            assertThat(a1.rotatable()).isTrue();
            assertThat(check.carton("LPN-A2").orElseThrow().status()).isEqualTo(PackageStatus.TO_CHECK);
        }

        @Test
        @DisplayName("volumetric weight = L x W x H / divisor, three decimals (60x40x40 / 6000 = 16.000)")
        void dimensionalWeight() {
            CartonCheck check = started();
            labelAll(check);
            assertThat(check.carton("LPN-A1").orElseThrow().dimWeightKg()).isEqualByComparingTo("16.000");
        }

        @Test
        @DisplayName("a tiny carton never rounds to nothing: floor 0.001")
        void dimensionalWeightFloor() {
            CartonCheck check = started();
            check.check("LPN-A1", WORKER, NOW);
            Spec tiny = new Spec(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("0.1"), false);
            check.label(List.of("LPN-A1"), Map.of("SKU-A", tiny), DIVISOR, WORKER, NOW);
            Carton carton = check.carton("LPN-A1").orElseThrow();
            assertThat(carton.dimWeightKg()).isEqualByComparingTo("0.001");
            assertThat(carton.rotatable()).isFalse();
        }

        @Test
        @DisplayName("naming a TO_CHECK carton: CARTON_NOT_CHECKED, nothing labelled")
        void toCheckRefused() {
            CartonCheck check = started();
            check.check("LPN-A1", WORKER, NOW);
            assertCode(() -> check.label(List.of("LPN-A1", "LPN-A2"), specs(), DIVISOR, WORKER, NOW),
                    ErrorCode.CARTON_NOT_CHECKED);
            assertThat(check.carton("LPN-A1").orElseThrow().status()).isEqualTo(PackageStatus.CHECKED);
        }

        @Test
        @DisplayName("nothing CHECKED to label: CARTON_NOT_CHECKED")
        void nothingToLabel() {
            assertCode(() -> started().label(null, specs(), DIVISOR, WORKER, NOW), ErrorCode.CARTON_NOT_CHECKED);
            assertCode(() -> started().label(List.of(), specs(), DIVISOR, WORKER, NOW), ErrorCode.CARTON_NOT_CHECKED);
        }

        @Test
        @DisplayName("a SKU without a spec, or an unmeasured one, stops every label and names every such SKU (BR-07)")
        void unmeasured() {
            CartonCheck check = started();
            labelNothing(check);
            Spec noWeight = new Spec(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, null, true);
            assertThatThrownBy(() -> check.label(null, Map.of("SKU-A", noWeight), DIVISOR, WORKER, NOW))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CARTON_SPEC_UNMEASURED);
                        assertThat(e.getMessage()).contains("SKU-A").contains("SKU-B");
                    });
            assertThat(check.cartons()).extracting(Carton::status).containsOnly(PackageStatus.CHECKED);
            assertThat(check.cartons()).extracting(Carton::labelVersion).containsOnly(0);
        }

        private void labelNothing(CartonCheck check) {
            check.check("LPN-A1", WORKER, NOW);
            check.check("LPN-A2", WORKER, NOW);
            check.check("LPN-B1", WORKER, NOW);
        }

        @Test
        @DisplayName("a zero measure is no measure")
        void zeroMeasure() {
            assertThat(new Spec(BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, true).measured())
                    .isFalse();
            assertThat(new Spec(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, true).measured())
                    .isTrue();
        }

        @Test
        @DisplayName("naming a LABELED carton again is left as it is: version stays 1, nothing changes")
        void repeatOnLabeled() {
            CartonCheck check = started();
            check.check("LPN-A1", WORKER, NOW);
            check.label(List.of("LPN-A1"), specs(), DIVISOR, WORKER, NOW);
            check.saved();
            assertThat(check.label(List.of("LPN-A1"), specs(), DIVISOR, WORKER, NOW)).isEmpty();
            assertThat(check.carton("LPN-A1").orElseThrow().labelVersion()).isEqualTo(1);
            assertThat(check.changedCartons()).isEmpty();
        }

        @Test
        @DisplayName("a removed carton is not labelled")
        void removedRefused() {
            CartonCheck check = started();
            check.remove("LPN-A1", null, WORKER, NOW);
            assertCode(() -> check.label(List.of("LPN-A1"), specs(), DIVISOR, WORKER, NOW),
                    ErrorCode.PACK_STATE_CONFLICT);
        }

        @Test
        @DisplayName("a carton of another task: CARTON_NOT_IN_TASK")
        void unknownCarton() {
            assertCode(() -> started().label(List.of("LPN-OTHER"), specs(), DIVISOR, WORKER, NOW),
                    ErrorCode.CARTON_NOT_IN_TASK);
        }
    }

    @Nested
    @DisplayName("reprint: the label version goes up (08 BR-08)")
    class Reprint {

        @Test
        @DisplayName("version +1 each time; the measures follow the spec now while the carton is in no shipment")
        void reprint() {
            CartonCheck check = started();
            labelAll(check);
            Spec corrected = new Spec(new BigDecimal("30"), new BigDecimal("30"), new BigDecimal("30"),
                    new BigDecimal("9"), false);
            Carton carton = check.reprint("LPN-A1", corrected, DIVISOR);
            assertThat(carton.labelVersion()).isEqualTo(2);
            assertThat(carton.lengthCm()).isEqualByComparingTo("30");
            assertThat(carton.weightKg()).isEqualByComparingTo("9");
            assertThat(carton.dimWeightKg()).isEqualByComparingTo("4.500");
            assertThat(carton.rotatable()).isFalse();
            assertThat(check.reprint("LPN-A1", null, DIVISOR).labelVersion()).isEqualTo(3);
        }

        @Test
        @DisplayName("once shipped the measures stay what load planning took")
        void shippedKeepsMeasures() {
            CartonCheck check = started();
            labelAll(check);
            Completion all = check.complete(false, null, false, wanted(20, 5), skus());
            check.ship(all, UUID.randomUUID(), WORKER, NOW);
            Spec corrected = new Spec(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, true);
            Carton carton = check.reprint("LPN-A1", corrected, DIVISOR);
            assertThat(carton.labelVersion()).isEqualTo(2);
            assertThat(carton.lengthCm()).isEqualByComparingTo("60");
        }

        @Test
        @DisplayName("an unmeasured spec keeps the measures the carton has")
        void unmeasuredSpecKeeps() {
            CartonCheck check = started();
            labelAll(check);
            Carton carton = check.reprint("LPN-A1", new Spec(null, null, null, null, true), DIVISOR);
            assertThat(carton.lengthCm()).isEqualByComparingTo("60");
            assertThat(carton.labelVersion()).isEqualTo(2);
        }

        @Test
        @DisplayName("TO_CHECK, CHECKED and REMOVED cartons have no label to print again")
        void notLabeled() {
            CartonCheck check = started();
            check.check("LPN-A2", WORKER, NOW);
            check.remove("LPN-B1", null, WORKER, NOW);
            assertCode(() -> check.reprint("LPN-A1", SPEC, DIVISOR), ErrorCode.CARTON_NOT_CHECKED);
            assertCode(() -> check.reprint("LPN-A2", SPEC, DIVISOR), ErrorCode.CARTON_NOT_CHECKED);
            assertCode(() -> check.reprint("LPN-B1", SPEC, DIVISOR), ErrorCode.CARTON_NOT_CHECKED);
            assertCode(() -> check.reprint("LPN-NOPE", SPEC, DIVISOR), ErrorCode.CARTON_NOT_IN_TASK);
        }
    }

    @Nested
    @DisplayName("remove: REMOVED from TO_CHECK, CHECKED or an unshipped LABELED carton (08 §5.2)")
    class Remove {

        @Test
        @DisplayName("from TO_CHECK, CHECKED and LABELED; the reason is DAMAGED with who, when and the note")
        void fromEachState() {
            CartonCheck check = started();
            check.check("LPN-A2", WORKER, NOW);
            check.check("LPN-B1", WORKER, NOW);
            check.label(List.of("LPN-B1"), specs(), DIVISOR, WORKER, NOW);
            for (String lpn : List.of("LPN-A1", "LPN-A2", "LPN-B1")) {
                Carton removed = check.remove(lpn, "  dented corner ", MANAGER, NOW.plusSeconds(7));
                assertThat(removed.status()).isEqualTo(PackageStatus.REMOVED);
                assertThat(removed.removalReason()).isEqualTo(RemovalReason.DAMAGED);
                assertThat(removed.removedBy()).isEqualTo(MANAGER);
                assertThat(removed.removedAt()).isEqualTo(NOW.plusSeconds(7));
                assertThat(removed.removalNote()).isEqualTo("dented corner");
            }
        }

        @Test
        @DisplayName("a blank note is stored as none")
        void blankNote() {
            assertThat(started().remove("LPN-A1", "   ", WORKER, NOW).removalNote()).isNull();
        }

        @Test
        @DisplayName("a repeat is answered as it is and writes nothing")
        void repeat() {
            CartonCheck check = started();
            check.remove("LPN-A1", "first", WORKER, NOW);
            check.saved();
            Carton again = check.remove("LPN-A1", "second", OTHER, NOW.plusSeconds(5));
            assertThat(again.removalNote()).isEqualTo("first");
            assertThat(again.removedBy()).isEqualTo(WORKER);
            assertThat(check.changedCartons()).isEmpty();
        }

        @Test
        @DisplayName("a carton in a shipment: PACKAGE_SHIPPED")
        void shipped() {
            CartonCheck check = started();
            labelAll(check);
            Completion all = check.complete(false, null, false, wanted(20, 5), skus());
            check.ship(all, UUID.randomUUID(), WORKER, NOW);
            assertCode(() -> check.remove("LPN-A1", null, WORKER, NOW), ErrorCode.PACKAGE_SHIPPED);
        }

        @Test
        @DisplayName("another task's carton: CARTON_NOT_IN_TASK")
        void unknown() {
            assertCode(() -> started().remove("LPN-NOPE", null, WORKER, NOW), ErrorCode.CARTON_NOT_IN_TASK);
        }
    }

    @Nested
    @DisplayName("complete: the order of 08 §4.4 step 8")
    class Complete {

        @Test
        @DisplayName("a carton still TO_CHECK or CHECKED comes first: PACKING_INCOMPLETE listing the LPNs")
        void openCartonFirst() {
            CartonCheck check = started();
            check.check("LPN-A1", WORKER, NOW);
            // Nothing is labelled either: the open carton is the answer, not "nothing to ship".
            assertThatThrownBy(() -> check.complete(false, null, false, wanted(20, 5), skus()))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.PACKING_INCOMPLETE);
                        assertThat(e.getMessage()).contains("LPN-A1", "LPN-A2", "LPN-B1");
                    });
        }

        @Test
        @DisplayName("then nothing to ship: every carton removed gives PACK_NOTHING_TO_SHIP")
        void nothingToShip() {
            CartonCheck check = started();
            for (String lpn : List.of("LPN-A1", "LPN-A2", "LPN-B1")) {
                check.remove(lpn, null, WORKER, NOW);
            }
            assertCode(() -> check.complete(true, "all damaged", true, wanted(20, 5), skus()),
                    ErrorCode.PACK_NOTHING_TO_SHIP);
        }

        @Test
        @DisplayName("then missing units: PACKING_INCOMPLETE without a partial request, naming the SKU")
        void missingUnits() {
            CartonCheck check = started();
            check.remove("LPN-A2", null, WORKER, NOW);
            check.check("LPN-A1", WORKER, NOW);
            check.check("LPN-B1", WORKER, NOW);
            check.label(null, specs(), DIVISOR, WORKER, NOW);
            assertThatThrownBy(() -> check.complete(false, null, true, wanted(20, 5), skus()))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.PACKING_INCOMPLETE);
                        assertThat(e.getMessage()).contains("SKU-A").contains("10 of 20");
                    });
        }

        @Test
        @DisplayName("a partial request on an order that does not allow it: PACK_PARTIAL_NOT_ALLOWED")
        void partialNotAllowed() {
            CartonCheck check = shortOfOneCarton();
            assertCode(() -> check.complete(true, "one carton damaged", false, wanted(20, 5), skus()),
                    ErrorCode.PACK_PARTIAL_NOT_ALLOWED);
        }

        @Test
        @DisplayName("allowed but without a note: VALIDATION_FAILED; a blank note too")
        void partialNeedsNote() {
            CartonCheck check = shortOfOneCarton();
            assertCode(() -> check.complete(true, null, true, wanted(20, 5), skus()), ErrorCode.VALIDATION_FAILED);
            assertCode(() -> check.complete(true, "  ", true, wanted(20, 5), skus()), ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("allowed with a note: a partial completion carrying the labelled cartons and what is missing")
        void partialOk() {
            CartonCheck check = shortOfOneCarton();
            Completion completion = check.complete(true, "one carton damaged", true, wanted(20, 5), skus());
            assertThat(completion.partial()).isTrue();
            assertThat(completion.cartons()).extracting(Carton::lpn).containsExactlyInAnyOrder("LPN-A1", "LPN-B1");
            assertThat(completion.missing()).singleElement().satisfies(m -> {
                assertThat(m.sku()).isEqualTo("SKU-A");
                assertThat(m.wanted()).isEqualTo(20);
                assertThat(m.labeled()).isEqualTo(10);
            });
        }

        @Test
        @DisplayName("wanted less than a carton (units closed before) is covered: a full completion")
        void wantedCovered() {
            CartonCheck check = shortOfOneCarton();
            Completion completion = check.complete(false, null, false, wanted(10, 5), skus());
            assertThat(completion.partial()).isFalse();
            assertThat(completion.missing()).isEmpty();
        }

        @Test
        @DisplayName("an order line with no carton at all is missing too")
        void lineWithoutCarton() {
            CartonCheck check = started();
            check.remove("LPN-B1", null, WORKER, NOW);
            check.check("LPN-A1", WORKER, NOW);
            check.check("LPN-A2", WORKER, NOW);
            check.label(null, specs(), DIVISOR, WORKER, NOW);
            assertCode(() -> check.complete(false, null, true, wanted(20, 5), skus()), ErrorCode.PACKING_INCOMPLETE);
        }

        @Test
        @DisplayName("a carton already in a shipment does not ship again, and is not 'to ship'")
        void shippedCartonsStay() {
            CartonCheck check = started();
            labelAll(check);
            Completion first = check.complete(false, null, false, wanted(20, 5), skus());
            UUID shipment = UUID.randomUUID();
            check.ship(first, shipment, WORKER, NOW);
            assertThat(check.cartons()).extracting(Carton::shipmentId).containsOnly(shipment);
            // COMPLETED now: a second completion is a state conflict, not a second shipment.
            assertCode(() -> check.complete(false, null, false, wanted(20, 5), skus()), ErrorCode.PACK_STATE_CONFLICT);
        }

        private CartonCheck shortOfOneCarton() {
            CartonCheck check = started();
            check.remove("LPN-A2", null, WORKER, NOW);
            check.check("LPN-A1", WORKER, NOW);
            check.check("LPN-B1", WORKER, NOW);
            check.label(null, specs(), DIVISOR, WORKER, NOW);
            return check;
        }
    }

    @Nested
    @DisplayName("cancel: the order was cancelled before any label (08 BR-04)")
    class Cancel {

        @Test
        @DisplayName("every carton not shipped is REMOVED for ORDER_CANCELLED with no worker; the session is CANCELLED")
        void cancel() {
            CartonCheck check = started();
            check.check("LPN-A1", WORKER, NOW);
            List<Carton> removed = check.cancel(NOW.plusSeconds(1), java.util.Set.of());
            assertThat(removed).hasSize(3);
            assertThat(check.cartons()).allSatisfy(c -> {
                assertThat(c.status()).isEqualTo(PackageStatus.REMOVED);
                assertThat(c.removalReason()).isEqualTo(RemovalReason.ORDER_CANCELLED);
                assertThat(c.removedBy()).isNull();
            });
            assertThat(check.status()).isEqualTo(PackStatus.CANCELLED);
            assertThat(check.sessionChanged()).isTrue();
        }

        @Test
        @DisplayName("a shipped carton is untouched; an already removed one is not removed twice")
        void shippedAndRemovedUntouched() {
            CartonCheck check = started();
            check.remove("LPN-B1", "dented", WORKER, NOW);
            check.check("LPN-A1", WORKER, NOW);
            check.check("LPN-A2", WORKER, NOW);
            check.label(null, specs(), DIVISOR, WORKER, NOW);
            Completion completion = check.complete(true, "b1 damaged", true, wanted(20, 5), skus());
            UUID shipment = UUID.randomUUID();
            check.ship(completion, shipment, WORKER, NOW);
            assertThat(check.cancel(NOW, java.util.Set.of())).isEmpty();
            assertThat(check.carton("LPN-A1").orElseThrow().status()).isEqualTo(PackageStatus.LABELED);
            assertThat(check.carton("LPN-B1").orElseThrow().removalReason()).isEqualTo(RemovalReason.DAMAGED);
        }

        @Test
        @DisplayName("a carton of a shipment the cancellation cancelled is removed too and leaves that shipment; a carton of "
                + "a shipment that did not cancel (part of it left on a vehicle) keeps it")
        void cartonsOfCancelledShipments() {
            CartonCheck check = started();
            labelAll(check);
            Completion all = check.complete(false, null, false, wanted(20, 5), skus());
            UUID cancelled = UUID.randomUUID();
            UUID kept = UUID.randomUUID();
            // Two cartons ship in a shipment that cancels, the third in one that stays (shipments are per completion;
            // the split is the store's rows, here set by shipping twice through a second session view).
            check.ship(all, cancelled, WORKER, NOW);
            Carton stays = check.carton("LPN-B1").orElseThrow();
            // B1 was moved to another shipment, as a delivery batch would
            moveToShipment(check, stays, kept);

            List<Carton> removed = check.cancel(NOW, java.util.Set.of(cancelled));

            assertThat(removed).extracting(Carton::lpn).containsExactlyInAnyOrder("LPN-A1", "LPN-A2");
            assertThat(removed).allSatisfy(c -> {
                assertThat(c.status()).isEqualTo(PackageStatus.REMOVED);
                assertThat(c.removalReason()).isEqualTo(RemovalReason.ORDER_CANCELLED);
                assertThat(c.shipmentId()).as("a removed carton is in no shipment (ck_package_shipped)").isNull();
            });
            assertThat(stays.status()).isEqualTo(PackageStatus.LABELED);
            assertThat(stays.shipmentId()).isEqualTo(kept);
            assertThat(check.status()).isEqualTo(PackStatus.CANCELLED);
        }

        private void moveToShipment(CartonCheck check, Carton carton, UUID shipment) {
            // The domain ships whole completions; a carton in another shipment is a stored fact the store rehydrates.
            CartonCheck.Completion one = new CartonCheck.Completion(List.of(carton), true, List.of());
            check.ship(one, shipment, WORKER, NOW);
        }

        @Test
        @DisplayName("a cancelled shipment's id that matches no carton changes nothing for the cartons not in a shipment")
        void unknownCancelledShipment() {
            CartonCheck check = started();
            assertThat(check.cancel(NOW, java.util.Set.of(UUID.randomUUID()))).hasSize(3);
        }

        @Test
        @DisplayName("cancelling twice is harmless")
        void twice() {
            CartonCheck check = started();
            assertThat(check.cancel(NOW, java.util.Set.of())).hasSize(3);
            check.saved();
            assertThat(check.cancel(NOW, java.util.Set.of())).isEmpty();
            assertThat(check.sessionChanged()).isFalse();
        }
    }

    @Test
    @DisplayName("labeledUnitsOf counts only LABELED cartons of the line; changed cartons clear on saved()")
    void readingAndSaving() {
        CartonCheck check = started();
        check.check("LPN-A1", WORKER, NOW);
        check.label(null, specs(), DIVISOR, WORKER, NOW);
        assertThat(check.labeledUnitsOf(LINE_A)).isEqualTo(10);
        assertThat(check.labeledUnitsOf(LINE_B)).isZero();
        assertThat(check.changedCartons()).isNotEmpty();
        check.saved();
        assertThat(check.changedCartons()).isEmpty();
        assertThat(check.cartons()).allSatisfy(c -> assertThat(c.stored()).isTrue());
        assertThatThrownBy(() -> check.cartons().add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new ArrayList<>(check.cartons())).hasSize(3);
    }
}
