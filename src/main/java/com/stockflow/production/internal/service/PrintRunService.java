package com.stockflow.production.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.contracts.ProductionCompleted;
import com.stockflow.contracts.ProductionOrderHeld;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import com.stockflow.production.internal.domain.ProductionRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link PrintRuns} on the production order's row lock, taken before loading (a second caller sees the first
 * one's result). Stock moves through {@link WorkshopStock} in the same transaction, so a refused step leaves
 * neither the order nor the stock changed.
 *
 * <p><b>QC is the only way out of QC</b> (plan, transition owners): completion — the hand-over to packing, the
 * {@code ProductionCompleted} announcement, a sample's round — runs inside the QC transaction, once, because
 * the order can reach COMPLETED only there.</p>
 */
@Service
@Transactional
class PrintRunService implements PrintRuns {

    private static final Logger log = LoggerFactory.getLogger(PrintRunService.class);

    private final ProductionOrderRepository orders;
    private final ProductionRecords records;
    private final ProductionOrders reads;
    private final WorkshopStock stock;
    private final SampleProductionProgress samples;
    private final OrderService salesOrders;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PrintRunService(ProductionOrderRepository orders, ProductionRecords records, ProductionOrders reads,
                    WorkshopStock stock, SampleProductionProgress samples, OrderService salesOrders,
                    ApplicationEventPublisher events, Clock clock) {
        this.orders = orders;
        this.records = records;
        this.reads = reads;
        this.stock = stock;
        this.samples = samples;
        this.salesOrders = salesOrders;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#productionOrderId")
    public ProductionOrders.Detail startPrinting(CurrentUser user, UUID productionOrderId) {
        ProductionOrder order = lock(user, productionOrderId);
        Instant now = now();
        ProductionOrder.Transition started = order.startPrinting();
        orders.save(order);
        orders.recordTransition(order.id(), started, now, user.userId());
        if (order.type() == ProductionOrder.Type.SAMPLE) {
            samples.productionStarted(order.id());
        }
        log.info("Production order {} printing", order.orderNumber());
        return reads.get(user, order.id());
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#productionOrderId")
    public ProductionOrders.Detail recordOutput(CurrentUser user, UUID productionOrderId, int printed,
                                                List<ScrapLine> scrap) {
        List<WorkshopStock.Spoilt> spoilt = spoilt(scrap);
        ProductionOrder order = lock(user, productionOrderId);
        Instant now = now();
        List<ProductionOrder.Transition> moved = order.recordOutput(printed, total(spoilt));
        stock.writeOff(order, spoilt, user.userId(), now);
        orders.save(order);
        moved.forEach(transition -> orders.recordTransition(order.id(), transition, now, user.userId()));
        if (order.status() == ProductionOrder.Status.READY) {
            reprint(order, user.userId(), now);
        }
        log.info("Production order {}: printed {}, scrap {}", order.orderNumber(), printed, total(spoilt));
        return reads.get(user, order.id());
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#productionOrderId")
    public ProductionOrders.Detail recordQc(CurrentUser user, UUID productionOrderId, int passed, int failed,
                                            String note, String packingLocation) {
        String text = optionalNote(note);
        ProductionOrder order = lock(user, productionOrderId);
        if (order.type() == ProductionOrder.Type.ORDER && salesOrderCancelled(order)) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "The order of production order %s is cancelled; stop the job instead".formatted(order.orderNumber()));
        }
        Instant now = now();
        int round = records.qcRoundsOf(order.id()).size() + 1;
        ProductionOrder.Transition decided = order.recordQc(passed, failed, now);
        records.recordQc(new ProductionRecords.QcRound(Identifiers.newId(), order.id(), round, passed, failed, text, now,
                user.userId()));
        if (failed > 0) {
            stock.writeOff(order, List.of(new WorkshopStock.Spoilt(ProductionRecords.ScrapReason.QC_FAILED, failed,
                    text)), user.userId(), now);
        }
        orders.save(order);
        orders.recordTransition(order.id(), decided, now, user.userId());
        if (order.status() == ProductionOrder.Status.COMPLETED) {
            complete(order, packingLocation, user.userId());
        } else {
            reprint(order, user.userId(), now);
        }
        log.info("Production order {} QC round {}: passed {}, failed {} -> {}", order.orderNumber(), round, passed,
                failed, order.status());
        return reads.get(user, order.id());
    }

    /**
     * The hand-over (SCRUM-400). Blanks issued and never printed go back to storage, free: the order has its
     * good units. Then an ORDER's goods, still held for it, go to packing and the order is told once; a SAMPLE's
     * are written off as SAMPLE and its round becomes READY.
     */
    private void complete(ProductionOrder order, String packingLocation, UUID by) {
        if (order.unprintedBlanks() > 0) {
            stock.returnUnprinted(order, order.unprintedBlanks(), false, by);
        }
        // Before the hand-over: a production hold that reached packing is finished goods, not a spare blank.
        stock.releaseUnissued(order);
        if (order.type() == ProductionOrder.Type.SAMPLE) {
            stock.writeOffSample(order, order.good(), by);
            samples.productionCompleted(order.id());
        } else {
            String packing = stock.finishToPacking(order, packingLocation, by);
            events.publishEvent(new ProductionCompleted(order.salesOrderId(), order.salesOrderLineId(), order.id(),
                    order.good()));
            log.info("Production order {} completed: {} good unit(s) to {}", order.orderNumber(), order.good(), packing);
        }
    }

    /**
     * Short after a round (Jira 398): blanks still issued and unprinted count first; the rest are held now, or
     * the order goes ON_HOLD (BLANKS_SHORT) and purchasing hears of it — nothing is held then.
     */
    private void reprint(ProductionOrder order, UUID by, Instant now) {
        int needed = order.outstandingBlanks();
        if (needed == 0 || stock.holdForReprint(order, needed)) {
            return;
        }
        held(order, order.hold(ProductionOrder.HoldReason.BLANKS_SHORT,
                "%d blank(s) of %s needed for a reprint".formatted(needed, order.work().blankSku()), now), by, now);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#productionOrderId")
    public ProductionOrders.Detail cancel(CurrentUser user, UUID productionOrderId, String reason) {
        ProductionOrder order = lock(user, productionOrderId);
        cancelLocked(order, reason, user.userId());
        return reads.get(user, order.id());
    }

    private void cancelLocked(ProductionOrder order, String reason, UUID by) {
        Instant now = now();
        ProductionOrder.Transition cancelled = order.cancel(reason, now);
        if (order.unprintedBlanks() > 0) {
            stock.returnUnprinted(order, order.unprintedBlanks(), keepForOrder(order), by);
        }
        stock.releaseUnissued(order);
        orders.save(order);
        orders.recordTransition(order.id(), cancelled, now, by);
        log.info("Production order {} cancelled: {}", order.orderNumber(), order.cancellationReason());
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#productionOrderId")
    public ProductionOrders.Detail stop(CurrentUser user, UUID productionOrderId, int printed, List<ScrapLine> scrap,
                                        String reason) {
        List<WorkshopStock.Spoilt> spoilt = spoilt(scrap);
        ProductionOrder order = lock(user, productionOrderId);
        Instant now = now();
        ProductionOrder.Status from = order.status();
        int unsold = order.stop(printed, total(spoilt), reason, now);
        List<WorkshopStock.Spoilt> writeOff = new ArrayList<>(spoilt);
        if (unsold > 0) {
            writeOff.add(new WorkshopStock.Spoilt(ProductionRecords.ScrapReason.OTHER, unsold,
                    "Printed for a stopped job: " + order.cancellationReason()));
        }
        stock.writeOff(order, writeOff, user.userId(), now);
        if (order.unprintedBlanks() > 0) {
            stock.returnUnprinted(order, order.unprintedBlanks(), keepForOrder(order), user.userId());
        }
        stock.releaseUnissued(order);
        orders.save(order);
        orders.recordTransition(order.id(), new ProductionOrder.Transition(from, ProductionOrder.Status.CANCELLED,
                "Stopped after printing %d: %s".formatted(order.printed(), order.cancellationReason())), now,
                user.userId());
        log.info("Production order {} stopped: printed {}, of which {} good written off", order.orderNumber(),
                order.printed(), unsold);
        return reads.get(user, order.id());
    }

    /**
     * SCRUM-401 from the order's side: its cancellation stops what has not been printed. Before printing the
     * job is cancelled and the blanks go back. A job on the press is put ON_HOLD instead — only the print shop
     * knows how many it printed, and a stop with a guessed count could return printed cups as blanks; the
     * manager stops it with the real count. A job in QC waits for the same stop (QC refuses a cancelled order).
     */
    public void onOrderCancelled(UUID salesOrderId, String orderNumber) {
        for (UUID id : orders.idsOfSalesOrder(salesOrderId)) {
            ProductionOrder order = orders.findByIdForUpdate(id).orElseThrow();
            Instant now = now();
            ProductionOrder.Status effective = order.effectiveStatus();
            if (effective == ProductionOrder.Status.PENDING_PREPRESS || effective == ProductionOrder.Status.READY
                    || effective == ProductionOrder.Status.MATERIAL_ISSUED) {
                cancelLocked(order, "Order %s cancelled".formatted(orderNumber), null);
            } else if (order.status() == ProductionOrder.Status.PRINTING) {
                held(order, order.hold(ProductionOrder.HoldReason.OTHER,
                        "Order %s cancelled: stop the job with the printed count".formatted(orderNumber), now), null, now);
            } else {
                log.info("Production order {} is {}: order {} cancelled, the job waits for its stop",
                        order.orderNumber(), order.status(), orderNumber);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductionRecords.Scrap> scrapOf(CurrentUser user, UUID productionOrderId) {
        reads.get(user, productionOrderId);
        return records.scrapOf(productionOrderId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductionRecords.QcRound> qcRoundsOf(CurrentUser user, UUID productionOrderId) {
        reads.get(user, productionOrderId);
        return records.qcRoundsOf(productionOrderId);
    }

    private void held(ProductionOrder order, ProductionOrder.Transition held, UUID by, Instant now) {
        orders.save(order);
        orders.recordTransition(order.id(), held, now, by);
        events.publishEvent(new ProductionOrderHeld(order.id(), order.orderNumber(), order.holdReason().name(),
                order.holdNote(), order.sampleRequestId(), order.salesOrderId()));
        log.info("Production order {} on hold: {}", order.orderNumber(), order.holdReason());
    }

    /** The order still wants its goods: its own holds go back to storage with them, still held for it. */
    private boolean keepForOrder(ProductionOrder order) {
        return order.type() == ProductionOrder.Type.ORDER && !salesOrderCancelled(order);
    }

    private boolean salesOrderCancelled(ProductionOrder order) {
        return salesOrders.findById(order.salesOrderId()).map(OrderSummary::status)
                .map(status -> status == OrderStatus.CANCELLED).orElse(true);
    }

    private ProductionOrder lock(CurrentUser user, UUID productionOrderId) {
        ScopeGuards.requireBackOffice(user);
        ProductionOrder order = orders.findByIdForUpdate(Objects.requireNonNull(productionOrderId, "productionOrderId"))
                .orElseThrow(() -> new NotFoundException("production-order", productionOrderId));
        WarehouseScope.requireWarehouse(order.work().warehouseId());
        return order;
    }

    /** Microseconds, as the columns keep them. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static List<WorkshopStock.Spoilt> spoilt(List<ScrapLine> lines) {
        List<WorkshopStock.Spoilt> spoilt = new ArrayList<>();
        for (ScrapLine line : lines == null ? List.<ScrapLine>of() : lines) {
            if (line == null || line.reason() == null || line.quantity() < 1) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Each scrap line has a reason and at least one unit");
            }
            String note = optionalNote(line.note());
            if (line.reason() == ProductionRecords.ScrapReason.OTHER && note == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Scrap for another reason must say what it is");
            }
            spoilt.add(new WorkshopStock.Spoilt(line.reason(), line.quantity(), note));
        }
        return spoilt;
    }

    private static String optionalNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String text = note.strip();
        if (text.length() > 1000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A note is at most 1000 characters");
        }
        return text;
    }

    private static int total(List<WorkshopStock.Spoilt> spoilt) {
        return spoilt.stream().mapToInt(WorkshopStock.Spoilt::quantity).sum();
    }
}
