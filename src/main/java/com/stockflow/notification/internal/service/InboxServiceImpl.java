package com.stockflow.notification.internal.service;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.internal.repository.UserNotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Writes the inbox for other modules. Joins the caller's transaction, so a message exists exactly when the
 * caller's change does.
 *
 * <p>Neither the title nor the body is logged here (see {@link Inbox}): the log line carries counts and
 * the dedupe key's length only.</p>
 */
@Service
@Transactional
class InboxServiceImpl implements Inbox {

    private static final Logger log = LoggerFactory.getLogger(InboxServiceImpl.class);

    private final UserNotificationRepository repository;
    private final Clock clock;

    InboxServiceImpl(UserNotificationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public int post(Collection<UUID> recipients, InboxMessage message) {
        Objects.requireNonNull(message, "message");
        if (recipients == null || recipients.isEmpty()) {
            return 0;
        }
        // Repeated ids would be absorbed by the unique key anyway; skipping them saves the round trips and
        // keeps the returned count honest.
        Set<UUID> distinct = new LinkedHashSet<>(recipients);
        distinct.remove(null);
        Instant now = clock.instant();
        int created = 0;
        for (UUID recipient : distinct) {
            if (repository.insertIfAbsent(Identifiers.newId(), recipient, message, now)) {
                created++;
            }
        }
        log.debug("Inbox post category={} recipients={} created={}", LogSafe.text(message.category()), distinct.size(), created);
        return created;
    }
}
