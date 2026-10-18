package com.stockflow.notification.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * How a staff notice is delivered, the way develop's stock alerts are (#102, {@code InventoryAlertNotificationListener}):
 * <b>in-app first</b>, one inbox row per recipient and {@code dedupeKey}, in the listener's transaction - that row is
 * the record, and it needs no mail server; then <b>e-mail, best effort</b>, only to the people newly given the row,
 * so a redelivered event mails nobody twice, and a mail server that refuses is logged (by its class name) and never
 * fails or replays the event. Titles, bodies and addresses are never logged.
 */
@Component
class StaffNotices {

    private static final Logger log = LoggerFactory.getLogger(StaffNotices.class);

    private final Inbox inbox;

    StaffNotices(Inbox inbox) {
        this.inbox = inbox;
    }

    /**
     * @param mail sends one e-mail to the addresses given; called once, with the non-blank addresses of the people
     *             newly told, and not at all when there are none
     * @return how many recipients were newly told
     */
    int deliver(List<StaffContact> recipients, InboxMessage message, Consumer<List<String>> mail) {
        Set<String> addresses = new LinkedHashSet<>();
        int told = 0;
        for (StaffContact recipient : recipients) {
            // One at a time: the count says who is newly told, and only they are e-mailed.
            if (recipient.userId() != null && inbox.post(List.of(recipient.userId()), message) == 1) {
                told++;
                if (recipient.email() != null && !recipient.email().isBlank()) {
                    addresses.add(recipient.email().strip());
                }
            }
        }
        if (!addresses.isEmpty()) {
            try {
                mail.accept(new ArrayList<>(addresses));
            } catch (RuntimeException failure) {
                log.warn("{} notice {}: e-mail to {} recipient(s) failed ({}); the in-app notice stands",
                        LogSafe.text(message.category()), LogSafe.text(message.sourceId()), addresses.size(),
                        failure.getClass().getSimpleName());
            }
        }
        return told;
    }
}
