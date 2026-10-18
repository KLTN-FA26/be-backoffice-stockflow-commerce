package com.stockflow.production.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.production.internal.repository.ProductionOrderSearch;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
class ProductionOrderQueryService implements ProductionOrders {

    /** {@code dueDate} first is the shop's queue order (19 §4.3); newest first is the default like every list. */
    private static final SortWhitelist SORT = SortWhitelist
            .of("createdAt", "dueDate", "orderNumber", "status")
            .withDefault("createdAt", Sort.Direction.DESC);

    private final ProductionOrderSearch search;

    ProductionOrderQueryService(ProductionOrderSearch search) {
        this.search = search;
    }

    @Override
    public PageResponse<Row> list(CurrentUser user, Criteria criteria) {
        ScopeGuards.requireBackOffice(user);
        return Pages.toResponse(search.search(new ProductionOrderSearch.Criteria(criteria.types(), criteria.statuses(),
                        criteria.warehouseId(), criteria.dueFrom(), criteria.dueTo(), criteria.sampleRequestId(),
                        criteria.salesOrderId(), criteria.number()),
                Pages.of(criteria.page(), criteria.size(), SORT.parse(criteria.sort())))
                .map(ProductionOrderQueryService::toRow));
    }

    @Override
    public Detail get(CurrentUser user, UUID productionOrderId) {
        ScopeGuards.requireBackOffice(user);
        ProductionOrderSearch.Detail detail = search.detail(productionOrderId)
                .orElseThrow(() -> new NotFoundException("production-order", productionOrderId));
        WarehouseScope.requireWarehouse(detail.row().warehouseId());
        return new Detail(toRow(detail.row()), detail.approvedSampleId(), detail.designChecksum(),
                detail.issuedQuantity(), detail.printedQuantity(), detail.scrapQuantity(), detail.prepressCheckedAt(),
                detail.completedAt(), detail.cancelledAt(), detail.cancellationReason(), detail.createdBy(),
                detail.lastModifiedAt(), detail.version(), detail.history().stream()
                .map(h -> new StatusChange(h.fromStatus(), h.toStatus(), h.reason(), h.changedAt(), h.changedBy()))
                .toList(), detail.origin(), detail.subcontract() == null ? null : new Subcontract(
                        detail.subcontract().method(), detail.subcontract().subcontractorId(),
                        detail.subcontract().unitPrice(), detail.subcontract().currency(),
                        detail.subcontract().purchaseOrderId()));
    }

    private static Row toRow(ProductionOrderSearch.Row r) {
        return new Row(r.id(), r.orderNumber(), r.type(), r.mode(), r.status(), r.holdReason(), r.warehouseId(),
                r.blankSku(), r.designSnapshotId(), r.plannedQuantity(), r.goodQuantity(), r.dueDate(),
                r.sampleRequestId(), r.salesOrderId(), r.salesOrderLineId(), r.parentId(), r.createdAt(),
                r.packingLocation());
    }
}
