package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.contracts.PutawayCompleted;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.internal.domain.ReturnedCartonOwnership;
import com.stockflow.inventory.internal.domain.StockAdjustmentRepository;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.UomConversionRepository;
import com.stockflow.warehouse.api.NewPutawayTask;
import com.stockflow.warehouse.api.PutawaySource;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Quarantine and release of whole cartons (team plan 4D-1; kltn-docs 20 §4.8, BR-21; 05 §4.4, BR-01, BR-14). Both are
 * a warehouse manager's (BR-21) and change a status, never a quantity, so neither goes through four eyes (decided
 * 2026-11-05). Writing a quarantined carton off is the third way out, an approved {@code DAMAGED} adjustment of its
 * LPN ({@code StockOperations#requestAdjustment}); a carton takes one of the three, so each refuses a carton another
 * has started on ({@code STOCK_PACKAGE_DECISION_PENDING}).
 *
 * <p><b>Quarantine.</b> The stock is moved and reclassified by {@link StockOperations#quarantine}. What this adds is
 * the carton's putaway task: a carton found faulty while it was being put away (20 §4.8: "khi cất") still has its
 * receipt's task open, and that task is finished here, DONE into the quarantine area, and announced as every finished
 * receipt task is ({@link PutawayCompleted}). Left open, it could never be confirmed - its carton is no longer INBOUND -
 * and its receipt, which closes once every task is done, would stay in putaway for ever. The carton has left
 * receiving, which is what the receipt waits for; where it goes from quarantine is the release's own task. An INBOUND
 * carton whose task is not open yet (tasks are opened just after a receipt is confirmed) is refused until it is, for
 * the same reason.</p>
 *
 * <p>A carton that came back opened on a return (WF-05 TH-4) is the exception: it holds fewer units than its label,
 * so it is not a whole carton any more and never goes back on sale (20 BR-14) - its release is refused
 * ({@code RETURN_CARTON_OPENED}), and a write-off is the one way out.</p>
 *
 * <p><b>Release.</b> A putaway task of source {@code QUARANTINE_RELEASE} is opened for the carton, which stays
 * QUARANTINE - out of ATP - where it stands until that task puts it into a bin and makes it AVAILABLE (05 BR-14,
 * {@code PutawayService#confirm}). Nothing moves and nothing is written to the ledger here. Releasing a carton that
 * already has its release task answers that task again.</p>
 *
 * <p>Lock order: the carton's putaway task (as a putaway confirmation takes it first), then the SKU advisory lock,
 * then the carton. A write-off's approval takes the SKU lock before it asks for an open task, so a release and an
 * approval of one carton are decided one after the other.</p>
 */
@Service
public class CartonQuarantineService {

    private static final Logger log = LoggerFactory.getLogger(CartonQuarantineService.class);

    private final StockOperations operations;
    private final StockPackageRepository stockPackages;
    private final StockAdjustmentRepository adjustments;
    private final UomConversionRepository units;
    private final InventoryPolicyRepository policies;
    private final PutawayTasks tasks;
    private final WarehouseService warehouses;
    private final ApplicationEventPublisher events;
    private final ReturnedCartonOwnership ownership;

    public CartonQuarantineService(StockOperations operations, StockPackageRepository stockPackages,
                                   StockAdjustmentRepository adjustments,
                                   UomConversionRepository units, InventoryPolicyRepository policies,
                                   PutawayTasks tasks, WarehouseService warehouses, ApplicationEventPublisher events,
                                   ReturnedCartonOwnership ownership) {
        this.ownership = ownership;
        this.operations = operations;
        this.stockPackages = stockPackages;
        this.adjustments = adjustments;
        this.units = units;
        this.policies = policies;
        this.tasks = tasks;
        this.warehouses = warehouses;
        this.events = events;
    }

    /**
     * Quarantines the carton {@code lpn} into the QUARANTINE area {@code locationCode}, for {@code reason}.
     *
     * @return the carton as it now stands
     * @throws BusinessException as {@link StockOperations#quarantine}, and {@code STOCK_PACKAGE_DECISION_PENDING} for
     *         an INBOUND carton whose putaway task is not open yet
     */
    @Transactional
    public StockPackageView quarantine(String lpn, String locationCode, String reason, UUID actorId) {
        StockPackage carton = find(lpn);
        Optional<PutawayTaskView> task = tasks.openTaskOf(carton.id().value())
                .map(open -> tasks.lockForUpdate(open.id()))
                .filter(locked -> "PENDING".equals(locked.status()) || "IN_PROGRESS".equals(locked.status()));
        if (task.isEmpty() && holdsInbound(carton)) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_DECISION_PENDING,
                    "%s has not been given its putaway task yet; try again in a moment".formatted(carton.lpn()),
                    List.of(), Map.of("lpn", carton.lpn()));
        }
        StockPackageView quarantined = operations.quarantine(lpn, locationCode, reason, actorId);
        task.filter(open -> open.source() == PutawaySource.RECEIPT).ifPresent(open -> {
            UUID area = warehouses.findLocation(quarantined.locationCode()).map(StorageLocationView::id)
                    .orElseThrow(() -> new IllegalStateException("No location " + quarantined.locationCode()));
            PutawayTaskView done = tasks.recordPutaway(open.id(), open.quantity(), open.quantity(), area);
            if ("DONE".equals(done.status())) {
                events.publishEvent(new PutawayCompleted(open.goodsReceiptId(), open.goodsReceiptLineId(), open.id()));
            }
            log.info("Putaway task {} of carton {} finished into quarantine {}", open.id(), carton.lpn(),
                    quarantined.locationCode());
        });
        return quarantined;
    }

    /**
     * Releases the quarantined carton {@code lpn}: opens (or answers again) its {@code QUARANTINE_RELEASE} putaway
     * task.
     *
     * @return the task's id
     * @throws BusinessException {@code STOCK_PACKAGE_NOT_FOUND}; 403 for a carton of another warehouse;
     *         {@code INVALID_STOCK_PACKAGE_TRANSITION} for a carton not in stock; {@code STOCK_PACKAGE_NOT_QUARANTINED};
     *         {@code RETURN_CARTON_OPENED} for a carton that came back opened on a return - written off only;
     *         {@code STOCK_PACKAGE_DECISION_PENDING} when a write-off of it awaits approval, it has another open
     *         putaway task, or it came back on a return whose refund is not decided ({@code returnOwnership}
     *         PENDING, WF-05 Q-4)
     */
    @Transactional
    public UUID release(String lpn, UUID actorId) {
        StockPackage seen = find(lpn);
        UomConversion unit = units.findAll(List.of(seen.uomConversionId())).stream().findFirst().orElseThrow(() ->
                new IllegalStateException("Stock package %s is in no known unit".formatted(seen.lpn())));
        policies.lock(unit.sku());
        StockPackage carton = stockPackages.findAllByIdForUpdate(List.of(seen.id())).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Stock package %s vanished".formatted(seen.lpn())));
        if (carton.status() != StockPackageStatus.IN_STOCK) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION,
                    "%s is %s; only a carton in stock is released".formatted(carton.lpn(), carton.status()));
        }
        if (carton.stockStatus() != StockStatus.QUARANTINE) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_QUARANTINED,
                    "%s is not in quarantine; only a quarantined carton is released".formatted(carton.lpn()),
                    List.of(), Map.of("lpn", carton.lpn()));
        }
        if (stockPackages.openedOnReturn(carton.id())) {
            throw new BusinessException(ErrorCode.RETURN_CARTON_OPENED, ("%s came back opened on a return: it holds "
                    + "fewer units than its label and is never sold again; write it off").formatted(carton.lpn()),
                    List.of(), Map.of("lpn", carton.lpn()));
        }
        Optional<ReturnedCartonOwnership.Owned> waiting = ownership.owned(carton.id())
                .filter(owned -> owned.ownership() == ReturnedCartonOwnership.Ownership.PENDING);
        if (waiting.isPresent()) {
            // WF-05 Q-4 (H1): the return it came back on has not decided its refund. Not refunded, the goods are the
            // customer's and leave the books; released now, they would be sold back to the customer who owns them.
            // The details name the return (audit r3 RAD-4), so the screen says what the carton waits for.
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_DECISION_PENDING, ("%s came back on a return whose "
                    + "refund is not decided yet: it is released once the refund makes it the company's")
                    .formatted(carton.lpn()), List.of(), Map.of("lpn", carton.lpn(), "returnOwnership", "PENDING",
                    "returnRequestId", waiting.get().returnRequestId()));
        }
        if (adjustments.findPendingForPackage(carton.id()).isPresent()) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_DECISION_PENDING,
                    "A write-off of %s awaits approval; it is released once that is rejected or withdrawn"
                            .formatted(carton.lpn()), List.of(), Map.of("lpn", carton.lpn()));
        }
        PutawayTaskView task = tasks.open(NewPutawayTask.quarantineRelease(unit.sku(), carton.nominalQty(),
                carton.id().value()));
        if (task.source() != PutawaySource.QUARANTINE_RELEASE) {
            throw new BusinessException(ErrorCode.STOCK_PACKAGE_DECISION_PENDING,
                    "%s still has its receipt's putaway task %s open".formatted(carton.lpn(), task.id()),
                    List.of(), Map.of("lpn", carton.lpn()));
        }
        log.info("Carton {} released from quarantine by {}: putaway task {}", carton.lpn(), actorId, task.id());
        return task.id();
    }

    private StockPackage find(String lpn) {
        String normal = StockPackageView.normalLpn(lpn);
        StockPackage carton = stockPackages.findByLpns(List.of(normal)).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_PACKAGE_NOT_FOUND, "No stock package " + normal));
        WarehouseScope.requireLocation(carton.location().code());
        return carton;
    }

    /**
     * Read again from the table, after the wait for the task's lock: a putaway finished meanwhile has made the carton
     * AVAILABLE, and the copy read before the wait would still say INBOUND. Without a lock: a task is only ever
     * opened, never removed, so a stale answer only refuses too early.
     */
    private boolean holdsInbound(StockPackage carton) {
        return stockPackages.stockStatusOf(carton.id()).filter(status -> status == StockStatus.INBOUND).isPresent();
    }
}
