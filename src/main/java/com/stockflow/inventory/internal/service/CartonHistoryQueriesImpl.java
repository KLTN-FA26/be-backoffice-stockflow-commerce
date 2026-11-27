package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.inventory.api.CartonHistoryQueries;
import com.stockflow.inventory.internal.domain.StockPackageBalance;
import com.stockflow.inventory.internal.repository.CartonDocumentsQuery;
import com.stockflow.inventory.spi.CartonWork;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link CartonHistoryQueries} by the screens' own reads, so each fact keeps the rule its screen applies: the carton by
 * {@link StockPackageLookup#byLpn} (not found, then refused for another warehouse), its history by
 * {@link StockOperations#ledger} with the caller's {@link BookVisibility}, the pick and check work by the
 * {@link CartonWork} the carton list asks, the putaway task by {@code warehouse :: api}, and the count and adjustment
 * documents by {@link CartonDocumentsQuery}. One statement per kind of fact, for one carton.
 *
 * <p>The reader is classified here, where a person's read enters the module (as {@code BookVisibility} requires), from
 * the caller in the security context: absent is blind.</p>
 */
@Service
@Transactional(readOnly = true)
class CartonHistoryQueriesImpl implements CartonHistoryQueries {

    private final StockPackageLookup lookup;
    private final StockOperations operations;
    private final StockBrowsing browsing;
    private final CartonDocumentsQuery documents;
    private final PutawayTasks putawayTasks;
    private final ObjectProvider<CartonWork> cartonWork;
    private final CurrentUserProvider currentUsers;

    CartonHistoryQueriesImpl(StockPackageLookup lookup, StockOperations operations, StockBrowsing browsing,
                             CartonDocumentsQuery documents, PutawayTasks putawayTasks,
                             ObjectProvider<CartonWork> cartonWork, CurrentUserProvider currentUsers) {
        this.lookup = lookup;
        this.operations = operations;
        this.browsing = browsing;
        this.documents = documents;
        this.putawayTasks = putawayTasks;
        this.cartonWork = cartonWork;
        this.currentUsers = currentUsers;
    }

    @Override
    public CartonHistory history(String lpn, int lines) {
        if (lines < 1 || lines > MAX_LINES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "lines must be between 1 and " + MAX_LINES + ", not " + lines);
        }
        StockPackageBalance carton = lookup.byLpn(lpn).carton();
        BookVisibility visibility = BookVisibility.of(currentUsers.current());
        boolean booksHidden = visibility.hidesCountedSlots()
                && !browsing.underCount(List.of(carton.id())).isEmpty();
        PageResponse<StockOperations.LedgerLine> history = operations.ledger(new StockOperations.LedgerFilter(null,
                null, null, null, null, null, carton.lpn()), 0, lines, visibility);
        List<LedgerLine> ledger = history.items().stream().map(line -> new LedgerLine(line.occurredAt(),
                line.type() == null ? null : line.type().name(), line.fromLocation(), line.toLocation(),
                line.referenceType(), line.referenceId(), line.actorId())).toList();
        return new CartonHistory(carton.id(), carton.lpn(), carton.sku(), carton.status().name(),
                carton.stockStatuses().isEmpty() ? null : carton.stockStatuses().getFirst().name(),
                carton.locationCode(), carton.warehouseId(), carton.warehouseCode(), carton.lotNumber(),
                carton.receivedAt(), booksHidden, ledger, history.totalElements(), openWork(carton, booksHidden));
    }

    /**
     * Every open piece of work naming the carton. Its pending adjustments are left out for a blind reader whose carton
     * a count holds: a COUNT_VARIANCE's direction is the gap between the books and the count.
     */
    private List<OpenWork> openWork(StockPackageBalance carton, boolean booksHidden) {
        List<OpenWork> work = new ArrayList<>();
        PutawayTaskView putaway = putawayTasks.openTasksOf(List.of(carton.id())).get(carton.id());
        if (putaway != null) {
            work.add(new OpenWork(WorkKind.PUTAWAY_TASK, putaway.id(), null, putaway.status()));
        }
        CartonWork picking = cartonWork.getIfAvailable();
        if (picking != null) {
            CartonWork.Work open = picking.openWorkOf(List.of(carton.lpn())).get(carton.lpn());
            if (open != null) {
                work.add(new OpenWork(open.kind() == CartonWork.Kind.PICK ? WorkKind.PICK_TASK : WorkKind.CARTON_CHECK,
                        open.taskId(), null, null));
            }
        }
        documents.openCounts(carton.id()).forEach(count -> work.add(new OpenWork(WorkKind.COUNT, count.id(),
                count.number(), count.status())));
        if (!booksHidden) {
            documents.pendingAdjustments(carton.id()).forEach(adjustment -> work.add(new OpenWork(
                    WorkKind.ADJUSTMENT, adjustment.id(), adjustment.number(), adjustment.status())));
        }
        return work;
    }
}
