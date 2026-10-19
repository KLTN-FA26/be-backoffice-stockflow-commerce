package com.stockflow.production.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.api.DesignService;
import com.stockflow.design.api.FulfillmentArtifact;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import com.stockflow.production.internal.domain.SampleRequest;
import com.stockflow.production.internal.domain.SampleRequestRepository;
import com.stockflow.production.internal.domain.SampleStatus;
import com.stockflow.production.internal.repository.ProductionOrderSearch;
import com.stockflow.warehouse.api.StorageLocationView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Reads only. The approved sample is resolved through the production order, never taken from the request, so a
 * caller who may see this order cannot reach another customer's sample or design through it.
 */
@Service
@Transactional(readOnly = true)
class QcReferenceService implements QcReferences {

    private final ProductionOrderRepository orders;
    private final SampleRequestRepository samples;
    private final ProductionOrderSearch search;
    private final DesignService designs;
    private final WorkshopStock stock;

    QcReferenceService(ProductionOrderRepository orders, SampleRequestRepository samples, ProductionOrderSearch search,
                       DesignService designs, WorkshopStock stock) {
        this.orders = orders;
        this.samples = samples;
        this.search = search;
        this.designs = designs;
        this.stock = stock;
    }

    @Override
    public ApprovedSample approvedSample(CurrentUser user, UUID productionOrderId) {
        ProductionOrder order = visible(user, productionOrderId);
        if (order.type() != ProductionOrder.Type.ORDER || order.approvedSampleId() == null) {
            throw new NotFoundException("approved-sample", productionOrderId);
        }
        SampleRequest sample = samples.findById(order.approvedSampleId())
                .orElseThrow(() -> new NotFoundException("approved-sample", productionOrderId));
        // Released only for an APPROVED round of this customer, design and blank (BR-PRD-08); anything else is
        // data that changed underneath, and showing it as "approved" would mislead QC.
        if (sample.status() != SampleStatus.APPROVED
                || !sample.specification().designSnapshotId().equals(order.work().designSnapshotId())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Sample %s is no longer the approved round of production order %s"
                            .formatted(sample.requestNumber(), order.orderNumber()));
        }
        ProductionOrderSearch.Row printed = search.forSamples(List.of(sample.id())).get(sample.id());
        return new ApprovedSample(order.id(), order.orderNumber(), sample.id(), sample.requestNumber(), sample.round(),
                sample.respondedAt(), order.work().designSnapshotId(), order.work().designChecksum(),
                printed == null ? null : printed.id(), printed == null ? null : printed.orderNumber(), preview(order));
    }

    @Override
    public List<String> packingLocations(CurrentUser user, UUID productionOrderId) {
        ProductionOrder order = visible(user, productionOrderId);
        return stock.packingAreas(order).stream().map(StorageLocationView::locationCode).sorted().toList();
    }

    private ProductionOrder visible(CurrentUser user, UUID productionOrderId) {
        ScopeGuards.requireBackOffice(user);
        ProductionOrder order = orders.findById(Objects.requireNonNull(productionOrderId, "productionOrderId"))
                .orElseThrow(() -> new NotFoundException("production-order", productionOrderId));
        WarehouseScope.requireWarehouse(order.work().warehouseId());
        return order;
    }

    /**
     * The customer-facing picture of the confirmed bundle, checked against the order's frozen checksum first: a
     * changed file is a 409 here as it is for the print file. A bundle confirmed without such a picture has none
     * to show; that is an answer, not an error.
     */
    private Preview preview(ProductionOrder order) {
        FulfillmentArtifact file;
        try {
            file = designs.fulfillmentDownload(order.work().designSnapshotId(), order.work().designChecksum(),
                    DesignArtifactRole.CUSTOMER_PREVIEW);
        } catch (BusinessException missing) {
            if (missing.errorCode() == ErrorCode.NOT_FOUND) {
                return null;
            }
            throw missing;
        }
        return new Preview(file.artifactId(), file.originalName(), file.contentType(), file.sizeBytes(),
                file.checksum(), file.url(), file.expiresAt());
    }
}
