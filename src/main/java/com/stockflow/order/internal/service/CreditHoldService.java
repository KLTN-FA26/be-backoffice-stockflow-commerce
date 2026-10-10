package com.stockflow.order.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.CreditChecks;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.repository.OrderHoldJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Orders over their customer's credit limit, and what the customer may be sold on (SCRUM-427,
 * kltn-docs 15 §4.3 and BR-03, 18 BR-02).
 *
 * <p>Whoever approves credit confirms a held order, or refuses it: the order is then cancelled, or
 * switched to prepaid or deposit terms the customer is allowed and waits for payment. Either way the
 * decision is recorded with who made it and why ({@code order_credit_check}).</p>
 */
@Service
@Transactional
public class CreditHoldService {

    private static final Logger log = LoggerFactory.getLogger(CreditHoldService.class);

    /** What a refusal does with the order. */
    public enum RefusalAction { CANCEL, SWITCH_TO_PREPAID, SWITCH_TO_DEPOSIT }

    /** The terms a customer may use and the credit left, for the checkout and the back office. */
    public record CreditPosition(CreditTerms terms, BigDecimal exposure, BigDecimal available) {
    }

    private final OrderRepository repository;
    private final OrderServiceImpl orders;
    private final OrderService orderService;
    private final OrderEventPublisher events;
    private final CreditChecks creditChecks;
    private final OrderHoldJpaRepository holds;
    private final CustomerService customers;
    private final com.stockflow.order.internal.repository.CreditHoldSearch search;
    private final com.stockflow.inventory.api.InventoryService inventory;
    private final Clock clock;

    CreditHoldService(OrderRepository repository, OrderServiceImpl orders, OrderService orderService,
                      OrderEventPublisher events, CreditChecks creditChecks, OrderHoldJpaRepository holds,
                      CustomerService customers, com.stockflow.order.internal.repository.CreditHoldSearch search,
                      com.stockflow.inventory.api.InventoryService inventory, Clock clock) {
        this.repository = repository;
        this.orders = orders;
        this.orderService = orderService;
        this.events = events;
        this.creditChecks = creditChecks;
        this.holds = holds;
        this.customers = customers;
        this.search = search;
        this.inventory = inventory;
        this.clock = clock;
    }

    /** The customer's terms, what they owe on credit and what is left of the limit (null without credit). */
    @Transactional(readOnly = true)
    public CreditPosition position(UUID customerId) {
        CreditTerms terms = customers.creditTerms(customerId);
        BigDecimal exposure = creditChecks.undeliveredCreditExposure(customerId, null);
        BigDecimal available = terms.allowCredit() ? terms.creditLimit().subtract(exposure).max(BigDecimal.ZERO) : null;
        return new CreditPosition(terms, exposure, available);
    }

    @Transactional(readOnly = true)
    public com.stockflow.common.api.PageResponse<UUID> heldOrders(int page, int size) {
        return com.stockflow.common.persistence.Pages.toResponse(
                search.heldOrders(com.stockflow.common.persistence.Pages.of(page, size, org.springframework.data.domain.Sort.unsorted())));
    }

    @Auditable(action = AuditAction.APPROVE, resourceType = "order-credit-hold", resourceId = "#orderId")
    public OrderSummary approve(UUID orderId, String note, UUID by) {
        requireNote(note);
        Order order = lockHeld(orderId);
        order.approveCredit();
        decide(order, CreditChecks.Outcome.APPROVED, note, by);
        repository.save(order);
        events.publishEventsOf(order);
        log.info("Credit approved for order {} by {}", order.orderNumber(), by);
        return orderService.findById(orderId).orElseThrow();
    }

    @Auditable(action = AuditAction.REJECT, resourceType = "order-credit-hold", resourceId = "#orderId")
    public OrderSummary refuse(UUID orderId, RefusalAction action, BigDecimal depositPercent, String note, UUID by) {
        requireNote(note);
        Order order = lockHeld(orderId);
        if (action == RefusalAction.CANCEL) {
            decide(order, CreditChecks.Outcome.REJECTED, note, by);
            orders.cancelLoaded(order, new CancelOrderCommand(CancellationReasonCode.CREDIT_REJECTED, note, null, by));
        } else {
            CreditTerms terms = customers.creditTerms(order.customerId());
            PaymentTerm term = action == RefusalAction.SWITCH_TO_PREPAID ? PaymentTerm.PREPAID : PaymentTerm.DEPOSIT;
            if (!terms.allows(CommercialTerm.valueOf(term.name()))) {
                throw new BusinessException(ErrorCode.PAYMENT_TERM_NOT_ALLOWED,
                        "Customer %s may not be sold on %s terms".formatted(order.customerId(), term));
            }
            order.switchTermsAfterCreditRefusal(term,
                    term == PaymentTerm.DEPOSIT && depositPercent == null ? terms.depositPercent() : depositPercent);
            decide(order, CreditChecks.Outcome.REJECTED, note, by);
            // Waiting for payment again: its holds get a deadline, as at checkout (kltn-docs 14 BR-07).
            inventory.rearmReservations(orderId);
            repository.save(order);
            events.publishEventsOf(order);
        }
        log.info("Credit refused for order {} by {}: {}", order.orderNumber(), by, action);
        return orderService.findById(orderId).orElseThrow();
    }

    private Order lockHeld(UUID orderId) {
        return repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
    }

    /** Records the decision against the check that put the order on hold, and closes the hold. */
    private void decide(Order order, CreditChecks.Outcome outcome, String note, UUID by) {
        UUID orderId = order.id().value();
        var hold = holds.findFirstByOrderIdAndResolvedAtIsNullOrderByRaisedAtDesc(orderId)
                .filter(h -> OrderServiceImpl.CREDIT_HOLD.equals(h.getReason()))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_ON_CREDIT_HOLD,
                        "Order %s is not waiting for a credit decision".formatted(order.orderNumber())));
        CreditChecks.Check held = creditChecks.latest(orderId).orElseThrow();
        Instant now = clock.instant();
        creditChecks.record(new CreditChecks.Check(orderId, now, held.creditLimit(), held.exposure(), held.orderAmount(),
                outcome, by, now, note.trim()));
        hold.resolve(by, outcome.name() + ": " + note.trim(), now);
        holds.save(hold);
    }

    private static void requireNote(String note) {
        if (note == null || note.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A credit decision must say why");
        }
    }
}
