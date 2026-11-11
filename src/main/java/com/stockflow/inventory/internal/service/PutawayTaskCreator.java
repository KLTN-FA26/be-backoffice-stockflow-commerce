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
 * Received goods are ready to be put away (a receipt is confirmed; there is no QC step, kltn-docs 03 BR-07): open the
 * warehouse's putaway tasks for them (SCRUM-92).
 *
 * <p>One task per carton of the line that is IN_STOCK with INBOUND units, wherever it stands now (a carton is put
 * away from wherever it stands, so a hand move must not hide it), sized by those units (kltn-docs 05 BR-08: each task
 * is one carton). Nothing else: a receipt puts every unit into a carton (team plan 4C-2), so there is no loose
 * remainder to put away; a line with units beyond its cartons could only be one received before that, and is logged
 * rather than given a task nobody could confirm. Cartons are read here, not carried by the event, because the event is
 * published by procurement, which does not know them. A carton already put away, or quarantined since, has no INBOUND
 * units and opens nothing.</p>
 *
 * <p>An event line whose receipt line already has any putaway task is skipped whole. The event is published at most
 * once per receipt line, so an existing task means this line was processed. The task that exists is what makes a
 * redelivery (the publication registry retries after a failure) harmless.</p>
 *
 * <p>This listener writes nothing but tasks and moves no stock.</p>
 *
 * <p>Public so the integration test can redeliver an event to it directly; nothing else should.</p>
 */
@Component
public class PutawayTaskCreator {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PutawayTaskCreator.class);

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
            if (line.quantity() > inCartons) {
                log.warn("Receipt line {} received {} x {} but its cartons hold {}; units outside a carton get no "
                        + "putaway task", line.receiptLineId(), line.quantity(), line.sku(), inCartons);
            }
        }
    }
}
