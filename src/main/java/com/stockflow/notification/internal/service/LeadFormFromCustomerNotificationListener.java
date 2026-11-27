package com.stockflow.notification.internal.service;

import com.stockflow.contracts.LeadFormFromExistingCustomer;
import com.stockflow.notification.api.CustomerContact;
import com.stockflow.notification.api.CustomerDirectory;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.notification.api.SalesAudiences.SalesAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.ResourceBundle;

/**
 * The public "open a business account" form named the tax code of a business that is already a customer (kltn-docs 18
 * §4.6 step 27; 00 §6): no lead was made, and whoever looks after that customer is told instead - its owner, the
 * owner's cover, or with no active owner the Sales Managers marked "cần phân công" ({@link SalesNotices}). In-app only,
 * once per form sent ({@code lead-form-existing:<customer>:<at>}).
 *
 * <p>The notice names the customer by the name on its profile, read when it is written; what the form said is in
 * neither the event nor the notice.</p>
 */
@Component
class LeadFormFromCustomerNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(LeadFormFromCustomerNotificationListener.class);

    static final String CATEGORY = "LEAD_FORM_EXISTING_CUSTOMER";

    private final SalesNotices sales;
    private final CustomerDirectory customers;
    private final Inbox inbox;

    LeadFormFromCustomerNotificationListener(SalesNotices sales, CustomerDirectory customers, Inbox inbox) {
        this.sales = sales;
        this.customers = customers;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(LeadFormFromExistingCustomer event) {
        SalesAudience audience = sales.audiences().ofCustomer(event.customerId()).orElse(null);
        if (audience == null || audience.userIds().isEmpty()) {
            log.warn("Customer {} sent the lead form; nobody looks after it and no manager is active to be told",
                    event.customerId());
            return;
        }
        String name = customers.contact(event.customerId()).map(CustomerContact::name).orElse(null);
        int told = inbox.post(audience.userIds(), SalesNotices.marked(message(event, name),
                audience.needsAssignment()));
        log.info("Customer {} sent the lead form: {} recipient(s), {} newly told", event.customerId(),
                audience.userIds().size(), told);
    }

    static InboxMessage message(LeadFormFromExistingCustomer event, String customerName) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String name = customerName == null || customerName.isBlank() ? "-" : customerName.strip();
        String title = texts.getString("inbox.lead.existingCustomer.title").formatted(name);
        if (title.length() > InboxMessage.TITLE_MAX) {
            title = title.substring(0, InboxMessage.TITLE_MAX);
        }
        return new InboxMessage(CATEGORY, InboxSeverity.INFO, title,
                texts.getString("inbox.lead.existingCustomer.body"), "/admin/customers/" + event.customerId(),
                "CUSTOMER_PROFILE", event.customerId().toString(),
                "lead-form-existing:%s:%d".formatted(event.customerId(), event.at().toEpochMilli()));
    }
}
