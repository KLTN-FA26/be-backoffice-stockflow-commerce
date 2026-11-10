package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.stockflow.fulfillment.internal.domain.PickLineFixtures.LPN;
import static com.stockflow.fulfillment.internal.domain.PickLineFixtures.NOW;
import static com.stockflow.fulfillment.internal.domain.PickLineFixtures.OTHER_LPN;
import static com.stockflow.fulfillment.internal.domain.PickLineFixtures.PICKER;
import static com.stockflow.fulfillment.internal.domain.PickLineFixtures.hold;
import static com.stockflow.fulfillment.internal.domain.PickLineFixtures.open;
import static com.stockflow.fulfillment.internal.domain.PickLineFixtures.picked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Team plan 4C-4 (kltn-docs 07 BR-04, BR-05, BR-10) as plain rules: planning one line per carton, confirming by the bin
 * and the carton's LPN only, completing, putting a carton back whole.
 */
class PickListTest {

    private static final UUID HCM = UUID.randomUUID();
    private static final UUID HN = UUID.randomUUID();
    private static final String BARCODE = "8930000000011";

    private static ErrorCode refusal(ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private static PickList picking(PickLine... lines) {
        return new PickList(UUID.randomUUID(), HCM, PickStatus.PICKING, false, List.of(lines));
    }

    @Nested
    @DisplayName("planning the lines")
    class Planning {

        @Test
        @DisplayName("one line per live hold, in walking order by location, each in its hold's carton (07 BR-04, BR-06)")
        void oneLinePerHold() {
            UUID sofa = UUID.randomUUID();
            UUID cup = UUID.randomUUID();
            UUID printed = UUID.randomUUID();
            UUID h1 = UUID.randomUUID();
            UUID h2 = UUID.randomUUID();
            UUID h3 = UUID.randomUUID();
            UUID hPrint = UUID.randomUUID();
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.PENDING, false, List.of());

            List<PickLine> added = list.plan(List.of(
                            new PickList.OrderLine(sofa, "SOFA-3S-GREY", List.of(h1, h2)),
                            new PickList.OrderLine(cup, "CUP-12", List.of(h3)),
                            new PickList.OrderLine(printed, "CUP-PRINT", List.of(hPrint))),
                    Map.of(h1, hold(h1, "SOFA-3S-GREY", "HCM-B01-1-A", HCM, 2, "LPN-1"),
                            h2, hold(h2, "SOFA-3S-GREY", "HCM-A01-2-B", HCM, 1, "LPN-2"),
                            h3, hold(h3, "CUP-12", "HCM-A02-1-A", HCM, 50, "LPN-3"),
                            hPrint, hold(hPrint, "CUP-PRINT", "HCM-C01-1-A", HCM, 100, "LPN-4")),
                    UUID::randomUUID);

            assertThat(added).extracting(PickLine::locationCode)
                    .containsExactly("HCM-A01-2-B", "HCM-A02-1-A", "HCM-B01-1-A", "HCM-C01-1-A");
            assertThat(added).extracting(PickLine::sequenceNo).containsExactly(1, 2, 3, 4);
            assertThat(added).extracting(PickLine::reservationId).containsExactly(h2, h3, h1, hPrint);
            assertThat(added).extracting(PickLine::orderLineId).containsExactly(sofa, cup, sofa, printed);
            assertThat(added).extracting(PickLine::allocatedQty).containsExactly(1, 50, 2, 100);
            assertThat(added).extracting(PickLine::lpn).containsExactly("LPN-2", "LPN-3", "LPN-1", "LPN-4");
            assertThat(added).allSatisfy(line -> {
                assertThat(line.status()).isEqualTo(PickLineStatus.OPEN);
                assertThat(line.wholeCarton()).isTrue();
                assertThat(line.shortReason()).isNull();
            });
            assertThat(list.lines()).hasSize(4);
        }

        @Test
        @DisplayName("a live hold on no carton is refused, naming the SKU, and nothing is planned (4C-2: no loose holds)")
        void looseHoldIsRefused() {
            UUID boxed = UUID.randomUUID();
            UUID loose = UUID.randomUUID();
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.PENDING, false, List.of());

            Throwable thrown = catchThrowable(() -> list.plan(List.of(
                            new PickList.OrderLine(UUID.randomUUID(), "CUP-12", List.of(boxed)),
                            new PickList.OrderLine(UUID.randomUUID(), "MUG-9", List.of(loose))),
                    Map.of(boxed, hold(boxed, "CUP-12", "HCM-A02-1-A", HCM, 10, "LPN-1"),
                            loose, hold(loose, "MUG-9", "HCM-A03-1-A", HCM, 3, null)),
                    UUID::randomUUID));

            assertThat(thrown).isInstanceOfSatisfying(BusinessException.class, refusal -> {
                assertThat(refusal.errorCode()).isEqualTo(ErrorCode.PICK_HOLD_NOT_IN_CARTON);
                assertThat(refusal.getMessage()).contains("MUG-9").doesNotContain("CUP-12");
            });
            assertThat(list.lines()).isEmpty();
        }

        @Test
        @DisplayName("planning again adds nothing: lines are planned once")
        void planningIsIdempotent() {
            UUID h1 = UUID.randomUUID();
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.PENDING, false, List.of());
            var order = List.of(new PickList.OrderLine(UUID.randomUUID(), "SOFA-3S-GREY", List.of(h1)));
            var holds = Map.of(h1, hold(h1, "SOFA-3S-GREY", "HCM-A01-2-B", HCM, 1, LPN));

            assertThat(list.plan(order, holds, UUID::randomUUID)).hasSize(1);
            assertThat(list.plan(order, holds, UUID::randomUUID)).isEmpty();
            assertThat(list.lines()).hasSize(1);
        }

        @Test
        @DisplayName("holds no longer live (consumed, released, expired) are not planned, loose or not")
        void closedHoldsAreSkipped() {
            UUID live = UUID.randomUUID();
            UUID released = UUID.randomUUID();
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.PENDING, false, List.of());

            List<PickLine> added = list.plan(
                    List.of(new PickList.OrderLine(UUID.randomUUID(), "SOFA-3S-GREY", List.of(live, released))),
                    Map.of(live, hold(live, "SOFA-3S-GREY", "HCM-A01-2-B", HCM, 1, LPN),
                            released, new PickList.Hold(released, "SOFA-3S-GREY", "HCM-A02-1-A", HCM, null, 1, false,
                                    null, false)),
                    UUID::randomUUID);

            assertThat(added).singleElement().satisfies(line -> assertThat(line.reservationId()).isEqualTo(live));
        }

        @Test
        @DisplayName("a hold in another warehouse than the task's is refused, naming the SKU, and nothing is planned")
        void holdInAnotherWarehouse() {
            UUID here = UUID.randomUUID();
            UUID there = UUID.randomUUID();
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.PENDING, false, List.of());

            Throwable thrown = catchThrowable(() -> list.plan(List.of(
                            new PickList.OrderLine(UUID.randomUUID(), "SOFA-3S-GREY", List.of(here)),
                            new PickList.OrderLine(UUID.randomUUID(), "TABLE-OAK-160", List.of(there))),
                    Map.of(here, hold(here, "SOFA-3S-GREY", "HCM-A01-2-B", HCM, 1, "LPN-1"),
                            there, hold(there, "TABLE-OAK-160", "HN-A01-1-A", HN, 1, "LPN-2")),
                    UUID::randomUUID));

            assertThat(thrown).isInstanceOfSatisfying(BusinessException.class, refusal -> {
                assertThat(refusal.errorCode()).isEqualTo(ErrorCode.FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE);
                assertThat(refusal.getMessage()).contains("TABLE-OAK-160").doesNotContain("SOFA-3S-GREY");
            });
            assertThat(list.lines()).isEmpty();
        }

        @Test
        @DisplayName("a task already picked or cancelled gets no lines")
        void onlyPendingOrPickingTasksArePlanned() {
            UUID h1 = UUID.randomUUID();
            var order = List.of(new PickList.OrderLine(UUID.randomUUID(), "SOFA-3S-GREY", List.of(h1)));
            var holds = Map.of(h1, hold(h1, "SOFA-3S-GREY", "HCM-A01-2-B", HCM, 1, LPN));
            for (PickStatus status : List.of(PickStatus.PICKED, PickStatus.CANCELLED)) {
                var list = new PickList(UUID.randomUUID(), HCM, status, false, List.of());
                assertThat(list.plan(order, holds, UUID::randomUUID)).as(status.name()).isEmpty();
            }
            assertThat(new PickList(UUID.randomUUID(), HCM, PickStatus.PICKING, false, List.of())
                    .plan(order, holds, UUID::randomUUID)).hasSize(1);
        }

        @Test
        @DisplayName("a pick line is a carton: it cannot be opened without an LPN")
        void lineWithoutLpnCannotBeOpened() {
            assertThatThrownBy(() -> PickLine.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CUP-12",
                    "HCM-A02-1-A", null, 1, 10, null, true)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("confirming a line by scan (07 section 4.2 step 5, BR-04, BR-10)")
    class Confirming {

        @Test
        @DisplayName("the line's bin, then its carton's LPN in any case, pick the whole carton with no quantity")
        void scanByLpn() {
            PickLine line = open("SOFA-3S-GREY", "HCM-A01-2-B", 2, LPN);
            var list = picking(line);

            var outcome = list.confirm(line.id(), " hcm-a01-2-b ", " lpn-20261113-000001 ", PICKER, NOW);

            assertThat(outcome.repeated()).isFalse();
            assertThat(line.status()).isEqualTo(PickLineStatus.PICKED);
            assertThat(line.pickedQty()).as("the whole carton, never a typed count").isEqualTo(2);
            assertThat(line.pickedBy()).isEqualTo(PICKER);
            assertThat(line.pickedAt()).isEqualTo(NOW);
            assertThat(line.scannedLocationCode()).isEqualTo("hcm-a01-2-b");
            assertThat(line.scannedCode()).as("the carton's own LPN is recorded").isEqualTo(LPN);
            assertThat(line.consumedAt()).isNull();
            line.recordConsumed(NOW);
            assertThat(line.consumedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("the SKU, the barcode and another carton's LPN are each PICK_ITEM_MISMATCH and leave the line open")
        void everythingButTheOwnLpnIsRefused() {
            PickLine line = open("SOFA-3S-GREY", "HCM-A01-2-B", 2, LPN);
            var list = picking(line);

            for (String scan : new String[] {"SOFA-3S-GREY", BARCODE, OTHER_LPN, "LPN", "", "   ", null}) {
                assertThat(refusal(() -> list.confirm(line.id(), "HCM-A01-2-B", scan, PICKER, NOW)))
                        .as("scan %s", scan).isEqualTo(ErrorCode.PICK_ITEM_MISMATCH);
            }
            assertThat(line.status()).isEqualTo(PickLineStatus.OPEN);
            assertThat(line.pickedQty()).isZero();
            assertThat(line.pickedBy()).isNull();
        }

        @Test
        @DisplayName("the refusal names the carton to take, the wrong bin is its own code, an unknown line is NOT_FOUND")
        void refusalCodes() {
            PickLine line = open("SOFA-3S-GREY", "HCM-A01-2-B", 2, LPN);
            var list = picking(line);

            Throwable thrown = catchThrowable(() -> list.confirm(line.id(), "HCM-A01-2-B", "SOFA-3S-GREY", PICKER, NOW));
            assertThat(thrown).hasMessageContaining(LPN);
            assertThat(refusal(() -> list.confirm(line.id(), "HCM-A02-1-A", LPN, PICKER, NOW)))
                    .as("the bin is checked before the carton").isEqualTo(ErrorCode.PICK_LOCATION_MISMATCH);
            assertThat(refusal(() -> list.confirm(line.id(), null, LPN, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_LOCATION_MISMATCH);
            assertThat(refusal(() -> list.confirm(UUID.randomUUID(), "HCM-A01-2-B", LPN, PICKER, NOW)))
                    .isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(line.status()).isEqualTo(PickLineStatus.OPEN);
        }

        @Test
        @DisplayName("the same scan again is answered as done; another scan of a picked line is refused")
        void repeatedConfirmation() {
            PickLine line = open("SOFA-3S-GREY", "HCM-A01-2-B", 2, LPN);
            var list = picking(line);
            list.confirm(line.id(), "HCM-A01-2-B", LPN, PICKER, NOW);

            var again = list.confirm(line.id(), "hcm-a01-2-b", LPN.toLowerCase(), PICKER, NOW.plusSeconds(5));

            assertThat(again.repeated()).isTrue();
            assertThat(line.pickedAt()).as("the repeat changes nothing").isEqualTo(NOW);
            assertThat(refusal(() -> list.confirm(line.id(), "HCM-A01-2-B", OTHER_LPN, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
            assertThat(refusal(() -> list.confirm(line.id(), "HCM-A01-2-B", "SOFA-3S-GREY", PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
            assertThat(refusal(() -> list.confirm(line.id(), "HCM-A02-1-A", LPN, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
        }

        @Test
        @DisplayName("a repeat is answered even after the task completed; a first scan is not")
        void repeatAfterCompletion() {
            PickLine picked = picked("SOFA-3S-GREY", "HCM-A01-2-B", 1, LPN);
            PickLine open = open("TABLE-OAK-160", "HCM-B01-1-A", 1, OTHER_LPN);
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.PICKED, false, List.of(picked, open));

            assertThat(list.confirm(picked.id(), "HCM-A01-2-B", LPN, PICKER, NOW).repeated()).isTrue();
            assertThat(refusal(() -> list.confirm(open.id(), "HCM-B01-1-A", OTHER_LPN, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
        }

        @Test
        @DisplayName("lines are confirmed only while the task is PICKING")
        void onlyWhilePicking() {
            for (PickStatus status : List.of(PickStatus.PENDING, PickStatus.PICKED, PickStatus.CANCELLED)) {
                PickLine line = open("SOFA-3S-GREY", "HCM-A01-2-B", 1, LPN);
                var list = new PickList(UUID.randomUUID(), HCM, status, false, List.of(line));
                assertThat(refusal(() -> list.confirm(line.id(), "HCM-A01-2-B", LPN, PICKER, NOW)))
                        .as(status.name()).isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
            }
        }

        @Test
        @DisplayName("a cancelled line cannot be picked")
        void cancelledLine() {
            PickLine line = open("SOFA-3S-GREY", "HCM-A01-2-B", 1, LPN);
            var list = picking(line);
            assertThat(list.cancelOpenLines()).containsExactly(line);
            assertThat(refusal(() -> list.confirm(line.id(), "HCM-A01-2-B", LPN, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
        }

        @Test
        @DisplayName("a line of before cartons, with no LPN, can be confirmed by no scan")
        void lineWithoutCartonCannotBeConfirmed() {
            PickLine history = PickLineFixtures.line("SOFA-3S-GREY", "HCM-A01-2-B", 2, 0, PickLineStatus.OPEN, 0, null,
                    null, 0);
            var list = picking(history);

            assertThat(refusal(() -> list.confirm(history.id(), "HCM-A01-2-B", "SOFA-3S-GREY", PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
            assertThat(refusal(() -> list.confirm(history.id(), "HCM-A01-2-B", null, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
            assertThat(history.status()).isEqualTo(PickLineStatus.OPEN);
        }
    }

    @Nested
    @DisplayName("completing the task")
    class Completing {

        @Test
        @DisplayName("refused while a line is open, accepted once all are picked")
        void everyLinePicked() {
            PickLine first = open("SOFA-3S-GREY", "HCM-A01-2-B", 1, LPN);
            PickLine second = open("TABLE-OAK-160", "HCM-B01-1-A", 1, OTHER_LPN);
            var list = picking(first, second);
            list.confirm(first.id(), "HCM-A01-2-B", LPN, PICKER, NOW);

            Throwable thrown = catchThrowable(list::requireAllPicked);
            assertThat(thrown).isInstanceOfSatisfying(BusinessException.class, refusal -> {
                assertThat(refusal.errorCode()).isEqualTo(ErrorCode.PICK_LINES_INCOMPLETE);
                assertThat(refusal.getMessage()).contains("1 of 2");
            });

            list.confirm(second.id(), "HCM-B01-1-A", OTHER_LPN, PICKER, NOW);
            list.requireAllPicked();
        }

        @Test
        @DisplayName("a task with no lines (an order with nothing left to pick) has nothing to wait for")
        void noLines() {
            picking().requireAllPicked();
        }
    }

    @Nested
    @DisplayName("putting a carton back (07 BR-05, section 4.5 step 14)")
    class PuttingBack {

        private final PickList.PutBackTarget bin = new PickList.PutBackTarget("HCM-A01-2-B", HCM, true, true);

        @Test
        @DisplayName("a picked line of a cancelled task goes back whole onto an active bin of its warehouse, once")
        void putBackOnce() {
            PickLine line = picked("SOFA-3S-GREY", "HCM-A01-2-B", 2, LPN);
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, true, List.of(line));

            var outcome = list.putBack(line.id(), " lpn-20261113-000001", bin, PICKER, NOW);

            assertThat(outcome.repeated()).isFalse();
            assertThat(line.status()).isEqualTo(PickLineStatus.PUT_BACK);
            assertThat(line.putBackQty()).isEqualTo(2);
            assertThat(line.putBackLocationCode()).isEqualTo("HCM-A01-2-B");
            assertThat(line.putBackBy()).isEqualTo(PICKER);
            assertThat(line.putBackAt()).isEqualTo(NOW);

            var again = list.putBack(line.id(), LPN, new PickList.PutBackTarget("HCM-A02-1-A", HCM, true, true), PICKER,
                    NOW.plusSeconds(60));
            assertThat(again.repeated()).isTrue();
            assertThat(line.putBackLocationCode()).isEqualTo("HCM-A01-2-B");
            assertThat(line.putBackAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("another carton's LPN, the SKU or nothing is PICK_ITEM_MISMATCH and the line stays picked")
        void wrongScan() {
            PickLine line = picked("SOFA-3S-GREY", "HCM-A01-2-B", 2, LPN);
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, true, List.of(line));

            for (String scan : new String[] {OTHER_LPN, "SOFA-3S-GREY", BARCODE, "", null}) {
                assertThat(refusal(() -> list.putBack(line.id(), scan, bin, PICKER, NOW)))
                        .as("scan %s", scan).isEqualTo(ErrorCode.PICK_ITEM_MISMATCH);
            }
            assertThat(line.status()).isEqualTo(PickLineStatus.PICKED);
        }

        @Test
        @DisplayName("a put-back line answers a repeat only to its own LPN")
        void repeatNeedsTheLpn() {
            PickLine line = picked("SOFA-3S-GREY", "HCM-A01-2-B", 2, LPN);
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, true, List.of(line));
            list.putBack(line.id(), LPN, bin, PICKER, NOW);

            assertThat(refusal(() -> list.putBack(line.id(), OTHER_LPN, bin, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PUT_BACK_NOT_REQUIRED);
        }

        @Test
        @DisplayName("nothing to put back unless the task was cancelled after picking and the line was picked")
        void notRequired() {
            PickLine picked = picked("SOFA-3S-GREY", "HCM-A01-2-B", 1, LPN);
            assertThat(refusal(() -> picking(picked).putBack(picked.id(), LPN, bin, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PUT_BACK_NOT_REQUIRED);
            PickLine other = picked("SOFA-3S-GREY", "HCM-A01-2-B", 1, LPN);
            assertThat(refusal(() -> new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, false, List.of(other))
                    .putBack(other.id(), LPN, bin, PICKER, NOW))).isEqualTo(ErrorCode.PUT_BACK_NOT_REQUIRED);
            PickLine open = open("SOFA-3S-GREY", "HCM-A01-2-B", 1, LPN);
            assertThat(refusal(() -> new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, true, List.of(open))
                    .putBack(open.id(), LPN, bin, PICKER, NOW))).isEqualTo(ErrorCode.PUT_BACK_NOT_REQUIRED);
            assertThat(refusal(() -> new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, true, List.of(open))
                    .putBack(UUID.randomUUID(), LPN, bin, PICKER, NOW))).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(picked.status()).isEqualTo(PickLineStatus.PICKED);
        }

        @Test
        @DisplayName("a loose line of before cartons has no carton to put back: PUT_BACK_NOT_REQUIRED")
        void looseHistoryLine() {
            PickLine loose = PickLineFixtures.line("SOFA-3S-GREY", "HCM-A01-2-B", 2, 2, PickLineStatus.PICKED, 0, null,
                    null, 0);
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, true, List.of(loose));

            assertThat(refusal(() -> list.putBack(loose.id(), "SOFA-3S-GREY", bin, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PUT_BACK_NOT_REQUIRED);
            assertThat(loose.status()).isEqualTo(PickLineStatus.PICKED);
        }

        @Test
        @DisplayName("a carton part of which went to quarantine at packing does not go back: STOCK_PACKAGE_TAKEN_WHOLE")
        void partlyDamagedCartonIsRefused() {
            PickLine some = PickLineFixtures.pickedPartlyDamaged("CUP-12", "HCM-A01-2-B", 10, LPN, 3);
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, true, List.of(some));

            Throwable thrown = catchThrowable(() -> list.putBack(some.id(), LPN, bin, PICKER, NOW));

            assertThat(thrown).isInstanceOfSatisfying(BusinessException.class, refusal -> {
                assertThat(refusal.errorCode()).isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
                assertThat(refusal.getMessage()).contains(LPN).contains("3");
            });
            assertThat(some.status()).isEqualTo(PickLineStatus.PICKED);
        }

        @Test
        @DisplayName("a carton all of which was found damaged has nothing to put back")
        void allDamagedCartonHasNothingToPutBack() {
            PickLine all = PickLineFixtures.pickedPartlyDamaged("CUP-12", "HCM-A01-2-B", 10, LPN, 10);
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, true, List.of(all));

            assertThat(refusal(() -> list.putBack(all.id(), LPN, bin, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PUT_BACK_NOT_REQUIRED);
        }

        @Test
        @DisplayName("only an active bin of the task's own warehouse takes the carton")
        void targetMustBeAnActiveBinOfTheWarehouse() {
            List<PickList.PutBackTarget> wrong = new ArrayList<>();
            wrong.add(null);                                                                // unknown code
            wrong.add(new PickList.PutBackTarget("HN-A01-1-A", HN, true, true));            // other warehouse
            wrong.add(new PickList.PutBackTarget("HCM-RCV01", HCM, false, true));           // an area, not a bin
            wrong.add(new PickList.PutBackTarget("HCM-A01-1-A", HCM, true, false));         // blocked bin
            for (PickList.PutBackTarget target : wrong) {
                PickLine line = picked("SOFA-3S-GREY", "HCM-A01-2-B", 1, LPN);
                var list = new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, true, List.of(line));
                assertThat(refusal(() -> list.putBack(line.id(), LPN, target, PICKER, NOW)))
                        .as(String.valueOf(target)).isEqualTo(ErrorCode.PUT_BACK_LOCATION_INVALID);
                assertThat(line.status()).isEqualTo(PickLineStatus.PICKED);
            }
        }

        @Test
        @DisplayName("cancelling the task cancels the open lines and leaves picked ones for the put-back")
        void cancellingLeavesPickedLines() {
            PickLine picked = picked("SOFA-3S-GREY", "HCM-A01-2-B", 1, LPN);
            PickLine open = open("TABLE-OAK-160", "HCM-B01-1-A", 1, OTHER_LPN);
            var list = picking(picked, open);

            assertThat(list.cancelOpenLines()).containsExactly(open);
            assertThat(open.status()).isEqualTo(PickLineStatus.CANCELLED);
            assertThat(picked.status()).isEqualTo(PickLineStatus.PICKED);
            assertThat(list.cancelOpenLines()).isEmpty();
        }
    }
}
