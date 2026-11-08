package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.DesignBatchSubmittedForReview;
import com.stockflow.contracts.DesignSubmittedForReview;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Tells whoever may review designs that a customer's design waits for them (kltn-docs 12 §4.3 step 12): the holders of
 * {@value #REVIEWERS}, system-wide (permissions, not role names - the role matrix decides who that is).
 *
 * <p>Delivered as every staff notice ({@link StaffNotices}): in-app first, one inbox row per person per submission
 * ({@code design-review:<design>:<submitted at>} - a design returned and submitted again is a new notice), then e-mail
 * best effort to the people newly told, so a redelivered event tells nobody twice. System text only: the SKU, never the
 * design's name, which the customer typed.</p>
 */
@Component
class DesignReviewNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(DesignReviewNotificationListener.class);

    static final String CATEGORY = "DESIGN_REVIEW";
    static final String SOURCE_TYPE = "DESIGN";
    /** Who reviews a customer's design (DesignReviewController). */
    static final String REVIEWERS = "design-designs:APPROVE";

    private final NotificationSender sender;
    private final StaffDirectory staff;
    private final StaffNotices notices;

    DesignReviewNotificationListener(NotificationSender sender, StaffDirectory staff, StaffNotices notices) {
        this.sender = sender;
        this.staff = staff;
        this.notices = notices;
    }

    @ApplicationModuleListener
    public void on(DesignSubmittedForReview event) {
        List<StaffContact> recipients = staff.activeHolders(REVIEWERS, null);
        if (recipients.isEmpty()) {
            log.warn("Design {} waits for review, but nobody active holds {}; nobody was notified",
                    event.designId(), REVIEWERS);
            return;
        }
        InboxMessage message = message(event);
        int told = notices.deliver(recipients, message, emails -> sender.sendStaffNotice(emails, message,
                "staff_design_review"));
        log.info("Design {} waits for review: {} reviewer(s), {} newly told", event.designId(), recipients.size(),
                told);
    }

    /** A project's designs submitted together: one notice for the round (kltn-docs 12 §4.10), not one per design. */
    @ApplicationModuleListener
    public void on(DesignBatchSubmittedForReview event) {
        List<StaffContact> recipients = staff.activeHolders(REVIEWERS, null);
        if (recipients.isEmpty()) {
            log.warn("Review round {} waits, but nobody active holds {}; nobody was notified", event.roundId(),
                    REVIEWERS);
            return;
        }
        InboxMessage message = message(event);
        int told = notices.deliver(recipients, message, emails -> sender.sendStaffNotice(emails, message,
                "staff_design_review"));
        log.info("Review round {} of {} designs waits: {} reviewer(s), {} newly told", event.roundId(),
                event.designIds().size(), recipients.size(), told);
    }

    static InboxMessage message(DesignBatchSubmittedForReview event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        int designs = event.designIds().size();
        return new InboxMessage(CATEGORY, InboxSeverity.INFO, texts.getString("inbox.design.batch.title")
                .formatted(designs), texts.getString("inbox.design.batch.body").formatted(designs),
                "/admin/design-review-rounds/" + event.roundId(), "DESIGN_REVIEW_ROUND", event.roundId().toString(),
                "design-review-round:" + event.roundId());
    }

    static InboxMessage message(DesignSubmittedForReview event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        String sku = LogSafe.text(event.sku());
        return new InboxMessage(CATEGORY, InboxSeverity.INFO, texts.getString("inbox.design.review.title")
                .formatted(sku), texts.getString("inbox.design.review.body").formatted(sku),
                "/admin/designs/review-queue", SOURCE_TYPE, event.designId().toString(),
                "design-review:%s:%d".formatted(event.designId(), event.submittedAt().toEpochMilli()));
    }
}
