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
import com.stockflow.contracts.ProductionOrderHeld;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
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
    private final ProductionCompletion completion;
    private final Subcontracting subcontracts;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PrintRunService(ProductionOrderRepository orders, ProductionRecords records, ProductionOrders reads,
                    WorkshopStock stock, SampleProductionProgress samples, OrderService salesOrders,
                    ProductionCompletion completion, Subcontracting subcontracts, ApplicationEventPublisher events,
                    Clock clock) {
        this.orders = orders;
        this.records = records;
        this.reads = reads;
        this.stock = stock;
        this.samples = samples;
        this.salesOrders = salesOrders;
        this.completion = completion;
        this.subcontracts = subcontracts;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#productionOrderId")
    public ProductionOrders.Detail startPrinting(CurrentUser user, UUID productionOrderId) {
        ProductionOrder order = lock(user, productionOrderId);
        Instant now = now();
        ProductionOrder.Transition started = order.startPrinting();
        if (order.type() == ProductionOrder.Type.ORDER) {
            // From the first print run a cancellation may keep an agreed fee (03 §9); a cancelled order prints nothing.
            salesOrders.recordPrintingStarted(order.salesOrderId());
        }
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
        records.recordPrintRun(order.id(), printed, total(spoilt), now, user.userId());
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
        if (order.mode() == ProductionOrder.Mode.SUBCONTRACTED
                && order.subcontract().method() == ProductionOrder.SubcontractMethod.SUPPLIED_BLANKS
                && records.subcontractReceiptsOf(order.id()).stream().anyMatch(receipt -> !receipt.reconciled())) {
            // BR-PRD-16, decision Q9: the warehouse reconciles the blanks of every receipt before QC decides on it.
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "Production order %s has a goods receipt whose blanks are not reconciled yet (BR-PRD-16)"
                            .formatted(order.orderNumber()));
        }
        if (order.type() == ProductionOrder.Type.ORDER && salesOrderCancelled(order)) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "The order of production order %s is cancelled, or its line closed; stop the job instead".formatted(order.orderNumber()));
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
            completion.complete(order, packingLocation, user.userId());
        } else {
            reprint(order, user.userId(), now);
        }
        log.info("Production order {} QC round {}: passed {}, failed {} -> {}", order.orderNumber(), round, passed,
                failed, order.status());
        return reads.get(user, order.id());
    }

    /**
     * Short after a round (Jira 398): blanks still issued and unprinted count first; the rest are held now, or
     * the order goes ON_HOLD (BLANKS_SHORT) and purchasing hears of it — nothing is held then.
     */
    private void reprint(ProductionOrder order, UUID by, Instant now) {
        if (order.mode() == ProductionOrder.Mode.SUBCONTRACTED) {
            return;   // a subcontractor's shortfall is sent again or split in house, not reprinted here
        }
        int needed = order.outstandingBlanks();
        if (needed == 0) {
            // Blanks issued and not printed cover the shortfall: back to the press without a scan (audit P1-3).
            order.continueWithIssuedBlanks().ifPresent(moved -> {
                orders.save(order);
                orders.recordTransition(order.id(), moved, now, by);
            });
            return;
        }
        if (stock.holdForReprint(order, needed)) {
            return;
        }
        held(order, order.hold(ProductionOrder.HoldReason.BLANKS_SHORT,
                "%d blank(s) of %s needed for a reprint".formatted(needed, order.work().blankSku()), now), by, now,
                needed);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#productionOrderId")
    public ProductionOrders.Detail cancel(CurrentUser user, UUID productionOrderId, String reason) {
        return cancel(user, productionOrderId, reason, null, null, null);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#productionOrderId")
    public ProductionOrders.Detail cancel(CurrentUser user, UUID productionOrderId, String reason,
                                          java.math.BigDecimal subcontractorFee, String subcontractorFeeReason) {
        return cancel(user, productionOrderId, reason, subcontractorFee, subcontractorFeeReason, null);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#productionOrderId")
    public ProductionOrders.Detail cancel(CurrentUser user, UUID productionOrderId, String reason,
                                          java.math.BigDecimal subcontractorFee, String subcontractorFeeReason,
                                          String packingLocation) {
        ProductionOrder order = lock(user, productionOrderId);
        if (order.mode() == ProductionOrder.Mode.SUBCONTRACTED) {
            // Its PO, its blanks at the subcontractor and its settlement go with it (03 §16).
            return subcontracts.cancel(user, order.id(), reason, subcontractorFee, subcontractorFeeReason,
                    packingLocation);
        }
        if (subcontractorFee != null && subcontractorFee.signum() != 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Production order %s is printed in house: no subcontractor fee".formatted(order.orderNumber()));
        }
        cancelLocked(order, reason, user.userId());
        return reads.get(user, order.id());
    }

    private void cancelLocked(ProductionOrder order, String reason, UUID by) {
        Instant now = now();
        ProductionOrder.Transition cancelled = order.cancel(reason, now);
        int unsold = order.goodUnitsScrappedByCancel();
        if (unsold > 0) {
            // Audit P1-4: printed and passed before the cancel - sold to nobody (BR-PRD-10), written off through the
            // four-eyes adjustment in the canceller's name, never left on a hold in the print shop.
            stock.writeOff(order, List.of(new WorkshopStock.Spoilt(ProductionRecords.ScrapReason.OTHER, unsold,
                    "Printed for a cancelled job: " + order.cancellationReason())), by, now);
        }
        if (order.unprintedBlanks() > 0) {
            stock.returnUnprinted(order, order.unprintedBlanks(), keepForOrder(order), by);
        }
        stock.releaseUnissued(order);
        orders.save(order);
        orders.recordTransition(order.id(), cancelled, now, by);
        sampleRoundCancelled(order);
        // The reason is typed text and stays on the order and its history (develop #99 log hygiene).
        log.info("Production order {} cancelled", order.orderNumber());
    }

    /**
     * {@inheritDoc}
     *
     * <p>One transaction, production order first (its row lock), then the sales order's: the order line gives up the
     * units that will not be made - refused beyond the customer's agreement, and everything here rolls back - before
     * production announces the completion, so the order is ready to fulfil for what was made as soon as the
     * announcement lands. Then the hand-over every completion does - unprinted blanks back, the goods to packing and
     * out of stock - or, for a job closed with nothing made, its blanks back as for any cancellation; and the line's
     * blanks never issued are let go.</p>
     */
    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#productionOrderId")
    public ProductionOrders.Detail completeShort(CurrentUser user, UUID productionOrderId, String reason,
                                                 String packingLocation) {
        ProductionOrder order = lock(user, productionOrderId);
        if (order.type() == ProductionOrder.Type.ORDER && salesOrderCancelled(order)) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "The order of production order %s is cancelled, or its line closed; cancel the job instead"
                            .formatted(order.orderNumber()));
        }
        Instant now = now();
        ProductionOrder.Status from = order.status();
        int shortfall = order.completeShort(reason, now);
        boolean delivered = order.status() == ProductionOrder.Status.COMPLETED;
        if (!delivered) {
            // Nothing made: the blanks go back free - unlike a cancellation of a live order's job, which keeps them for
            // the order: the units they were for are given up.
            if (order.unprintedBlanks() > 0) {
                stock.returnUnprinted(order, order.unprintedBlanks(), false, user.userId());
            }
            stock.releaseUnissued(order);
        }
        orders.save(order);
        orders.recordTransition(order.id(), new ProductionOrder.Transition(from, order.status(), delivered
                ? "Completed short: %d of %d good, %d not made; %s".formatted(order.good(),
                        order.work().plannedQuantity(), shortfall, order.shortCompletionReason())
                : "%d not made; %s".formatted(shortfall, order.cancellationReason())), now, user.userId());
        salesOrders.closeProductionShortfall(order.salesOrderId(), order.salesOrderLineId(), shortfall,
                user.userId());
        if (delivered) {
            completion.complete(order, packingLocation, user.userId());
        }
        stock.letGoUnissuedLineHolds(order, salesOrders.findById(order.salesOrderId()).orElseThrow().lines().stream()
                .filter(line -> line.lineId().equals(order.salesOrderLineId()))
                .flatMap(line -> line.reservationIds().stream()).toList());
        log.info("Production order {} closed short ({}): {} good of {} planned", order.orderNumber(), order.status(),
                order.good(), order.work().plannedQuantity());
        return reads.get(user, order.id());
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
        if (printed > 0) {
            records.recordPrintRun(order.id(), printed, total(spoilt), now, user.userId());
        }
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
        sampleRoundCancelled(order);
        log.info("Production order {} stopped: printed {}, of which {} good written off", order.orderNumber(),
                order.printed(), unsold);
        return reads.get(user, order.id());
    }

    /**
     * SCRUM-401 from the order's side: its cancellation stops what has not been printed. Before printing the
     * job is cancelled and the blanks go back. A job on the press is put ON_HOLD instead — only the print shop
     * knows how many it printed, and a stop with a guessed count could return printed cups as blanks; the
     * manager stops it with the real count. A job in QC waits for the same stop (QC refuses a cancelled order).
     * A subcontracted job is put ON_HOLD too: the manager recalls its blanks and cancels it with its PO.
     *
     * <p>A job cancelled here may have write-offs to ask for - good units of an earlier QC round, expired blanks -
     * which need a person to ask in the name of (four eyes). That is {@code cancelledBy}, who cancelled the order.
     * With none (a cancellation by the system) such a job is put ON_HOLD for the manager instead, who cancels it
     * under their own name; a job with nothing issued yet is still cancelled at once.</p>
     */
    public void onOrderCancelled(UUID salesOrderId, String orderNumber, UUID cancelledBy) {
        stopForOrder(salesOrderId, orderNumber, null, cancelledBy);
    }

    /**
     * PACK-EARLY: staff closed print lines of an order whose stock already shipped ({@code OrderRemainderClosed}). Their
     * production orders stop as for a cancelled order ({@link #onOrderCancelled}); the order's other lines go on.
     */
    public void onLinesClosed(UUID salesOrderId, String orderNumber, java.util.Set<UUID> lineIds, UUID closedBy) {
        if (lineIds.isEmpty()) {
            return;
        }
        stopForOrder(salesOrderId, orderNumber, lineIds, closedBy);
    }

    /** @param lineIds the lines whose jobs stop; null: every job of the order */
    private void stopForOrder(UUID salesOrderId, String orderNumber, java.util.Set<UUID> lineIds, UUID cancelledBy) {
        for (UUID id : orders.idsOfSalesOrder(salesOrderId)) {
            ProductionOrder order = orders.findByIdForUpdate(id).orElseThrow();
            if (lineIds != null && !lineIds.contains(order.salesOrderLineId())) {
                continue;
            }
            Instant now = now();
            ProductionOrder.Status effective = order.effectiveStatus();
            if (order.mode() == ProductionOrder.Mode.SUBCONTRACTED) {
                // Its PO and its blanks at the subcontractor are the manager's to settle, with a real person on the
                // record and a fee the subcontractor may claim: held, and the hold tells them.
                if (order.status() != ProductionOrder.Status.ON_HOLD && order.status() != ProductionOrder.Status.COMPLETED
                        && order.status() != ProductionOrder.Status.CANCELLED) {
                    held(order, order.hold(ProductionOrder.HoldReason.OTHER,
                            "Order %s cancelled: recall the blanks and cancel the subcontract".formatted(orderNumber),
                            now), null, now, null);
                }
                continue;
            }
            if (effective == ProductionOrder.Status.PENDING_PREPRESS || effective == ProductionOrder.Status.READY
                    || effective == ProductionOrder.Status.MATERIAL_ISSUED) {
                if (cancelledBy == null && (order.good() > 0 || order.unprintedBlanks() > 0)) {
                    held(order, order.hold(ProductionOrder.HoldReason.OTHER,
                            "Order %s cancelled by the system: cancel the job to write off what was printed or issued"
                                    .formatted(orderNumber), now), null, now, null);
                } else {
                    cancelLocked(order, "Order %s cancelled".formatted(orderNumber), cancelledBy);
                }
            } else if (order.status() == ProductionOrder.Status.PRINTING) {
                held(order, order.hold(ProductionOrder.HoldReason.OTHER,
                        "Order %s cancelled: stop the job with the printed count".formatted(orderNumber), now), null, now,
                        null);
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

    /** Decision Q5: a SAMPLE production order cancelled or stopped ends its round, which may be started again. */
    private void sampleRoundCancelled(ProductionOrder order) {
        if (order.type() == ProductionOrder.Type.SAMPLE) {
            samples.productionCancelled(order.id(), "Production order %s cancelled: %s".formatted(order.orderNumber(),
                    order.cancellationReason()));
        }
    }

    private void held(ProductionOrder order, ProductionOrder.Transition held, UUID by, Instant now,
                      Integer blanksNeeded) {
        orders.save(order);
        orders.recordTransition(order.id(), held, now, by);
        events.publishEvent(new ProductionOrderHeld(order.id(), order.orderNumber(), order.holdReason().name(),
                order.sampleRequestId(), order.salesOrderId(), order.work().warehouseId(),
                order.work().blankSku().code(), blanksNeeded, now));
        log.info("Production order {} on hold: {}", order.orderNumber(), order.holdReason());
    }

    /** The order still wants its goods: its own holds go back to storage with them, still held for it. */
    private boolean keepForOrder(ProductionOrder order) {
        return order.type() == ProductionOrder.Type.ORDER && !salesOrderCancelled(order);
    }

    /**
     * Read under the sales order's row lock (audit of #109): a cancellation committing between this check and the work
     * done next - QC completing the job, goods handed to packing, blanks kept for the order - waits for this
     * transaction, or came first and is seen. Production order row first, then the sales order, the same order the
     * completion's stock locks follow.
     */
    private boolean salesOrderCancelled(ProductionOrder order) {
        if (order.salesOrderLineId() == null) {
            return salesOrders.lockedStatus(order.salesOrderId())
                    .map(status -> status == OrderStatus.CANCELLED).orElse(true);
        }
        // PACK-EARLY: the line closed out of an order whose stock shipped first is withdrawn just the same.
        return salesOrders.lockedLineWithdrawn(order.salesOrderId(), order.salesOrderLineId());
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
