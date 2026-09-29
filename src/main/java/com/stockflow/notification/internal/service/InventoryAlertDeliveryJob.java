package com.stockflow.notification.internal.service;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;
@Component
public class InventoryAlertDeliveryJob {
    private final InventoryAlertDeliveryTransactions transactions;
    private final NotificationSender sender;
    private final List<String> recipients;
    public InventoryAlertDeliveryJob(InventoryAlertDeliveryTransactions transactions,NotificationSender sender,
            @Value("${stockflow.notification.inventory-alert-recipients:}") String recipients) {
        this.transactions=transactions;this.sender=sender;
        this.recipients=java.util.Arrays.stream(recipients.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if(this.recipients.size()>50)throw new IllegalArgumentException("At most 50 inventory alert recipients are supported");
        for(String address:this.recipients)try{new jakarta.mail.internet.InternetAddress(address,true).validate();}
        catch(jakarta.mail.internet.AddressException e){throw new IllegalArgumentException("Invalid inventory alert recipient",e);}
    }
    @Scheduled(fixedDelayString="${stockflow.notification.inventory-alert-retry-ms:30000}")
    @SchedulerLock(name="inventoryAlertDelivery",lockAtMostFor="PT10M")
    public void deliver() {
        for(int i=0;i<50;i++) {
            var pending=transactions.claim(recipients);if(pending==null)return;
            if(pending.token()==null)continue;
            String error=null;
            try{sender.sendInventoryAlert(pending.recipient(),pending.subject(),pending.body());}
            catch(RuntimeException failure){error=failure.getClass().getSimpleName();}
            transactions.complete(pending,error);
        }
    }
}
