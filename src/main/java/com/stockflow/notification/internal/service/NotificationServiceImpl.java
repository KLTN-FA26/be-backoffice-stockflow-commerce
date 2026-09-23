package com.stockflow.notification.internal.service;

import com.stockflow.notification.api.NotificationService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exposes delivery evidence without permitting callers to bypass the durable event workflow.
 */
@Service
@Transactional
class NotificationServiceImpl implements NotificationService {

    private final com.stockflow.notification.internal.repository.DeliveryLogJpaRepository logs;

    NotificationServiceImpl(com.stockflow.notification.internal.repository.DeliveryLogJpaRepository logs) {
        this.logs = logs;
    }

    @Override
    @Transactional(readOnly = true)
    public com.stockflow.common.api.PageResponse<com.stockflow.notification.api.DeliveryAttemptSummary> purchaseOrderDeliveries(
            java.util.UUID purchaseOrderId, int page, int size) {
        var pageable = com.stockflow.common.persistence.Pages.of(page, size,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt", "id"));
        return com.stockflow.common.persistence.Pages.toResponse(logs.findByOperationReference("purchase-order:" + purchaseOrderId, pageable)
                .map(log -> new com.stockflow.notification.api.DeliveryAttemptSummary(log.getId(), log.getChannel().name(),
                        log.getStatus().name(), log.getCreatedAt(), log.getSentAt(), log.getError())));
    }
}
