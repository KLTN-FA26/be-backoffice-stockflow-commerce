package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.ReturnAwaitingRefund;
import com.stockflow.contracts.ReturnReceived;
import com.stockflow.contracts.ReturnedCartonsInQuarantine;
import com.stockflow.contracts.ReturnedGoodsReceived;
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
 * Tells staff what a return leaves them to do (WF-05 BE-24, audit R-9): {@link ReturnNotificationListener} told the
 * customer every step, and nobody on staff was told anything - a return reached RECEIVED and sat there, its order
 * RETURNED for good, until someone happened to open it.
 *
 * <ul>
 *   <li>A return waits for its refund to be proposed - or refused with a reason ({@link ReturnAwaitingRefund}, whatever
 *       moved it to RECEIVED): whoever proposes refunds ({@value #PROPOSES}).</li>
 *   <li>A parcel came back different from what was approved - more, fewer, or another SKU ({@link ReturnReceived} with
 *       a discrepancy): the same people, to contact the customer (03 edge cases: "báo Sale").</li>
 *   <li>A shipment the carrier brought back was received with cartons still missing ({@link ReturnedGoodsReceived}
 *       {@code missingCartons}): whoever records delivery outcomes at that warehouse ({@value #CARRIER}), to ask the
 *       carrier.</li>
 *   <li>Returned cartons wait in a warehouse's quarantine ({@link ReturnedCartonsInQuarantine}, daily): that
 *       warehouse's managers, to release, write off, or give back (TH-6).</li>
 * </ul>
 *
 * <p>In-app first, e-mail best effort ({@link StaffNotices}); each notice deduped on what it is about, so a redelivered
 * event tells nobody twice. Public handler methods: {@code @ApplicationModuleListener} is proxy-based advice.</p>
 */
@Component
class ReturnStaffNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(ReturnStaffNotificationListener.class);

    static final String CATEGORY = "RETURN";
    static final String SOURCE_TYPE = "RETURN_REQUEST";
    /** Who proposes a return's refund (ReturnResolutionController). */
    static final String PROPOSES = "sales-rma-resolutions:UPDATE";
    /** Who records what the carrier did with a shipment (ShipmentController.recordOutcome, APPROVE). */
    static final String CARRIER = "fulfillment-delivery-outcomes:APPROVE";

    private final NotificationSender sender;
    private final StaffDirectory staff;
    private final StaffNotices notices;

    ReturnStaffNotificationListener(NotificationSender sender, StaffDirectory staff, StaffNotices notices) {
        this.sender = sender;
        this.staff = staff;
        this.notices = notices;
    }

    @ApplicationModuleListener
    public void on(ReturnAwaitingRefund event) {
        tell(staff.activeHolders(PROPOSES, null), awaitingProposal(event), "staff_return_awaiting_proposal");
    }

    @ApplicationModuleListener
    public void on(ReturnReceived event) {
        if (event.discrepancy()) {
            tell(staff.activeHolders(PROPOSES, null), discrepancy(event), "staff_return_discrepancy");
        }
    }

    @ApplicationModuleListener
    public void on(ReturnedGoodsReceived event) {
        if (event.shipmentId() != null && !event.missingCartons().isEmpty()) {
            tell(staff.activeHolders(CARRIER, event.warehouseId()), missingCartons(event),
                    "staff_return_missing_cartons");
        }
    }

    @ApplicationModuleListener
    public void on(ReturnedCartonsInQuarantine event) {
        if (event.cartons() > 0) {
            tell(staff.activeStaff(Role.WAREHOUSE_MANAGER, event.warehouseId()), quarantineDigest(event),
                    "staff_quarantine_digest");
        }
    }

    private void tell(List<StaffContact> recipients, InboxMessage message, String mailCategory) {
        if (recipients.isEmpty()) {
            log.warn("{} notice {}: nobody active may act on it; nobody was notified",
                    LogSafe.text(message.category()), LogSafe.text(message.dedupeKey()));
            return;
        }
        int told = notices.deliver(recipients, message, emails -> sender.sendStaffNotice(emails, message,
                mailCategory));
        log.info("{} notice {}: {} person(s), {} newly told", LogSafe.text(message.category()),
                LogSafe.text(message.dedupeKey()), recipients.size(), told);
    }

    static InboxMessage awaitingProposal(ReturnAwaitingRefund event) {
        ResourceBundle texts = texts();
        String rma = LogSafe.text(event.rmaNumber());
        return new InboxMessage(CATEGORY, InboxSeverity.WARN,
                texts.getString("inbox.return.awaiting-proposal.title").formatted(rma),
                texts.getString("inbox.return.awaiting-proposal.body").formatted(rma,
                        LogSafe.text(event.orderNumber())), "/admin/orders/returns/" + event.returnRequestId(),
                SOURCE_TYPE, event.returnRequestId().toString(), "return-awaiting-proposal:%s:%s".formatted(
                        event.returnRequestId(), event.at() == null ? "" : event.at().toEpochMilli()));
    }

    static InboxMessage discrepancy(ReturnReceived event) {
        ResourceBundle texts = texts();
        String rma = LogSafe.text(event.rmaNumber());
        return new InboxMessage(CATEGORY, InboxSeverity.WARN,
                texts.getString("inbox.return.receipt-discrepancy.title").formatted(rma),
                texts.getString("inbox.return.receipt-discrepancy.body").formatted(rma,
                        LogSafe.text(event.orderNumber()), event.receiptNumber() == null
                                ? "#" + event.parcelNumber() : LogSafe.text(event.receiptNumber())),
                "/admin/orders/returns/" + event.returnRequestId(), SOURCE_TYPE, event.returnRequestId().toString(),
                "return-discrepancy:%s:%d".formatted(event.returnRequestId(), event.parcelNumber()));
    }

    static InboxMessage missingCartons(ReturnedGoodsReceived event) {
        ResourceBundle texts = texts();
        String order = LogSafe.text(event.orderNumber());
        return new InboxMessage(CATEGORY, InboxSeverity.WARN,
                texts.getString("inbox.return.rto-missing-cartons.title").formatted(order),
                texts.getString("inbox.return.rto-missing-cartons.body").formatted(
                        LogSafe.text(event.receiptNumber()), order, event.missingCartons().size()),
                "/admin/receipts/returns/" + event.receiptId(), "GOODS_RECEIPT", event.receiptId().toString(),
                "return-missing-cartons:" + event.receiptId());
    }

    static InboxMessage quarantineDigest(ReturnedCartonsInQuarantine event) {
        ResourceBundle texts = texts();
        return new InboxMessage("QUARANTINE", InboxSeverity.INFO,
                texts.getString("inbox.quarantine.digest.title").formatted(event.cartons()),
                texts.getString("inbox.quarantine.digest.body").formatted(event.cartons(),
                        LogSafe.text(event.warehouseCode())), "/admin/inventory/quarantine", "WAREHOUSE",
                event.warehouseId().toString(), "quarantine-digest:%s:%s".formatted(event.warehouseId(),
                        event.day()));
    }

    private static ResourceBundle texts() {
        return ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
    }
}
