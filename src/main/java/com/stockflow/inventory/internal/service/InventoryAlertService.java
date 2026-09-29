package com.stockflow.inventory.internal.service;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.*;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.*;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.*;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.internal.domain.StockAlert;
import com.stockflow.inventory.internal.repository.*;
import com.stockflow.notification.api.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class InventoryAlertService {
    private final InventoryControlService inventory;
    private final InventoryPolicyRepository policies;
    private final StockAlertRepository alerts;
    private final NotificationService notifications;
    private final Clock clock;
    public InventoryAlertService(InventoryControlService inventory,InventoryPolicyRepository policies,StockAlertRepository alerts,NotificationService notifications,Clock clock){
        this.inventory=inventory;this.policies=policies;this.alerts=alerts;this.notifications=notifications;this.clock=clock;
    }
    /** Background reconciliation is deliberately unscoped: thresholds are system-wide per SKU. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void evaluate(String code) {
        Sku sku=new Sku(code);policies.lock(sku.code());var e=inventory.evaluate(sku);
        reconcile(sku.code(),"REORDER",e.reorderPoint(),Boolean.TRUE.equals(e.reorderRequired()),e.usableOnHand());
        reconcile(sku.code(),"SAFETY",e.safetyStock(),Boolean.TRUE.equals(e.belowSafetyStock()),e.usableOnHand());
    }
    private void reconcile(String sku,String kind,Integer threshold,boolean breached,long quantity) {
        var current=alerts.open(sku,kind);StockAlert transition=null;var now=clock.instant();
        if(breached) {
            if(current.isEmpty())transition=alerts.create(sku,kind,quantity,threshold,now);
            else alerts.observed(current.get().id(),quantity,threshold,now);
        } else if(current.isPresent())transition=alerts.resolve(current.get().id(),quantity,now,threshold==null?"THRESHOLD_CLEARED":"RECOVERED");
        if(transition!=null)notifications.queueInventoryAlert(new InventoryAlertMessage(transition.id(),sku,kind,
                transition.status(),quantity,transition.threshold(),now));
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void attempted(String sku){alerts.attempted(sku,clock.instant());}
    @Transactional(readOnly=true)
    public PageResponse<StockAlert> list(String status,int page,int size){
        global();if(status!=null && !List.of("OPEN","RESOLVED").contains(status))throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var p=Pages.of(page,size);return PageResponse.of(alerts.page(status,p.getOffset(),p.getPageSize()),page,size,alerts.count(status));
    }
    @Transactional(readOnly=true)
    public StockAlert get(UUID id){global();return alerts.find(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));}
    @Auditable(action=AuditAction.UPDATE,resourceType="inventory-alert",resourceId="#id")
    public StockAlert acknowledge(UUID id){var alert=get(id);if(!"OPEN".equals(alert.status()))throw new BusinessException(ErrorCode.CONFLICT);
        alerts.acknowledge(id,DataScopeContext.current().orElseThrow().user().userId(),clock.instant());return get(id);}
    @Transactional(readOnly=true)
    public List<AlertDeliverySummary> deliveries(UUID id){get(id);return notifications.inventoryAlertDeliveries(id);}
    @Auditable(action=AuditAction.UPDATE,resourceType="inventory-alert",resourceId="#id")
    public void retry(UUID id){get(id);notifications.retryInventoryAlert(id);}
    /** A per-SKU total spans warehouses; a warehouse-only grant must not leak other warehouses. */
    private static void global(){if(DataScopeContext.current().map(s -> s.effective()!=DataScope.ALL).orElse(true))throw new BusinessException(ErrorCode.OUT_OF_DATA_SCOPE);}
}
