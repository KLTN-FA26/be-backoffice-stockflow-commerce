package com.stockflow.order.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
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
 * Tells people about a quote's prices waiting for approval (kltn-docs 14 BR-10 as changed on 2026-11-26), in the in-app
 * inbox, after the step commits: the holders of {@value #APPROVES_PRICES} when a quote starts waiting - whoever asked
 * excepted, who may not decide it - and the Sales account that asked when it is approved or rejected.
 *
 * <p>Who hears follows the role matrix as it is edited, not a role name in code. The inbox is idempotent per recipient
 * and key, so a replayed event posts nothing new. Replaces {@code QuoteDiscountAlerts} (decision D-1, 2026-11-25), which
 * told the managers only after a quote priced too low had already reached the customer.</p>
 */
@Component
class QuotePriceReviewNotices {

    private static final Logger log = LoggerFactory.getLogger(QuotePriceReviewNotices.class);

    static final String APPROVES_PRICES = "sales-quote-price-approvals:APPROVE";

    private final IdentityService identities;
    private final Inbox inbox;

    QuotePriceReviewNotices(IdentityService identities, Inbox inbox) {
        this.identities = identities;
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(QuotePriceReviewRequested event) {
        List<UUID> approvers = identities.notificationRecipientsHolding(APPROVES_PRICES, null).stream()
                .map(StaffContact::userId).filter(id -> !id.equals(event.submittedBy())).distinct().toList();
        if (approvers.isEmpty()) {
            log.warn("Quote {} revision {} waits for its prices to be approved and nobody else holds {} - nobody was told",
                    LogSafe.text(event.quoteNumber()), event.revision(), APPROVES_PRICES);
            return;
        }
        ResourceBundle texts = texts();
        int told = inbox.post(approvers, new InboxMessage("QUOTE", InboxSeverity.WARN,
                texts.getString("inbox.quote.priceReview.requested.title").formatted(event.quoteNumber()),
                texts.getString("inbox.quote.priceReview.requested.body").formatted(event.quoteNumber(),
                        event.revision(), event.linesToReview()),
                link(event.quoteId()), "QUOTE", event.quoteId().toString(),
                "quote-price-review:%s:requested".formatted(event.reviewId())));
        log.info("Quote {} revision {} waits for its prices to be approved; {} approver(s) newly told",
                LogSafe.text(event.quoteNumber()), event.revision(), told);
    }

    @ApplicationModuleListener
    public void on(QuotePriceReviewDecided event) {
        boolean approved = "APPROVED".equals(event.outcome());
        ResourceBundle texts = texts();
        String key = approved ? "approved" : "rejected";
        String body = texts.getString("inbox.quote.priceReview." + key + ".body").formatted(event.quoteNumber(),
                event.revision(), event.reason() == null ? "" : event.reason());
        inbox.post(List.of(event.submittedBy()), new InboxMessage("QUOTE",
                approved ? InboxSeverity.INFO : InboxSeverity.WARN,
                texts.getString("inbox.quote.priceReview." + key + ".title").formatted(event.quoteNumber()),
                body.length() > InboxMessage.BODY_MAX ? body.substring(0, InboxMessage.BODY_MAX) : body,
                link(event.quoteId()), "QUOTE", event.quoteId().toString(),
                "quote-price-review:%s:%s".formatted(event.reviewId(), key)));
    }

    private static String link(UUID quoteId) {
        return "/admin/sales/quotes/" + quoteId;
    }

    private static ResourceBundle texts() {
        return ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
    }
}
