package com.stockflow.warehouse.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The putaway tasks of the warehouse module (SCRUM-92), for the inventory module that orchestrates a putaway: it
 * moves the stock, this module keeps the task that says what is still to be put away and where it last went.
 *
 * <p>Every method runs in the caller's transaction, so a task and the stock movement it records commit together.
 * Only {@link #lockForUpdate} locks.</p>
 */
public interface PutawayTasks {

    /**
     * Opens a task for a receipt line, or for one carton of it. Idempotent: the open task of the same carton, or the
     * loose task (any status) of the same receipt line, is returned instead of a second one, so receiving a line twice
     * cannot make the warehouse put it away twice.
     */
    PutawayTaskView open(NewPutawayTask task);

    /** @return empty when there is no such task */
    Optional<PutawayTaskView> find(UUID taskId);

    /**
     * The task, read under a row lock: two people putting away the same line wait for each other, so each sees what
     * the other left.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PUTAWAY_TASK_NOT_FOUND}
     */
    PutawayTaskView lockForUpdate(UUID taskId);

    /**
     * Records {@code units} put into {@code targetLocationId}. The task becomes {@code DONE} when nothing is left,
     * {@code IN_PROGRESS} otherwise.
     *
     * @param leftBefore what the task still had to put away before these units; the caller computes it from the
     *                   stock it has moved, because the task keeps no running total
     * @throws com.stockflow.common.error.BusinessException {@code PUTAWAY_TASK_NOT_FOUND}, {@code PUTAWAY_TASK_DONE},
     *         {@code PUTAWAY_QUANTITY_EXCEEDED} (more than is left, or part of a carton)
     */
    PutawayTaskView recordPutaway(UUID taskId, int units, int leftBefore, UUID targetLocationId);

    /** Every task of the receipt, whatever its status, oldest first. */
    List<PutawayTaskView> tasksOfReceipt(UUID goodsReceiptId);

    /** The tasks still to do ({@code PENDING} and {@code IN_PROGRESS}), oldest first. */
    List<PutawayTaskView> openTasks();

    /** Units of {@code DONE} tasks per receipt line of the receipt; a line with none is absent. */
    Map<UUID, Integer> doneQuantityByReceiptLine(UUID goodsReceiptId);
}
