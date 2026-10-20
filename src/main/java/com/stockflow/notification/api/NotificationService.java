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

    void validateSupplierDelivery(String channel, String recipient);

    Map<UUID, String> purchaseOrderDeliveryStatuses(Collection<UUID> ids);

    Map<UUID, String> purchaseOrderCancellationStatuses(Collection<UUID> ids);

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
