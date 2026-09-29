package com.stockflow.notification.internal.service;
import com.stockflow.notification.internal.repository.*;
import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
import com.stockflow.notification.internal.domain.*;
import com.stockflow.common.id.Identifiers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.util.List;
@Service
public class InventoryAlertDeliveryTransactions {
    private final InventoryAlertDeliveryRepository queue;
    private final DeliveryLogJpaRepository logs;
    private final Clock clock;
    public InventoryAlertDeliveryTransactions(InventoryAlertDeliveryRepository queue,DeliveryLogJpaRepository logs,Clock clock){this.queue=queue;this.logs=logs;this.clock=clock;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public InventoryAlertDeliveryRepository.Pending claim(List<String> recipients){return queue.claim(clock.instant(),recipients);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void complete(InventoryAlertDeliveryRepository.Pending p,String error){
        var now=clock.instant();
        if(!queue.complete(p,now,error))return;
        String reference="inventory-alert:"+p.id();
        var log=new DeliveryLogJpaEntity(Identifiers.newId(),"inventory.threshold",NotificationChannel.EMAIL,p.recipient(),
                error==null?DeliveryStatus.SENT:DeliveryStatus.FAILED,error,error==null?now:null,error==null?reference:null);
        log.correlate(reference);logs.save(log);
    }
}
