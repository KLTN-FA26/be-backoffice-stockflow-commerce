package com.stockflow.order.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
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
 * Orders held for credit when their contract was signed, and a customer's credit position (SCRUM-427; kltn-docs 15
 * §4.3, BR-10, BR-11; 18 BR-02).
 *
 * <p>Accounting confirms a held order, or refuses it: the order is then cancelled ({@code CREDIT_REJECTED}). Either way
 * the decision is recorded with who made it and why ({@code order_credit_check}), and the order's salesperson is told
 * the outcome in the in-app inbox (WF-02 FLOW-1b, {@link SalesFollowUpService#creditDecided}). Until 4B-10 a refusal could also
 * switch a checkout order to prepaid or deposit terms; orders have no payment terms of their own any more.</p>
 */
@Service
@Transactional
public class CreditHoldService {

    private static final Logger log = LoggerFactory.getLogger(CreditHoldService.class);

    /** The customer's credit, what they owe after delivery and what is left, for Sales and the customer. */
    public record CreditPosition(CreditTerms terms, BigDecimal exposure, BigDecimal available, boolean hasOverdue,
                                 BigDecimal overdueAmount, java.time.LocalDate oldestOverdueDueDate) {
    }

    private final OrderRepository repository;
    private final OrderServiceImpl orders;
    private final OrderService orderService;
    private final OrderEventPublisher events;
    private final CreditChecks creditChecks;
    private final OrderHoldJpaRepository holds;
    private final CustomerService customers;
    private final com.stockflow.order.internal.repository.CreditHoldSearch search;
    private final com.stockflow.payment.api.PaymentService paymentService;
    private final Clock clock;
    private final com.stockflow.order.internal.domain.SalesContractRepository contracts;
    private final SalesFollowUpService followUps;
    /** The credit of a page of customers in one read (audit bo-29: the customer list's credit columns). */
    private final com.stockflow.customer.api.CustomerCredits credits;

    CreditHoldService(OrderRepository repository, OrderServiceImpl orders, OrderService orderService,
                      OrderEventPublisher events, CreditChecks creditChecks, OrderHoldJpaRepository holds,
                      CustomerService customers, com.stockflow.order.internal.repository.CreditHoldSearch search,
                      com.stockflow.payment.api.PaymentService paymentService, Clock clock,
                      com.stockflow.order.internal.domain.SalesContractRepository contracts,
                      SalesFollowUpService followUps, com.stockflow.customer.api.CustomerCredits credits) {
        this.credits = credits;
        this.contracts = contracts;
        this.followUps = followUps;
        this.repository = repository;
        this.orders = orders;
        this.orderService = orderService;
        this.events = events;
        this.creditChecks = creditChecks;
        this.holds = holds;
        this.customers = customers;
        this.search = search;
        this.paymentService = paymentService;
        this.clock = clock;
    }

    /**
     * The customer's credit, what they owe after delivery and what is left of the limit (null without credit). The
     * exposure is every signed contract's installments paid after delivery and the cancellation fee items not paid
     * (15 BR-10, 4B-6) - one held for credit too: it is signed, and what it would add is what accounting decides on.
     */
    @Transactional(readOnly = true)
    public CreditPosition position(UUID customerId) {
        return position(customers.creditTerms(customerId), paymentService.creditPosition(customerId));
    }

    /**
     * {@link #position} of many customers, for a list that shows each row's credit (audit bo-29: the back office
     * customer list): two reads whatever the number - the customers' credit terms, then what they owe - not two per
     * customer. The customer module cannot ask payment itself (payment depends on customer), so the two meet here, where
     * the single position already joins them.
     *
     * @return one position per distinct customer that exists, in the order given; an unknown id is left out rather than
     *         refused, as a page of the list may race a customer's removal
     */
    @Transactional(readOnly = true)
    public java.util.List<CreditPosition> positions(java.util.Collection<UUID> customerIds) {
        java.util.List<UUID> ids = customerIds == null ? java.util.List.of()
                : customerIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return java.util.List.of();
        }
        var terms = credits.creditTermsOf(ids);
        var owed = paymentService.creditPositions(terms.keySet());
        return ids.stream().filter(terms::containsKey)
                .map(id -> position(terms.get(id), owed.get(id)))
                .toList();
    }

    /** The one rule both reads share: what is left is the limit less the exposure, never below zero. */
    private static CreditPosition position(CreditTerms terms, com.stockflow.payment.api.CreditPosition owed) {
        BigDecimal exposure = owed.outstandingReceivables();
        BigDecimal available = terms.creditLimit() == null ? null
                : terms.creditLimit().subtract(exposure).max(BigDecimal.ZERO);
        return new CreditPosition(terms, exposure, available, owed.hasOverdue(), owed.overdueAmount(),
                owed.oldestOverdueDueDate());
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
        // 14 §5.3: an order held when its contract was signed still waits for the installment on signing - unless the
        // customer paid it while the order was held (4B-6: the installments say).
        var contract = signedContract(order);
        order.approveCredit(by, clock.instant(), contract.isPresent()
                && !paymentService.installmentStanding(orderId).onSigningPaid());
        decide(order, CreditChecks.Outcome.APPROVED, note, by);
        repository.save(order);
        events.publishEventsOf(order);
        followUps.creditDecided(orderId, order.orderNumber().value(), order.customerId(), true);
        log.info("Credit approved for order {} by {}", order.orderNumber(), by);
        return orderService.findById(orderId).orElseThrow();
    }

    /**
     * Refused (15 §4.3 step 10): the order is cancelled ({@code CREDIT_REJECTED}), and the money its installments
     * collected is settled with the cancellation (4B-9).
     */
    @Auditable(action = AuditAction.REJECT, resourceType = "order-credit-hold", resourceId = "#orderId")
    public OrderSummary refuse(UUID orderId, String note, UUID by) {
        requireNote(note);
        Order order = lockHeld(orderId);
        decide(order, CreditChecks.Outcome.REJECTED, note, by);
        orders.cancelLoaded(order, new CancelOrderCommand(CancellationReasonCode.CREDIT_REJECTED, note, null, by));
        // 15 BR-22: whoever looks after the order's customer hears of it, not who sent the contract.
        followUps.creditDecided(orderId, order.orderNumber().value(), order.customerId(), false);
        log.info("Credit refused for order {} by {}", order.orderNumber(), by);
        return orderService.findById(orderId).orElseThrow();
    }

    /** The order's signed contract (4B-5): an order held at signing is decided against it. */
    private java.util.Optional<com.stockflow.order.internal.domain.SalesContract> signedContract(Order order) {
        return contracts.findLiveByOrderId(order.id().value())
                .filter(c -> c.status() == com.stockflow.order.internal.domain.SalesContractStatus.SIGNED);
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
                        "Order %s is not waiting for a credit decision".formatted(order.orderNumber()))
                        .localized("refusal.credit.notHeld", order.orderNumber().value()));
        CreditChecks.Check held = creditChecks.latest(orderId).orElseThrow();
        Instant now = clock.instant();
        creditChecks.record(new CreditChecks.Check(orderId, now, held.creditLimit(), held.exposure(), held.orderAmount(),
                outcome, by, now, note.trim()));
        hold.resolve(by, outcome.name() + ": " + note.trim(), now);
        holds.save(hold);
    }

    private static void requireNote(String note) {
        if (note == null || note.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A credit decision must say why")
                    .localized("refusal.credit.noteRequired");
        }
    }
}
