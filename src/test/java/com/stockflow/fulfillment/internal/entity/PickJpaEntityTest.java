package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.fulfillment.api.PickDestination;
import com.stockflow.fulfillment.internal.domain.PickStatus;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Team plan 4C-5 on the task row: the destination and the assignee change only while PENDING (kltn-docs 07 BR-08,
 * {@code tg_pick_pending_edits}), a task completes STAGED or PICKED by where its lines go, and the move to dispatch is
 * the one way out of STAGED.
 */
class PickJpaEntityTest {

    private static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");
    private static final UUID PICKER = UUID.randomUUID();
    private static final String STAGING = "HCM-STG01";

    private static PickJpaEntity task(PickStatus status, PickDestination destination) {
        return new PickJpaEntity(UUID.randomUUID(), null, UUID.randomUUID(), "SO-1", UUID.randomUUID(), status,
                destination, destination == PickDestination.STAGING ? STAGING : null);
    }

    private static ErrorCode refusal(ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("a task built without a destination goes to DISPATCH, with no staging area, and confirms into nothing")
    void defaultsToDispatch() {
        var task = new PickJpaEntity(UUID.randomUUID(), null, UUID.randomUUID(), "SO-1", UUID.randomUUID(),
                PickStatus.PENDING);

        assertThat(task.getDestination()).isEqualTo(PickDestination.DISPATCH);
        assertThat(task.getStagingLocationCode()).isNull();
        assertThat(task.confirmsInto()).isNull();
    }

    @Test
    @DisplayName("assign works while PENDING and is PICK_TASK_STATE_CONFLICT in every other status")
    void assignOnlyWhilePending() {
        var pending = task(PickStatus.PENDING, PickDestination.DISPATCH);
        pending.assign(PICKER, NOW);
        assertThat(pending.getAssignedUserId()).isEqualTo(PICKER);
        assertThat(pending.getAssignedAt()).isEqualTo(NOW);

        for (PickStatus status : new PickStatus[] {PickStatus.PICKING, PickStatus.STAGED, PickStatus.PICKED,
                PickStatus.CANCELLED}) {
            var other = task(status, PickDestination.DISPATCH);
            assertThat(refusal(() -> other.assign(PICKER, NOW))).as(status.name())
                    .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
            assertThat(other.getAssignedUserId()).as(status.name() + " keeps its assignee").isNull();
        }
    }

    @Test
    @DisplayName("changeDestination works while PENDING both ways, and drops the staging area for DISPATCH")
    void changeDestinationWhilePending() {
        var task = task(PickStatus.PENDING, PickDestination.DISPATCH);

        task.changeDestination(PickDestination.STAGING, STAGING);
        assertThat(task.getDestination()).isEqualTo(PickDestination.STAGING);
        assertThat(task.getStagingLocationCode()).isEqualTo(STAGING);
        assertThat(task.confirmsInto()).isEqualTo(STAGING);

        task.changeDestination(PickDestination.DISPATCH, STAGING);
        assertThat(task.getDestination()).isEqualTo(PickDestination.DISPATCH);
        assertThat(task.getStagingLocationCode()).as("ck_pick_staging_location: STAGING iff a code").isNull();
        assertThat(task.confirmsInto()).isNull();
    }

    @Test
    @DisplayName("changeDestination is PICK_TASK_STATE_CONFLICT once the task left PENDING, and changes nothing")
    void changeDestinationNotAfterPending() {
        for (PickStatus status : new PickStatus[] {PickStatus.PICKING, PickStatus.STAGED, PickStatus.PICKED,
                PickStatus.CANCELLED}) {
            var task = task(status, PickDestination.DISPATCH);
            assertThat(refusal(() -> task.changeDestination(PickDestination.STAGING, STAGING))).as(status.name())
                    .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
            assertThat(task.getDestination()).isEqualTo(PickDestination.DISPATCH);
        }
    }

    @Test
    @DisplayName("a STAGING destination needs its staging area")
    void stagingNeedsAnArea() {
        var task = task(PickStatus.PENDING, PickDestination.DISPATCH);

        assertThatThrownBy(() -> task.changeDestination(PickDestination.STAGING, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("complete: a STAGING task becomes STAGED with staged_at, a DISPATCH task PICKED with picked_at")
    void completeByDestination() {
        var staging = task(PickStatus.PENDING, PickDestination.STAGING);
        staging.assign(PICKER, NOW);
        staging.start(PICKER, NOW);
        staging.complete(PICKER, NOW.plusSeconds(60));
        assertThat(staging.getStatus()).isEqualTo(PickStatus.STAGED);
        assertThat(staging.getStagedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(staging.getPickedAt()).isNull();

        var dispatch = task(PickStatus.PENDING, PickDestination.DISPATCH);
        dispatch.assign(PICKER, NOW);
        dispatch.start(PICKER, NOW);
        dispatch.complete(PICKER, NOW.plusSeconds(60));
        assertThat(dispatch.getStatus()).isEqualTo(PickStatus.PICKED);
        assertThat(dispatch.getPickedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(dispatch.getStagedAt()).isNull();
    }

    @Test
    @DisplayName("moveToDispatch: only from STAGED; PICKED, with who and when; the destination stays on record")
    void moveToDispatch() {
        var task = task(PickStatus.STAGED, PickDestination.STAGING);
        UUID manager = UUID.randomUUID();

        task.moveToDispatch(manager, NOW);

        assertThat(task.getStatus()).isEqualTo(PickStatus.PICKED);
        assertThat(task.getPickedAt()).isEqualTo(NOW);
        assertThat(task.getMovedToDispatchAt()).isEqualTo(NOW);
        assertThat(task.getMovedToDispatchBy()).isEqualTo(manager);
        assertThat(task.getDestination()).isEqualTo(PickDestination.STAGING);

        for (PickStatus status : new PickStatus[] {PickStatus.PENDING, PickStatus.PICKING, PickStatus.PICKED,
                PickStatus.CANCELLED}) {
            var other = task(status, PickDestination.STAGING);
            assertThat(refusal(() -> other.moveToDispatch(manager, NOW))).as(status.name())
                    .isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
            assertThat(other.getMovedToDispatchAt()).isNull();
        }
    }

    @Test
    @DisplayName("confirmsInto is null after the move: a top-up line of a moved task goes straight to dispatch")
    void confirmsIntoAfterTheMove() {
        var task = task(PickStatus.STAGED, PickDestination.STAGING);
        assertThat(task.confirmsInto()).isEqualTo(STAGING);

        task.moveToDispatch(UUID.randomUUID(), NOW);

        assertThat(task.confirmsInto()).isNull();
    }

    @Test
    @DisplayName("reopen: from STAGED and from PICKED back to PICKING; from PENDING, PICKING or CANCELLED refused")
    void reopen() {
        var staged = task(PickStatus.STAGED, PickDestination.STAGING);
        staged.reopen();
        assertThat(staged.getStatus()).isEqualTo(PickStatus.PICKING);
        assertThat(staged.confirmsInto()).as("new lines are staged too").isEqualTo(STAGING);

        var picked = task(PickStatus.STAGED, PickDestination.STAGING);
        picked.moveToDispatch(UUID.randomUUID(), NOW);
        picked.reopen();
        assertThat(picked.getStatus()).isEqualTo(PickStatus.PICKING);
        assertThat(picked.getPickedAt()).isNull();
        assertThat(picked.confirmsInto()).as("a moved task tops up into dispatch").isNull();

        for (PickStatus status : new PickStatus[] {PickStatus.PENDING, PickStatus.PICKING, PickStatus.CANCELLED}) {
            var other = task(status, PickDestination.STAGING);
            assertThat(refusal(other::reopen)).as(status.name()).isEqualTo(ErrorCode.PICK_TASK_STATE_CONFLICT);
        }
    }

    @Test
    @DisplayName("a topped-up STAGED task completes STAGED again; a moved one completes PICKED")
    void completeAfterTopUp() {
        var walked = task(PickStatus.PENDING, PickDestination.STAGING);
        walked.assign(PICKER, NOW);
        walked.start(PICKER, NOW);
        walked.complete(PICKER, NOW.plusSeconds(10));
        walked.reopen();
        walked.complete(PICKER, NOW.plusSeconds(20));
        assertThat(walked.getStatus()).isEqualTo(PickStatus.STAGED);
        assertThat(walked.getStagedAt()).isEqualTo(NOW.plusSeconds(20));

        walked.moveToDispatch(UUID.randomUUID(), NOW.plusSeconds(30));
        walked.reopen();
        walked.complete(PICKER, NOW.plusSeconds(40));
        assertThat(walked.getStatus()).isEqualTo(PickStatus.PICKED);
    }

    @Test
    @DisplayName("cancel: a PENDING task needs no put-back, a STAGED one does")
    void cancelFlagsAPutBackOnlyOnceGoodsMoved() {
        var pending = task(PickStatus.PENDING, PickDestination.STAGING);
        var stagedTask = task(PickStatus.STAGED, PickDestination.STAGING);

        pending.cancel();
        stagedTask.cancel();

        assertThat(pending.isNeedsPutBack()).isFalse();
        assertThat(stagedTask.isNeedsPutBack()).isTrue();
    }
}
