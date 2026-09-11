package com.stockflow.notification.internal.domain;

import com.stockflow.common.domain.AggregateRoot;

import java.util.UUID;

/**
 * <b>Aggregate root of the notification module.</b>
 *
 * <p>STARTER STUB, and optional. Add persistence only if the team decides notifications must be
 * stored (delivery status, an in-app inbox). If notifications are fire-and-forget through
 * {@code NotificationSender}, this aggregate and its future table are not needed — delete this
 * file. Kept here as the sample of the shape.</p>
 *
 * <p>If kept: no framework annotation on the aggregate
 * ({@code ArchitectureTest.domainDoesNotDependOnFrameworks}); one method per state transition
 * (queued → sent → failed), each calling {@code registerEvent(...)}. See
 * {@code inventory.internal.domain.StockItem} and {@code docs/adding-a-module.md} §4.2.</p>
 */
public final class Notification extends AggregateRoot {

    private final UUID id;

    private Notification(UUID id) {
        this.id = java.util.Objects.requireNonNull(id, "id");
    }

    /** TODO: replace with the real birth of the aggregate, taking the fields it needs. */
    public static Notification create(UUID id) {
        return new Notification(id);
    }

    public UUID id() {
        return id;
    }
}
