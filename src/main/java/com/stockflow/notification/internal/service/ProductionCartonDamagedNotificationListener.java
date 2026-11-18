package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.ProductionCartonWrittenOffDamaged;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.notification.api.ProductionOrderDirectory;
import com.stockflow.notification.api.ShortSalesOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Tells the Production Coordinator that a carton of a production order's goods was written off as damaged (kltn-docs 20
 * §4.8 step 3, 17 §4.13 step 51): a {@code WARN} in the in-app inbox, nothing by e-mail, naming the carton, its SKU,
 * its lot (the production order's code) and, when one is left short by it, the sales order - the ground for drawing up a
 * replacement production order, which is the coordinator's own step (step 52), not this message's. Who is told is
 * decided by permission, not by role name (house rule P8): the holders of {@link #AUDIENCE}, the one that lets a person
 * draw a production order up - the Production Coordinator in today's role matrix; their scope is the whole business,
 * so whichever warehouse the carton stood in, they see it. One message per person per adjustment
 * ({@code production-carton-damaged:<adjustment id>}): a redelivered event tells nobody twice.
 */
@Component
class ProductionCartonDamagedNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(ProductionCartonDamagedNotificationListener.class);

    static final String CATEGORY = "PRODUCTION_ORDER";
    static final String SOURCE_TYPE = "PRODUCTION_ORDER";
    /** Who draws up production orders, and so the replacement for the cartons lost (17 §4.13 step 52). */
    static final String AUDIENCE = "production-orders:CREATE";

    private final StaffDirectory staff;
    private final Inbox inbox;
    private final ProductionOrderDirectory productionOrders;

    ProductionCartonDamagedNotificationListener(StaffDirectory staff, Inbox inbox,
                                                ProductionOrderDirectory productionOrders) {
        this.staff = staff;
        this.inbox = inbox;
        this.productionOrders = productionOrders;
    }

    @ApplicationModuleListener
    public void on(ProductionCartonWrittenOffDamaged event) {
        List<StaffContact> recipients = staff.activeHolders(AUDIENCE, event.warehouseId());
        if (recipients.isEmpty()) {
            log.warn("A carton of production order {} was written off as damaged; nobody can be told",
                    LogSafe.text(event.lotNumber()));
            return;
        }
        Optional<ShortSalesOrder> shortOrder = productionOrders.shortSalesOrder(event.productionOrderId());
        int told = inbox.post(recipients.stream().map(StaffContact::userId).filter(Objects::nonNull).toList(),
                message(event, shortOrder.orElse(null)));
        log.info("A carton of production order {} was written off as damaged: {} recipient(s), {} newly told",
                LogSafe.text(event.lotNumber()), recipients.size(), told);
    }

    static InboxMessage message(ProductionCartonWrittenOffDamaged event, ShortSalesOrder shortOrder) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String lpn = event.lpn() == null ? "" : event.lpn();
        String order = shortOrder == null ? texts.getString("inbox.production-carton.damaged.no-short-order")
                : texts.getString("inbox.production-carton.damaged.short-order")
                .formatted(shortOrder.orderNumber(), shortOrder.shortCartons());
        String title = texts.getString("inbox.production-carton.damaged.title").formatted(event.lotNumber());
        String body = texts.getString("inbox.production-carton.damaged.body")
                .formatted(lpn, event.sku(), event.lotNumber(), order);
        return new InboxMessage(CATEGORY, InboxSeverity.WARN,
                title.length() <= InboxMessage.TITLE_MAX ? title : title.substring(0, InboxMessage.TITLE_MAX),
                body.length() <= InboxMessage.BODY_MAX ? body : body.substring(0, InboxMessage.BODY_MAX),
                "/production/orders/" + event.productionOrderId(), SOURCE_TYPE, event.productionOrderId().toString(),
                "production-carton-damaged:" + event.adjustmentId());
    }
}
