package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.api.PageResponse;
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
import com.stockflow.contracts.ReceiptStockReadyForPutaway;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.inventory.api.CountReceivingPackagesCommand;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryItemPolicy;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReceiptOrigin;
import com.stockflow.inventory.api.ReceiveStockCommand;
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
 * Goods receipts against production orders (4A-5, kltn-docs 03 §4.1, BR-01..BR-15), through {@code order :: api}
 * ({@link ProductionOrderReceiving}). Whole cartons of each production order line's standard count, in the production
 * order's lot, never more than the line orders on all its receipts not cancelled (BR-02): nothing over is received, so
 * nothing waits for approval.
 *
 * <p>Confirming is the one step with consequences beyond the receipt, and they all happen in its transaction: the
 * cartons become INBOUND stock (inventory, same transaction through {@code inventory :: api}), the production order
 * moves on, and the events go out after commit. A failure anywhere leaves the receipt a DRAFT with nothing counted
 * in.</p>
 *
 * <p><b>Lock order</b> - starting, counting, confirming and cancelling alike: the production order's sales order, then
 * the production order ({@link ProductionOrderReceiving#lock}), then the goods receipt ({@code FOR UPDATE}), then
 * inventory's SKU advisory locks (counting or receiving the cartons, by SKU then line). The order module takes the same
 * first two for every write to a production order, so a receipt and a cancellation or an approval of the same
 * production order wait for each other instead of deadlocking.</p>
 *
 * <p>Purchasing is gone (4A-8, kltn-docs 00 of 04/11): receipts against purchase orders - and blind ones, the
 * over-tolerance approval, the PO reference of a blind receipt - went with it. A historical purchase receipt is read as
 * it stands and, while it has counted nothing in, cancelled; the aggregate refuses anything else.</p>
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

    GoodsReceiptServiceImpl(GoodsReceiptRepository receipts, GoodsReceiptSearch search,
                            ProductionOrderReceiving receiving, ReceivingLocations locations, InventoryService inventory,
                            WarehouseService warehouses, IdentityService identity,
                            InventoryControlService inventoryControl, UomConversionService units,
                            CurrentUserProvider users, ApplicationEventPublisher events, Clock clock) {
        this.receipts = receipts;
        this.search = search;
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
        // The caller's warehouse first, read without a lock: one scoped to another warehouse learns nothing of the
        // production order and holds no lock on it or on its sales order.
        ReceivableProductionOrder read = receiving.find(command.productionOrderId()).orElseThrow(() ->
                notFoundProduction(command.productionOrderId()));
        com.stockflow.common.security.WarehouseScope.requireWarehouse(read.receivingWarehouseId());
        ReceivableProductionOrder production = receiving.lock(command.productionOrderId()).orElseThrow(() ->
                notFoundProduction(command.productionOrderId()));
        requireReceivable(production);
        Instant now = clock.instant();
        GoodsReceipt saved = receipts.save(GoodsReceipt.productionDraft(Identifiers.newId(),
                receipts.nextNumber(LocalDate.ofInstant(now, BUSINESS_ZONE)), production.id(),
                production.receivingWarehouseId(), userId, blankToNull(command.deliveryNote()),
                blankToNull(command.note()), now));
        receiving.openReceipt(production.id(), saved.id(), saved.number());
        log.info("Receipt {} started against production order {}", saved.number(), production.code());
        return view(saved, production);
    }

    /**
     * {@inheritDoc}
     *
     * <p>kltn-docs 03 §4.1 step 2: the whole count, one line per production order line, each listing its cartons.
     * Locked as every receipt write is (see the class comment): the production order, then the receipt, then - through
     * inventory - the SKUs. Every line is checked before anything is refused, each refusal on its own field
     * ({@code lines[i].productionOrderLineId}, {@code .locationCode}, {@code .packages}, {@code .packages[j].quantity},
     * {@code .packages[j].lpn}, {@code .unitOfMeasure}, {@code .quantity}, {@code .lotNumber}, {@code .expiryDate},
     * {@code .sku}); {@code check_goods_receipt_line} checks the same again.</p>
     *
     * <p>BR-02 counts the cartons of every other receipt of the production order line that is not cancelled - drafts
     * included, as the documents say - under the production order's lock, so two counts cannot each see room the
     * other takes; {@code tg_goods_receipt_lines_production_cartons} counts the same again at the save. Every carton
     * holds the line's standard count (BR-13): a carton of another count is not listed, it goes back to the producer.
     * A line keeps its id across counts: stock packages labelled during receiving point at their receipt line
     * ({@code inventory.stock_package.goods_receipt_line_id}, V20261020000100) with a restricting foreign key, so a
     * fresh id on every recount would make a draft with labelled cartons impossible to correct. Only once the count is
     * saved - so a new line's row exists for the cartons' foreign key - does inventory label, recount, move and void
     * them; the response then carries their LPNs.</p>
     */
    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "goods-receipt", resourceId = "#receiptId")
    public ReceiptView replaceLines(UUID receiptId, List<NewLine> newLines) {
        GoodsReceipt read = load(receiptId);
        if (!read.againstProductionOrder()) {
            // A historical purchase receipt: the aggregate refuses it, naming why.
            read.replaceLines(List.of());
        }
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
        return view(saved, production);
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
     * that is the default shipping unit. Null, with the refusal recorded on {@code unitOfMeasure}, when none fits. A
     * carton of another unit is not kept while the line changes unit (SCRUM-473 design §3 rule 7).
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
        return post(receipt, production, userId);
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
        return view(saved, production);
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
     * A draft line that cartons were ever labelled on - VOID ones included - is not dropped from the count: the
     * cartons point at it with a restricting foreign key (V20261020000100), which would otherwise refuse the save as a
     * bare 409 naming no line. The way to drop such a line is to cancel the receipt and count it again.
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
                    ("Receipt %s: %d line(s) left out of the count have had labelled cartons; such a line stays"
                            + " on the draft - to drop it, cancel the receipt and count it again")
                            .formatted(receipt.number(), labelled.size()));
        }
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
     * <p>kltn-docs 03 §4.1 step 6. Locked as counting is - the production order (its sales order first), then the
     * receipt - and what counting checked is checked again, since another receipt may have been counted or confirmed
     * meanwhile: the receipt is a draft with lines, the production order can still receive goods, every line's cartons
     * hold its quantity in the standard count, and BR-02 holds. The goods are then counted in as INBOUND stock in their
     * cartons, in the production order's lot, and the production order moves on
     * ({@link ProductionOrderReceiving#recordReceipt}). No tolerance and no approval: what is over was refused while
     * counting.</p>
     *
     * <p>SCRUM-473: nothing here names a carton. {@code inventory.receive} finds the line's RECEIVING cartons by the
     * line's id and puts the goods into them - one INBOUND row each, IN_STOCK and still unopened.</p>
     */
    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "goods-receipt", resourceId = "#receiptId")
    public ReceiptView confirm(UUID receiptId, UUID userId) {
        GoodsReceipt receipt = load(receiptId);
        if (!receipt.againstProductionOrder()) {
            // A historical purchase receipt: the aggregate refuses it, naming why.
            receipt.confirm(userId, clock.instant());
        }
        return confirmProduction(receipt, userId);
    }

    /**
     * The count is final and the receipt has already moved on in the aggregate: save it, count the goods in as
     * INBOUND stock in the production order's lot, move the production order on and send the events.
     */
    private ReceiptView post(GoodsReceipt receipt, ReceivableProductionOrder production, UUID userId) {
        GoodsReceipt saved = receipts.save(receipt);

        Map<UUID, ReceivingLocation> where = locations.byIds(
                saved.lines().stream().map(ReceiptLine::locationId).toList());
        Map<UUID, InventoryItemPolicy> items = new HashMap<>();
        // 4A-5: the lot is the production order's - one per production order and SKU across its receipts (BR-03).
        ReceiptOrigin origin = ReceiptOrigin.ofProductionOrder(saved.id(), saved.number(), saved.warehouseId(),
                production.id(), production.code());
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
            confirmedLines.add(new GoodsReceiptConfirmed.Line(line.id(), line.productionOrderLineId(), item.sku(),
                    line.lotNumber(), line.receivedQuantity(), locationCode));
            // No quality check at the warehouse (kltn-docs 03 BR-07): every line goes on to putaway.
            readyLines.add(new ReceiptStockReadyForPutaway.Line(line.id(), item.sku(), line.lotNumber(),
                    locationCode, line.receivedQuantity()));
        }

        // 17 §4.3 step 11: the production order counts the cartons in and moves on, in this transaction.
        ReceivableProductionOrder after = receiving.recordReceipt(production.id(), saved.id(),
                cartonsPerProductionOrderLine(saved, production), userId, saved.confirmedAt());

        events.publishEvent(new GoodsReceiptConfirmed(saved.id(), saved.number(), production.id(),
                saved.warehouseId(), confirmedLines, saved.confirmedAt()));
        events.publishEvent(new ReceiptStockReadyForPutaway(saved.id(), saved.number(), saved.warehouseId(),
                readyLines));
        log.info("Confirmed receipt {} (against {}, production order now {}): {} line(s)", saved.number(),
                after.code(), after.status(), saved.lines().size());
        return view(saved, after);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A historical purchase receipt has no production order to let it go: it is cancelled under its own row lock,
     * and the cartons labelled while it was counted, which held no stock, are voided.</p>
     */
    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "goods-receipt", resourceId = "#receiptId")
    public ReceiptView cancel(UUID receiptId) {
        GoodsReceipt read = load(receiptId);
        if (read.againstProductionOrder()) {
            return cancelProduction(read);
        }
        GoodsReceipt receipt = loadForUpdate(receiptId);
        receipt.cancel();
        // Cartons labelled while counting held no stock and their labels are no longer valid (RECEIVING → VOID).
        inventory.voidStockPackagesOfReceiptLines(receipt.lines().stream().map(ReceiptLine::id).toList());
        GoodsReceipt saved = receipts.save(receipt);
        log.info("Historical purchase receipt {} cancelled", saved.number());
        return view(saved, null);
    }

    // ------------------------------------------------------------------ reads

    @Override
    @Transactional(readOnly = true)
    public ReceiptView get(UUID receiptId) {
        GoodsReceipt receipt = load(receiptId);
        return view(receipt, receipt.againstProductionOrder()
                ? receiving.find(receipt.productionOrderId()).orElse(null) : null);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<Row> list(List<GoodsReceiptStatus> statuses, UUID warehouseId, String text,
                                  Instant receivedFrom, Instant receivedTo, UUID productionOrderId,
                                  GoodsReceiptType receiptType, int page, int size, String sort) {
        var criteria = new GoodsReceiptSearch.Criteria(statuses, warehouseId, blankToNull(text), receivedFrom,
                receivedTo, productionOrderId, receiptType);
        var rows = search.search(criteria, Pages.of(page, size, SORT.parse(sort)));
        // SCRUM-464: one query each for the people and the production orders of the page.
        var names = identity.displayNames(rows.stream()
                .flatMap(r -> java.util.stream.Stream.of(r.receivedBy(), r.confirmedBy())).toList());
        var productionOrders = receiving.findAll(rows.stream().map(GoodsReceiptSearch.Row::productionOrderId)
                .filter(Objects::nonNull).distinct().toList());
        Map<UUID, Optional<WarehouseView>> where = new HashMap<>();
        return Pages.toResponse(rows.map(r -> {
            var production = r.productionOrderId() == null ? null : productionOrders.get(r.productionOrderId());
            var warehouse = where.computeIfAbsent(r.warehouseId(), warehouses::findWarehouse);
            return new Row(r.id(), r.number(), r.warehouseId(), warehouse.map(WarehouseView::prefix).orElse(null),
                    warehouse.map(WarehouseView::name).orElse(null), r.status(), r.deliveryNote(), r.receivedAt(),
                    r.receivedBy(), names.get(r.receivedBy()), r.confirmedAt(), r.confirmedBy(),
                    names.get(r.confirmedBy()), r.closedAt(), r.receiptType(), r.productionOrderId(),
                    production == null ? null : production.code(),
                    production == null ? null : production.producerName());
        }));
    }

    // ------------------------------------------------------------------ rules the aggregate cannot see

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

    // ------------------------------------------------------------------ view

    /**
     * The receipt as the screens show it. {@code production}: its production order (4A-5), which each line is shown
     * against - ordered, received so far, this receipt, what remains (03 §3 output, §4.1 step 3); null on a historical
     * purchase receipt.
     */
    private ReceiptView view(GoodsReceipt receipt, ReceivableProductionOrder production) {
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
        Map<UUID, String> names = identity.displayNames(people);
        // SCRUM-473: every line's cartons in one call, and every unit of the receipt's items in another - the line's
        // own unit is among them, and the counting screen offers the rest.
        Map<UUID, List<StockPackageView>> cartons = receipt.lines().isEmpty() ? Map.of()
                : inventory.receivingPackagesOf(receipt.lines().stream().map(ReceiptLine::id).toList());
        Map<String, List<UomConversion>> unitsBySku = items.isEmpty() ? Map.of()
                : units.conversionsOf(items.values().stream().map(i -> new Sku(i.sku())).toList());
        Map<UUID, String> unitCodes = new HashMap<>();
        unitsBySku.values().forEach(list -> list.forEach(u -> unitCodes.put(u.id(), u.unitOfMeasure())));
        // BR-06: a lot that expires soon after it arrives is flagged, never refused - only a historical purchase line
        // carries an expiry date. Counted from the day it was received, in the business's calendar; a cancelled
        // receipt counted nothing, so it flags nothing.
        boolean flags = receipt.status() != GoodsReceiptStatus.CANCELLED;
        LocalDate receivedOn = LocalDate.ofInstant(receipt.receivedAt(), BUSINESS_ZONE);
        int window = flags ? inventoryControl.expiryWarningDays() : 0;
        // A draft's cartons are not counted in yet: what remains on the production order line leaves them out.
        boolean pending = receipt.status() == GoodsReceiptStatus.DRAFT;
        // A draft's room on each line is what every other receipt not cancelled leaves - other drafts too, as BR-02
        // counts them - not only what was confirmed: a figure the hard stop would refuse is no figure to count to.
        Map<UUID, Integer> elsewhere = production == null || !pending ? Map.of()
                : receipts.unitsOnOtherProductionReceipts(receipt.lines().stream()
                        .map(ReceiptLine::productionOrderLineId).filter(Objects::nonNull).toList(), receipt.id());
        List<LineView> lines = receipt.lines().stream().map(line -> {
            InventoryItemPolicy item = items.get(line.inventoryItemId());
            List<String> lineWarnings = new ArrayList<>();
            if (flags && ExpiryWarning.isNear(line.expiryDate(), receivedOn, window)) {
                lineWarnings.add(ExpiryWarning.EXPIRY_NEAR);
            }
            List<PackageView> packages = cartons.getOrDefault(line.id(), List.of()).stream()
                    .map(c -> new PackageView(c.id(), c.lpn(), c.quantity() == null ? 0 : c.quantity(),
                            c.receivedQty() == null ? 0 : c.receivedQty(), c.nominalQty(), c.status(),
                            c.locationCode()))
                    .toList();
            // Design §3 rule 5: loose is what was not counted into a carton. Not what is in the cartons now - a
            // confirmed receipt is frozen (BR-05), and units picked from a carton later were never loose.
            int inCartons = packages.stream().mapToInt(PackageView::receivedQty).sum();
            return new LineView(line.id(), line.inventoryItemId(), item == null ? null : item.sku(),
                    line.receivedQuantity(), line.lotNumber(), line.expiryDate(), code(where, line.locationId()),
                    line.note(), item != null && item.lotTracked(), item != null && item.expiryTracked(),
                    line.receivedQuantity(), lineWarnings,
                    line.uomConversionId() == null ? null : unitCodes.get(line.uomConversionId()),
                    line.receivedQuantity() - inCartons, packages,
                    item == null ? List.of() : unitsBySku.getOrDefault(item.sku(), List.of()).stream()
                            .map(u -> new UnitView(u.unitOfMeasure(), u.factor(), u.base(), u.active(),
                                    u.defaultPurchase()))
                            .toList(),
                    line.productionOrderLineId(), production == null || line.productionOrderLineId() == null ? null
                            : production.line(line.productionOrderLineId())
                            .map(ordered -> progress(ordered, line, pending,
                                    elsewhere.getOrDefault(ordered.id(), 0))).orElse(null));
        }).toList();
        List<String> warnings = new ArrayList<>();
        if (lines.stream().anyMatch(l -> l.warnings().contains(ExpiryWarning.EXPIRY_NEAR))) {
            warnings.add(ExpiryWarning.EXPIRY_NEAR);
        }
        var warehouse = warehouses.findWarehouse(receipt.warehouseId());
        return new ReceiptView(receipt.id(), receipt.number(), receipt.warehouseId(),
                warehouse.map(WarehouseView::prefix).orElse(null), warehouse.map(WarehouseView::name).orElse(null),
                receipt.status(), receipt.deliveryNote(), receipt.note(), receipt.receivedAt(), receipt.receivedBy(),
                names.get(receipt.receivedBy()), receipt.confirmedAt(), receipt.confirmedBy(),
                names.get(receipt.confirmedBy()), receipt.closedAt(), lines, warnings, receipt.type(),
                receipt.productionOrderId(), production == null ? null : production.code(),
                production == null ? null : production.status(), production == null ? null : production.producerId(),
                production == null ? null : production.producerName());
    }

    /**
     * A production line against what its production order line orders, in cartons and units (4A-5). A draft's remaining
     * cartons leave out {@code unitsElsewhere}, what other receipts not cancelled hold of the line (confirmed or draft);
     * a confirmed receipt's are what the production order still awaits.
     */
    private static ProductionLineProgress progress(ReceivableProductionOrder.Line ordered, ReceiptLine line,
                                                   boolean pending, int unitsElsewhere) {
        int per = ordered.unitsPerCarton();
        int here = line.receivedQuantity() / per;
        int remaining = Math.max(pending ? ordered.cartons() - unitsElsewhere / per - here
                : ordered.cartons() - ordered.receivedCartons(), 0);
        return new ProductionLineProgress(per, ordered.cartons(), ordered.receivedCartons(), here, remaining,
                ordered.cartons() * per, ordered.receivedCartons() * per, here * per, remaining * per);
    }

    private static String code(Map<UUID, ReceivingLocation> where, UUID id) {
        return id == null || !where.containsKey(id) ? null : where.get(id).code();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BusinessException notFoundProduction(UUID id) {
        return new BusinessException(ErrorCode.PRODUCTION_ORDER_NOT_FOUND, "No production order " + id);
    }
}
