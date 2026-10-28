package com.stockflow.inventory.internal.service;

import com.stockflow.contracts.ReceiptStockReadyForPutaway;
import com.stockflow.contracts.ReceiptStockReadyForPutaway.Line;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.warehouse.api.NewPutawayTask;
import com.stockflow.warehouse.api.PutawayTaskView;
import com.stockflow.warehouse.api.PutawayTasks;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Received goods are ready to be put away (confirmation of a no-QC line, or QC accepting part of one): open the
 * warehouse's putaway tasks for them (SCRUM-92).
 *
 * <p>One task per carton of the line that is IN_STOCK with INBOUND units, wherever it stands now (a carton is put
 * away from wherever it stands, so a hand move must not hide it), sized by those units, plus one loose task for what
 * the line has beyond those cartons. Cartons are read here, not carried by the event, because the event is published
 * by procurement, which does not know them. A carton QC quarantined or rejected, or one already put away, has no
 * INBOUND units and opens nothing.</p>
 *
 * <p>An event line whose receipt line already has any putaway task is skipped whole. The event is published at most
 * once per receipt line, so an existing task means this line was processed; recomputing would be wrong, because the
 * loose remainder is derived from INBOUND units that putaway has since consumed, and a redelivery after the cartons
 * were put away would open a loose task for stock already in bins. The task that exists is what makes a redelivery
 * (the publication registry retries after a failure) harmless.</p>
 *
 * <p>This listener writes nothing but tasks and moves no stock.</p>
 *
 * <p>Public so the integration test can redeliver an event to it directly; nothing else should.</p>
 */
@Component
public class PutawayTaskCreator {

    private final PutawayTasks tasks;
    private final StockPackageRepository stockPackages;

    public PutawayTaskCreator(PutawayTasks tasks, StockPackageRepository stockPackages) {
        this.tasks = tasks;
        this.stockPackages = stockPackages;
    }

    @ApplicationModuleListener
    public void on(ReceiptStockReadyForPutaway event) {
        Set<UUID> processed = tasks.tasksOfReceipt(event.receiptId()).stream()
                .map(PutawayTaskView::goodsReceiptLineId).collect(Collectors.toSet());
        Map<UUID, List<StockPackage>> cartonsByLine = stockPackages
                .findByReceiptLines(event.lines().stream().map(Line::receiptLineId).toList()).stream()
                .collect(Collectors.groupingBy(StockPackage::goodsReceiptLineId));
        Map<StockPackageId, Integer> inbound = stockPackages.inboundOnHandOf(cartonsByLine.values().stream()
                .flatMap(Collection::stream).map(StockPackage::id).toList());

        for (Line line : event.lines()) {
            if (processed.contains(line.receiptLineId())) {
                continue;
            }
            int inCartons = 0;
            for (StockPackage carton : cartonsByLine.getOrDefault(line.receiptLineId(), List.of())) {
                int units = inbound.getOrDefault(carton.id(), 0);
                if (carton.status() != StockPackageStatus.IN_STOCK || units <= 0) {
                    continue;
                }
                inCartons += units;
                tasks.open(new NewPutawayTask(event.receiptId(), line.receiptLineId(), line.sku(), units,
                        carton.id().value()));
            }
            int loose = line.quantity() - inCartons;
            if (loose > 0) {
                tasks.open(new NewPutawayTask(event.receiptId(), line.receiptLineId(), line.sku(), loose, null));
            }
        }
    }
}
