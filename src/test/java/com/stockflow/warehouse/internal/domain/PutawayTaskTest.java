package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.warehouse.api.NewPutawayTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The putaway task's own rules: parts of a loose line, the whole of a carton, nothing after DONE (SCRUM-92). */
class PutawayTaskTest {

    private final UUID bin = Identifiers.newId();
    private final UUID bin2 = Identifiers.newId();

    private static PutawayTask loose(int quantity) {
        return PutawayTask.open(Identifiers.newId(),
                new NewPutawayTask(Identifiers.newId(), Identifiers.newId(), "SKU-1", quantity, null));
    }

    private static PutawayTask carton(int quantity) {
        return PutawayTask.open(Identifiers.newId(),
                new NewPutawayTask(Identifiers.newId(), Identifiers.newId(), "SKU-1", quantity, Identifiers.newId()));
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("a new task is PENDING with no target")
    void newTaskIsPending() {
        PutawayTask task = loose(100);

        assertThat(task.status()).isEqualTo(PutawayStatus.PENDING);
        assertThat(task.targetLocationId()).isNull();
        assertThat(task.quantity()).isEqualTo(100);
    }

    @Test
    @DisplayName("part of a loose line leaves the task IN_PROGRESS, targeting the bin it went to")
    void partialPutawayIsInProgress() {
        PutawayTask task = loose(100);

        task.recordPutaway(40, 100, bin);

        assertThat(task.status()).isEqualTo(PutawayStatus.IN_PROGRESS);
        assertThat(task.targetLocationId()).isEqualTo(bin);
    }

    @Test
    @DisplayName("the last part finishes the task and the target is the last bin")
    void lastPartIsDone() {
        PutawayTask task = loose(100);
        task.recordPutaway(40, 100, bin);

        task.recordPutaway(60, 60, bin2);

        assertThat(task.status()).isEqualTo(PutawayStatus.DONE);
        assertThat(task.targetLocationId()).isEqualTo(bin2);
    }

    @Test
    @DisplayName("a DONE task takes nothing more: PUTAWAY_TASK_DONE")
    void doneRefusesMore() {
        PutawayTask task = loose(60);
        task.recordPutaway(60, 60, bin);

        assertThatThrownBy(() -> task.recordPutaway(1, 0, bin2))
                .extracting(PutawayTaskTest::code).isEqualTo(ErrorCode.PUTAWAY_TASK_DONE);
    }

    @Test
    @DisplayName("more than is left, or nothing, is refused: PUTAWAY_QUANTITY_EXCEEDED")
    void moreThanLeftRefused() {
        PutawayTask task = loose(100);
        task.recordPutaway(40, 100, bin);

        assertThatThrownBy(() -> task.recordPutaway(61, 60, bin2))
                .extracting(PutawayTaskTest::code).isEqualTo(ErrorCode.PUTAWAY_QUANTITY_EXCEEDED);
        assertThatThrownBy(() -> task.recordPutaway(0, 60, bin2))
                .extracting(PutawayTaskTest::code).isEqualTo(ErrorCode.PUTAWAY_QUANTITY_EXCEEDED);
        assertThat(task.status()).as("a refusal changes nothing").isEqualTo(PutawayStatus.IN_PROGRESS);
        assertThat(task.targetLocationId()).isEqualTo(bin);
    }

    @Test
    @DisplayName("a carton is put away whole: part of it is PUTAWAY_QUANTITY_EXCEEDED, all of it is DONE")
    void cartonTaskIsWhole() {
        PutawayTask task = carton(980);

        assertThatThrownBy(() -> task.recordPutaway(500, 980, bin))
                .extracting(PutawayTaskTest::code).isEqualTo(ErrorCode.PUTAWAY_QUANTITY_EXCEEDED);

        task.recordPutaway(980, 980, bin);

        assertThat(task.status()).isEqualTo(PutawayStatus.DONE);
    }
}
