package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.contracts.ReturnedGoodsReceived;
import com.stockflow.fulfillment.api.ShipmentCartons;
import com.stockflow.fulfillment.api.ShippedCarton;
import com.stockflow.fulfillment.api.ShippedCartons;
import com.stockflow.inventory.api.CartonReturns;
import com.stockflow.inventory.api.ReceiveReturnedCartonsCommand;
import com.stockflow.inventory.api.ReturnedCarton;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ReturnRequestLookup;
import com.stockflow.order.api.ReturnRequestReference;
import com.stockflow.procurement.internal.domain.GoodsReceiptRepository;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.domain.ReturnReceipt;
import com.stockflow.procurement.internal.domain.ReturnReceiptRepository;
import com.stockflow.procurement.internal.repository.ReturnReceiptSearch;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Return receipts (team plan 4D-2; kltn-docs 03 §4.2 steps 9-12, BR-03, BR-14; 17 BR-18; 09 BR-06): goods that came
 * back are taken in through a receipt, never added to stock directly. The warehouse opens one for a shipment the
 * carrier brought back or an approved return request, at the warehouse the goods left from; scans each carton by LPN,
 * and only a carton that left on that shipment (or was delivered on that order) is taken; confirms, and in that one
 * transaction every carton is back in stock in quarantine ({@code inventory :: api} {@link CartonReturns}), the
 * receipt is CLOSED, and {@link ReturnedGoodsReceived} goes out after commit for the order and payment to act on.
 *
 * <p>What it is against is read from the modules that own it - the shipment and its cartons from
 * {@code fulfillment :: api}, the return request from {@code order :: api} - and checked again by the database
 * ({@code check_return_receipt}, {@code check_return_receipt_carton}). Every step is in the caller's warehouses
 * (BR-SEC-002), checked before anything else is said about the receipt or its source.</p>
 *
 * <p>A return request is against one delivered shipment (WF-05 TH-1): its receipt takes only that shipment's cartons,
 * and lists them as what to expect. A request of an order delivered without shipments takes any carton delivered on
 * the order, as before. Its goods may come in several parcels (WF-05 TH-3): while the request still awaits part of
 * them, each parcel is received on a receipt of its own - one draft at a time - and a carton that came back on one is
 * never taken again by the next (it is no longer CONSUMED).</p>
 *
 * <p>What is inside is counted (WF-05 TH-4): each carton keeps the units found in it - fewer than its label, it came
 * back opened, goes into quarantine whole as every carton does, is marked so in inventory and is only ever written
 * off - and units with no carton label are loose lines, recorded on the receipt and never put into stock. The return
 * is refunded for what was counted.</p>
 *
 * <p>Lock order: the receipt row ({@code FOR UPDATE}), then - on confirmation, inside inventory - the SKU advisory
 * locks, the cartons, their rows: the global order (receipt before SKU locks before cartons before stock rows).</p>
 */
@Service
@Transactional
public class ReturnReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ReturnReceiptService.class);

    /** Receipt numbers follow the business's calendar, as every receipt's does. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final SortWhitelist SORT = SortWhitelist.of("receivedAt", "receiptNumber", "status")
            .withDefault("receivedAt", Sort.Direction.DESC);

    /** What the warehouse asks for: one source, the quarantine area, a note. */
    public record Create(UUID shipmentId, UUID returnRequestId, String quarantineLocationCode, String note) {
    }

    /**
     * A carton on the receipt, as it was shipped, with the condition noted when it was scanned and the units found in
     * it ({@code countedQuantity}); {@code opened} when fewer than its label - it is only ever written off.
     */
    public record CartonView(String lpn, String sku, String lotNumber, int quantity, String condition, UUID scannedBy,
                             Instant scannedAt, int countedQuantity, boolean opened) {
    }

    /** Units that came back with no carton label: recorded, never put into stock (WF-05 TH-4). */
    public record LooseLineView(UUID id, String sku, String lotNumber, int quantity, UUID recordedBy) {
    }

    /**
     * A carton the source carried (03 §4.2 step 11: what was scanned against what was shipped), {@code scanned} when
     * it is on the receipt.
     */
    public record ExpectedCarton(String lpn, String sku, String lotNumber, int quantity, boolean scanned) {
    }

    /** One return receipt in full: its source, its cartons, and what its source carried. */
    public record ReturnReceiptView(UUID id, String number, String receiptType, GoodsReceiptStatus status,
                                    UUID warehouseId, UUID orderId, String orderNumber, UUID shipmentId,
                                    String shipmentCode, UUID returnRequestId, String rmaNumber,
                                    UUID quarantineLocationId, String quarantineLocationCode, String note,
                                    Instant receivedAt, UUID receivedBy, Instant confirmedAt, UUID confirmedBy,
                                    Instant closedAt, List<CartonView> cartons, List<ExpectedCarton> expectedCartons,
                                    long version, List<LooseLineView> looseLines) {
    }

    /** One receipt in the list: its source by id, and how many cartons it lists. */
    public record ReturnReceiptRow(UUID id, String number, String receiptType, GoodsReceiptStatus status,
                                   UUID warehouseId, UUID orderId, UUID shipmentId, UUID returnRequestId,
                                   Instant receivedAt, UUID receivedBy, Instant closedAt, int cartonCount) {
    }

    private final ReturnReceiptRepository receipts;
    private final ReturnReceiptSearch search;
    private final GoodsReceiptRepository numbering;
    private final ShipmentCartons shipments;
    private final ReturnRequestLookup returnRequests;
    private final OrderService orders;
    private final CartonReturns cartonReturns;
    private final WarehouseService warehouses;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ReturnReceiptService(ReturnReceiptRepository receipts, ReturnReceiptSearch search,
                                GoodsReceiptRepository numbering, ShipmentCartons shipments,
                                ReturnRequestLookup returnRequests, OrderService orders, CartonReturns cartonReturns,
                                WarehouseService warehouses, ApplicationEventPublisher events, Clock clock) {
        this.receipts = receipts;
        this.search = search;
        this.numbering = numbering;
        this.shipments = shipments;
        this.returnRequests = returnRequests;
        this.orders = orders;
        this.cartonReturns = cartonReturns;
        this.warehouses = warehouses;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ opening

    /**
     * Opens a return receipt (DRAFT) for one returned shipment or one approved return request (03 §4.2 step 9), at the
     * warehouse the goods left from, into one of its QUARANTINE areas.
     *
     * @throws BusinessException {@code VALIDATION_FAILED} (neither or both sources); {@code SHIPMENT_NOT_FOUND},
     *         {@code RETURN_REQUEST_NOT_FOUND}; 403 for a source of another warehouse; {@code SHIPMENT_NOT_RETURNED};
     *         {@code RETURN_NOT_AWAITING_GOODS}; {@code RETURN_RECEIPT_ALREADY_OPEN}; {@code LOCATION_NOT_FOUND},
     *         {@code LOCATION_NOT_USABLE}, {@code LOCATION_AREA_MISMATCH} (not a QUARANTINE area of that warehouse)
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "return-receipt", resourceId = "#result?.id()")
    public ReturnReceiptView create(Create command, UUID userId) {
        if ((command.shipmentId() == null) == (command.returnRequestId() == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A return receipt is against one returned shipment or one return request",
                    List.of(FieldError.of("shipmentId", "name a returned shipment or a return request, not both"),
                            FieldError.of("returnRequestId", "name a returned shipment or a return request, not both")));
        }
        Instant now = clock.instant();
        ReturnReceipt receipt;
        if (command.shipmentId() != null) {
            ShippedCartons shipment = shipments.ofShipment(command.shipmentId()).orElseThrow(() ->
                    new BusinessException(ErrorCode.SHIPMENT_NOT_FOUND, "No shipment " + command.shipmentId()));
            // BR-SEC-002: the goods come back to the warehouse they left from, received by its staff.
            WarehouseScope.requireWarehouse(shipment.warehouseId());
            if (!shipment.returned()) {
                throw new BusinessException(ErrorCode.SHIPMENT_NOT_RETURNED, ("Shipment %s is %s; goods are received "
                        + "back once the carrier has returned it (09 BR-06)").formatted(shipment.code(),
                        shipment.status()));
            }
            receipts.findLiveForShipment(shipment.shipmentId()).ifPresent(ReturnReceiptService::alreadyOpen);
            UUID area = quarantineArea(command.quarantineLocationCode(), shipment.warehouseId()).id();
            receipt = ReturnReceipt.forShipment(Identifiers.newId(), nextNumber(now), shipment.warehouseId(),
                    shipment.orderId(), shipment.shipmentId(), area, userId, command.note(), now);
        } else {
            ReturnRequestReference request = returnRequests.find(command.returnRequestId()).orElseThrow(() ->
                    new BusinessException(ErrorCode.RETURN_REQUEST_NOT_FOUND,
                            "No return request " + command.returnRequestId()));
            UUID warehouseId = order(request.orderId()).warehouseId();
            if (warehouseId == null) {
                throw new BusinessException(ErrorCode.RETURN_NOT_AWAITING_GOODS,
                        "Order %s never left a warehouse; nothing of it comes back".formatted(request.orderNumber()));
            }
            WarehouseScope.requireWarehouse(warehouseId);
            if (!request.awaitingGoods()) {
                throw new BusinessException(ErrorCode.RETURN_NOT_AWAITING_GOODS, ("Return request %s is %s; its goods "
                        + "are received while it is approved and awaiting them").formatted(request.rmaNumber(),
                        request.status()));
            }
            // WF-05 TH-3: a further parcel of the return gets a receipt of its own; one is counted at a time.
            receipts.findDraftForReturnRequest(request.id()).ifPresent(ReturnReceiptService::alreadyOpen);
            UUID area = quarantineArea(command.quarantineLocationCode(), warehouseId).id();
            receipt = ReturnReceipt.forReturnRequest(Identifiers.newId(), nextNumber(now), warehouseId,
                    request.orderId(), request.id(), area, userId, command.note(), now);
        }
        ReturnReceipt saved = receipts.save(receipt);
        log.info("Return receipt {} opened for order {} ({})", saved.number(), saved.orderId(),
                saved.shipmentId() != null ? "shipment " + saved.shipmentId() : "return request " + saved.returnRequestId());
        return view(saved);
    }

    // ------------------------------------------------------------------ scanning

    /**
     * A carton scanned by LPN (03 §4.2 step 10): taken only when it left on the receipt's shipment - or, for a return
     * request, was delivered on its order - and has not come back already. It keeps its LPN, SKU, count and lot;
     * {@code condition} notes its outside. The same LPN again is the same carton.
     *
     * @throws BusinessException {@code GOODS_RECEIPT_NOT_FOUND}; 403; {@code VALIDATION_FAILED} (no LPN);
     *         {@code INVALID_RECEIPT_TRANSITION} (not a draft); {@code RETURN_CARTON_NOT_OF_SOURCE};
     *         {@code STOCK_PACKAGE_NOT_FOUND}; {@code RETURN_CARTON_IN_WAREHOUSE}
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "return-receipt", resourceId = "#receiptId",
            details = "{lpn: #lpn}")
    public ReturnReceiptView scan(UUID receiptId, String lpn, String condition, UUID userId) {
        return scan(receiptId, lpn, condition, null, userId);
    }

    /**
     * As {@link #scan(UUID, String, String, UUID)}, with the units found inside the carton (WF-05 TH-4):
     * {@code countedQuantity} null takes it as sealed - its whole label - on the first scan and keeps what was counted
     * on a later one; fewer than its label records it opened. Never above the label ({@code VALIDATION_FAILED}).
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "return-receipt", resourceId = "#receiptId",
            details = "{lpn: #lpn, counted: #countedQuantity}")
    public ReturnReceiptView scan(UUID receiptId, String lpn, String condition, Integer countedQuantity,
                                  UUID userId) {
        ReturnReceipt receipt = lock(receiptId);
        String normal = StockPackageView.normalLpn(lpn);
        if (normal == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Scan a carton's LPN",
                    List.of(FieldError.of("lpn", "must not be blank")));
        }
        receipt.requireScannable();
        Optional<ReturnReceipt.Carton> already = receipt.carton(normal);
        if (already.isEmpty()) {
            if (sourceCartons(receipt).stream().noneMatch(c -> normal.equals(StockPackageView.normalLpn(c.lpn())))) {
                throw new BusinessException(ErrorCode.RETURN_CARTON_NOT_OF_SOURCE, ("Carton %s did not leave on %s; "
                        + "only its own cartons are taken back (03 BR-14)").formatted(normal, sourceName(receipt)),
                        List.of(), Map.of("lpn", normal));
            }
            ReturnedCarton carton = cartonReturns.find(normal).orElseThrow(() -> new BusinessException(
                    ErrorCode.STOCK_PACKAGE_NOT_FOUND, "No stock package " + normal));
            if (!"CONSUMED".equals(carton.status())) {
                throw new BusinessException(ErrorCode.RETURN_CARTON_IN_WAREHOUSE, ("Carton %s is %s at %s; only a "
                        + "carton that left the warehouse comes back").formatted(normal, carton.status(),
                        carton.locationCode()), List.of(), Map.of("lpn", normal));
            }
            receipt.scan(new ReturnReceipt.Carton(Identifiers.newId(), carton.stockPackageId(), carton.lpn(),
                    carton.sku(), carton.lotNumber(), carton.quantity(), condition, userId, clock.instant(),
                    countedQuantity == null ? carton.quantity() : countedQuantity), countedQuantity != null);
        } else {
            ReturnReceipt.Carton held = already.get();
            receipt.scan(new ReturnReceipt.Carton(held.id(), held.stockPackageId(), held.lpn(), held.sku(),
                    held.lotNumber(), held.quantity(), condition, held.scannedBy(), held.scannedAt(),
                    countedQuantity == null ? held.countedQuantity() : countedQuantity), countedQuantity != null);
        }
        return view(receipts.save(receipt));
    }

    /**
     * Units that came back with no carton label (WF-05 TH-4): their SKU - one of the order's - their lot when it can
     * be read, how many. Recorded on a return request's receipt only, never put into stock (they are destroyed), and
     * received for the return. The same SKU and lot again replaces the count.
     *
     * @throws BusinessException {@code GOODS_RECEIPT_NOT_FOUND}; 403; {@code VALIDATION_FAILED} (no SKU, no unit, a SKU
     *         the order does not have); {@code INVALID_RECEIPT_TRANSITION} (not a draft, or a receipt of a shipment
     *         the carrier brought back)
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "return-receipt", resourceId = "#receiptId",
            details = "{loose: #sku, quantity: #quantity}")
    public ReturnReceiptView recordLoose(UUID receiptId, String sku, String lotNumber, int quantity, UUID userId) {
        ReturnReceipt receipt = lock(receiptId);
        String code = sku == null ? null : sku.strip();
        if (code == null || code.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Name the SKU of the loose units",
                    List.of(FieldError.of("sku", "must not be blank")));
        }
        boolean ofOrder = order(receipt.orderId()).lines().stream().anyMatch(line -> code.equals(line.sku()));
        if (!ofOrder) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("%s is not a SKU of the order: only its own "
                    + "goods are taken back (03 BR-14)").formatted(code), List.of(FieldError.of("sku",
                    "not a SKU of the order")));
        }
        receipt.recordLoose(new ReturnReceipt.LooseLine(Identifiers.newId(), code, lotNumber, quantity, userId));
        return view(receipts.save(receipt));
    }

    /** Loose units recorded by mistake come off the draft. */
    @Auditable(action = AuditAction.UPDATE, resourceType = "return-receipt", resourceId = "#receiptId",
            details = "{removedLoose: #lineId}")
    public ReturnReceiptView removeLoose(UUID receiptId, UUID lineId) {
        ReturnReceipt receipt = lock(receiptId);
        receipt.removeLoose(lineId);
        return view(receipts.save(receipt));
    }

    /** A carton scanned by mistake comes off the draft. */
    @Auditable(action = AuditAction.UPDATE, resourceType = "return-receipt", resourceId = "#receiptId",
            details = "{removed: #lpn}")
    public ReturnReceiptView removeCarton(UUID receiptId, String lpn) {
        ReturnReceipt receipt = lock(receiptId);
        String normal = StockPackageView.normalLpn(lpn);
        if (normal == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Name the carton's LPN",
                    List.of(FieldError.of("lpn", "must not be blank")));
        }
        receipt.remove(normal);
        return view(receipts.save(receipt));
    }

    // ------------------------------------------------------------------ deciding

    /**
     * Confirms the receipt (03 §4.2 step 12): every carton on it comes back into stock in its QUARANTINE area, the
     * receipt is CLOSED, and {@link ReturnedGoodsReceived} is published after commit. Confirmed again, it answers as it
     * is.
     *
     * @throws BusinessException {@code INVALID_RECEIPT_TRANSITION} (cancelled, or no carton scanned); as
     *         {@link CartonReturns#receive} - {@code INVALID_STOCK_PACKAGE_TRANSITION} when a carton came back on
     *         another receipt meanwhile
     */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "return-receipt", resourceId = "#receiptId",
            details = "{to: 'CLOSED'}")
    public ReturnReceiptView confirm(UUID receiptId, UUID userId) {
        ReturnReceipt receipt = lock(receiptId);
        if (receipt.status() == GoodsReceiptStatus.CLOSED) {
            return view(receipt);
        }
        Instant now = clock.instant();
        receipt.confirm(userId, now);
        String area = location(receipt.quarantineLocationId()).locationCode();
        // Every carton goes into quarantine whole; one counted short is marked opened there, never to be released.
        List<ReturnedCarton> received = receipt.cartons().isEmpty() ? List.of()
                : cartonReturns.receive(new ReceiveReturnedCartonsCommand(receipt.id(), receipt.orderId(), area,
                        receipt.cartons().stream().map(ReturnReceipt.Carton::lpn).toList(), userId, now,
                        receipt.cartons().stream().filter(ReturnReceipt.Carton::opened)
                                .map(ReturnReceipt.Carton::lpn).collect(Collectors.toSet())));
        ReturnReceipt saved = receipts.save(receipt);
        OrderSummary order = order(saved.orderId());
        Map<String, ReturnReceipt.Carton> scanned = saved.cartons().stream()
                .collect(Collectors.toMap(ReturnReceipt.Carton::lpn, c -> c));
        events.publishEvent(new ReturnedGoodsReceived(saved.id(), saved.number(), saved.warehouseId(), saved.orderId(),
                order.orderNumber(), order.customerId(), saved.shipmentId(), saved.returnRequestId(), area,
                received.stream().map(c -> new ReturnedGoodsReceived.Carton(c.lpn(), c.sku(), c.lotNumber(),
                        c.quantity(), scanned.get(c.lpn()).condition(), scanned.get(c.lpn()).opened()
                        ? scanned.get(c.lpn()).countedQuantity() : null)).toList(), userId, now,
                saved.looseLines().stream().map(l -> new ReturnedGoodsReceived.LooseLine(l.sku(), l.lotNumber(),
                        l.quantity())).toList()));
        log.info("Return receipt {} confirmed and closed: {} carton(s) ({} opened) and {} loose line(s) of order {} "
                        + "into quarantine at {}", saved.number(), received.size(),
                saved.cartons().stream().filter(ReturnReceipt.Carton::opened).count(), saved.looseLines().size(),
                order.orderNumber(), area);
        return view(saved);
    }

    /** Cancels a draft (03 BR-05): nothing was received. Cancelled again, it answers as it is. */
    @Auditable(action = AuditAction.TRANSITION, resourceType = "return-receipt", resourceId = "#receiptId",
            details = "{to: 'CANCELLED'}")
    public ReturnReceiptView cancel(UUID receiptId) {
        ReturnReceipt receipt = lock(receiptId);
        if (receipt.status() != GoodsReceiptStatus.CANCELLED) {
            receipt.cancel();
            receipt = receipts.save(receipt);
        }
        return view(receipt);
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public ReturnReceiptView get(UUID receiptId) {
        ReturnReceipt receipt = receipts.findById(receiptId).orElseThrow(() -> notFound(receiptId));
        WarehouseScope.requireWarehouse(receipt.warehouseId());
        return view(receipt);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReturnReceiptRow> list(List<GoodsReceiptStatus> statuses, UUID warehouseId,
                                                      UUID orderId, UUID shipmentId, UUID returnRequestId,
                                                      String number, int page, int size, String sort) {
        if (warehouseId != null) {
            WarehouseScope.requireWarehouse(warehouseId);
        }
        return Pages.toResponse(search.search(new ReturnReceiptSearch.Criteria(statuses, warehouseId, orderId,
                shipmentId, returnRequestId, number == null || number.isBlank() ? null : number.strip()),
                Pages.of(page, size, SORT.parse(sort))).map(r -> new ReturnReceiptRow(r.id(), r.number(), "RETURN",
                r.status(), r.warehouseId(), r.orderId(), r.shipmentId(), r.returnRequestId(), r.receivedAt(),
                r.receivedBy(), r.closedAt(), r.cartonCount())));
    }

    // ------------------------------------------------------------------ helpers

    private ReturnReceipt lock(UUID receiptId) {
        ReturnReceipt receipt = receipts.findByIdForUpdate(receiptId).orElseThrow(() -> notFound(receiptId));
        WarehouseScope.requireWarehouse(receipt.warehouseId());
        return receipt;
    }

    private static BusinessException notFound(UUID receiptId) {
        return new BusinessException(ErrorCode.GOODS_RECEIPT_NOT_FOUND, "No return receipt " + receiptId);
    }

    private static void alreadyOpen(ReturnReceipt open) {
        throw new BusinessException(ErrorCode.RETURN_RECEIPT_ALREADY_OPEN, ("Return receipt %s (%s) is already open "
                + "for these goods; confirm or cancel it first").formatted(open.number(), open.status()), List.of(),
                Map.of("receiptId", open.id(), "receiptNumber", open.number()));
    }

    private String nextNumber(Instant now) {
        return numbering.nextNumber(LocalDate.ofInstant(now, BUSINESS_ZONE));
    }

    /** A usable QUARANTINE area of the warehouse (03 BR-14). */
    private StorageLocationView quarantineArea(String code, UUID warehouseId) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Choose the quarantine area the goods go into",
                    List.of(FieldError.of("quarantineLocationCode", "must not be blank")));
        }
        StorageLocationView area = warehouses.findLocation(code.strip()).orElseThrow(() ->
                new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + code));
        if (!warehouseId.equals(area.warehouseId()) || area.areaType() != StorageLocationView.AreaType.QUARANTINE) {
            throw new BusinessException(ErrorCode.LOCATION_AREA_MISMATCH, ("%s is not a QUARANTINE area of the "
                    + "warehouse the goods left from; returned goods go into one (03 BR-14)").formatted(code));
        }
        if (!area.usable()) {
            throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                    "%s cannot take stock now: it or its warehouse is not active".formatted(code));
        }
        return area;
    }

    private StorageLocationView location(UUID locationId) {
        return warehouses.findLocation(locationId).orElseThrow(() ->
                new IllegalStateException("No location " + locationId));
    }

    private OrderSummary order(UUID orderId) {
        return orders.findById(orderId).orElseThrow(() ->
                new BusinessException(ErrorCode.NOT_FOUND, "No order " + orderId));
    }

    /**
     * The cartons the receipt's source carried: the shipment's; for a return request, those of the delivered shipment
     * it is against (WF-05 TH-1) - or, for an order delivered without shipments, those delivered on the order.
     */
    private List<ShippedCarton> sourceCartons(ReturnReceipt receipt) {
        if (receipt.shipmentId() != null) {
            return shipments.ofShipment(receipt.shipmentId()).map(ShippedCartons::cartons).orElse(List.of());
        }
        return requestCartons(returnRequests.find(receipt.returnRequestId()), receipt.orderId());
    }

    /** What a return request takes back: its shipment's cartons, else every carton delivered on its order. */
    private List<ShippedCarton> requestCartons(Optional<ReturnRequestReference> request, UUID orderId) {
        UUID against = request.map(ReturnRequestReference::shipmentId).orElse(null);
        if (against != null) {
            return shipments.ofShipment(against).map(ShippedCartons::cartons).orElse(List.of());
        }
        return shipments.deliveredToOrder(orderId);
    }

    private String sourceName(ReturnReceipt receipt) {
        if (receipt.shipmentId() != null) {
            return shipments.ofShipment(receipt.shipmentId()).map(s -> "shipment " + s.code())
                    .orElse("its shipment");
        }
        Optional<ReturnRequestReference> request = returnRequests.find(receipt.returnRequestId());
        UUID against = request.map(ReturnRequestReference::shipmentId).orElse(null);
        if (against != null) {
            return shipments.ofShipment(against).map(s -> "shipment " + s.code() + " the return is against")
                    .orElse("the shipment the return is against");
        }
        return request.map(r -> "order " + r.orderNumber()).orElse("its order");
    }

    private ReturnReceiptView view(ReturnReceipt receipt) {
        String shipmentCode = null;
        List<ShippedCarton> expected;
        String rmaNumber = null;
        if (receipt.shipmentId() != null) {
            Optional<ShippedCartons> shipment = shipments.ofShipment(receipt.shipmentId());
            shipmentCode = shipment.map(ShippedCartons::code).orElse(null);
            expected = shipment.map(ShippedCartons::cartons).orElse(List.of());
        } else {
            Optional<ReturnRequestReference> request = returnRequests.find(receipt.returnRequestId());
            rmaNumber = request.map(ReturnRequestReference::rmaNumber).orElse(null);
            expected = requestCartons(request, receipt.orderId());
        }
        Set<String> scanned = receipt.cartons().stream().map(ReturnReceipt.Carton::lpn).collect(Collectors.toSet());
        String orderNumber = orders.findById(receipt.orderId()).map(OrderSummary::orderNumber).orElse(null);
        String areaCode = warehouses.findLocation(receipt.quarantineLocationId())
                .map(StorageLocationView::locationCode).orElse(null);
        return new ReturnReceiptView(receipt.id(), receipt.number(), "RETURN", receipt.status(), receipt.warehouseId(),
                receipt.orderId(), orderNumber, receipt.shipmentId(), shipmentCode, receipt.returnRequestId(),
                rmaNumber, receipt.quarantineLocationId(), areaCode, receipt.note(), receipt.receivedAt(),
                receipt.receivedBy(), receipt.confirmedAt(), receipt.confirmedBy(), receipt.closedAt(),
                receipt.cartons().stream().map(c -> new CartonView(c.lpn(), c.sku(), c.lotNumber(), c.quantity(),
                        c.condition(), c.scannedBy(), c.scannedAt(), c.countedQuantity(), c.opened())).toList(),
                expected.stream().map(c -> new ExpectedCarton(c.lpn(), c.sku(), c.lotNumber(), c.quantity(),
                        scanned.contains(StockPackageView.normalLpn(c.lpn())))).toList(),
                receipt.version(),
                receipt.looseLines().stream().map(l -> new LooseLineView(l.id(), l.sku(), l.lotNumber(), l.quantity(),
                        l.recordedBy())).toList());
    }
}
