package com.stockflow.inventory.internal.service;

import com.stockflow.contracts.ReceiptStockReadyForPutaway;
import com.stockflow.contracts.ReceiptStockReadyForPutaway.Line;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.warehouse.api.NewPutawayTask;
import com.stockflow.warehouse.api.PutawayTasks;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Received goods are ready to be put away (confirmation of a no-QC line, or QC accepting part of one): open the
 * warehouse's putaway tasks for them (SCRUM-92).
 *
 * <p>One task per carton still waiting at the event's location, sized by its INBOUND units, plus one loose task for
 * what the line has beyond those cartons. Cartons are read here, not carried by the event, because the event is
 * published by procurement, which does not know them. A carton QC quarantined is elsewhere by now (or not
 * IN_STOCK), and one already put away has no INBOUND units, so neither opens a task.</p>
 *
 * <p>This listener writes nothing but tasks and moves no stock. It is redelivered by the publication registry after
 * a failure, which is safe because {@link PutawayTasks#open} returns the existing task instead of a second one.</p>
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
        Map<UUID, List<StockPackage>> cartonsByLine = stockPackages
                .findByReceiptLines(event.lines().stream().map(Line::receiptLineId).toList()).stream()
                .collect(Collectors.groupingBy(StockPackage::goodsReceiptLineId));
        Map<StockPackageId, Integer> inbound = stockPackages.inboundOnHandOf(cartonsByLine.values().stream()
                .flatMap(Collection::stream).map(StockPackage::id).toList());

        for (Line line : event.lines()) {
            int inCartons = 0;
            for (StockPackage carton : cartonsByLine.getOrDefault(line.receiptLineId(), List.of())) {
                int units = inbound.getOrDefault(carton.id(), 0);
                if (carton.status() != StockPackageStatus.IN_STOCK || units <= 0
                        || !carton.location().code().equalsIgnoreCase(line.locationCode())) {
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
