package com.stockflow.notification.api;

import com.stockflow.common.api.PageResponse;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Delivery visibility and transactional dispatch control; only after-commit listeners perform
 * transport.
 */
public interface NotificationService {
    int prepareSupplierDelivery(UUID purchaseOrderId, boolean recovery);

    void suppressSupplierDelivery(UUID purchaseOrderId);

    void prepareSupplierCancellation(UUID purchaseOrderId);

    /**
     * Stops the order's own delivery that has not reached the supplier yet - still queued, or failing and being
     * retried - from reaching them after an amendment notice (SCRUM-117). Called in the transaction that approves an
     * amendment of a sent order, right before its notice is published: the notice carries the whole order as amended,
     * so the pre-amendment order arriving after it would tell the supplier the opposite of what was decided.
     *
     * <p>A no-op when there is nothing to stop: the delivery succeeded, it was suppressed (the supplier answered, or
     * the order ended), or its current generation already failed for good. Decided here, under the order's delivery
     * lock, rather than by the caller from a status read, so a retry finishing meanwhile cannot slip between the check
     * and the change. Suppression and the cancellation notice are not touched.</p>
     *
     * <p>Otherwise the delivery generation is advanced, so the listener drops any event of the earlier one, and the new
     * generation is recorded as finished: one terminal attempt with failure {@code SUPERSEDED_BY_AMENDMENT}, addressed
     * where the notice goes. Recorded as finished because a recovery requires the current generation to be terminal: a
     * generation with no attempt at all would leave the order unrecoverable and its delivery shown as queued forever.
     * So the order's own delivery reads {@code FAILED} with that reason - it never did reach the supplier - and a
     * recovery still resends the order as it now stands.</p>
     *
     * @param channel   the supplier's channel the amendment notice uses, recorded on the closing attempt
     * @param recipient the address on it
     */
    void supersedeSupplierDelivery(UUID purchaseOrderId, String channel, String recipient);

    void validateSupplierDelivery(String channel, String recipient);

    Map<UUID, String> purchaseOrderDeliveryStatuses(Collection<UUID> ids);

    Map<UUID, String> purchaseOrderCancellationStatuses(Collection<UUID> ids);

    /**
     * The delivery state of the latest attempt of the newest amendment notice attempted (highest revision), per order,
     * with that revision: the caller shows it only for the order's active revision, since a later amendment whose
     * notice was not attempted yet has no state of its own. An order is absent until its first notice was attempted.
     */
    Map<UUID, AmendmentDeliveryStatus> purchaseOrderAmendmentStatuses(Collection<UUID> ids);

    PageResponse<DeliveryAttemptSummary> purchaseOrderDeliveries(
            UUID purchaseOrderId, int page, int size);

    /**
     * Sends a customer portal invitation now, outside any transaction. Call it after the invitation
     * has committed. Unlike the purchase-order mail this is not an event: the e-mail carries the only
     * copy of the secret, and an event payload is persisted until it is processed. A failure is
     * thrown to the caller, who reports it and lets Sales resend - which issues a new secret.
     *
     * @throws org.springframework.mail.MailException when the mail server refuses or cannot be reached
     */
    void sendPortalInvitation(PortalInvitationMail mail);
}
