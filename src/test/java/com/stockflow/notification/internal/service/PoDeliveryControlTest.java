package com.stockflow.notification.internal.service;

import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;
import com.stockflow.notification.internal.repository.PoDeliveryControlRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Clock;
import java.util.UUID;
import static org.mockito.Mockito.*;

class PoDeliveryControlTest {
    @ParameterizedTest
    @CsvSource({"0,true", "1,false"})
    void suppressedAndStaleEventsNeverReachTransport(int generation, boolean suppressed) {
        var control = mock(PoDeliveryControlRepository.class);
        var logs = mock(DeliveryLogJpaRepository.class);
        var sender = mock(NotificationSender.class);
        var attempts = mock(DeliveryAttemptRecorder.class);
        var event = mock(PurchaseOrderSent.class);
        UUID id = UUID.randomUUID();
        when(event.purchaseOrderId()).thenReturn(id);
        when(control.lock(id)).thenReturn(new PoDeliveryControlRepository.Control(generation, suppressed));
        new PurchaseOrderNotificationListener(sender, logs, Clock.systemUTC(), attempts, control).on(event);
        verifyNoInteractions(sender, logs, attempts);
    }
}
