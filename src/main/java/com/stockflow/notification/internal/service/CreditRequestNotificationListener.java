package com.stockflow.notification.internal.service;

import com.stockflow.contracts.CreditRequestDecided;
import com.stockflow.contracts.CreditRequestSubmitted;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.CustomerDirectory;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * Tells whoever acts next on a credit request (QĐ-A, 2026-11-26; kltn-docs 15 §4.3), in the in-app inbox:
 * <ul>
 *   <li>the customer filed one from the portal: Sales, to give their opinion - the active holders of
 *       {@value #SALES_AUDIENCE};</li>
 *   <li>it waits for the accountant: the active holders of {@value #ACCOUNTANT_AUDIENCE};</li>
 *   <li>it was decided: the customer, in their portal account's inbox, and the Sales who gave their opinion on it (for
 *       a request Sales filed, whoever filed it).</li>
 * </ul>
 *
 * <p>Who is told is decided by permission, not by role name, as an overdue installment's readers are
 * ({@link InstallmentOverdueNotificationListener}): it follows the role matrix as it is edited. There is no "Sales in
 * charge" of a customer in this system, so a customer's request goes to every Sales who may give the opinion. No
 * e-mail: as for the customer's other in-app notices (E23), e-mail to customers is a later phase. One message per
 * person per step ({@code credit-request:<id>:<step>}): a redelivered event tells nobody twice. System text only -
 * amounts and days; never the reason, the opinion or the accountant's note.</p>
 */
@Component
class CreditRequestNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(CreditRequestNotificationListener.class);

    static final String CATEGORY = "CREDIT_REQUEST";
    static final String SOURCE_TYPE = "CREDIT_REQUEST";
    /** Who gives Sales' opinion on a request. */
    static final String SALES_AUDIENCE = "customer-credit-requests:UPDATE";
    /** Who decides a request. */
    static final String ACCOUNTANT_AUDIENCE = "customer-credit-requests:APPROVE";

    private static final Locale VI = Locale.forLanguageTag("vi");

    private final StaffDirectory staff;
    private final CustomerDirectory customers;
    private final Inbox inbox;

    CreditRequestNotificationListener(StaffDirectory staff, CustomerDirectory customers, Inbox inbox) {
        this.staff = staff;
        this.customers = customers;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(CreditRequestSubmitted event) {
        boolean sales = event.stage() == CreditRequestSubmitted.Stage.AWAITING_SALES;
        String audience = sales ? SALES_AUDIENCE : ACCOUNTANT_AUDIENCE;
        List<UUID> recipients = staff.activeHolders(audience, null).stream().map(StaffContact::userId)
                .filter(Objects::nonNull).filter(id -> !id.equals(event.submittedBy())).distinct().toList();
        if (recipients.isEmpty()) {
            log.warn("Credit request {} waits ({}) and nobody holds {} to be told", event.creditRequestId(),
                    event.stage(), audience);
            return;
        }
        int told = inbox.post(recipients, submitted(event));
        log.info("Credit request {} waits ({}): {} recipient(s), {} newly told", event.creditRequestId(),
                event.stage(), recipients.size(), told);
    }

    @ApplicationModuleListener
    public void on(CreditRequestDecided event) {
        UUID account = event.customerId() == null ? null : customers.portalAccount(event.customerId()).orElse(null);
        int customerTold = account == null ? 0 : inbox.post(List.of(account), decidedForCustomer(event));
        int salesTold = event.recommendedBy() == null ? 0
                : inbox.post(List.of(event.recommendedBy()), decidedForSales(event));
        log.info("Credit request {} {}: customer {}, Sales {}", event.creditRequestId(), event.outcome(),
                account == null ? "has no portal account" : customerTold == 1 ? "told" : "already told",
                salesTold == 1 ? "told" : "not told again");
    }

    static InboxMessage submitted(CreditRequestSubmitted event) {
        String step = event.stage() == CreditRequestSubmitted.Stage.AWAITING_SALES ? "awaiting-sales"
                : "awaiting-decision";
        String title = texts().getString("inbox.credit-request." + step + ".title")
                .formatted(amount(event.requestedLimit(), event.currency()));
        return new InboxMessage(CATEGORY, InboxSeverity.INFO, title, null, backOfficeLink(event.creditRequestId()),
                SOURCE_TYPE, event.creditRequestId().toString(),
                "credit-request:%s:%s".formatted(event.creditRequestId(), step));
    }

    static InboxMessage decidedForCustomer(CreditRequestDecided event) {
        boolean approved = event.outcome() == CreditRequestDecided.Outcome.APPROVED;
        String key = "inbox.credit-request.customer." + (approved ? "approved" : "rejected") + ".title";
        String title = approved
                ? texts().getString(key).formatted(amount(event.approvedLimit(), event.currency()),
                event.approvedTermDays())
                : texts().getString(key);
        return new InboxMessage(CATEGORY, approved ? InboxSeverity.INFO : InboxSeverity.WARN, title, null,
                "/credit-requests/" + event.creditRequestId(), SOURCE_TYPE, event.creditRequestId().toString(),
                "credit-request:%s:decided:customer".formatted(event.creditRequestId()));
    }

    static InboxMessage decidedForSales(CreditRequestDecided event) {
        boolean approved = event.outcome() == CreditRequestDecided.Outcome.APPROVED;
        String key = "inbox.credit-request." + (approved ? "approved" : "rejected") + ".title";
        String title = approved
                ? texts().getString(key).formatted(amount(event.approvedLimit(), event.currency()),
                event.approvedTermDays())
                : texts().getString(key);
        return new InboxMessage(CATEGORY, InboxSeverity.INFO, title, null, backOfficeLink(event.creditRequestId()),
                SOURCE_TYPE, event.creditRequestId().toString(),
                "credit-request:%s:decided".formatted(event.creditRequestId()));
    }

    private static String backOfficeLink(UUID id) {
        return "/admin/payment/credit-requests/" + id;
    }

    private static ResourceBundle texts() {
        return ResourceBundle.getBundle("i18n.messages", VI);
    }

    private static String amount(BigDecimal value, String currency) {
        return value == null ? "-" : NumberFormat.getIntegerInstance(VI).format(value) + " " + currency;
    }
}
