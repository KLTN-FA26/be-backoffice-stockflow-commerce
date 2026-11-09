package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.common.security.AssignedWarehouses;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.design.api.DesignService;
import com.stockflow.design.api.DesignVerification;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.DeliveryBatch;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.Producer;
import com.stockflow.order.internal.domain.ProducerRepository;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderReceiptRepository;
import com.stockflow.order.internal.domain.ProductionOrderRepository;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.domain.SalesContract;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.order.internal.domain.WarehouseDirectory;
import com.stockflow.order.internal.repository.ProductionOrderSearch;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import com.stockflow.common.id.Identifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Production orders (4A-2, kltn-docs 17 §4.3 steps 6-7, 12-13, §5.3; BR-05, BR-06, BR-23, BR-24, BR-26).
 *
 * <ul>
 *   <li><b>Draw up</b> (Production Coordinator): from a confirmed order of a contract with a shipping warehouse - or
 *       one already waiting for stock, for its other batches (4A-6) - for one delivery batch, its lines filled with what
 *       the batch lacks there after the stock held for it (17 §4.2 step 3) and what other open production orders of the
 *       batch still bring. Received at the shipping warehouse; needed by
 *       the batch's window start (BR-26); a sample required when one of its SKUs was never produced (BR-24).</li>
 *   <li><b>Edit</b> while a draft (B33), <b>submit</b>, <b>approve</b> (Sales Manager, not the one who drew it up -
 *       BR-23; the print files are checked byte by byte first) which sends it - the slip drawn and its producer told
 *       ({@link ProductionOrderDispatch}, 4A-3) - or <b>return</b> it with a reason.</li>
 *   <li><b>Cancel</b> before mass printing, <b>close</b> after it started (BR-06), with a reason - never while a goods
 *       receipt against it is a draft (4A-5, kltn-docs 03 edge cases); a producer it was sent to is told to stop.</li>
 *   <li><b>Accept</b> with the producer's expected completion day, move that day, and <b>start production</b> when no
 *       print sample is to be approved (4A-4, 17 §4.3 steps 8 and 10); the print sample rounds are
 *       {@code ProductionSampleService}'s.</li>
 * </ul>
 *
 * <p>Every write locks the sales order first and the production order after it - the order {@code drawUp} locks in,
 * and the one receiving takes ({@code ProductionOrderReceivingService}). One order everywhere: a path taking the
 * production order before its sales order would deadlock against an approval.</p>
 *
 * <p>The capacity warning (BR-25) waits for the warehouse capacity table: not computed yet.</p>
 */
@Service
@Transactional
public class ProductionOrderService {

    private static final Logger log = LoggerFactory.getLogger(ProductionOrderService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final SortWhitelist SORT = SortWhitelist.of("createdAt", "needByDate", "code", "status")
            .withDefault("createdAt", Sort.Direction.DESC);

    /** A production order and its history of status, for display. */
    public record Detail(ProductionOrder order, List<ProductionOrderRepository.HistoryEntry> history) {
    }

    private final ProductionOrderRepository productionOrders;
    private final ProductionOrderReceiptRepository receipts;
    private final ProductionOrderSearch search;
    private final OrderRepository orders;
    private final SalesContractRepository contracts;
    private final ProducerRepository producers;
    private final ShippingWarehouseService shippingWarehouses;
    private final WarehouseDirectory warehouses;
    private final CustomerSkus customerSkus;
    private final DesignService designs;
    private final ProductionOrderDispatch dispatch;
    private final Clock clock;
    private final TransactionTemplate tx;

    ProductionOrderService(ProductionOrderRepository productionOrders, ProductionOrderReceiptRepository receipts,
                           ProductionOrderSearch search, OrderRepository orders, SalesContractRepository contracts,
                           ProducerRepository producers, ShippingWarehouseService shippingWarehouses,
                           WarehouseDirectory warehouses, CustomerSkus customerSkus, DesignService designs,
                           ProductionOrderDispatch dispatch, Clock clock, PlatformTransactionManager transactions) {
        this.productionOrders = productionOrders;
        this.receipts = receipts;
        this.search = search;
        this.orders = orders;
        this.contracts = contracts;
        this.producers = producers;
        this.shippingWarehouses = shippingWarehouses;
        this.warehouses = warehouses;
        this.customerSkus = customerSkus;
        this.designs = designs;
        this.dispatch = dispatch;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    /**
     * Draws up a production order for what delivery batch {@code batchSeq} of the order lacks (17 §4.3 step 6).
     *
     * @throws BusinessException {@code NOT_FOUND}; {@code PRODUCTION_ORDER_NOT_ALLOWED} for an order neither confirmed
     *         nor waiting for stock, not of a contract, or with no shipping warehouse; {@code PRODUCER_NOT_FOUND};
     *         {@code PRODUCER_INACTIVE};
     *         {@code PRODUCTION_ORDER_NOTHING_SHORT} when the batch lacks nothing
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "production-order", resourceId = "#result?.id()")
    public ProductionOrder drawUp(UUID salesOrderId, int batchSeq, UUID producerId, UUID by) {
        Order order = orders.findByIdForUpdate(new OrderId(salesOrderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + salesOrderId));
        requireProducible(order);
        SalesContract contract = contracts.findLiveByOrderId(salesOrderId).orElseThrow(() ->
                new BusinessException(ErrorCode.PRODUCTION_ORDER_NOT_ALLOWED, "Order %s has no signed contract"
                        .formatted(order.orderNumber())));
        DeliveryBatch batch = contract.schedule().stream().filter(b -> b.seq() == batchSeq).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Order %s has no delivery batch %d"
                        .formatted(order.orderNumber(), batchSeq)));
        Producer producer = activeProducer(producerId);
        Map<UUID, Integer> lacking = lacking(order, contract.schedule(), batch, null);
        if (lacking.values().stream().noneMatch(cartons -> cartons > 0)) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_NOTHING_SHORT, ("Delivery batch %d of order %s lacks "
                    + "nothing: its stock is held, or on its way").formatted(batchSeq, order.orderNumber()));
        }
        Map<UUID, OrderLine> lines = new HashMap<>();
        order.lines().forEach(line -> lines.put(line.id(), line));
        List<ProductionOrder.Line> made = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : lacking.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            OrderLine line = lines.get(entry.getKey());
            CustomerSku sku = customerSkus.find(line.sku().code()).orElseThrow(() -> new BusinessException(
                    ErrorCode.PRODUCTION_ORDER_NOT_ALLOWED, "%s is not a customer's own SKU: it is not produced to order"
                    .formatted(line.sku().code())));
            String checksum = designs.verifySnapshotForSku(sku.designSnapshotId(), order.customerId(), sku.sku())
                    .checksum();
            made.add(new ProductionOrder.Line(Identifiers.newId(), line.id(), line.sku().code(), entry.getValue(),
                    line.unitsPerCarton(), sku.designSnapshotId(), checksum, null, 0));
        }
        Set<String> producedBefore = productionOrders.producedBefore(made.stream().map(ProductionOrder.Line::sku)
                .toList());
        boolean firstTime = made.stream().anyMatch(line -> !producedBefore.contains(line.sku()));
        Instant now = clock.instant();
        ProductionOrder drawn = ProductionOrder.drawUp(productionOrders.nextCode(LocalDate.ofInstant(now,
                        BUSINESS_ZONE)), salesOrderId, batch.id(), order.customerId(), producer.id(),
                order.shippingWarehouseId(), batch.windowStart(), firstTime, made,
                order.total().currency().getCurrencyCode(), by, now);
        ProductionOrder saved = productionOrders.save(drawn);
        log.info("Production order {} drawn up for batch {} of order {}: {} line(s), producer {}", saved.code(),
                batchSeq, order.orderNumber(), made.size(), producer.code());
        return saved;
    }

    /**
     * The coordinator's changes to a draft read at {@code version} (B33). The sales order is locked first, as drawing up
     * locks it: two drafts of one batch edited or drawn up together would otherwise each see the other's old cartons and
     * together make more than the batch lacks.
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_NOT_FOUND}; {@code OPTIMISTIC_LOCK};
     *         {@code PRODUCTION_ORDER_STATUS_INVALID}; {@code PRODUCER_INACTIVE};
     *         {@code PRODUCTION_ORDER_EXCEEDS_SHORTAGE}; {@code VALIDATION_FAILED}
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "production-order", resourceId = "#id")
    public ProductionOrder edit(UUID id, long version, ProductionOrder.Draft draft) {
        Locked locked = lockedWithOrder(id, version);
        activeProducer(draft.producerId());
        locked.production().edit(draft, lacking(locked));
        return productionOrders.save(locked.production());
    }

    /**
     * DRAFT → PENDING_APPROVAL, while the batch still lacks what it makes: stock that arrived since it was drawn up (a
     * top-up, goods returned, another shipping warehouse) may have covered part of it.
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_EXCEEDS_SHORTAGE}; {@code PRODUCTION_ORDER_STATUS_INVALID};
     *         {@code PRODUCER_INACTIVE}; {@code OPTIMISTIC_LOCK}
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#id")
    public ProductionOrder submit(UUID id, long version, UUID by) {
        Locked locked = lockedWithOrder(id, version);
        ProductionOrder production = locked.production();
        activeProducer(production.producerId());
        production.requireWithin(lacking(locked));
        production.submit(by, clock.instant());
        log.info("Production order {} submitted for approval", production.code());
        return productionOrders.save(production);
    }

    /**
     * Approved by the Sales Manager - never the one who drew it up - so sent (BR-23), while the batch still lacks what it
     * makes (as on submission). The print files of every line are re-read and re-hashed first, before any row is locked
     * (a full download of each bundle, each in its own read-only transaction): a production order is not sent to print
     * a file that changed. Its slip is drawn and stored then too, still with no lock held; the transaction keeps it and
     * publishes {@code ProductionOrderSent} (4A-3), or the stored slip is deleted when it fails.
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_SELF_APPROVAL}; {@code PRINT_FILE_MISMATCH};
     *         {@code PRODUCTION_ORDER_EXCEEDS_SHORTAGE}; {@code PRODUCTION_ORDER_STATUS_INVALID};
     *         {@code OPTIMISTIC_LOCK}
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Auditable(action = AuditAction.APPROVE, resourceType = "production-order", resourceId = "#id")
    public ProductionOrder approve(UUID id, long version, UUID by) {
        ProductionOrder read = productionOrders.findById(id).filter(this::visible).orElseThrow(() -> notFound(id));
        // Refused before the download, where the bytes would not change the answer.
        if (by.equals(read.drawnUpBy())) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_SELF_APPROVAL, ("Production order %s was drawn up by "
                    + "you: someone else approves it (BR-23)").formatted(read.code()));
        }
        if (read.status() != ProductionOrderStatus.PENDING_APPROVAL) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID, ("Production order %s is %s and "
                    + "cannot be approved").formatted(read.code(), read.status()));
        }
        // A stale request is refused before the files are downloaded and a slip drawn for a version nobody approves.
        if (read.version() != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, "Production order %s changed since it was read"
                    .formatted(read.code()));
        }
        Map<UUID, String> snapshots = new LinkedHashMap<>();
        read.lines().forEach(line -> snapshots.put(line.designSnapshotId(), line.printFileChecksum()));
        for (Map.Entry<UUID, String> snapshot : snapshots.entrySet()) {
            DesignVerification verified = designs.verifyForPrinting(snapshot.getKey(), snapshot.getValue());
            if (!verified.matching()) {
                throw new BusinessException(ErrorCode.PRINT_FILE_MISMATCH, ("Production order %s: the print files of "
                        + "design %s no longer match what was approved").formatted(read.code(), snapshot.getKey()));
            }
        }
        // 4A-3: the slip is drawn and stored with no lock held, from the version read - which the transaction checks.
        Instant now = clock.instant();
        try (ProductionOrderDispatch.PreparedSlip slip = dispatch.prepareSlip(read, by, now)) {
            return tx.execute(status -> {
                Locked locked = lockedWithOrder(id, version);
                ProductionOrder production = locked.production();
                production.requireWithin(lacking(locked));
                production.approve(by, now);
                ProductionOrder saved = productionOrders.save(production);
                dispatch.sent(saved, slip);
                log.info("Production order {} approved and sent", saved.code());
                return saved;
            });
        }
    }

    /** Returned to the coordinator with a reason (BR-23). */
    @Auditable(action = AuditAction.REJECT, resourceType = "production-order", resourceId = "#id")
    public ProductionOrder returnToDraft(UUID id, long version, String reason, UUID by) {
        ProductionOrder production = lockedAfterItsOrder(id, version);
        production.returnToDraft(reason, by, clock.instant());
        log.info("Production order {} returned to its coordinator", production.code());
        return productionOrders.save(production);
    }

    /**
     * Cancelled before mass printing, with a reason (BR-06), while no goods receipt against it is a draft (03 edge
     * cases: the draft is confirmed or cancelled first). A producer it was sent to is told to stop (4A-3).
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_HAS_DRAFT_RECEIPTS}; {@code PRODUCTION_ORDER_STATUS_INVALID};
     *         {@code VALIDATION_FAILED}; {@code OPTIMISTIC_LOCK}
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#id")
    public ProductionOrder cancel(UUID id, long version, String reason, UUID by) {
        ProductionOrder production = lockedAfterItsOrder(id, version);
        requireNoDraftReceipt(production, "cancelled");
        production.cancel(reason, by, clock.instant());
        log.info("Production order {} cancelled", production.code());
        ProductionOrder saved = productionOrders.save(production);
        dispatch.stopped(saved);
        return saved;
    }

    /**
     * Closed after mass printing started, short or before anything came, with a reason (BR-06), while no goods receipt
     * against it is a draft (03 edge cases). Its producer is told to stop (4A-3).
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_HAS_DRAFT_RECEIPTS}; {@code PRODUCTION_ORDER_STATUS_INVALID};
     *         {@code VALIDATION_FAILED}; {@code OPTIMISTIC_LOCK}
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#id")
    public ProductionOrder close(UUID id, long version, String reason, UUID by) {
        ProductionOrder production = lockedAfterItsOrder(id, version);
        requireNoDraftReceipt(production, "closed");
        production.close(reason, by, clock.instant());
        log.info("Production order {} closed", production.code());
        ProductionOrder saved = productionOrders.save(production);
        dispatch.stopped(saved);
        return saved;
    }

    /**
     * The producer accepted it and gave its expected completion day (17 §4.3 step 8): SENT → ACCEPTED, recorded by the
     * coordinator by hand - there is no producer system to hear it from.
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_STATUS_INVALID}; {@code VALIDATION_FAILED} with no day;
     *         {@code OPTIMISTIC_LOCK}
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#id")
    public ProductionOrder accept(UUID id, long version, LocalDate expectedCompletion, UUID by) {
        requireNotPast(expectedCompletion);
        ProductionOrder production = lockedAfterItsOrder(id, version);
        production.accept(expectedCompletion, by, clock.instant());
        log.info("Production order {} accepted by its producer, expected done on {}", production.code(),
                expectedCompletion);
        return productionOrders.save(production);
    }

    /**
     * The producer moved its expected completion day (17 §4.3 steps 8, 10), from acceptance until the goods are in.
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_STATUS_INVALID}; {@code VALIDATION_FAILED};
     *         {@code OPTIMISTIC_LOCK}
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "production-order", resourceId = "#id")
    public ProductionOrder expectCompletion(UUID id, long version, LocalDate expectedCompletion) {
        requireNotPast(expectedCompletion);
        ProductionOrder production = lockedAfterItsOrder(id, version);
        if (!production.expectCompletion(expectedCompletion)) {
            return production;
        }
        log.info("Production order {}: its producer now expects it done on {}", production.code(), expectedCompletion);
        return productionOrders.save(production);
    }

    /**
     * The producer started mass printing, with no print sample to approve (17 §4.3 step 10): ACCEPTED →
     * IN_PRODUCTION, its expected completion day updated if the producer gave a new one. The sales order records that
     * printing started (BR-11) in the same transaction ({@code ProductionOrderProgressListener}).
     *
     * @throws BusinessException {@code PRODUCTION_ORDER_SAMPLE_REQUIRED}; {@code PRODUCTION_ORDER_STATUS_INVALID};
     *         {@code OPTIMISTIC_LOCK}
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#id")
    public ProductionOrder startProduction(UUID id, long version, LocalDate expectedCompletion, UUID by) {
        requireNotPast(expectedCompletion);
        ProductionOrder production = lockedAfterItsOrder(id, version);
        production.startProduction(expectedCompletion, by, clock.instant());
        log.info("Production order {} in mass production", production.code());
        return productionOrders.save(production);
    }

    /**
     * A producer says when the goods will be done, never when they would have been: a day already gone is a slip of
     * the keyboard, and it would raise or hide the AT_RISK alert that reads it (17 §4.12). Checked here, against the
     * business day, not in the aggregate or the database, which have no clock.
     */
    private void requireNotPast(LocalDate expectedCompletion) {
        if (expectedCompletion != null && expectedCompletion.isBefore(LocalDate.ofInstant(clock.instant(),
                BUSINESS_ZONE))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("The expected completion day %s is in the past")
                    .formatted(expectedCompletion));
        }
    }

    /**
     * 03 edge cases: a receipt counted against it while it is cancelled or closed would be posted against a
     * production order that no longer takes goods. Decided under the production order's lock, which a receipt is
     * started and confirmed under too, so no draft appears between this answer and the change.
     */
    private void requireNoDraftReceipt(ProductionOrder production, String what) {
        List<String> drafts = receipts.drafts(production.id());
        if (!drafts.isEmpty()) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_HAS_DRAFT_RECEIPTS, ("Production order %s has draft "
                    + "receipt(s) %s: confirm or cancel them before it is %s").formatted(production.code(),
                    String.join(", ", drafts), what));
        }
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    /** One production order and its history; a warehouse-bound caller sees those received at their warehouses only. */
    @Transactional(readOnly = true)
    public Detail detail(UUID id) {
        ProductionOrder production = productionOrders.findById(id).filter(this::visible).orElseThrow(() -> notFound(id));
        return new Detail(production, productionOrders.history(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductionOrder> list(UUID receivingWarehouseId, ProductionOrderStatus status, UUID producerId,
                                              UUID salesOrderId, int page, int size, String sort) {
        return Pages.toResponse(search.search(receivingWarehouseId, status, producerId, salesOrderId,
                WarehouseScope.restriction().map(AssignedWarehouses::ids).orElse(null),
                Pages.of(page, size, SORT.parse(sort))));
    }

    /** What each line of the batch still lacks for a production order, in cartons (the draft's own cartons count). */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> lacking(UUID productionOrderId) {
        ProductionOrder production = productionOrders.findById(productionOrderId).orElseThrow(() ->
                notFound(productionOrderId));
        Order order = orders.findById(new OrderId(production.salesOrderId())).orElseThrow();
        List<DeliveryBatch> schedule = scheduleOf(production);
        return lacking(order, schedule, batchOf(production, schedule), production.id());
    }

    // ---- the batch's shortage -----------------------------------------------------------------------------------------

    /**
     * Per order line of {@code batch}, the cartons it lacks (BR-05). What is held at the shipping warehouse counts for
     * the earliest batch first (17 §4.2 step 3, the coverage). So do the cartons open production orders still bring:
     * walking the batches in order, a production order's cartons cover its own batch, and what is left of them once that
     * batch is covered goes on to the next - goods arriving fill the earliest batch still short, so a surplus is never
     * produced twice. {@code excluding} leaves one production order out (a draft being edited or checked counts against
     * what remains without it).
     */
    private Map<UUID, Integer> lacking(Order order, List<DeliveryBatch> schedule, DeliveryBatch batch, UUID excluding) {
        Map<UUID, Integer> seqOf = new HashMap<>();
        schedule.forEach(b -> seqOf.put(b.id(), b.seq()));
        Map<Integer, Map<UUID, Integer>> coming = new HashMap<>();
        for (ProductionOrder other : productionOrders.ofSalesOrder(order.id().value())) {
            Integer seq = seqOf.get(other.deliveryBatchId());
            if (other.id().equals(excluding) || seq == null) {
                continue;
            }
            Map<UUID, Integer> bySeq = coming.computeIfAbsent(seq, key -> new HashMap<>());
            other.lines().forEach(line -> bySeq.merge(line.orderLineId(), other.outstandingCartons(line.orderLineId()),
                    Integer::sum));
        }
        ShippingWarehouseService.Coverage coverage = shippingWarehouses.coverage(order.id().value());
        Map<UUID, Integer> carried = new HashMap<>();
        Map<UUID, Integer> lacking = new LinkedHashMap<>();
        for (ShippingWarehouseService.BatchCoverage covered : coverage.batches().stream()
                .sorted(Comparator.comparingInt(ShippingWarehouseService.BatchCoverage::seq)).toList()) {
            if (covered.seq() > batch.seq()) {
                break;
            }
            Map<UUID, Integer> here = coming.getOrDefault(covered.seq(), Map.of());
            for (ShippingWarehouseService.LineCoverage line : covered.lines()) {
                int arriving = carried.getOrDefault(line.lineId(), 0) + here.getOrDefault(line.lineId(), 0);
                carried.put(line.lineId(), Math.max(0, arriving - line.shortCartons()));
                if (covered.seq() == batch.seq()) {
                    lacking.merge(line.lineId(), Math.max(0, line.shortCartons() - arriving), Integer::sum);
                }
            }
        }
        return lacking;
    }

    private Map<UUID, Integer> lacking(Locked locked) {
        return lacking(locked.order(), locked.schedule(), batchOf(locked.production(), locked.schedule()),
                locked.production().id());
    }

    private List<DeliveryBatch> scheduleOf(ProductionOrder production) {
        return contracts.findLiveByOrderId(production.salesOrderId()).map(SalesContract::schedule)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCTION_ORDER_NOT_ALLOWED, ("Production order %s: "
                        + "its order's contract is gone").formatted(production.code())));
    }

    private static DeliveryBatch batchOf(ProductionOrder production, List<DeliveryBatch> schedule) {
        return schedule.stream().filter(batch -> batch.id().equals(production.deliveryBatchId())).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCTION_ORDER_NOT_ALLOWED, ("Production order %s: "
                        + "its delivery batch is gone").formatted(production.code())));
    }

    /**
     * CONFIRMED, or AWAITING_STOCK (4A-6): an order waiting for stock has one production order sent and may need more -
     * another batch, or what a production order closed short no longer brings (17 §4.4 step 16).
     */
    private static void requireProducible(Order order) {
        if (!order.paidByContract()
                || order.status() != OrderStatus.CONFIRMED && order.status() != OrderStatus.AWAITING_STOCK) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_NOT_ALLOWED, ("Order %s is %s: production orders are "
                    + "drawn up for a confirmed order of a contract").formatted(order.orderNumber(), order.status()));
        }
        if (order.shippingWarehouseId() == null) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_NOT_ALLOWED, ("Order %s has no shipping warehouse: "
                    + "choose it and hold its stock first - production makes only what is missing there")
                    .formatted(order.orderNumber()));
        }
    }

    private Producer activeProducer(UUID producerId) {
        Producer producer = producers.findById(producerId).orElseThrow(() -> new BusinessException(
                ErrorCode.PRODUCER_NOT_FOUND, "No producer " + producerId));
        if (!producer.takesOrders()) {
            throw new BusinessException(ErrorCode.PRODUCER_INACTIVE, "Producer %s takes no new order"
                    .formatted(producer.code()));
        }
        return producer;
    }

    /** A production order locked with its sales order, the sales order first - the order {@code drawUp} locks in. */
    private record Locked(Order order, ProductionOrder production, List<DeliveryBatch> schedule) {
    }

    private Locked lockedWithOrder(UUID id, long version) {
        Order order = lockOrderOf(id);
        ProductionOrder production = locked(id, version);
        return new Locked(order, production, scheduleOf(production));
    }

    /**
     * The production order locked after its sales order, for a change that reads nothing of the order (returning,
     * cancelling, closing): the same lock order as {@link #lockedWithOrder}, without needing the contract's schedule -
     * a production order is still cancelled after its order's contract is gone.
     */
    private ProductionOrder lockedAfterItsOrder(UUID id, long version) {
        lockOrderOf(id);
        return locked(id, version);
    }

    /** The sales order of a production order the caller may see, locked. */
    private Order lockOrderOf(UUID id) {
        UUID salesOrderId = productionOrders.findById(id).filter(this::visible).orElseThrow(() -> notFound(id))
                .salesOrderId();
        return orders.findByIdForUpdate(new OrderId(salesOrderId)).orElseThrow();
    }

    private ProductionOrder locked(UUID id, long version) {
        ProductionOrder production = productionOrders.findByIdForUpdate(id).filter(this::visible)
                .orElseThrow(() -> notFound(id));
        if (production.version() != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, "Production order %s changed since it was read"
                    .formatted(production.code()));
        }
        return production;
    }

    /** A warehouse-bound caller (the warehouse manager) sees the production orders coming to their warehouses. */
    private boolean visible(ProductionOrder production) {
        return WarehouseScope.restriction().map(allowed -> allowed.ids().contains(production.receivingWarehouseId()))
                .orElse(true);
    }

    private static BusinessException notFound(UUID id) {
        return new BusinessException(ErrorCode.PRODUCTION_ORDER_NOT_FOUND, "No production order " + id);
    }

    /** The production orders of a sales order, for the order's own view. */
    @Transactional(readOnly = true)
    public List<ProductionOrder> ofSalesOrder(UUID salesOrderId) {
        return productionOrders.ofSalesOrder(salesOrderId).stream().filter(this::visible)
                .collect(Collectors.toList());
    }
}
