package com.stockflow.notification.api;

/**
 * THE public API of the notification module — the only package other modules may import.
 *
 * <p>STARTER STUB, and optional. Notification is largely event-driven: it reacts to
 * {@code OrderPlaced}, {@code PaymentFailed} and {@code ShipmentDispatched} through
 * {@code internal.service.OrderNotificationListener}, and an event-driven module often needs no
 * synchronous api at all. Keep this interface only if another module must trigger a notification
 * inside its own transaction; otherwise delete it and let the listeners do the work.</p>
 *
 * <p>If kept: every parameter and return type is a record or enum declared in THIS package, never a
 * domain object or JPA entity ({@code ArchitectureTest.theApiPackageLeaksNothingInternal}).</p>
 */
public interface NotificationService {

    // TODO: keep only if a synchronous trigger is genuinely needed; otherwise delete this file
    //       and rely on the event listeners. See docs/adding-a-module.md §1.
}
