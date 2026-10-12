package com.stockflow.production.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import com.stockflow.production.internal.domain.SampleRequest;
import com.stockflow.production.internal.domain.SampleRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * {@link SampleProductionProgress} on the round's row lock. Joins the caller's transaction (the
 * production-order workflow), which is the point: the round and the order commit together.
 */
@Service
@Transactional
class SampleProductionProgressService implements SampleProductionProgress {

    private static final Logger log = LoggerFactory.getLogger(SampleProductionProgressService.class);

    private final ProductionOrderRepository productionOrders;
    private final SampleRequestRepository samples;

    SampleProductionProgressService(ProductionOrderRepository productionOrders, SampleRequestRepository samples) {
        this.productionOrders = productionOrders;
        this.samples = samples;
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "sample-request", resourceId = "#productionOrderId")
    public void productionStarted(UUID productionOrderId) {
        report(productionOrderId, SampleRequest::productionStarted, "in production");
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "sample-request", resourceId = "#productionOrderId")
    public void productionCompleted(UUID productionOrderId) {
        report(productionOrderId, SampleRequest::productionCompleted, "ready to send");
    }

    private void report(UUID productionOrderId, Predicate<SampleRequest> move, String outcome) {
        ProductionOrder order = productionOrders.findById(Objects.requireNonNull(productionOrderId, "productionOrderId"))
                .orElseThrow(() -> new NotFoundException("production-order", productionOrderId));
        if (order.type() != ProductionOrder.Type.SAMPLE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Production order " + order.orderNumber() + " makes an order line, not a sample");
        }
        SampleRequest sample = samples.findByIdForUpdate(order.sampleRequestId())
                .orElseThrow(() -> new NotFoundException("sample-request", order.sampleRequestId()));
        if (move.test(sample)) {
            samples.save(sample);
            log.info("Sample request {} is {} (production order {})", sample.requestNumber(), outcome,
                    order.orderNumber());
        }
    }
}
