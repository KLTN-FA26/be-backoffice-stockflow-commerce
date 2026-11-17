package com.stockflow.notification.api;

import com.stockflow.common.api.PageResponse;

import java.util.UUID;

/**
 * Delivery visibility; only after-commit listeners perform transport. The purchase order's delivery control (prepare,
 * suppress, supersede, recover, statuses) went with purchasing (4A-8): the producer's messages of a production order
 * are its successor (4A-3).
 */
public interface NotificationService {

    /**
     * Every attempt to tell a production order's producer something (4A-3): that it was sent, that its sample was
     * approved, that it stopped - newest first, each named by its template code ({@code production_order_sent},
     * {@code production_order_sample_approved}, {@code production_order_stopped}). Empty until the first message was
     * attempted; a producer handed the slip by the coordinator has each message recorded as SKIPPED.
     */
    PageResponse<DeliveryAttemptSummary> productionOrderDeliveries(UUID productionOrderId, int page, int size);

    /**
     * Sends a customer portal invitation now, outside any transaction. Call it after the invitation
     * has committed. Unlike a producer's mail this is not an event: the e-mail carries the only
     * copy of the secret, and an event payload is persisted until it is processed. A failure is
     * thrown to the caller, who reports it and lets Sales resend - which issues a new secret.
     *
     * @throws RuntimeException when the mail provider (Resend, or the SMTP relay) refuses the message or cannot be
     *                          reached; the caller records the invitation as not delivered
     */
    void sendPortalInvitation(PortalInvitationMail mail);
}
