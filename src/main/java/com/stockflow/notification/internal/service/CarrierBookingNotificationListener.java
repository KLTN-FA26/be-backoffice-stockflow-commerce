package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.CarrierBookingCancelled;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Tells a warehouse's dispatchers that the carrier cancelled the order of one of its loads before the pickup (3-5;
 * kltn-docs 09 §4.5: "chuyến về Planned, xoá thông tin đặt xe, báo Dispatch Staff đặt lại"): the load is PLANNED
 * again and needs a vehicle. The audience is whoever may book vehicles there ({@value #AUDIENCE}), so the role matrix
 * decides who that is.
 *
 * <p>Delivered as every staff notice ({@link StaffNotices}): in-app first, one inbox row per person per load and order
 * ({@code carrier-booking-cancelled:<load>:<order>}), then e-mail best effort to the people newly told; a redelivered
 * event tells nobody twice. System text only - codes, never the carrier's words.</p>
 */
@Component
class CarrierBookingNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(CarrierBookingNotificationListener.class);

    static final String CATEGORY = "CARRIER_BOOKING";
    static final String SOURCE_TYPE = "DISPATCH_LOAD";
    /** Who books a load's vehicle (DispatchController's booking). */
    static final String AUDIENCE = "fulfillment-dispatch:UPDATE";

    private final NotificationSender sender;
    private final StaffDirectory staff;
    private final StaffNotices notices;

    CarrierBookingNotificationListener(NotificationSender sender, StaffDirectory staff, StaffNotices notices) {
        this.sender = sender;
        this.staff = staff;
        this.notices = notices;
    }

    @ApplicationModuleListener
    public void on(CarrierBookingCancelled event) {
        List<StaffContact> recipients = staff.activeHolders(AUDIENCE, event.warehouseId());
        if (recipients.isEmpty()) {
            log.warn("Load {}: its carrier cancelled order {}, and nobody may book vehicles at its warehouse",
                    LogSafe.text(event.loadCode()), LogSafe.text(event.carrierOrderId()));
            return;
        }
        InboxMessage message = message(event);
        int told = notices.deliver(recipients, message, emails -> sender.sendStaffNotice(emails, message,
                "staff_carrier_booking_cancelled"));
        log.info("Load {}: carrier cancelled order {}; {} dispatcher(s), {} newly told", LogSafe.text(event.loadCode()),
                LogSafe.text(event.carrierOrderId()), recipients.size(), told);
    }

    static InboxMessage message(CarrierBookingCancelled event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        return new InboxMessage(CATEGORY, InboxSeverity.WARN,
                texts.getString("inbox.carrier.booking.cancelled.title").formatted(event.carrierCode(),
                        event.loadCode()),
                texts.getString("inbox.carrier.booking.cancelled.body").formatted(event.carrierCode(),
                        event.carrierOrderId(), event.loadCode()),
                "/admin/fulfillment/loads/" + event.loadId(), SOURCE_TYPE, event.loadId().toString(),
                "carrier-booking-cancelled:%s:%s".formatted(event.loadId(), event.carrierOrderId()));
    }
}
