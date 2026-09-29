package com.stockflow.inventory.internal.service;
import com.stockflow.contracts.*;
import com.stockflow.inventory.internal.repository.StockAlertRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
public class InventoryAlertJob {
    private final InventoryAlertService service;
    private final StockAlertRepository alerts;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(InventoryAlertJob.class);
    public InventoryAlertJob(InventoryAlertService service,StockAlertRepository alerts){this.service=service;this.alerts=alerts;}
    @ApplicationModuleListener public void on(SkuInventoryControlChanged e){refresh(e.sku());}
    @ApplicationModuleListener public void on(StockLevelChanged e){refresh(e.sku());}
    @ApplicationModuleListener public void on(StockDeducted e){refresh(e.sku());}
    @Scheduled(fixedDelayString="${stockflow.inventory.alert-check-ms:30000}")
    @SchedulerLock(name="inventoryAlertEvaluation",lockAtMostFor="PT2M")
    public void reconcile(){for(String sku:alerts.nextEvaluation())refresh(sku);}
    private void refresh(String sku){try{service.attempted(sku);service.evaluate(sku);}catch(RuntimeException e){LOG.warn("Inventory alert evaluation pending for {}",sku,e);}}
}
