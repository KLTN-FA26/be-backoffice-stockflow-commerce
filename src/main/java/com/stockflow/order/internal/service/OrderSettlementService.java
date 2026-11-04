package com.stockflow.order.internal.service;

import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.SettlementCandidates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Completes and closes orders as time passes (G3-2; kltn-docs 17 §4.3 step 10, BR-05; BR-ORD-005):
 * <ul>
 *   <li>DELIVERED → COMPLETED once the return window ({@code stockflow.order.return-window-days}, default 7) has passed,
 *       nothing is owed any more and no return request is open. A credit order not yet paid stays DELIVERED (17 edge).
 *       An order with an open return is RETURN_REQUESTED and so not a candidate at all; the check here stays as the
 *       guard against a return opened between the candidate query and the row lock (SCRUM-243: the return service
 *       takes the same lock, so one of the two sees the other).</li>
 *   <li>COMPLETED / REFUNDED / PARTIALLY_REFUNDED → CLOSED after {@code stockflow.order.close-after-days} (default 30,
 *       decision G3-Q1).</li>
 * </ul>
 * One order per transaction, through the aggregate, so each gets its history row and event, and one failing order does
 * not hold the others back.
 */
@Service
public class OrderSettlementService {

    private static final Logger log = LoggerFactory.getLogger(OrderSettlementService.class);

    private final OrderRepository repository;
    private final SettlementCandidates candidates;
    private final OrderEventPublisher events;
    private final Clock clock;
    private final Duration returnWindow;
    private final Duration closeAfter;

    OrderSettlementService(OrderRepository repository, SettlementCandidates candidates, OrderEventPublisher events,
                           Clock clock, @Value("${stockflow.order.return-window-days:7}") int returnWindowDays,
                           @Value("${stockflow.order.close-after-days:30}") int closeAfterDays) {
        this.repository = repository;
        this.candidates = candidates;
        this.events = events;
        this.clock = clock;
        this.returnWindow = Duration.ofDays(returnWindowDays);
        this.closeAfter = Duration.ofDays(closeAfterDays);
    }

    @Transactional(readOnly = true)
    public List<UUID> completionCandidates(int limit) {
        return candidates.deliveredBy(clock.instant().minus(returnWindow), limit);
    }

    @Transactional(readOnly = true)
    public List<UUID> closingCandidates(int limit) {
        return candidates.settledBy(clock.instant().minus(closeAfter), limit);
    }

    /** @return whether the order was completed */
    @Transactional
    public boolean complete(UUID orderId) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId)).orElse(null);
        if (order == null || !order.complete(clock.instant(), returnWindow, candidates.hasOpenReturn(orderId))) {
            return false;
        }
        repository.save(order);
        events.publishEventsOf(order);
        log.info("Order {} completed", order.orderNumber());
        return true;
    }

    /**
     * @return whether the order was closed; never while a return of it is open (SCRUM-243): CLOSED is final (BR-05), and
     *         the return would have nowhere to put its outcome
     */
    @Transactional
    public boolean close(UUID orderId) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId)).orElse(null);
        if (order == null || candidates.hasOpenReturn(orderId) || !order.close(clock.instant(), closeAfter)) {
            return false;
        }
        repository.save(order);
        events.publishEventsOf(order);
        log.info("Order {} closed", order.orderNumber());
        return true;
    }
}
