package com.stockflow.notification.internal.service;

import com.stockflow.contracts.CustomersNeedSalesOwner;
import com.stockflow.contracts.OwnerChangeRequestDecided;
import com.stockflow.contracts.OwnerChangeRequested;
import com.stockflow.contracts.StaffAccountReactivated;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.SalesStaff;
import com.stockflow.notification.api.CustomerContact;
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
 * The notices the sales-owner plan promised about who looks after a customer (review finding C1: the events were
 * published and nobody listened). In-app only, each once per event ({@code dedupeKey}):
 * <ul>
 *   <li><b>A salesperson's book released</b> ({@link CustomersNeedSalesOwner}, BR-S08, D44): the Sales Managers (else
 *       the System Admins, plan §4 case 25), once per deactivation with the counts of customers and leads - not once
 *       per customer. The chat supervisors hear of the requeued conversations from the chat itself; that is another
 *       notice about another list.</li>
 *   <li><b>A salesperson back</b> ({@link StaffAccountReactivated}, BR-S10): the managers, because nothing returns to
 *       them by itself and giving customers back is a decision someone has to make. Only when the account is a Sales
 *       agent now: an unlocked warehouse account concerns no Sales Manager.</li>
 *   <li><b>A customer asks for another salesperson</b> ({@link OwnerChangeRequested}, §10.5 step 2): the managers only;
 *       the current owner hears nothing before the decision.</li>
 *   <li><b>The decision</b> ({@link OwnerChangeRequestDecided}, §10.5 step 4). Approved: both salespeople - the one who
 *       had the customer (if still a Sales agent) and the new one. The customer is told by the chat, which carries
 *       every owner change made with "tell the customer" (a line in their open conversation, else a notice), so it is
 *       not told twice here. Rejected: the customer's portal account, pointed at the manager's answer on their account
 *       page.</li>
 * </ul>
 * The claim of a conversation from the queue (CLAIMED_FROM_QUEUE) is told by the chat and has no notice here. Titles
 * and bodies are the system's words from the Vietnamese bundle with names the system holds; never what anyone typed (a
 * customer's note, a manager's answer).
 */
@Component
class SalesOwnerNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(SalesOwnerNotificationListener.class);

    static final String CATEGORY = "SALES_OWNER";
    static final String ASSIGNMENT_LINK = "/admin/sales/assignment";
    static final String REQUESTS_LINK = "/admin/sales/owner-change-requests";
    /** The storefront's account page, where the card "Sale phụ trách của bạn" shows the request and its answer. */
    static final String CUSTOMER_ACCOUNT_LINK = "/account";

    private final SalesNotices sales;
    private final SalesStaff salesStaff;
    private final IdentityService identity;
    private final CustomerDirectory customers;
    private final Inbox inbox;

    SalesOwnerNotificationListener(SalesNotices sales, SalesStaff salesStaff, IdentityService identity,
                                   CustomerDirectory customers, Inbox inbox) {
        this.sales = sales;
        this.salesStaff = salesStaff;
        this.identity = identity;
        this.customers = customers;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(CustomersNeedSalesOwner released) {
        List<UUID> managers = sales.managers();
        if (managers.isEmpty()) {
            log.warn("The book of account {} was released; no manager is active to be told", released.formerUserId());
            return;
        }
        int told = inbox.post(managers, released(released, name(released.formerUserId())));
        log.info("Book of account {} released ({} customers, {} leads): {} manager(s) told",
                released.formerUserId(), released.customerIds().size(), released.openLeads(), told);
    }

    @ApplicationModuleListener
    public void on(StaffAccountReactivated back) {
        if (!salesStaff.isActiveSalesAgent(back.userId())) {
            return;
        }
        List<UUID> managers = sales.managers();
        if (!managers.isEmpty()) {
            inbox.post(managers, back(back, name(back.userId())));
        }
    }

    @ApplicationModuleListener
    public void on(OwnerChangeRequested requested) {
        List<UUID> managers = sales.managers();
        if (managers.isEmpty()) {
            log.warn("Customer {} asked to change salesperson; no manager is active to be told", requested.customerId());
            return;
        }
        inbox.post(managers, requested(requested, customerName(requested.customerId())));
    }

    @ApplicationModuleListener
    public void on(OwnerChangeRequestDecided decided) {
        String customerName = customerName(decided.customerId());
        if (!decided.approved()) {
            customers.portalAccount(decided.customerId()).ifPresentOrElse(
                    account -> inbox.post(List.of(account), rejected(decided)),
                    () -> log.info("Request {} rejected; its customer has no portal account to tell",
                            decided.requestId()));
            return;
        }
        String newName = name(decided.newOwnerId());
        inbox.post(List.of(decided.newOwnerId()), approvedToNew(decided, customerName));
        UUID previous = decided.previousOwnerId();
        if (previous != null && !previous.equals(decided.newOwnerId()) && salesStaff.isActiveSalesAgent(previous)) {
            inbox.post(List.of(previous), approvedToPrevious(decided, customerName, newName));
        }
    }

    static InboxMessage released(CustomersNeedSalesOwner released, String formerName) {
        ResourceBundle texts = texts();
        String cause = texts.containsKey("inbox.sales.released.cause." + released.cause())
                ? texts.getString("inbox.sales.released.cause." + released.cause())
                : texts.getString("inbox.sales.released.cause.LOCKED");
        return new InboxMessage(CATEGORY, InboxSeverity.WARN,
                fit(texts.getString("inbox.sales.released.title").formatted(formerName), InboxMessage.TITLE_MAX),
                texts.getString("inbox.sales.released.body").formatted(released.customerIds().size(),
                        released.openLeads(), formerName, cause),
                ASSIGNMENT_LINK, "USER", released.formerUserId().toString(),
                "sales-book-released:%s:%d".formatted(released.formerUserId(), released.at().toEpochMilli()));
    }

    static InboxMessage back(StaffAccountReactivated back, String name) {
        ResourceBundle texts = texts();
        return new InboxMessage(CATEGORY, InboxSeverity.INFO,
                fit(texts.getString("inbox.sales.back.title").formatted(name), InboxMessage.TITLE_MAX),
                texts.getString("inbox.sales.back.body"), ASSIGNMENT_LINK, "USER", back.userId().toString(),
                "sales-agent-back:%s:%d".formatted(back.userId(), back.at().toEpochMilli()));
    }

    static InboxMessage requested(OwnerChangeRequested requested, String customerName) {
        ResourceBundle texts = texts();
        return new InboxMessage(CATEGORY, InboxSeverity.INFO,
                fit(texts.getString("inbox.sales.ownerChangeRequested.title").formatted(customerName),
                        InboxMessage.TITLE_MAX),
                texts.getString("inbox.sales.ownerChangeRequested.body"), REQUESTS_LINK, "SALES_OWNER_CHANGE_REQUEST",
                requested.requestId().toString(), "owner-change-requested:" + requested.requestId());
    }

    static InboxMessage approvedToNew(OwnerChangeRequestDecided decided, String customerName) {
        ResourceBundle texts = texts();
        return new InboxMessage(CATEGORY, InboxSeverity.INFO,
                fit(texts.getString("inbox.sales.ownerChangeApproved.new.title").formatted(customerName),
                        InboxMessage.TITLE_MAX),
                texts.getString("inbox.sales.ownerChangeApproved.new.body"),
                "/admin/customers/" + decided.customerId(), "CUSTOMER_PROFILE", decided.customerId().toString(),
                "owner-change-approved:" + decided.requestId());
    }

    static InboxMessage approvedToPrevious(OwnerChangeRequestDecided decided, String customerName, String newName) {
        ResourceBundle texts = texts();
        return new InboxMessage(CATEGORY, InboxSeverity.INFO,
                fit(texts.getString("inbox.sales.ownerChangeApproved.previous.title").formatted(customerName),
                        InboxMessage.TITLE_MAX),
                texts.getString("inbox.sales.ownerChangeApproved.previous.body").formatted(newName),
                "/admin/customers/" + decided.customerId(), "CUSTOMER_PROFILE", decided.customerId().toString(),
                "owner-change-approved:" + decided.requestId());
    }

    static InboxMessage rejected(OwnerChangeRequestDecided decided) {
        ResourceBundle texts = texts();
        return new InboxMessage(CATEGORY, InboxSeverity.INFO, texts.getString("inbox.customer.ownerChangeRejected.title"),
                texts.getString("inbox.customer.ownerChangeRejected.body"), CUSTOMER_ACCOUNT_LINK,
                "SALES_OWNER_CHANGE_REQUEST", decided.requestId().toString(),
                "owner-change-rejected:" + decided.requestId());
    }

    private String name(UUID userId) {
        if (userId == null) {
            return "-";
        }
        String name = identity.displayNames(List.of(userId)).get(userId);
        return name == null || name.isBlank() ? "-" : name.strip();
    }

    private String customerName(UUID customerId) {
        String name = customers.contact(customerId).map(CustomerContact::name).orElse(null);
        return name == null || name.isBlank() ? "-" : name.strip();
    }

    private static String fit(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static ResourceBundle texts() {
        return ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
    }
}
