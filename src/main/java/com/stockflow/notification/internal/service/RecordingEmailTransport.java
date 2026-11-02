package com.stockflow.notification.internal.service;

import com.stockflow.common.id.Identifiers;
import com.stockflow.notification.internal.domain.Email;
import com.stockflow.notification.internal.domain.EmailProvider;
import com.stockflow.notification.internal.domain.EmailReceipt;
import com.stockflow.notification.internal.domain.EmailTransport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Sends nothing; keeps the last {@value #CAPACITY} messages in memory. The provider of the test profile, so that no
 * test reaches a real inbox or a real provider, and a test can read what would have been sent.
 *
 * <p>Public only so integration tests in other packages can read it; no production code depends on it, and it is never
 * the provider of a server profile.</p>
 */
public final class RecordingEmailTransport implements EmailTransport {

    static final int CAPACITY = 500;

    private static final Logger log = LoggerFactory.getLogger(RecordingEmailTransport.class);

    private final Deque<Email> sent = new ConcurrentLinkedDeque<>();

    @Override
    public EmailProvider provider() {
        return EmailProvider.RECORDING;
    }

    @Override
    public EmailReceipt send(Email email) {
        sent.addLast(email);
        while (sent.size() > CAPACITY) {
            sent.pollFirst();
        }
        log.info("E-mail recorded, not sent: {}", email);
        return new EmailReceipt(EmailProvider.RECORDING, "recorded-" + Identifiers.newId());
    }

    /** What was recorded, oldest first. */
    public List<Email> sent() {
        return new ArrayList<>(sent);
    }

    /** What was recorded for one address, oldest first. */
    public List<Email> sentTo(String address) {
        return sent.stream().filter(email -> email.to().stream().anyMatch(address::equalsIgnoreCase)).toList();
    }

    public void clear() {
        sent.clear();
    }
}
