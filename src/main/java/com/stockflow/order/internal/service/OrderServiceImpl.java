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
class OrderServiceImpl implements OrderService, QuotedOrders {

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
        return place(command, false);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The same transaction and steps as {@link #placeOrder}; only the price source differs. Every line
     * — design lines included — is placed at the price on the command, which the quote service computed
     * from the accepted quote. This is why the method is on an internal interface and not on
     * {@code OrderService}: a client-supplied price never reaches it.</p>
     */
    @Override
    public OrderSummary placeQuotedOrder(PlaceOrderCommand command) {
        return place(command, true);
    }

    private OrderSummary place(PlaceOrderCommand command, boolean quotePriced) {
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

        // SCRUM-298 is a separate integration: never fall back to a client-supplied design price. A
        // design line is priced by an accepted quote (SCRUM-448), which arrives through placeQuotedOrder.
        if (!quotePriced && command.snapshotCustomer()
                && command.lines().stream().anyMatch(line -> line.designSnapshotId() != null)) {
            throw new BusinessException(ErrorCode.DESIGN_QUOTE_REQUIRED);
        }
        // SCRUM-428: the customer's own tier for the order's total of each SKU, else its group's or the
        // base price (kltn-docs 13 §3); the client's price is only what it expects to pay (agreedPrice).
        var sellingPrices =
                command.snapshotCustomer() && !quotePriced
                        ? catalog.checkoutPrices(command.customerId(),
                                command.lines().stream()
                                        .filter(line -> line.designSnapshotId() == null)
                                        .collect(Collectors.toMap(line -> line.sku().code(),
                                                PlaceOrderCommand.Line::quantity, Integer::sum)))
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
                            command.snapshotCustomer() && !quotePriced
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
        com.stockflow.order.api.BalancePolicy policy = balancePolicyFor(term, command.balancePolicy(), terms);
        boolean balanceOnCredit = policy == com.stockflow.order.api.BalancePolicy.ON_CREDIT;
        BigDecimal depositPercent = term == PaymentTerm.DEPOSIT && command.depositPercent() == null
                ? terms.depositPercent() : command.depositPercent();
        order.applyTerms(term, depositPercent,
                term == PaymentTerm.CREDIT || balanceOnCredit ? terms.creditTermDays() : null,
                policy);
        // BALCREDIT: the balance goes on credit only within the limit - checked here, before any stock is held.
        BalanceCredit balanceCredit = null;
        if (balanceOnCredit) {
            try {
                balanceCredit = requireBalanceWithinLimit(order, terms);
            } catch (BusinessException refused) {
                boolean creditRefusal = refused.errorCode() == ErrorCode.BALANCE_CREDIT_LIMIT_EXCEEDED
                        || refused.errorCode() == ErrorCode.CUSTOMER_CREDIT_OVERDUE;
                // SCRUM-193 (CREDIT-PORTAL, owner decision): a balance that does not fit on credit is refused with the
                // credit left in the error, whether Sales asked for ON_CREDIT or it is the customer's default - never
                // quietly paid before delivery instead, which the customer could not see or undo. The customer picks
                // BEFORE_DELIVERY (or pays the deposit and waits for Sales) knowing why. The one exception is an
                // accepted quote being converted: the quote carries no balance policy to choose, and refusing would
                // leave Sales unable to convert it, so the default gives way there as it did before (review of #150).
                if (!creditRefusal || !quotePriced || command.balancePolicy() != null) {
                    throw refused;
                }
                order.applyTerms(term, depositPercent, null, com.stockflow.order.api.BalancePolicy.BEFORE_DELIVERY);
                log.info("Order {}: the balance of customer {} does not fit on credit ({}); paid before delivery instead",
                        orderNumber, command.customerId(), refused.errorCode());
            }
        }

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
        if (balanceCredit != null) {
            creditChecks.record(new CreditChecks.Check(order.id().value(), clock.instant(), terms.creditLimit(),
                    balanceCredit.exposure(), balanceCredit.amount(), CreditChecks.Outcome.WITHIN_LIMIT, null, null,
                    "Deposit balance on credit"));
        }
        events.publishEventsOf(order);

        log.info("Placed order {} for customer {} with {} line(s), total {}, {} ({})",
                saved.orderNumber(), saved.customerId(), saved.lines().size(), saved.total(), term, saved.status());
        return toSummary(saved);
    }

    /**
    * G2-3 (15 §4.2 step 3, "theo chính sách của khách"): a deposit order's balance is collected as the customer's terms
     * say. Staff may tighten it to BEFORE_DELIVERY - goods leave only paid - but not collect on delivery for a customer
     * whose terms do not allow it ({@code BALANCE_POLICY_NOT_ALLOWED}). ON_CREDIT (BALCREDIT) needs a customer allowed
     * credit, whether their terms or Sales chose it. Other terms have no balance to collect.
     */
    static com.stockflow.order.api.BalancePolicy balancePolicyFor(PaymentTerm term,
                                                                  com.stockflow.order.api.BalancePolicy requested,
                                                                  CreditTerms terms) {
        if (term != PaymentTerm.DEPOSIT) {
            if (requested != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Only a deposit order has a balance policy");
            }
            return null;
        }
        com.stockflow.order.api.BalancePolicy customers = terms.depositBalancePolicy() == null
                ? com.stockflow.order.api.BalancePolicy.BEFORE_DELIVERY
                : com.stockflow.order.api.BalancePolicy.valueOf(terms.depositBalancePolicy().name());
        com.stockflow.order.api.BalancePolicy chosen = requested == null ? customers : requested;
        if (chosen == com.stockflow.order.api.BalancePolicy.ON_CREDIT) {
            // BALCREDIT: the balance on credit is the customer's debt - only for a customer allowed credit (Sales may
            // choose it for one; the limit is checked when the order is placed).
            if (!terms.allowCredit()) {
                throw new BusinessException(ErrorCode.BALANCE_POLICY_NOT_ALLOWED,
                        "Customer %s is not allowed credit: the balance cannot go on credit".formatted(terms.customerId()));
            }
            return chosen;
        }
        if (requested == null || requested == customers) {
            return customers;
        }
        if (requested == com.stockflow.order.api.BalancePolicy.BEFORE_DELIVERY) {
            return requested;
        }
        throw new BusinessException(ErrorCode.BALANCE_POLICY_NOT_ALLOWED,
                "Customer %s pays the balance of a deposit order %s".formatted(terms.customerId(), customers));
    }

    /**
     * The balance policies a customer may place a deposit order on - exactly those {@link #balancePolicyFor} accepts
     * from them: paying before delivery (always, it only tightens), their own configured policy, and the balance on
     * credit when they are allowed credit. Empty when deposits are not allowed. Offered by the payment-terms read so a
     * storefront shows only what will not be refused.
     */
    static List<com.stockflow.order.api.BalancePolicy> allowedBalancePolicies(CreditTerms terms) {
        if (!terms.allowDeposit()) {
            return List.of();
        }
        var own = terms.depositBalancePolicy() == null ? com.stockflow.order.api.BalancePolicy.BEFORE_DELIVERY
                : com.stockflow.order.api.BalancePolicy.valueOf(terms.depositBalancePolicy().name());
        List<com.stockflow.order.api.BalancePolicy> allowed = new ArrayList<>();
        allowed.add(com.stockflow.order.api.BalancePolicy.BEFORE_DELIVERY);
        if (own == com.stockflow.order.api.BalancePolicy.ON_DELIVERY) {
            allowed.add(own);
        }
        if (terms.allowCredit()) {
            allowed.add(com.stockflow.order.api.BalancePolicy.ON_CREDIT);
        }
        return List.copyOf(allowed);
    }

    /** What the customer owed on credit before the order, and the balance it puts on credit. */
    private record BalanceCredit(BigDecimal exposure, BigDecimal amount) {
    }

    /**
     * BALCREDIT (lead's decision 2026-10-21; kltn-docs 15 §4.2 step 3, BR-03): a deposit order's balance goes on credit
     * only when what the customer already owes on credit - undelivered credit (orders on credit, balances on credit) and
     * open receivables - plus this balance stays within their limit, and they are not overdue (15 §4.3 step 4). Over
     * it the order is refused, never held: Sales changes the terms (409 {@code BALANCE_CREDIT_LIMIT_EXCEEDED},
     * {@code CUSTOMER_CREDIT_OVERDUE}). The customer's row is locked (lockCreditTerms), so two orders are checked one
     * after the other.
     */
    private BalanceCredit requireBalanceWithinLimit(Order order, CreditTerms terms) {
        var position = paymentService.creditPosition(order.customerId());
        BigDecimal amount = order.creditAmount();
        BigDecimal exposure = creditChecks.undeliveredCreditExposure(order.customerId(), order.id().value())
                .add(position.outstandingReceivables());
        String currency = order.total().currency().getCurrencyCode();
        BigDecimal available = terms.creditLimit().subtract(exposure).max(BigDecimal.ZERO);
        if (position.hasOverdue()) {
            Map<String, Object> details = new java.util.LinkedHashMap<>(creditDetails(terms, exposure, available, amount,
                    currency));
            details.put("overdueAmount", position.overdueAmount());
            if (position.oldestOverdueDueDate() != null) {
                details.put("oldestOverdueDueDate", position.oldestOverdueDueDate().toString());
            }
            throw new BusinessException(ErrorCode.CUSTOMER_CREDIT_OVERDUE, ("Customer %s has overdue receivables: the "
                    + "balance of a deposit order cannot go on credit; choose BEFORE_DELIVERY or ON_DELIVERY")
                    .formatted(terms.customerId()), List.of(new com.stockflow.common.api.FieldError("balancePolicy",
                    "the customer is overdue", "CreditOverdue")), details);
        }
        if (exposure.add(amount).compareTo(terms.creditLimit()) > 0) {
            throw new BusinessException(ErrorCode.BALANCE_CREDIT_LIMIT_EXCEEDED, ("The balance on credit, %s %s, would "
                    + "take customer %s to %s %s owed on credit, above their limit of %s %s (%s %s available): change "
                    + "the balance policy or the deposit").formatted(plain(amount), currency, terms.customerId(),
                    plain(exposure.add(amount)), currency, plain(terms.creditLimit()), currency,
                    plain(available), currency),
                    List.of(new com.stockflow.common.api.FieldError("balancePolicy",
                            "over the customer's credit limit", "CreditLimitExceeded")),
                    creditDetails(terms, exposure, available, amount, currency));
        }
        return new BalanceCredit(exposure, amount);
    }

    /**
     * What the storefront needs to explain a credit refusal (SCRUM-193): the credit left (limit minus what is owed on
     * credit, never below zero), the limit, what is owed, what this order would put on credit, and the currency.
     */
    private static Map<String, Object> creditDetails(CreditTerms terms, BigDecimal exposure, BigDecimal available,
                                                     BigDecimal required, String currency) {
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("availableCredit", available);
        details.put("creditLimit", terms.creditLimit());
        details.put("exposure", exposure);
        details.put("requiredAmount", required);
        details.put("currency", currency);
        return details;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
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
        // Guard the module API as well as HTTP: no caller may reserve stock for a guest.
        throw new BusinessException(ErrorCode.GUEST_CHECKOUT_DISABLED);
    }

    /** The server price is authoritative; the client's amount is only what it expects to pay. */
    private static Money agreedPrice(String sku, Money expected, Map<String, Money> prices) {
        var actual = prices.get(sku);
        if (actual == null) throw new BusinessException(ErrorCode.PRICE_NOT_AVAILABLE);
        if (!actual.equals(expected)) throw new BusinessException(ErrorCode.CHECKOUT_PRICE_CHANGED);
        return actual;
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

    @Override
    public void recordPrintingStarted(UUID orderId) {
        Order order = lockOrder(orderId);
        if (order.recordPrintingStarted(clock.instant())) {
            repository.save(order);
            log.info("Order {}: printing started", order.orderNumber());
        }
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void recordPackingStarted(UUID orderId) {
        Order order = lockOrder(orderId);
        if (order.recordPackingStarted(clock.instant())) {
            repository.save(order);
            log.info("Order {}: packing started, delivery mode {} locked", order.orderNumber(), order.deliveryMode());
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
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean lockedLineWithdrawn(UUID orderId, UUID lineId) {
        return repository.findByIdForUpdate(new OrderId(orderId))
                .map(order -> order.status() == OrderStatus.CANCELLED || order.lineWithdrawn(lineId))
                .orElse(true);
    }

    /**
     * Decision 2026-10-17: a cancellation the system would make on its own is not made once printing started; the
     * order stays as it is and Sales and the coordinators are told (OrderCancellationReferred), to settle the fee, the
     * blanks and the printed goods by hand. Once per order and reason: a redelivered event tells nobody twice.
     */
    private void referToStaff(Order order, CancellationReasonCode wouldHaveBeen) {
        order.referCancellationToStaff(wouldHaveBeen, clock.instant());
        events.publishEventsOf(order);
        log.warn("Order {}: {} after printing started; not cancelled, referred to staff", order.orderNumber(),
                wouldHaveBeen);
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
        order.cancel(command.reasonCode(), command.note(), command.retainedPercent(), command.agreedFee(),
                command.cancelledBy(),
                clock.instant());

        if (wasHoldingStock) {
            // Nothing has shipped (a cancellation after a package is refused): every open unit of a print line is scrap.
            releaseHolds(order, order.lines().stream().collect(Collectors.toMap(line -> line,
                            line -> line.quantity() - line.cancelledQuantity(), (a, b) -> a, java.util.LinkedHashMap::new)),
                    "cancel", "Order %s cancelled: printed custom goods (BR-PRD-10)".formatted(order.orderNumber()),
                    command.cancelledBy());
            order.clearReservations();
        }

        Order saved = repository.save(order);
        closeUndecidableCancellationRequest(order);
        events.publishEventsOf(order);
        // The code, not the reason text: the text carries the customer's note, and a line break in it
        // would forge log lines (CodeQL java/log-injection).
        log.info("Cancelled order {} ({})", order.orderNumber(), order.cancellationReasonCode());
        return saved;
    }

    /**
     * Frees the stock holds of {@code closed}'s lines. BR-PRD-10 (decision 2026-10-16, 19/10: cancel allowed with a
     * production fee, the printed cups are SCRAP): the printed goods of a print line that will not ship - as many as
     * {@code closed} says for it - are scrapped, never sold: since G4-Q2 (2026-10-21) they left stock when production
     * completed, so inventory books exactly those units back under a hold of their own and asks a SCRAP write-off of
     * them in {@code by}'s name. Inventory decides from the goods' state which holds are printed goods; the release
     * leaves those alone, and releasing a hold already consumed or closed does nothing.
     *
     * @param step what closes the units ("cancel", "closure"): with the line and its closed count after the step, the
     *             idempotency key of the write-off
     */
    private void releaseHolds(Order order, java.util.Map<OrderLine, Integer> closed, String step, String scrapNote,
                              UUID by) {
        // SKU order: each write-off takes its SKU's lock, and checkout takes them sorted too (no deadlock).
        closed.entrySet().stream().filter(entry -> entry.getKey().isMadeToOrder() && entry.getValue() > 0)
                .sorted(java.util.Comparator.comparing(entry -> entry.getKey().sku().code()))
                .forEach(entry -> inventory.scrapCancelledPrint(new com.stockflow.inventory.api.ScrapCancelledPrintCommand(
                        order.id().value(), UUID.nameUUIDFromBytes("print-scrap:%s:%s:%d".formatted(step,
                                entry.getKey().id(), entry.getKey().cancelledQuantity())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        entry.getKey().reservationIds(), entry.getValue(), scrapNote, by)));
        closed.keySet().stream().flatMap(line -> line.reservationIds().stream())
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
        if (order.printingStartedAt() != null && order.status().canTransitionTo(OrderStatus.CANCELLED)) {
            referToStaff(order, CancellationReasonCode.PAYMENT_FAILED);
            return;
        }
        if (!order.status().canTransitionTo(OrderStatus.CANCELLED) || order.packedForGood()) {
            // A listener must not throw for a state it cannot change: the publication would be retried for ever.
            log.warn("Payment of order {} failed ({}), but the order is {}{}: left for Sales", order.orderNumber(),
                    failureCode, order.status(), order.packedForGood() ? " and packed" : "");
            return;
        }
        order.cancel(CancellationReasonCode.PAYMENT_FAILED, failureCode, null, null, clock.instant());
        order.clearReservations();
        repository.save(order);
        closeUndecidableCancellationRequest(order);
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
        recordPayment(orderId, paymentId, amount, currency, false);
    }

    /** @param ofReceivable the payment paid a receivable of the order (payment's allocation, G3-2 credit exposure) */
    public void recordPayment(UUID orderId, UUID paymentId, java.math.BigDecimal amount, String currency,
                              boolean ofReceivable) {
        recordPayment(orderId, paymentId, amount, currency, ofReceivable, false);
    }

    /** @param ofFeeReceivable the money paid a closure fee's receivable (re-review of #150) */
    public void recordPayment(UUID orderId, UUID paymentId, java.math.BigDecimal amount, String currency,
                              boolean ofReceivable, boolean ofFeeReceivable) {
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
        boolean confirmed = order.recordPayment(new Money(amount, order.total().currency()), clock.instant(),
                ofReceivable, ofFeeReceivable);
        if (confirmed) {
            inventory.pinReservations(orderId);
        }
        if (order.status() == OrderStatus.CANCELLED) {
            log.warn("Payment {} of {} {} arrived for order {}, already cancelled ({}); settle it with the customer",
                    paymentId, amount, currency, order.orderNumber(), order.cancellationReasonCode());
        }
        repository.save(order);
        events.publishEventsOf(order);
    }

    /**
     * The accountant took back money an allocation had brought (G2-3, {@code PaymentReversed}): counted once per
     * reversal ({@code ordering.order_payment}, a negative row). A confirmed order left short of its terms goes ON_HOLD
     * with reason PAYMENT ({@link Order#reversePayment}); never silently un-confirmed. Called by
     * {@code PaymentEventListener}, not exposed on the port.
     */
    public void reversePayment(UUID orderId, UUID reversalId, java.math.BigDecimal amount, String currency,
                               boolean ofReceivable, String reason, UUID by, java.time.Instant at) {
        reversePayment(orderId, reversalId, amount, currency, ofReceivable, false, reason, by, at);
    }

    /** @param ofFeeReceivable the money had paid a closure fee's receivable (re-review of #150) */
    public void reversePayment(UUID orderId, UUID reversalId, java.math.BigDecimal amount, String currency,
                               boolean ofReceivable, boolean ofFeeReceivable, String reason, UUID by,
                               java.time.Instant at) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new IllegalArgumentException("No order with id " + orderId));
        if (!order.total().currency().getCurrencyCode().equals(currency)) {
            log.error("Reversal {} for order {} is in {}, the order in {}; not counted", reversalId,
                    order.orderNumber(), currency, order.total().currency());
            return;
        }
        if (!payments.record(reversalId, orderId, amount.negate(), currency, clock.instant())) {
            return; // Redelivered event; already counted.
        }
        boolean held = order.reversePayment(new Money(amount, order.total().currency()), ofReceivable,
                ofFeeReceivable, reason, by, at == null ? clock.instant() : at);
        if (held) {
            log.warn("Order {} put on hold: {} {} of its payment was taken back ({})", order.orderNumber(), amount,
                    currency, reason);
        } else if (!order.termsMet()) {
            log.warn("Order {} is {} and short of its {} terms after a reversal of {} {}", order.orderNumber(),
                    order.status(), order.paymentTerm(), amount, currency);
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
        if (found.get().printingStartedAt() != null) {
            referToStaff(found.get(), CancellationReasonCode.PAYMENT_NOT_RECEIVED);
            return;
        }
        cancelLoaded(found.get(), new CancelOrderCommand(CancellationReasonCode.PAYMENT_NOT_RECEIVED,
                "not paid before the stock hold ran out", null, null));
    }

    @Override
    public OrderSummary recordDelivery(UUID orderId) {
        return recordDelivery(orderId, null);
    }

    @Override
    @com.stockflow.common.audit.Auditable(action = com.stockflow.common.audit.AuditAction.TRANSITION,
            resourceType = "order", resourceId = "#orderId")
    public OrderSummary recordDelivery(UUID orderId, UUID by) {
        Order order = lockOrder(orderId);
        if (order.status() == OrderStatus.DELIVERED && order.shipments().isEmpty()) {
            return toSummary(order);
        }
        order.markDelivered(clock.instant(), by);
        log.info("Order {} delivered", order.orderNumber());
        return saved(order);
    }

    @Override
    @com.stockflow.common.audit.Auditable(action = com.stockflow.common.audit.AuditAction.TRANSITION,
            resourceType = "order", resourceId = "#orderId")
    public OrderSummary recordDeliveryFailure(UUID orderId, com.stockflow.order.api.DeliveryFailureReason reason,
                                              String note, UUID by) {
        Order order = lockOrder(orderId);
        if (!order.markDeliveryFailed(reason, note, clock.instant(), by)) {
            return toSummary(order);
        }
        log.info("Order {}: delivery failed ({})", order.orderNumber(), reason);
        return saved(order);
    }

    @Override
    public OrderSummary recordReturnedToOrigin(UUID orderId, UUID by) {
        Order order = lockOrder(orderId);
        return order.markReturnedToOrigin(clock.instant(), by) ? saved(order) : toSummary(order);
    }

    @Override
    @Deprecated(forRemoval = true)
    public OrderSummary releaseToFulfillment(UUID orderId) {
        return startPicking(orderId, null);
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
        if (!order.markShipped(handedOverAt == null ? clock.instant() : handedOverAt, by, ledgerOf(order))) {
            return toSummary(order);
        }
        log.info("Order {}: shipped", order.orderNumber());
        return saved(order);
    }

    @Override
    public OrderSummary recordHandover(UUID orderId, com.stockflow.order.api.ShipmentHandover handover) {
        Order order = lockOrder(orderId);
        if (!order.recordHandover(handover, ledgerOf(order))) {
            return toSummary(order);
        }
        log.info("Order {}: shipment {} handed over, order {}", order.orderNumber(), handover.shipmentId(),
                order.status());
        return saved(order);
    }

    @Override
    public void recordGoodsLeft(UUID orderId, java.time.Instant at,
                                java.util.List<com.stockflow.order.api.ShipmentHandover.Line> inPlan) {
        Order order = lockOrder(orderId);
        if (order.recordGoodsLeft(at, inPlan == null || inPlan.isEmpty() ? null : quantities(inPlan), ledgerOf(order))) {
            log.info("Order {}: goods left on a load plan's vehicle; no longer cancelled", order.orderNumber());
            saved(order);
        }
    }

    @Override
    public void requirePlanCarriesTheRest(UUID orderId,
                                          java.util.List<com.stockflow.order.api.ShipmentHandover.Line> inPlan) {
        Order order = repository.findById(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
        order.requirePlanCarriesTheRest(inPlan == null || inPlan.isEmpty() ? null : quantities(inPlan));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<com.stockflow.order.api.OrderHoldState> holdStates(java.util.Collection<UUID> orderIds) {
        return repository.holdStates(orderIds == null ? java.util.List.of() : java.util.Set.copyOf(orderIds));
    }

    @Override
    @Transactional(readOnly = true)
    public java.math.BigDecimal codFor(UUID orderId, java.util.List<com.stockflow.order.api.ShipmentHandover.Line> lines,
                                       java.util.List<com.stockflow.order.api.ShipmentHandover.Line> earlierPending) {
        Order order = repository.findById(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return order.codFor(quantities(lines), quantities(earlierPending), ledgerOf(order));
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
     * G2-3: the order's money as payment's ledger has it, read under the order's lock (taken by the caller): every
     * allocation takes that lock first, so one committed just before the handover is counted in the COD and the gate.
     */
    private Order.LedgerMoney ledgerOf(Order order) {
        com.stockflow.payment.api.OrderLedger ledger = paymentService.ledgerOf(order.id().value());
        return new Order.LedgerMoney(ledger.collected(), ledger.paidAhead());
    }

    @Override
    public OrderSummary recordShipmentDelivered(UUID orderId, UUID shipmentId, UUID by) {
        Order order = lockOrder(orderId);
        if (!order.recordShipmentDelivered(shipmentId, clock.instant(), by)) {
            return toSummary(order);
        }
        log.info("Order {}: shipment {} delivered, order {}", order.orderNumber(), shipmentId, order.status());
        return saved(order);
    }

    @Override
    public OrderSummary recordShipmentDeliveryFailed(UUID orderId, UUID shipmentId,
                                                     com.stockflow.order.api.DeliveryFailureReason reason, String note,
                                                     UUID by) {
        Order order = lockOrder(orderId);
        if (!order.recordShipmentDeliveryFailed(shipmentId, reason, note, clock.instant(), by)) {
            return toSummary(order);
        }
        log.info("Order {}: shipment {} not delivered ({}), order {}", order.orderNumber(), shipmentId, reason,
                order.status());
        return saved(order);
    }

    @Override
    public OrderSummary recordShipmentReturned(UUID orderId, UUID shipmentId, UUID by) {
        Order order = lockOrder(orderId);
        if (!order.recordShipmentReturned(shipmentId, clock.instant(), by)) {
            return toSummary(order);
        }
        log.info("Order {}: shipment {} returned, order {}", order.orderNumber(), shipmentId, order.status());
        return saved(order);
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
        Order order = lockOrder(orderId);
        java.util.Map<UUID, Integer> closedBefore = order.lines().stream()
                .collect(Collectors.toMap(OrderLine::id, OrderLine::cancelledQuantity));
        BigDecimal closed = order.closeRemainder(reasonCode, note, fee, by, clock.instant());
        java.util.Map<OrderLine, Integer> closedLines = order.lines().stream()
                .filter(line -> line.cancelledQuantity() > closedBefore.get(line.id()))
                .collect(Collectors.toMap(line -> line, line -> line.cancelledQuantity() - closedBefore.get(line.id()),
                        (a, b) -> a, java.util.LinkedHashMap::new));
        // The holds of the units that will not go: still held stock is freed, printed goods not shipped are scrapped
        // (BR-PRD-10) - only the closed units, the shipped ones are the customer's - and a hold already consumed by a
        // pick is left as it is (its goods are fulfillment's to put back, G4-4).
        releaseHolds(order, closedLines, "closure", "Order %s: rest closed, printed custom goods (BR-PRD-10)"
                .formatted(order.orderNumber()), by);
        log.info("Order {}: rest closed ({}), {} {} worth; now {}", order.orderNumber(), reasonCode, closed,
                order.total().currency(), order.status());
        return saved(order);
    }

    @Override
    @com.stockflow.common.audit.Auditable(action = com.stockflow.common.audit.AuditAction.TRANSITION,
            resourceType = "order", resourceId = "#orderId")
    public OrderSummary agreeProductionShortfall(UUID orderId, UUID orderLineId, int units, String note, UUID by) {
        Order order = lockOrder(orderId);
        order.agreeProductionShortfall(orderLineId, units, note, by, clock.instant());
        // The note is what the customer said: it stays on the line, not in the log.
        log.info("Order {}: the customer goes without at most {} unit(s) of line {}", order.orderNumber(), units,
                orderLineId);
        return saved(order);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Not audited here: it commits or rolls back with production's short completion, which is audited as its
     * transition - an audit row written on return from here would outlive a rollback after it.</p>
     */
    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public OrderSummary closeProductionShortfall(UUID orderId, UUID orderLineId, int units, UUID by) {
        Order order = lockOrder(orderId);
        BigDecimal closed = order.closeProductionShortfall(orderLineId, units, by, clock.instant());
        // Production completes or ends the job and settles its blanks in this transaction; why is on the line's
        // agreement and on the production order's record.
        log.info("Order {}: {} unit(s) of line {} not made, closed ({} {} worth)", order.orderNumber(), units,
                orderLineId, closed, order.total().currency());
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
     * cancelling can be decided - its first package completed (decision 2026-10-17), handed over, delivered - or is
     * cancelled another way, could only ever be refused ({@code ORDER_NOT_CANCELLABLE}), and the customer saw it pending
     * for good. It closes here, in the transaction that moved the order, as {@code EXPIRED} with a note the customer
     * reads on the order. Called after every save that may move the order; nothing to do in the common case.
     */
    private void closeUndecidableCancellationRequest(Order order) {
        OrderStatus status = order.status();
        String why;
        if (status == OrderStatus.CANCELLED) {
            why = "Closed: the order was cancelled.";
        } else if (order.packedForGood() || !status.canTransitionTo(OrderStatus.CANCELLED)) {
            why = "Closed: the order is %s and can no longer be cancelled; ask for a return instead."
                    .formatted(order.packedForGood() && status.canTransitionTo(OrderStatus.CANCELLED)
                            ? OrderStatus.PACKED : status);
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

    @Override
    public void putOnDesignHold(UUID orderId, String reason) {
        var order = lockOrder(orderId);
        if (order.holdReason() == com.stockflow.order.api.HoldReason.DESIGN_MISMATCH) { return; }
        // Held for another reason (ADDRESS, ...): refused (409), so the packing completion that found the mismatch
        // rolls back - package and evidence included - rather than holding a package nothing would ever release
        // (review of G3-2). The coordinator resolves their hold; packing is completed again then.
        order.putOnHold(com.stockflow.order.api.HoldReason.DESIGN_MISMATCH, null, null, clock.instant());
        repository.save(order);
        events.publishEventsOf(order);
        holds.save(new OrderHoldJpaEntity(Identifiers.newId(), orderId,
                com.stockflow.order.api.HoldReason.DESIGN_MISMATCH.name(), clock.instant()));
    }

    @Override
    public void resolveDesignHold(UUID orderId, UUID resolvedBy, String note) {
        var order = lockOrder(orderId);
        // Not held (any more): nothing to do on the order, the package is still completed by the caller. Held for
        // another reason: 409 from the aggregate - that hold is resolved first (review of G3-2).
        boolean resolved = order.resolveDesignHold(note, resolvedBy, clock.instant());
        holds.findFirstByOrderIdAndResolvedAtIsNullOrderByRaisedAtDesc(orderId)
                .filter(hold -> com.stockflow.order.api.HoldReason.DESIGN_MISMATCH.name().equals(hold.getReason()))
                .ifPresent(hold -> {
                    hold.resolve(resolvedBy, note, clock.instant());
                    holds.save(hold);
                });
        if (resolved) {
            repository.save(order);
            events.publishEventsOf(order);
        }
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
                        order.paidInFullAt(), order.paymentStatus(), order.creditTermDays(), order.balancePolicy()),
                order.status() == OrderStatus.CANCELLED
                        ? new OrderSummary.Cancellation(order.cancellationReasonCode(), order.cancellationReason(),
                                order.cancellationRetainedAmount(), order.cancellationFeeReason(),
                                order.cancellationFeeDetail(), order.cancellationFeeAmount())
                        : null,
                lifecycleOf(order));
    }

    private static OrderSummary.ProductionShortfall productionShortfallOf(OrderLine line) {
        OrderLine.ShortfallAgreement agreed = line.shortfallAgreement();
        return agreed == null ? null : new OrderSummary.ProductionShortfall(agreed.quantity(), agreed.note(),
                agreed.agreedBy(), agreed.agreedAt(), line.shortfallClosedQuantity());
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
                        line.cancelledQuantity(), productionShortfallOf(line)))
                .toList();
        List<OrderSummary.Shipment> shipments = order.shipments().stream()
                .map(s -> new OrderSummary.Shipment(s.id(), s.status(), s.amount(), s.billedAmount(),
                        s.handedOverAt(), s.outcomeAt(), s.failureReason(),
                        s.quantities().entrySet().stream()
                                .map(e -> new OrderSummary.ShipmentLine(e.getKey(), e.getValue())).toList(),
                        s.codAmount(), s.carriage()))
                .toList();
        return new OrderSummary.Lifecycle(order.holdReason(), order.statusBeforeHold(), order.holdNote(),
                order.packedAt(), order.shippedAt(), order.deliveredAt(), order.deliveryFailureReason(), order.completedAt(),
                order.closedAt(), order.billedAmount(), order.billable(), order.amountDue(),
                order.amountDueBeforeHandover(), progress, shipments, order.amountDueOnDelivery(), order.codAmount(),
                order.allowPartialShipment(), order.printLinesReady(), order.deliveryMode(),
                order.deliveryModeLocked(), order.goodsLeftAt());
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
