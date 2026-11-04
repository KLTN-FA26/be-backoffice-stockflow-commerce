package com.stockflow.production.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.SampleRequest;
import com.stockflow.production.internal.domain.SampleRequestRepository;
import com.stockflow.production.internal.domain.SampleStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The approved sample a production order is checked against (BR-PRD-05), resolved through the order and never taken
 * from a request. One place for "is it still the approved round of this design", which both the QC screen's reference
 * ({@link QcReferenceService}) and the QC round itself ({@link PrintRunService#recordQc}) rely on: a QC that passes an
 * order against a sample the screen would refuse to show is the gap this closes.
 */
@Component
class ApprovedSamples {

    private final SampleRequestRepository samples;

    ApprovedSamples(SampleRequestRepository samples) {
        this.samples = samples;
    }

    /**
     * @return the approved round; empty for a SAMPLE, and for an ORDER repeating a design printed to completion before
     *         (BR-PRD-08), which QC checks against the design file instead
     * @throws NotFoundException {@code approved-sample} when the order names a sample that does not exist
     * @throws BusinessException {@code CONFLICT} when that sample is no longer the APPROVED round of the order's design
     */
    Optional<SampleRequest> of(ProductionOrder order) {
        if (order.type() != ProductionOrder.Type.ORDER || order.approvedSampleId() == null) {
            return Optional.empty();
        }
        SampleRequest sample = samples.findById(order.approvedSampleId())
                .orElseThrow(() -> new NotFoundException("approved-sample", order.id()));
        // Released only for an APPROVED round of this customer, design and blank (BR-PRD-08); anything else is
        // data that changed underneath, and treating it as "approved" would mislead QC.
        if (sample.status() != SampleStatus.APPROVED
                || !sample.specification().designSnapshotId().equals(order.work().designSnapshotId())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Sample %s is no longer the approved round of production order %s"
                            .formatted(sample.requestNumber(), order.orderNumber()));
        }
        return Optional.of(sample);
    }
}
