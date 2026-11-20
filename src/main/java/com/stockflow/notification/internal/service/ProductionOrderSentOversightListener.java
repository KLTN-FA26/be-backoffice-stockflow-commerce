package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.ProductionOrderSent;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * Tells the Sales Manager that the Production Coordinator sent a production order (the coordinator owns production
 * and sends without an approval step, so the Sales Manager oversees by being told): INFO, in the in-app inbox only.
 * Who is told is decided when it is delivered: the ACTIVE staff holding {@link #AUDIENCE}, of every warehouse (the
 * notice is about a business decision, not one warehouse's work, hence a null warehouse). A permission, not a role
 * name (house rule P8). A redelivered event tells nobody twice ({@code production-order-sent-oversight:<id>}; the
 * inbox owns the dedupe).
 *
 * <p>{@code production-order-sends:READ} is a dedicated audience permission because {@code production-orders:READ}
 * is held too widely to say "told of every send"; see V20261210000200. The receiving warehouse's own notice is
 * {@link ProductionOrderArrivalNotificationListener}'s.</p>
 */
@Component
class ProductionOrderSentOversightListener {

    private static final Logger log = LoggerFactory.getLogger(ProductionOrderSentOversightListener.class);

    static final String AUDIENCE = "production-order-sends:READ";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final StaffDirectory staff;
    private final Inbox inbox;

    ProductionOrderSentOversightListener(StaffDirectory staff, Inbox inbox) {
        this.staff = staff;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(ProductionOrderSent event) {
        List<UUID> recipients = staff.activeHolders(AUDIENCE, null).stream()
                .map(StaffContact::userId).filter(Objects::nonNull).distinct().toList();
        if (recipients.isEmpty()) {
            log.info("Production order {} sent: nobody holds {} to tell", LogSafe.text(event.code()), AUDIENCE);
            return;
        }
        int told = inbox.post(recipients, message(event));
        log.info("Production order {} sent: {} person(s) told", LogSafe.text(event.code()), told);
    }

    static InboxMessage message(ProductionOrderSent event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String title = texts.getString("inbox.production-order.sent-oversight.title").formatted(event.code());
        String needBy = event.needByDate() == null ? "-" : DAY.format(event.needByDate());
        return new InboxMessage(ProductionOrderArrivalNotificationListener.CATEGORY, InboxSeverity.INFO,
                title.length() <= InboxMessage.TITLE_MAX ? title : title.substring(0, InboxMessage.TITLE_MAX),
                texts.getString("inbox.production-order.sent-oversight.body").formatted(event.totalCartons(), needBy),
                "/production/orders/" + event.productionOrderId(),
                ProductionOrderArrivalNotificationListener.SOURCE_TYPE, event.productionOrderId().toString(),
                "production-order-sent-oversight:" + event.productionOrderId());
    }
}
