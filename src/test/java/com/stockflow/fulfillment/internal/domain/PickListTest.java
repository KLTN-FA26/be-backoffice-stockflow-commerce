package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** SCRUM-267/274/275 as plain rules: planning lines from holds, confirming by scan, completing, putting back. */
class PickListTest {

    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID HN = UUID.randomUUID();
    private static final UUID PICKER = UUID.randomUUID();
    private static final String BARCODE = "8930000000011";

    private static ErrorCode refusal(ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private static PickList.Hold hold(UUID id, String sku, String location, UUID warehouse, int quantity) {
        return new PickList.Hold(id, sku, location, warehouse, null, quantity, true);
    }

    private static PickLine openLine(String sku, String location, int quantity) {
        return new PickLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), sku, location, null, 1, quantity,
                0, PickLineStatus.OPEN, null, null, null, null, null, 0, null, null, null);
    }

    private static PickLine pickedLine(String sku, String location, int quantity) {
        return new PickLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), sku, location, null, 1, quantity,
                quantity, PickLineStatus.PICKED, PICKER, NOW, NOW, location, sku, 0, null, null, null);
    }

    private static PickList picking(PickLine... lines) {
        return new PickList(UUID.randomUUID(), HCM, PickStatus.PICKING, false, List.of(lines));
    }

    @Nested
    @DisplayName("planning the lines")
    class Planning {

        @Test
        @DisplayName("one line per live hold of a stock line, in walking order by location; print lines get none")
        void oneLinePerHoldOfAStockLine() {
            UUID sofa = UUID.randomUUID();
            UUID cup = UUID.randomUUID();
            UUID printed = UUID.randomUUID();
            UUID h1 = UUID.randomUUID();
            UUID h2 = UUID.randomUUID();
            UUID h3 = UUID.randomUUID();
            UUID hPrint = UUID.randomUUID();
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.PENDING, false, List.of());

            List<PickLine> added = list.plan(List.of(
                            new PickList.OrderLine(sofa, "SOFA-3S-GREY", false, List.of(h1, h2)),
                            new PickList.OrderLine(cup, "CUP-12", false, List.of(h3)),
                            new PickList.OrderLine(printed, "CUP-PRINT", true, List.of(hPrint))),
                    Map.of(h1, hold(h1, "SOFA-3S-GREY", "HCM-B01-1-A", HCM, 2),
                            h2, hold(h2, "SOFA-3S-GREY", "HCM-A01-2-B", HCM, 1),
                            h3, hold(h3, "CUP-12", "HCM-A02-1-A", HCM, 50),
                            hPrint, hold(hPrint, "CUP-PRINT", "HCM-C01-1-A", HCM, 100)),
                    UUID::randomUUID);

            assertThat(added).extracting(PickLine::locationCode)
                    .containsExactly("HCM-A01-2-B", "HCM-A02-1-A", "HCM-B01-1-A");
            assertThat(added).extracting(PickLine::sequenceNo).containsExactly(1, 2, 3);
            assertThat(added).extracting(PickLine::reservationId).containsExactly(h2, h3, h1);
            assertThat(added).extracting(PickLine::orderLineId).containsExactly(sofa, cup, sofa);
            assertThat(added).extracting(PickLine::allocatedQty).containsExactly(1, 50, 2);
            assertThat(added).allSatisfy(line -> assertThat(line.status()).isEqualTo(PickLineStatus.OPEN));
            assertThat(added).noneMatch(line -> line.sku().equals("CUP-PRINT"));
            assertThat(list.lines()).hasSize(3);
        }

        @Test
        @DisplayName("planning again adds nothing: lines are planned once")
        void planningIsIdempotent() {
            UUID h1 = UUID.randomUUID();
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.PENDING, false, List.of());
            var order = List.of(new PickList.OrderLine(UUID.randomUUID(), "SOFA-3S-GREY", false, List.of(h1)));
            var holds = Map.of(h1, hold(h1, "SOFA-3S-GREY", "HCM-A01-2-B", HCM, 1));

            assertThat(list.plan(order, holds, UUID::randomUUID)).hasSize(1);
            assertThat(list.plan(order, holds, UUID::randomUUID)).isEmpty();
            assertThat(list.lines()).hasSize(1);
        }

        @Test
        @DisplayName("holds no longer live (consumed, released, expired) are not planned")
        void closedHoldsAreSkipped() {
            UUID live = UUID.randomUUID();
            UUID released = UUID.randomUUID();
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.PENDING, false, List.of());

            List<PickLine> added = list.plan(
                    List.of(new PickList.OrderLine(UUID.randomUUID(), "SOFA-3S-GREY", false, List.of(live, released))),
                    Map.of(live, hold(live, "SOFA-3S-GREY", "HCM-A01-2-B", HCM, 1),
                            released, new PickList.Hold(released, "SOFA-3S-GREY", "HCM-A02-1-A", HCM, null, 1, false)),
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
                            new PickList.OrderLine(UUID.randomUUID(), "SOFA-3S-GREY", false, List.of(here)),
                            new PickList.OrderLine(UUID.randomUUID(), "TABLE-OAK-160", false, List.of(there))),
                    Map.of(here, hold(here, "SOFA-3S-GREY", "HCM-A01-2-B", HCM, 1),
                            there, hold(there, "TABLE-OAK-160", "HN-A01-1-A", HN, 1)),
                    UUID::randomUUID));

            assertThat(thrown).isInstanceOfSatisfying(BusinessException.class, refusal -> {
                assertThat(refusal.errorCode()).isEqualTo(ErrorCode.FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE);
                assertThat(refusal.getMessage()).contains("TABLE-OAK-160").doesNotContain("SOFA-3S-GREY");
            });
            assertThat(list.lines()).isEmpty();
        }

        @Test
        @DisplayName("a task already picked, short or cancelled gets no lines")
        void onlyPendingOrPickingTasksArePlanned() {
            UUID h1 = UUID.randomUUID();
            var order = List.of(new PickList.OrderLine(UUID.randomUUID(), "SOFA-3S-GREY", false, List.of(h1)));
            var holds = Map.of(h1, hold(h1, "SOFA-3S-GREY", "HCM-A01-2-B", HCM, 1));
            for (PickStatus status : List.of(PickStatus.PICKED, PickStatus.SHORT, PickStatus.CANCELLED)) {
                var list = new PickList(UUID.randomUUID(), HCM, status, false, List.of());
                assertThat(list.plan(order, holds, UUID::randomUUID)).as(status.name()).isEmpty();
            }
            assertThat(new PickList(UUID.randomUUID(), HCM, PickStatus.PICKING, false, List.of())
                    .plan(order, holds, UUID::randomUUID)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("confirming a line by scan")
    class Confirming {

        @Test
        @DisplayName("the right location, the SKU in any case and the whole allocation pick the line")
        void scanBySku() {
            PickLine line = openLine("SOFA-3S-GREY", "HCM-A01-2-B", 2);
            var list = picking(line);

            var outcome = list.confirm(line.id(), " hcm-a01-2-b ", "sofa-3s-grey", 2, BARCODE, PICKER, NOW);

            assertThat(outcome.repeated()).isFalse();
            assertThat(line.status()).isEqualTo(PickLineStatus.PICKED);
            assertThat(line.pickedQty()).isEqualTo(2);
            assertThat(line.pickedBy()).isEqualTo(PICKER);
            assertThat(line.pickedAt()).isEqualTo(NOW);
            assertThat(line.scannedLocationCode()).isEqualTo("hcm-a01-2-b");
            assertThat(line.scannedCode()).isEqualTo("sofa-3s-grey");
            assertThat(line.consumedAt()).isNull();
            line.recordConsumed(NOW);
            assertThat(line.consumedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("the barcode is as good as the SKU")
        void scanByBarcode() {
            PickLine line = openLine("SOFA-3S-GREY", "HCM-A01-2-B", 1);

            picking(line).confirm(line.id(), "HCM-A01-2-B", BARCODE, 1, BARCODE, PICKER, NOW);

            assertThat(line.status()).isEqualTo(PickLineStatus.PICKED);
            assertThat(line.scannedCode()).isEqualTo(BARCODE);
        }

        @Test
        @DisplayName("each wrong scan is refused with its own code and leaves the line open")
        void wrongScans() {
            PickLine line = openLine("SOFA-3S-GREY", "HCM-A01-2-B", 2);
            var list = picking(line);

            assertThat(refusal(() -> list.confirm(line.id(), "HCM-A02-1-A", "SOFA-3S-GREY", 2, BARCODE, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_LOCATION_MISMATCH);
            assertThat(refusal(() -> list.confirm(line.id(), "HCM-A01-2-B", "TABLE-OAK-160", 2, BARCODE, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_ITEM_MISMATCH);
            assertThat(refusal(() -> list.confirm(line.id(), "HCM-A01-2-B", "8930000000099", 2, BARCODE, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_ITEM_MISMATCH);
            // A SKU without a barcode: a barcode-looking scan is simply not this item.
            assertThat(refusal(() -> list.confirm(line.id(), "HCM-A01-2-B", BARCODE, 2, null, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_ITEM_MISMATCH);
            assertThat(refusal(() -> list.confirm(line.id(), "HCM-A01-2-B", "SOFA-3S-GREY", 3, BARCODE, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_QUANTITY_EXCEEDS_ALLOCATION);
            assertThat(refusal(() -> list.confirm(line.id(), "HCM-A01-2-B", "SOFA-3S-GREY", 1, BARCODE, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_QUANTITY_SHORT);
            assertThat(refusal(() -> list.confirm(UUID.randomUUID(), "HCM-A01-2-B", "SOFA-3S-GREY", 2, BARCODE,
                    PICKER, NOW))).isEqualTo(ErrorCode.NOT_FOUND);

            assertThat(line.status()).isEqualTo(PickLineStatus.OPEN);
            assertThat(line.pickedQty()).isZero();
        }

        @Test
        @DisplayName("the same scan again is answered as done; a different scan of a picked line is refused")
        void repeatedConfirmation() {
            PickLine line = openLine("SOFA-3S-GREY", "HCM-A01-2-B", 2);
            var list = picking(line);
            list.confirm(line.id(), "HCM-A01-2-B", "SOFA-3S-GREY", 2, BARCODE, PICKER, NOW);

            var again = list.confirm(line.id(), "hcm-a01-2-b", BARCODE, 2, BARCODE, PICKER, NOW.plusSeconds(5));

            assertThat(again.repeated()).isTrue();
            assertThat(line.pickedAt()).isEqualTo(NOW);
            assertThat(refusal(() -> list.confirm(line.id(), "HCM-A01-2-B", "SOFA-3S-GREY", 1, BARCODE, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
            assertThat(refusal(() -> list.confirm(line.id(), "HCM-A02-1-A", "SOFA-3S-GREY", 2, BARCODE, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
        }

        @Test
        @DisplayName("a repeat is answered even after the task completed; a first scan is not")
        void repeatAfterCompletion() {
            PickLine picked = pickedLine("SOFA-3S-GREY", "HCM-A01-2-B", 1);
            PickLine open = openLine("TABLE-OAK-160", "HCM-B01-1-A", 1);
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.PICKED, false, List.of(picked, open));

            assertThat(list.confirm(picked.id(), "HCM-A01-2-B", "SOFA-3S-GREY", 1, null, PICKER, NOW).repeated())
                    .isTrue();
            assertThat(refusal(() -> list.confirm(open.id(), "HCM-B01-1-A", "TABLE-OAK-160", 1, null, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
        }

        @Test
        @DisplayName("lines are confirmed only while the task is PICKING")
        void onlyWhilePicking() {
            for (PickStatus status : List.of(PickStatus.PENDING, PickStatus.PICKED, PickStatus.CANCELLED)) {
                PickLine line = openLine("SOFA-3S-GREY", "HCM-A01-2-B", 1);
                var list = new PickList(UUID.randomUUID(), HCM, status, false, List.of(line));
                assertThat(refusal(() -> list.confirm(line.id(), "HCM-A01-2-B", "SOFA-3S-GREY", 1, null, PICKER, NOW)))
                        .as(status.name()).isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
            }
        }

        @Test
        @DisplayName("a cancelled line cannot be picked")
        void cancelledLine() {
            PickLine line = openLine("SOFA-3S-GREY", "HCM-A01-2-B", 1);
            var list = picking(line);
            assertThat(list.cancelOpenLines()).containsExactly(line);
            assertThat(refusal(() -> list.confirm(line.id(), "HCM-A01-2-B", "SOFA-3S-GREY", 1, null, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
        }
    }

    @Nested
    @DisplayName("completing the task")
    class Completing {

        @Test
        @DisplayName("refused while a line is open, accepted once all are picked")
        void everyLinePicked() {
            PickLine first = openLine("SOFA-3S-GREY", "HCM-A01-2-B", 1);
            PickLine second = openLine("TABLE-OAK-160", "HCM-B01-1-A", 1);
            var list = picking(first, second);
            list.confirm(first.id(), "HCM-A01-2-B", "SOFA-3S-GREY", 1, null, PICKER, NOW);

            Throwable thrown = catchThrowable(list::requireAllPicked);
            assertThat(thrown).isInstanceOfSatisfying(BusinessException.class, refusal -> {
                assertThat(refusal.errorCode()).isEqualTo(ErrorCode.PICK_LINES_INCOMPLETE);
                assertThat(refusal.getMessage()).contains("1 of 2");
            });

            list.confirm(second.id(), "HCM-B01-1-A", "TABLE-OAK-160", 1, null, PICKER, NOW);
            list.requireAllPicked();
        }

        @Test
        @DisplayName("a task with no lines (print lines only) has nothing to wait for")
        void noLines() {
            picking().requireAllPicked();
        }
    }

    @Nested
    @DisplayName("putting goods back (docs 07 BR-05)")
    class PuttingBack {

        private final PickList.PutBackTarget bin = new PickList.PutBackTarget("HCM-A01-2-B", HCM, true, true);

        @Test
        @DisplayName("a picked line of a cancelled task goes back in full onto an active bin of its warehouse, once")
        void putBackOnce() {
            PickLine line = pickedLine("SOFA-3S-GREY", "HCM-A01-2-B", 2);
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, true, List.of(line));

            var outcome = list.putBack(line.id(), bin, PICKER, NOW);

            assertThat(outcome.repeated()).isFalse();
            assertThat(line.status()).isEqualTo(PickLineStatus.PUT_BACK);
            assertThat(line.putBackQty()).isEqualTo(2);
            assertThat(line.putBackLocationCode()).isEqualTo("HCM-A01-2-B");
            assertThat(line.putBackBy()).isEqualTo(PICKER);
            assertThat(line.putBackAt()).isEqualTo(NOW);

            var again = list.putBack(line.id(), new PickList.PutBackTarget("HCM-A02-1-A", HCM, true, true), PICKER,
                    NOW.plusSeconds(60));
            assertThat(again.repeated()).isTrue();
            assertThat(line.putBackLocationCode()).isEqualTo("HCM-A01-2-B");
            assertThat(line.putBackAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("nothing to put back unless the task was cancelled after picking and the line was picked")
        void notRequired() {
            PickLine picked = pickedLine("SOFA-3S-GREY", "HCM-A01-2-B", 1);
            assertThat(refusal(() -> picking(picked).putBack(picked.id(), bin, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PUT_BACK_NOT_REQUIRED);
            PickLine other = pickedLine("SOFA-3S-GREY", "HCM-A01-2-B", 1);
            assertThat(refusal(() -> new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, false, List.of(other))
                    .putBack(other.id(), bin, PICKER, NOW))).isEqualTo(ErrorCode.PUT_BACK_NOT_REQUIRED);
            PickLine open = openLine("SOFA-3S-GREY", "HCM-A01-2-B", 1);
            assertThat(refusal(() -> new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, true, List.of(open))
                    .putBack(open.id(), bin, PICKER, NOW))).isEqualTo(ErrorCode.PUT_BACK_NOT_REQUIRED);
            assertThat(refusal(() -> new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, true, List.of(open))
                    .putBack(UUID.randomUUID(), bin, PICKER, NOW))).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(picked.status()).isEqualTo(PickLineStatus.PICKED);
        }

        @Test
        @DisplayName("only an active bin of the task's own warehouse takes the goods")
        void targetMustBeAnActiveBinOfTheWarehouse() {
            List<PickList.PutBackTarget> wrong = new ArrayList<>();
            wrong.add(null);                                                                // unknown code
            wrong.add(new PickList.PutBackTarget("HN-A01-1-A", HN, true, true));            // other warehouse
            wrong.add(new PickList.PutBackTarget("HCM-RCV01", HCM, false, true));           // an area, not a bin
            wrong.add(new PickList.PutBackTarget("HCM-A01-1-A", HCM, true, false));         // blocked bin
            for (PickList.PutBackTarget target : wrong) {
                PickLine line = pickedLine("SOFA-3S-GREY", "HCM-A01-2-B", 1);
                var list = new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, true, List.of(line));
                assertThat(refusal(() -> list.putBack(line.id(), target, PICKER, NOW)))
                        .as(String.valueOf(target)).isEqualTo(ErrorCode.PUT_BACK_LOCATION_INVALID);
                assertThat(line.status()).isEqualTo(PickLineStatus.PICKED);
            }
        }

        @Test
        @DisplayName("cancelling the task cancels the open lines and leaves picked ones for the put-back")
        void cancellingLeavesPickedLines() {
            PickLine picked = pickedLine("SOFA-3S-GREY", "HCM-A01-2-B", 1);
            PickLine open = openLine("TABLE-OAK-160", "HCM-B01-1-A", 1);
            var list = picking(picked, open);

            assertThat(list.cancelOpenLines()).containsExactly(open);
            assertThat(open.status()).isEqualTo(PickLineStatus.CANCELLED);
            assertThat(picked.status()).isEqualTo(PickLineStatus.PICKED);
            assertThat(list.cancelOpenLines()).isEmpty();
        }
    }
}
