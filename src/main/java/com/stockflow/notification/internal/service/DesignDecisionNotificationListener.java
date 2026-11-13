package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.contracts.DesignReviewed;
import com.stockflow.notification.api.Inbox;
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
 * Tells the customer the Sales Manager decided their design (kltn-docs 12 §4.4, §4.5): in the app, to the design's
 * owner - approved with the SKU issued for it, or returned to be changed. One inbox row per decision
 * ({@code design-decision:<design>:<decided at>}), so a redelivered event tells nobody twice. System text only: the
 * reason a design was returned is read in the design itself.
 */
@Component
class DesignDecisionNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(DesignDecisionNotificationListener.class);

    static final String CATEGORY = "DESIGN_DECISION";
    static final String SOURCE_TYPE = "DESIGN";

    private final Inbox inbox;

    DesignDecisionNotificationListener(Inbox inbox) {
        this.inbox = inbox;
    }

    @ApplicationModuleListener
    public void on(DesignReviewed event) {
        if (event.ownerUserId() == null) {
            log.warn("Design {} was decided but has no owner to tell", event.designId());
            return;
        }
        int told = inbox.post(List.of(event.ownerUserId()), message(event));
        log.info("Design {} {}: owner told ({} new)", event.designId(), LogSafe.text(event.outcome()), told);
    }

    static InboxMessage message(DesignReviewed event) {
        ResourceBundle texts = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        boolean approved = "APPROVED".equals(event.outcome());
        String sku = LogSafe.text(event.sku());
        String prefix = approved ? "inbox.design.approved." : "inbox.design.returned.";
        return new InboxMessage(CATEGORY, approved ? InboxSeverity.INFO : InboxSeverity.WARN,
                texts.getString(prefix + "title").formatted(sku), texts.getString(prefix + "body").formatted(sku),
                "/designs/" + event.designId(), SOURCE_TYPE, event.designId().toString(),
                "design-decision:%s:%d".formatted(event.designId(), event.decidedAt().toEpochMilli()));
    }
}
