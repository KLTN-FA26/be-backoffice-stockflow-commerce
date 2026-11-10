package com.stockflow.fulfillment.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
 * Team plan 4C-5 (kltn-docs 07 section 4.2 step 5, section 4.3 steps 7-8, BR-07, BR-11) as plain rules: a STAGING
 * task stages its lines instead of picking them, moves to dispatch whole, takes a top-up after the last stop, and puts
 * a staged carton back when its order is cancelled.
 */
class PickListStagingTest {

    private static final UUID HCM = UUID.randomUUID();
    private static final String STAGING = "HCM-STG01";

    private static ErrorCode refusal(ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private static PickList stagingList(PickStatus status, PickLine... lines) {
        return new PickList(UUID.randomUUID(), HCM, status, false, STAGING, List.of(lines));
    }

    /** A STAGED line: the carton was taken whole and stands in the staging area, still held. */
    private static PickLine staged(String sku, String location, int quantity, String lpn) {
        return new PickLine(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), sku, location, "L1", 1, quantity,
                quantity, PickLineStatus.STAGED, PICKER, NOW, null, location, lpn, 0, null, null, null, 0, null, lpn,
                true, 0, null, 0, NOW, STAGING);
    }

    @Nested
    @DisplayName("confirming on a staging task")
    class Confirming {

        @Test
        @DisplayName("the line turns STAGED, whole, with when and where; it is not deducted")
        void confirmStages() {
            PickLine line = open("SOFA", "HCM-A01-2-B", 4, LPN);
            var list = stagingList(PickStatus.PICKING, line);

            var confirmation = list.confirm(line.id(), "hcm-a01-2-b", LPN.toLowerCase(), PICKER, NOW);

            assertThat(confirmation.repeated()).isFalse();
            assertThat(line.status()).isEqualTo(PickLineStatus.STAGED);
            assertThat(line.pickedQty()).isEqualTo(4);
            assertThat(line.pickedBy()).isEqualTo(PICKER);
            assertThat(line.stagedAt()).isEqualTo(NOW);
            assertThat(line.stagedLocationCode()).isEqualTo(STAGING);
            assertThat(line.consumedAt()).as("nothing is deducted until dispatch").isNull();
        }

        @Test
        @DisplayName("a repeat of the same scan is answered as done and changes nothing")
        void repeatIsDone() {
            PickLine line = open("SOFA", "HCM-A01-2-B", 4, LPN);
            var list = stagingList(PickStatus.PICKING, line);
            list.confirm(line.id(), "HCM-A01-2-B", LPN, PICKER, NOW);

            var again = list.confirm(line.id(), "HCM-A01-2-B", LPN, PICKER, NOW.plusSeconds(60));

            assertThat(again.repeated()).isTrue();
            assertThat(line.stagedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("the wrong carton or bin is refused as for a dispatch task, and nothing is staged")
        void scansAreStillChecked() {
            PickLine line = open("SOFA", "HCM-A01-2-B", 4, LPN);
            var list = stagingList(PickStatus.PICKING, line);

            assertThat(refusal(() -> list.confirm(line.id(), "HCM-A01-2-B", OTHER_LPN, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_ITEM_MISMATCH);
            assertThat(refusal(() -> list.confirm(line.id(), "HCM-A09-9-Z", LPN, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PICK_LOCATION_MISMATCH);
            assertThat(line.status()).isEqualTo(PickLineStatus.OPEN);
            assertThat(line.stagedAt()).isNull();
        }

        @Test
        @DisplayName("a list with no staging code (DISPATCH, or already moved) picks: PICKED, no staging facts")
        void noStagingCodePicks() {
            PickLine line = open("SOFA", "HCM-A01-2-B", 4, LPN);
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.PICKING, false, List.of(line));

            list.confirm(line.id(), "HCM-A01-2-B", LPN, PICKER, NOW);

            assertThat(line.status()).isEqualTo(PickLineStatus.PICKED);
            assertThat(line.stagedAt()).isNull();
            assertThat(line.stagedLocationCode()).isNull();
        }

        @Test
        @DisplayName("requireAllPicked counts STAGED as done, and still refuses an open line")
        void allPickedCountsStaged() {
            var done = stagingList(PickStatus.PICKING, staged("SOFA", "HCM-A01-2-B", 4, LPN),
                    picked("CUP", "HCM-A02-1-A", 50, OTHER_LPN));
            done.requireAllPicked();

            var notDone = stagingList(PickStatus.PICKING, staged("SOFA", "HCM-A01-2-B", 4, LPN),
                    open("CUP", "HCM-A02-1-A", 50, OTHER_LPN));
            assertThat(refusal(notDone::requireAllPicked)).isEqualTo(ErrorCode.PICK_LINES_INCOMPLETE);
        }
    }

    @Nested
    @DisplayName("moving the whole task to dispatch (07 BR-11)")
    class MovingToDispatch {

        @Test
        @DisplayName("every STAGED line turns PICKED; the caller then deducts them")
        void stagedLinesTurnPicked() {
            PickLine a = staged("SOFA", "HCM-A01-2-B", 4, LPN);
            PickLine b = staged("CUP", "HCM-A02-1-A", 50, OTHER_LPN);
            var list = stagingList(PickStatus.STAGED, a, b);

            List<PickLine> moved = list.moveToDispatch();

            assertThat(moved).containsExactlyInAnyOrder(a, b);
            assertThat(moved).allSatisfy(line -> {
                assertThat(line.status()).isEqualTo(PickLineStatus.PICKED);
                assertThat(line.consumedAt()).isNull();
                line.recordConsumed(NOW);
                assertThat(line.consumedAt()).isEqualTo(NOW);
            });
        }

        @Test
        @DisplayName("only from STAGED: a PICKING, PENDING, PICKED or CANCELLED task is PICK_TASK_STATE_CONFLICT")
        void onlyFromStaged() {
            for (PickStatus status : List.of(PickStatus.PENDING, PickStatus.PICKING, PickStatus.PICKED,
                    PickStatus.CANCELLED)) {
                var list = stagingList(status, staged("SOFA", "HCM-A01-2-B", 4, LPN));
                assertThat(refusal(list::moveToDispatch)).as(status.name())
                        .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
            }
        }

        @Test
        @DisplayName("a staged task with an open line cannot move: PICK_LINES_INCOMPLETE, nothing changes")
        void openLineBlocks() {
            PickLine a = staged("SOFA", "HCM-A01-2-B", 4, LPN);
            var list = stagingList(PickStatus.STAGED, a, open("CUP", "HCM-A02-1-A", 50, OTHER_LPN));

            assertThat(refusal(list::moveToDispatch)).isEqualTo(ErrorCode.PICK_LINES_INCOMPLETE);
            assertThat(a.status()).isEqualTo(PickLineStatus.STAGED);
        }

        @Test
        @DisplayName("a line that is not STAGED cannot be dispatched")
        void dispatchStagedGuard() {
            PickLine picked = picked("SOFA", "HCM-A01-2-B", 4, LPN);
            assertThatThrownBy(picked::dispatchStaged).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("a top-up (07 section 4.3 step 7, BR-07)")
    class TopUp {

        private final UUID existing = UUID.randomUUID();
        private final UUID added = UUID.randomUUID();
        private final UUID orderLine = UUID.randomUUID();

        private List<PickLine> topUp(PickList list, Map<UUID, PickList.Hold> holds, UUID... reservations) {
            return list.plan(List.of(new PickList.OrderLine(orderLine, "SOFA", List.of(reservations))), holds,
                    UUID::randomUUID);
        }

        private PickLine lineHolding(UUID reservation, int sequence, PickLineStatus status) {
            boolean stagedLine = status == PickLineStatus.STAGED;
            return new PickLine(UUID.randomUUID(), orderLine, reservation, "SOFA", "HCM-A01-2-B", "L1", sequence, 4,
                    4, status, PICKER, NOW, status == PickLineStatus.PICKED ? NOW : null, "HCM-A01-2-B", LPN, 0, null,
                    null, null, 0, null, LPN, true, 0, null, 0, stagedLine ? NOW : null, stagedLine ? STAGING : null);
        }

        @Test
        @DisplayName("a STAGED or PICKED task with lines takes lines for the new holds, after its last stop")
        void newHoldsJoinTheTask() {
            for (PickStatus status : List.of(PickStatus.STAGED, PickStatus.PICKED)) {
                PickLineStatus lineStatus = status == PickStatus.STAGED ? PickLineStatus.STAGED : PickLineStatus.PICKED;
                var list = stagingList(status, lineHolding(existing, 3, lineStatus));
                var holds = Map.of(existing, hold(existing, "SOFA", "HCM-A01-2-B", HCM, 4, LPN),
                        added, hold(added, "SOFA", "HCM-A05-1-A", HCM, 6, OTHER_LPN));

                List<PickLine> lines = topUp(list, holds, existing, added);

                assertThat(lines).as(status.name()).singleElement().satisfies(line -> {
                    assertThat(line.reservationId()).isEqualTo(added);
                    assertThat(line.sequenceNo()).as("after the last stop").isEqualTo(4);
                    assertThat(line.status()).isEqualTo(PickLineStatus.OPEN);
                    assertThat(line.lpn()).isEqualTo(OTHER_LPN);
                });
                assertThat(list.lines()).hasSize(2);
            }
        }

        @Test
        @DisplayName("a hold that already has a line, in any status, gets no second one")
        void coveredHoldsAreSkipped() {
            var list = stagingList(PickStatus.STAGED, lineHolding(existing, 1, PickLineStatus.STAGED));
            var holds = Map.of(existing, hold(existing, "SOFA", "HCM-A01-2-B", HCM, 4, LPN));

            assertThat(topUp(list, holds, existing)).isEmpty();
            assertThat(list.lines()).hasSize(1);
        }

        @Test
        @DisplayName("a PICKED task with no lines (picked before lines existed) and a CANCELLED task take nothing")
        void lineslessPickedAndCancelledTakeNothing() {
            var holds = Map.of(added, hold(added, "SOFA", "HCM-A05-1-A", HCM, 6, OTHER_LPN));

            assertThat(topUp(stagingList(PickStatus.PICKED), holds, added)).isEmpty();
            assertThat(topUp(stagingList(PickStatus.CANCELLED, lineHolding(existing, 1, PickLineStatus.PICKED)),
                    holds, added)).isEmpty();
        }

        @Test
        @DisplayName("a top-up hold in another warehouse is FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE, no line is added")
        void otherWarehouse() {
            var list = stagingList(PickStatus.STAGED, lineHolding(existing, 1, PickLineStatus.STAGED));
            var holds = Map.of(existing, hold(existing, "SOFA", "HCM-A01-2-B", HCM, 4, LPN),
                    added, hold(added, "SOFA", "HN-A05-1-A", UUID.randomUUID(), 6, OTHER_LPN));

            assertThat(refusal(() -> topUp(list, holds, existing, added)))
                    .isEqualTo(ErrorCode.FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE);
            assertThat(list.lines()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("putting back after the order was cancelled")
    class PuttingBack {

        private final PickList.PutBackTarget bin = new PickList.PutBackTarget("HCM-A01-2-B", HCM, true, true);

        @Test
        @DisplayName("a STAGED line goes back whole, and the result says it came from staging")
        void stagedLinePutBack() {
            PickLine line = staged("SOFA", "HCM-A01-2-B", 4, LPN);
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, true, STAGING, List.of(line));

            var result = list.putBack(line.id(), LPN, bin, PICKER, NOW);

            assertThat(result.fromStaging()).isTrue();
            assertThat(result.repeated()).isFalse();
            assertThat(line.status()).isEqualTo(PickLineStatus.PUT_BACK);
            assertThat(line.putBackLocationCode()).isEqualTo("HCM-A01-2-B");
            assertThat(line.consumedAt()).as("a staged line was never deducted").isNull();
        }

        @Test
        @DisplayName("a PICKED line is not from staging; a repeat is answered as done")
        void pickedLineAndRepeat() {
            PickLine line = picked("SOFA", "HCM-A01-2-B", 4, LPN);
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, true, List.of(line));

            assertThat(list.putBack(line.id(), LPN, bin, PICKER, NOW).fromStaging()).isFalse();
            assertThat(list.putBack(line.id(), LPN, bin, PICKER, NOW).repeated()).isTrue();
        }

        @Test
        @DisplayName("a STAGED line of a live task is PUT_BACK_NOT_REQUIRED: nothing is cancelled")
        void liveTask() {
            PickLine line = staged("SOFA", "HCM-A01-2-B", 4, LPN);
            var list = stagingList(PickStatus.STAGED, line);

            assertThat(refusal(() -> list.putBack(line.id(), LPN, bin, PICKER, NOW)))
                    .isEqualTo(ErrorCode.PUT_BACK_NOT_REQUIRED);
        }

        @Test
        @DisplayName("onto a staging area or an inactive bin is PUT_BACK_LOCATION_INVALID")
        void targetMustBeAnActiveBin() {
            PickLine line = staged("SOFA", "HCM-A01-2-B", 4, LPN);
            var list = new PickList(UUID.randomUUID(), HCM, PickStatus.CANCELLED, true, STAGING, List.of(line));

            assertThat(refusal(() -> list.putBack(line.id(), LPN,
                    new PickList.PutBackTarget(STAGING, HCM, false, true), PICKER, NOW)))
                    .isEqualTo(ErrorCode.PUT_BACK_LOCATION_INVALID);
            assertThat(refusal(() -> list.putBack(line.id(), LPN,
                    new PickList.PutBackTarget("HCM-A01-2-B", HCM, true, false), PICKER, NOW)))
                    .isEqualTo(ErrorCode.PUT_BACK_LOCATION_INVALID);
            assertThat(line.status()).isEqualTo(PickLineStatus.STAGED);
        }
    }

    @Test
    @DisplayName("the line rehydrates only with both staging facts or neither, and a STAGED line is not deducted")
    void lineInvariants() {
        assertThatThrownBy(() -> new PickLine(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "SOFA", "HCM-A01-2-B", "L1", 1, 4, 4, PickLineStatus.STAGED, PICKER, NOW, NOW,
                "HCM-A01-2-B", LPN, 0, null, null, null, 0, null, LPN, true, 0, null, 0, NOW, STAGING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PickLine(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "SOFA", "HCM-A01-2-B", "L1", 1, 4, 0, PickLineStatus.OPEN, null, null, null,
                null, null, 0, null, null, null, 0, null, LPN, true, 0, null, 0, NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
