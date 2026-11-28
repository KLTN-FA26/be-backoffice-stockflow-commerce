package com.stockflow.notification.internal.service;

import com.stockflow.contracts.SalesLeadAssigned;
import com.stockflow.contracts.SalesLeadSubmitted;
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

/**
 * Leads from the public form (sales-owner plan §10.7; review finding C1: the events had no listener). A new lead tells
 * the Sales Managers (else the System Admins), who assign it; an assignment tells the salesperson it was given to. In
 * the inbox only, once per lead ({@code lead-submitted:<lead>}) and per assignment
 * ({@code lead-assigned:<lead>:<sales>:<at>}: given back to the same person later is a new notice). The notice names
 * no company: what the form said is text a stranger typed, shown on the lead's own screen under its permission.
 */
@Component
class SalesLeadNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(SalesLeadNotificationListener.class);

    static final String CATEGORY = "SALES_LEAD";
    static final String SOURCE_TYPE = "SALES_LEAD";
    static final String LINK = "/admin/sales/leads";

    private final SalesNotices sales;
    private final Inbox inbox;

    SalesLeadNotificationListener(SalesNotices sales, Inbox inbox) {
        this.sales = sales;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(SalesLeadSubmitted submitted) {
        List<java.util.UUID> managers = sales.managers();
        if (managers.isEmpty()) {
            log.warn("Lead {} came in; no manager is active to be told", submitted.leadId());
            return;
        }
        inbox.post(managers, submitted(submitted));
    }

    @ApplicationModuleListener
    public void on(SalesLeadAssigned assigned) {
        inbox.post(List.of(assigned.salesUserId()), assigned(assigned));
    }

    static InboxMessage submitted(SalesLeadSubmitted submitted) {
        ResourceBundle texts = texts();
        return new InboxMessage(CATEGORY, InboxSeverity.INFO, texts.getString("inbox.lead.submitted.title"),
                texts.getString("inbox.lead.submitted.body"), LINK, SOURCE_TYPE, submitted.leadId().toString(),
                "lead-submitted:" + submitted.leadId());
    }

    static InboxMessage assigned(SalesLeadAssigned assigned) {
        ResourceBundle texts = texts();
        return new InboxMessage(CATEGORY, InboxSeverity.INFO, texts.getString("inbox.lead.assigned.title"),
                texts.getString("inbox.lead.assigned.body"), LINK, SOURCE_TYPE, assigned.leadId().toString(),
                "lead-assigned:%s:%s:%d".formatted(assigned.leadId(), assigned.salesUserId(),
                        assigned.at().toEpochMilli()));
    }

    private static ResourceBundle texts() {
        return ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
    }
}
