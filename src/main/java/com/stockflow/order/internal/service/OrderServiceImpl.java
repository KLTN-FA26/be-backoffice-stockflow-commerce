package com.stockflow.order.internal.service;

import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReserveStockResult;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.customer.api.CheckoutCustomer;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CreditTerms;
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
import com.stockflow.order.api.PlaceGuestOrderCommand;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderAddressSnapshot;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.CancellationRequest;
import com.stockflow.order.internal.domain.CancellationRequestRepository;
import com.stockflow.order.internal.domain.OrderPayments;
import com.stockflow.order.internal.domain.CreditChecks;
import com.stockflow.order.api.PaymentTerm;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import java.util.UUID;
import com.stockflow.catalog.api.CatalogService;
import com.stockflow.common.domain.Money;
import com.stockflow.common.id.Identifiers;
import com.stockflow.design.api.DesignService;
import com.stockflow.order.internal.entity.OrderHoldJpaEntity;
import com.stockflow.order.internal.repository.OrderHoldJpaRepository;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Order orchestration — and <b>the single clearest illustration of what this architecture buys</b>.
 *
 * <h2>Read {@link #placeOrder} against its distributed equivalent</h2>
 *
 * <p>In the microservices build this operation spanned two services and needed all of the
 * following, none of which is business logic:</p>
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
 * paid, the stock was never held — that only shows up under load. Below, the same operation is one
 * method, and the failure mode is gone: if anything throws, the transaction rolls back and both
 * the order and the reservation cease to exist together.</p>
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
class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    /** The days a coordinator filters by are Saigon days: "placed on the 8th" ends at 17:00 UTC. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** {@code ordering.order_hold.reason} of an order over its customer's credit limit (SCRUM-427). */
    static final String CREDIT_HOLD = "CREDIT";

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
    private final CatalogService catalog;
    private final OrderPayments payments;
    private final CancellationRequestRepository cancellationRequests;
    private final CreditChecks creditChecks;
    private final com.stockflow.payment.api.PaymentService paymentService;

    OrderServiceImpl(OrderRepository repository, OrderSearchRepository search,
                     InventoryService inventory, OrderEventPublisher events, Clock clock,
            DesignService designs,
            OrderHoldJpaRepository holds,
                     CustomerService customers,
            CatalogService catalog, OrderPayments payments,
                     CancellationRequestRepository cancellationRequests, CreditChecks creditChecks,
                     com.stockflow.payment.api.PaymentService paymentService) {
        this.repository = repository;
        this.search = search;
        this.inventory = inventory;
        this.events = events;
        this.clock = clock;
        this.designs = designs;
        this.holds = holds;
        this.customers = customers;
        this.catalog = catalog;
        this.payments = payments;
        this.cancellationRequests = cancellationRequests;
        this.creditChecks = creditChecks;
        this.paymentService = paymentService;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>One transaction covers everything below.</b> The order rows, every stock reservation,
     * and the {@code event_publication} row for {@code OrderPlaced} all commit together or none of
     * them do. There is no window in which an order exists without its stock.</p>
     */
    @Override
    public OrderSummary placeOrder(PlaceOrderCommand command) {
        // Idempotency first. A double-clicked "Place order" must not create two orders, and this
        // check is cheap compared to discovering the duplicate after the customer has paid twice.
        Optional<Order> alreadyPlaced = repository.findByRequestId(command.requestId());
        if (alreadyPlaced.isPresent()) {
            if (!command.customerId().equals(alreadyPlaced.get().customerId())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            log.info("Request {} already produced order {}",
                    command.requestId(), alreadyPlaced.get().orderNumber());
            return toSummary(alreadyPlaced.get());
        }

        // SCRUM-298 is a separate integration: never fall back to a client-supplied design price.
        if (command.snapshotCustomer()
                && command.lines().stream().anyMatch(line -> line.designSnapshotId() != null)) {
            throw new BusinessException(ErrorCode.DESIGN_QUOTE_REQUIRED);
        }
        var sellingPrices =
                command.snapshotCustomer()
                        ? catalog.checkoutPrices(
                                command.lines().stream()
                                        .filter(line -> line.designSnapshotId() == null)
                                        .map(line -> line.sku().code())
                                        .collect(Collectors.toSet()))
                        : Map.<String, Money>of();
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        OrderNumber orderNumber = repository.nextOrderNumber(today);

        // Line ids derived from the checkout request id and the line's position, not random.
        // A random id would make the per-line reservation key below different on every attempt,
        // which quietly breaks the retry-safety this method advertises: a retry would look like a
        // brand-new request to inventory and hold the stock a second time.
        List<OrderLine> lines = new ArrayList<>();
        for (int index = 0; index < command.lines().size(); index++) {
            PlaceOrderCommand.Line line = command.lines().get(index);
            var orderLine = Order.line(deterministicLineId(command.requestId(), index),
                    line.sku(), line.quantity(),
                            command.snapshotCustomer()
                                    ? agreedPrice(
                                            line.sku().code(), line.unitPrice(), sellingPrices)
                                    : line.unitPrice(), line.designSnapshotId());
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
        // Before any stock is held: terms that cannot be accepted refuse the order cheaply. The
        // customer row is locked here, before reserving, so two credit orders of one customer are
        // checked against the limit one after the other (kltn-docs 15 BR-03) and every checkout takes
        // its locks in the same order.
        CreditTerms terms = customers.lockCreditTerms(command.customerId());
        PaymentTerm term = termFor(command.paymentTerm(), terms);
        order.applyTerms(term,
                term == PaymentTerm.DEPOSIT && command.depositPercent() == null ? terms.depositPercent()
                        : command.depositPercent(),
                term == PaymentTerm.CREDIT ? terms.creditTermDays() : null);

        // Direct in-process call across the module boundary, through inventory's published port.
        // It joins this transaction. If reserve() throws InsufficientStockException on line 3,
        // lines 1 and 2 are rolled back with the order — no compensation code, no saga state, no
        // possibility of a half-reserved order surviving the failure.
        inventory.prepareReservation(
                order.lines().stream().map(OrderLine::sku).collect(Collectors.toSet()));
        for (OrderLine line : order.lines()) {
            ReserveStockResult reservation = inventory.reserve(new ReserveStockCommand(
                    // A derived, deterministic request id: a retry of this same checkout produces
                    // the same key, so inventory recognises the replay instead of double-holding.
                                    deterministicRequestId(command.requestId(), line.id()),
                    line.sku(),
                    line.quantity(),
                    order.id().value()));
            // All of the ids, not the first: a line drawn from two lots comes back with two holds,
            // and cancelling has to release both.
            order.attachReservations(line.id(), reservation.reservationIds());
        }

        Order saved;
        if (term == PaymentTerm.CREDIT) {
            saved = submitOnCredit(order, terms);
        } else {
            order.submit();
            saved = repository.save(order);
        }
        events.publishEventsOf(order);

        log.info("Placed order {} for customer {} with {} line(s), total {}, {} ({})",
                saved.orderNumber(), saved.customerId(), saved.lines().size(), saved.total(), term, saved.status());
        return toSummary(saved);
    }

    /** kltn-docs 15 BR-01: the term asked for, or the customer's default, and only one they may use. */
    private static PaymentTerm termFor(PaymentTerm requested, CreditTerms terms) {
        PaymentTerm term = requested != null ? requested : PaymentTerm.valueOf(terms.defaultTerm().name());
        if (!terms.allows(CommercialTerm.valueOf(term.name()))) {
            throw new BusinessException(ErrorCode.PAYMENT_TERM_NOT_ALLOWED,
                    "Customer %s may not be sold on %s terms".formatted(terms.customerId(), term));
        }
        return term;
    }

    /**
     * kltn-docs 15 §4.3 and BR-03: a credit order is CONFIRMED when what the customer already owes plus
     * this order stays within the limit, otherwise ON_HOLD (hold reason CREDIT) for whoever approves
     * credit. What the customer owes is the undelivered credit orders plus the open receivables of
     * delivered ones (SCRUM-431); a customer with an overdue receivable gets no new credit without the
     * approver (15 §4.3 step 4). Either way the order waits for no payment, so its holds are pinned now.
     */
    private Order submitOnCredit(Order order, CreditTerms terms) {
        var position = paymentService.creditPosition(order.customerId());
        BigDecimal exposure = creditChecks.undeliveredCreditExposure(order.customerId(), order.id().value())
                .add(position.outstandingReceivables());
        BigDecimal amount = order.total().amount();
        CreditChecks.Outcome outcome = position.hasOverdue() ? CreditChecks.Outcome.OVERDUE
                : exposure.add(amount).compareTo(terms.creditLimit()) <= 0 ? CreditChecks.Outcome.WITHIN_LIMIT
                : CreditChecks.Outcome.OVER_LIMIT;
        boolean within = outcome == CreditChecks.Outcome.WITHIN_LIMIT;
        order.submitOnCredit(within);
        Order saved = repository.save(order);
        java.time.Instant now = clock.instant();
        creditChecks.record(new CreditChecks.Check(order.id().value(), now, terms.creditLimit(), exposure, amount,
                outcome, null, null, null));
        if (!within) {
            holds.save(new OrderHoldJpaEntity(Identifiers.newId(), order.id().value(), CREDIT_HOLD, now));
        }
        inventory.pinReservations(order.id().value());
        return saved;
    }

    @Override
    public OrderSummary placeGuestOrder(PlaceGuestOrderCommand command) {
        String email = command.email().trim().toLowerCase(Locale.ROOT);
        // Construct snapshots before the replay lookup as well: a reused request id must not make
        // malformed address data appear valid merely because an earlier checkout succeeded.
        var shipping = toSnapshot(command.shippingAddress());
        var billing = toSnapshot(command.billingAddress());
        Optional<Order> alreadyPlaced = repository.findByRequestId(command.requestId());
        if (alreadyPlaced.isPresent()) {
            if (!sameGuestCheckout(alreadyPlaced.get(), email, shipping, billing, command.lines())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return toSummary(alreadyPlaced.get());
        }

        var sellingPrices =
                catalog.checkoutPrices(
                        command.lines().stream()
                                .map(line -> line.sku().code())
                                .collect(Collectors.toSet()));
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        OrderNumber orderNumber = repository.nextOrderNumber(today);
        List<OrderLine> lines = new ArrayList<>();
        for (int index = 0; index < command.lines().size(); index++) {
            PlaceGuestOrderCommand.Line line = command.lines().get(index);
            lines.add(Order.line(deterministicLineId(command.requestId(), index), line.sku(),
                    line.quantity(),
                            agreedPrice(line.sku().code(), line.unitPrice(), sellingPrices), null));
        }
        Order order = Order.guestDraft(orderNumber, command.requestId(), lines, clock.instant(),
                shipping.recipientName(), email,
                shipping.phone(), shipping, billing);
        reserve(order, command.requestId());
        order.submit();
        Order saved = repository.save(order);
        events.publishEventsOf(order);
        return toSummary(saved);
    }

    /**
     * A body {@code requestId} is an idempotency key, not an order lookup key. Returning an old
     * order for a changed basket or address would make a customer believe the changed checkout was
     * accepted while fulfilment receives the earlier one. The HTTP idempotency filter protects
     * normal callers too; this comparison is the durable, service-level backstop for retries that
     * reach this use case directly.
     */
    private static Money agreedPrice(String sku, Money expected, Map<String, Money> prices) {
        var actual = prices.get(sku);
        if (actual == null) throw new BusinessException(ErrorCode.PRICE_NOT_AVAILABLE);
        if (!actual.equals(expected)) throw new BusinessException(ErrorCode.CHECKOUT_PRICE_CHANGED);
        return actual;
    }

    private static boolean sameGuestCheckout(Order order, String email,
                                             OrderAddressSnapshot shipping,
                                             OrderAddressSnapshot billing,
                                             List<PlaceGuestOrderCommand.Line> requestedLines) {
        if (order.customerId() != null
                || !email.equals(order.contactEmail())
                || !shipping.equals(order.shippingAddress())
                || !billing.equals(order.billingAddress())
                || order.lines().size() != requestedLines.size()) {
            return false;
        }
        for (int index = 0; index < requestedLines.size(); index++) {
            PlaceGuestOrderCommand.Line requested = requestedLines.get(index);
            OrderLine stored = order.lines().get(index);
            if (!requested.sku().equals(stored.sku())
                    || requested.quantity() != stored.quantity()
                    || !requested.unitPrice().equals(stored.unitPrice())
                    || stored.designSnapshotId() != null
                    || stored.designChecksum() != null) {
                return false;
            }
        }
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderSummary> findById(UUID orderId) {
        return repository.findById(new OrderId(orderId)).map(this::toSummary);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Symmetrical with {@link #placeOrder}: the status change and every stock release are one
     * transaction. The distributed version needed a compensating command per line plus a
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

    /**
     * {@inheritDoc}
     *
     * <p>A second request while one is pending is refused rather than stacked: the customer has
     * already asked, and Sales should see one question per order.</p>
     */
    @Override
    public CancellationOutcome cancelOwn(UUID orderId, UUID customerId, CancellationReasonCode reasonCode,
                                         String note, UUID requestedBy) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId))
                .filter(found -> customerId != null && customerId.equals(found.customerId()))
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
        order.cancel(command.reasonCode(), command.note(), command.retainedPercent(), command.cancelledBy(),
                clock.instant());

        if (wasHoldingStock) {
            order.reservationIds()
                    .forEach(reservationId -> inventory.release(reservationId, "ORDER_CANCELLED"));
            order.clearReservations();
        }

        Order saved = repository.save(order);
        events.publishEventsOf(order);
        log.info("Cancelled order {} ({})", order.orderNumber(), order.cancellationReason());
        return saved;
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
                query.placedTo() == null ? null : query.placedTo().plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant());
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
     * never from a client-supplied parameter — this method applies no scope check of its own, the
     * same trust boundary {@link #placeOrder} already relies on for {@code
     * PlaceOrderCommand.customerId()}.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> myOrders(UUID customerId, int page, int size) {
        var pageable = Pages.of(page, size, Sort.by(Sort.Direction.DESC, "lastModifiedAt"));
        return Pages.toResponse(search.findByCustomerId(customerId, pageable));
    }

    /**
     * Cancel an order whose payment failed, <b>without</b> touching inventory.
     *
     * <p>Separate from {@link #cancel} on purpose. On the payment-failed path, inventory has its
     * own listener on the same event and releases its own holds; calling
     * {@code inventory.release(...)} from here as well would work — the release is idempotent —
     * but it would encode "what inventory does when a payment fails" inside the order module, and
     * that is inventory's business, not order's.</p>
     */
    public void cancelAfterPaymentFailure(UUID orderId, String failureCode) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new IllegalArgumentException("No order with id " + orderId));
        if (order.status() == OrderStatus.CANCELLED) {
            return; // Redelivered event; already handled.
        }
        order.cancel(CancellationReasonCode.PAYMENT_FAILED, failureCode, null, null, clock.instant());
        order.clearReservations();
        repository.save(order);
        events.publishEventsOf(order);
    }

    /**
     * Count a captured payment, and confirm the order when it is the money its terms ask for (kltn-docs
     * 15 BR-02). Called by {@code PaymentEventListener}, not exposed on the port.
     *
     * <p>Counted once per payment ({@code ordering.order_payment}): the event is delivered at least
     * once. Confirming pins the holds in this transaction (kltn-docs 14 BR-07, SCRUM-465): a
     * confirmed order's stock no longer expires, and committing the status and the pins together
     * leaves the expiry sweep no moment in which the order is confirmed but its holds can still lapse.</p>
     *
     * <p>Money in a currency the order is not in is not added up: it is logged for a person to sort
     * out, not retried forever. Money for an order already cancelled — its hold expired before the
     * money arrived — is counted and logged for Sales to settle with the customer (kltn-docs 15).</p>
     */
    public void recordPayment(UUID orderId, UUID paymentId, java.math.BigDecimal amount, String currency) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new IllegalArgumentException("No order with id " + orderId));
        if (!order.total().currency().getCurrencyCode().equals(currency)) {
            log.error("Payment {} for order {} is in {}, the order in {}; not counted",
                    paymentId, order.orderNumber(), currency, order.total().currency());
            return;
        }
        if (!payments.record(paymentId, orderId, amount, currency, clock.instant())) {
            return; // Redelivered event; already counted.
        }
        boolean confirmed = order.recordPayment(new Money(amount, order.total().currency()), clock.instant());
        if (confirmed) {
            inventory.pinReservations(orderId);
        }
        if (order.status() == OrderStatus.CANCELLED) {
            log.warn("Payment {} of {} {} arrived for order {}, already cancelled ({}); settle it with the customer",
                    paymentId, amount, currency, order.orderNumber(), order.cancellationReason());
        }
        repository.save(order);
        events.publishEventsOf(order);
    }

    /**
     * A hold of the order expired before it was paid: the order is cancelled and its other holds
     * released (kltn-docs 14 BR-07, 15 step 4). Called by {@code InventoryEventListener} for every
     * expired hold — the first one cancels, the rest find the order already cancelled. An order that
     * is no longer waiting for payment is left alone: its holds are pinned and cannot expire, so the
     * event can only be a late redelivery.
     */
    public void cancelAfterHoldExpired(UUID orderId) {
        Optional<Order> found = repository.findByIdForUpdate(new OrderId(orderId));
        if (found.isEmpty() || found.get().status() != OrderStatus.PENDING_PAYMENT) {
            return;
        }
        cancelLoaded(found.get(), new CancelOrderCommand(CancellationReasonCode.PAYMENT_NOT_RECEIVED,
                "not paid before the stock hold ran out", null, null));
    }

    @Override
    @com.stockflow.common.audit.Auditable(action = com.stockflow.common.audit.AuditAction.TRANSITION,
            resourceType = "order", resourceId = "#orderId")
    public OrderSummary recordDelivery(UUID orderId) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
        if (order.status() == OrderStatus.DELIVERED) {
            return toSummary(order);
        }
        order.markDelivered(clock.instant());
        Order saved = repository.save(order);
        events.publishEventsOf(order);
        log.info("Order {} delivered", order.orderNumber());
        return toSummary(saved);
    }

    @Override
    public OrderSummary releaseToFulfillment(UUID orderId) {
        var order = repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (order.status() == OrderStatus.IN_FULFILMENT) { return toSummary(order); }
        order.startFulfilment();
        Order saved = repository.save(order);
        events.publishEventsOf(order);
        return toSummary(saved);
    }

    @Override
    public void putOnDesignHold(UUID orderId, String reason) {
        var order = repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (order.status() == OrderStatus.ON_HOLD) { return; }
        order.putOnHold();
        repository.save(order);
        holds.save(new OrderHoldJpaEntity(Identifiers.newId(), orderId, reason, clock.instant()));
    }

    @Override
    public void resolveDesignHold(UUID orderId, UUID resolvedBy, String note) {
        var order = repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var hold = holds.findFirstByOrderIdAndResolvedAtIsNullOrderByRaisedAtDesc(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT));
        order.resumeFromHold();
        hold.resolve(resolvedBy, note, clock.instant());
        repository.save(order);
        holds.save(hold);
    }

    /**
     * Same input, same id, every time.
     *
     * <p>A random {@code UUID.randomUUID()} here would defeat inventory's idempotency check on the
     * first retry: the same checkout would arrive with a new key and hold the stock a second time.
     * Deriving the id from the checkout's request id and the line id makes the retry recognisable.
     * Type 3 (name-based) semantics, built from the two ids so the result is stable across
     * processes and restarts.</p>
     */
    private static UUID deterministicRequestId(UUID requestId, UUID lineId) {
        return UUID.nameUUIDFromBytes((requestId + ":" + lineId).getBytes(StandardCharsets.UTF_8));
    }

    /** Stable line identity: same checkout request, same position, same id. */
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
                                line.reservationIds(), line.designSnapshotId(), line.designChecksum()))
                        .toList(),
                order.placedAt(), order.createdBy(), order.lastModifiedAt(), order.lastModifiedBy(),
                order.contactName(), order.contactEmail(), order.contactPhone(),
                toSummary(order.shippingAddress()), toSummary(order.billingAddress()),
                order.paymentTerm(), order.warehouseId(), order.releasedAt(),
                new OrderSummary.Payment(order.paidAmount(), order.depositRequired(), order.depositReceivedAt(),
                        order.paidInFullAt(), order.paymentStatus(), order.creditTermDays()),
                order.status() == OrderStatus.CANCELLED
                        ? new OrderSummary.Cancellation(order.cancellationReasonCode(), order.cancellationReason(),
                                order.cancellationRetainedAmount())
                        : null);
    }

    private void reserve(Order order, UUID requestId) {
        inventory.prepareReservation(
                order.lines().stream().map(OrderLine::sku).collect(Collectors.toSet()));
        for (OrderLine line : order.lines()) {
            ReserveStockResult reservation = inventory.reserve(new ReserveStockCommand(
                    deterministicRequestId(requestId, line.id()), line.sku(), line.quantity(),
                    order.id().value()));
            order.attachReservations(line.id(), reservation.reservationIds());
        }
    }

    private static OrderAddressSnapshot toSnapshot(PlaceGuestOrderCommand.Address address) {
        return new OrderAddressSnapshot(address.recipientName(), address.phone(), address.line1(),
                address.line2(), address.wardCode(), address.wardName(), address.provinceCode(),
                address.provinceName(), address.countryCode(), address.postalCode());
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
