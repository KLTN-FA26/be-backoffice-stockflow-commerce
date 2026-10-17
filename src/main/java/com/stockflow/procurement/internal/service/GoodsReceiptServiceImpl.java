package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.api.FieldError;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.contracts.GoodsReceiptConfirmed;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.contracts.ReceiptStockReadyForPutaway;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockCommand;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.ReclassifyStockCommand;
import com.stockflow.inventory.api.StockDisposition;
import com.stockflow.procurement.internal.domain.ExpiryWarning;
import com.stockflow.procurement.internal.domain.GoodsReceipt;
import com.stockflow.procurement.internal.domain.GoodsReceiptRepository;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.domain.QcInspection;
import com.stockflow.procurement.internal.domain.QcOutcome;
import com.stockflow.procurement.internal.domain.ReceiptLine;
import com.stockflow.procurement.internal.domain.ReceivingLocation;
import com.stockflow.procurement.internal.domain.ReceivingLocations;
import com.stockflow.procurement.internal.domain.ReceivingPurchaseOrder;
import com.stockflow.procurement.internal.domain.ReceivingPurchaseOrders;
import com.stockflow.procurement.internal.repository.GoodsReceiptSearch;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Goods receipts against purchase orders (SCRUM-435, docs 03).
 *
 * <p>Confirming is the one step with consequences beyond the receipt, and they all happen in its
 * transaction: the goods become INBOUND stock (inventory, same transaction through
 * {@code inventory :: api}), the purchase order's lines and status move on, and the events go out
 * after commit. A failure anywhere leaves the receipt a DRAFT with nothing counted in.</p>
 */
@Service
@Transactional
class GoodsReceiptServiceImpl implements GoodsReceipts {

    private static final Logger log = LoggerFactory.getLogger(GoodsReceiptServiceImpl.class);

    /** Receipt numbers and the BR-06 expiry check follow the business's calendar, not UTC. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final String RECEIVING = "RECEIVING";
    private static final String QUALITY_CONTROL = "QUALITY_CONTROL";
    private static final String QUARANTINE = "QUARANTINE";

    private static final SortWhitelist SORT = SortWhitelist.of("receivedAt", "receiptNumber", "status")
            .withDefault("receivedAt", Sort.Direction.DESC);

    private final GoodsReceiptRepository receipts;
    private final GoodsReceiptSearch search;
    private final ReceivingPurchaseOrders orders;
    private final ReceivingLocations locations;
    private final InventoryService inventory;
    private final WarehouseService warehouses;
    private final IdentityService identity;
    private final InventoryControlService inventoryControl;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    GoodsReceiptServiceImpl(GoodsReceiptRepository receipts, GoodsReceiptSearch search, ReceivingPurchaseOrders orders,
                            ReceivingLocations locations, InventoryService inventory, WarehouseService warehouses,
                            IdentityService identity, InventoryControlService inventoryControl,
                            ApplicationEventPublisher events, Clock clock) {
        this.receipts = receipts;
        this.search = search;
        this.orders = orders;
        this.locations = locations;
        this.inventory = inventory;
        this.warehouses = warehouses;
        this.identity = identity;
        this.inventoryControl = inventoryControl;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ counting

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "goods-receipt", resourceId = "#result?.id()")
    public ReceiptView create(Create command, UUID userId) {
        ReceivingPurchaseOrder order = orders.find(command.purchaseOrderId()).orElseThrow(() ->
                new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND, "No purchase order " + command.purchaseOrderId()));
        requireReceivable(order);
        // BR-SEC-002: goods are received by the staff of the warehouse the order delivers to.
        com.stockflow.common.security.WarehouseScope.requireWarehouse(order.warehouseId());
        Instant now = clock.instant();
        GoodsReceipt receipt = GoodsReceipt.draft(Identifiers.newId(),
                receipts.nextNumber(LocalDate.ofInstant(now, BUSINESS_ZONE)), order.id(), order.activeRevisionId(),
                order.warehouseId(), userId, blankToNull(command.deliveryNote()), blankToNull(command.note()), now);
        return view(receipts.save(receipt), order);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every line is checked before anything is refused (SCRUM-464): the form gets one answer
     * naming each field at fault — {@code lines[i].purchaseOrderLineId}, {@code .locationCode},
     * {@code .lotNumber}, {@code .expiryDate}, {@code .quantity} — and the top-level error code is the
     * first one found, line by line. {@code check_goods_receipt_line} checks the same again.</p>
     */
    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "goods-receipt", resourceId = "#receiptId")
    public ReceiptView replaceLines(UUID receiptId, List<NewLine> newLines) {
        GoodsReceipt receipt = load(receiptId);
        ReceivingPurchaseOrder order = orderOf(receipt);
        LocalDate receivedOn = LocalDate.ofInstant(receipt.receivedAt(), BUSINESS_ZONE);

        LineErrors errors = new LineErrors();
        List<ReceiptLine> counted = new ArrayList<>();
        Map<UUID, Integer> perPoLine = new LinkedHashMap<>();
        Map<UUID, List<Integer>> indexesOf = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < newLines.size(); i++) {
            NewLine in = newLines.get(i);
            Optional<ReceivingPurchaseOrder.Line> found = order.line(in.purchaseOrderLineId());
            if (found.isEmpty()) {
                errors.add(i, "purchaseOrderLineId", ErrorCode.RECEIPT_LINE_NOT_ON_PO,
                        "Line %s is not a line of %s".formatted(in.purchaseOrderLineId(), order.number()));
                continue;
            }
            ReceivingPurchaseOrder.Line poLine = found.get();
            if (!poLine.open()) {
                errors.add(i, "purchaseOrderLineId", ErrorCode.PO_LINE_NOT_OPEN,
                        "Line %d of %s is %s".formatted(poLine.lineNo(), order.number(), poLine.status()));
                continue;
            }
            InventoryItemPolicy item = policy(poLine.inventoryItemId());
            String lot = blankToNull(in.lotNumber());
            int before = errors.size();
            ReceivingLocation location = errors.area(i, in.locationCode(), RECEIVING, receipt.warehouseId());
            checkLotData(errors, i, item, lot, in.expiryDate(), receivedOn);
            if (!seen.add(poLine.id() + "|" + Objects.toString(lot, ""))) {
                errors.add(i, "lotNumber", ErrorCode.DUPLICATE_RECEIPT_LINE,
                        "Line %d of %s with this lot is already counted above; put the whole quantity of a lot on one line"
                                .formatted(poLine.lineNo(), order.number()));
            }
            perPoLine.merge(poLine.id(), in.quantity(), Integer::sum);
            indexesOf.computeIfAbsent(poLine.id(), id -> new ArrayList<>()).add(i);
            if (errors.size() == before) {
                // A SUBCONTRACT order's goods get one inspection, the production QC against the approved sample
                // (user decision 2026-10-13): receiving counts them and does not inspect the same goods again.
                counted.add(ReceiptLine.counted(Identifiers.newId(), poLine.id(), poLine.inventoryItemId(),
                        location.id(), in.quantity(), lot, in.expiryDate(), blankToNull(in.note()),
                        item.qcRequired() && !order.subcontract()));
            }
        }
        for (var over : overTolerance(order, receipt.id(), perPoLine)) {
            for (int i : indexesOf.get(over.poLineId())) {
                errors.add(i, "quantity", ErrorCode.OVER_RECEIPT_TOLERANCE, over.message());
            }
        }
        errors.throwIfAny();
        receipt.replaceLines(counted);
        return view(receipts.save(receipt), order);
    }

    /**
     * The refusals of one request, by line: thrown once, as one {@link BusinessException} whose code
     * is the first refusal's and whose {@code fieldErrors} name every field.
     */
    private final class LineErrors {

        /** Kept in line order, whatever order the checks ran in. */
        private final java.util.TreeMap<Integer, List<FieldError>> byLine = new java.util.TreeMap<>();
        private final Map<Integer, ErrorCode> codeByLine = new HashMap<>();
        private final Map<Integer, String> messageByLine = new HashMap<>();
        private int count;

        void add(int index, String field, ErrorCode code, String message) {
            add(index, field, code, code.name(), message);
        }

        void add(int index, String field, ErrorCode code, String fieldCode, String message) {
            byLine.computeIfAbsent(index, i -> new ArrayList<>())
                    .add(new FieldError("lines[%d].%s".formatted(index, field), message, fieldCode));
            codeByLine.putIfAbsent(index, code);
            messageByLine.putIfAbsent(index, message);
            count++;
        }

        int size() {
            return count;
        }

        /** The area, or null with the refusal recorded: LOCATION_NOT_FOUND or LOCATION_AREA_MISMATCH. */
        ReceivingLocation area(int index, String code, String type, UUID warehouseId) {
            try {
                return GoodsReceiptServiceImpl.this.area(code, type, warehouseId);
            } catch (BusinessException refused) {
                add(index, "locationCode", refused.errorCode(), refused.getMessage());
                return null;
            }
        }

        void throwIfAny() {
            if (byLine.isEmpty()) {
                return;
            }
            int firstLine = byLine.firstKey();
            List<FieldError> all = byLine.values().stream().flatMap(List::stream).toList();
            throw new BusinessException(codeByLine.get(firstLine),
                    "Receipt line %d: %s".formatted(firstLine + 1, messageByLine.get(firstLine))
                            + (all.size() > 1 ? " (and %d more)".formatted(all.size() - 1) : ""), all);
        }
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "goods-receipt", resourceId = "#receiptId")
    public ReceiptView confirm(UUID receiptId, UUID userId) {
        GoodsReceipt receipt = load(receiptId);
        // Lock the order first: two receipts of one order confirmed together must not both compute
        // its status from totals that miss the other.
        ReceivingPurchaseOrder order = orders.lock(receipt.purchaseOrderId()).orElseThrow(() ->
                new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND, "No purchase order " + receipt.purchaseOrderId()));
        requireReceivable(order);
        Map<UUID, Integer> perPoLine = new LinkedHashMap<>();
        receipt.lines().forEach(l -> perPoLine.merge(l.poLineId(), l.receivedQuantity(), Integer::sum));
        overTolerance(order, receipt.id(), perPoLine).stream().findFirst().ifPresent(over -> {
            throw new BusinessException(ErrorCode.OVER_RECEIPT_TOLERANCE, over.message());
        });
        // A SUBCONTRACT order's goods wait for production QC in the QUALITY_CONTROL area as well.
        if ((order.subcontract() || receipt.lines().stream().anyMatch(ReceiptLine::qcRequired))
                && !locations.hasArea(receipt.warehouseId(), QUALITY_CONTROL)) {
            // Docs 03 edge case: the 3-step flow has nowhere to go.
            throw new BusinessException(ErrorCode.QC_AREA_MISSING,
                    "This receipt has items that need QC, and the warehouse has no QUALITY_CONTROL area yet");
        }

        // Microseconds, as the database keeps them: the confirmation time is also the receipt time of
        // the stock layers this creates, and the QC steps find their layer again by it.
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        receipt.confirm(userId, now, order.subcontract());
        GoodsReceipt saved = receipts.save(receipt);

        Map<UUID, ReceivingLocation> where = locations.byIds(
                saved.lines().stream().map(ReceiptLine::locationId).toList());
        Map<UUID, InventoryItemPolicy> items = new HashMap<>();
        List<GoodsReceiptConfirmed.Line> confirmedLines = new ArrayList<>();
        List<ReceiptStockReadyForPutaway.Line> readyLines = new ArrayList<>();
        for (ReceiptLine line : saved.lines()) {
            InventoryItemPolicy item = items.computeIfAbsent(line.inventoryItemId(), this::policy);
            String locationCode = where.get(line.locationId()).code();
            inventory.receive(new ReceiveStockCommand(line.id(), new Sku(item.sku()), line.lotNumber(),
                    line.expiryDate(), locationCode, line.receivedQuantity(), userId, saved.confirmedAt()));
            confirmedLines.add(new GoodsReceiptConfirmed.Line(line.id(), line.poLineId(), item.sku(), line.lotNumber(),
                    line.receivedQuantity(), line.qcRequired(), locationCode));
            // A SUBCONTRACT order's goods are held for their production order in this transaction (production's
            // BEFORE_COMMIT listener) and wait for its QC: nothing of them is for putaway.
            if (!line.qcRequired() && !order.subcontract()) {
                readyLines.add(new ReceiptStockReadyForPutaway.Line(line.id(), item.sku(), line.lotNumber(),
                        locationCode, line.receivedQuantity()));
            }
        }

        Map<UUID, Integer> confirmed = receipts.confirmedQuantities(order.id());
        Map<UUID, ReceivingPurchaseOrder.LineStatus> lineStatuses = new LinkedHashMap<>();
        for (UUID poLineId : perPoLine.keySet()) {
            ReceivingPurchaseOrder.Line poLine = order.line(poLineId).orElseThrow();
            lineStatuses.put(poLineId, ReceivingPurchaseOrder.lineStatusFor(poLine, confirmed.getOrDefault(poLineId, 0)));
        }
        ReceivingPurchaseOrder.Status orderStatus = order.statusAfter(lineStatuses);
        orders.recordProgress(order, lineStatuses, orderStatus, userId, saved.id(), saved.number());

        events.publishEvent(new GoodsReceiptConfirmed(saved.id(), saved.number(), order.id(), saved.warehouseId(),
                confirmedLines, saved.confirmedAt()));
        if (!readyLines.isEmpty()) {
            events.publishEvent(new ReceiptStockReadyForPutaway(saved.id(), saved.number(), saved.warehouseId(),
                    readyLines));
        }
        log.info("Confirmed receipt {} against {}: {} line(s), order now {}", saved.number(), order.number(),
                saved.lines().size(), orderStatus);
        return view(saved, order);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "goods-receipt", resourceId = "#receiptId")
    public ReceiptView cancel(UUID receiptId) {
        GoodsReceipt receipt = load(receiptId);
        receipt.cancel();
        return view(receipts.save(receipt), orderOf(receipt));
    }

    // ------------------------------------------------------------------ QC

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "goods-receipt", resourceId = "#receiptId")
    public ReceiptView moveToQc(UUID receiptId, UUID lineId, String qcLocationCode, UUID userId) {
        GoodsReceipt receipt = load(receiptId);
        ReceivingLocation qcArea = area(qcLocationCode, QUALITY_CONTROL, receipt.warehouseId());
        ReceiptLine line = receipt.moveToQc(lineId, qcArea.id(), userId, clock.instant());
        GoodsReceipt saved = receipts.save(receipt);

        String from = locations.byIds(List.of(line.locationId())).get(line.locationId()).code();
        InventoryItemPolicy item = policy(line.inventoryItemId());
        inventory.move(new MoveStockCommand(line.id(), new Sku(item.sku()), line.lotNumber(), from, qcArea.code(),
                line.receivedQuantity(), MoveReference.GOODS_RECEIPT_LINE, line.id(), userId, saved.confirmedAt()));
        return view(saved, orderOf(saved));
    }

    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "goods-receipt", resourceId = "#receiptId")
    public ReceiptView inspect(UUID receiptId, UUID lineId, Decision decision, UUID userId) {
        GoodsReceipt receipt = load(receiptId);
        Instant now = clock.instant();
        ReceivingLocation quarantineTo = quarantineArea(decision.quarantined(), "quarantined", receipt.warehouseId());
        ReceivingLocation rejectTo = quarantineArea(decision.rejected(), "rejected", receipt.warehouseId());
        if (quarantineTo != null && rejectTo != null && quarantineTo.id().equals(rejectTo.id())) {
            // One stock row per SKU, location and lot: the same lot cannot be QUARANTINE and BLOCKED
            // in one place.
            throw new BusinessException(ErrorCode.QC_LOCATIONS_MUST_DIFFER,
                    "Quarantined and rejected goods go to two different quarantine locations",
                    List.of(new FieldError("rejected.locationCode",
                            "Quarantined and rejected goods go to two different quarantine locations",
                            ErrorCode.QC_LOCATIONS_MUST_DIFFER.name())));
        }

        List<QcInspection> decided = new ArrayList<>();
        if (decision.accepted() > 0) {
            decided.add(new QcInspection(Identifiers.newId(), QcOutcome.ACCEPTED, decision.accepted(), null, null,
                    userId, now));
        }
        QcInspection quarantined = part(decision.quarantined(), QcOutcome.QUARANTINE, quarantineTo, userId, now);
        QcInspection rejected = part(decision.rejected(), QcOutcome.REJECTED, rejectTo, userId, now);
        Optional.ofNullable(quarantined).ifPresent(decided::add);
        Optional.ofNullable(rejected).ifPresent(decided::add);

        receipt.inspect(lineId, decided, now);
        GoodsReceipt saved = receipts.save(receipt);

        ReceiptLine line = saved.line(lineId);
        String qcCode = locations.byIds(List.of(line.qcLocationId())).get(line.qcLocationId()).code();
        Sku sku = new Sku(policy(line.inventoryItemId()).sku());
        if (quarantined != null) {
            inventory.reclassify(new ReclassifyStockCommand(sku, line.lotNumber(), qcCode, quarantineTo.code(),
                    quarantined.quantity(), StockDisposition.QUARANTINE, MoveReference.QC_INSPECTION, quarantined.id(),
                    userId, quarantined.reason(), saved.confirmedAt()));
        }
        if (rejected != null) {
            inventory.reclassify(new ReclassifyStockCommand(sku, line.lotNumber(), qcCode, rejectTo.code(),
                    rejected.quantity(), StockDisposition.BLOCKED, MoveReference.QC_INSPECTION, rejected.id(),
                    userId, rejected.reason(), saved.confirmedAt()));
        }
        if (decision.accepted() > 0) {
            events.publishEvent(new ReceiptStockReadyForPutaway(saved.id(), saved.number(), saved.warehouseId(),
                    List.of(new ReceiptStockReadyForPutaway.Line(line.id(), sku.code(), line.lotNumber(), qcCode,
                            decision.accepted()))));
        }
        log.info("QC on receipt {} line {}: accepted {}, quarantined {}, rejected {}; receipt now {}", saved.number(),
                lineId, decision.accepted(), quarantined == null ? 0 : quarantined.quantity(),
                rejected == null ? 0 : rejected.quantity(), saved.status());
        return view(saved, orderOf(saved));
    }

    // ------------------------------------------------------------------ reads

    @Override
    @Transactional(readOnly = true)
    public ReceiptView get(UUID receiptId) {
        GoodsReceipt receipt = load(receiptId);
        return view(receipt, orderOf(receipt));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<Row> list(List<GoodsReceiptStatus> statuses, UUID purchaseOrderId, UUID warehouseId,
                                  String text, Instant receivedFrom, Instant receivedTo, int page, int size,
                                  String sort) {
        var criteria = new GoodsReceiptSearch.Criteria(statuses, purchaseOrderId, warehouseId, blankToNull(text),
                receivedFrom, receivedTo);
        var rows = search.search(criteria, Pages.of(page, size, SORT.parse(sort)));
        // SCRUM-464: one query each for the orders, the people and the warehouses of the page.
        var labels = search.orderLabels(rows.stream().map(GoodsReceiptSearch.Row::purchaseOrderId).toList());
        var names = identity.displayNames(rows.stream()
                .flatMap(r -> java.util.stream.Stream.of(r.receivedBy(), r.confirmedBy())).toList());
        Map<UUID, Optional<WarehouseView>> where = new HashMap<>();
        return Pages.toResponse(rows.map(r -> {
            var label = labels.get(r.purchaseOrderId());
            var warehouse = where.computeIfAbsent(r.warehouseId(), warehouses::findWarehouse);
            return new Row(r.id(), r.number(), r.purchaseOrderId(), label == null ? null : label.purchaseOrderNumber(),
                    label == null ? null : label.supplierId(), label == null ? null : label.supplierName(),
                    r.warehouseId(), warehouse.map(WarehouseView::prefix).orElse(null),
                    warehouse.map(WarehouseView::name).orElse(null), r.status(), r.deliveryNote(), r.receivedAt(),
                    r.receivedBy(), names.get(r.receivedBy()), r.confirmedAt(), r.confirmedBy(),
                    names.get(r.confirmedBy()), r.closedAt());
        }));
    }

    // ------------------------------------------------------------------ rules the aggregate cannot see

    private static void requireReceivable(ReceivingPurchaseOrder order) {
        if (!order.receivable()) {
            throw new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_RECEIVABLE,
                    "%s is %s; goods are received only against a CONFIRMED or PARTIALLY_RECEIVED order (BR-01)"
                            .formatted(order.number(), order.status()));
        }
    }

    private record OverTolerance(UUID poLineId, String message) {
    }

    /** BR-02, counted the way {@code check_goods_receipt_line} counts it: every receipt not cancelled. */
    private List<OverTolerance> overTolerance(ReceivingPurchaseOrder order, UUID receiptId,
                                              Map<UUID, Integer> perPoLine) {
        if (perPoLine.isEmpty()) {
            return List.of();
        }
        Map<UUID, Integer> elsewhere = receipts.receivedOnOtherReceipts(perPoLine.keySet(), receiptId);
        List<OverTolerance> over = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : perPoLine.entrySet()) {
            ReceivingPurchaseOrder.Line line = order.line(entry.getKey()).orElseThrow();
            int limit = line.receivableLimit(order.tolerancePercent());
            int total = elsewhere.getOrDefault(entry.getKey(), 0) + entry.getValue();
            if (total > limit) {
                over.add(new OverTolerance(line.id(),
                        "Line %d of %s would be received %d of %s ordered; at most %d with the supplier's %s%% tolerance (BR-02)"
                                .formatted(line.lineNo(), order.number(), total,
                                        line.orderedQuantity().stripTrailingZeros().toPlainString(), limit,
                                        order.tolerancePercent().stripTrailingZeros().toPlainString())));
            }
        }
        return over;
    }

    /**
     * BR-03 and BR-06, each refusal on its own field with its own code ({@code LOT_REQUIRED},
     * {@code EXPIRY_NOT_AFTER_RECEIPT}…) under RECEIPT_LOT_DATA_INVALID.
     */
    private static void checkLotData(LineErrors errors, int index, InventoryItemPolicy item, String lot,
                                     LocalDate expiry, LocalDate receivedOn) {
        ErrorCode code = ErrorCode.RECEIPT_LOT_DATA_INVALID;
        if (item.lotTracked() && lot == null) {
            errors.add(index, "lotNumber", code, "LOT_REQUIRED",
                    "%s is lot-tracked: each line needs a lot number (BR-03)".formatted(item.sku()));
        }
        if (!item.lotTracked() && lot != null) {
            errors.add(index, "lotNumber", code, "LOT_NOT_ALLOWED",
                    "%s is not lot-tracked; leave the lot number empty".formatted(item.sku()));
        }
        if (item.expiryTracked() && expiry == null) {
            errors.add(index, "expiryDate", code, "EXPIRY_REQUIRED",
                    "%s is expiry-tracked: each line needs an expiry date (BR-03)".formatted(item.sku()));
        }
        if (!item.expiryTracked() && expiry != null) {
            errors.add(index, "expiryDate", code, "EXPIRY_NOT_ALLOWED",
                    "%s is not expiry-tracked; leave the expiry date empty".formatted(item.sku()));
        }
        if (expiry != null && !expiry.isAfter(receivedOn)) {
            errors.add(index, "expiryDate", code, "EXPIRY_NOT_AFTER_RECEIPT",
                    "Expiry date %s must be after the receiving date %s (BR-06)".formatted(expiry, receivedOn));
        }
    }

    private ReceivingLocation area(String code, String type, UUID warehouseId) {
        String trimmed = code == null ? "" : code.trim().toUpperCase();
        ReceivingLocation location = locations.byCode(trimmed).orElseThrow(() ->
                new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + trimmed));
        if (!location.isArea(type, warehouseId)) {
            throw new BusinessException(ErrorCode.LOCATION_AREA_MISMATCH,
                    "%s is not a %s area of this receipt's warehouse".formatted(trimmed, type));
        }
        return location;
    }

    /**
     * Where a quarantined or rejected part goes. A part of no units needs no area (it is not
     * recorded), so an empty or stale location code on it is ignored. A part with units needs a
     * reason and a QUARANTINE area of this warehouse, each refused on its own field.
     */
    private ReceivingLocation quarantineArea(Part part, String field, UUID warehouseId) {
        if (part == null || part.quantity() <= 0) {
            return null;
        }
        if (part.reason() == null || part.reason().isBlank()) {
            throw new BusinessException(ErrorCode.QC_REASON_REQUIRED, "A %s part needs a reason (BR-08)".formatted(field),
                    List.of(new FieldError(field + ".reason", "A reason is required", ErrorCode.QC_REASON_REQUIRED.name())));
        }
        if (part.locationCode() == null || part.locationCode().isBlank()) {
            throw new BusinessException(ErrorCode.QC_TARGET_LOCATION_REQUIRED,
                    "A %s part needs the quarantine area it goes to".formatted(field),
                    List.of(new FieldError(field + ".locationCode", "A quarantine area is required",
                            ErrorCode.QC_TARGET_LOCATION_REQUIRED.name())));
        }
        try {
            return area(part.locationCode(), QUARANTINE, warehouseId);
        } catch (BusinessException refused) {
            throw new BusinessException(refused.errorCode(), refused.getMessage(),
                    List.of(new FieldError(field + ".locationCode", refused.getMessage(), refused.errorCode().name())));
        }
    }

    private static QcInspection part(Part part, QcOutcome outcome, ReceivingLocation to, UUID userId, Instant now) {
        if (part == null || part.quantity() <= 0) {
            return null;
        }
        return new QcInspection(Identifiers.newId(), outcome, part.quantity(), to.id(), blankToNull(part.reason()),
                userId, now);
    }

    private InventoryItemPolicy policy(UUID inventoryItemId) {
        return inventory.itemPolicy(inventoryItemId).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND, "No inventory item " + inventoryItemId));
    }

    /** The receipt, if it is in a warehouse the caller is assigned to (BR-SEC-002). */
    private GoodsReceipt load(UUID receiptId) {
        GoodsReceipt receipt = receipts.findById(receiptId).orElseThrow(() ->
                new BusinessException(ErrorCode.GOODS_RECEIPT_NOT_FOUND, "No goods receipt " + receiptId));
        com.stockflow.common.security.WarehouseScope.requireWarehouse(receipt.warehouseId());
        return receipt;
    }

    private ReceivingPurchaseOrder orderOf(GoodsReceipt receipt) {
        return orders.find(receipt.purchaseOrderId()).orElseThrow(() ->
                new IllegalStateException("Receipt %s points at a missing order".formatted(receipt.number())));
    }

    // ------------------------------------------------------------------ view

    private ReceiptView view(GoodsReceipt receipt, ReceivingPurchaseOrder order) {
        Set<UUID> locationIds = new HashSet<>();
        for (ReceiptLine line : receipt.lines()) {
            locationIds.add(line.locationId());
            Optional.ofNullable(line.qcLocationId()).ifPresent(locationIds::add);
            line.inspections().forEach(i -> Optional.ofNullable(i.targetLocationId()).ifPresent(locationIds::add));
        }
        Map<UUID, ReceivingLocation> where = locations.byIds(locationIds);
        Map<UUID, InventoryItemPolicy> items = inventory.itemPolicies(
                receipt.lines().stream().map(ReceiptLine::inventoryItemId).toList());
        Set<UUID> people = new HashSet<>();
        Optional.ofNullable(receipt.receivedBy()).ifPresent(people::add);
        Optional.ofNullable(receipt.confirmedBy()).ifPresent(people::add);
        receipt.lines().forEach(l -> l.inspections().forEach(i -> Optional.ofNullable(i.inspectedBy()).ifPresent(people::add)));
        Map<UUID, String> names = identity.displayNames(people);
        // BR-06: a lot that expires soon after it arrives is flagged, never refused. Counted from the day it was
        // received, in the business's calendar; a cancelled receipt counted nothing, so it flags nothing.
        boolean flags = receipt.status() != GoodsReceiptStatus.CANCELLED;
        LocalDate receivedOn = LocalDate.ofInstant(receipt.receivedAt(), BUSINESS_ZONE);
        int window = flags ? inventoryControl.expiryWarningDays() : 0;
        List<LineView> lines = receipt.lines().stream().map(line -> {
            InventoryItemPolicy item = items.get(line.inventoryItemId());
            List<String> lineWarnings = flags && ExpiryWarning.isNear(line.expiryDate(), receivedOn, window)
                    ? List.of(ExpiryWarning.EXPIRY_NEAR) : List.of();
            return new LineView(line.id(), line.poLineId(),
                    order.line(line.poLineId()).map(ReceivingPurchaseOrder.Line::lineNo).orElse(null),
                    line.inventoryItemId(), item == null ? null : item.sku(), line.receivedQuantity(),
                    line.lotNumber(), line.expiryDate(), code(where, line.locationId()), line.note(),
                    line.qcRequired(), item != null && item.lotTracked(), item != null && item.expiryTracked(),
                    progress(line, order.subcontract()), code(where, line.qcLocationId()), line.movedToQcAt(),
                    order.subcontract() || line.qcRequired() && !line.inspected() ? 0 : line.quantityForPutaway(),
                    line.inspections().stream().map(i -> new InspectionView(i.id(), i.outcome(), i.quantity(),
                            code(where, i.targetLocationId()), i.reason(), i.inspectedBy(), names.get(i.inspectedBy()),
                            i.inspectedAt())).toList(),
                    lineWarnings);
        }).toList();
        List<String> warnings = lines.stream().anyMatch(l -> !l.warnings().isEmpty())
                ? List.of(ExpiryWarning.EXPIRY_NEAR) : List.of();
        var label = search.orderLabels(List.of(receipt.purchaseOrderId())).get(receipt.purchaseOrderId());
        var warehouse = warehouses.findWarehouse(receipt.warehouseId());
        return new ReceiptView(receipt.id(), receipt.number(), receipt.purchaseOrderId(), order.number(),
                order.supplierId(), label == null ? null : label.supplierName(), receipt.warehouseId(),
                warehouse.map(WarehouseView::prefix).orElse(null), warehouse.map(WarehouseView::name).orElse(null),
                receipt.status(), receipt.deliveryNote(), receipt.note(), receipt.receivedAt(), receipt.receivedBy(),
                names.get(receipt.receivedBy()), receipt.confirmedAt(), receipt.confirmedBy(),
                names.get(receipt.confirmedBy()), receipt.closedAt(), lines, warnings);
    }

    private static QcProgress progress(ReceiptLine line, boolean subcontract) {
        if (subcontract && !line.qcRequired()) {
            return QcProgress.PRODUCTION_QC;
        }
        if (!line.qcRequired()) {
            return QcProgress.NOT_REQUIRED;
        }
        if (line.inspected()) {
            return QcProgress.INSPECTED;
        }
        return line.movedToQc() ? QcProgress.IN_QC_AREA : QcProgress.AWAITING_MOVE_TO_QC;
    }

    private static String code(Map<UUID, ReceivingLocation> where, UUID id) {
        return id == null || !where.containsKey(id) ? null : where.get(id).code();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
