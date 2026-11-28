package com.stockflow.order.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.ContractChangeRequested;
import com.stockflow.contracts.ContractSignDeadlinePassed;
import com.stockflow.contracts.ContractTermsReviewDecided;
import com.stockflow.contracts.ContractTermsReviewRequested;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * Tells the people on Sales' side about a contract's negotiation and its signing deadline, in the in-app inbox, after the
 * step commits (contract e-signing plan of 2026-11-28):
 * <ul>
 *   <li>the customer asked for changes, and a sent contract passed its signing deadline: whoever looks after the order's
 *       customer today - the owner, the owner's cover, or with no active owner the Sales Managers, the notice marked
 *       "cần phân công" ({@link SalesRecipients}); never every salesperson;</li>
 *   <li>a draft's terms wait for a Sales Manager: the holders of {@value #APPROVES_TERMS}, whoever asked excepted, who may
 *       not be the one deciding their own request;</li>
 *   <li>the terms were approved or rejected: the account that asked, and whoever looks after the customer when that is
 *       someone else; the one who decided is not told of their own decision.</li>
 * </ul>
 * Who hears follows the role matrix as it is edited, not a role name in code. System text only: numbers and days, never
 * the customer's reason or a manager's note, which are on the contract. The inbox posts a key to a recipient once, so a
 * replayed event tells nobody twice.
 */
@Component
class ContractNotices {

    private static final Logger log = LoggerFactory.getLogger(ContractNotices.class);

    static final String APPROVES_TERMS = "sales-contract-terms:APPROVE";
    private static final String CATEGORY = SalesFollowUpService.CATEGORY;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final IdentityService identities;
    private final Inbox inbox;
    private final SalesRecipients recipients;

    ContractNotices(IdentityService identities, Inbox inbox, SalesRecipients recipients) {
        this.identities = identities;
        this.inbox = inbox;
        this.recipients = recipients;
    }

    @ApplicationModuleListener
    public void on(ContractChangeRequested event) {
        ResourceBundle texts = texts();
        tellSales(event.customerId(), event.contractNumber(), InboxSeverity.WARN, texts,
                texts.getString("inbox.sales.contract.changeRequested.title").formatted(event.contractNumber()),
                texts.getString("inbox.sales.contract.changeRequested.body").formatted(event.contractNumber(),
                        event.orderNumber(), event.revision(), event.revision() + 1),
                event.orderId(), "contract-change-requested:%s:%d".formatted(event.contractId(), event.revision()),
                null);
    }

    @ApplicationModuleListener
    public void on(ContractSignDeadlinePassed event) {
        ResourceBundle texts = texts();
        tellSales(event.customerId(), event.contractNumber(), InboxSeverity.WARN, texts,
                texts.getString("inbox.sales.contract.signDeadline.title").formatted(event.contractNumber()),
                texts.getString("inbox.sales.contract.signDeadline.body").formatted(event.contractNumber(),
                        event.orderNumber(), DAY.format(event.signBy())),
                event.orderId(), "contract-sign-deadline:%s:%d".formatted(event.contractId(), event.revision()), null);
    }

    @ApplicationModuleListener
    public void on(ContractTermsReviewRequested event) {
        List<UUID> approvers = identities.notificationRecipientsHolding(APPROVES_TERMS, null).stream()
                .map(StaffContact::userId).filter(id -> !id.equals(event.requestedBy())).distinct().toList();
        if (approvers.isEmpty()) {
            log.warn("Contract {} waits for its terms to be approved and nobody else holds {} - nobody was told",
                    LogSafe.text(event.contractNumber()), APPROVES_TERMS);
            return;
        }
        ResourceBundle texts = texts();
        int told = inbox.post(approvers, new InboxMessage(CATEGORY, InboxSeverity.WARN,
                texts.getString("inbox.sales.contract.termsReview.requested.title").formatted(event.contractNumber()),
                texts.getString("inbox.sales.contract.termsReview.requested.body").formatted(event.contractNumber(),
                        event.orderNumber()),
                link(event.orderId()), "SALES_CONTRACT", event.contractId().toString(),
                "contract-terms-review:%s:%d:requested".formatted(event.contractId(), event.occurredAt().toEpochMilli())));
        log.info("Contract {} waits for its terms to be approved; {} approver(s) newly told",
                LogSafe.text(event.contractNumber()), told);
    }

    @ApplicationModuleListener
    public void on(ContractTermsReviewDecided event) {
        boolean approved = "APPROVED".equals(event.outcome());
        ResourceBundle texts = texts();
        String key = approved ? "approved" : "rejected";
        String dedupe = "contract-terms-review:%s:%d:%s".formatted(event.contractId(),
                event.occurredAt().toEpochMilli(), key);
        tellSales(event.customerId(), event.contractNumber(), approved ? InboxSeverity.INFO : InboxSeverity.WARN, texts,
                texts.getString("inbox.sales.contract.termsReview." + key + ".title").formatted(event.contractNumber()),
                texts.getString("inbox.sales.contract.termsReview." + key + ".body").formatted(event.contractNumber(),
                        event.orderNumber()),
                event.orderId(), dedupe, event.requestedBy(), event.decidedBy());
    }

    /**
     * Posts to whoever looks after the customer - and {@code first}, who asked - except {@code not}; nothing when there is
     * nobody (logged, as every notice that finds no one).
     */
    private void tellSales(UUID customerId, String contractNumber, InboxSeverity severity, ResourceBundle texts,
                           String title, String body, UUID orderId, String dedupeKey, UUID first, UUID... not) {
        SalesRecipients.Recipients to = recipients.chooser().of(customerId).withFirst(first);
        List<UUID> told = new ArrayList<>(to.userIds());
        for (UUID except : not) {
            told.remove(except);
        }
        if (told.isEmpty()) {
            log.warn("{}: the customer has no active owner and no Sales Manager or admin is active - nobody was told",
                    LogSafe.text(dedupeKey));
            return;
        }
        String text = to.needsAssignment() ? body + "\n" + texts.getString("inbox.sales.needs-assignment") : body;
        inbox.post(told, new InboxMessage(CATEGORY, severity, title,
                text.length() > InboxMessage.BODY_MAX ? text.substring(0, InboxMessage.BODY_MAX) : text,
                link(orderId), "SALES_CONTRACT", dedupeKey.split(":")[1], dedupeKey));
        log.info("Contract {}: {} told", LogSafe.text(contractNumber), dedupeKey.split(":")[0]);
    }

    private static String link(UUID orderId) {
        return "/admin/orders/" + orderId;
    }

    private static ResourceBundle texts() {
        return ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
    }
}
