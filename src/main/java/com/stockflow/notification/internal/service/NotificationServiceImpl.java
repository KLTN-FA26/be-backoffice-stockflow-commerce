package com.stockflow.notification.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import com.stockflow.notification.api.DeliveryAttemptSummary;
import com.stockflow.notification.api.NotificationService;
import com.stockflow.notification.api.PortalInvitationMail;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Exposes delivery evidence without permitting callers to bypass the durable event workflow. */
@Service
@Transactional
class NotificationServiceImpl implements NotificationService {

    private final DeliveryLogJpaRepository logs;
    private final NotificationSender sender;

    NotificationServiceImpl(DeliveryLogJpaRepository logs, NotificationSender sender) {
        this.logs = logs;
        this.sender = sender;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void sendPortalInvitation(PortalInvitationMail mail) {
        sender.sendPortalInvitation(mail);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DeliveryAttemptSummary> productionOrderDeliveries(UUID productionOrderId, int page, int size) {
        return attempts(ProductionOrderMailListener.references(productionOrderId), page, size);
    }

    private PageResponse<DeliveryAttemptSummary> attempts(List<String> references, int page, int size) {
        var pageable = Pages.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return Pages.toResponse(
                logs.findByOperationReferenceIn(references, pageable)
                        .map(
                                log ->
                                        new DeliveryAttemptSummary(
                                                log.getId(),
                                                log.getChannel().name(),
                                                log.getStatus().name(),
                                                log.getCreatedAt(),
                                                log.getSentAt(),
                                                log.getError(),
                                                log.getDeliveryGeneration(),
                                                log.getAttemptNumber(),
                                                log.getRecipient(),
                                                log.getTemplateCode())));
    }
}
