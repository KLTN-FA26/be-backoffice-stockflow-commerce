package com.stockflow.order.internal.service;

import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReserveStockResult;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.repository.OrderSearchRepository;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
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

    private final OrderRepository repository;
    private final OrderSearchRepository search;
    private final InventoryService inventory;
    private final OrderEventPublisher events;
    private final Clock clock;

    OrderServiceImpl(OrderRepository repository, OrderSearchRepository search,
                     InventoryService inventory, OrderEventPublisher events, Clock clock) {
        this.repository = repository;
        this.search = search;
        this.inventory = inventory;
        this.events = events;
        this.clock = clock;
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
            log.info("Request {} already produced order {}",
                    command.requestId(), alreadyPlaced.get().orderNumber());
            return toSummary(alreadyPlaced.get());
        }

        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        OrderNumber orderNumber = repository.nextOrderNumber(today);

        // Line ids derived from the checkout request id and the line's position, not random.
        // A random id would make the per-line reservation key below different on every attempt,
        // which quietly breaks the retry-safety this method advertises: a retry would look like a
        // brand-new request to inventory and hold the stock a second time.
        List<OrderLine> lines = new ArrayList<>();
        for (int index = 0; index < command.lines().size(); index++) {
            PlaceOrderCommand.Line line = command.lines().get(index);
            lines.add(Order.line(deterministicLineId(command.requestId(), index),
                    line.sku(), line.quantity(), line.unitPrice(), line.designSnapshotId()));
        }

        Order order = Order.draft(orderNumber, command.customerId(), command.requestId(),
                lines, clock.instant());

        // Direct in-process call across the module boundary, through inventory's published port.
        // It joins this transaction. If reserve() throws InsufficientStockException on line 3,
        // lines 1 and 2 are rolled back with the order — no compensation code, no saga state, no
        // possibility of a half-reserved order surviving the failure.
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

        order.submit();
        Order saved = repository.save(order);
        events.publishEventsOf(order);

        log.info("Placed order {} for customer {} with {} line(s), total {}",
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
    public void cancel(UUID orderId, String reason) {
        Order order = repository.findByIdInScope(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "No order with id " + orderId));

        boolean wasHoldingStock = order.status().holdsStock();
        order.cancel(reason);

        if (wasHoldingStock) {
            order.reservationIds()
                    .forEach(reservationId -> inventory.release(reservationId, "ORDER_CANCELLED"));
            order.clearReservations();
        }

        repository.save(order);
        events.publishEventsOf(order);
        log.info("Cancelled order {} ({})", order.orderNumber(), reason);
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
    public void cancelAfterPaymentFailure(UUID orderId, String reason) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new IllegalArgumentException("No order with id " + orderId));
        if (order.status() == OrderStatus.CANCELLED) {
            return; // Redelivered event; already handled.
        }
        order.cancel(reason);
        order.clearReservations();
        repository.save(order);
        events.publishEventsOf(order);
    }

    /** Move an order to PAID. Called by {@code PaymentEventListener}, not exposed on the port. */
    public void markPaid(UUID orderId) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new IllegalArgumentException("No order with id " + orderId));
        if (order.status() == OrderStatus.PAID) {
            return; // Redelivered event; nothing to do.
        }
        order.markPaid();
        repository.save(order);
        events.publishEventsOf(order);
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
        return UUID.nameUUIDFromBytes((requestId + ":" + lineId).getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Stable line identity: same checkout request, same position, same id. */
    private static UUID deterministicLineId(UUID requestId, int index) {
        return UUID.nameUUIDFromBytes((requestId + "#line#" + index).getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
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
                                line.reservationIds()))
                        .toList(),
                order.placedAt(),
                order.createdBy(),
                order.lastModifiedAt(),
                order.lastModifiedBy());
    }
}
