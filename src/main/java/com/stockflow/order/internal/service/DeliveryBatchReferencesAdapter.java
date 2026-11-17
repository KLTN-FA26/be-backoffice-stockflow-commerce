package com.stockflow.order.internal.service;

import com.stockflow.order.api.DeliveryBatchReferences;
import com.stockflow.order.internal.domain.SalesContractRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * {@link DeliveryBatchReferences} over the order's live contract: the schedule a pick task's batch was released from
 * (4B-8) is that contract's, so its batch is found there. Unscoped, as every read of another module is: the caller has
 * checked the warehouse of what it prints.
 */
@Component
class DeliveryBatchReferencesAdapter implements DeliveryBatchReferences {

    private final SalesContractRepository contracts;

    DeliveryBatchReferencesAdapter(SalesContractRepository contracts) {
        this.contracts = contracts;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Reference> of(UUID orderId, UUID deliveryBatchId) {
        if (orderId == null || deliveryBatchId == null) {
            return Optional.empty();
        }
        return contracts.findLiveByOrderId(orderId).flatMap(contract -> contract.schedule().stream()
                .filter(batch -> batch.id().equals(deliveryBatchId)).findFirst()
                .map(batch -> new Reference(contract.contractNumber(), batch.seq())));
    }
}
