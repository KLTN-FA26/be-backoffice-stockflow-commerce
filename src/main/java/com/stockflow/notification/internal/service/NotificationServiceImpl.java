package com.stockflow.notification.internal.service;

import com.stockflow.notification.api.NotificationService;
import com.stockflow.notification.api.DeliveryAttemptSummary;
import com.stockflow.notification.internal.domain.SupplierEndpointPolicy;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;
import com.stockflow.notification.internal.repository.PoDeliveryControlRepository;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exposes delivery evidence without permitting callers to bypass the durable event workflow.
 */
@Service
@Transactional
class NotificationServiceImpl implements NotificationService {

    private final DeliveryLogJpaRepository logs;
    private final SupplierEndpointPolicy policy;
    private final PoDeliveryControlRepository controls;

    NotificationServiceImpl(DeliveryLogJpaRepository logs, PoDeliveryControlRepository controls,
            @Value("${stockflow.notification.supplier-api-allowed-hosts:}") String hosts) {
        this.logs = logs;
        this.controls = controls;
        this.policy = new SupplierEndpointPolicy(hosts);
    }

    @Override
    public void validateSupplierDelivery(String channel, String recipient) { policy.validate(channel, recipient); }

    @Override
    public int prepareSupplierDelivery(UUID id, boolean recovery) {
        var control = controls.lock(id);
        String reference = "purchase-order:" + id;
        if (control.suppressed() || logs.existsByExternalReference(reference))
            throw new BusinessException(ErrorCode.CONFLICT, "Delivery is suppressed or already successful");
        if (!recovery) return control.generation();
        if (!logs.existsByOperationReferenceAndDeliveryGenerationAndTerminalTrue(reference, control.generation()))
            throw new BusinessException(ErrorCode.CONFLICT, "Only a terminal failed delivery can be recovered");
        int generation = Math.addExact(control.generation(), 1);
        controls.advance(id, generation);
        return generation;
    }

    @Override
    public void suppressSupplierDelivery(UUID id) { controls.suppress(id); }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> purchaseOrderDeliveryStatuses(Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        var references = ids.stream().map(id -> "purchase-order:" + id).toList();
        var result = new HashMap<UUID, String>();
        for (var row : logs.latestStatuses(references)) {
            result.put(UUID.fromString(row.getReference().substring("purchase-order:".length())),
                    switch (row.getStatus()) {
                        case "SENT" -> "DELIVERED";
                        case "QUEUED", "SUPPRESSED" -> row.getStatus();
                        default -> row.getTerminal() ? "FAILED" : "RETRYING";
                    });
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DeliveryAttemptSummary> purchaseOrderDeliveries(UUID purchaseOrderId, int page, int size) {
        var pageable = Pages.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return Pages.toResponse(logs.findByOperationReference("purchase-order:" + purchaseOrderId, pageable)
                .map(log -> new DeliveryAttemptSummary(log.getId(), log.getChannel().name(),
                        log.getStatus().name(), log.getCreatedAt(), log.getSentAt(), log.getError(),
                        log.getDeliveryGeneration(), log.getRecipient())));
    }
}
