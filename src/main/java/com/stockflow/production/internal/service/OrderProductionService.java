package com.stockflow.production.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.OrderLinesReleasedForProduction;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import com.stockflow.production.internal.domain.SampleRequest;
import com.stockflow.production.internal.domain.SampleRequestRepository;
import com.stockflow.production.internal.domain.SampleStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ORDER production orders from a release (SCRUM-395/405): one per released print line, all of an order's
 * lines together or none.
 *
 * <p><b>Production trusts the order module for money, not for what to print.</b> The deposit and the
 * credit position (BR-PRD-09) are the order module's decision and are not repeated here. What is checked
 * is what production is answerable for: that each line really is a line of that order, released from that
 * warehouse, with that design and blank and quantity — read back through {@code order :: api}, not taken
 * from the event alone — and that a new design has a sample the customer approved (BR-PRD-08), from this
 * module's own sample rounds. A repeat design is accepted without a sample only when an ORDER production
 * order of it completed for another order, the same rule the order module applies at release.</p>
 *
 * <p><b>Idempotent on the order line (BR-PRD-01).</b> A redelivered event finds each line's order and
 * creates nothing; an order already printed is left as it is. A redelivery that disagrees with what was
 * created is refused with {@code PRODUCTION_SOURCE_CONFLICT}.</p>
 *
 * <p>Anything refused throws, which rolls back every line of the release; the publication stays incomplete
 * in the event registry and is logged, so the inconsistency is visible instead of half-printed.</p>
 */
@Service
@Transactional
public class OrderProductionService {

    private static final Logger log = LoggerFactory.getLogger(OrderProductionService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ProductionOrderRepository productionOrders;
    private final SampleRequestRepository samples;
    private final OrderService orders;
    private final Clock clock;

    OrderProductionService(ProductionOrderRepository productionOrders, SampleRequestRepository samples,
                           OrderService orders, Clock clock) {
        this.productionOrders = productionOrders;
        this.samples = samples;
        this.orders = orders;
        this.clock = clock;
    }

    /** @return the production order of each released line, in line id order */
    public List<ProductionOrder> createFor(OrderLinesReleasedForProduction release) {
        List<OrderLinesReleasedForProduction.Line> lines = validShape(release);
        OrderSummary order = orders.findById(release.orderId()).orElseThrow(() -> new BusinessException(
                ErrorCode.NOT_FOUND, "Released order " + release.orderNumber() + " does not exist"));
        if (order.status() == OrderStatus.CANCELLED) {
            // Cancelled between release and this listener: nothing is to be printed, and retrying will
            // not change that. SCRUM-460's OrderCancelled is what stops orders already created.
            log.warn("Order {} was cancelled before its production orders were created; none created",
                    order.orderNumber());
            return List.of();
        }
        if (order.releasedAt() == null || !release.warehouseId().equals(order.warehouseId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Order %s was not released from warehouse %s"
                    .formatted(order.orderNumber(), release.warehouseId()));
        }
        Map<UUID, OrderSummary.LineSummary> orderLines = order.lines().stream()
                .collect(Collectors.toMap(OrderSummary.LineSummary::lineId, Function.identity()));

        // Decide every line before writing any, so a refused last line leaves no first line behind.
        record Plan(UUID lineId, UUID approvedSampleId, ProductionOrder.Work work) {
        }
        List<Plan> planned = new ArrayList<>();
        for (OrderLinesReleasedForProduction.Line line : lines) {
            requireSameLine(order, orderLines.get(line.orderLineId()), line);
            ProductionOrder.Work work = new ProductionOrder.Work(release.warehouseId(), new Sku(line.blankSku()),
                    line.designSnapshotId(), line.designChecksum(), line.quantity(), line.dueDate());
            Optional<ProductionOrder> existing = productionOrders.findBySource(ProductionOrder.Type.ORDER,
                    line.orderLineId());
            // A replay compares with what was decided then; a sample approved since does not change it.
            UUID approval = existing.isEmpty() ? approvedSample(order, line)
                    : line.approvedSampleId() != null ? line.approvedSampleId() : existing.get().approvedSampleId();
            planned.add(new Plan(line.orderLineId(), approval, work));
        }

        // Stable order: two overlapping releases take the per-line locks in the same sequence.
        planned.sort(Comparator.comparing(Plan::lineId));
        Instant now = clock.instant();
        List<ProductionOrder> created = new ArrayList<>();
        for (Plan plan : planned) {
            Optional<ProductionOrder> existing = productionOrders.findBySource(ProductionOrder.Type.ORDER, plan.lineId());
            ProductionOrder requested = ProductionOrder.forOrderLine(
                    existing.map(ProductionOrder::id).orElseGet(Identifiers::newId),
                    existing.map(ProductionOrder::orderNumber).orElseGet(() -> productionOrders.nextNumber(today())),
                    order.orderId(), plan.lineId(), plan.approvedSampleId(), plan.work());
            ProductionOrder stored = existing.orElseGet(() -> productionOrders.createIfAbsent(requested, null, now));
            stored.requireSameWorkAs(requested, productionOrders.splitOffOf(stored.id()));
            created.add(stored);
        }
        log.info("Order {} released from {}: {} production order(s)", order.orderNumber(), release.warehouseId(),
                created.size());
        return List.copyOf(created);
    }

    private static List<OrderLinesReleasedForProduction.Line> validShape(OrderLinesReleasedForProduction release) {
        Objects.requireNonNull(release, "release");
        if (release.orderId() == null || release.warehouseId() == null || release.lines() == null
                || release.lines().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A release names its order and warehouse and carries at least one line");
        }
        var seen = new HashSet<UUID>();
        for (OrderLinesReleasedForProduction.Line line : release.lines()) {
            if (line == null || line.orderLineId() == null || !seen.add(line.orderLineId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Every released line names a distinct order line");
            }
        }
        return List.copyOf(release.lines());
    }

    /** The line must be on the order exactly as released: same blank, design, checksum and quantity. */
    private static void requireSameLine(OrderSummary order, OrderSummary.LineSummary actual,
                                        OrderLinesReleasedForProduction.Line released) {
        if (actual == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "Line %s is not a line of order %s"
                    .formatted(released.orderLineId(), order.orderNumber()));
        }
        if (!Objects.equals(actual.sku(), released.blankSku())
                || !Objects.equals(actual.designSnapshotId(), released.designSnapshotId())
                || !Objects.equals(actual.designChecksum(), released.designChecksum())
                || actual.quantity() != released.quantity()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Line %s of order %s was released with other content"
                    .formatted(released.orderLineId(), order.orderNumber()));
        }
    }

    /**
     * BR-PRD-08. The sample the release names must be an APPROVED round of this customer for this design
     * on this blank. A release naming none is a repeat design: the latest approved sample of the pair is
     * still recorded as the QC reference when there is one, otherwise the design must have been printed
     * to completion for another order.
     */
    private UUID approvedSample(OrderSummary order, OrderLinesReleasedForProduction.Line line) {
        Sku blank = new Sku(line.blankSku());
        if (line.approvedSampleId() != null) {
            SampleRequest sample = samples.findById(line.approvedSampleId())
                    .filter(found -> matches(found, order.customerId(), line.designSnapshotId(), blank))
                    .orElseThrow(() -> notApproved(order, line));
            return sample.id();
        }
        Optional<SampleRequest> latest = samples.latestApproved(line.designSnapshotId(), blank)
                .filter(found -> matches(found, order.customerId(), line.designSnapshotId(), blank));
        if (latest.isPresent()) {
            return latest.get().id();
        }
        if (productionOrders.completedForAnotherOrder(line.designSnapshotId(), order.orderId())) {
            return null;
        }
        throw notApproved(order, line);
    }

    private static boolean matches(SampleRequest sample, UUID customerId, UUID designSnapshotId, Sku blank) {
        return sample.status() == SampleStatus.APPROVED
                && sample.customerId().equals(customerId)
                && sample.specification().designSnapshotId().equals(designSnapshotId)
                && sample.specification().blankSku().equals(blank);
    }

    private static BusinessException notApproved(OrderSummary order, OrderLinesReleasedForProduction.Line line) {
        return new BusinessException(ErrorCode.DESIGN_SAMPLE_NOT_APPROVED,
                "Line %s of order %s: design %s on %s has no approved sample and was never printed (BR-PRD-08)"
                        .formatted(line.orderLineId(), order.orderNumber(), line.designSnapshotId(), line.blankSku()));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE);
    }
}
