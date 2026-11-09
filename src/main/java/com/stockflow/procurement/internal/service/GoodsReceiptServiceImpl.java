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
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.ReceiptOrigin;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.order.api.ProductionOrderReceiving;
import com.stockflow.order.api.ReceivableProductionOrder;
import com.stockflow.procurement.internal.domain.ExpiryWarning;
import com.stockflow.procurement.internal.domain.GoodsReceipt;
import com.stockflow.procurement.internal.domain.GoodsReceiptRepository;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.domain.GoodsReceiptType;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 *
 * <p>SCRUM-477 (BR-RCP-001): a count beyond a PO line's over-receipt tolerance is not refused. Confirming it sends the
 * receipt to PENDING_APPROVAL, with nothing counted in; {@link #approve} posts the whole count, {@link #rejectExcess}
 * cuts it back to the tolerance and posts the rest, both for a user other than the receiver.</p>
 *
 * <p>4A-5 (kltn-docs 03 §4.1, BR-01..BR-15): receipts against a production order, through {@code order :: api}
 * ({@link ProductionOrderReceiving}). Whole cartons of each production order line's standard count, in the production
 * order's lot, never more than the line orders on all its receipts not cancelled (BR-02): nothing over is received, so
 * nothing waits for approval. Confirming counts the cartons in and moves the production order on in the same
 * transaction.</p>
 *
 * <p><b>Lock order of a production receipt</b> - starting, counting, confirming and cancelling alike: the production
 * order's sales order, then the production order ({@link ProductionOrderReceiving#lock}), then the goods receipt
 * ({@code FOR UPDATE}), then inventory's SKU advisory locks (counting or receiving the cartons, by SKU then line). The
 * order module takes the same first two for every write to a production order, so a receipt and a cancellation or an
 * approval of the same production order wait for each other instead of deadlocking.</p>
 */
@Service
@Transactional
class GoodsReceiptServiceImpl implements GoodsReceipts {

    private static final Logger log = LoggerFactory.getLogger(GoodsReceiptServiceImpl.class);

    /** Receipt numbers and the BR-06 expiry check follow the business's calendar, not UTC. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final String RECEIVING = "RECEIVING";

    private static final SortWhitelist SORT = SortWhitelist.of("receivedAt", "receiptNumber", "status")
            .withDefault("receivedAt", Sort.Direction.DESC);

    private final GoodsReceiptRepository receipts;
    private final GoodsReceiptSearch search;
    private final ReceivingPurchaseOrders orders;
    private final ProductionOrderReceiving receiving;
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
                            ProductionOrderReceiving receiving, ReceivingLocations locations, InventoryService inventory,
                            WarehouseService warehouses, IdentityService identity,
                            InventoryControlService inventoryControl, UomConversionService units,
                            CurrentUserProvider users, ApplicationEventPublisher events, Clock clock) {
        this.receipts = receipts;
        this.search = search;
        this.orders = orders;
        this.receiving = receiving;
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
     * <p>The supplier is required and must exist, the reason is required, and the warehouse is one the caller is
     * assigned to (BR-SEC-002).</p>
     */
    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "goods-receipt", resourceId = "#result?.id()")
    public ReceiptView createWithoutPurchaseOrder(CreateWithoutPo command, UUID userId) {
        if (command.reason() == null || command.reason().isBlank()) {
            throw new BusinessException(ErrorCode.BLIND_RECEIPT_REASON_REQUIRED,
                    "A receipt without a purchase order needs a reason (BR-RCP-002)",
                    List.of(new FieldError("reason", "A reason is required", ErrorCode.BLIND_RECEIPT_REASON_REQUIRED.name())));
        }
        if (orders.supplierName(command.supplierId()).isEmpty()) {
            throw new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND, "No supplier " + command.supplierId());
        }
        warehouses.findWarehouse(command.warehouseId()).orElseThrow(() ->
                new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND, "No warehouse " + command.warehouseId()));
        com.stockflow.common.security.WarehouseScope.requireWarehouse(command.warehouseId());
        Instant now = clock.instant();
        GoodsReceipt receipt = GoodsReceipt.blindDraft(Identifiers.newId(),
                receipts.nextNumber(LocalDate.ofInstant(now, BUSINESS_ZONE)), command.supplierId(),
                command.warehouseId(), userId, command.reason(), blankToNull(command.deliveryNote()),
                blankToNull(command.note()), now);
        return view(receipts.save(receipt), null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The production order is locked first ({@link ProductionOrderReceiving#lock}: its sales order, then it), so the
     * answer "it can receive goods" still holds when the draft is registered with it: a cancellation or a closing waits
     * on the same lock and then finds the draft. Its warehouse is the receipt's (BR-01), and the caller is one of its
     * staff (BR-SEC-002).</p>
     */
    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "goods-receipt", resourceId = "#result?.id()")
    public ReceiptView createForProductionOrder(CreateForProductionOrder command, UUID userId) {
        if (command.productionOrderId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The production order is required",
                    List.of(new FieldError("productionOrderId", "The production order is required",
                            ErrorCode.VALIDATION_FAILED.name())));
        }
        ReceivableProductionOrder production = receiving.lock(command.productionOrderId()).orElseThrow(() ->
                new BusinessException(ErrorCode.PRODUCTION_ORDER_NOT_FOUND,
                        "No production order " + command.productionOrderId()));
        requireReceivable(production);
        com.stockflow.common.security.WarehouseScope.requireWarehouse(production.receivingWarehouseId());
        Instant now = clock.instant();
        GoodsReceipt saved = receipts.save(GoodsReceipt.productionDraft(Identifiers.newId(),
                receipts.nextNumber(LocalDate.ofInstant(now, BUSINESS_ZONE)), production.id(),
                production.receivingWarehouseId(), userId, blankToNull(command.deliveryNote()),
                blankToNull(command.note()), now));
        receiving.openReceipt(production.id(), saved.id(), saved.number());
        log.info("Receipt {} started against production order {}", saved.number(), production.code());
        return view(saved, null, production);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A reference only (owner decision NOPO-ATTACH): the order is not locked, not read for its state and not
     * written; nothing is counted against it.</p>
     */
    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "goods-receipt", resourceId = "#receiptId")
    public ReceiptView referencePurchaseOrder(UUID receiptId, UUID purchaseOrderId) {
        GoodsReceipt receipt = load(receiptId);
        ReceivingPurchaseOrder order = orders.find(purchaseOrderId).orElseThrow(() ->
                new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND, "No purchase order " + purchaseOrderId));
        receipt.referencePurchaseOrder(purchaseOrderId, order.supplierId());
        return view(receipts.save(receipt), null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every line is checked before anything is refused (SCRUM-464): the form gets one answer
     * naming each field at fault — {@code lines[i].purchaseOrderLineId}, {@code .locationCode},
     * {@code .lotNumber}, {@code .expiryDate} — and the top-level error code is the
     * first one found, line by line. {@code check_goods_receipt_line} checks the same again.</p>
     *
     * <p>A count beyond a PO line's over-receipt tolerance is not refused here (SCRUM-477): a draft is a worksheet, and
     * what the receiver counted is what the manager decides on. The response flags the lines concerned
     * ({@link GoodsReceipts#OVER_TOLERANCE}); confirming the receipt sends it to the manager.</p>
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
     * carton of the old one is kept) and {@code lines[i].packages} (cartons above the line).
     * Only once the count is saved - so a new line's row exists for the cartons' foreign key - does inventory
     * label, recount, move and void them, for every line that has cartons now or had some before; the response
     * then carries their LPNs. The lines are taken SKU by SKU in code order, so two counts never take two SKUs'
     * locks the other way round.</p>
     *
     * <p>4A-5: a receipt against a production order is counted by {@link #replaceProductionLines}.</p>
     */
    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "goods-receipt", resourceId = "#receiptId")
    public ReceiptView replaceLines(UUID receiptId, List<NewLine> newLines) {
        GoodsReceipt receipt = load(receiptId);
        if (receipt.againstProductionOrder()) {
            return replaceProductionLines(receipt, newLines);
        }
        // SCRUM-478: a receipt without a PO has no order; its lines name the item by SKU.
        ReceivingPurchaseOrder order = orderOf(receipt);
        LocalDate receivedOn = LocalDate.ofInstant(receipt.receivedAt(), BUSINESS_ZONE);
        Map<String, ReceiptLine> current = new HashMap<>();
        for (ReceiptLine line : receipt.lines()) {
            current.put(lineKey(line.poLineId(), line.inventoryItemId(), line.lotNumber()), line);
        }
        // The cartons labelled so far, read once: rule 7, and which lines inventory must count again.
        Map<UUID, List<StockPackageView>> labelled = receipt.lines().isEmpty() ? Map.of()
                : inventory.receivingPackagesOf(receipt.lines().stream().map(ReceiptLine::id).toList());
        // Every unit of every item a line names a unit of, in one query: a lookup per line was a query per line.
        Map<UUID, InventoryItemPolicy> items = new HashMap<>();
        // The policies of every item the lines name, in one read (maxShelfLifeDays is part of it); an item it does
        // not return falls back to the single read below.
        if (order != null) {
            items.putAll(inventory.itemPolicies(newLines.stream()
                    .flatMap(in -> order.line(in.purchaseOrderLineId()).stream())
                    .map(ReceivingPurchaseOrder.Line::inventoryItemId).distinct().toList()));
        }
        // The items of a receipt without a PO by SKU, one read each; an unknown or malformed SKU is that line's error.
        Map<String, InventoryItemPolicy> bySku = new HashMap<>();
        if (order == null) {
            for (NewLine in : newLines) {
                String sku = blankToNull(in.sku());
                if (sku != null && !bySku.containsKey(sku)) {
                    itemBySku(sku).ifPresent(policy -> {
                        bySku.put(sku, policy);
                        items.put(policy.inventoryItemId(), policy);
                    });
                }
            }
        }
        // A line's units are needed when it names one, or lists cartons whose unit defaults to the line's own or the
        // ordered one (SCRUM-473).
        List<Sku> unitSkus = newLines.stream()
                .filter(in -> blankToNull(in.unitOfMeasure()) != null || !in.packages().isEmpty())
                .map(in -> order != null
                        ? order.line(in.purchaseOrderLineId()).map(poLine ->
                                items.computeIfAbsent(poLine.inventoryItemId(), this::policy).sku()).orElse(null)
                        : Optional.ofNullable(bySku.get(blankToNull(in.sku()))).map(InventoryItemPolicy::sku)
                                .orElse(null))
                .filter(Objects::nonNull).map(Sku::new)
                .distinct().toList();
        Map<String, List<UomConversion>> unitsBySku = unitSkus.isEmpty() ? Map.of() : units.conversionsOf(unitSkus);

        LineErrors errors = new LineErrors();
        List<ReceiptLine> counted = new ArrayList<>();
        List<CartonCount> cartonCounts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < newLines.size(); i++) {
            NewLine in = newLines.get(i);
            ReceivingPurchaseOrder.Line poLine = null;
            UUID itemId;
            String lineLabel;
            if (order == null) {
                // BR-RCP-002: no PO line on a receipt without a PO; the line is the item, by SKU.
                if (in.purchaseOrderLineId() != null) {
                    errors.add(i, "purchaseOrderLineId", ErrorCode.RECEIPT_LINE_NOT_ON_PO,
                            "Receipt %s has no purchase order: count the item by its SKU".formatted(receipt.number()));
                    continue;
                }
                String sku = blankToNull(in.sku());
                if (sku == null) {
                    errors.add(i, "sku", ErrorCode.VALIDATION_FAILED, "The SKU of the item received is required");
                    continue;
                }
                InventoryItemPolicy found = bySku.get(sku);
                if (found == null) {
                    errors.add(i, "sku", ErrorCode.INVENTORY_ITEM_NOT_FOUND, "No inventory item with SKU " + sku);
                    continue;
                }
                itemId = found.inventoryItemId();
                lineLabel = found.sku();
            } else {
                Optional<ReceivingPurchaseOrder.Line> found = in.purchaseOrderLineId() == null
                        ? Optional.empty() : order.line(in.purchaseOrderLineId());
                if (found.isEmpty()) {
                    errors.add(i, "purchaseOrderLineId", ErrorCode.RECEIPT_LINE_NOT_ON_PO,
                            "Line %s is not a line of %s".formatted(in.purchaseOrderLineId(), order.number()));
                    continue;
                }
                poLine = found.get();
                if (!poLine.open()) {
                    errors.add(i, "purchaseOrderLineId", ErrorCode.PO_LINE_NOT_OPEN,
                            "Line %d of %s is %s".formatted(poLine.lineNo(), order.number(), poLine.status()));
                    continue;
                }
                itemId = poLine.inventoryItemId();
                lineLabel = "Line %d of %s".formatted(poLine.lineNo(), order.number());
            }
            UUID poLineId = poLine == null ? null : poLine.id();
            InventoryItemPolicy item = items.computeIfAbsent(itemId, this::policy);
            String lot = blankToNull(in.lotNumber());
            int before = errors.size();
            if (in.quantity() < 1) {
                // The request leaves it out only for a production receipt, which counts it from the cartons.
                errors.add(i, "quantity", ErrorCode.VALIDATION_FAILED, "A line receives at least 1 unit");
            }
            ReceivingLocation location = errors.area(i, in.locationCode(), RECEIVING, receipt.warehouseId());
            checkLotData(errors, i, item, lot, in.expiryDate(), receivedOn);
            if (!seen.add(lineKey(poLineId, itemId, lot))) {
                errors.add(i, "lotNumber", ErrorCode.DUPLICATE_RECEIPT_LINE,
                        "%s with this lot is already counted above; put the whole quantity of a lot on one line"
                                .formatted(lineLabel));
            }
            ReceiptLine was = current.get(lineKey(poLineId, itemId, lot));
            UUID lineId = was == null ? Identifiers.newId() : was.id();
            List<StockPackageView> cartonsBefore = labelled.getOrDefault(lineId, List.of());
            UomConversion unit = cartonUnit(errors, i, in, item, unitsBySku.getOrDefault(item.sku(), List.of()),
                    was == null ? null : was.uomConversionId(),
                    poLine == null ? null : poLine.uomConversionId(), cartonsBefore);
            if (errors.size() == before) {
                counted.add(ReceiptLine.counted(lineId, poLineId, itemId,
                        location.id(), in.quantity(), lot, in.expiryDate(), blankToNull(in.note()),
                        unit == null ? null : unit.id()));
                if (!in.packages().isEmpty() || !cartonsBefore.isEmpty()) {
                    cartonCounts.add(new CartonCount(lineId, item.sku(), unit == null ? null : unit.id(),
                            location.code(), in.packages()));
                }
            }
        }
        errors.throwIfAny();
        requireNoCartonsOnDroppedLines(receipt, counted);
        receipt.replaceLines(counted);
        GoodsReceipt saved = receipts.save(receipt);
        countCartons(cartonCounts);
        return view(saved, order);
    }

    /**
     * Inventory labels, recounts, moves and voids each line's cartons, once the count is saved - so a new line's row
     * exists for the cartons' foreign key - SKU by SKU in code order, then by line: two counts never take two SKUs'
     * locks the other way round.
     */
    private void countCartons(List<CartonCount> cartonCounts) {
        UUID actor = users.current().map(CurrentUser::userId).orElse(null);
        List<CartonCount> ordered = new ArrayList<>(cartonCounts);
        ordered.sort(Comparator.comparing(CartonCount::sku).thenComparing(CartonCount::lineId));
        for (CartonCount count : ordered) {
            inventory.countReceivingPackages(new CountReceivingPackagesCommand(count.lineId(), new Sku(count.sku()),
                    count.unitId(), count.locationCode(), count.packages().stream()
                    .map(p -> new CountReceivingPackagesCommand.PackageCount(p.lpn(), p.quantity()))
                    .toList(), actor));
        }
    }

    // ------------------------------------------------------------------ production orders (4A-5)

    /**
     * kltn-docs 03 §4.1 step 2: the whole count of a production receipt, one line per production order line, each
     * listing its cartons. Locked as every production receipt write is (see the class comment): the production order,
     * then the receipt, then - through inventory - the SKUs. Every line is checked before anything is refused, each
     * refusal on its own field ({@code lines[i].productionOrderLineId}, {@code .locationCode}, {@code .packages},
     * {@code .packages[j].quantity}, {@code .packages[j].lpn}, {@code .unitOfMeasure}, {@code .quantity},
     * {@code .lotNumber}, {@code .expiryDate}, {@code .purchaseOrderLineId}, {@code .sku}).
     *
     * <p>BR-02 counts the cartons of every other receipt of the production order line that is not cancelled - drafts
     * included, as the documents say - under the production order's lock, so two counts cannot each see room the
     * other takes; {@code tg_goods_receipt_lines_production_cartons} counts the same again at the save. Every carton
     * holds the line's standard count (BR-13): a carton of another count is not listed, it goes back to the producer.
     * A line keeps its id across counts (its cartons point at it).</p>
     */
    private ReceiptView replaceProductionLines(GoodsReceipt read, List<NewLine> newLines) {
        ReceivableProductionOrder production = lockProduction(read);
        GoodsReceipt receipt = loadForUpdate(read.id());
        receipt.requireDraft("change the lines of");
        requireReceivable(production);
        Map<UUID, ReceiptLine> current = new HashMap<>();
        receipt.lines().forEach(line -> current.put(line.productionOrderLineId(), line));
        Map<UUID, List<StockPackageView>> labelled = receipt.lines().isEmpty() ? Map.of()
                : inventory.receivingPackagesOf(receipt.lines().stream().map(ReceiptLine::id).toList());
        // The items of the SKUs the count names, one read each, and all their units in one.
        Map<String, InventoryItemPolicy> items = new HashMap<>();
        Set<UUID> named = new LinkedHashSet<>();
        for (NewLine in : newLines) {
            Optional<ReceivableProductionOrder.Line> line = in.productionOrderLineId() == null ? Optional.empty()
                    : production.line(in.productionOrderLineId());
            line.ifPresent(found -> {
                named.add(found.id());
                if (!items.containsKey(found.sku())) {
                    itemBySku(found.sku()).ifPresent(policy -> items.put(found.sku(), policy));
                }
            });
        }
        Map<String, List<UomConversion>> unitsBySku = items.isEmpty() ? Map.of()
                : units.conversionsOf(items.keySet().stream().map(Sku::new).toList());
        Map<UUID, Integer> elsewhere = receipts.unitsOnOtherProductionReceipts(named, receipt.id());

        LineErrors errors = new LineErrors();
        List<ReceiptLine> counted = new ArrayList<>();
        List<CartonCount> cartonCounts = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < newLines.size(); i++) {
            NewLine in = newLines.get(i);
            int before = errors.size();
            if (in.purchaseOrderLineId() != null) {
                errors.add(i, "purchaseOrderLineId", ErrorCode.VALIDATION_FAILED, ("Receipt %s is against production "
                        + "order %s: its lines name production order lines").formatted(receipt.number(),
                        production.code()));
            }
            if (blankToNull(in.lotNumber()) != null) {
                errors.add(i, "lotNumber", ErrorCode.RECEIPT_LOT_DATA_INVALID, "LOT_NOT_ALLOWED", ("The lot is "
                        + "production order %s's code, assigned by the system (BR-03)").formatted(production.code()));
            }
            if (in.expiryDate() != null) {
                errors.add(i, "expiryDate", ErrorCode.RECEIPT_LOT_DATA_INVALID, "EXPIRY_NOT_ALLOWED",
                        "Finished goods carry no expiry date (BR-06)");
            }
            if (in.productionOrderLineId() == null) {
                errors.add(i, "productionOrderLineId", ErrorCode.VALIDATION_FAILED,
                        "The production order line counted is required");
                continue;
            }
            ReceivableProductionOrder.Line line = production.line(in.productionOrderLineId()).orElse(null);
            if (line == null) {
                errors.add(i, "productionOrderLineId", ErrorCode.RECEIPT_LINE_NOT_ON_PRODUCTION_ORDER,
                        "Line %s is not a line of production order %s".formatted(in.productionOrderLineId(),
                                production.code()));
                continue;
            }
            String label = "%s of %s".formatted(line.sku(), production.code());
            if (!seen.add(line.id())) {
                errors.add(i, "productionOrderLineId", ErrorCode.DUPLICATE_RECEIPT_LINE,
                        "%s is already counted above; put all its cartons on one line".formatted(label));
            }
            String sku = blankToNull(in.sku());
            if (sku != null && !sku.equals(line.sku())) {
                errors.add(i, "sku", ErrorCode.RECEIPT_LINE_NOT_ON_PRODUCTION_ORDER, ("%s is not the SKU of %s: goods "
                        + "not on the production order are not received").formatted(sku, label));
            }
            InventoryItemPolicy item = items.get(line.sku());
            if (item == null) {
                errors.add(i, "productionOrderLineId", ErrorCode.INVENTORY_ITEM_NOT_FOUND,
                        "No inventory item with SKU " + line.sku());
                continue;
            }
            if (item.expiryTracked()) {
                errors.add(i, "productionOrderLineId", ErrorCode.RECEIPT_LOT_DATA_INVALID, "EXPIRY_REQUIRED",
                        ("%s is set up as expiry-tracked, but finished goods carry no expiry date (BR-06): its "
                                + "inventory policy is corrected first").formatted(line.sku()));
            }
            ReceivingLocation location = errors.area(i, in.locationCode(), RECEIVING, receipt.warehouseId());
            ReceiptLine was = current.get(line.id());
            UUID lineId = was == null ? Identifiers.newId() : was.id();
            List<StockPackageView> cartonsBefore = labelled.getOrDefault(lineId, List.of());
            int cartons = standardCartons(errors, i, in, line);
            UomConversion unit = productionCartonUnit(errors, i, in, line,
                    unitsBySku.getOrDefault(line.sku(), List.of()), was == null ? null : was.uomConversionId(),
                    cartonsBefore);
            int quantity = cartons * line.unitsPerCarton();
            if (in.quantity() != 0 && in.quantity() != quantity) {
                errors.add(i, "quantity", ErrorCode.VALIDATION_FAILED, ("%d carton(s) of %d hold %d units, not %d: "
                        + "leave the quantity out, it is counted from the cartons").formatted(cartons,
                        line.unitsPerCarton(), quantity, in.quantity()));
            }
            int onOthers = elsewhere.getOrDefault(line.id(), 0) / line.unitsPerCarton();
            if (cartons > 0 && onOthers + cartons > line.cartons()) {
                errors.add(i, "packages", ErrorCode.RECEIPT_EXCEEDS_PRODUCTION_ORDER, ("%s orders %d carton(s) and %d "
                        + "are on its other receipts not cancelled, drafts included: %d more at most, not %d (BR-02)")
                        .formatted(label, line.cartons(), onOthers, Math.max(line.cartons() - onOthers, 0), cartons));
            }
            if (errors.size() == before) {
                counted.add(ReceiptLine.countedForProductionOrder(lineId, line.id(), item.inventoryItemId(),
                        location.id(), quantity, production.code(), blankToNull(in.note()), unit.id()));
                cartonCounts.add(new CartonCount(lineId, line.sku(), unit.id(), location.code(), in.packages().stream()
                        .map(carton -> new NewPackage(carton.lpn(), line.unitsPerCarton())).toList()));
            }
        }
        errors.throwIfAny();
        requireNoCartonsOnDroppedLines(receipt, counted);
        receipt.replaceLines(counted);
        GoodsReceipt saved = receipts.save(receipt);
        countCartons(cartonCounts);
        return view(saved, null, production);
    }

    /**
     * BR-12, BR-13: a line is received in cartons, at least one, each holding the line's standard count - a carton
     * listed with no count holds it. Answers how many cartons are listed.
     */
    private static int standardCartons(LineErrors errors, int index, NewLine in, ReceivableProductionOrder.Line line) {
        if (in.packages().isEmpty()) {
            errors.add(index, "packages", ErrorCode.VALIDATION_FAILED,
                    "Goods are received in cartons: list at least one (BR-12)");
            return 0;
        }
        Set<String> named = new HashSet<>();
        for (int j = 0; j < in.packages().size(); j++) {
            NewPackage carton = in.packages().get(j);
            if (carton.quantity() != 0 && carton.quantity() != line.unitsPerCarton()) {
                errors.add(index, "packages[%d].quantity".formatted(j), ErrorCode.RECEIPT_CARTON_NOT_STANDARD,
                        ("A carton of %s holds its standard %d; one of %d is not received, it goes back to the producer "
                                + "(BR-13)").formatted(line.sku(), line.unitsPerCarton(), carton.quantity()));
            }
            String lpn = StockPackageView.normalLpn(carton.lpn());
            if (lpn != null && !named.add(lpn)) {
                errors.add(index, "packages[%d].lpn".formatted(j), ErrorCode.VALIDATION_FAILED,
                        "%s is listed twice".formatted(lpn));
            }
        }
        return in.packages().size();
    }

    /**
     * The packing unit a production line's cartons are in: the SKU's carton specification holding the line's standard
     * count (kltn-docs 01 BR-11). {@code unitOfMeasure} names it when the SKU has several of that count; otherwise the
     * line's own unit when it still fits (a recount), else the one active unit of that count, else the one of them
     * that is the default shipping unit. Null, with the refusal recorded on {@code unitOfMeasure}, when none fits. As
     * for purchase lines (design §3 rule 7), a carton of another unit is not kept while the line changes unit.
     */
    private static UomConversion productionCartonUnit(LineErrors errors, int index, NewLine in,
                                                      ReceivableProductionOrder.Line line,
                                                      List<UomConversion> itemUnits, UUID lineUnitId,
                                                      List<StockPackageView> cartonsBefore) {
        List<UomConversion> fitting = itemUnits.stream()
                .filter(unit -> !unit.base() && unit.factor() == line.unitsPerCarton()).toList();
        String code = blankToNull(in.unitOfMeasure());
        UomConversion unit;
        if (code != null) {
            Optional<UomConversion> found = unitByCode(itemUnits, code);
            if (found.isEmpty()) {
                errors.add(index, "unitOfMeasure", ErrorCode.UOM_CONVERSION_NOT_FOUND,
                        "%s has no unit %s".formatted(line.sku(), code));
                return null;
            }
            if (!fitting.contains(found.get())) {
                errors.add(index, "unitOfMeasure", ErrorCode.RECEIPT_LINE_UNIT_INVALID, ("%s is not a carton of %s "
                        + "holding its standard %d (BR-13)").formatted(code, line.sku(), line.unitsPerCarton()));
                return null;
            }
            unit = found.get();
        } else {
            Optional<UomConversion> kept = fitting.stream().filter(u -> u.id().equals(lineUnitId)).findFirst();
            List<UomConversion> active = fitting.stream().filter(UomConversion::active).toList();
            List<UomConversion> shipping = active.stream().filter(UomConversion::defaultShipping).toList();
            if (kept.isPresent()) {
                unit = kept.get();
            } else if (active.size() == 1) {
                unit = active.getFirst();
            } else if (shipping.size() == 1) {
                unit = shipping.getFirst();
            } else if (active.isEmpty()) {
                errors.add(index, "unitOfMeasure", ErrorCode.RECEIPT_LINE_UNIT_INVALID, ("%s has no active carton of "
                        + "%d: its carton specification is set up first (01 BR-11)").formatted(line.sku(),
                        line.unitsPerCarton()));
                return null;
            } else {
                errors.add(index, "unitOfMeasure", ErrorCode.VALIDATION_FAILED, ("%s has several cartons of %d (%s): "
                        + "name the one the goods arrived in").formatted(line.sku(), line.unitsPerCarton(),
                        String.join(", ", active.stream().map(UomConversion::unitOfMeasure).toList())));
                return null;
            }
        }
        if (!unit.active() && !unit.id().equals(lineUnitId)) {
            errors.add(index, "unitOfMeasure", ErrorCode.RECEIPT_LINE_UNIT_INVALID,
                    "%s is retired; cartons are labelled in an active packing unit".formatted(unit.unitOfMeasure()));
            return null;
        }
        Set<String> named = new HashSet<>();
        in.packages().forEach(carton -> Optional.ofNullable(StockPackageView.normalLpn(carton.lpn()))
                .ifPresent(named::add));
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
     * kltn-docs 03 §4.1 step 6. Locked as counting is - the production order (its sales order first), then the receipt -
     * and what counting checked is checked again, since another receipt may have been counted or confirmed meanwhile:
     * the receipt is a draft with lines, the production order can still receive goods, every line's cartons hold its
     * quantity in the standard count, and BR-02 holds. The goods are then counted in as INBOUND stock in their cartons,
     * in the production order's lot, and the production order moves on ({@link ProductionOrderReceiving#recordReceipt}).
     * No tolerance and no approval: what is over was refused while counting.
     */
    private ReceiptView confirmProduction(GoodsReceipt read, UUID userId) {
        ReceivableProductionOrder production = lockProduction(read);
        GoodsReceipt receipt = loadForUpdate(read.id());
        // Microseconds, as the database keeps them: also the receipt time of the stock layers this creates.
        receipt.confirm(userId, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        requireReceivable(production);
        requireStandardCartons(receipt);
        requireWithinProductionOrder(receipt, production);
        return post(receipt, null, production, userId);
    }

    /**
     * BR-12, BR-13 at confirmation: every line's cartons - at least one - hold all its units, each its standard count,
     * so receiving leaves nothing loose. A count that drifted from its cartons is counted again.
     */
    private void requireStandardCartons(GoodsReceipt receipt) {
        Map<UUID, List<StockPackageView>> cartons = inventory.receivingPackagesOf(
                receipt.lines().stream().map(ReceiptLine::id).toList());
        for (ReceiptLine line : receipt.lines()) {
            List<StockPackageView> labelled = cartons.getOrDefault(line.id(), List.of()).stream()
                    .filter(carton -> "RECEIVING".equals(carton.status())).toList();
            boolean standard = labelled.stream().allMatch(carton -> Objects.equals(carton.receivedQty(),
                    carton.nominalQty()));
            if (labelled.isEmpty() || !standard || countedIntoCartons(labelled) != line.receivedQuantity()) {
                throw new BusinessException(ErrorCode.RECEIPT_CARTON_NOT_STANDARD, ("Receipt %s: a line of %d units has "
                        + "%d carton(s) holding %d; every unit is received in a carton of its standard count (BR-12, "
                        + "BR-13) - count the line again").formatted(receipt.number(), line.receivedQuantity(),
                        labelled.size(), countedIntoCartons(labelled)));
            }
        }
    }

    /** BR-02 at confirmation: every line within what its production order line orders, all refusals named at once. */
    private void requireWithinProductionOrder(GoodsReceipt receipt, ReceivableProductionOrder production) {
        Map<UUID, Integer> elsewhere = receipts.unitsOnOtherProductionReceipts(receipt.lines().stream()
                .map(ReceiptLine::productionOrderLineId).toList(), receipt.id());
        List<FieldError> over = new ArrayList<>();
        for (int i = 0; i < receipt.lines().size(); i++) {
            ReceiptLine line = receipt.lines().get(i);
            ReceivableProductionOrder.Line ordered = production.line(line.productionOrderLineId()).orElseThrow(() ->
                    new IllegalStateException("Receipt %s counts a line its production order does not have"
                            .formatted(receipt.number())));
            int onOthers = elsewhere.getOrDefault(ordered.id(), 0) / ordered.unitsPerCarton();
            int here = line.receivedQuantity() / ordered.unitsPerCarton();
            if (onOthers + here > ordered.cartons()) {
                over.add(new FieldError("lines[%d].packages".formatted(i), ("%s of %s orders %d carton(s) and %d are on "
                        + "its other receipts not cancelled: %d more at most, not %d (BR-02)").formatted(ordered.sku(),
                        production.code(), ordered.cartons(), onOthers, Math.max(ordered.cartons() - onOthers, 0),
                        here), ErrorCode.RECEIPT_EXCEEDS_PRODUCTION_ORDER.name()));
            }
        }
        if (!over.isEmpty()) {
            throw new BusinessException(ErrorCode.RECEIPT_EXCEEDS_PRODUCTION_ORDER, "Receipt %s: %s".formatted(
                    receipt.number(), String.join("; ", over.stream().map(FieldError::message).toList())), over);
        }
    }

    /** Cartons of each production order line on a receipt. */
    private static Map<UUID, Integer> cartonsPerProductionOrderLine(GoodsReceipt receipt,
                                                                    ReceivableProductionOrder production) {
        Map<UUID, Integer> cartons = new LinkedHashMap<>();
        for (ReceiptLine line : receipt.lines()) {
            int per = production.line(line.productionOrderLineId()).orElseThrow().unitsPerCarton();
            cartons.merge(line.productionOrderLineId(), line.receivedQuantity() / per, Integer::sum);
        }
        return cartons;
    }

    /** Cancelled while a draft, under the production order's lock; the production order lets the draft go. */
    private ReceiptView cancelProduction(GoodsReceipt read) {
        ReceivableProductionOrder production = lockProduction(read);
        GoodsReceipt receipt = loadForUpdate(read.id());
        receipt.cancel();
        // Cartons labelled while counting held no stock and their labels are no longer valid (RECEIVING → VOID).
        inventory.voidStockPackagesOfReceiptLines(receipt.lines().stream().map(ReceiptLine::id).toList());
        GoodsReceipt saved = receipts.save(receipt);
        receiving.dropReceipt(production.id(), saved.id(), users.current().map(CurrentUser::userId).orElse(null));
        log.info("Receipt {} against production order {} cancelled", saved.number(), production.code());
        return view(saved, null, production);
    }

    /** The receipt's production order, locked: its sales order first, then it (see the class comment). */
    private ReceivableProductionOrder lockProduction(GoodsReceipt receipt) {
        return receiving.lock(receipt.productionOrderId()).orElseThrow(() -> new IllegalStateException(
                "Receipt %s points at a missing production order".formatted(receipt.number())));
    }

    /** BR-01, BR-15: goods are received against it now. */
    private static void requireReceivable(ReceivableProductionOrder production) {
        if (!production.receivable()) {
            throw new BusinessException(ErrorCode.PRODUCTION_ORDER_NOT_RECEIVABLE, production.notReceivableReason());
        }
    }

    /**
     * The unit among an item's units with this code, active or retired. Unit codes are stored upper-case
     * ({@code ck_uom_conversions_unit}): a client's "carton" means CARTON.
     */
    private static Optional<UomConversion> unitByCode(List<UomConversion> itemUnits, String code) {
        String wanted = code.strip().toUpperCase(Locale.ROOT);
        return itemUnits.stream().filter(unit -> unit.unitOfMeasure().equals(wanted)).findFirst();
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
     *
     * <p>Cartons listed with no unit are in the line's own unit when it has been counted in one, else in
     * {@code orderedUnitId}, the unit the PO line was ordered in (SCRUM-473). That is the default, never a rule: a
     * supplier that ships another pack size is counted in the unit the clerk names, and every rule below holds the
     * defaulted unit as it holds a named one. A unit retired since the PO line was ordered in it is still accepted, as
     * {@code tg_goods_receipt_lines_unit} allows: goods ordered before the supplier changed its carton arrive in the
     * old one.</p>
     */
    private UomConversion cartonUnit(LineErrors errors, int index, NewLine in, InventoryItemPolicy item,
                                     List<UomConversion> itemUnits, UUID lineUnitId,
                                     UUID orderedUnitId, List<StockPackageView> cartonsBefore) {
        String code = blankToNull(in.unitOfMeasure());
        if (in.packages().isEmpty()) {
            if (code != null) {
                Optional<UomConversion> unit = unitByCode(itemUnits, code);
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
        // The line's own unit first: a recount of a line counted in another unit than ordered must not switch it and
        // void its labelled cartons.
        UUID fallback = lineUnitId != null ? lineUnitId : orderedUnitId;
        if (code == null && fallback != null) {
            code = itemUnits.stream().filter(unit -> unit.id().equals(fallback)).findFirst()
                    .map(UomConversion::unitOfMeasure).orElse(null);
        }
        if (code == null) {
            errors.add(index, "unitOfMeasure", ErrorCode.VALIDATION_FAILED, "Cartons need the packing unit they are in");
            return null;
        }
        Optional<UomConversion> found = unitByCode(itemUnits, code);
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
        if (!unit.active() && !unit.id().equals(lineUnitId) && !unit.id().equals(orderedUnitId)) {
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
    private static String lineKey(UUID poLineId, UUID itemId, String lot) {
        // A receipt without a PO has no PO line: its lines are told apart by item (and lot).
        return (poLineId == null ? "item:" + itemId : poLineId.toString()) + "|" + Objects.toString(lot, "");
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
        if (receipt.againstProductionOrder()) {
            return confirmProduction(receipt, userId);
        }
        if (receipt.blind()) {
            // BR-RCP-002: a receipt without a PO is never posted by whoever counted it - it always waits for the manager.
            receipt.requestApproval(userId, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            GoodsReceipt waiting = receipts.save(receipt);
            log.info("Receipt {} has no purchase order: waiting for the warehouse manager", waiting.number());
            return view(waiting, null);
        }
        // Lock the order first: two receipts of one order confirmed together must not both compute
        // its status from totals that miss the other.
        ReceivingPurchaseOrder order = lockOrder(receipt);
        requireReceivable(order);
        Map<UUID, Integer> perPoLine = countedPerPoLine(receipt);
        if (!overTolerance(order, receipt.id(), perPoLine).isEmpty()) {
            // BR-RCP-001: beyond the tolerance the count is not refused and not posted - the manager decides.
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            receipt.requestApproval(userId, now);
            GoodsReceipt waiting = receipts.save(receipt);
            log.info("Receipt {} against {} goes beyond the over-receipt tolerance: waiting for the warehouse manager",
                    waiting.number(), order.number());
            return view(waiting, order);
        }
        // Microseconds, as the database keeps them: the confirmation time is also the receipt time of
        // the stock layers this creates.
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        receipt.confirm(userId, now);
        return post(receipt, order, null, userId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Locks the order first, like {@link #confirm}, then the receipt: the posting moves the order's lines on from
     * totals that must not miss another receipt's, and a second decision on the same receipt waits here and finds it
     * posted.</p>
     */
    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "goods-receipt", resourceId = "#receiptId")
    public ReceiptView approve(UUID receiptId, UUID approverId, String reason) {
        GoodsReceipt receipt = load(receiptId);
        ReceivingPurchaseOrder order = lockOrder(receipt);
        receipt = loadForUpdate(receiptId);
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        // The receipt's own state, the reason and the four eyes are answered before the order's state is.
        receipt.approve(approverId, reason, now);
        if (order != null) {
            requireReceivable(order);
        }
        return post(receipt, order, null, approverId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each PO line of the receipt may take what its limit leaves after the receipts already posted; the excess comes
     * off the receipt's lines of that PO line from the last one counted backwards. The cut is saved while the receipt
     * still waits (the lines of a posted receipt are frozen, BR-05) and the receipt is then posted. A line that
     * labelled cartons were counted into cannot be cut below them, nor removed: the manager approves instead, or the
     * receipt is cancelled and counted again.</p>
     */
    @Override
    @Auditable(action = AuditAction.REJECT, resourceType = "goods-receipt", resourceId = "#receiptId")
    public ReceiptView rejectExcess(UUID receiptId, UUID approverId, String reason) {
        GoodsReceipt receipt = load(receiptId);
        ReceivingPurchaseOrder order = lockOrder(receipt);
        receipt = loadForUpdate(receiptId);
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        List<ReceiptLine> before = List.copyOf(receipt.lines());
        receipt.cutBackTo(allowedPerPoLine(order, receipt));
        requireCartonsFitTheCut(receipt, before);
        // The cut is saved while the receipt still waits: the lines of a posted receipt are frozen (BR-05). The reason
        // and the four eyes are answered in the aggregate first, so a refusal leaves nothing behind either way (it is
        // one transaction).
        if (!before.equals(receipt.lines())) {
            receipts.save(receipt);
        }
        receipt.rejectExcess(approverId, reason, now);
        if (receipt.status() == GoodsReceiptStatus.CANCELLED) {
            // Nothing within the tolerance is left: the receipt is cancelled, with the manager's reason on it.
            GoodsReceipt cancelled = receipts.save(receipt);
            log.info("Receipt {} against {}: the excess was rejected and nothing within the tolerance is left; cancelled",
                    cancelled.number(), order.number());
            return view(cancelled, order);
        }
        requireReceivable(order);
        return post(receipt, order, null, approverId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>BR-RCP-002: nothing was counted in, so there is nothing to undo beyond the cartons labelled while counting,
     * which are voided as on any cancellation.</p>
     */
    @Override
    @Auditable(action = AuditAction.REJECT, resourceType = "goods-receipt", resourceId = "#receiptId")
    public ReceiptView reject(UUID receiptId, UUID approverId, String reason) {
        GoodsReceipt receipt = loadForUpdate(receiptId);
        receipt.reject(approverId, reason, clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        inventory.voidStockPackagesOfReceiptLines(receipt.lines().stream().map(ReceiptLine::id).toList());
        GoodsReceipt cancelled = receipts.save(receipt);
        log.info("Receipt {} without a purchase order was rejected by the warehouse manager: cancelled",
                cancelled.number());
        return view(cancelled, null);
    }

    /** The receipt's order, locked; null for a receipt without one (SCRUM-478, or against a production order). */
    private ReceivingPurchaseOrder lockOrder(GoodsReceipt receipt) {
        if (receipt.purchaseOrderId() == null) {
            return null;
        }
        return orders.lock(receipt.purchaseOrderId()).orElseThrow(() ->
                new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND, "No purchase order " + receipt.purchaseOrderId()));
    }

    /**
     * The count is final and the receipt has already moved on in the aggregate (confirmed, or decided by the manager):
     * save it, count the goods in as INBOUND stock, move the order's progress - the purchase order's, or the production
     * order's ({@code production}, 4A-5) - and send the events.
     */
    private ReceiptView post(GoodsReceipt receipt, ReceivingPurchaseOrder order, ReceivableProductionOrder production,
                             UUID userId) {
        GoodsReceipt saved = receipts.save(receipt);

        Map<UUID, ReceivingLocation> where = locations.byIds(
                saved.lines().stream().map(ReceiptLine::locationId).toList());
        Map<UUID, InventoryItemPolicy> items = new HashMap<>();
        ReceiptOrigin origin;
        if (production != null) {
            // 4A-5: the lot is the production order's - one per production order and SKU across its receipts (BR-03).
            origin = ReceiptOrigin.ofProductionOrder(saved.id(), saved.number(), saved.warehouseId(), production.id(),
                    production.code());
        } else {
            // SCRUM-142: the lots this receipt opens remember it, its order and its supplier as they read now.
            // A receipt without a PO (BR-RCP-002) names no order, and the supplier it carries itself.
            UUID supplierId = order == null ? saved.supplierId() : order.supplierId();
            origin = new ReceiptOrigin(saved.id(), saved.number(), order == null ? null : order.id(),
                    order == null ? null : order.number(), supplierId, orders.supplierName(supplierId).orElse(null),
                    saved.warehouseId());
        }
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
                    line.receivedQuantity(), locationCode));
            // No quality check at the warehouse (kltn-docs 03 BR-07): every line goes on to putaway.
            readyLines.add(new ReceiptStockReadyForPutaway.Line(line.id(), item.sku(), line.lotNumber(),
                    locationCode, line.receivedQuantity()));
        }

        // A receipt without a PO moves no order: nothing is counted against any PO (BR-RCP-002).
        String orderLabel = "no purchase order";
        if (order != null) {
            Map<UUID, Integer> confirmed = receipts.confirmedQuantities(order.id());
            Map<UUID, ReceivingPurchaseOrder.LineStatus> lineStatuses = new LinkedHashMap<>();
            for (UUID poLineId : countedPerPoLine(saved).keySet()) {
                ReceivingPurchaseOrder.Line poLine = order.line(poLineId).orElseThrow();
                lineStatuses.put(poLineId, ReceivingPurchaseOrder.lineStatusFor(poLine, confirmed.getOrDefault(poLineId, 0)));
            }
            ReceivingPurchaseOrder.Status orderStatus = order.statusAfter(lineStatuses);
            orders.recordProgress(order, lineStatuses, orderStatus, userId, saved.id(), saved.number());
            orderLabel = "against %s, order now %s".formatted(order.number(), orderStatus);
        }
        // 4A-5 (17 §4.3 step 11): the production order counts the cartons in and moves on, in this transaction.
        ReceivableProductionOrder after = production;
        if (production != null) {
            after = receiving.recordReceipt(production.id(), saved.id(),
                    cartonsPerProductionOrderLine(saved, production), userId, saved.confirmedAt());
            orderLabel = "against %s, production order now %s".formatted(after.code(), after.status());
        }

        events.publishEvent(new GoodsReceiptConfirmed(saved.id(), saved.number(), order == null ? null : order.id(),
                saved.warehouseId(), confirmedLines, saved.confirmedAt()));
        events.publishEvent(new ReceiptStockReadyForPutaway(saved.id(), saved.number(), saved.warehouseId(),
                readyLines));
        log.info("Confirmed receipt {} ({}): {} line(s)", saved.number(), orderLabel, saved.lines().size());
        return view(saved, order, after);
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "goods-receipt", resourceId = "#receiptId")
    public ReceiptView cancel(UUID receiptId) {
        GoodsReceipt receipt = load(receiptId);
        if (receipt.againstProductionOrder()) {
            return cancelProduction(receipt);
        }
        receipt.cancel();
        // Cartons labelled while counting held no stock and their labels are no longer valid (RECEIVING → VOID).
        inventory.voidStockPackagesOfReceiptLines(receipt.lines().stream().map(ReceiptLine::id).toList());
        return view(receipts.save(receipt), orderOf(receipt));
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
                                  String text, Instant receivedFrom, Instant receivedTo, Boolean withoutPurchaseOrder,
                                  UUID productionOrderId, GoodsReceiptType receiptType, int page, int size,
                                  String sort) {
        var criteria = new GoodsReceiptSearch.Criteria(statuses, purchaseOrderId, warehouseId, blankToNull(text),
                receivedFrom, receivedTo, withoutPurchaseOrder, productionOrderId, receiptType);
        var rows = search.search(criteria, Pages.of(page, size, SORT.parse(sort)));
        // SCRUM-464: one query each for the orders, the people and the warehouses of the page.
        var labels = search.orderLabels(rows.stream().map(GoodsReceiptSearch.Row::purchaseOrderId)
                .filter(Objects::nonNull).toList());
        // A receipt without a PO (SCRUM-478) carries its supplier itself.
        var supplierNames = search.supplierNames(rows.stream().map(GoodsReceiptSearch.Row::supplierId)
                .filter(Objects::nonNull).toList());
        var names = identity.displayNames(rows.stream()
                .flatMap(r -> java.util.stream.Stream.of(r.receivedBy(), r.confirmedBy())).toList());
        // 4A-5: the production orders of the page, in one call.
        var productionOrders = receiving.findAll(rows.stream().map(GoodsReceiptSearch.Row::productionOrderId)
                .filter(Objects::nonNull).distinct().toList());
        Map<UUID, Optional<WarehouseView>> where = new HashMap<>();
        return Pages.toResponse(rows.map(r -> {
            var label = r.purchaseOrderId() == null ? null : labels.get(r.purchaseOrderId());
            var production = r.productionOrderId() == null ? null : productionOrders.get(r.productionOrderId());
            var warehouse = where.computeIfAbsent(r.warehouseId(), warehouses::findWarehouse);
            return new Row(r.id(), r.number(), r.purchaseOrderId(), label == null ? null : label.purchaseOrderNumber(),
                    r.purchaseOrderId() == null ? r.supplierId() : label == null ? null : label.supplierId(),
                    r.purchaseOrderId() == null ? (r.supplierId() == null ? null : supplierNames.get(r.supplierId()))
                            : label == null ? null : label.supplierName(),
                    r.warehouseId(), warehouse.map(WarehouseView::prefix).orElse(null),
                    warehouse.map(WarehouseView::name).orElse(null), r.status(), r.deliveryNote(), r.receivedAt(),
                    r.receivedBy(), names.get(r.receivedBy()), r.confirmedAt(), r.confirmedBy(),
                    names.get(r.confirmedBy()), r.closedAt(),
                    r.purchaseOrderId() == null && r.productionOrderId() == null, r.receiptType(),
                    r.productionOrderId(), production == null ? null : production.code(),
                    production == null ? null : production.producerName());
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

    /** Units of each PO line on this receipt, in the order the lines were counted. */
    private static Map<UUID, Integer> countedPerPoLine(GoodsReceipt receipt) {
        Map<UUID, Integer> perPoLine = new LinkedHashMap<>();
        receipt.lines().stream().filter(l -> l.poLineId() != null)
                .forEach(l -> perPoLine.merge(l.poLineId(), l.receivedQuantity(), Integer::sum));
        return perPoLine;
    }

    /**
     * BR-02 (BR-RCP-001): the PO lines whose total - what the receipts posted so far hold, plus this receipt's count -
     * goes beyond the supplier's tolerance. Counted the way {@code check_goods_receipt_tolerance} counts it: a draft, a
     * receipt waiting for approval and a cancelled one hold nothing.
     */
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

    /** What each PO line of the receipt may still receive on it: its limit less what other posted receipts hold. */
    private Map<UUID, Integer> allowedPerPoLine(ReceivingPurchaseOrder order, GoodsReceipt receipt) {
        Map<UUID, Integer> perPoLine = countedPerPoLine(receipt);
        if (perPoLine.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> elsewhere = receipts.receivedOnOtherReceipts(perPoLine.keySet(), receipt.id());
        Map<UUID, Integer> allowed = new LinkedHashMap<>();
        for (UUID poLineId : perPoLine.keySet()) {
            int limit = order.line(poLineId).orElseThrow().receivableLimit(order.tolerancePercent());
            allowed.put(poLineId, Math.max(limit - elsewhere.getOrDefault(poLineId, 0), 0));
        }
        return allowed;
    }

    /**
     * Cutting a line back must leave the cartons counted into it where they were: a carton is part of the line
     * ({@code RECEIPT_PACKAGES_EXCEED_LINE} when the cut goes below what they hold), and a line they were ever labelled
     * on cannot be removed ({@code RECEIPT_LINE_HAS_PACKAGES}). Either way the manager approves the count, or the
     * receipt is cancelled and counted again.
     */
    private void requireCartonsFitTheCut(GoodsReceipt receipt, List<ReceiptLine> before) {
        Map<UUID, ReceiptLine> after = new HashMap<>();
        receipt.lines().forEach(l -> after.put(l.id(), l));
        List<UUID> cut = before.stream().filter(l -> !after.containsKey(l.id())
                || after.get(l.id()).receivedQuantity() < l.receivedQuantity()).map(ReceiptLine::id).toList();
        if (cut.isEmpty()) {
            return;
        }
        List<UUID> dropped = cut.stream().filter(id -> !after.containsKey(id)).toList();
        if (!dropped.isEmpty() && !inventory.receiptLinesWithStockPackages(dropped).isEmpty()) {
            throw new BusinessException(ErrorCode.RECEIPT_LINE_HAS_PACKAGES,
                    ("Receipt %s: rejecting the excess would remove a line that labelled cartons were counted into;"
                            + " approve the receipt, or cancel it and count it again").formatted(receipt.number()));
        }
        List<UUID> shrunk = cut.stream().filter(after::containsKey).toList();
        Map<UUID, List<StockPackageView>> cartons = shrunk.isEmpty() ? Map.of() : inventory.receivingPackagesOf(shrunk);
        for (UUID id : cut) {
            if (after.containsKey(id) && countedIntoCartons(cartons.getOrDefault(id, List.of()))
                    > after.get(id).receivedQuantity()) {
                throw new BusinessException(ErrorCode.RECEIPT_PACKAGES_EXCEED_LINE,
                        ("Receipt %s: rejecting the excess would cut a line below the units its cartons hold;"
                                + " approve the receipt, or cancel it and count it again").formatted(receipt.number()));
            }
        }
    }

    /**
     * BR-03 and BR-06, each refusal on its own field with its own code ({@code LOT_REQUIRED},
     * {@code EXPIRY_NOT_AFTER_RECEIPT}, {@code EXPIRY_EXCEEDS_MAX_SHELF_LIFE}…) under RECEIPT_LOT_DATA_INVALID.
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
        // BR-RCP-004 (SCRUM-479): no later than the receiving date plus the SKU's maximum shelf life, when it has one.
        if (expiry != null && item.expiryTracked() && item.maxShelfLifeDays() != null
                && expiry.isAfter(receivedOn.plusDays(item.maxShelfLifeDays()))) {
            errors.add(index, "expiryDate", code, "EXPIRY_EXCEEDS_MAX_SHELF_LIFE",
                    "Expiry date %s is later than %s, the receiving date %s plus the maximum shelf life of %d day(s) of %s (BR-RCP-004)"
                            .formatted(expiry, receivedOn.plusDays(item.maxShelfLifeDays()), receivedOn,
                                    item.maxShelfLifeDays(), item.sku()));
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

    /** What was counted into a line's cartons: the line less this is its loose remainder (design §3 rule 5). */
    private static int countedIntoCartons(List<StockPackageView> cartons) {
        return cartons.stream().mapToInt(c -> c.receivedQty() == null ? 0 : c.receivedQty()).sum();
    }

    /** The item with this SKU, or empty for an unknown or malformed one. */
    private Optional<InventoryItemPolicy> itemBySku(String sku) {
        try {
            return inventory.itemPolicy(new Sku(sku));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
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

    /**
     * {@link #load} under a row lock: a second decision on the same receipt waits here and finds it decided, and
     * {@code PutawayCompletedListener} closing it takes the same lock.
     */
    private GoodsReceipt loadForUpdate(UUID receiptId) {
        GoodsReceipt receipt = receipts.findByIdForUpdate(receiptId).orElseThrow(() ->
                new BusinessException(ErrorCode.GOODS_RECEIPT_NOT_FOUND, "No goods receipt " + receiptId));
        com.stockflow.common.security.WarehouseScope.requireWarehouse(receipt.warehouseId());
        return receipt;
    }

    /**
     * The receipt's order; null for a receipt without one ({@link GoodsReceipt#blind}, SCRUM-478), or against a
     * production order (4A-5).
     */
    private ReceivingPurchaseOrder orderOf(GoodsReceipt receipt) {
        if (receipt.purchaseOrderId() == null) {
            return null;
        }
        return orders.find(receipt.purchaseOrderId()).orElseThrow(() ->
                new IllegalStateException("Receipt %s points at a missing order".formatted(receipt.number())));
    }

    // ------------------------------------------------------------------ view

    private ReceiptView view(GoodsReceipt receipt, ReceivingPurchaseOrder order) {
        return view(receipt, order, receipt.againstProductionOrder()
                ? receiving.find(receipt.productionOrderId()).orElse(null) : null);
    }

    /**
     * The receipt as the screens show it. {@code production}: its production order (4A-5), which each line is shown
     * against - ordered, received so far, this receipt, what remains (03 §3 output, §4.1 step 3); null otherwise.
     */
    private ReceiptView view(GoodsReceipt receipt, ReceivingPurchaseOrder order, ReceivableProductionOrder production) {
        Set<UUID> locationIds = new HashSet<>();
        for (ReceiptLine line : receipt.lines()) {
            locationIds.add(line.locationId());
        }
        Map<UUID, ReceivingLocation> where = locations.byIds(locationIds);
        Map<UUID, InventoryItemPolicy> items = inventory.itemPolicies(
                receipt.lines().stream().map(ReceiptLine::inventoryItemId).toList());
        Set<UUID> people = new HashSet<>();
        Optional.ofNullable(receipt.receivedBy()).ifPresent(people::add);
        Optional.ofNullable(receipt.confirmedBy()).ifPresent(people::add);
        receipt.approval().ifPresent(a -> {
            people.add(a.requestedBy());
            Optional.ofNullable(a.decidedBy()).ifPresent(people::add);
        });
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
        // SCRUM-477: a count not yet posted that goes beyond a PO line's tolerance is flagged, on the lines concerned
        // and on the receipt: confirming it sends it to the manager, and the manager sees which lines to decide on.
        Set<UUID> overPoLines = new HashSet<>();
        if (order != null && (receipt.status() == GoodsReceiptStatus.DRAFT
                || receipt.status() == GoodsReceiptStatus.PENDING_APPROVAL)) {
            overTolerance(order, receipt.id(), countedPerPoLine(receipt))
                    .forEach(over -> overPoLines.add(over.poLineId()));
        }
        // A draft's cartons are not counted in yet: what remains on the production order line leaves them out.
        boolean pending = receipt.status() == GoodsReceiptStatus.DRAFT;
        List<LineView> lines = receipt.lines().stream().map(line -> {
            InventoryItemPolicy item = items.get(line.inventoryItemId());
            List<String> lineWarnings = new ArrayList<>();
            if (flags && ExpiryWarning.isNear(line.expiryDate(), receivedOn, window)) {
                lineWarnings.add(ExpiryWarning.EXPIRY_NEAR);
            }
            if (overPoLines.contains(line.poLineId())) {
                lineWarnings.add(GoodsReceipts.OVER_TOLERANCE);
            }
            List<PackageView> packages = cartons.getOrDefault(line.id(), List.of()).stream()
                    .map(c -> new PackageView(c.id(), c.lpn(), c.quantity() == null ? 0 : c.quantity(),
                            c.receivedQty() == null ? 0 : c.receivedQty(), c.nominalQty(), c.status(),
                            c.locationCode()))
                    .toList();
            // Design §3 rule 5: loose is what was not counted into a carton. Not what is in the cartons now - a
            // confirmed receipt is frozen (BR-05), and units picked from a carton later were never loose.
            int inCartons = packages.stream().mapToInt(PackageView::receivedQty).sum();
            return new LineView(line.id(), line.poLineId(),
                    order == null ? null
                            : order.line(line.poLineId()).map(ReceivingPurchaseOrder.Line::lineNo).orElse(null),
                    line.inventoryItemId(), item == null ? null : item.sku(), line.receivedQuantity(),
                    line.lotNumber(), line.expiryDate(), code(where, line.locationId()), line.note(),
                    item != null && item.lotTracked(), item != null && item.expiryTracked(),
                    line.receivedQuantity(), lineWarnings, line.uomConversionId() == null ? null : unitCodes.get(line.uomConversionId()),
                    line.receivedQuantity() - inCartons, packages,
                    item == null ? List.of() : unitsBySku.getOrDefault(item.sku(), List.of()).stream()
                            .map(u -> new UnitView(u.unitOfMeasure(), u.factor(), u.base(), u.active(),
                                    u.defaultPurchase()))
                            .toList(),
                    order == null ? null : order.line(line.poLineId())
                            .map(ReceivingPurchaseOrder.Line::uomConversionId).map(unitCodes::get).orElse(null),
                    line.productionOrderLineId(), production == null || line.productionOrderLineId() == null ? null
                            : production.line(line.productionOrderLineId())
                            .map(ordered -> progress(ordered, line, pending)).orElse(null));
        }).toList();
        List<String> warnings = new ArrayList<>();
        if (lines.stream().anyMatch(l -> l.warnings().contains(ExpiryWarning.EXPIRY_NEAR))) {
            warnings.add(ExpiryWarning.EXPIRY_NEAR);
        }
        if (!overPoLines.isEmpty()) {
            warnings.add(GoodsReceipts.OVER_TOLERANCE);
        }
        // The supplier comes from the PO, or from the receipt itself when it has none (SCRUM-478); the PO attached to
        // such a receipt afterwards is read for its number only.
        List<UUID> labelled = new ArrayList<>();
        Optional.ofNullable(receipt.purchaseOrderId()).ifPresent(labelled::add);
        Optional.ofNullable(receipt.referencedPurchaseOrderId()).ifPresent(labelled::add);
        var labels = labelled.isEmpty() ? Map.<UUID, GoodsReceiptSearch.OrderLabel>of() : search.orderLabels(labelled);
        var label = receipt.purchaseOrderId() == null ? null : labels.get(receipt.purchaseOrderId());
        var referenced = receipt.referencedPurchaseOrderId() == null ? null
                : labels.get(receipt.referencedPurchaseOrderId());
        // A production receipt (4A-5) has neither: the producer is on the production order.
        UUID supplierId = receipt.purchaseOrderId() != null ? order.supplierId() : receipt.supplierId();
        String supplierName = receipt.purchaseOrderId() != null ? (label == null ? null : label.supplierName())
                : receipt.supplierId() == null ? null
                : search.supplierNames(List.of(receipt.supplierId())).get(receipt.supplierId());
        var warehouse = warehouses.findWarehouse(receipt.warehouseId());
        return new ReceiptView(receipt.id(), receipt.number(), receipt.purchaseOrderId(),
                order == null ? null : order.number(), supplierId, supplierName, receipt.warehouseId(),
                warehouse.map(WarehouseView::prefix).orElse(null), warehouse.map(WarehouseView::name).orElse(null),
                receipt.status(), receipt.deliveryNote(), receipt.note(), receipt.receivedAt(), receipt.receivedBy(),
                names.get(receipt.receivedBy()), receipt.confirmedAt(), receipt.confirmedBy(),
                names.get(receipt.confirmedBy()), receipt.closedAt(), lines, warnings,
                receipt.approval().map(a -> new Approval(a.requestedAt(), a.requestedBy(),
                        names.get(a.requestedBy()), a.decision(), a.decidedAt(), a.decidedBy(),
                        a.decidedBy() == null ? null : names.get(a.decidedBy()), a.reason())).orElse(null),
                receipt.blind(), receipt.blindReason(), receipt.referencedPurchaseOrderId(),
                referenced == null ? null : referenced.purchaseOrderNumber(), receipt.type(),
                receipt.productionOrderId(), production == null ? null : production.code(),
                production == null ? null : production.status(), production == null ? null : production.producerId(),
                production == null ? null : production.producerName());
    }

    /** A production line against what its production order line orders, in cartons and units (4A-5). */
    private static ProductionLineProgress progress(ReceivableProductionOrder.Line ordered, ReceiptLine line,
                                                   boolean pending) {
        int per = ordered.unitsPerCarton();
        int here = line.receivedQuantity() / per;
        int remaining = Math.max(ordered.cartons() - ordered.receivedCartons() - (pending ? here : 0), 0);
        return new ProductionLineProgress(per, ordered.cartons(), ordered.receivedCartons(), here, remaining,
                ordered.cartons() * per, ordered.receivedCartons() * per, here * per, remaining * per);
    }

    private static String code(Map<UUID, ReceivingLocation> where, UUID id) {
        return id == null || !where.containsKey(id) ? null : where.get(id).code();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
