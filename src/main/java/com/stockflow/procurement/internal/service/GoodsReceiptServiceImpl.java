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
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.contracts.GoodsReceiptConfirmed;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.contracts.ReceiptStockReadyForPutaway;
import com.stockflow.inventory.api.CountReceivingPackagesCommand;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockCommand;
import com.stockflow.inventory.api.MoveStockPackageCommand;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.ReceiptOrigin;
import com.stockflow.inventory.api.ReclassifyStockCommand;
import com.stockflow.inventory.api.ReclassifyStockPackageCommand;
import com.stockflow.inventory.api.StockDisposition;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionService;
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
import java.util.Comparator;
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
    /** {@code inventory.stock_package.status} of a carton holding stock. */
    private static final String IN_STOCK = "IN_STOCK";

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
    private final UomConversionService units;
    private final CurrentUserProvider users;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    GoodsReceiptServiceImpl(GoodsReceiptRepository receipts, GoodsReceiptSearch search, ReceivingPurchaseOrders orders,
                            ReceivingLocations locations, InventoryService inventory, WarehouseService warehouses,
                            IdentityService identity, InventoryControlService inventoryControl,
                            UomConversionService units, CurrentUserProvider users,
                            ApplicationEventPublisher events, Clock clock) {
        this.receipts = receipts;
        this.search = search;
        this.orders = orders;
        this.locations = locations;
        this.inventory = inventory;
        this.warehouses = warehouses;
        this.identity = identity;
        this.inventoryControl = inventoryControl;
        this.units = units;
        this.users = users;
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
     *
     * <p>A line counted again for the same PO line and lot keeps its id: the draft's line is recounted,
     * not replaced. Stock packages labelled during receiving point at their receipt line
     * ({@code inventory.stock_package.goods_receipt_line_id}, V20261020000100), with a restricting
     * foreign key and a value that never changes once set, so a fresh id on every recount would make a
     * draft with labelled cartons impossible to correct.</p>
     *
     * <p>SCRUM-473 (design §3): a line's {@code packages} are its labelled cartons, the whole list each time. Their
     * rules are checked with the others, each on its own field - {@code lines[i].unitOfMeasure} (the unit missing,
     * not the item's, the base unit or a retired one with cartons, a packing unit without, or a new unit while a
     * carton of the old one is kept) and {@code lines[i].packages} (a subcontract receipt, cartons above the line).
     * Only once the count is saved - so a new line's row exists for the cartons' foreign key - does inventory
     * label, recount, move and void them, for every line that has cartons now or had some before; the response
     * then carries their LPNs. The lines are taken SKU by SKU in code order, so two counts never take two SKUs'
     * locks the other way round.</p>
     */
    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "goods-receipt", resourceId = "#receiptId")
    public ReceiptView replaceLines(UUID receiptId, List<NewLine> newLines) {
        GoodsReceipt receipt = load(receiptId);
        ReceivingPurchaseOrder order = orderOf(receipt);
        LocalDate receivedOn = LocalDate.ofInstant(receipt.receivedAt(), BUSINESS_ZONE);
        Map<String, ReceiptLine> current = new HashMap<>();
        for (ReceiptLine line : receipt.lines()) {
            current.put(lineKey(line.poLineId(), line.lotNumber()), line);
        }
        // The cartons labelled so far, read once: rule 7, and which lines inventory must count again.
        Map<UUID, List<StockPackageView>> labelled = receipt.lines().isEmpty() ? Map.of()
                : inventory.receivingPackagesOf(receipt.lines().stream().map(ReceiptLine::id).toList());

        LineErrors errors = new LineErrors();
        List<ReceiptLine> counted = new ArrayList<>();
        List<CartonCount> cartonCounts = new ArrayList<>();
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
            if (!seen.add(lineKey(poLine.id(), lot))) {
                errors.add(i, "lotNumber", ErrorCode.DUPLICATE_RECEIPT_LINE,
                        "Line %d of %s with this lot is already counted above; put the whole quantity of a lot on one line"
                                .formatted(poLine.lineNo(), order.number()));
            }
            perPoLine.merge(poLine.id(), in.quantity(), Integer::sum);
            indexesOf.computeIfAbsent(poLine.id(), id -> new ArrayList<>()).add(i);
            ReceiptLine was = current.get(lineKey(poLine.id(), lot));
            UUID lineId = was == null ? Identifiers.newId() : was.id();
            List<StockPackageView> cartonsBefore = labelled.getOrDefault(lineId, List.of());
            UomConversion unit = cartonUnit(errors, i, in, item, order.subcontract(),
                    was == null ? null : was.uomConversionId(), cartonsBefore);
            if (errors.size() == before) {
                // A SUBCONTRACT order's goods get one inspection, the production QC against the approved sample
                // (user decision 2026-10-13): receiving counts them and does not inspect the same goods again.
                counted.add(ReceiptLine.counted(lineId, poLine.id(), poLine.inventoryItemId(),
                        location.id(), in.quantity(), lot, in.expiryDate(), blankToNull(in.note()),
                        item.qcRequired() && !order.subcontract(), unit == null ? null : unit.id()));
                if (!in.packages().isEmpty() || !cartonsBefore.isEmpty()) {
                    cartonCounts.add(new CartonCount(lineId, item.sku(), unit == null ? null : unit.id(),
                            location.code(), in.packages()));
                }
            }
        }
        for (var over : overTolerance(order, receipt.id(), perPoLine)) {
            for (int i : indexesOf.get(over.poLineId())) {
                errors.add(i, "quantity", ErrorCode.OVER_RECEIPT_TOLERANCE, over.message());
            }
        }
        errors.throwIfAny();
        requireNoCartonsOnDroppedLines(receipt, counted);
        receipt.replaceLines(counted);
        GoodsReceipt saved = receipts.save(receipt);

        UUID actor = users.current().map(CurrentUser::userId).orElse(null);
        cartonCounts.sort(Comparator.comparing(CartonCount::sku).thenComparing(CartonCount::lineId));
        for (CartonCount count : cartonCounts) {
            inventory.countReceivingPackages(new CountReceivingPackagesCommand(count.lineId(), new Sku(count.sku()),
                    count.unitId(), count.locationCode(), count.packages().stream()
                    .map(p -> new CountReceivingPackagesCommand.PackageCount(blankToNull(p.lpn()), p.quantity()))
                    .toList(), actor));
        }
        return view(saved, order);
    }

    /** A line's cartons as counted, for inventory once the count is saved. {@code unitId} null voids them all. */
    private record CartonCount(UUID lineId, String sku, UUID unitId, String locationCode, List<NewPackage> packages) {
    }

    /**
     * Design §3 rules 2–5, 7 and 8 for one line, each refusal on its own field: the packing unit the line's cartons
     * are in, or null for a loose line.
     *
     * <p>A retired unit is refused for cartons unless it is already the line's unit: a draft counted before the
     * unit was retired is still recounted in it, as {@code tg_goods_receipt_lines_unit} and
     * {@code tg_stock_package_created} allow. Refused here, the retired unit is an answer naming the field; let
     * through, it would be a check violation from the database.</p>
     */
    private UomConversion cartonUnit(LineErrors errors, int index, NewLine in, InventoryItemPolicy item,
                                     boolean subcontract, UUID lineUnitId, List<StockPackageView> cartonsBefore) {
        String code = blankToNull(in.unitOfMeasure());
        Sku sku = new Sku(item.sku());
        if (in.packages().isEmpty()) {
            if (code != null) {
                Optional<UomConversion> unit = units.conversion(sku, code);
                if (unit.isEmpty()) {
                    errors.add(index, "unitOfMeasure", ErrorCode.UOM_CONVERSION_NOT_FOUND,
                            "%s has no unit %s".formatted(item.sku(), code));
                } else if (!unit.get().base()) {
                    errors.add(index, "unitOfMeasure", ErrorCode.RECEIPT_LINE_UNIT_INVALID,
                            "%s is a packing unit: list the line's cartons in it, or count the line loose in the base unit"
                                    .formatted(code));
                }
            }
            return null;
        }
        if (subcontract) {
            // Decision D5: production holds a subcontract receipt's goods one row per line, not per carton.
            errors.add(index, "packages", ErrorCode.RECEIPT_PACKAGES_NOT_SUPPORTED,
                    "Cartons are not labelled on a subcontract receipt; count its goods loose");
            return null;
        }
        int total = 0;
        Set<String> named = new HashSet<>();
        for (int j = 0; j < in.packages().size(); j++) {
            NewPackage carton = in.packages().get(j);
            if (carton.quantity() < 1) {
                errors.add(index, "packages[%d].quantity".formatted(j), ErrorCode.VALIDATION_FAILED,
                        "A carton holds at least 1 unit");
            }
            String lpn = StockPackageView.normalLpn(carton.lpn());
            if (lpn != null && !named.add(lpn)) {
                errors.add(index, "packages[%d].lpn".formatted(j), ErrorCode.VALIDATION_FAILED,
                        "%s is listed twice".formatted(lpn));
            }
            total += Math.max(carton.quantity(), 0);
        }
        if (total > in.quantity()) {
            errors.add(index, "packages", ErrorCode.RECEIPT_PACKAGES_EXCEED_LINE,
                    "The cartons hold %d units, more than the line's %d".formatted(total, in.quantity()));
        }
        if (code == null) {
            errors.add(index, "unitOfMeasure", ErrorCode.VALIDATION_FAILED, "Cartons need the packing unit they are in");
            return null;
        }
        Optional<UomConversion> found = units.conversion(sku, code);
        if (found.isEmpty()) {
            errors.add(index, "unitOfMeasure", ErrorCode.UOM_CONVERSION_NOT_FOUND,
                    "%s has no unit %s".formatted(item.sku(), code));
            return null;
        }
        UomConversion unit = found.get();
        if (unit.base()) {
            errors.add(index, "unitOfMeasure", ErrorCode.RECEIPT_LINE_UNIT_INVALID,
                    "Cartons are labelled in a packing unit, not in the base unit %s".formatted(code));
            return null;
        }
        if (!unit.active() && !unit.id().equals(lineUnitId)) {
            errors.add(index, "unitOfMeasure", ErrorCode.RECEIPT_LINE_UNIT_INVALID,
                    "%s is retired; cartons are labelled in an active packing unit".formatted(code));
            return null;
        }
        // Rule 7: every carton of a line is in the line's unit, so the unit changes only with all the old ones voided.
        List<String> keptInOtherUnit = cartonsBefore.stream()
                .filter(carton -> named.contains(carton.lpn()) && !unit.id().equals(carton.uomConversionId()))
                .map(StockPackageView::lpn).toList();
        if (!keptInOtherUnit.isEmpty()) {
            errors.add(index, "unitOfMeasure", ErrorCode.RECEIPT_LINE_HAS_PACKAGES,
                    "Cartons %s are in another unit; leave them out to void them before the line changes unit"
                            .formatted(String.join(", ", keptInOtherUnit)));
        }
        return unit;
    }

    /**
     * A draft line that cartons were ever labelled on - VOID ones included - is not dropped from the count: the
     * cartons point at it with a restricting foreign key (V20261020000100), which would otherwise refuse the save as a
     * bare 409 naming no line. Since a line is keyed by its PO line and lot, this is also what refuses correcting the
     * lot or PO line of such a line: the corrected line is a new one and the old one would be dropped. The way to
     * correct it is to cancel the receipt and count it again.
     */
    private void requireNoCartonsOnDroppedLines(GoodsReceipt receipt, List<ReceiptLine> counted) {
        Set<UUID> kept = new HashSet<>();
        counted.forEach(line -> kept.add(line.id()));
        List<UUID> dropped = receipt.lines().stream().map(ReceiptLine::id).filter(id -> !kept.contains(id)).toList();
        if (dropped.isEmpty()) {
            return;
        }
        Set<UUID> labelled = inventory.receiptLinesWithStockPackages(dropped);
        if (!labelled.isEmpty()) {
            throw new BusinessException(ErrorCode.RECEIPT_LINE_HAS_PACKAGES,
                    ("Receipt %s: %d line(s) left out of the count have had labelled cartons; such a line keeps its"
                            + " PO line and lot and stays on the draft - to correct its lot or PO line, cancel the"
                            + " receipt and count it again").formatted(receipt.number(), labelled.size()));
        }
    }

    /** What identifies a line within one receipt: its PO line and lot ({@code uk_goods_receipt_lines_lot}). */
    private static String lineKey(UUID poLineId, String lot) {
        return poLineId + "|" + Objects.toString(lot, "");
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

    /**
     * {@inheritDoc}
     *
     * <p>SCRUM-473: nothing here names a carton. {@code inventory.receive} finds the line's RECEIVING cartons by the
     * line's id and puts the goods into them - one INBOUND row each, IN_STOCK and still unopened - and only the
     * remainder goes loose; it refuses cartons holding more than the line ({@code RECEIPT_PACKAGES_EXCEED_LINE}),
     * the invariant {@link #replaceLines} states first.</p>
     */
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
        // SCRUM-142: the lots this receipt opens remember it, its order and its supplier as they read now.
        ReceiptOrigin origin = new ReceiptOrigin(saved.id(), saved.number(), order.id(), order.number(),
                order.supplierId(), orders.supplierName(order.supplierId()).orElse(null), saved.warehouseId());
        saved.lines().forEach(line -> items.computeIfAbsent(line.inventoryItemId(), this::policy));
        // Each receive takes its SKU's advisory lock: in replaceLines' order - SKU, then line id - so a confirm and a
        // count of two receipts sharing SKUs cannot take them in opposite orders and deadlock.
        for (ReceiptLine line : saved.lines().stream().sorted(Comparator
                .comparing((ReceiptLine l) -> items.get(l.inventoryItemId()).sku())
                .thenComparing(ReceiptLine::id)).toList()) {
            InventoryItemPolicy item = items.get(line.inventoryItemId());
            inventory.receive(new ReceiveStockCommand(line.id(), new Sku(item.sku()), line.lotNumber(),
                    line.expiryDate(), where.get(line.locationId()).code(), line.receivedQuantity(), userId,
                    saved.confirmedAt(), origin));
        }
        List<GoodsReceiptConfirmed.Line> confirmedLines = new ArrayList<>();
        List<ReceiptStockReadyForPutaway.Line> readyLines = new ArrayList<>();
        for (ReceiptLine line : saved.lines()) {
            InventoryItemPolicy item = items.get(line.inventoryItemId());
            String locationCode = where.get(line.locationId()).code();
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
        // Cartons labelled while counting held no stock and their labels are no longer valid (RECEIVING → VOID).
        inventory.voidStockPackagesOfReceiptLines(receipt.lines().stream().map(ReceiptLine::id).toList());
        return view(receipts.save(receipt), orderOf(receipt));
    }

    // ------------------------------------------------------------------ QC

    /**
     * {@inheritDoc}
     *
     * <p>SCRUM-473 (design §5): every IN_STOCK carton of the line moves whole, still unopened, and the loose remainder
     * - the line less what was counted into its cartons - moves as one loose move, none when it is 0. All of them
     * name the line as their reference.</p>
     *
     * <p>The loose move goes first. It treats a MOVE ledger row with its reference as its own earlier attempt and
     * skips (the replay lookup in {@code inventory.move}); written after the cartons, it would find theirs - same
     * {@code GOODS_RECEIPT_LINE} and line id - and leave the loose units at the receiving area without a word.</p>
     */
    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "goods-receipt", resourceId = "#receiptId")
    public ReceiptView moveToQc(UUID receiptId, UUID lineId, String qcLocationCode, UUID userId) {
        GoodsReceipt receipt = load(receiptId);
        ReceivingLocation qcArea = area(qcLocationCode, QUALITY_CONTROL, receipt.warehouseId());
        ReceiptLine line = receipt.moveToQc(lineId, qcArea.id(), userId, clock.instant());
        GoodsReceipt saved = receipts.save(receipt);

        List<StockPackageView> cartons = inventory.receivingPackagesOf(List.of(line.id()))
                .getOrDefault(line.id(), List.of());
        int loose = line.receivedQuantity() - countedIntoCartons(cartons);
        if (loose > 0) {
            String from = locations.byIds(List.of(line.locationId())).get(line.locationId()).code();
            InventoryItemPolicy item = policy(line.inventoryItemId());
            // Before the cartons: their MOVE rows share this reference, and this move would take them for its replay.
            inventory.move(new MoveStockCommand(line.id(), new Sku(item.sku()), line.lotNumber(), from, qcArea.code(),
                    loose, MoveReference.GOODS_RECEIPT_LINE, line.id(), userId, saved.confirmedAt()));
        }
        for (StockPackageView carton : cartons) {
            if (IN_STOCK.equals(carton.status())) {
                inventory.moveStockPackage(new MoveStockPackageCommand(carton.lpn(), qcArea.code(),
                        MoveReference.GOODS_RECEIPT_LINE, line.id(), userId));
            }
        }
        return view(saved, orderOf(saved));
    }

    /**
     * {@inheritDoc}
     *
     * <p>SCRUM-473 (design §5): a quarantined or rejected part is its loose {@code quantity} plus the cartons it names
     * by LPN, each counted at what was counted into it ({@code receivedQty}). Every named carton is checked before
     * anything moves: it is a carton of this line ({@code STOCK_PACKAGE_NOT_FOUND} otherwise - another line's LPN is
     * not a way to move another receipt's goods) and named once across both parts ({@code VALIDATION_FAILED}); then,
     * once the aggregate has taken the decision - the line in QC and not yet decided, BR-08 on the parts' totals as
     * before ({@code QC_QUANTITY_MISMATCH}) - it is IN_STOCK ({@code INVALID_STOCK_PACKAGE_TRANSITION}) at the line's
     * QC area ({@code STOCK_PACKAGE_WRONG_LOCATION}). The loose parts together may not take more than the line's loose
     * remainder ({@code QC_QUANTITY_MISMATCH} too): the accepted part is what is left, the unnamed cartons and the
     * loose units no part took, and it stays INBOUND at the QC area. {@code QcInspection.quantity} records a part's total; which carton went where is in the ledger
     * ({@code QC_INSPECTION} and the inspection's id, with the carton on both sides).</p>
     *
     * <p>Each part's loose units are reclassified before its cartons, for the reason {@link #moveToQc} gives: the
     * loose reclassification skips when a MOVE row with its reference ({@code QC_INSPECTION} and the part's
     * inspection id) exists already, and the cartons' rows carry that reference too.</p>
     */
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

        List<StockPackageView> cartons = inventory.receivingPackagesOf(List.of(lineId)).getOrDefault(lineId, List.of());
        Map<String, NamedCarton> named = new LinkedHashMap<>();
        int quarantinedInCartons = namedCartons(decision.quarantined(), "quarantined", cartons, named);
        int rejectedInCartons = namedCartons(decision.rejected(), "rejected", cartons, named);

        List<QcInspection> decided = new ArrayList<>();
        if (decision.accepted() > 0) {
            decided.add(new QcInspection(Identifiers.newId(), QcOutcome.ACCEPTED, decision.accepted(), null, null,
                    userId, now));
        }
        QcInspection quarantined = part(decision.quarantined(), quarantinedInCartons, QcOutcome.QUARANTINE,
                quarantineTo, userId, now);
        QcInspection rejected = part(decision.rejected(), rejectedInCartons, QcOutcome.REJECTED, rejectTo, userId,
                now);
        Optional.ofNullable(quarantined).ifPresent(decided::add);
        Optional.ofNullable(rejected).ifPresent(decided::add);

        // What runs first is the named cartons' membership and duplicates, above: the parts' totals the aggregate
        // checks are reckoned from those cartons' counts, so they cannot be taken before the cartons are found. Then
        // the aggregate, so a line not moved to QC or already decided hears that before where its cartons are or what
        // state they are in; then each carton's status and place.
        receipt.inspect(lineId, decided, now);
        ReceiptLine line = receipt.line(lineId);
        String qcCode = locations.byIds(List.of(line.qcLocationId())).get(line.qcLocationId()).code();
        requireAtQcArea(named.values(), qcCode);
        int looseTaken = looseOf(decision.quarantined()) + looseOf(decision.rejected());
        int looseRemainder = line.receivedQuantity() - countedIntoCartons(cartons);
        if (looseTaken > looseRemainder) {
            throw new BusinessException(ErrorCode.QC_QUANTITY_MISMATCH,
                    "The quarantined and rejected parts take %d loose units; the line has %d outside its cartons (BR-08)"
                            .formatted(looseTaken, looseRemainder));
        }
        GoodsReceipt saved = receipts.save(receipt);

        Sku sku = new Sku(policy(line.inventoryItemId()).sku());
        if (quarantined != null) {
            decide(decision.quarantined(), quarantined, StockDisposition.QUARANTINE, quarantineTo, sku, line, qcCode,
                    saved.confirmedAt(), userId);
        }
        if (rejected != null) {
            decide(decision.rejected(), rejected, StockDisposition.BLOCKED, rejectTo, sku, line, qcCode,
                    saved.confirmedAt(), userId);
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
     * Where a quarantined or rejected part goes. A part of no goods - 0 loose units and no cartons -
     * needs no area (it is not recorded), so an empty or stale location code on it is ignored. A part
     * with loose units or cartons ({@link Part#hasGoods}) needs a reason and a QUARANTINE area of this
     * warehouse, each refused on its own field.
     */
    private ReceivingLocation quarantineArea(Part part, String field, UUID warehouseId) {
        if (part == null || !part.hasGoods()) {
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

    /** The inspection a part records, its loose units and its cartons together; null for a part of no goods. */
    private static QcInspection part(Part part, int inCartons, QcOutcome outcome, ReceivingLocation to, UUID userId,
                                     Instant now) {
        if (part == null || !part.hasGoods()) {
            return null;
        }
        return new QcInspection(Identifiers.newId(), outcome, looseOf(part) + inCartons, to.id(),
                blankToNull(part.reason()), userId, now);
    }

    /** A part's loose units; a part of none (or a negative count, which the request refuses) takes none. */
    private static int looseOf(Part part) {
        return part == null ? 0 : Math.max(part.quantity(), 0);
    }

    /** What was counted into a line's cartons: the line less this is its loose remainder (design §3 rule 5). */
    private static int countedIntoCartons(List<StockPackageView> cartons) {
        return cartons.stream().mapToInt(c -> c.receivedQty() == null ? 0 : c.receivedQty()).sum();
    }

    /** A carton a QC part names, with the request field that named it. */
    private record NamedCarton(String field, StockPackageView carton) {
    }

    /**
     * The cartons a part names, looked up among the line's own and added to {@code named}: an LPN that is not one of
     * this line's cartons is {@code STOCK_PACKAGE_NOT_FOUND}, one named twice - in this part or in both -
     * {@code VALIDATION_FAILED}. Returns what was counted into them.
     */
    private static int namedCartons(Part part, String field, List<StockPackageView> lineCartons,
                                    Map<String, NamedCarton> named) {
        if (part == null) {
            return 0;
        }
        int units = 0;
        for (int j = 0; j < part.packages().size(); j++) {
            String lpn = StockPackageView.normalLpn(part.packages().get(j));
            String at = "%s.packages[%d]".formatted(field, j);
            StockPackageView carton = lineCartons.stream().filter(c -> c.lpn().equals(lpn)).findFirst()
                    .orElseThrow(() -> refused(ErrorCode.STOCK_PACKAGE_NOT_FOUND, at,
                            "%s is not a carton of this receipt line".formatted(lpn)));
            if (named.putIfAbsent(lpn, new NamedCarton(at, carton)) != null) {
                throw refused(ErrorCode.VALIDATION_FAILED, at,
                        "%s is named twice; a carton goes to one place".formatted(lpn));
            }
            units += carton.receivedQty() == null ? 0 : carton.receivedQty();
        }
        return units;
    }

    /**
     * A named carton is decided where QC looked at it: IN_STOCK ({@code INVALID_STOCK_PACKAGE_TRANSITION}) at the
     * line's QC area ({@code STOCK_PACKAGE_WRONG_LOCATION}), not taken somewhere else by hand since.
     */
    private static void requireAtQcArea(java.util.Collection<NamedCarton> named, String qcCode) {
        for (NamedCarton each : named) {
            StockPackageView carton = each.carton();
            if (!IN_STOCK.equals(carton.status())) {
                throw refused(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION, each.field(),
                        "%s is %s; QC decides on a carton in stock".formatted(carton.lpn(), carton.status()));
            }
            if (!qcCode.equals(carton.locationCode())) {
                throw refused(ErrorCode.STOCK_PACKAGE_WRONG_LOCATION, each.field(),
                        "%s is at %s, not at the line's QC area %s".formatted(carton.lpn(), carton.locationCode(),
                                qcCode));
            }
        }
    }

    private static BusinessException refused(ErrorCode code, String field, String message) {
        return new BusinessException(code, message, List.of(new FieldError(field, message, code.name())));
    }

    /**
     * One quarantined or rejected part into stock: its loose units by quantity, then each named carton whole, all
     * under the part's inspection id.
     */
    private void decide(Part part, QcInspection inspection, StockDisposition disposition, ReceivingLocation to,
                        Sku sku, ReceiptLine line, String qcCode, Instant receivedAt, UUID userId) {
        if (looseOf(part) > 0) {
            // Before the cartons: their MOVE rows share this reference, and this would take them for its replay.
            inventory.reclassify(new ReclassifyStockCommand(sku, line.lotNumber(), qcCode, to.code(), looseOf(part),
                    disposition, MoveReference.QC_INSPECTION, inspection.id(), userId, inspection.reason(),
                    receivedAt));
        }
        for (String lpn : part.packages()) {
            inventory.reclassifyStockPackage(new ReclassifyStockPackageCommand(lpn, to.code(), disposition,
                    MoveReference.QC_INSPECTION, inspection.id(), userId, inspection.reason()));
        }
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
        // SCRUM-473: every line's cartons in one call, and every unit of the receipt's items in another - the line's
        // own unit is among them, and the counting screen offers the rest.
        Map<UUID, List<StockPackageView>> cartons = receipt.lines().isEmpty() ? Map.of()
                : inventory.receivingPackagesOf(receipt.lines().stream().map(ReceiptLine::id).toList());
        Map<String, List<UomConversion>> unitsBySku = items.isEmpty() ? Map.of()
                : units.conversionsOf(items.values().stream().map(i -> new Sku(i.sku())).toList());
        Map<UUID, String> unitCodes = new HashMap<>();
        unitsBySku.values().forEach(list -> list.forEach(u -> unitCodes.put(u.id(), u.unitOfMeasure())));
        // BR-06: a lot that expires soon after it arrives is flagged, never refused. Counted from the day it was
        // received, in the business's calendar; a cancelled receipt counted nothing, so it flags nothing.
        boolean flags = receipt.status() != GoodsReceiptStatus.CANCELLED;
        LocalDate receivedOn = LocalDate.ofInstant(receipt.receivedAt(), BUSINESS_ZONE);
        int window = flags ? inventoryControl.expiryWarningDays() : 0;
        List<LineView> lines = receipt.lines().stream().map(line -> {
            InventoryItemPolicy item = items.get(line.inventoryItemId());
            List<String> lineWarnings = flags && ExpiryWarning.isNear(line.expiryDate(), receivedOn, window)
                    ? List.of(ExpiryWarning.EXPIRY_NEAR) : List.of();
            List<PackageView> packages = cartons.getOrDefault(line.id(), List.of()).stream()
                    .map(c -> new PackageView(c.id(), c.lpn(), c.quantity() == null ? 0 : c.quantity(),
                            c.receivedQty() == null ? 0 : c.receivedQty(), c.nominalQty(), c.status(),
                            c.locationCode()))
                    .toList();
            // Design §3 rule 5: loose is what was not counted into a carton. Not what is in the cartons now - a
            // confirmed receipt is frozen (BR-05), and units picked from a carton later were never loose.
            int inCartons = packages.stream().mapToInt(PackageView::receivedQty).sum();
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
                    lineWarnings, line.uomConversionId() == null ? null : unitCodes.get(line.uomConversionId()),
                    line.receivedQuantity() - inCartons, packages,
                    item == null ? List.of() : unitsBySku.getOrDefault(item.sku(), List.of()).stream()
                            .map(u -> new UnitView(u.unitOfMeasure(), u.factor(), u.base(), u.active(),
                                    u.defaultPurchase()))
                            .toList());
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
