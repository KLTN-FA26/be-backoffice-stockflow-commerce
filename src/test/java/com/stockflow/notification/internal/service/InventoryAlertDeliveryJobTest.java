package com.stockflow.notification.internal.service;

import com.stockflow.notification.internal.repository.InventoryAlertDeliveryRepository.Pending;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;

class InventoryAlertDeliveryJobTest {
    @Test void transportFailureIsRecordedAndDoesNotStopNextMessage() {
        var transactions=mock(InventoryAlertDeliveryTransactions.class);var sender=mock(NotificationSender.class);
        var first=new Pending(UUID.randomUUID(),UUID.randomUUID(),"OPEN","planner@example.test","first","body",1,UUID.randomUUID());
        var next=new Pending(UUID.randomUUID(),UUID.randomUUID(),"OPEN","planner@example.test","next","body",1,UUID.randomUUID());
        when(transactions.claim(List.of("planner@example.test"))).thenReturn(first,next,null);
        doThrow(new org.springframework.mail.MailSendException("Sensitive SMTP details"))
                .when(sender).sendInventoryAlert(first.recipient(),first.subject(),first.body());
        new InventoryAlertDeliveryJob(transactions,sender,"planner@example.test").deliver();
        verify(transactions).complete(first,"MailSendException");verify(transactions).complete(next,null);
        verify(sender).sendInventoryAlert(next.recipient(),next.subject(),next.body());
    }
}
