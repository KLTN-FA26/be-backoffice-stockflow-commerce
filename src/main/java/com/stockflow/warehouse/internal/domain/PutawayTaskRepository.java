package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.domain.AggregateRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link PutawayTask}. */
public interface PutawayTaskRepository extends AggregateRepository<PutawayTask, UUID> {

    /** Reads under a row lock and refreshes, so what is returned is the committed row and not a stale copy. */
    Optional<PutawayTask> findByIdForUpdate(UUID id);

    /** The PENDING or IN_PROGRESS task of a carton; at most one ({@code uk_putaway_task_open_package}). */
    Optional<PutawayTask> findOpenByStockPackage(UUID stockPackageId);

    /** The loose task of a receipt line, whatever its status: a finished line is not put away a second time. */
    Optional<PutawayTask> findLooseOfReceiptLine(UUID goodsReceiptLineId);

    /** Oldest first; a receipt has a handful of lines. */
    List<PutawayTask> findByReceipt(UUID goodsReceiptId);

    /** PENDING and IN_PROGRESS, oldest first. */
    List<PutawayTask> findOpen();

    /** Units of DONE tasks, summed per receipt line; a line with none is absent. */
    Map<UUID, Integer> doneQuantityByReceiptLine(UUID goodsReceiptId);
}
