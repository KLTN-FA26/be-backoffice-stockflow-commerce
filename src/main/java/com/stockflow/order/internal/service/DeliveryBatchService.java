package com.stockflow.order.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.common.storage.RecordRetention;
import com.stockflow.common.storage.StagedUploads;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.common.storage.UploadSlot;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.order.api.OrderShipmentStatus;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.BatchInFlight;
import com.stockflow.order.internal.domain.DeliveryBatch;
import com.stockflow.order.internal.domain.DeliveryBatchRecords;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.SalesContract;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.order.internal.domain.WindowChangeReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The delivery schedule of an order as it is worked (4B-8; kltn-docs 17 §4.5, §5.5, BR-28, BR-29): the signed
 * contract's batches with the window in force, what each has been released, handed over and delivered, and the state
 * worked out from those facts - never set by hand (§5.5). Sales moves a window here (BR-28); the release
 * ({@link OrderReleaseService}) and the handover ({@code OrderServiceImpl}) ask it which batch is next and which is on
 * its way.
 *
 * <p>The signed contract stays as it was signed (14 BR-14): a moved window is a row of
 * {@code ordering.delivery_batch_window_change}, and the latest one of a batch is its window in force.</p>
 */
@Service
@Transactional
public class DeliveryBatchService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryBatchService.class);

    /** §5.5: the state of a batch, worked out. */
    public enum BatchState { AWAITING_STOCK, READY, IN_DELIVERY, DELIVERED, CLOSED }

    /** A line of a batch: its cartons, and how many are held for it (picked ones counted). */
    public record BatchLine(UUID orderLineId, String sku, int cartons, int heldCartons) {
    }

    /** One window move (BR-28), as the history shows it. */
    public record WindowChangeView(UUID id, LocalDate oldStart, LocalDate oldEnd, LocalDate newStart, LocalDate newEnd,
                                   WindowChangeReason reason, String note, String evidenceFileName,
                                   UUID evidenceMessageId, UUID changedBy, Instant changedAt) {
    }

    /**
     * A batch of the schedule.
     *
     * @param contractWindowStart the window the contract was signed with; {@code windowStart} is the one in force
     * @param late                DELIVERY_LATE (§4.12): its window ended and not every unit of it was handed over
     */
    public record BatchView(UUID batchId, int seq, LocalDate contractWindowStart, LocalDate contractWindowEnd,
                            LocalDate windowStart, LocalDate windowEnd, BatchState state, boolean late,
                            Instant releasedAt, List<BatchLine> lines, List<WindowChangeView> windowChanges) {
    }

    /** The order's delivery schedule in force. */
    public record Schedule(UUID orderId, String orderNumber, String contractNumber, List<BatchView> batches) {
    }

    /** What Sales sends to move a window: exactly one of {@code evidenceUploadId} and {@code evidenceMessageId}. */
    public record WindowChange(LocalDate windowStart, LocalDate windowEnd, WindowChangeReason reason, String note,
                               UUID evidenceUploadId, UUID evidenceMessageId) {
    }

    private final OrderRepository orders;
    private final SalesContractRepository contracts;
    private final DeliveryBatchRecords records;
    private final com.stockflow.order.internal.domain.ProductionOrderRepository productionOrders;
    private final ShippingWarehouseService shippingWarehouses;
    private final FileTransfers files;
    private final StagedUploads staged;
    private final TransactionTemplate tx;
    private final Clock clock;

    DeliveryBatchService(OrderRepository orders, SalesContractRepository contracts, DeliveryBatchRecords records,
                         ShippingWarehouseService shippingWarehouses, FileTransfers files, StagedUploads staged,
                         TransactionTemplate tx, Clock clock,
                         com.stockflow.order.internal.domain.ProductionOrderRepository productionOrders) {
        this.productionOrders = productionOrders;
        this.orders = orders;
        this.contracts = contracts;
        this.records = records;
        this.shippingWarehouses = shippingWarehouses;
        this.files = files;
        this.staged = staged;
        this.tx = tx;
        this.clock = clock;
    }

    // ---- reading --------------------------------------------------------------------------------------------------

    /** The schedule of an order as the back office sees it; the caller's data scope applies. */
    @Transactional(readOnly = true)
    public Schedule schedule(UUID orderId) {
        return scheduleOf(orders.findById(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId)));
    }

    /** The schedule of one of the customer's own orders (17 BR-28: the customer sees the windows in force). */
    @Transactional(readOnly = true)
    public Schedule scheduleOfCustomer(UUID customerId, UUID orderId) {
        return scheduleOf(orders.findById(new OrderId(orderId)).filter(o -> customerId.equals(o.customerId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId)));
    }

    private Schedule scheduleOf(Order order) {
        SalesContract contract = signedContract(order).orElse(null);
        if (contract == null) {
            return new Schedule(order.id().value(), order.orderNumber().value(), null, List.of());
        }
        Map<Integer, ShippingWarehouseService.BatchCoverage> cover = new HashMap<>();
        shippingWarehouses.coverage(order.id().value()).batches().forEach(b -> cover.put(b.seq(), b));
        Map<UUID, DeliveryBatchRecords.Release> releases = new HashMap<>();
        records.releasesOf(order.id().value()).forEach(r -> releases.put(r.batchId(), r));
        Map<UUID, List<DeliveryBatchRecords.WindowChange>> changes = changesByBatch(order);
        Map<UUID, OrderLine> lines = linesById(order);
        LocalDate today = BusinessCalendar.date(clock.instant());
        List<BatchView> batches = new ArrayList<>();
        for (DeliveryBatch batch : sorted(contract)) {
            List<DeliveryBatchRecords.WindowChange> moved = changes.getOrDefault(batch.id(), List.of());
            LocalDate start = moved.isEmpty() ? batch.windowStart() : moved.getLast().newStart();
            LocalDate end = moved.isEmpty() ? batch.windowEnd() : moved.getLast().newEnd();
            ShippingWarehouseService.BatchCoverage covered = cover.get(batch.seq());
            List<BatchLine> batchLines = batch.lines().stream().map(l -> new BatchLine(l.orderLineId(),
                    lines.containsKey(l.orderLineId()) ? lines.get(l.orderLineId()).sku().code() : null, l.cartons(),
                    covered == null ? 0 : covered.lines().stream().filter(c -> c.lineId().equals(l.orderLineId()))
                            .mapToInt(ShippingWarehouseService.LineCoverage::heldCartons).sum())).toList();
            int units = unitsOf(batch, lines);
            int handedOver = shippedUnits(order, batch.id(), false);
            int delivered = shippedUnits(order, batch.id(), true);
            BatchState state = stateOf(order, units, handedOver, delivered, covered != null && covered.fullyHeld());
            boolean late = end != null && today.isAfter(end) && handedOver < units
                    && state != BatchState.CLOSED;
            DeliveryBatchRecords.Release release = releases.get(batch.id());
            batches.add(new BatchView(batch.id(), batch.seq(), batch.windowStart(), batch.windowEnd(), start, end,
                    state, late, release == null ? null : release.releasedAt(), batchLines,
                    moved.stream().map(DeliveryBatchService::view).toList()));
        }
        return new Schedule(order.id().value(), order.orderNumber().value(), contract.contractNumber(), batches);
    }

    private static BatchState stateOf(Order order, int units, int handedOver, int delivered, boolean fullyHeld) {
        if (units > 0 && delivered >= units) {
            return BatchState.DELIVERED;
        }
        boolean closed = order.status() == OrderStatus.CANCELLED || order.status() == OrderStatus.CLOSED
                || order.status() == OrderStatus.COMPLETED;
        if (closed) {
            return BatchState.CLOSED;
        }
        if (handedOver > 0) {
            return BatchState.IN_DELIVERY;
        }
        return fullyHeld ? BatchState.READY : BatchState.AWAITING_STOCK;
    }

    // ---- what the release and the handover ask -----------------------------------------------------------------------

    /**
     * The batch to release next (17 BR-29): the earliest not released, once the one before it is handed over in full.
     * Empty for an order without a signed schedule (it is released whole) or one whose batches are all released.
     *
     * @throws BusinessException {@code DELIVERY_BATCH_NOT_NEXT} when the batch before it still has goods to hand over
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<DeliveryBatch> nextToRelease(Order order) {
        SalesContract contract = signedContract(order).orElse(null);
        if (contract == null || contract.schedule().isEmpty()) {
            return Optional.empty();
        }
        Set<UUID> released = new java.util.HashSet<>();
        records.releasesOf(order.id().value()).forEach(r -> released.add(r.batchId()));
        Map<UUID, OrderLine> lines = linesById(order);
        DeliveryBatch previous = null;
        for (DeliveryBatch batch : sorted(contract)) {
            if (!released.contains(batch.id())) {
                if (previous != null && shippedUnits(order, previous.id(), false) < unitsOf(previous, lines)) {
                    throw new BusinessException(ErrorCode.DELIVERY_BATCH_NOT_NEXT, ("Delivery batch %d of order %s is "
                            + "released once batch %d is handed over").formatted(batch.seq(), order.orderNumber(),
                            previous.seq()));
                }
                return Optional.of(batch);
            }
            previous = batch;
        }
        return Optional.empty();
    }

    /**
     * 4B-8 (15 §4.1, 17 §4.5): the batches every unit of which is in a delivered shipment, each with the moment its last
     * shipment was delivered - what an installment after delivery tied to the batch counts from.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public Map<UUID, Instant> deliveredBatches(Order order) {
        SalesContract contract = signedContract(order).orElse(null);
        Map<UUID, Instant> delivered = new HashMap<>();
        if (contract == null) {
            return delivered;
        }
        Map<UUID, OrderLine> lines = linesById(order);
        for (DeliveryBatch batch : contract.schedule()) {
            int units = unitsOf(batch, lines);
            if (units > 0 && shippedUnits(order, batch.id(), true) >= units) {
                order.shipments().stream().filter(s -> batch.id().equals(s.deliveryBatchId()))
                        .filter(s -> s.status() == OrderShipmentStatus.DELIVERED && s.outcomeAt() != null)
                        .map(com.stockflow.order.internal.domain.OrderShipment::outcomeAt)
                        .max(Comparator.naturalOrder()).ifPresent(at -> delivered.put(batch.id(), at));
            }
        }
        return delivered;
    }

    /** Whether the order works its schedule batch by batch: it has a signed contract with a delivery schedule. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean scheduled(Order order) {
        return signedContract(order).map(contract -> !contract.schedule().isEmpty()).orElse(false);
    }

    /** {@code batchId}'s lines - those of the order it carries. */
    public static Set<UUID> linesOf(DeliveryBatch batch) {
        Set<UUID> ids = new java.util.LinkedHashSet<>();
        batch.lines().forEach(l -> ids.add(l.orderLineId()));
        return ids;
    }

    /**
     * The batch on its way out (17 §4.5): the latest released, with its window in force and the units of the batches
     * after it. Empty for an order without released batches - it hands over as a whole, as before.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BatchInFlight> inFlight(Order order) {
        List<DeliveryBatchRecords.Release> releases = records.releasesOf(order.id().value());
        if (releases.isEmpty()) {
            return Optional.empty();
        }
        SalesContract contract = signedContract(order).orElse(null);
        if (contract == null) {
            return Optional.empty();
        }
        DeliveryBatchRecords.Release last = releases.getLast();
        DeliveryBatch batch = contract.schedule().stream().filter(b -> b.id().equals(last.batchId())).findFirst()
                .orElse(null);
        if (batch == null) {
            return Optional.empty();
        }
        Map<UUID, OrderLine> lines = linesById(order);
        int later = contract.schedule().stream().filter(b -> b.seq() > batch.seq()).mapToInt(b -> unitsOf(b, lines))
                .sum();
        List<DeliveryBatchRecords.WindowChange> moved = changesByBatch(order).getOrDefault(batch.id(), List.of());
        LocalDate start = moved.isEmpty() ? batch.windowStart() : moved.getLast().newStart();
        return Optional.of(new BatchInFlight(batch.id(), batch.seq(), start, later));
    }

    // ---- moving a window (BR-28) ----------------------------------------------------------------------------------

    /**
     * Where the browser uploads the customer's consent (an e-mail, a signed note) before the window is moved. Not
     * read-only: the order is read as the caller's data scope sees it, which locks its row ({@code findByIdInScope}),
     * and Postgres refuses {@code SELECT ... FOR UPDATE} in a read-only transaction.
     */
    @Transactional
    public UploadSlot beginEvidenceUpload(CurrentUser user, UUID orderId, UUID batchId, UploadSlotRequest request) {
        Order order = orders.findByIdInScope(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
        batchOf(order, batchId);
        return staged.begin(FileCategory.DOCUMENT, user.userId(), request);
    }

    /**
     * Sales moves a batch's window (17 BR-28): only Sales, only while no carton of it was handed over, not into the
     * past, keeping the schedule's order, with a reason, a note and the customer's consent - a file or a chat message.
     * SKUs, cartons and the number of batches do not change. Not transactional itself: an evidence file is checked and
     * kept first, then the change is recorded under the order's lock.
     *
     * @throws BusinessException {@code DELIVERY_BATCH_NOT_FOUND}, {@code DELIVERY_BATCH_WINDOW_LOCKED},
     *         {@code VALIDATION_FAILED}
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Auditable(action = AuditAction.UPDATE, resourceType = "delivery-batch", resourceId = "#batchId",
            details = "{orderId: #orderId}")
    public Schedule changeWindow(CurrentUser user, UUID orderId, UUID batchId, WindowChange change) {
        requireWellFormed(change);
        tx.executeWithoutResult(status -> check(lockedInScope(orderId), batchId, change));
        if (change.evidenceMessageId() != null) {
            return tx.execute(status -> record(user, orderId, batchId, change, null));
        }
        try (PreparedFiles prepared = IncomingFiles.uploaded(user.userId(), List.of(change.evidenceUploadId()))
                .prepare(FileCategory.DOCUMENT, files, staged)) {
            StoredFile stored = prepared.single().file();
            return tx.execute(status -> {
                Schedule schedule = record(user, orderId, batchId, change, stored);
                prepared.adopt();
                files.retainAfterCommit(stored.key(), RecordRetention.SALES_CONTRACT);
                return schedule;
            });
        }
    }

    private Schedule record(CurrentUser user, UUID orderId, UUID batchId, WindowChange change, StoredFile evidence) {
        Order order = lockedInScope(orderId);
        DeliveryBatch batch = check(order, batchId, change);
        List<DeliveryBatchRecords.WindowChange> moved = changesByBatch(order).getOrDefault(batch.id(), List.of());
        LocalDate oldStart = moved.isEmpty() ? batch.windowStart() : moved.getLast().newStart();
        LocalDate oldEnd = moved.isEmpty() ? batch.windowEnd() : moved.getLast().newEnd();
        records.recordWindowChange(order.id().value(), new DeliveryBatchRecords.WindowChange(Identifiers.newId(),
                batch.id(), oldStart, oldEnd, change.windowStart(), change.windowEnd(), change.reason(),
                change.note().strip(), evidence == null ? null : evidence.key(),
                evidence == null ? null : evidence.originalName(), change.evidenceMessageId(), user.userId(),
                clock.instant()));
        log.info("Order {}: delivery batch {} moved from {}..{} to {}..{} ({})", order.orderNumber(), batch.seq(),
                oldStart, oldEnd, change.windowStart(), change.windowEnd(), change.reason());
        // 17 §4.5: a draft production order of the batch takes the new default needed-by date (BR-26); one already
        // sent keeps its date - the production coordinator tells the producer.
        for (com.stockflow.order.internal.domain.ProductionOrder draft : productionOrders.ofSalesOrder(orderId)) {
            if (batch.id().equals(draft.deliveryBatchId()) && draft.followWindow(change.windowStart())) {
                productionOrders.save(draft);
                log.info("Production order {} of order {} is needed by {} now", draft.code(), order.orderNumber(),
                        change.windowStart());
            }
        }
        return scheduleOf(order);
    }

    private static void requireWellFormed(WindowChange change) {
        if (change == null || change.windowStart() == null || change.windowEnd() == null || change.reason() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The new window and a reason are required");
        }
        if (change.windowStart().isAfter(change.windowEnd())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The window starts no later than it ends");
        }
        if (change.note() == null || change.note().isBlank() || change.note().strip().length() > 1000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A note of at most 1000 characters is required");
        }
        if ((change.evidenceUploadId() == null) == (change.evidenceMessageId() == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("The customer's consent is either an uploaded "
                    + "file or a chat message, exactly one of them (17 BR-28)"));
        }
    }

    /** The rules of BR-28 against the order as it stands; the database checks them again. */
    private DeliveryBatch check(Order order, UUID batchId, WindowChange change) {
        if (order.status() == OrderStatus.CANCELLED || order.status() == OrderStatus.CLOSED
                || order.status() == OrderStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.CONFLICT, "Order %s is %s: its schedule no longer moves"
                    .formatted(order.orderNumber(), order.status()));
        }
        DeliveryBatch batch = batchOf(order, batchId);
        if (order.shipments().stream().anyMatch(s -> batch.id().equals(s.deliveryBatchId()))) {
            throw new BusinessException(ErrorCode.DELIVERY_BATCH_WINDOW_LOCKED, ("Delivery batch %d of order %s has goods "
                    + "handed over; its window no longer moves").formatted(batch.seq(), order.orderNumber()));
        }
        LocalDate today = BusinessCalendar.date(clock.instant());
        if (change.windowStart().isBefore(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The new window does not start in the past");
        }
        Map<UUID, List<DeliveryBatchRecords.WindowChange>> changes = changesByBatch(order);
        LocalDate before = null;
        for (DeliveryBatch b : sorted(signedContract(order).orElseThrow())) {
            List<DeliveryBatchRecords.WindowChange> moved = changes.getOrDefault(b.id(), List.of());
            LocalDate start = b.id().equals(batch.id()) ? change.windowStart()
                    : moved.isEmpty() ? b.windowStart() : moved.getLast().newStart();
            if (before != null && start != null && start.isBefore(before)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("Batch %d would start before the batch "
                        + "before it; move the later batches too (17 BR-28)").formatted(b.seq()));
            }
            before = start == null ? before : start;
        }
        return batch;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private Order lockedInScope(UUID orderId) {
        return orders.findByIdInScope(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
    }

    private DeliveryBatch batchOf(Order order, UUID batchId) {
        return signedContract(order).stream().flatMap(c -> c.schedule().stream())
                .filter(b -> b.id().equals(batchId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.DELIVERY_BATCH_NOT_FOUND,
                        "Order %s has no delivery batch %s".formatted(order.orderNumber(), batchId)));
    }

    private Optional<SalesContract> signedContract(Order order) {
        return contracts.findLiveByOrderId(order.id().value()).filter(c -> c.signedAt() != null);
    }

    private Map<UUID, List<DeliveryBatchRecords.WindowChange>> changesByBatch(Order order) {
        Map<UUID, List<DeliveryBatchRecords.WindowChange>> byBatch = new HashMap<>();
        records.windowChangesOf(order.id().value())
                .forEach(c -> byBatch.computeIfAbsent(c.batchId(), k -> new ArrayList<>()).add(c));
        return byBatch;
    }

    private static List<DeliveryBatch> sorted(SalesContract contract) {
        return contract.schedule().stream().sorted(Comparator.comparingInt(DeliveryBatch::seq)).toList();
    }

    private static Map<UUID, OrderLine> linesById(Order order) {
        Map<UUID, OrderLine> lines = new HashMap<>();
        order.lines().forEach(line -> lines.put(line.id(), line));
        return lines;
    }

    /**
     * The units a batch carries: its cartons of each line, in pieces - a line without a carton count as one piece per
     * carton, as {@code OrderService.batchUnits} and the release trigger count it.
     */
    private static int unitsOf(DeliveryBatch batch, Map<UUID, OrderLine> lines) {
        return batch.lines().stream().mapToInt(l -> {
            OrderLine line = lines.get(l.orderLineId());
            return line == null ? 0 : l.cartons() * (line.unitsPerCarton() == null ? 1 : line.unitsPerCarton());
        }).sum();
    }

    /** Units of the batch's shipments: handed over (not returned), or delivered only. */
    private static int shippedUnits(Order order, UUID batchId, boolean deliveredOnly) {
        return order.shipments().stream().filter(s -> batchId.equals(s.deliveryBatchId()))
                .filter(s -> deliveredOnly ? s.status() == OrderShipmentStatus.DELIVERED
                        : s.status() != OrderShipmentStatus.RETURNED)
                .mapToInt(s -> s.quantities().values().stream().mapToInt(Integer::intValue).sum()).sum();
    }

    private static WindowChangeView view(DeliveryBatchRecords.WindowChange c) {
        return new WindowChangeView(c.id(), c.oldStart(), c.oldEnd(), c.newStart(), c.newEnd(), c.reason(), c.note(),
                c.evidenceFileName(), c.evidenceMessageId(), c.changedBy(), c.changedAt());
    }
}
