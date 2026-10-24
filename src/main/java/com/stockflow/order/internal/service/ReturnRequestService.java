package com.stockflow.order.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.BufferedUpload;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.ReceiveReturnCommand;
import com.stockflow.inventory.api.ReclassifyStockCommand;
import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.api.StockDisposition;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ReturnChannel;
import com.stockflow.order.internal.domain.ReturnEvidence;
import com.stockflow.order.internal.domain.ReturnLine;
import com.stockflow.order.internal.domain.ReturnPolicy;
import com.stockflow.order.internal.domain.ReturnReason;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnRequestRepository;
import com.stockflow.order.internal.domain.ReturnResolution;
import com.stockflow.order.internal.domain.ReturnStatus;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.api.ReturnSettlement;
import com.stockflow.payment.api.SettleReturnCommand;
import com.stockflow.payment.api.SettleReturnResult;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Return requests (RMA, "đổi trả"; SCRUM-243 / SCRUM-260, 03-state-machines §12): the transaction boundary of every
 * step, and the one place the return, its order, the stock and the money are moved together.
 *
 * <p><b>Locks.</b> Every step takes the order's row lock first, then the return's - the order the settlement job and
 * payment take the order in - so a step and the nightly completion, a step and a payment, two steps of one return, or
 * two returns of one order are decided one after the other, each on what the other committed. Two staff approving at
 * once: the second waits, then finds the return approved ({@code RETURN_INVALID_TRANSITION}). A cancellation racing
 * the receipt: whichever commits first wins, the other is refused. A screen sending the version it showed is refused
 * with {@code OPTIMISTIC_LOCK} when the return moved meanwhile.</p>
 *
 * <p><b>The order follows its returns</b> (03 §9; BR-ORD-005). Opening one takes a DELIVERED order to
 * RETURN_REQUESTED, which keeps it from completing; when its last open return ends, the order goes back to DELIVERED
 * if no return gave money back (refused, withdrawn, expired, replaced only) - to complete once its window passed and it
 * is paid - or to RETURNED and then REFUNDED / PARTIALLY_REFUNDED if one did.</p>
 *
 * <p><b>Cross-module, in the same transaction:</b> inventory counts returned goods into quarantine, puts QC-passed
 * stock items back on sale and is asked for the write-offs (a second person approves them, BR-STK-005); payment books
 * the money ({@link ReturnSettlement}); a replacement is a new free order released and fulfilled as any order
 * ({@link ReplacementOrders}). Each step is announced with a {@code contracts.Return*} event after commit.</p>
 */
@Service
@Transactional
public class ReturnRequestService {

    private static final Logger log = LoggerFactory.getLogger(ReturnRequestService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * Open a return on {@code orderId}: the lines and units claimed, why, and what the customer would like.
     * {@code lateClaimReason}: Sales accept a claim outside the return window, and say why (back office only).
     */
    public record OpenReturnCommand(UUID requestId, UUID orderId, ReturnReason reason, String description,
                                    ReturnResolution requestedResolution, List<ReturnRequest.Claim> lines,
                                    String lateClaimReason) {

        /** Within the return window. */
        public OpenReturnCommand(UUID requestId, UUID orderId, ReturnReason reason, String description,
                                 ReturnResolution requestedResolution, List<ReturnRequest.Claim> lines) {
            this(requestId, orderId, reason, description, requestedResolution, lines, null);
        }
    }

    /** Sales' review: units approved per return line (none named: all), whether the goods come back. */
    public record ApproveReturnCommand(Map<UUID, Integer> approved, Boolean returnRequired, String note, Long version) {
    }

    /** The warehouse's count; {@code locationCode} null: the order's warehouse's quarantine (or QC) area. */
    public record ReceiveReturnGoodsCommand(String locationCode, List<ReturnRequest.Receipt> lines,
                                            List<ReturnRequest.Unexpected> unexpected, String note, Long version) {
    }

    /**
     * QC's verdict per line. {@code noMoreParcels}: approved units have not all arrived and are not coming - the return
     * is inspected without them (else it waits for the next parcel).
     */
    public record InspectReturnCommand(List<ReturnRequest.Inspection> lines, String note, Long version,
                                       Boolean noMoreParcels) {
        public InspectReturnCommand(List<ReturnRequest.Inspection> lines, String note, Long version) {
            this(lines, note, version, null);
        }
    }

    /** How the accepted units are made good, and how the money goes. */
    public record ResolveReturnCommand(List<ReturnRequest.Remedy> lines, ReturnResolution moneyMode, String note,
                                       Long version) {
    }

    private final ReturnRequestRepository returns;
    private final OrderRepository orders;
    private final OrderEventPublisher events;
    private final InventoryService inventory;
    private final WarehouseService warehouses;
    private final ReturnSettlement settlement;
    private final PaymentService payments;
    private final CustomerService customers;
    private final ReplacementOrders replacements;
    private final OrderServiceImpl orderService;
    private final FileTransfers files;
    private final UploadInspection inspection;
    private final ReturnPolicy policy;
    private final Clock clock;

    ReturnRequestService(ReturnRequestRepository returns, OrderRepository orders, OrderEventPublisher events,
                         InventoryService inventory, WarehouseService warehouses, ReturnSettlement settlement,
                         PaymentService payments, CustomerService customers, ReplacementOrders replacements,
                         OrderServiceImpl orderService, FileTransfers files, UploadInspection inspection,
                         ReturnPolicy policy, Clock clock) {
        this.inspection = inspection;
        this.returns = returns;
        this.orders = orders;
        this.events = events;
        this.inventory = inventory;
        this.warehouses = warehouses;
        this.settlement = settlement;
        this.payments = payments;
        this.customers = customers;
        this.replacements = replacements;
        this.orderService = orderService;
        this.files = files;
        this.policy = policy;
        this.clock = clock;
    }

    // ================================================================================================= opening

    /**
     * The customer opens a return on the portal: only on their own order (another customer's, or none, is 404 either
     * way) and only while their account is ACTIVE. Idempotent on {@code requestId}: the same request again returns the
     * return it opened.
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "return-request", resourceId = "#result?.id()")
    public ReturnRequest openOwn(UUID ownCustomerId, OpenReturnCommand command, UUID userId) {
        Objects.requireNonNull(ownCustomerId, "ownCustomerId");
        return open(command, ReturnChannel.PORTAL, userId, ownCustomerId);
    }

    /**
     * Sales open a return for any customer (a phone call, a visit), a blocked customer's included - also outside the
     * return window when they say why, and on an order a return already settled. Someone else approves it.
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "return-request", resourceId = "#result?.id()")
    public ReturnRequest openForCustomer(OpenReturnCommand command, UUID staffId) {
        return open(command, ReturnChannel.BACK_OFFICE, staffId, null);
    }

    private ReturnRequest open(OpenReturnCommand command, ReturnChannel channel, UUID by, UUID ownCustomerId) {
        Objects.requireNonNull(command, "command");
        if (command.requestId() == null || command.orderId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A return names its request id and its order");
        }
        // The order first, locked: a retry and its original, two returns of one order, a return and the nightly
        // completion are decided one after the other.
        Order order = orders.findByIdForUpdate(new OrderId(command.orderId()))
                .filter(found -> ownCustomerId == null || ownCustomerId.equals(found.customerId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + command.orderId()));
        Optional<ReturnRequest> replay = returns.findByRequestId(command.requestId());
        if (replay.isPresent()) {
            if (!replay.get().orderId().equals(order.id().value())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "Request %s already opened a return on another order".formatted(command.requestId()));
            }
            log.info("Request {} already opened return {}", command.requestId(), replay.get().rmaNumber());
            return replay.get();
        }
        if (channel == ReturnChannel.PORTAL) {
            String status = customers.findById(order.customerId()).map(CustomerSummary::status).orElse(null);
            if (!"ACTIVE".equals(status)) {
                throw new BusinessException(ErrorCode.RETURN_CUSTOMER_NOT_ACTIVE,
                        "This account is %s: ask Sales to open the return".formatted(status));
            }
        }
        Map<UUID, Integer> claimed = new HashMap<>();
        Set<UUID> open = new HashSet<>();
        for (ReturnRequest other : returns.findByOrder(order.id().value())) {
            for (ReturnLine line : other.lines()) {
                claimed.merge(line.orderLineId(), line.claimedUnits(other.status()), Integer::sum);
                if (other.status().isOpen()) {
                    open.add(line.orderLineId());
                }
            }
        }
        Instant now = now();
        ReturnRequest request = ReturnRequest.open(returns.nextRmaNumber(LocalDate.ofInstant(now, BUSINESS_ZONE)),
                command.requestId(), order, channel, command.reason(), command.description(),
                command.requestedResolution(), command.lines(), claimed, open, by, now, policy,
                channel == ReturnChannel.BACK_OFFICE ? command.lateClaimReason() : null, replacedPrices(order));
        if (order.requestReturn(by, now, "RETURN_REQUESTED " + request.rmaNumber())) {
            orders.save(order);
            events.publishEventsOf(order);
        }
        ReturnRequest saved = returns.save(request);
        events.publishEventsOf(request);
        log.info("Return {} opened on order {} ({}, {} line(s))", saved.rmaNumber(), order.orderNumber(),
                command.reason(), saved.lines().size());
        return saved;
    }

    /**
     * A free replacement order's lines cost nothing; the units they replaced were paid at the prices of the return that
     * replaced them, and a claim on them is credited at those - per line of the replacement order.
     */
    private Map<UUID, BigDecimal> replacedPrices(Order order) {
        if (order.replacesReturnRequestId() == null) {
            return Map.of();
        }
        Map<UUID, BigDecimal> prices = new HashMap<>();
        returns.findById(order.replacesReturnRequestId()).ifPresent(replaced -> replaced.lines().stream()
                .filter(line -> line.replacementOrderLineId() != null)
                .forEach(line -> prices.put(line.replacementOrderLineId(), line.unitPrice())));
        return prices;
    }

    // ================================================================================================= evidence

    /**
     * Keep a photo with the return. The customer's go with the claim (stage CLAIM) on their own return only; staff may
     * add receipt and inspection photos. The return's state is checked before anything is read; the file's declared
     * type must match its bytes (an image, never a document or a script) and it is scanned like every user upload
     * (design, product media) before it is stored - and deleted again when the transaction rolls back
     * ({@link FileTransfers}).
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest addEvidence(UUID returnId, UUID ownCustomerId, ReturnEvidence.Stage stage, FileUpload upload,
                                     UUID by) {
        ReturnRequest request = returns.findByIdForUpdate(returnId)
                .filter(found -> ownCustomerId == null || found.belongsTo(ownCustomerId))
                .orElseThrow(() -> notFound(returnId));
        ReturnEvidence.Stage taken = ownCustomerId != null || stage == null ? ReturnEvidence.Stage.CLAIM : stage;
        request.requireEvidenceAllowed(taken, policy);
        StoredFile file;
        try (BufferedUpload buffered = BufferedUpload.read(FileCategory.RETURN_EVIDENCE, upload)) {
            try (InputStream content = buffered.open()) {
                inspection.requireClean(content);
            }
            try (InputStream content = new BufferedInputStream(buffered.open())) {
                file = files.store(FileCategory.RETURN_EVIDENCE, new FileUpload(upload.originalName(),
                        upload.contentType(), upload.sizeBytes(), content));
            }
        } catch (IOException unreadable) {
            throw new StorageException("Could not read the uploaded photo", unreadable);
        }
        request.addEvidence(new ReturnEvidence(Identifiers.newId(), taken, file.key(), file.originalName(),
                file.contentType(), file.sizeBytes(), by, now()), policy);
        return returns.save(request);
    }

    // ================================================================================================= withdrawal

    /**
     * The customer (their own return) or Sales withdraw a return before its goods are back: REQUESTED or
     * AWAITING_RETURN → CANCELLED; the order goes back to DELIVERED if no other return of it is open.
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest cancel(UUID returnId, UUID ownCustomerId, String reason, UUID by) {
        Locked locked = lock(returnId, ownCustomerId, null);
        locked.request().cancel(by, now(), reason, ownCustomerId != null);
        return finish(locked, by);
    }

    // ================================================================================================= review

    /** Sales approve - in full, or fewer units per line (03 §12: REQUESTED → AWAITING_RETURN, or INSPECTED). */
    @Auditable(action = AuditAction.APPROVE, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest approve(UUID returnId, ApproveReturnCommand command, UUID by) {
        Locked locked = lock(returnId, null, command.version());
        ReturnRequest request = locked.request();
        // What the order's other returns already let the customer keep on photos alone: the limit is per order.
        BigDecimal leftWithCustomer = returns.findByOrder(request.orderId()).stream()
                .filter(other -> !other.id().equals(request.id()))
                .filter(other -> Boolean.FALSE.equals(other.returnRequired()))
                .filter(other -> other.status() != ReturnStatus.REJECTED && other.status() != ReturnStatus.CANCELLED)
                .flatMap(other -> other.lines().stream())
                .map(line -> line.unitPrice().multiply(BigDecimal.valueOf(
                        line.approvedQuantity() == null ? 0 : line.approvedQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        request.approve(by, now(), command.approved(), command.returnRequired(), command.note(), policy,
                leftWithCustomer);
        return finish(locked, by);
    }

    /** Sales refuse the return, saying why (03 §12: REQUESTED → REJECTED). */
    @Auditable(action = AuditAction.REJECT, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest reject(UUID returnId, String reason, Long version, UUID by) {
        Locked locked = lock(returnId, null, version);
        locked.request().reject(by, now(), reason);
        return finish(locked, by);
    }

    // ================================================================================================= receipt

    /**
     * The warehouse counts a parcel into quarantine (03 §12: AWAITING_RETURN → RECEIVED; OQ-12). Each line's units of
     * this parcel become QUARANTINE stock of the SKU that actually arrived, through {@code InventoryService.receiveReturn}
     * - every parcel of a return into the same quarantine layer, which QC's decisions name later; nothing is sellable
     * until QC decides. Restricted to the caller's warehouses by inventory (BR-SEC-002).
     *
     * <p>A parcel may also come after the return expired, or after the customer withdrew an approved one: the goods are
     * here, so they are counted in and the return is back to RECEIVED for QC and Sales - unless another return of the
     * same order line is open meanwhile, which then takes the goods instead ({@code RETURN_LINE_ALREADY_OPEN}). The
     * order shows the open claim again (RETURN_REQUESTED) unless it is closed for good.</p>
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest receive(UUID returnId, ReceiveReturnGoodsCommand command, UUID by) {
        Locked locked = lock(returnId, null, command.version());
        ReturnRequest request = locked.request();
        boolean reopening = request.receivingReopens();
        if (reopening) {
            requireNoOtherOpenReturn(locked);
        }
        String location = request.parcelCount() > 0 ? request.receivingLocation()
                : receivingLocation(locked.order(), command.locationCode());
        if (request.parcelCount() > 0 && command.locationCode() != null && !command.locationCode().isBlank()) {
            location = command.locationCode().trim();
        }
        List<ReturnRequest.Receipt> counted = new ArrayList<>();
        for (ReturnRequest.Receipt receipt : command.lines() == null ? List.<ReturnRequest.Receipt>of()
                : command.lines()) {
            counted.add(receipt == null || receipt.quantity() <= 0 ? receipt : withLotPolicy(request, receipt));
        }
        Map<UUID, Integer> parcelUnits = new HashMap<>();
        for (ReturnRequest.Receipt receipt : counted) {
            if (receipt != null && receipt.lineId() != null && receipt.quantity() > 0) {
                parcelUnits.merge(receipt.lineId(), receipt.quantity(), Integer::sum);
            }
        }
        request.receive(by, now(), location, counted, command.unexpected(), command.note());
        for (ReturnLine line : request.lines()) {
            int units = parcelUnits.getOrDefault(line.id(), 0);
            if (units > 0) {
                inventory.receiveReturn(new ReceiveReturnCommand(line.id(), new Sku(line.receivedSkuOrOwn()),
                        line.lotNumber(), request.receivingLocation(), units, request.orderId(), by,
                        request.receivedAt(), line.receivedQuantity()));
            }
        }
        if (reopening && locked.order().requestReturn(by, now(), "RETURN_GOODS_ARRIVED " + request.rmaNumber())) {
            orders.save(locked.order());
            events.publishEventsOf(locked.order());
        }
        return finish(locked, by);
    }

    /**
     * A return coming back to life must not claim a line another return of the order took meanwhile, nor, with what the
     * line's other returns claim now, more units than were delivered (the units it approved are what it claims again).
     */
    private void requireNoOtherOpenReturn(Locked locked) {
        ReturnRequest request = locked.request();
        Set<UUID> mine = new HashSet<>();
        request.lines().forEach(line -> mine.add(line.orderLineId()));
        Map<UUID, Integer> claimedElsewhere = new HashMap<>();
        for (ReturnRequest other : returns.findByOrder(request.orderId())) {
            if (other.id().equals(request.id())) {
                continue;
            }
            for (ReturnLine line : other.lines()) {
                if (!mine.contains(line.orderLineId())) {
                    continue;
                }
                if (other.status().isOpen()) {
                    throw new BusinessException(ErrorCode.RETURN_LINE_ALREADY_OPEN,
                            "%s of order %s is claimed by open return %s now: count these goods in on it".formatted(
                                    line.sku(), request.orderNumber(), other.rmaNumber()));
                }
                claimedElsewhere.merge(line.orderLineId(), line.claimedUnits(other.status()), Integer::sum);
            }
        }
        for (ReturnLine line : request.lines()) {
            com.stockflow.order.internal.domain.OrderLine ordered = locked.order().lines().stream()
                    .filter(l -> l.id().equals(line.orderLineId())).findFirst().orElseThrow();
            int delivered = ordered.quantity() - ordered.cancelledQuantity();
            int claimed = claimedElsewhere.getOrDefault(line.orderLineId(), 0)
                    + (line.approvedQuantity() == null ? 0 : line.approvedQuantity());
            if (claimed > delivered) {
                throw new BusinessException(ErrorCode.RETURN_QUANTITY_EXCEEDED, ("%s of order %s: %d of the %d unit(s) "
                        + "delivered are claimed by its returns since; these goods need Sales before they are counted in")
                        .formatted(line.sku(), request.orderNumber(), claimed, delivered));
            }
        }
    }

    /**
     * The location the goods are counted into: the one named, which must be a quarantine or QC area (never a bin:
     * OQ-12); otherwise the order's warehouse's quarantine area, or its QC area when it has none.
     */
    private String receivingLocation(Order order, String requested) {
        if (requested != null && !requested.isBlank()) {
            StorageLocationView location = warehouses.findLocation(requested.trim())
                    .orElseThrow(() -> new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + requested));
            if (location.areaType() != StorageLocationView.AreaType.QUARANTINE
                    && location.areaType() != StorageLocationView.AreaType.QUALITY_CONTROL) {
                throw new BusinessException(ErrorCode.RETURN_RECEIVING_LOCATION_INVALID,
                        "%s is not a quarantine or QC area".formatted(location.locationCode()));
            }
            if (!location.usable()) {
                throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                        "%s cannot take stock now".formatted(location.locationCode()));
            }
            return location.locationCode();
        }
        if (order.warehouseId() == null) {
            throw new BusinessException(ErrorCode.RETURN_RECEIVING_LOCATION_INVALID,
                    "Order %s names no warehouse: say which quarantine area the goods go to".formatted(
                            order.orderNumber()));
        }
        for (StorageLocationView.AreaType type : List.of(StorageLocationView.AreaType.QUARANTINE,
                StorageLocationView.AreaType.QUALITY_CONTROL)) {
            Optional<StorageLocationView> area = warehouses.areasOf(order.warehouseId(), type).stream()
                    .filter(StorageLocationView::usable).findFirst();
            if (area.isPresent()) {
                return area.get().locationCode();
            }
        }
        throw new BusinessException(ErrorCode.RETURN_RECEIVING_LOCATION_INVALID,
                "The warehouse of order %s has no usable quarantine or QC area".formatted(order.orderNumber()));
    }

    /**
     * What arrived for a line, as its SKU's inventory policy takes it: a SKU that is not a stock item of ours cannot
     * become stock (it is recorded as an unexpected item instead); a lot-tracked SKU needs the lot read off the goods,
     * and an untracked one keeps none (the stock policy refuses either otherwise). Returns of expiry-tracked SKUs are
     * not taken here: their expiry date is not known from the parcel (a goods receipt counts them).
     */
    private ReturnRequest.Receipt withLotPolicy(ReturnRequest request, ReturnRequest.Receipt receipt) {
        String code = receipt.sku() == null || receipt.sku().isBlank()
                ? request.findLine(receipt.lineId()).map(ReturnLine::receivedSkuOrOwn).orElse(null)
                : receipt.sku().trim();
        if (code == null) {
            return receipt;
        }
        Sku sku;
        try {
            sku = new Sku(code);
        } catch (IllegalArgumentException malformed) {
            throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                    "%s is not a SKU: record it as an unexpected item".formatted(code));
        }
        var item = inventory.itemPolicy(sku).orElseThrow(() -> new BusinessException(
                ErrorCode.RETURN_QUANTITIES_INVALID,
                "%s is not one of our stock items: record it as an unexpected item".formatted(code)));
        if (item.expiryTracked()) {
            throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                    "%s is expiry-tracked: count it in through a goods receipt with its expiry date".formatted(code));
        }
        boolean lotGiven = receipt.lotNumber() != null && !receipt.lotNumber().isBlank();
        if (item.lotTracked() && !lotGiven) {
            throw new BusinessException(ErrorCode.RETURN_QUANTITIES_INVALID,
                    "%s is lot-tracked: read the lot number off the goods".formatted(code));
        }
        return item.lotTracked() ? receipt
                : new ReturnRequest.Receipt(receipt.lineId(), receipt.quantity(), receipt.sku(), null);
    }

    // ================================================================================================= inspection

    /**
     * QC decides every unit received (03 §12: RECEIVED → INSPECTED, or REJECTED when none holds the claim) and where it
     * goes: accepted plain stock back on sale at a bin of the same warehouse now (QUARANTINE → AVAILABLE, OQ-12);
     * accepted units to scrap, and rejected units - the customer's - going back to them or, with their consent,
     * scrapped, as inventory adjustments a second person approves (BR-STK-005); until then they stay in quarantine, out
     * of sale.
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest inspect(UUID returnId, InspectReturnCommand command, UUID by) {
        Locked locked = lock(returnId, null, command.version());
        ReturnRequest request = locked.request();
        for (ReturnRequest.Inspection line : command.lines() == null ? List.<ReturnRequest.Inspection>of()
                : command.lines()) {
            if (line != null && line.restock() > 0 && line.restockLocation() != null
                    && !line.restockLocation().isBlank()) {
                requireSellingBin(request, line.restockLocation().trim());
            }
        }
        request.inspect(by, now(), command.lines(), command.note(), Boolean.TRUE.equals(command.noMoreParcels()));
        for (ReturnLine line : request.lines()) {
            Sku sku = new Sku(line.receivedSkuOrOwn());
            if (line.restockQuantity() > 0) {
                inventory.reclassify(new ReclassifyStockCommand(sku, line.lotNumber(), request.receivingLocation(),
                        line.restockLocation(), line.restockQuantity(), StockDisposition.AVAILABLE,
                        MoveReference.RETURN_REQUEST_LINE, line.id(), by,
                        "%s: passed QC, back on sale".formatted(request.rmaNumber()), request.receivedAt()));
            }
            UUID scrap = line.scrapQuantity() == 0 ? null : inventory.requestAdjustment(new RequestAdjustmentCommand(
                    request.receivingLocation(), sku, line.lotNumber(), -line.scrapQuantity(),
                    StockAdjustmentReason.SCRAP, scrapNote(request, line), by, request.receivedAt())).adjustmentId();
            UUID back = line.customerReturnQuantity() == 0 ? null : inventory.requestAdjustment(
                    new RequestAdjustmentCommand(request.receivingLocation(), sku, line.lotNumber(),
                            -line.customerReturnQuantity(), StockAdjustmentReason.OTHER,
                            "%s: no defect found, goods sent back to the customer".formatted(request.rmaNumber()), by,
                            request.receivedAt())).adjustmentId();
            if (scrap != null || back != null) {
                request.recordStockAdjustments(line.id(), scrap, back);
            }
        }
        return finish(locked, by);
    }

    /** What the approver of a scrap write-off reads: whose goods, and for the customer's, how they agreed. */
    private static String scrapNote(ReturnRequest request, ReturnLine line) {
        int customers = line.rejectedScrapped();
        return customers == 0 ? "%s: returned goods scrapped after QC".formatted(request.rmaNumber())
                : "%s: returned goods scrapped after QC, %d of them the customer's with their consent: %s".formatted(
                        request.rmaNumber(), customers, line.disposalConsent());
    }

    /**
     * Goods back on sale go to a bin - pickable stock - of the warehouse that received them, usable now: a quarantine,
     * QC or dock area would count them in what may be promised while no picker can be sent there.
     */
    private void requireSellingBin(ReturnRequest request, String code) {
        StorageLocationView bin = warehouses.findLocation(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + code));
        StorageLocationView received = request.receivingLocation() == null ? null
                : warehouses.findLocation(request.receivingLocation()).orElse(null);
        if (bin.kind() != StorageLocationView.Kind.BIN || !bin.usable() || received == null
                || !bin.warehouseId().equals(received.warehouseId())) {
            throw new BusinessException(ErrorCode.RETURN_RESTOCK_LOCATION_INVALID,
                    "%s is not a usable bin of the warehouse that received the goods of %s".formatted(code,
                            request.rmaNumber()));
        }
    }

    // ================================================================================================= resolution

    /**
     * Sales say how the accepted units are made good. Every resolution waits for a second person (SETTLEMENT_PENDING):
     * free replacement goods are worth what money is. What would refuse the execution later is checked now: a
     * replacement that cannot be placed, a cash refund to a customer with overdue receivables.
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest proposeResolution(UUID returnId, ResolveReturnCommand command, UUID by) {
        Locked locked = lock(returnId, null, command.version());
        ReturnRequest request = locked.request();
        request.proposeResolution(by, now(), command.lines(), command.moneyMode(), command.note());
        replacements.requirePlaceable(locked.order(), request);
        requireRefundAllowed(request);
        return finish(locked, by);
    }

    /**
     * The second person approves the resolution (four eyes, BR-PAY-003 without approver limits): the replacement order
     * is placed, payment books the money, the return is RESOLVED - all in this transaction, or none of it.
     */
    @Auditable(action = AuditAction.APPROVE, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest approveSettlement(UUID returnId, Long version, UUID by) {
        Locked locked = lock(returnId, null, version);
        locked.request().requireSettlementApprover(by);
        requireRefundAllowed(locked.request());
        carryOut(locked, by);
        return finish(locked, by);
    }

    /** The approver sends the settlement back to Sales, saying why: SETTLEMENT_PENDING → INSPECTED. */
    @Auditable(action = AuditAction.REJECT, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest rejectSettlement(UUID returnId, String reason, Long version, UUID by) {
        Locked locked = lock(returnId, null, version);
        locked.request().rejectSettlement(by, now(), reason);
        return finish(locked, by);
    }

    /** A customer who owes money past its due date is credited, never paid out in cash. */
    private void requireRefundAllowed(ReturnRequest request) {
        if (request.moneyMode() == ReturnResolution.REFUND && payments.creditPosition(request.customerId()).hasOverdue()) {
            throw new BusinessException(ErrorCode.RETURN_REFUND_CUSTOMER_OVERDUE,
                    "The customer of %s has overdue receivables: resolve it as a credit note".formatted(
                            request.rmaNumber()));
        }
    }

    private void carryOut(Locked locked, UUID by) {
        ReturnRequest request = locked.request();
        ReplacementOrders.Placed placed = request.replacementUnits() == 0 ? null
                : replacements.place(locked.order(), request, by);
        ReturnRequest.MoneyOutcome money = ReturnRequest.MoneyOutcome.NONE;
        Order paidOn = paidOn(locked.order());
        if (request.settlementAmount().signum() > 0) {
            SettleReturnResult booked = settlement.settleReturn(new SettleReturnCommand(request.id(),
                    request.rmaNumber(), paidOn.id().value(), paidOn.orderNumber().value(), request.customerId(),
                    request.settlementAmount(), request.currency(), request.moneyMode() == ReturnResolution.REFUND,
                    by));
            money = new ReturnRequest.MoneyOutcome(booked.offsetAmount(), booked.refundedAmount(),
                    booked.creditedAmount(), booked.creditTransferId());
        }
        request.resolve(by, now(), placed == null ? null : placed.orderId(),
                placed == null ? null : placed.orderNumber(), placed == null ? Map.of() : placed.lineByReturnLine(),
                money, paidOn.id().value());
    }

    /**
     * The order the goods were paid on: the returned order itself, or for a free replacement order - which received
     * no money - the order whose units it replaced, followed back through every replacement in between. Its money is
     * what a defective reprint is refunded from or credited against.
     */
    private Order paidOn(Order order) {
        Order current = order;
        Set<UUID> seen = new HashSet<>();
        while (current.replacesReturnRequestId() != null && seen.add(current.id().value())) {
            ReturnRequest replaced = returns.findById(current.replacesReturnRequestId()).orElseThrow();
            current = orders.findById(new OrderId(replaced.orderId())).orElseThrow();
        }
        return current;
    }

    // ================================================================================================= replacement withdrawal

    /**
     * Sales propose to withdraw a resolved return's free replacement order before it ships, crediting the replaced
     * units instead (SCRUM-260): the customer would rather have the money, or the goods cannot be made again. Nothing
     * changes until a second person approves; the replacement order must still be cancellable - not yet packed or
     * handed over - now and then.
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest proposeReplacementWithdrawal(UUID returnId, String reason, Long version, UUID by) {
        Locked locked = lock(returnId, null, version);
        ReturnRequest request = locked.request();
        request.proposeReplacementWithdrawal(by, now(), reason);
        withdrawable(orders.findById(new OrderId(request.replacementOrderId())).orElseThrow(), request);
        return finish(locked, by);
    }

    /**
     * The second person approves the withdrawal: the replacement order is cancelled (REPLACEMENT_WITHDRAWN - its holds
     * released, anything already printed for it scrapped, BR-PRD-10) and payment credits the replaced units' value -
     * against the original order's receivables first, the rest as the customer's credit (reference RMA number + "-W").
     * All in this transaction, or none of it.
     */
    @Auditable(action = AuditAction.APPROVE, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest approveReplacementWithdrawal(UUID returnId, Long version, UUID by) {
        Locked locked = lock(returnId, null, version);
        ReturnRequest request = locked.request();
        request.requireWithdrawalApprover(by);
        Order replacement = orderService.loadForUpdate(request.replacementOrderId());
        Map<UUID, Integer> withdrawn = withdrawable(replacement, request);
        String note = "%s: %s".formatted(request.rmaNumber(), request.withdrawalReason());
        if (replacement.packedAt() == null) {
            orderService.cancelLoaded(replacement, new CancelOrderCommand(CancellationReasonCode.REPLACEMENT_WITHDRAWN,
                    note, null, by));
        } else {
            // Shipped in part: the customer keeps what reached them; the rest is closed and credited.
            orderService.closeRemainder(replacement.id().value(), CancellationReasonCode.REPLACEMENT_WITHDRAWN, note,
                    null, by);
        }
        ReturnRequest.MoneyOutcome money = ReturnRequest.MoneyOutcome.NONE;
        Order paidOn = paidOn(locked.order());
        BigDecimal amount = request.withdrawalAmountFor(withdrawn);
        if (amount.signum() > 0) {
            SettleReturnResult booked = settlement.settleReturn(new SettleReturnCommand(request.id(),
                    request.rmaNumber(), paidOn.id().value(), paidOn.orderNumber().value(), request.customerId(),
                    amount, request.currency(), false, by, request.rmaNumber() + "-W"));
            money = new ReturnRequest.MoneyOutcome(booked.offsetAmount(), booked.refundedAmount(),
                    booked.creditedAmount(), booked.creditTransferId());
        }
        request.approveReplacementWithdrawal(by, now(), money, paidOn.id().value(), withdrawn);
        log.info("Return {}: replacement order {} withdrawn, {} {} credited", request.rmaNumber(),
                replacement.orderNumber(), request.withdrawalAmount().stripTrailingZeros().toPlainString(),
                request.currency());
        return finish(locked, by);
    }

    /** The approver sends the withdrawal back, saying why: the replacement ships as decided. */
    @Auditable(action = AuditAction.REJECT, resourceType = "return-request", resourceId = "#returnId")
    public ReturnRequest rejectReplacementWithdrawal(UUID returnId, String reason, Long version, UUID by) {
        Locked locked = lock(returnId, null, version);
        locked.request().rejectReplacementWithdrawal(by, now(), reason);
        return finish(locked, by);
    }

    /**
     * What of the replacement can still be withdrawn, per return line: all of it while nothing is packed (the order is
     * cancelled), or - shipped in part (PARTIALLY_FULFILLED) - the units not shipped, whose rest is closed. Goods packed,
     * handed over or delivered come back as a return instead.
     */
    private static Map<UUID, Integer> withdrawable(Order replacement, ReturnRequest request) {
        boolean whole = replacement.status().canTransitionTo(com.stockflow.order.api.OrderStatus.CANCELLED)
                && replacement.packedAt() == null;
        boolean rest = replacement.status() == com.stockflow.order.api.OrderStatus.PARTIALLY_FULFILLED;
        Map<UUID, Integer> units = new HashMap<>();
        if (whole || rest) {
            for (ReturnLine line : request.lines()) {
                if (line.replacementQuantity() > 0 && line.replacementOrderLineId() != null) {
                    units.put(line.id(), whole ? line.replacementQuantity()
                            : replacement.unshippedUnits(line.replacementOrderLineId()));
                }
            }
        }
        if (units.values().stream().mapToInt(Integer::intValue).sum() <= 0) {
            throw new BusinessException(ErrorCode.RETURN_REPLACEMENT_NOT_WITHDRAWABLE,
                    "Replacement order %s of %s is %s: nothing of it can be withdrawn any more".formatted(
                            replacement.orderNumber(), request.rmaNumber(), replacement.status()));
        }
        return units;
    }

    // ================================================================================================= expiry

    /** The returns whose goods should have arrived by now, for the sweep. */
    @Transactional(readOnly = true)
    public List<UUID> expiryCandidates(int limit) {
        return returns.awaitingReturnPast(clock.instant(), limit);
    }

    /**
     * The approved goods did not arrive by the deadline (03 §12: AWAITING_RETURN → EXPIRED), each return in its own
     * transaction. A parcel counted in meanwhile wins: the return is no longer waiting, and nothing changes.
     *
     * @return whether it expired
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "return-request", resourceId = "#returnId")
    public boolean expire(UUID returnId) {
        Optional<UUID> orderId = returns.orderOf(returnId);
        if (orderId.isEmpty()) {
            return false;
        }
        Order order = orders.findByIdForUpdate(new OrderId(orderId.get())).orElseThrow();
        ReturnRequest request = returns.findByIdForUpdate(returnId).orElseThrow();
        if (!request.expire(now())) {
            return false;
        }
        finish(new Locked(order, request), null);
        log.info("Return {} expired: its goods did not arrive by {}", request.rmaNumber(), request.returnDeadline());
        return true;
    }

    // ================================================================================================= helpers

    /** A return and its order, both locked: the order first, as every money and stock path takes them. */
    private record Locked(Order order, ReturnRequest request) {
    }

    private Locked lock(UUID returnId, UUID ownCustomerId, Long expectedVersion) {
        UUID orderId = returns.orderOf(returnId).orElseThrow(() -> notFound(returnId));
        Order order = orders.findByIdForUpdate(new OrderId(orderId)).orElseThrow();
        ReturnRequest request = returns.findByIdForUpdate(returnId)
                .filter(found -> ownCustomerId == null || found.belongsTo(ownCustomerId))
                .orElseThrow(() -> notFound(returnId));
        if (expectedVersion != null && expectedVersion != request.version()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Return %s changed meanwhile (version %d, not %d): reload it".formatted(request.rmaNumber(),
                            request.version(), expectedVersion));
        }
        return new Locked(order, request);
    }

    /**
     * Saves the return, moves its order when the return was the order's last open one, and announces both. The return
     * is read again with its siblings, so the order's next status is decided on every return of the order as they now
     * stand (this one's state in memory, not yet saved).
     */
    private ReturnRequest finish(Locked locked, UUID by) {
        ReturnRequest request = locked.request();
        Order order = locked.order();
        if (!request.status().isOpen()) {
            List<ReturnRequest> all = new ArrayList<>(returns.findByOrder(order.id().value()));
            all.removeIf(other -> other.id().equals(request.id()));
            all.add(request);
            if (all.stream().noneMatch(other -> other.status().isOpen())) {
                BigDecimal given = all.stream().filter(other -> other.status() == ReturnStatus.RESOLVED)
                        .map(other -> other.money().total()).reduce(BigDecimal.ZERO, BigDecimal::add);
                Instant now = now();
                boolean moved = given.signum() > 0
                        ? order.settleReturn(given.compareTo(order.billable()) >= 0, by, now,
                                "RETURN_SETTLED " + request.rmaNumber())
                        : order.withdrawReturn(by, now, "RETURN_" + request.status().name() + " " + request.rmaNumber());
                if (moved) {
                    orders.save(order);
                    events.publishEventsOf(order);
                }
            }
        }
        ReturnRequest saved = returns.save(request);
        events.publishEventsOf(request);
        return saved;
    }

    /** Microseconds, as the database keeps them: a receipt time the stock layer is found by again must compare equal. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static BusinessException notFound(UUID returnId) {
        return new BusinessException(ErrorCode.RETURN_REQUEST_NOT_FOUND, "No return request " + returnId);
    }
}
