package com.stockflow.order.internal.service;

import com.stockflow.inventory.api.InventoryService;
import com.stockflow.customer.api.CheckoutCustomer;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationOutcome;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.ListOrdersQuery;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatusChange;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderFee;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderAddressSnapshot;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.CancellationRequest;
import com.stockflow.order.internal.domain.CancellationRequestRepository;
import com.stockflow.order.internal.domain.SalesFollowUps;
import java.math.BigDecimal;
import com.stockflow.order.internal.repository.OrderSearchRepository;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import java.util.UUID;
import com.stockflow.common.id.Identifiers;
import com.stockflow.design.api.DesignService;
import com.stockflow.order.internal.entity.OrderHoldJpaEntity;
import com.stockflow.order.internal.entity.OrderRemainderClosureJpaEntity;
import com.stockflow.order.internal.repository.OrderHoldJpaRepository;
import com.stockflow.order.internal.repository.OrderRemainderClosureJpaRepository;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Order orchestration — and <b>the single clearest illustration of what this architecture buys</b>.
 *
 * <h2>Read {@link #cancel} against its distributed equivalent</h2>
 *
 * <p>Checkout, the operation this class was first written around, is gone (team plan 5-2, 4B-10): an order is drawn
 * up from an accepted quote and holds stock once its shipping warehouse is chosen. What it illustrated still holds for
 * every operation here that touches an order and its stock together. In the microservices build placing an order
 * spanned two services and needed all of the following, none of which is business logic:</p>
 * <ol>
 *   <li>write the order row plus an {@code outbox_event} row in one local transaction;</li>
 *   <li>a relay thread polling the outbox and publishing {@code OrderPlaced} to Kafka;</li>
 *   <li>an inventory consumer, made idempotent because Kafka redelivers on any consumer restart;</li>
 *   <li>inventory publishing {@code StockReserved} or {@code StockReservationFailed} back;</li>
 *   <li>an order consumer for both, moving the order forward or cancelling it;</li>
 *   <li>a saga state row, because between (1) and (5) the order sits in a status that exists only
 *       to represent "I have asked and not yet heard back";</li>
 *   <li>a timeout, because (5) may never arrive;</li>
 *   <li>a compensating release, for a reservation that succeeded after the order had already been
 *       cancelled by that timeout.</li>
 * </ol>
 *
 * <p>Roughly 600 lines across two services, four Kafka topics, and a class of bug — the customer
 * paid, the stock was never held — that only shows up under load. Here the same kind of operation is one
 * method, and the failure mode is gone: if anything throws, the transaction rolls back and the order and its
 * stock holds change together or not at all.</p>
 *
 * <h2>What was given up</h2>
 *
 * <p>Order and inventory now deploy together and share a database, so inventory cannot be scaled
 * or released independently, and a runaway query in one module can starve the other's connections.
 * For a five-person team shipping a capstone in one semester that is a trade worth making — and
 * the module boundaries are kept enforceable so the trade can be reversed later, module by module,
 * rather than requiring a rewrite. {@code docs/adr/0005-modular-monolith.md} records the decision
 * in full.</p>
 */
@Service
@Transactional
class OrderServiceImpl implements OrderService, QuotedOrders {

    private static final java.util.ResourceBundle VIETNAMESE = java.util.ResourceBundle.getBundle("i18n.messages",
            java.util.Locale.forLanguageTag("vi"));

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    /** The days a coordinator filters by are Saigon days: "placed on the 8th" ends at 17:00 UTC. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** {@code ordering.order_hold.reason} of an order over its customer's credit limit (SCRUM-427). */
    static final String CREDIT_HOLD = "CREDIT";

    /** {@code ordering.order_hold.reason} of an order a shipment of which came back (WF-05 fix plan, section 2). */
    static final String DELIVERY_FAILED_HOLD = com.stockflow.order.api.HoldReason.DELIVERY_FAILED.name();

    private static final SortWhitelist LIST_SORT = SortWhitelist.of("orderNumber", "placedAt", "totalAmount", "status")
            .withDefault("placedAt", Sort.Direction.DESC);

    private final OrderRepository repository;
    private final OrderSearchRepository search;
    private final InventoryService inventory;
    private final OrderEventPublisher events;
    private final Clock clock;
    private final DesignService designs;
    private final OrderHoldJpaRepository holds;
    private final CustomerService customers;
    private final CancellationRequestRepository cancellationRequests;
    private final com.stockflow.payment.api.PaymentService paymentService;
    private final com.stockflow.product.api.CustomerSkus customerSkus;
    private final com.stockflow.order.internal.domain.SalesContractRepository contracts;
    private final DeliveryBatchService batches;
    private final com.stockflow.order.internal.domain.DeliveryBatchRecords batchRecords;
    private final com.stockflow.order.internal.domain.DeliveryFailureCases deliveryFailures;
    /** Fulfillment's answer to which units are labelled and not handed over (order :: spi); absent in a module test. */
    private final org.springframework.beans.factory.ObjectProvider<com.stockflow.order.spi.LabelledNotHandedOver> labelled;
    /** Every closure of the rest of an order, one row each (WF4-CLOSE-HIST-SCHEMA). */
    private final OrderRemainderClosureJpaRepository remainderClosures;
    /**
     * WF-05 BE-37: the returns, followed when closing the rest delivers the order. Looked up, not injected: the return
     * service reaches this one through the delivery failures, and a constructor cycle would not start.
     */
    private org.springframework.beans.factory.ObjectProvider<ReturnRequestService> returnRequests;

    OrderServiceImpl(OrderRepository repository, OrderSearchRepository search,
                     InventoryService inventory, OrderEventPublisher events, Clock clock,
            DesignService designs,
            OrderHoldJpaRepository holds,
                     CustomerService customers,
                     CancellationRequestRepository cancellationRequests,
                     com.stockflow.payment.api.PaymentService paymentService,
                     com.stockflow.product.api.CustomerSkus customerSkus,
                     com.stockflow.order.internal.domain.SalesContractRepository contracts,
                     DeliveryBatchService batches,
                     com.stockflow.order.internal.domain.DeliveryBatchRecords batchRecords,
                     com.stockflow.order.internal.domain.DeliveryFailureCases deliveryFailures,
                     org.springframework.beans.factory.ObjectProvider<com.stockflow.order.spi.LabelledNotHandedOver>
                             labelled,
                     OrderRemainderClosureJpaRepository remainderClosures) {
        this.remainderClosures = remainderClosures;
        this.deliveryFailures = deliveryFailures;
        this.labelled = labelled;
        this.batches = batches;
        this.batchRecords = batchRecords;
        this.contracts = contracts;
        this.customerSkus = customerSkus;
        this.repository = repository;
        this.search = search;
        this.inventory = inventory;
        this.events = events;
        this.clock = clock;
        this.designs = designs;
        this.holds = holds;
        this.customers = customers;
        this.cancellationRequests = cancellationRequests;
        this.paymentService = paymentService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every line is placed at the price on the command, which the quote service computed from the accepted quote,
     * and the order is a DRAFT for its contract: no stock is held and no terms are applied here. The order rows and
     * their history commit with the quote's conversion, in the caller's transaction. The only way an order is created
     * since checkout went (team plan 5-2, kltn-docs 14 BR-01, BR-02) and {@code placeOrder} with it (4B-10).</p>
     *
     * <p>It is also where a customer's SKU gets its inventory item (owner decision Q5 of 2026-11-26): the design
     * approval issues the SKU with none, and only goods an order names are ever received, held, counted and shipped.
     * The items are opened in this same transaction ({@code InventoryService.openItems}), packed as the catalogue
     * variant each SKU was drawn on, so an order never exists whose SKU inventory does not know, and a conversion that
     * rolls back leaves no item behind. A SKU ordered before keeps its item. A retried conversion returns the order it
     * made before; its items were opened with it.</p>
     */
    @Override
    public OrderSummary placeQuotedOrder(PlaceOrderCommand command) {
        // Idempotency first: a retried conversion of the same quote must not create a second order.
        Optional<Order> alreadyPlaced = repository.findByRequestId(command.requestId());
        if (alreadyPlaced.isPresent()) {
            if (!command.customerId().equals(alreadyPlaced.get().customerId())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            log.info("Request {} already produced order {}",
                    command.requestId(), alreadyPlaced.get().orderNumber());
            return toSummary(alreadyPlaced.get());
        }

        // Another customer's own SKU is not there for this customer (kltn-docs 13 BR-02), whoever places the order.
        customerSkus.requireVisibleTo(command.lines().stream().map(line -> line.sku().code()).toList(),
                command.customerId());
        // The day in the business's calendar, as a quote's number is dated: an order converted at 06:00 in Ho Chi Minh
        // City is numbered for that day, not for the UTC day before.
        LocalDate today = com.stockflow.common.domain.BusinessCalendar.date(clock.instant());
        OrderNumber orderNumber = repository.nextOrderNumber(today);

        // Line ids derived from the request id and the line's position, not random: a retried conversion writes the
        // same rows.
        List<OrderLine> lines = new ArrayList<>();
        for (int index = 0; index < command.lines().size(); index++) {
            PlaceOrderCommand.Line line = command.lines().get(index);
            var orderLine = Order.line(deterministicLineId(command.requestId(), index),
                    line.sku(), line.quantity(), line.unitPrice(), line.designSnapshotId());
            if (line.carton() != null) {
                orderLine.withCartons(line.carton().uomConversionId(), line.carton().cartons(),
                        line.carton().unitsPerCarton());
            }
            if (line.taxRate() != null) {
                // 4B-3 (14 BR-21): the rate fixed on the accepted quote line; the tax is the line's total at it.
                orderLine.withTax(line.taxRate());
            }
            orderLine.withRequestedDeliveryDate(line.requestedDeliveryDate());
            if (line.designSnapshotId() != null) {
                orderLine.recordDesignChecksum(designs.verifySnapshotForSku(line.designSnapshotId(), command.customerId(), line.sku().code()).checksum());
            }
            lines.add(orderLine);
        }

        Order order;
        if (command.snapshotCustomer()) {
            CheckoutCustomer checkout = customers.resolveCheckout(command.customerId(),
                    command.shippingAddressId(), command.billingAddressId());
            order = Order.customerDraft(orderNumber, command.customerId(), command.requestId(),
                    lines, clock.instant(), checkout.fullName(), checkout.email(), checkout.phone(),
                    toSnapshot(checkout.shippingAddress()), toSnapshot(checkout.billingAddress()));
        } else {
            order = Order.draft(orderNumber, command.customerId(), command.requestId(),
                    lines, clock.instant());
        }
        // 4B-3: the quote's fee lines (14 BR-09).
        order.attachFees(command.fees().stream().map(fee -> OrderFee.of(deterministicFeeId(command.requestId(),
                fee.kind(), fee.sku()), fee.kind(), fee.sku(), fee.amount(), fee.taxRate())).toList());
        // 4B-5 (kltn-docs 14 §4.2 step 7): an accepted quote becomes a DRAFT order - no stock held, no terms, no credit
        // checked. Its sales contract takes it on: sent, signed, then held for credit, waiting for the installment on
        // signing, or confirmed (SalesContracts). How it is paid is the contract's plan's: an order has no payment terms
        // of its own since 4B-10. Its stock is held once a shipping warehouse is chosen for it (4B-7).
        Order saved = repository.save(order);
        openInventoryItems(command);
        log.info("Order {} drafted for customer {} from an accepted quote, {} line(s), total {}",
                saved.orderNumber(), saved.customerId(), saved.lines().size(), saved.total());
        return toSummary(saved);
    }

    /**
     * Opens the inventory item of every SKU the order's lines name (owner decision Q5), after the order row: an order
     * line's SKU is a customer's own SKU ({@code fk_order_line_customer_sku}), drawn on the catalogue variant whose
     * carton the item is packed in. One read for the SKUs, one insert per SKU in code order; an item that exists is
     * kept. No SKU advisory lock is taken: nothing is held or moved, and two conversions naming one SKU meet only on
     * the item's unique code, where the second waits for the first and keeps its row.
     *
     * @throws BusinessException {@code VARIANT_NOT_FOUND} for a line SKU that is not a customer's SKU - which the
     *         foreign key would refuse anyway, but an item packed as nothing must not be guessed first
     */
    private void openInventoryItems(PlaceOrderCommand command) {
        List<String> skus = command.lines().stream().map(line -> line.sku().code()).distinct().toList();
        if (skus.isEmpty()) {
            return;
        }
        java.util.Map<String, com.stockflow.product.api.CustomerSku> known = customerSkus.findAll(skus);
        List<com.stockflow.inventory.api.OpenInventoryItemsCommand.Item> items = new ArrayList<>();
        for (String sku : skus) {
            com.stockflow.product.api.CustomerSku customerSku = known.get(sku);
            if (customerSku == null || customerSku.sourceVariantId() == null) {
                throw new BusinessException(ErrorCode.VARIANT_NOT_FOUND,
                        "%s is not a customer's SKU: only a customer's own SKU is ordered".formatted(sku));
            }
            items.add(new com.stockflow.inventory.api.OpenInventoryItemsCommand.Item(new com.stockflow.common.domain.Sku(sku),
                    customerSku.sourceVariantId()));
        }
        inventory.openItems(new com.stockflow.inventory.api.OpenInventoryItemsCommand(items));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderSummary> findById(UUID orderId) {
        return repository.findById(new OrderId(orderId)).map(this::toSummary);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The status change and every stock release are one transaction. The distributed version needed a compensating command per line plus a
     * reconciliation job for the ones that got lost.</p>
     *
     * <p>SCRUM-242/WBS 3.17.4: looks the order up via {@link OrderRepository#findByIdInScope},
     * not {@code findByIdForUpdate} directly — this is what makes {@code scope = OWN} on the
     * customer-facing cancellation endpoint an actual restriction rather than a decorative one. The
     * admin/sales endpoint calls this same method under {@code scope = ALL}, where the lookup
     * imposes no ownership restriction at all.</p>
     */
    @Override
    public OrderSummary cancel(UUID orderId, CancelOrderCommand command) {
        Order order = repository.findByIdInScope(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "No order with id " + orderId));
        return toSummary(cancelLoaded(order, command));
    }

    @Override
    public void cancel(UUID orderId, String reason) {
        cancel(orderId, CancelOrderCommand.fromText(reason, null));
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void recordFirstLabel(UUID orderId) {
        Order order = lockOrder(orderId);
        if (order.recordFirstLabel(clock.instant())) {
            repository.save(order);
            // 17 §4.6 step 28: a cancellation request still waiting can no longer be granted.
            closeUndecidableCancellationRequest(order);
            log.info("Order {}: first carton labelled, the order is locked", order.orderNumber());
        }
    }

    @Override
    public void recordPacked(UUID orderId) {
        Order order = lockOrder(orderId);
        if (order.recordPacked(clock.instant())) {
            repository.save(order);
            closeUndecidableCancellationRequest(order);
            log.info("Order {}: packed", order.orderNumber());
        }
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Optional<OrderStatus> lockedStatus(UUID orderId) {
        return repository.findByIdForUpdate(new OrderId(orderId)).map(Order::status);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> shippingWarehouseId(UUID orderId) {
        return repository.findById(new OrderId(orderId))
                .map(o -> o.warehouseId() != null ? o.warehouseId() : o.shippingWarehouseId());
    }

    private Order lockOrder(UUID orderId) {
        return repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
    }

    /**
     * {@inheritDoc}
     *
     * <p>A second request while one is pending is refused rather than stacked: the customer has
     * already asked, and Sales should see one question per order.</p>
     */
    @Override
    public CancellationOutcome cancelOwn(UUID orderId, UUID customerId, CancellationReasonCode reasonCode,
                                         String note, UUID requestedBy) {
        if (reasonCode != null && !reasonCode.customerMayGive()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A customer cancels for their own reasons",
                    List.of(new com.stockflow.common.api.FieldError("reasonCode",
                            "must be one of CUSTOMER_REQUEST, DUPLICATE_ORDER, OTHER", "NotACustomerReason")));
        }
        Order order = repository.findByIdForUpdate(new OrderId(orderId))
                .filter(found -> customerId != null && customerId.equals(found.customerId()))
                // 17 §5.2: a DRAFT order is not the customer's - nothing to ask about.
                .filter(found -> found.status() != OrderStatus.DRAFT)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "No order with id " + orderId));
        CancellationReasonCode code = reasonCode == null ? CancellationReasonCode.CUSTOMER_REQUEST : reasonCode;
        if (order.status().customerMayCancelDirectly()) {
            Order cancelled = cancelLoaded(order, new CancelOrderCommand(code, note, null, requestedBy));
            return new CancellationOutcome(CancellationOutcome.Result.CANCELLED, null, toSummary(cancelled));
        }
        if (!order.status().canTransitionTo(OrderStatus.CANCELLED)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_CANCELLABLE,
                    "Order %s is %s and can no longer be cancelled; raise a return instead"
                            .formatted(order.orderNumber(), order.status()));
        }
        order.requireNotPacked();
        if (cancellationRequests.hasPending(order.id().value())) {
            throw new BusinessException(ErrorCode.ORDER_CANCELLATION_REQUEST_PENDING,
                    "Order %s already has a cancellation request waiting".formatted(order.orderNumber()));
        }
        CancellationRequest request = cancellationRequests.save(
                CancellationRequest.open(order.id().value(), requestedBy, code, note, clock.instant()));
        log.info("Cancellation of order {} requested ({})", order.orderNumber(), code);
        // 17 §4.8 step 28: Sales are told, not left to find it on the requests page.
        events.publish(new com.stockflow.contracts.OrderCancellationRequested(request.id(), order.id().value(),
                order.orderNumber().value(), String.valueOf(code), request.requestedAt()));
        return new CancellationOutcome(CancellationOutcome.Result.REQUESTED, request.id(), toSummary(order));
    }

    /**
     * Cancels a loaded, locked order: releases its holds, records the reason and publishes
     * {@code OrderCancelled} - and {@code SalesContractStatusChanged} when it takes an unsigned contract with it - all in
     * this transaction. Shared by every cancellation path, including
     * an approved request ({@code CancellationRequestService}).
     */
    Order cancelLoaded(Order order, CancelOrderCommand command) {
        boolean wasHoldingStock = order.status().holdsStock();
        java.time.Instant now = clock.instant();
        order.cancel(command.reasonCode(), command.note(), command.retainedPercent(), command.agreedFee(),
                command.cancelledBy(), now);
        resolveOpenHolds(order, command, now);
        // 17 §4.8 step 27: an order cancelled before its contract is signed takes the contract with it. A signed
        // contract stays SIGNED until the cancellation's money is settled; the nightly run then LIQUIDATES it (WF-05
        // Q-6, OrderSettlementService.liquidateCancelled).
        contracts.findLiveByOrderId(order.id().value())
                .filter(contract -> contract.status().canTransitionTo(
                        com.stockflow.order.internal.domain.SalesContractStatus.CANCELLED))
                .ifPresent(contract -> {
                    // Someone cancels an order that has a contract: Sales, accounting or the customer's approved
                    // request - never the system on its own, which would have no one to name on the contract.
                    contract.cancel(cancelledWith(command.reasonCode()), now,
                            java.util.Objects.requireNonNull(command.cancelledBy(),
                                    "an order with a contract is cancelled by someone"));
                    contracts.save(contract);
                    // LC-1: told like a cancellation of the contract alone, so the chat's contract card the customer
                    // holds reads "cancelled" and offers no download. Only a DRAFT or SENT contract gets here.
                    events.publish(new com.stockflow.contracts.SalesContractStatusChanged(contract.id(),
                            contract.contractNumber(), order.id().value(), order.orderNumber().value(),
                            order.customerId(), contract.status().name(), command.cancelledBy(), now));
                });

        if (wasHoldingStock) {
            releaseHolds(order.lines());
            order.clearReservations();
        }

        Order saved = repository.save(order);
        settleContractMoney(order, command, now);
        closeUndecidableCancellationRequest(order);
        events.publishEventsOf(order);
        endShortageCards(order.id().value(), com.stockflow.order.api.ShortageCardEnd.ORDER_CANCELLED);
        // The code, not the reason text: the text carries the customer's note, and a line break in it
        // would forge log lines (CodeQL java/log-injection).
        log.info("Cancelled order {} ({})", order.orderNumber(), order.cancellationReasonCode());
        return saved;
    }

    /**
     * Why a contract or a hold closed with its order, as Sales and the customer read it on a Vietnamese screen: "Huỷ cùng
     * đơn hàng: Khách yêu cầu", not "Order cancelled: CUSTOMER_REQUEST". From the Vietnamese bundle whatever the
     * caller's language, as the contract itself is a Vietnamese document.
     */
    static String cancelledWith(com.stockflow.order.api.CancellationReasonCode code) {
        String name = String.valueOf(code);
        String key = "label.CancellationReasonCode." + name;
        String reason = VIETNAMESE.containsKey(key) ? VIETNAMESE.getString(key) : name;
        return java.text.MessageFormat.format(VIETNAMESE.getString("order.cancelledWith"), reason);
    }

    /**
     * A held order cancelled leaves no hold open behind it: a credit hold Sales cancel instead of accounting deciding it
     * (17 §5.1 lets Sales cancel an order on hold) is closed on record by whoever cancelled, with the reason - the trail
     * then says the hold ended with the order, not that it is still waiting for a decision (15 §4.3: every decision is
     * kept). Accounting's own refusal and Sales' delivery-failure decision resolve their row before they cancel, so
     * nothing is left here for them. A cancellation nobody is named for (the system's) cannot be signed on the row
     * (ck_order_hold_resolution) and leaves it as it is.
     */
    private void resolveOpenHolds(Order order, CancelOrderCommand command, java.time.Instant at) {
        if (command.cancelledBy() == null) {
            return;
        }
        String note = cancelledWith(command.reasonCode())
                + (command.note() == null || command.note().isBlank() ? "" : " - " + command.note().trim());
        for (int i = 0; i < 20; i++) {
            var open = holds.findFirstByOrderIdAndResolvedAtIsNullOrderByRaisedAtDesc(order.id().value());
            if (open.isEmpty()) {
                return;
            }
            OrderHoldJpaEntity hold = open.get();
            hold.resolve(command.cancelledBy(), note.length() > 1000 ? note.substring(0, 1000) : note, at);
            holds.save(hold);
        }
    }

    /**
     * 4B-9 (15 §4.6 step 18, 17 §4.8 step 30): a contract order's money is on its installments, so payment settles it
     * here, in the cancelling transaction and under the order's lock - the installments not paid in full cancelled, the
     * money collected paying the fee first, the rest to refund, a shortfall owed on a fee item. {@link Order#cancel} has
     * already refused a fee before mass printing (BR-11) or above the order's worth.
     */
    private void settleContractMoney(Order order, CancelOrderCommand command, java.time.Instant at) {
        if (!order.paidByContract()) {
            return;
        }
        com.stockflow.order.api.CancellationFee fee = command.agreedFee();
        BigDecimal percent = command.retainedPercent() == null || command.retainedPercent().signum() == 0 ? null
                : command.retainedPercent();
        paymentService.settleCancellation(new com.stockflow.payment.api.SettleCancellationCommand(order.id().value(),
                percent, fee == null ? null : fee.amount(), fee == null ? null : fee.reason(),
                fee == null ? null : fee.detail(), command.cancelledBy(), at));
    }

    /**
     * Frees the stock holds of {@code lines}. The goods of a print line not shipped stay in stock as the customer's
     * surplus (kltn-docs 17 BR-07): nothing is scrapped. Releasing a hold already consumed by a pick or closed does
     * nothing - its goods are fulfillment's to put back (G4-4). An expired hold is closed too: since team plan 5-2
     * nothing cancels a checkout order when its hold runs out, so Sales cancels such an order by hand
     * ({@code POST /orders/{id}/admin-cancellation}) and its expired holds are skipped here, not refused.
     */
    private void releaseHolds(java.util.Collection<OrderLine> lines) {
        lines.stream().flatMap(line -> line.reservationIds().stream())
                .forEach(reservationId -> inventory.release(reservationId, "ORDER_CANCELLED"));
    }

    /** For an approved cancellation request: the order, locked. */
    Order loadForUpdate(UUID orderId) {
        return repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Whether the caller may see every order is decided by the controller (staff only); this
     * method applies no scope of its own, like {@link #myOrders}.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> list(ListOrdersQuery query) {
        if (query.placedFrom() != null && query.placedTo() != null && query.placedTo().isBefore(query.placedFrom())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "placedTo is before placedFrom");
        }
        var criteria = new OrderSearchRepository.Criteria(query.search(), query.statuses(), query.customerId(),
                query.placedFrom() == null ? null : query.placedFrom().atStartOfDay(BUSINESS_ZONE).toInstant(),
                query.placedTo() == null ? null : query.placedTo().plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant(),
                query.warehouseIds());
        return Pages.toResponse(search.search(criteria, Pages.of(query.page(), query.size(), LIST_SORT.parse(query.sort()))));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderStatusChange> history(UUID orderId) {
        return search.history(orderId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.stockflow.order.api.RemainderClosure> remainderClosures(UUID orderId) {
        return remainderClosures.findByOrderIdOrderByClosedAtAscIdAsc(orderId).stream()
                .map(row -> new com.stockflow.order.api.RemainderClosure(CancellationReasonCode.valueOf(row.getReasonCode()),
                        row.getNote(), row.getClosedBy(), row.getClosedAt()))
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code customerId} must come from the controller resolving {@code @AuthenticatedUser},
     * never from a client-supplied parameter — this method applies no scope check of its own.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> myOrders(UUID customerId, int page, int size) {
        var pageable = Pages.of(page, size, Sort.by(Sort.Direction.DESC, "lastModifiedAt"));
        return Pages.toResponse(search.findByCustomerId(customerId, pageable));
    }

    /**
     * 4B-6 (15 BR-07, §4.2 step 7): a receipt paid installments of the order. Once every installment its contract wants
     * on signing is paid - read now, not from the event: a receipt voided since is counted - an order waiting for that
     * money is confirmed. Called by {@code PaymentEventListener}; a redelivered event finds it confirmed already.
     */
    public void installmentsPaid(UUID orderId, java.time.Instant at) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new IllegalArgumentException("No order with id " + orderId));
        if (!order.paidByContract() || order.status() != OrderStatus.PENDING_PAYMENT
                || !paymentService.installmentStanding(orderId).onSigningPaid()) {
            return;
        }
        order.confirmByInstallments(at == null ? clock.instant() : at);
        repository.save(order);
        events.publishEventsOf(order);
        log.info("Order {} confirmed: its installments on signing are paid", order.orderNumber());
    }

    /**
     * 4B-6 (15 §4.4 step 14, BR-13): a receipt voided with the accountant's agreement to hold what it leaves short. A
     * confirmed order of a contract whose installment on signing is no longer paid - read now - goes ON_HOLD for
     * payment, never silently back to unpaid; Sales resolves it once the money is in again. Called by
     * {@code PaymentEventListener}; a redelivered event finds it on hold already.
     */
    public void installmentsShort(UUID orderId, String reference, UUID by, java.time.Instant at) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new IllegalArgumentException("No order with id " + orderId));
        if (!order.paidByContract() || paymentService.installmentStanding(orderId).onSigningPaid()) {
            return;
        }
        if (!order.status().mayBePutOnHold()) {
            // Already on hold, or past the handover - which waited for this money (installmentsDue), so goods left only
            // before the void. Accounting and Sales settle it with the customer.
            log.warn("Order {} is {} and short of its installment on signing after payment receipt {} was voided; not "
                    + "put on hold", order.orderNumber(), order.status(), reference);
            return;
        }
        java.time.Instant when = at == null ? clock.instant() : at;
        String note = SalesFollowUps.VOIDED_RECEIPT_HOLD_NOTE.formatted(reference);
        order.putOnHold(com.stockflow.order.api.HoldReason.PAYMENT, note, by, when);
        holds.save(new OrderHoldJpaEntity(Identifiers.newId(), orderId,
                com.stockflow.order.api.HoldReason.PAYMENT.name(), when, note, by));
        repository.save(order);
        events.publishEventsOf(order);
        log.warn("Order {} put on hold: payment receipt {} was voided and its installment on signing is short",
                order.orderNumber(), reference);
    }

    @Override
    public OrderSummary recordDelivery(UUID orderId) {
        return recordDelivery(orderId, null);
    }

    @Override
    @com.stockflow.common.audit.Auditable(action = com.stockflow.common.audit.AuditAction.TRANSITION,
            resourceType = "order", resourceId = "#orderId")
    public OrderSummary recordDelivery(UUID orderId, UUID by) {
        return deliver(orderId, clock.instant(), by);
    }

    @Override
    @com.stockflow.common.audit.Auditable(action = com.stockflow.common.audit.AuditAction.TRANSITION,
            resourceType = "order", resourceId = "#orderId")
    public OrderSummary recordDelivery(UUID orderId, Instant at, UUID by) {
        return deliver(orderId, at, by);
    }

    private OrderSummary deliver(UUID orderId, Instant at, UUID by) {
        Order order = lockOrder(orderId);
        if (order.status() == OrderStatus.DELIVERED && order.shipments().isEmpty()) {
            return toSummary(order);
        }
        order.markDelivered(at, by);
        log.info("Order {} delivered", order.orderNumber());
        return saved(order);
    }

    @Override
    @com.stockflow.common.audit.Auditable(action = com.stockflow.common.audit.AuditAction.TRANSITION,
            resourceType = "order", resourceId = "#orderId")
    public OrderSummary recordDeliveryFailure(UUID orderId, com.stockflow.order.api.DeliveryFailureReason reason,
                                              String note, UUID by) {
        return failDelivery(orderId, reason, note, clock.instant(), by);
    }

    @Override
    @com.stockflow.common.audit.Auditable(action = com.stockflow.common.audit.AuditAction.TRANSITION,
            resourceType = "order", resourceId = "#orderId")
    public OrderSummary recordDeliveryFailure(UUID orderId, com.stockflow.order.api.DeliveryFailureReason reason,
                                              String note, Instant at, UUID by) {
        return failDelivery(orderId, reason, note, at, by);
    }

    private OrderSummary failDelivery(UUID orderId, com.stockflow.order.api.DeliveryFailureReason reason, String note,
                                      Instant at, UUID by) {
        Order order = lockOrder(orderId);
        if (!order.markDeliveryFailed(reason, note, at, by)) {
            return toSummary(order);
        }
        log.info("Order {}: delivery failed ({})", order.orderNumber(), reason);
        return saved(order);
    }

    @Override
    public OrderSummary recordReturnedToOrigin(UUID orderId, UUID by) {
        return recordReturnedToOrigin(orderId, clock.instant(), by);
    }

    @Override
    public OrderSummary recordReturnedToOrigin(UUID orderId, Instant at, UUID by) {
        Order order = lockOrder(orderId);
        return order.markReturnedToOrigin(at, by) ? saved(order) : toSummary(order);
    }

    @Override
    public OrderSummary startPicking(UUID orderId, UUID by) {
        Order order = lockOrder(orderId);
        return order.startPicking(by, clock.instant()) ? saved(order) : toSummary(order);
    }

    @Override
    public OrderSummary markPacked(UUID orderId, UUID by) {
        Order order = lockOrder(orderId);
        if (!order.markPacked(clock.instant(), by)) {
            return toSummary(order);
        }
        log.info("Order {}: packed", order.orderNumber());
        return saved(order);
    }

    @Override
    public OrderSummary markPartlyPacked(UUID orderId, UUID by) {
        Order order = lockOrder(orderId);
        boolean changed = order.markPartlyPacked(clock.instant(), by);
        // 17 §4.14 step 58: the partial check of a card's "ship part, close the rest" is done - Sales close the rest now.
        ShortageCardService cardsOfOrder = shortageCards == null ? null : shortageCards.getIfAvailable();
        if (cardsOfOrder != null) {
            cardsOfOrder.partialCheckCompleted(orderId);
        }
        if (!changed) {
            return toSummary(order);
        }
        log.info("Order {}: packed in part, {}", order.orderNumber(), order.status());
        return saved(order);
    }

    @Override
    public OrderSummary markShipped(UUID orderId, java.time.Instant handedOverAt, UUID by) {
        Order order = lockOrder(orderId);
        if (!order.markShipped(handedOverAt == null ? clock.instant() : handedOverAt, by, installmentsDue(order))) {
            return toSummary(order);
        }
        log.info("Order {}: shipped", order.orderNumber());
        return saved(order);
    }

    @Override
    public OrderSummary recordHandover(UUID orderId, com.stockflow.order.api.ShipmentHandover handover) {
        Order order = lockOrder(orderId);
        // WF-05 fix plan, section 2: a shipment made from a redelivery's pick carries the batch it delivers again, and
        // closes its delivery failure case once it has left.
        Optional<com.stockflow.order.internal.domain.DeliveryFailureCase> redelivery =
                handover.redeliveryCaseId() == null ? Optional.empty()
                        : deliveryFailures.find(handover.redeliveryCaseId())
                                .filter(failure -> failure.orderId().equals(orderId));
        var batch = redelivery.isPresent() ? flightOf(order, redelivery.get().deliveryBatchId()) : flightOf(order, null);
        if (!order.recordHandover(handover, installmentsDue(order, batch), batch.orElse(null),
                businessDay(handover.handedOverAt()))) {
            return toSummary(order);
        }
        log.info("Order {}: shipment {} handed over, order {}", order.orderNumber(), handover.shipmentId(),
                order.status());
        OrderSummary summary = saved(order);
        // After the order's rows are written: the case names the new shipment (check_delivery_failure_case).
        redelivery.filter(com.stockflow.order.internal.domain.DeliveryFailureCase::released).ifPresent(failure -> {
            failure.redelivered(handover.shipmentId(), handover.handedOverBy(), handover.handedOverAt());
            deliveryFailures.save(failure);
            log.info("Order {}: the goods of receipt {} were handed over again on shipment {}", order.orderNumber(),
                    failure.receiptNumber(), handover.shipmentId());
        });
        return summary;
    }

    @Override
    public void recordGoodsLeft(UUID orderId, java.time.Instant at,
                                java.util.List<com.stockflow.order.api.ShipmentHandover.Line> inPlan) {
        Order order = lockOrder(orderId);
        var batch = inFlight(order);
        if (order.recordGoodsLeft(at, inPlan == null || inPlan.isEmpty() ? null : quantities(inPlan),
                installmentsDue(order, batch), batch.orElse(null), businessDay(at))) {
            log.info("Order {}: goods left on a load plan's vehicle; no longer cancelled", order.orderNumber());
            saved(order);
        }
    }

    @Override
    public void requirePlanCarriesTheRest(UUID orderId,
                                          java.util.List<com.stockflow.order.api.ShipmentHandover.Line> inPlan) {
        Order order = repository.findById(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
        order.requirePlanCarriesTheRest(inPlan == null || inPlan.isEmpty() ? null : quantities(inPlan),
                inFlight(order).orElse(null));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Read without locks: a decision on the whole vehicle before any order on it is changed; each order is checked
     * again under its own lock when it is handed over.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public java.util.List<com.stockflow.order.api.HandoverBlock> handoverBlocks(java.util.Collection<UUID> orderIds,
                                                                              Instant at) {
        java.util.List<com.stockflow.order.api.HandoverBlock> blocks = new java.util.ArrayList<>();
        for (UUID orderId : new java.util.TreeSet<>(orderIds)) {
            Order order = repository.findById(new OrderId(orderId)).orElse(null);
            if (order == null) {
                continue;
            }
            var batch = inFlight(order);
            java.math.BigDecimal due = installmentsDue(order, batch);
            java.util.List<String> reasons = order.handoverBlockReasons(due, batch.orElse(null), businessDay(at));
            if (!reasons.isEmpty()) {
                blocks.add(new com.stockflow.order.api.HandoverBlock(orderId, order.orderNumber().value(),
                        reasons.get(0), reasons, due == null ? java.math.BigDecimal.ZERO : due,
                        order.total().currency().getCurrencyCode(),
                        batch.map(com.stockflow.order.internal.domain.BatchInFlight::seq).orElse(null),
                        batch.map(com.stockflow.order.internal.domain.BatchInFlight::windowStart).orElse(null)));
            }
        }
        return blocks;
    }

    /**
     * {@inheritDoc}
     *
     * <p>One batch is on its way at a time (a batch is released once the one before it was handed over), so the batch
     * to pick is the latest released, if it has no task yet.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<com.stockflow.order.api.ReleasedBatch> batchToPick(UUID orderId,
                                                                           java.util.Collection<UUID> pickedBatches) {
        Order order = repository.findById(new OrderId(orderId)).orElse(null);
        if (order == null) {
            return java.util.Optional.empty();
        }
        // The latest batch released, not a redelivery: goods handed over again have a pick of their own
        // (redeliveryToPick), of a batch that was picked once already.
        return flightOf(order, null).filter(batch -> pickedBatches == null || !pickedBatches.contains(batch.batchId()))
                .map(batch -> new com.stockflow.order.api.ReleasedBatch(batch.batchId(), batch.seq(),
                        batch.windowStart(), holdsOfBatch(orderId, batch.batchId())));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Set<UUID> holdsOfBatch(UUID orderId, UUID deliveryBatchId) {
        java.util.Set<UUID> ids = new java.util.LinkedHashSet<>();
        batchRecords.holdsOf(orderId).stream().filter(h -> h.batchId().equals(deliveryBatchId))
                .forEach(h -> ids.add(h.reservationId()));
        return ids;
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Map<UUID, Integer> batchUnits(UUID orderId, UUID deliveryBatchId) {
        Order order = repository.findById(new OrderId(orderId)).orElse(null);
        java.util.Map<UUID, Integer> units = new java.util.LinkedHashMap<>();
        if (order == null) {
            return units;
        }
        contracts.findLiveByOrderId(orderId).stream().flatMap(c -> c.schedule().stream())
                .filter(b -> b.id().equals(deliveryBatchId)).flatMap(b -> b.lines().stream())
                .forEach(l -> order.lines().stream().filter(line -> line.id().equals(l.orderLineId())).findFirst()
                        .ifPresent(line -> units.merge(line.id(), l.cartons()
                                * (line.unitsPerCarton() == null ? 1 : line.unitsPerCarton()), Integer::sum)));
        // WF4-CLOSE-REST (2026-11-13): once the rest of a line was closed, the batch wants no more of it than the line
        // still has open beyond what the other batches shipped - the units labelled for this batch, which the closure
        // kept (Order#closeRemainder) - so the check of its labelled cartons completes in full.
        order.lines().stream().filter(line -> line.cancelledQuantity() > 0 && units.containsKey(line.id()))
                .forEach(line -> {
                    int elsewhere = order.shipments().stream()
                            .filter(s -> s.holdsUnits() && !deliveryBatchId.equals(s.deliveryBatchId()))
                            .mapToInt(s -> s.quantities().getOrDefault(line.id(), 0)).sum();
                    int open = Math.max(0, line.quantity() - line.cancelledQuantity() - elsewhere);
                    units.computeIfPresent(line.id(), (id, planned) -> Math.min(planned, open));
                });
        return units;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Read without locks: the load plan's confirmation decides on it, and each order is checked again under its own
     * lock when its goods leave.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public java.util.Map<UUID, Integer> unitsDueTogether(java.util.Collection<UUID> orderIds) {
        java.util.Map<UUID, Integer> units = new java.util.LinkedHashMap<>();
        for (UUID orderId : new java.util.TreeSet<>(orderIds == null ? java.util.List.of() : orderIds)) {
            repository.findById(new OrderId(orderId))
                    .ifPresent(order -> units.put(orderId, order.unitsDueTogether(inFlight(order).orElse(null))));
        }
        return units;
    }

    /**
     * 4B-8: the delivery batch on its way out, if the order releases by batch. A redelivery with the warehouse (WF-05
     * fix plan, section 2) is the batch on its way: its shipment carries the batch it delivers again.
     *
     * <p>While a redelivery is released, every caller - the load plan, the truck leaving - sees that batch, with no
     * delivery window ({@code windowStart} null: the goods were due already). A next batch released at the same time is
     * then not checked against its window before the handover (WF-05 review F14); the handover itself still checks it,
     * per shipment, so it cannot be handed over early - only planned onto a truck early. Accepted: at most one
     * redelivery is released at a time and it goes first.</p>
     */
    private java.util.Optional<com.stockflow.order.internal.domain.BatchInFlight> inFlight(Order order) {
        return flightOf(order, releasedRedelivery(order.id().value())
                .map(com.stockflow.order.internal.domain.DeliveryFailureCase::deliveryBatchId).orElse(null));
    }

    /** The batch on its way: {@code redeliveredBatchId} when goods of it are handed over again, else the latest released. */
    private java.util.Optional<com.stockflow.order.internal.domain.BatchInFlight> flightOf(Order order,
                                                                                           UUID redeliveredBatchId) {
        return order.paidByContract() ? batches.inFlight(order, redeliveredBatchId) : java.util.Optional.empty();
    }

    /** The order's redelivery released to the warehouse and not handed over yet, if any (at most one at a time). */
    private Optional<com.stockflow.order.internal.domain.DeliveryFailureCase> releasedRedelivery(UUID orderId) {
        return deliveryFailures.ofOrder(orderId).stream()
                .filter(com.stockflow.order.internal.domain.DeliveryFailureCase::released).findFirst();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The earliest redelivery released and not picked yet: its allocations and the units it delivers again.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<com.stockflow.order.api.RedeliveryToPick> redeliveryToPick(UUID orderId,
                                                                              java.util.Collection<UUID> pickedCases) {
        return deliveryFailures.ofOrder(orderId).stream()
                .filter(com.stockflow.order.internal.domain.DeliveryFailureCase::released)
                .filter(failure -> pickedCases == null || !pickedCases.contains(failure.id()))
                .findFirst().map(this::toPick);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<com.stockflow.order.api.RedeliveryToPick> redelivery(UUID caseId) {
        return deliveryFailures.find(caseId).map(this::toPick);
    }

    private com.stockflow.order.api.RedeliveryToPick toPick(
            com.stockflow.order.internal.domain.DeliveryFailureCase failure) {
        java.util.Set<UUID> holdIds = new java.util.LinkedHashSet<>();
        deliveryFailures.holdsOf(failure.id()).forEach(hold -> holdIds.add(hold.reservationId()));
        return new com.stockflow.order.api.RedeliveryToPick(failure.id(), failure.deliveryBatchId(), holdIds,
                failure.units());
    }

    private static java.time.LocalDate businessDay(Instant at) {
        return at == null ? null : com.stockflow.common.domain.BusinessCalendar.date(at);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<com.stockflow.order.api.OrderHoldState> holdStates(java.util.Collection<UUID> orderIds) {
        return repository.holdStates(orderIds == null ? java.util.List.of() : java.util.Set.copyOf(orderIds));
    }

    private static java.util.Map<UUID, Integer> quantities(
            java.util.List<com.stockflow.order.api.ShipmentHandover.Line> lines) {
        java.util.Map<UUID, Integer> quantities = new java.util.LinkedHashMap<>();
        if (lines != null) {
            lines.forEach(line -> quantities.merge(line.orderLineId(), line.quantity(), Integer::sum));
        }
        return quantities;
    }

    /**
     * 4B-6 (15 BR-07, BR-08): what an order of a contract still owes on its installments before delivery and on signing,
     * read under the order's lock (taken by the caller) - a receipt's void locks the order first, so one voided a moment
     * ago is seen here, before the hold it calls for has arrived. Null for an order without a contract: nothing of it is
     * paid through the system since the money ledger went (4B-10).
     */
    private java.math.BigDecimal installmentsDue(Order order) {
        return order.paidByContract() ? paymentService.installmentStanding(order.id().value()).dueBeforeHandover()
                : null;
    }

    /**
     * 4B-8 (15 BR-08, 09 BR-18): for the delivery batch on its way out, only what it waits for - the installments on
     * signing, and those before delivery tied to it or to no batch.
     */
    private java.math.BigDecimal installmentsDue(Order order,
                                                 java.util.Optional<com.stockflow.order.internal.domain.BatchInFlight> batch) {
        if (batch.isEmpty()) {
            return installmentsDue(order);
        }
        return paymentService.dueBeforeHandover(order.id().value(), batch.get().batchId());
    }

    @Override
    public OrderSummary recordShipmentDelivered(UUID orderId, UUID shipmentId, UUID by) {
        return recordShipmentDelivered(orderId, shipmentId, clock.instant(), by);
    }

    @Override
    public OrderSummary recordShipmentDelivered(UUID orderId, UUID shipmentId, Instant at, UUID by) {
        Order order = lockOrder(orderId);
        if (!order.recordShipmentDelivered(shipmentId, at, by)) {
            return toSummary(order);
        }
        log.info("Order {}: shipment {} delivered, order {}", order.orderNumber(), shipmentId, order.status());
        return saved(order);
    }

    @Override
    public OrderSummary recordShipmentDeliveryFailed(UUID orderId, UUID shipmentId,
                                                     com.stockflow.order.api.DeliveryFailureReason reason, String note,
                                                     UUID by) {
        return recordShipmentDeliveryFailed(orderId, shipmentId, reason, note, clock.instant(), by);
    }

    @Override
    public OrderSummary recordShipmentDeliveryFailed(UUID orderId, UUID shipmentId,
                                                     com.stockflow.order.api.DeliveryFailureReason reason, String note,
                                                     Instant at, UUID by) {
        Order order = lockOrder(orderId);
        if (!order.recordShipmentDeliveryFailed(shipmentId, reason, note, at, by)) {
            return toSummary(order);
        }
        log.info("Order {}: shipment {} not delivered ({}), order {}", order.orderNumber(), shipmentId, reason,
                order.status());
        return saved(order);
    }

    @Override
    public OrderSummary correctShipmentFailureReason(UUID orderId, UUID shipmentId,
                                                     com.stockflow.order.api.DeliveryFailureReason reason,
                                                     String note) {
        Order order = lockOrder(orderId);
        // The case of the returned goods is opened with the shipment's reason and keeps it (tg_delivery_failure_case):
        // Sales decide on it - a failed trip charged or claimed from the carrier - so it does not move under them.
        if (deliveryFailures.findByShipment(shipmentId).isPresent()) {
            throw new BusinessException(ErrorCode.SHIPMENT_FAILURE_REASON_LOCKED, ("The goods of shipment %s of order %s "
                    + "were received back and their case opened: its failure reason stays").formatted(shipmentId,
                    order.orderNumber()));
        }
        if (!order.correctShipmentFailureReason(shipmentId, reason, note)) {
            return toSummary(order);
        }
        log.info("Order {}: failure reason of shipment {} corrected to {}", order.orderNumber(), shipmentId, reason);
        return saved(order);
    }

    @Override
    public OrderSummary correctShipmentToFailed(UUID orderId, UUID shipmentId,
                                                com.stockflow.order.api.DeliveryFailureReason reason, String note,
                                                Instant at, UUID by) {
        Order order = lockOrder(orderId);
        // WF-05, the gap left after TH-1: a return against this delivery says the customer received its goods - they
        // claim for them, and the claim's refund, its cartons in quarantine and its invoice adjustment all stand on
        // the delivery. Undoing the delivery under them would leave a refund for goods "never delivered", an installment
        // taken back while its refund is credited to it, and an invoice reduced twice. Read under the order's lock,
        // which opening a return takes too: one of the two sees the other. A carrier that really failed is settled by
        // closing the return first (rejected or cancelled); a resolved one stays, and so does the delivery.
        if (repository.hasReturnAgainst(orderId, shipmentId)) {
            throw new BusinessException(ErrorCode.ORDER_DELIVERY_HAS_RETURN, ("Shipment %s of order %s has a return "
                    + "request against it: its delivery is not corrected to a failure").formatted(shipmentId,
                    order.orderNumber()));
        }
        if (!order.correctShipmentToFailed(shipmentId, reason, note, at, by)) {
            return toSummary(order);
        }
        log.info("Order {}: shipment {} corrected to not delivered ({}), order {}", order.orderNumber(), shipmentId,
                reason, order.status());
        return saved(order);
    }

    @Override
    public OrderSummary recordShipmentReturned(UUID orderId, UUID shipmentId, UUID by) {
        return recordShipmentReturned(orderId, shipmentId, clock.instant(), by);
    }

    @Override
    public OrderSummary recordShipmentReturned(UUID orderId, UUID shipmentId, Instant at, UUID by) {
        Order order = lockOrder(orderId);
        if (!order.recordShipmentReturned(shipmentId, at, by)) {
            return toSummary(order);
        }
        // RTM-RETURNED (owner 18-19/11; kltn-docs 09 §4.3 step 4, 17 §5.1): the order is no longer held for Sales to
        // deliver again - a new order sends the goods again. WF-05 Q-7 (BE-14, L-I8): nothing is closed or settled on
        // the carrier's word; the shipment's units are closed, and payment settles them, when the warehouse confirms
        // their return receipt (DeliveryFailureService.goodsReceived, then settleShipmentClosure).
        log.info("Order {}: shipment {} reported back by the carrier; order {}, its units wait for the return receipt",
                order.orderNumber(), shipmentId, order.status());
        return saved(order);
    }

    /**
     * WF-05 X-1 / X-2 (Q-2, Q-7, Q-12): the money of a shipment whose units the order just closed without delivering
     * them - at its return receipt, or as lost - settled by payment in this transaction, under the order's lock: the
     * shipment's own batch first, then older debt, what was paid above that refundable (Q-2) - and the share of the
     * shipping fee waived with it (Q-19), which payment works out from the order's fee and goods ({@link
     * #shippingFeeOf}). Dated by this transaction's clock, never the carrier's (BE-32). The closure is an entry of the
     * order's history like a closure of the rest ({@code order_remainder_closure}, keyed by the closure id: written
     * once). {@code ShipmentReturnSettled}, registered by the aggregate, is published when the caller saves the order.
     *
     * <p>The order is written before payment is asked: payment's triggers read the order's shipments
     * ({@code tg_order_settlement_shipping_waiver}), and they must see the closure. Payment's waiver is then kept on
     * the shipment, which the caller's save writes ({@code order_shipment.shipping_fee_waived}, 0 to its amount
     * once).</p>
     */
    void settleShipmentClosure(Order order, Order.ShipmentClosure closure,
                               com.stockflow.payment.api.SettleShipmentReturnCommand.Cause cause, String note, UUID by,
                               Instant settledAt) {
        UUID orderId = order.id().value();
        remainderClosures.save(new OrderRemainderClosureJpaEntity(Identifiers.newId(), orderId, closure.closureId(),
                CancellationReasonCode.DELIVERY_FAILED.name(), note, by, settledAt));
        if (order.paidByContract()) {
            repository.save(order);
            paymentService.settleShipmentReturn(new com.stockflow.payment.api.SettleShipmentReturnCommand(orderId,
                    closure.shipmentId(), closure.deliveryBatchId(), closure.closureId(), closure.goodsValue(), cause,
                    settledAt, by, HandedOverBatches.of(order), shippingFeeOf(order)))
                    // Q-19: payment's answer is kept on the shipment, for invoicing to bill the fee less it.
                    .ifPresent(settled -> order.recordShippingFeeWaived(closure.shipmentId(),
                            settled.shippingFeeWaived()));
        }
        log.info("Order {}: shipment {} closed ({}), goods worth {} {} settled; order {}", order.orderNumber(),
                closure.shipmentId(), cause, closure.goodsValue(), order.total().currency(), order.status());
    }

    /**
     * WF-05 Q-19 (owner, 23/11): what payment waives the shipping fee by - the order's shipping fee line with its VAT,
     * and every goods line with its VAT as signed (never less the units closed since: the shares of the shipments
     * brought back add up against one fixed whole, so all of them waive the whole fee). The plate fee lines are in
     * neither. {@code NONE} for an order without a shipping fee line.
     *
     * <p>{@code endsWithNothingDelivered} (Q-19 edge, owner 23/11): read from the order as this return or closure left
     * it - nothing of it delivered ({@link Order#deliveredSomething}, the definition the contract's liquidation (Q-6)
     * and the customer's PARTIALLY_RETURNED read too) and nothing more coming ({@link Order#deliveriesOver}). Then the
     * order is a whole return, and payment waives what is left of the fee, whichever came last - the shipment brought
     * back, or the rest closed before it shipped.</p>
     */
    static com.stockflow.payment.api.SettleShipmentReturnCommand.ShippingFee shippingFeeOf(Order order) {
        BigDecimal fee = order.fees().stream()
                .filter(line -> line.kind() == com.stockflow.order.api.FeeKind.SHIPPING_FEE)
                .map(line -> line.gross().amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (fee.signum() == 0) {
            return com.stockflow.payment.api.SettleShipmentReturnCommand.ShippingFee.NONE;
        }
        BigDecimal goods = order.lines().stream().map(line -> line.grossTotal().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new com.stockflow.payment.api.SettleShipmentReturnCommand.ShippingFee(fee, goods,
                endsWithNothingDelivered(order));
    }

    /** Q-19 edge: the order, as it stands now, ended with nothing of it delivered and nothing more coming. */
    static boolean endsWithNothingDelivered(Order order) {
        return !order.deliveredSomething() && order.deliveriesOver();
    }

    /** The note a closure at the return receipt is recorded with in the order's history. */
    static String returnedShipmentNote() {
        return VIETNAMESE.getString("order.closure.returnedShipment");
    }

    /** The order as it stands now, read under its lock. */
    OrderSummary summaryOf(UUID orderId) {
        return toSummary(lockOrder(orderId));
    }

    /** Saves a locked order a closure changed, and publishes what it registered. */
    OrderSummary saveClosed(Order order) {
        return saved(order);
    }

    @org.springframework.beans.factory.annotation.Autowired
    void returnRequests(org.springframework.beans.factory.ObjectProvider<ReturnRequestService> returnRequests) {
        this.returnRequests = returnRequests;
    }

    /**
     * The shortage choice cards of an order end when the order moves on without them (17 §4.14 step 60). Looked up,
     * not injected: the cards read this module's other services, and a constructor cycle would not start. Null in a
     * unit test built by hand: there are no cards then.
     */
    private org.springframework.beans.factory.ObjectProvider<ShortageCardService> shortageCards;

    @org.springframework.beans.factory.annotation.Autowired
    void shortageCards(org.springframework.beans.factory.ObjectProvider<ShortageCardService> shortageCards) {
        this.shortageCards = shortageCards;
    }

    /** Ends the order's live shortage cards for {@code ending}; nothing when {@code ending} is null (the card's own steps). */
    private void endShortageCards(UUID orderId, com.stockflow.order.api.ShortageCardEnd ending) {
        ShortageCardService service = ending == null || shortageCards == null ? null : shortageCards.getIfAvailable();
        if (service != null) {
            service.expireLive(orderId, ending);
        }
    }

    /**
     * WF-05 fix plan, section 2: Sales decided the delivery failure the order was held for - the goods are delivered
     * again. The hold is lifted and its row resolved; the order is PARTIALLY_FULFILLED, owing that delivery. Called by
     * {@link DeliveryFailureService} in its transaction; an order held for something else is refused by the aggregate.
     */
    Order resumeAfterDeliveryFailure(UUID orderId, String note, UUID by, Instant at) {
        Order order = lockOrder(orderId);
        String heldFor = order.holdNote();
        order.resolveDeliveryFailure(note, by, at);
        resolveHoldRow(order, heldFor, note, by, at);
        Order saved = repository.save(order);
        events.publishEventsOf(order);
        return saved;
    }

    /**
     * WF-05 fix plan, section 2 (17 §4.8 step 33, 15 §4.6 step 19): Sales decided to cancel what a failed delivery
     * left undelivered. The hold is lifted and, in the same transaction, the rest of the order is closed as
     * {@link #closeRemainder} closes it - the units that came back and those never shipped - with the fee agreed for
     * the goods already made, settled on the installments by payment. Never refunded by default: what was paid above
     * the goods delivered and the fee is what becomes refundable.
     */
    OrderSummary closeUndelivered(UUID orderId, String note, com.stockflow.order.api.CancellationFee fee, UUID by,
                                  Instant at) {
        Order order = lockOrder(orderId);
        String heldFor = order.holdNote();
        order.resolveDeliveryFailure(note, by, at);
        resolveHoldRow(order, heldFor, note, by, at);
        return closeRest(order, CancellationReasonCode.DELIVERY_FAILED, note, fee, by, at);
    }

    /**
     * Resolves the order's open hold row with Sales' decision on the failed delivery. When the shipment came back on
     * the carrier's report while the order was held for another reason, that earlier row is still open: nobody could
     * resolve it then (ck_order_hold_resolution names who resolved a hold, and no system account exists in
     * {@code identity.app_user} to name). It is resolved now, as superseded, by the deciding user, and the
     * DELIVERY_FAILED hold the order was under is put on record - raised when the shipment came back, by nobody (the
     * carrier reported it), with the order's hold note - and resolved by the decision (WF-05 review F12). The trail
     * then says what the order was held for, and when, rather than closing the old reason with this decision's note.
     *
     * @param heldFor the order's hold note before the decision lifted it
     */
    private void resolveHoldRow(Order order, String heldFor, String note, UUID by, Instant at) {
        UUID orderId = order.id().value();
        var open = holds.findFirstByOrderIdAndResolvedAtIsNullOrderByRaisedAtDesc(orderId);
        if (open.isEmpty()) {
            return;
        }
        OrderHoldJpaEntity hold = open.get();
        if (!DELIVERY_FAILED_HOLD.equals(hold.getReason())) {
            hold.resolve(by, "Superseded: a shipment came back; the order was held for the failed delivery", at);
            holds.saveAndFlush(hold);
            Instant cameBack = order.shipments().stream()
                    .filter(com.stockflow.order.internal.domain.OrderShipment::isReturned)
                    .map(com.stockflow.order.internal.domain.OrderShipment::outcomeAt).filter(java.util.Objects::nonNull)
                    .max(java.util.Comparator.naturalOrder()).orElse(at);
            hold = new OrderHoldJpaEntity(Identifiers.newId(), orderId, DELIVERY_FAILED_HOLD, cameBack, heldFor, null);
        }
        hold.resolve(by, note.trim(), at);
        holds.save(hold);
    }

    @Override
    @com.stockflow.common.audit.Auditable(action = com.stockflow.common.audit.AuditAction.TRANSITION,
            resourceType = "order", resourceId = "#orderId")
    public OrderSummary putOnHold(UUID orderId, com.stockflow.order.api.HoldReason reason, String note, UUID by) {
        Order order = lockOrder(orderId);
        order.putOnHold(reason, note, by, clock.instant());
        holds.save(new OrderHoldJpaEntity(Identifiers.newId(), orderId, reason.name(), clock.instant(), note, by));
        log.info("Order {} put on hold ({})", order.orderNumber(), reason);
        return saved(order);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The hold was lifted by a path that did not replace the missing carton (for example Sales allowing a partial
     * shipment after a carton was removed at the check station), so a live shortage card ends as
     * {@code HOLD_RESOLVED_ELSEWHERE}, not {@code REALLOCATED}: the customer is not told that the order is complete
     * (17 §4.14 step 60). Fulfillment's reallocation passes {@code REALLOCATED} through
     * {@link #resolveHold(UUID, String, UUID, com.stockflow.order.api.ShortageCardEnd)}; Sales lifting the hold by hand
     * go through the order operations with {@code DECIDED_WITHOUT_CARD}.</p>
     */
    @Override
    @com.stockflow.common.audit.Auditable(action = com.stockflow.common.audit.AuditAction.TRANSITION,
            resourceType = "order", resourceId = "#orderId")
    public OrderSummary resolveHold(UUID orderId, String note, UUID by) {
        return resolveHold(orderId, note, by, com.stockflow.order.api.ShortageCardEnd.HOLD_RESOLVED_ELSEWHERE);
    }

    /**
     * Resolves the hold, and ends the order's live shortage cards for {@code ending}: {@code DECIDED_WITHOUT_CARD} for
     * Sales lifting it by hand (D47), {@code REALLOCATED} for the warehouse's reallocation. {@code null} leaves the cards
     * as they are - for the operation that carries a card's own choice out.
     */
    @Override
    @com.stockflow.common.audit.Auditable(action = com.stockflow.common.audit.AuditAction.TRANSITION,
            resourceType = "order", resourceId = "#orderId")
    public OrderSummary resolveHold(UUID orderId, String note, UUID by,
                                    com.stockflow.order.api.ShortageCardEnd ending) {
        Order order = lockOrder(orderId);
        // A credit hold is accounting's to decide (ORDER_CREDIT_HOLD_USE_APPROVAL from the order itself).
        if (order.paidByContract() && order.status() == OrderStatus.ON_HOLD
                && order.holdReason() != null && order.holdReason().resolvedByNote()
                && !paymentService.installmentStanding(orderId).onSigningPaid()) {
            // 4B-6 (15 §4.4 step 14, BR-13): an order of a contract goes back to work only with its installment on
            // signing paid - whatever it was held for, it was confirmed by that money and a voided receipt took it.
            throw new BusinessException(ErrorCode.ORDER_BALANCE_NOT_PAID, ("Order %s is short of its installment on "
                    + "signing; record the receipt before resolving the hold").formatted(order.orderNumber()));
        }
        order.resolveHold(note, by, clock.instant());
        holds.findFirstByOrderIdAndResolvedAtIsNullOrderByRaisedAtDesc(orderId).ifPresent(hold -> {
            hold.resolve(by, note.trim(), clock.instant());
            holds.save(hold);
        });
        log.info("Order {}: hold resolved, now {}", order.orderNumber(), order.status());
        endShortageCards(orderId, ending);
        return saved(order);
    }

    @Override
    @com.stockflow.common.audit.Auditable(action = com.stockflow.common.audit.AuditAction.TRANSITION,
            resourceType = "order", resourceId = "#orderId")
    public OrderSummary closeRemainder(UUID orderId, CancellationReasonCode reasonCode, String note, UUID by) {
        // Declared here, not left to the interface's default: the class's @Transactional must cover it.
        return closeRemainder(orderId, reasonCode, note, null, by);
    }

    @Override
    @com.stockflow.common.audit.Auditable(action = com.stockflow.common.audit.AuditAction.TRANSITION,
            resourceType = "order", resourceId = "#orderId")
    public OrderSummary closeRemainder(UUID orderId, CancellationReasonCode reasonCode, String note,
                                       com.stockflow.order.api.CancellationFee fee, UUID by) {
        return closeRest(lockOrder(orderId), reasonCode, note, fee, by, clock.instant());
    }

    /**
     * "Giảm số thùng" carried out (kltn-docs 17 §4.14 step 58, D45, D52; 15 §4.6 step 19), for
     * {@link ShortageCardExecutionService#reduceCartons} under its locks (pick tasks, order, card): the missing cartons of
     * the card's batch come off the order's lines ({@link Order#dropShortCartons}; their holds were released with the
     * short pick lines), and the installments owe less by their value with VAT, no fee - payment's arithmetic of a
     * closure of the rest ({@code OrderSettlement.closeRemainder}) with that batch as the one closed: its installments
     * first, then the latest (step 19). The signed contract is not touched.
     *
     * <p>Settled as a closure of the rest (kind {@code REMAINDER_CLOSED}) named by the card - one settlement per card: the
     * same arithmetic, the same refusal of a void of a receipt it settled, nothing copied. The batch still delivering is
     * named as such, so its installment before delivery keeps gating its handover; a batch emptied by it (D52) is closed
     * whole, and what that installment still owes above its goods - its share of the fee lines - is carried onto the fee
     * item as for any batch a closure closes (audit r3 LM-3). The emptied batch is also an entry of the order's closures
     * ({@code ordering.order_remainder_closure}, reason {@code OUT_OF_STOCK}), which keeps a shipping fee a Q-19 edge
     * waives for invoicing.</p>
     *
     * @param units        per order line, the units of the cartons dropped
     * @param batchEmptied whether the batch carries nothing after this (D52)
     * @return the value dropped, with VAT
     */
    public BigDecimal dropShortCartons(UUID orderId, UUID cardId, UUID batchId, java.util.Map<UUID, Integer> units,
                                       boolean batchEmptied, String note, UUID by) {
        Order order = lockOrder(orderId);
        java.time.Instant now = clock.instant();
        BigDecimal value = order.dropShortCartons(units, by, now);
        BigDecimal waived = BigDecimal.ZERO;
        if (order.paidByContract() && value.signum() > 0) {
            // Payment's trigger reads the order's shipments and lines: the order is written first, as closeRest does.
            repository.save(order);
            // Every batch but this one counts as not closed by it, so this batch's installments are lowered first.
            java.util.Set<UUID> notClosed = contracts.findLiveByOrderId(orderId)
                    .map(com.stockflow.order.internal.domain.SalesContract::signedSchedule).orElse(List.of()).stream()
                    .map(com.stockflow.order.internal.domain.DeliveryBatch::id).filter(id -> !id.equals(batchId))
                    .collect(Collectors.toSet());
            waived = paymentService.settleRemainderClosure(new com.stockflow.payment.api.SettleRemainderClosureCommand(
                    orderId, cardId, value, null, null, null, by, now, notClosed, shippingFeeOf(order),
                    batchEmptied ? java.util.Set.of() : java.util.Set.of(batchId)))
                    .map(com.stockflow.payment.api.OrderSettlementResult::shippingFeeWaived)
                    .orElse(BigDecimal.ZERO);
        }
        if (batchEmptied) {
            remainderClosures.save(new OrderRemainderClosureJpaEntity(Identifiers.newId(), orderId, cardId,
                    CancellationReasonCode.OUT_OF_STOCK.name(), note, by, now, waived));
        }
        repository.save(order);
        log.info("Order {}: {} {} worth of missing cartons dropped from batch {} (shortage card {}){}",
                order.orderNumber(), value, order.total().currency(), batchId, cardId,
                batchEmptied ? ", the batch emptied" : "");
        return value;
    }

    /**
     * D52 (17 §4.14 step 58): the batch emptied by "Giảm số thùng" while later batches still have goods - once the hold is
     * resolved, the order waits between batches as after a batch handed over ({@link Order#batchEmptied}).
     */
    public void batchEmptied(UUID orderId, String note, UUID by) {
        Order order = lockOrder(orderId);
        order.batchEmptied(by, clock.instant(), note);
        saved(order);
    }

    /** The closure of the rest of a locked order: its units closed, their holds freed, the money settled, saved. */
    private OrderSummary closeRest(Order order, CancellationReasonCode reasonCode, String note,
                                   com.stockflow.order.api.CancellationFee fee, UUID by, java.time.Instant now) {
        UUID orderId = order.id().value();
        java.util.Map<UUID, Integer> closedBefore = order.lines().stream()
                .collect(Collectors.toMap(OrderLine::id, OrderLine::cancelledQuantity));
        // WF4-CLOSE-REST (owner decision 2026-11-13, 17 §4.8 step 33): the cartons checked and labelled for it and not
        // handed over yet stay on the order and are delivered; asked under the order's lock, which every label takes
        // too (CartonCheckService.label), so none is labelled between this answer and the closure.
        com.stockflow.order.spi.LabelledNotHandedOver station = labelled.getIfAvailable();
        java.util.Map<UUID, Integer> waiting = station == null ? java.util.Map.of() : station.unitsOf(orderId);
        BigDecimal closed = order.closeRemainder(reasonCode, note, fee, waiting, by, now);
        java.util.Map<OrderLine, Integer> closedLines = order.lines().stream()
                .filter(line -> line.cancelledQuantity() > closedBefore.get(line.id()))
                .collect(Collectors.toMap(line -> line, line -> line.cancelledQuantity() - closedBefore.get(line.id()),
                        (a, b) -> a, java.util.LinkedHashMap::new));
        // The holds of the lines with units that will not go are freed; a hold already consumed by a pick is left as
        // it is (its goods are fulfillment's to put back, G4-4).
        releaseHolds(closedLines.keySet());
        // 4B-9 (15 §4.6 step 19, BR-16): a contract order's installments owe less by the goods closed with their VAT -
        // never the mould / print or shipping fee lines (D30, D32), which closeRemainder leaves out of `closed`; money
        // paid above the order's new worth pays the fee first and the rest is to refund. In this transaction, under the
        // order's lock. WF-05 TT-5: the batches whose goods left are named, so the installments of those closed go first.
        // WF-05 Q-19 edge (owner 23/11): a closure that ends, after a carrier return, an order with nothing delivered
        // waives what the shipments brought back left of the shipping fee (shippingFeeOf, read after the closure).
        // The order is written first: payment's trigger reads its shipments (tg_order_settlement_shipping_waiver).
        // Audit r3 LM-3: the batches still delivering are named too - only the one in flight, and only when labelled
        // cartons of it stay on the order (everything else still wanted was closed just now) - so payment carries what
        // the installments before delivery of the batches closed whole still owe onto the closure's fee item.
        BigDecimal waived = BigDecimal.ZERO;
        if (order.paidByContract() && closed.signum() > 0) {
            repository.save(order);
            java.util.Set<UUID> delivering = order.outstandingUnits() == 0 ? java.util.Set.of()
                    : batches.inFlight(order).map(com.stockflow.order.internal.domain.BatchInFlight::batchId)
                            .map(java.util.Set::of).orElse(java.util.Set.of());
            waived = paymentService.settleRemainderClosure(new com.stockflow.payment.api.SettleRemainderClosureCommand(
                    orderId, order.lastClosureId(), closed, fee == null ? null : fee.amount(),
                    fee == null ? null : fee.reason(), fee == null ? null : fee.detail(), by, now,
                    HandedOverBatches.of(order), shippingFeeOf(order), delivering))
                    .map(com.stockflow.payment.api.OrderSettlementResult::shippingFeeWaived)
                    .orElse(BigDecimal.ZERO);
        }
        // WF4-CLOSE-HIST-SCHEMA: the closure is an entry of the order's history of its own, written whether or not it moved
        // the order's status (the checked cartons stay and it remains PARTIALLY_FULFILLED); a refused closure threw above.
        // Append-only, so written once payment answered: it keeps the shipping fee waived with it (Q-19 edge), which
        // invoicing reads (ShippingFeeWaivers).
        remainderClosures.save(new OrderRemainderClosureJpaEntity(Identifiers.newId(), orderId, order.lastClosureId(),
                reasonCode.name(), note, by, now, waived));
        // 17 §4.14 step 58 / 60: a card whose "ship part, close the rest" was started is carried out by this closure;
        // any other live card ends.
        ShortageCardService cardsOfOrder = shortageCards == null ? null : shortageCards.getIfAvailable();
        if (cardsOfOrder != null) {
            cardsOfOrder.remainderClosed(orderId, by);
        }
        log.info("Order {}: rest closed ({}), {} {} worth; now {}", order.orderNumber(), reasonCode, closed,
                order.total().currency(), order.status());
        OrderSummary summary = saved(order);
        // WF-05 BE-37 (L-I7): DELIVERED reached by closing the rest follows the order's open returns exactly as
        // DELIVERED reached by a delivery does (OrderDeliveredReturnsListener): a return opened while it was partly
        // fulfilled takes it to RETURN_REQUESTED now - before, it showed DELIVERED with a claim open.
        ReturnRequestService returns = returnRequests == null ? null : returnRequests.getIfAvailable();
        if (order.status() == OrderStatus.DELIVERED && returns != null) {
            returns.followDelivery(orderId, by);
            return toSummary(lockOrder(orderId));
        }
        return summary;
    }

    private OrderSummary saved(Order order) {
        Order saved = repository.save(order);
        closeUndecidableCancellationRequest(order);
        events.publishEventsOf(order);
        return toSummary(saved);
    }

    /**
     * E2E QA 2026-10-21: a customer's cancellation request still waiting when the order passes the point where
     * cancelling can be decided - its first carton labelled (4C-6, 08 BR-04), handed over, delivered - or is
     * cancelled another way, could only ever be refused ({@code ORDER_NOT_CANCELLABLE}), and the customer saw it pending
     * for good. It closes here, in the transaction that moved the order, as {@code EXPIRED} with a note the customer
     * reads on the order. Called after every save that may move the order; nothing to do in the common case.
     */
    private void closeUndecidableCancellationRequest(Order order) {
        OrderStatus status = order.status();
        String why;
        if (status == OrderStatus.CANCELLED) {
            why = "Closed: the order was cancelled.";
        } else if (!status.canTransitionTo(OrderStatus.CANCELLED)) {
            why = "Closed: the order is %s and can no longer be cancelled; ask for a return instead.".formatted(status);
        } else if (order.packedForGood()) {
            why = "Closed: a carton of the order is labelled for shipping, so it can no longer be cancelled; ask for a "
                    + "return instead.";
        } else {
            return;
        }
        cancellationRequests.findPending(order.id().value()).ifPresent(request -> {
            request.expire(why, clock.instant());
            cancellationRequests.save(request);
            log.info("Order {}: cancellation request {} closed as EXPIRED ({})", order.orderNumber(), request.id(),
                    status);
        });
    }

    /** A fee line's id from the request and what it is for: a retried placement writes the same row. */
    static UUID deterministicFeeId(UUID requestId, com.stockflow.order.api.FeeKind kind,
                                   com.stockflow.common.domain.Sku sku) {
        return UUID.nameUUIDFromBytes(("order-fee:" + requestId + ":" + kind + ":" + (sku == null ? "" : sku.code()))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Stable line identity: same request, same position, same id. */
    private static UUID deterministicLineId(UUID requestId, int index) {
        return UUID.nameUUIDFromBytes((requestId + "#line#" + index).getBytes(StandardCharsets.UTF_8));
    }

    private OrderSummary toSummary(Order order) {
        return new OrderSummary(
                order.id().value(),
                order.orderNumber().value(),
                order.customerId(),
                order.status(),
                order.total(),
                order.lines().stream()
                        .map(line -> new OrderSummary.LineSummary(
                                line.id(),
                                line.sku().code(),
                                line.quantity(),
                                line.unitPrice(),
                                line.lineTotal(),
                                line.reservationIds(), line.designSnapshotId(), line.designChecksum(),
                                line.requestedDeliveryDate(), line.cartons(), line.unitsPerCarton(), line.taxRate(),
                                line.taxAmount()))
                        .toList(),
                order.placedAt(), order.createdBy(), order.lastModifiedAt(), order.lastModifiedBy(),
                order.contactName(), order.contactEmail(), order.contactPhone(),
                toSummary(order.shippingAddress()), toSummary(order.billingAddress()),
                order.warehouseId(), order.releasedAt(),
                order.status() == OrderStatus.CANCELLED
                        ? new OrderSummary.Cancellation(order.cancellationReasonCode(), order.cancellationReason(),
                                order.cancellationRetainedAmount(), order.cancellationFeeReason(),
                                order.cancellationFeeDetail(), order.cancellationFeeAmount())
                        : null,
                lifecycleOf(order),
                new OrderSummary.Charges(order.netTotal(), order.taxTotal(), order.fees().stream()
                        .map(fee -> new OrderSummary.FeeSummary(fee.kind(), fee.sku() == null ? null : fee.sku().code(),
                                fee.amount(), fee.taxRate(), fee.taxAmount())).toList()),
                order.version());
    }

    /** The hold, the milestones, how much of each line went out and the shipments (G3-2). */
    static OrderSummary.Lifecycle lifecycleOf(Order order) {
        List<OrderSummary.LineProgress> progress = order.lines().stream()
                .map(line -> new OrderSummary.LineProgress(line.id(),
                        order.shipments().stream().filter(com.stockflow.order.internal.domain.OrderShipment::holdsUnits)
                                .mapToInt(s -> s.quantities().getOrDefault(line.id(), 0)).sum(),
                        order.shipments().stream()
                                .filter(s -> s.status() == com.stockflow.order.api.OrderShipmentStatus.DELIVERED)
                                .mapToInt(s -> s.quantities().getOrDefault(line.id(), 0)).sum(),
                        line.cancelledQuantity()))
                .toList();
        List<OrderSummary.Shipment> shipments = order.shipments().stream()
                .map(s -> new OrderSummary.Shipment(s.id(), s.status(), s.amount(), s.handedOverAt(),
                        s.outcomeAt(), s.failureReason(),
                        s.quantities().entrySet().stream()
                                .map(e -> new OrderSummary.ShipmentLine(e.getKey(), e.getValue())).toList(),
                        s.carriage(), s.unitsClosedAt()))
                .toList();
        return new OrderSummary.Lifecycle(order.holdReason(), order.statusBeforeHold(), order.holdNote(),
                order.packedAt(), order.shippedAt(), order.deliveredAt(), order.deliveryFailureReason(), order.completedAt(),
                order.closedAt(), order.billable(), order.amountDueBeforeHandover(), progress, shipments, order.allowPartialShipment(), order.goodsLeftAt(),
                order.firstLabeledAt());
    }

    private static OrderAddressSnapshot toSnapshot(CheckoutCustomer.CheckoutAddress address) {
        return new OrderAddressSnapshot(address.recipientName(), address.phone(), address.line1(),
                address.line2(), address.wardCode(), address.wardName(), address.provinceCode(),
                address.provinceName(), address.countryCode(), address.postalCode());
    }

    private static OrderSummary.AddressSummary toSummary(OrderAddressSnapshot address) {
        if (address == null) return null;
        return new OrderSummary.AddressSummary(address.recipientName(), address.phone(),
                address.line1(), address.line2(), address.wardCode(), address.wardName(),
                address.provinceCode(), address.provinceName(), address.countryCode(),
                address.postalCode());
    }
}
