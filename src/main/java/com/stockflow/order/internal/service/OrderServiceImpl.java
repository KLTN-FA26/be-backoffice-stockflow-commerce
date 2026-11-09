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
import com.stockflow.order.internal.domain.OrderFee;
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
import java.time.Instant;
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
    private final com.stockflow.product.api.CustomerSkus customerSkus;
    private final com.stockflow.order.internal.domain.SalesContractRepository contracts;

    OrderServiceImpl(OrderRepository repository, OrderSearchRepository search,
                     InventoryService inventory, OrderEventPublisher events, Clock clock,
            DesignService designs,
            OrderHoldJpaRepository holds,
                     CustomerService customers,
            CatalogService catalog, OrderPayments payments,
                     CancellationRequestRepository cancellationRequests, CreditChecks creditChecks,
                     com.stockflow.payment.api.PaymentService paymentService,
                     com.stockflow.product.api.CustomerSkus customerSkus,
                     com.stockflow.order.internal.domain.SalesContractRepository contracts) {
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
        // SCRUM-428: the customer's own tier for the order's total of each SKU - the one source of a price since 5-3,
        // no group or base price (kltn-docs 13 BR-04); the client's price is only what it expects to pay (agreedPrice).
        var sellingPrices =
                command.snapshotCustomer() && !quotePriced
                        ? catalog.checkoutPrices(command.customerId(),
                                command.lines().stream()
                                        .filter(line -> line.designSnapshotId() == null)
                                        .collect(Collectors.toMap(line -> line.sku().code(),
                                                PlaceOrderCommand.Line::quantity, Integer::sum)))
                        : Map.<String, Money>of();
        // Another customer's own SKU is not there for this customer (kltn-docs 13 BR-02), whoever places the order.
        customerSkus.requireVisibleTo(command.lines().stream().map(line -> line.sku().code()).toList(),
                command.customerId());
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
        // 4B-3: the quote's fee lines, before the terms - a deposit is a share of the total with them (14 BR-09).
        order.attachFees(command.fees().stream().map(fee -> OrderFee.of(deterministicFeeId(command.requestId(),
                fee.kind(), fee.sku()), fee.kind(), fee.sku(), fee.amount(), fee.taxRate())).toList());
        if (quotePriced) {
            // 4B-5 (kltn-docs 14 §4.2 step 7): an accepted quote becomes a DRAFT order - no stock held, no terms, no
            // credit checked. Its sales contract takes it on: sent, signed, then held for credit, waiting for the
            // installment on signing, or confirmed (SalesContracts). The payment term is the contract's plan's; the
            // order carries PREPAID only as the column's neutral value for the money ledger, which goes in 4B-10.
            order.applyTerms(PaymentTerm.PREPAID, null, null, null);
            Order saved = repository.save(order);
            log.info("Order {} drafted for customer {} from an accepted quote, {} line(s), total {}",
                    saved.orderNumber(), saved.customerId(), saved.lines().size(), saved.total());
            return toSummary(saved);
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
        // SCRUM-193 (CREDIT-PORTAL, owner decision): a balance that does not fit on credit is refused with the credit
        // left in the error, whether Sales asked for ON_CREDIT or it is the customer's default - never quietly paid
        // before delivery instead, which the customer could not see or undo. (An accepted quote no longer comes this
        // way: it becomes a DRAFT order above.)
        BalanceCredit balanceCredit = balanceOnCredit ? requireBalanceWithinLimit(order, terms) : null;

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
     * say. Staff may tighten it to BEFORE_DELIVERY - goods leave only paid. ON_CREDIT (BALCREDIT) needs a customer
     * allowed credit, whether their terms or Sales chose it ({@code BALANCE_POLICY_NOT_ALLOWED}). Nothing is collected
     * on delivery (09 BR-08). Other terms have no balance to collect.
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
        return com.stockflow.order.api.BalancePolicy.BEFORE_DELIVERY;
    }

    /**
     * The balance policies a customer may place a deposit order on - exactly those {@link #balancePolicyFor} accepts
     * from them: paying before delivery (always), and the balance on credit when they are allowed credit. Empty when
     * deposits are not allowed. Offered by the payment-terms read so a storefront shows only what will not be
     * refused.
     */
    static List<com.stockflow.order.api.BalancePolicy> allowedBalancePolicies(CreditTerms terms) {
        if (!terms.allowDeposit()) {
            return List.of();
        }
        List<com.stockflow.order.api.BalancePolicy> allowed = new ArrayList<>();
        allowed.add(com.stockflow.order.api.BalancePolicy.BEFORE_DELIVERY);
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
                    + "balance of a deposit order cannot go on credit; choose BEFORE_DELIVERY")
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
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void recordPackingStarted(UUID orderId) {
        Order order = lockOrder(orderId);
        if (order.recordPackingStarted(clock.instant())) {
            repository.save(order);
            log.info("Order {}: packing started", order.orderNumber());
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
                // 17 §5.2: a DRAFT order is not the customer's - nothing to ask about.
                .filter(found -> found.status() != OrderStatus.DRAFT)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "No order with id " + orderId));
        CancellationReasonCode code = reasonCode == null ? CancellationReasonCode.CUSTOMER_REQUEST : reasonCode;
        // A replacement order is changed through its return, by Sales: neither cancelled here nor queued as a request
        // a later approval would fail on.
        if (order.replacesReturnRequestId() != null) {
            throw new BusinessException(ErrorCode.RETURN_REPLACEMENT_ORDER_LOCKED,
                    "Order %s ships replacements for a return: ask Sales to change it".formatted(order.orderNumber()));
        }
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
        closeUndecidableCancellationRequest(order);
        events.publishEventsOf(order);
        // The code, not the reason text: the text carries the customer's note, and a line break in it
        // would forge log lines (CodeQL java/log-injection).
        log.info("Cancelled order {} ({})", order.orderNumber(), order.cancellationReasonCode());
        return saved;
    }

    /**
     * Frees the stock holds of {@code lines}. The goods of a print line not shipped stay in stock as the customer's
     * surplus (kltn-docs 17 BR-07): nothing is scrapped. Releasing a hold already consumed by a pick or closed does
     * nothing - its goods are fulfillment's to put back (G4-4).
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
            // Already on hold, or past the handover - which waited for this money (ledgerOf), so goods left only
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
     * allocation takes that lock first, so one committed just before the handover is counted by the gate.
     */
    private Order.LedgerMoney ledgerOf(Order order) {
        if (order.paidByContract()) {
            // 4B-6 (15 BR-07, BR-08): its installments before delivery and on signing, read under the order's lock - a
            // receipt's void locks the order first, so one voided a moment ago is seen here, before the hold it calls
            // for has arrived.
            return new Order.LedgerMoney(java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                    paymentService.installmentStanding(order.id().value()).dueBeforeHandover());
        }
        com.stockflow.payment.api.OrderLedger ledger = paymentService.ledgerOf(order.id().value());
        return new Order.LedgerMoney(ledger.collected(), ledger.paidAhead());
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
        // A credit hold is accounting's to decide (ORDER_CREDIT_HOLD_USE_APPROVAL from the order itself).
        if (order.paidByContract() && order.status() == OrderStatus.ON_HOLD
                && order.holdReason() != com.stockflow.order.api.HoldReason.CREDIT
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
        Order order = lockOrder(orderId);
        java.util.Map<UUID, Integer> closedBefore = order.lines().stream()
                .collect(Collectors.toMap(OrderLine::id, OrderLine::cancelledQuantity));
        BigDecimal closed = order.closeRemainder(reasonCode, note, fee, by, clock.instant());
        java.util.Map<OrderLine, Integer> closedLines = order.lines().stream()
                .filter(line -> line.cancelledQuantity() > closedBefore.get(line.id()))
                .collect(Collectors.toMap(line -> line, line -> line.cancelledQuantity() - closedBefore.get(line.id()),
                        (a, b) -> a, java.util.LinkedHashMap::new));
        // The holds of the lines with units that will not go are freed; a hold already consumed by a pick is left as
        // it is (its goods are fulfillment's to put back, G4-4).
        releaseHolds(closedLines.keySet());
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

    /** A fee line's id from the request and what it is for: a retried placement writes the same row. */
    static UUID deterministicFeeId(UUID requestId, com.stockflow.order.api.FeeKind kind,
                                   com.stockflow.common.domain.Sku sku) {
        return UUID.nameUUIDFromBytes(("order-fee:" + requestId + ":" + kind + ":" + (sku == null ? "" : sku.code()))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
                                line.reservationIds(), line.designSnapshotId(), line.designChecksum(),
                                line.requestedDeliveryDate(), line.cartons(), line.unitsPerCarton(), line.taxRate(),
                                line.taxAmount()))
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
                .map(s -> new OrderSummary.Shipment(s.id(), s.status(), s.amount(), s.billedAmount(),
                        s.handedOverAt(), s.outcomeAt(), s.failureReason(),
                        s.quantities().entrySet().stream()
                                .map(e -> new OrderSummary.ShipmentLine(e.getKey(), e.getValue())).toList(),
                        s.carriage()))
                .toList();
        return new OrderSummary.Lifecycle(order.holdReason(), order.statusBeforeHold(), order.holdNote(),
                order.packedAt(), order.shippedAt(), order.deliveredAt(), order.deliveryFailureReason(), order.completedAt(),
                order.closedAt(), order.billedAmount(), order.billable(), order.amountDue(),
                order.amountDueBeforeHandover(), progress, shipments, order.allowPartialShipment(), order.goodsLeftAt(),
                order.replacesReturnRequestId());
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
