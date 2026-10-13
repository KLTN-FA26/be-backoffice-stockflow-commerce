package com.stockflow.notification.api;

import java.util.Collection;
import java.util.UUID;

/**
 * The in-app inbox (the bell): how another module tells a user something.
 *
 * <p>The recipient reads it through {@code GET /api/v1/notifications/me}; nothing else reads it. There is no
 * transport here, no e-mail and no template: this is a row per recipient.</p>
 *
 * <h2>What callers pass</h2>
 *
 * <p><b>Codes, ids and a short system-generated title</b> - never text a user typed. A customer's name, an
 * order note, a rejection reason or a payment provider's failure text must not be copied into
 * {@code title} or {@code body}: they are stored, shown to other people and, because the inbox is a place
 * people trust, easy to abuse. Put the id in {@code sourceId} and let the screen the {@code link} opens show
 * the detail under its own permission.</p>
 *
 * <p><b>Do not log the title or the body</b>, here or in the caller. Log ids with
 * {@code LogSafe.text(...)} ({@code NotificationSender} does the same, and hotfix #99 fixed it for that
 * reason).</p>
 *
 * <h2>Transaction and idempotence</h2>
 *
 * <p>{@link #post} joins the caller's transaction: a message is posted exactly when the caller commits, and a
 * rollback takes it back. It is idempotent per recipient and {@code dedupeKey}: calling it again with the same
 * key, concurrently or after a retry of a failed event listener, creates nothing new. The key is the caller's
 * way to say "once per day", "once per episode" or "once per document".</p>
 */
public interface Inbox {

    /**
     * Posts {@code message} to each recipient that has not already received its {@code dedupeKey}.
     *
     * <p>{@code null} and repeated ids are ignored, and an empty collection posts nothing. Every recipient
     * must be an existing user: an unknown id fails the call (and the caller's transaction) with a
     * {@code DataIntegrityViolationException}, because that is a bug in how the caller found its recipients.</p>
     *
     * @return how many recipients were newly given the message; the others already had it
     */
    int post(Collection<UUID> recipients, InboxMessage message);
}
