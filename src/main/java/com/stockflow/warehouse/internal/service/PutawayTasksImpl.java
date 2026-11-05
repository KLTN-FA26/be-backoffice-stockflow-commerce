package com.stockflow.warehouse.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.warehouse.api.NewPutawayTask;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import com.stockflow.warehouse.internal.domain.PutawayTask;
import com.stockflow.warehouse.internal.domain.PutawayTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The only implementation of {@link PutawayTasks}. Joins the caller's transaction (inventory's putaway), so a task
 * and the stock movement it records commit or roll back together.
 *
 * <p>Package-private class, public interface: other modules inject it by the interface.</p>
 */
@Service
@Transactional
class PutawayTasksImpl implements PutawayTasks {

    private final PutawayTaskRepository tasks;

    PutawayTasksImpl(PutawayTaskRepository tasks) {
        this.tasks = tasks;
    }

    @Override
    public PutawayTaskView open(NewPutawayTask request) {
        Optional<PutawayTask> existing = request.stockPackageId() != null
                ? tasks.findOpenByStockPackage(request.stockPackageId())
                : tasks.findLooseOfReceiptLine(request.goodsReceiptLineId());
        return toView(existing.orElseGet(() -> tasks.save(PutawayTask.open(Identifiers.newId(), request))));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PutawayTaskView> find(UUID taskId) {
        return tasks.findById(taskId).map(PutawayTasksImpl::toView);
    }

    @Override
    public PutawayTaskView lockForUpdate(UUID taskId) {
        return toView(locked(taskId));
    }

    @Override
    public PutawayTaskView recordPutaway(UUID taskId, int units, int leftBefore, UUID targetLocationId) {
        PutawayTask task = locked(taskId);
        task.recordPutaway(units, leftBefore, targetLocationId);
        return toView(tasks.save(task));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PutawayTaskView> tasksOfReceipt(UUID goodsReceiptId) {
        return tasks.findByReceipt(goodsReceiptId).stream().map(PutawayTasksImpl::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PutawayTaskView> openTasks() {
        return tasks.findOpen().stream().map(PutawayTasksImpl::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> doneQuantityByReceiptLine(UUID goodsReceiptId) {
        return tasks.doneQuantityByReceiptLine(goodsReceiptId);
    }

    private PutawayTask locked(UUID taskId) {
        return tasks.findByIdForUpdate(taskId).orElseThrow(() -> new BusinessException(
                ErrorCode.PUTAWAY_TASK_NOT_FOUND, "Putaway task " + taskId + " not found"));
    }

    private static PutawayTaskView toView(PutawayTask t) {
        return new PutawayTaskView(t.id(), t.goodsReceiptId(), t.goodsReceiptLineId(), t.sku(), t.quantity(),
                t.stockPackageId(), t.targetLocationId(), t.status().name());
    }
}
