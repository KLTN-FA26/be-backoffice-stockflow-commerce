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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import java.util.UUID;
import com.stockflow.common.id.Identifiers;
import com.stockflow.design.api.DesignService;
import com.stockflow.order.internal.entity.OrderHoldJpaEntity;
import com.stockflow.order.internal.repository.OrderHoldJpaRepository;
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
                     com.stockflow.order.internal.domain.DeliveryFailureCases deliveryFailures) {
        this.deliveryFailures = deliveryFailures;
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
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
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
        log.info("Order {} drafted for customer {} from an accepted quote, {} line(s), total {}",
                saved.orderNumber(), saved.customerId(), saved.lines().size(), saved.total());
        return toSummary(saved);
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
        return new CancellationOutcome(CancellationOutcome.Result.REQUESTED, request.id(), toSummary(order));
    }

    /**
     * Cancels a loaded, locked order: releases its holds, records the reason and publishes
     * {@code OrderCancelled}, all in this transaction. Shared by every cancellation path, including
     * an approved request ({@code CancellationRequestService}).
     */
    Order cancelLoaded(Order order, CancelOrderCommand command) {
        boolean wasHoldingStock = order.status().holdsStock();
        java.time.Instant now = clock.instant();
        order.cancel(command.reasonCode(), command.note(), command.retainedPercent(), command.agreedFee(),
                command.cancelledBy(), now);
        // 17 §4.8 step 27: an order cancelled before its contract is signed takes the contract with it. A signed
        // contract stays SIGNED: it is settled outside the system (14 §5.2, decided 2026-11-05).
        contracts.findLiveByOrderId(order.id().value())
                .filter(contract -> contract.status().canTransitionTo(
                        com.stockflow.order.internal.domain.SalesContractStatus.CANCELLED))
                .ifPresent(contract -> {
                    // Someone cancels an order that has a contract: Sales, accounting or the customer's approved
                    // request - never the system on its own, which would have no one to name on the contract.
                    contract.cancel("Order cancelled: " + command.reasonCode(), clock.instant(),
                            java.util.Objects.requireNonNull(command.cancelledBy(),
                                    "an order with a contract is cancelled by someone"));
                    contracts.save(contract);
                });

        if (wasHoldingStock) {
            releaseHolds(order.lines());
            order.clearReservations();
        }

        Order saved = repository.save(order);
        settleContractMoney(order, command, now);
        closeUndecidableCancellationRequest(order);
        events.publishEventsOf(order);
        // The code, not the reason text: the text carries the customer's note, and a line break in it
        // would forge log lines (CodeQL java/log-injection).
        log.info("Cancelled order {} ({})", order.orderNumber(), order.cancellationReasonCode());
        return saved;
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
        String note = "Payment receipt %s voided: the installment on signing is short".formatted(reference);
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
            try {
                order.requireHandoverAllowed(due, batch.orElse(null), businessDay(at));
            } catch (BusinessException refused) {
                blocks.add(new com.stockflow.order.api.HandoverBlock(orderId, order.orderNumber().value(),
                        refused.errorCode().name(), due == null ? java.math.BigDecimal.ZERO : due,
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
    public OrderSummary correctShipmentToFailed(UUID orderId, UUID shipmentId,
                                                com.stockflow.order.api.DeliveryFailureReason reason, String note,
                                                Instant at, UUID by) {
        Order order = lockOrder(orderId);
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
        // WF-05 fix plan, sections 2-3: the order is held for the failed delivery from here. The hold is on record as
        // any hold is (one open at a time, uk_order_active_hold); one raised earlier for another reason gives way to
        // it - that decision comes first. A hold is resolved by a person (ck_order_hold_resolution), so when the
        // carrier's report brought the shipment back (no actor) the earlier row stays open, and Sales' decision on the
        // failed delivery resolves it.
        var open = holds.findFirstByOrderIdAndResolvedAtIsNullOrderByRaisedAtDesc(orderId);
        boolean superseded = open.isPresent() && !DELIVERY_FAILED_HOLD.equals(open.get().getReason()) && by != null;
        if (superseded) {
            open.get().resolve(by, "Superseded: a shipment came back; the order is held for the failed delivery", at);
            holds.saveAndFlush(open.get());
        }
        if (open.isEmpty() || superseded) {
            holds.save(new OrderHoldJpaEntity(Identifiers.newId(), orderId, DELIVERY_FAILED_HOLD, at,
                    order.holdNote(), by));
        }
        log.info("Order {}: shipment {} returned, order {} for {}", order.orderNumber(), shipmentId, order.status(),
                order.holdReason());
        return saved(order);
    }

    /**
     * WF-05 fix plan, section 2: Sales decided the delivery failure the order was held for - the goods are delivered
     * again. The hold is lifted and its row resolved; the order is PARTIALLY_FULFILLED, owing that delivery. Called by
     * {@link DeliveryFailureService} in its transaction; an order held for something else is refused by the aggregate.
     */
    Order resumeAfterDeliveryFailure(UUID orderId, String note, UUID by, Instant at) {
        Order order = lockOrder(orderId);
        order.resolveDeliveryFailure(note, by, at);
        resolveHoldRow(orderId, note, by, at);
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
        order.resolveDeliveryFailure(note, by, at);
        resolveHoldRow(orderId, note, by, at);
        return closeRest(order, CancellationReasonCode.DELIVERY_FAILED, note, fee, by, at);
    }

    private void resolveHoldRow(UUID orderId, String note, UUID by, Instant at) {
        holds.findFirstByOrderIdAndResolvedAtIsNullOrderByRaisedAtDesc(orderId).ifPresent(hold -> {
            hold.resolve(by, note.trim(), at);
            holds.save(hold);
        });
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

    @Override
    @com.stockflow.common.audit.Auditable(action = com.stockflow.common.audit.AuditAction.TRANSITION,
            resourceType = "order", resourceId = "#orderId")
    public OrderSummary resolveHold(UUID orderId, String note, UUID by) {
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

    /** The closure of the rest of a locked order: its units closed, their holds freed, the money settled, saved. */
    private OrderSummary closeRest(Order order, CancellationReasonCode reasonCode, String note,
                                   com.stockflow.order.api.CancellationFee fee, UUID by, java.time.Instant now) {
        UUID orderId = order.id().value();
        java.util.Map<UUID, Integer> closedBefore = order.lines().stream()
                .collect(Collectors.toMap(OrderLine::id, OrderLine::cancelledQuantity));
        BigDecimal closed = order.closeRemainder(reasonCode, note, fee, by, now);
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
        // order's lock.
        if (order.paidByContract() && closed.signum() > 0) {
            paymentService.settleRemainderClosure(new com.stockflow.payment.api.SettleRemainderClosureCommand(orderId,
                    order.lastClosureId(), closed, fee == null ? null : fee.amount(), fee == null ? null : fee.reason(),
                    fee == null ? null : fee.detail(), by, now));
        }
        log.info("Order {}: rest closed ({}), {} {} worth; now {}", order.orderNumber(), reasonCode, closed,
                order.total().currency(), order.status());
        return saved(order);
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
                                fee.amount(), fee.taxRate(), fee.taxAmount())).toList()));
    }

    /** The hold, the milestones, how much of each line went out and the shipments (G3-2). */
    static OrderSummary.Lifecycle lifecycleOf(Order order) {
        List<OrderSummary.LineProgress> progress = order.lines().stream()
                .map(line -> new OrderSummary.LineProgress(line.id(),
                        order.shipments().stream().filter(s -> !s.isReturned())
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
                        s.carriage()))
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
