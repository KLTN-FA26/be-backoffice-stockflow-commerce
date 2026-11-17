package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.VehicleHandedOver;
import com.stockflow.notification.api.CustomerDirectory;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * Tells the customer that goods of their order left with a carrier (3-6; kltn-docs 00 §6 "Đơn đã bàn giao vận chuyển",
 * 17 BR-27, E23): one in-app notice per vehicle and order, in the portal account's inbox, naming the carrier, the
 * waybill number and the plate - what 17 BR-27 says the customer sees of each vehicle. Not by e-mail (E23: e-mail to
 * customers is a later phase).
 *
 * <p>The carrier's name and the plate come from the warehouse's records, not from anything the customer typed; the
 * driver is never named. A customer whose portal account is not activated yet has no inbox: nothing is posted, and the
 * order page still shows the vehicle. A redelivered event posts nothing twice
 * ({@code vehicle-handed-over:<load>:<order>}).</p>
 */
@Component
class VehicleHandoverNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(VehicleHandoverNotificationListener.class);

    static final String CATEGORY = "ORDER_SHIPPING";
    static final String SOURCE_TYPE = "ORDER";

    private final CustomerDirectory customers;
    private final Inbox inbox;

    VehicleHandoverNotificationListener(CustomerDirectory customers, Inbox inbox) {
        this.customers = customers;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(VehicleHandedOver event) {
        UUID account = event.customerId() == null ? null : customers.portalAccount(event.customerId()).orElse(null);
        if (account == null) {
            log.info("Order {} left on load {}; its customer has no portal account to tell",
                    LogSafe.text(event.orderNumber()), LogSafe.text(event.loadCode()));
            return;
        }
        int told = inbox.post(List.of(account), message(event));
        log.info("Order {} left on load {}: customer {}", LogSafe.text(event.orderNumber()),
                LogSafe.text(event.loadCode()), told == 1 ? "told" : "already told");
    }

    static InboxMessage message(VehicleHandedOver event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String body = texts.getString("inbox.order.vehicle.left.body").formatted(
                orDash(event.carrierName()), orDash(event.waybillNo()), orDash(event.vehiclePlate()));
        return new InboxMessage(CATEGORY, InboxSeverity.INFO,
                texts.getString("inbox.order.vehicle.left.title").formatted(event.orderNumber()),
                body.length() <= InboxMessage.BODY_MAX ? body : body.substring(0, InboxMessage.BODY_MAX),
                "/orders/" + event.orderId(), SOURCE_TYPE, event.orderId().toString(),
                "vehicle-handed-over:%s:%s".formatted(event.loadId(), event.orderId()));
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
