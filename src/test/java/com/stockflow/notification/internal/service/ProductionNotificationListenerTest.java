package com.stockflow.notification.internal.service;

import com.stockflow.contracts.ProductionOrderHeld;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Who hears of a held production order: Sales, purchasing, or the workshop. */
class ProductionNotificationListenerTest {

    private final NotificationSender sender = mock(NotificationSender.class);
    private final ProductionNotificationListener listener = new ProductionNotificationListener(sender);

    private static ProductionOrderHeld held(String reason, String note) {
        return new ProductionOrderHeld(UUID.randomUUID(), "LSX-20261012-0001", reason, note, null, UUID.randomUUID());
    }

    @Test
    void aRejectedFileGoesToSales() {
        listener.on(held("FILE_REJECTED", "Bleed missing"));
        verify(sender).send(isNull(), eq("production.held.sales"), contains("FILE_REJECTED"), contains("Bleed missing"));
    }

    @Test
    void blanksShortGoToPurchasing() {
        listener.on(held("BLANKS_SHORT", "3 blank(s) of SOFA-3S-GREY needed for a reprint"));
        verify(sender).send(isNull(), eq("production.held.purchasing"), contains("BLANKS_SHORT"), contains("3 blank(s)"));
    }

    @Test
    void aMachineDownStaysInTheWorkshop() {
        listener.on(held("MACHINE_DOWN", null));
        verify(sender).send(isNull(), eq("production.held.workshop"), contains("MACHINE_DOWN"), contains("LSX-20261012-0001"));
    }
}
