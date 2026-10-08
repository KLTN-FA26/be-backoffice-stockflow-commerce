package com.stockflow.production.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.ConflictException;
import com.stockflow.contracts.OrderLinesReleasedForProduction;
import com.stockflow.design.api.DesignService;
import com.stockflow.production.internal.domain.ApprovedSample;
import com.stockflow.production.internal.domain.ApprovedSampleRepository;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import com.stockflow.production.internal.domain.SampleSpecification;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Deliberately not a Spring bean until the real persistence and order adapters exist. The future
 * Modulith listener must invoke this proxied transaction; it must never acknowledge an event
 * through an in-memory fallback. No stock is reserved and no payment policy is repeated here.
 */
public class OrderProductionService {
    private final ProductionOrderRepository orders;
    private final ApprovedSampleRepository approvals;
    private final ReleasedOrderLookup releasedOrders;
    private final DesignService designs;

    public OrderProductionService(ProductionOrderRepository orders, ApprovedSampleRepository approvals,
                                  ReleasedOrderLookup releasedOrders, DesignService designs) {
        this.orders = orders;
        this.approvals = approvals;
        this.releasedOrders = releasedOrders;
        this.designs = designs;
    }

    @Transactional
    public List<UUID> receive(OrderLinesReleasedForProduction event) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(event.orderId(), "orderId");
        Objects.requireNonNull(event.warehouseId(), "warehouseId");
        if (event.orderNumber() == null || event.orderNumber().isBlank()) {
            throw new IllegalArgumentException("Order number is required");
        }
        List<OrderLinesReleasedForProduction.Line> lines = List.copyOf(event.lines());
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A production release must contain lines");
        }
        var seen = new HashSet<UUID>();
        // Validate every line before any persistence call, including a malformed final line.
        for (var line : lines) {
            Objects.requireNonNull(line.orderLineId(), "orderLineId");
            if (!seen.add(line.orderLineId())) {
                throw new ConflictException("A release contains the same order line twice");
            }
            specification(line);
        }
        UUID customerId = Objects.requireNonNull(
                releasedOrders.requireCustomer(event.orderId(), event.warehouseId()), "customerId");
        List<ProductionOrder.Specification> planned = new ArrayList<>();
        for (var line : lines) {
            var source = new ProductionOrder.Source(ProductionOrder.Type.ORDER, line.orderLineId());
            var print = specification(line);
            var existing = orders.findBySource(source);
            UUID approvalId;
            if (existing.isPresent()) {
                // Replay does not depend on an artifact still being downloadable or a new sample
                // having since been approved for the same design. Compare the frozen original.
                approvalId = line.approvedSampleId() == null
                        ? existing.get().specification().approvedSampleId() : line.approvedSampleId();
            } else {
                ApprovedSample approved = (line.approvedSampleId() == null
                        ? approvals.findForDesign(customerId, line.designSnapshotId(), print.blankSku())
                        : approvals.findById(line.approvedSampleId()))
                        .orElseThrow(() -> new ConflictException("This design and blank need an approved sample"));
                approved.requireMatches(customerId, print);
                approvalId = approved.sampleRequestId();
                var design = designs.verifySnapshotForSku(line.designSnapshotId(), customerId, print.blankSku().code());
                if (!design.snapshotId().equals(print.designSnapshotId())
                        || !design.checksum().equals(print.designChecksum())) {
                    throw new ConflictException("Released design checksum does not match the immutable snapshot");
                }
            }
            var requested = new ProductionOrder.Specification(source, customerId, event.warehouseId(),
                    event.orderId(), event.orderNumber(), print, approvalId);
            existing.ifPresent(order -> order.requireSameSpecification(requested));
            planned.add(requested);
        }
        // Stable lock order across overlapping releases prevents avoidable database deadlocks.
        planned.sort(Comparator.comparing(item -> item.source().id()));
        List<UUID> ids = new ArrayList<>();
        for (var requested : planned) {
            var saved = orders.insertOrGet(ProductionOrder.pending(requested));
            saved.requireSameSpecification(requested);
            ids.add(saved.id());
        }
        return List.copyOf(ids);
    }

    private SampleSpecification specification(OrderLinesReleasedForProduction.Line line) {
        return new SampleSpecification(line.designSnapshotId(), line.designChecksum(), new Sku(line.blankSku()),
                line.quantity(), line.dueDate());
    }
}
