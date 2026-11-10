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

/**
 * The putaway task's own rules: one carton per task (team plan 4C-2), recorded whole, nothing after DONE
 * (SCRUM-92).
 */
class PutawayTaskTest {

    private final UUID bin = Identifiers.newId();
    private final UUID bin2 = Identifiers.newId();

    private static PutawayTask carton(int quantity) {
        return PutawayTask.open(Identifiers.newId(),
                new NewPutawayTask(Identifiers.newId(), Identifiers.newId(), "SKU-1", quantity, Identifiers.newId()));
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("a task names its carton (05 BR-08): a loose one is refused")
    void aLooseTaskIsRefused() {
        assertThat(code(org.assertj.core.api.Assertions.catchThrowable(() -> PutawayTask.open(Identifiers.newId(),
                new NewPutawayTask(Identifiers.newId(), Identifiers.newId(), "SKU-1", 10, null)))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("a new task is PENDING with no target")
    void newTaskIsPending() {
        PutawayTask task = carton(100);

        assertThat(task.status()).isEqualTo(PutawayStatus.PENDING);
        assertThat(task.targetLocationId()).isNull();
        assertThat(task.quantity()).isEqualTo(100);
    }

    @Test
    @DisplayName("a DONE task takes nothing more: PUTAWAY_TASK_DONE")
    void doneRefusesMore() {
        PutawayTask task = carton(60);
        task.recordPutaway(60, 60, bin);

        assertThatThrownBy(() -> task.recordPutaway(1, 0, bin2))
                .extracting(PutawayTaskTest::code).isEqualTo(ErrorCode.PUTAWAY_TASK_DONE);
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

    @Test
    @DisplayName("a cancelled task records nothing and stays CANCELLED")
    void cancelledTaskIsNotReopened() {
        PutawayTask task = new PutawayTask(Identifiers.newId(), Identifiers.newId(), Identifiers.newId(), "SKU-1",
                100, null, null, PutawayStatus.CANCELLED);

        assertThatThrownBy(() -> task.recordPutaway(100, 100, bin))
                .extracting(PutawayTaskTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(task.status()).isEqualTo(PutawayStatus.CANCELLED);
        assertThat(task.targetLocationId()).isNull();
    }
}
