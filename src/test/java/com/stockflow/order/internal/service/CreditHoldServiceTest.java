package com.stockflow.order.internal.service;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.CreditTerms;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.DepositBalancePolicy;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.api.BalancePolicy;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.DeliveryMode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.CreditChecks;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.entity.OrderHoldJpaEntity;
import com.stockflow.order.internal.repository.CreditHoldSearch;
import com.stockflow.order.internal.repository.OrderHoldJpaRepository;
import com.stockflow.payment.api.CreditPosition;
import com.stockflow.payment.api.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.order.internal.service.OrderFixtures.NOW;
import static com.stockflow.order.internal.service.OrderFixtures.STAFF;
import static com.stockflow.order.internal.service.OrderFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * kltn-docs 15 §4.3 and BR-03, 18 BR-02 (SCRUM-427): an order over its customer's credit limit waits for a person, who
 * approves it or refuses it; a refusal cancels the order or moves it to prepaid or deposit terms the customer is
 * allowed. Every decision says why and is recorded against the credit check that put the order on hold.
 */
class CreditHoldServiceTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final OrderServiceImpl orders = mock(OrderServiceImpl.class);
    private final OrderService orderService = mock(OrderService.class);
    private final OrderEventPublisher events = mock(OrderEventPublisher.class);
    private final CreditChecks creditChecks = mock(CreditChecks.class);
    private final OrderHoldJpaRepository holds = mock(OrderHoldJpaRepository.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final OrderSummary summary = mock(OrderSummary.class);
    private final CreditHoldService service = new CreditHoldService(repository, orders, orderService, events,
            creditChecks, holds, customers, mock(CreditHoldSearch.class), inventory, paymentService,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static CreditTerms terms(UUID customer, boolean prepaid, boolean deposit, boolean credit,
                                     String limit, DepositBalancePolicy balance) {
        return new CreditTerms(customer, true, prepaid, deposit, credit, CommercialTerm.CREDIT,
                new BigDecimal("40"), limit == null ? null : new BigDecimal(limit), 30, "VND", null, null, null, 1L,
                balance);
    }

    /** An over-limit credit order: ON_HOLD with reason CREDIT, a credit check and an open hold row behind it. */
    private Order onCreditHold() {
        Order order = OrderFixtures.draft(PaymentTerm.CREDIT, UUID.randomUUID());
        order.submitOnCredit(false);
        order.pullDomainEvents();
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));
        when(orderService.findById(order.id().value())).thenReturn(Optional.of(summary));
        return order;
    }

    private OrderHoldJpaEntity openHold(Order order) {
        OrderHoldJpaEntity hold = new OrderHoldJpaEntity(UUID.randomUUID(), order.id().value(), "CREDIT", NOW);
        when(holds.findFirstByOrderIdAndResolvedAtIsNullOrderByRaisedAtDesc(order.id().value()))
                .thenReturn(Optional.of(hold));
        when(creditChecks.latest(order.id().value())).thenReturn(Optional.of(new CreditChecks.Check(
                order.id().value(), NOW, new BigDecimal("1000000"), new BigDecimal("900000"), new BigDecimal("1100000"),
                CreditChecks.Outcome.OVER_LIMIT, null, null, null)));
        return hold;
    }

    @Nested
    @DisplayName("the customer's credit position")
    class Position {

        @Test
        @DisplayName("exposure is undelivered credit plus open receivables; what is left of the limit is the available credit")
        void availableCredit() {
            UUID customer = UUID.randomUUID();
            CreditTerms terms = terms(customer, true, false, true, "5000000", null);
            when(customers.creditTerms(customer)).thenReturn(terms);
            when(paymentService.creditPosition(customer)).thenReturn(new CreditPosition(new BigDecimal("1200000"), true,
                    new BigDecimal("400000"), LocalDate.of(2026, 10, 1)));
            when(creditChecks.undeliveredCreditExposure(customer, null)).thenReturn(new BigDecimal("2000000"));

            var position = service.position(customer);

            assertThat(position.exposure()).isEqualByComparingTo("3200000");
            assertThat(position.available()).isEqualByComparingTo("1800000");
            assertThat(position.hasOverdue()).isTrue();
            assertThat(position.overdueAmount()).isEqualByComparingTo("400000");
            assertThat(position.oldestOverdueDueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(position.allowedBalancePolicies()).isEmpty();
        }

        @Test
        @DisplayName("over the limit nothing is available, never a negative amount")
        void availableNeverNegative() {
            UUID customer = UUID.randomUUID();
            when(customers.creditTerms(customer)).thenReturn(terms(customer, true, false, true, "1000000", null));
            when(paymentService.creditPosition(customer)).thenReturn(new CreditPosition(new BigDecimal("700000"), false));
            when(creditChecks.undeliveredCreditExposure(customer, null)).thenReturn(new BigDecimal("900000"));

            assertThat(service.position(customer).available()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a customer without credit has no available amount (null), and still reports what they owe")
        void noCreditNoAvailable() {
            UUID customer = UUID.randomUUID();
            when(customers.creditTerms(customer)).thenReturn(CreditTerms.prepaidOnly(customer));
            when(paymentService.creditPosition(customer)).thenReturn(new CreditPosition(BigDecimal.ZERO, false));
            when(creditChecks.undeliveredCreditExposure(customer, null)).thenReturn(BigDecimal.ZERO);

            var position = service.position(customer);

            assertThat(position.available()).isNull();
            assertThat(position.exposure()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("the balance policies offered follow the customer's terms: before delivery, their own, on credit")
        void balancePolicies() {
            UUID customer = UUID.randomUUID();
            when(customers.creditTerms(customer)).thenReturn(
                    terms(customer, true, true, true, "5000000", DepositBalancePolicy.ON_DELIVERY));
            when(paymentService.creditPosition(customer)).thenReturn(new CreditPosition(BigDecimal.ZERO, false));
            when(creditChecks.undeliveredCreditExposure(customer, null)).thenReturn(BigDecimal.ZERO);

            assertThat(service.position(customer).allowedBalancePolicies()).containsExactly(
                    BalancePolicy.BEFORE_DELIVERY, BalancePolicy.ON_DELIVERY, BalancePolicy.ON_CREDIT);
        }
    }

    @Nested
    @DisplayName("approving (ON_HOLD to CONFIRMED)")
    class Approve {

        @Test
        @DisplayName("confirms the order, records APPROVED with who and why, closes the hold and announces")
        void approves() {
            Order order = onCreditHold();
            OrderHoldJpaEntity hold = openHold(order);

            assertThat(service.approve(order.id().value(), "  good payer  ", STAFF)).isSameAs(summary);

            assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
            ArgumentCaptor<CreditChecks.Check> check = ArgumentCaptor.forClass(CreditChecks.Check.class);
            verify(creditChecks).record(check.capture());
            assertThat(check.getValue().outcome()).isEqualTo(CreditChecks.Outcome.APPROVED);
            assertThat(check.getValue().decidedBy()).isEqualTo(STAFF);
            assertThat(check.getValue().decidedAt()).isEqualTo(NOW);
            assertThat(check.getValue().note()).isEqualTo("good payer");
            assertThat(check.getValue().creditLimit()).as("the limit and exposure of the check that held it")
                    .isEqualByComparingTo("1000000");
            assertThat(check.getValue().exposure()).isEqualByComparingTo("900000");
            assertThat(hold.getResolvedAt()).isEqualTo(NOW);
            verify(holds).save(hold);
            verify(repository).save(order);
            verify(events).publishEventsOf(order);
        }

        @Test
        @DisplayName("a decision without a reason is refused (VALIDATION_FAILED) before anything is loaded")
        void needsAReason() {
            assertThat(codeOf(() -> service.approve(UUID.randomUUID(), null, STAFF))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(codeOf(() -> service.approve(UUID.randomUUID(), "   ", STAFF))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            verify(repository, never()).findByIdForUpdate(any());
        }

        @Test
        @DisplayName("an unknown order is NOT_FOUND")
        void unknownOrder() {
            when(repository.findByIdForUpdate(any())).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.approve(UUID.randomUUID(), "ok", STAFF))).isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("an order that is not on a credit hold cannot be approved (ORDER_NOT_ON_CREDIT_HOLD)")
        void notOnHold() {
            Order order = OrderFixtures.confirmedPrepaid();
            when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));

            assertThat(codeOf(() -> service.approve(order.id().value(), "ok", STAFF)))
                    .isEqualTo(ErrorCode.ORDER_NOT_ON_CREDIT_HOLD);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("a credit hold with no open hold row of reason CREDIT is ORDER_NOT_ON_CREDIT_HOLD, nothing saved")
        void noOpenHoldRow() {
            Order order = onCreditHold();
            when(holds.findFirstByOrderIdAndResolvedAtIsNullOrderByRaisedAtDesc(order.id().value()))
                    .thenReturn(Optional.of(new OrderHoldJpaEntity(UUID.randomUUID(), order.id().value(), "PAYMENT",
                            NOW)));

            assertThat(codeOf(() -> service.approve(order.id().value(), "ok", STAFF)))
                    .isEqualTo(ErrorCode.ORDER_NOT_ON_CREDIT_HOLD);
            verify(creditChecks, never()).record(any());
            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("refusing")
    class Refuse {

        @Test
        @DisplayName("CANCEL records REJECTED and cancels the order with CREDIT_REJECTED and the note")
        void cancels() {
            Order order = onCreditHold();
            OrderHoldJpaEntity hold = openHold(order);

            service.refuse(order.id().value(), CreditHoldService.RefusalAction.CANCEL, null, "over the limit", STAFF);

            ArgumentCaptor<CreditChecks.Check> check = ArgumentCaptor.forClass(CreditChecks.Check.class);
            verify(creditChecks).record(check.capture());
            assertThat(check.getValue().outcome()).isEqualTo(CreditChecks.Outcome.REJECTED);
            assertThat(hold.getResolvedAt()).isEqualTo(NOW);
            ArgumentCaptor<CancelOrderCommand> command = ArgumentCaptor.forClass(CancelOrderCommand.class);
            verify(orders).cancelLoaded(org.mockito.ArgumentMatchers.eq(order), command.capture());
            assertThat(command.getValue().reasonCode()).isEqualTo(CancellationReasonCode.CREDIT_REJECTED);
            assertThat(command.getValue().note()).isEqualTo("over the limit");
            assertThat(command.getValue().cancelledBy()).isEqualTo(STAFF);
            verify(inventory, never()).rearmReservations(any());
        }

        @Test
        @DisplayName("SWITCH_TO_PREPAID moves the order to PENDING_PAYMENT on prepaid terms and re-arms its holds' deadline")
        void switchesToPrepaid() {
            Order order = onCreditHold();
            openHold(order);
            when(customers.creditTerms(order.customerId())).thenReturn(
                    terms(order.customerId(), true, false, true, "1000000", null));

            service.refuse(order.id().value(), CreditHoldService.RefusalAction.SWITCH_TO_PREPAID, null, "pay first",
                    STAFF);

            assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(order.paymentTerm()).isEqualTo(PaymentTerm.PREPAID);
            verify(inventory).rearmReservations(order.id().value());
            verify(repository).save(order);
            verify(events).publishEventsOf(order);
            verify(orders, never()).cancelLoaded(any(), any());
        }

        @Test
        @DisplayName("a term the customer may not use is refused (PAYMENT_TERM_NOT_ALLOWED) and nothing changes")
        void termNotAllowed() {
            Order order = onCreditHold();
            openHold(order);
            when(customers.creditTerms(order.customerId())).thenReturn(
                    terms(order.customerId(), false, false, true, "1000000", null));

            assertThat(codeOf(() -> service.refuse(order.id().value(), CreditHoldService.RefusalAction.SWITCH_TO_PREPAID,
                    null, "pay first", STAFF))).isEqualTo(ErrorCode.PAYMENT_TERM_NOT_ALLOWED);
            assertThat(order.status()).isEqualTo(OrderStatus.ON_HOLD);
            verify(creditChecks, never()).record(any());
            verify(inventory, never()).rearmReservations(any());
        }

        @Test
        @DisplayName("SWITCH_TO_DEPOSIT without a percentage takes the customer's default deposit")
        void depositTakesTheCustomersPercent() {
            Order order = onCreditHold();
            openHold(order);
            when(customers.creditTerms(order.customerId())).thenReturn(
                    terms(order.customerId(), true, true, true, "1000000", DepositBalancePolicy.BEFORE_DELIVERY));

            service.refuse(order.id().value(), CreditHoldService.RefusalAction.SWITCH_TO_DEPOSIT, null, "deposit",
                    STAFF);

            assertThat(order.paymentTerm()).isEqualTo(PaymentTerm.DEPOSIT);
            assertThat(order.depositRequired()).isEqualByComparingTo("440000");
            assertThat(order.balancePolicy()).isEqualTo(BalancePolicy.BEFORE_DELIVERY);
            assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        }

        @Test
        @DisplayName("an explicit deposit percentage wins over the customer's default")
        void explicitDepositPercent() {
            Order order = onCreditHold();
            openHold(order);
            when(customers.creditTerms(order.customerId())).thenReturn(
                    terms(order.customerId(), true, true, true, "1000000", DepositBalancePolicy.BEFORE_DELIVERY));

            service.refuse(order.id().value(), CreditHoldService.RefusalAction.SWITCH_TO_DEPOSIT,
                    new BigDecimal("50"), "deposit", STAFF);

            assertThat(order.depositRequired()).isEqualByComparingTo("550000");
        }

        @Test
        @DisplayName("a balance on credit is not the way out of a refusal: it becomes paid-before-delivery")
        void balanceOnCreditBecomesBeforeDelivery() {
            Order order = onCreditHold();
            openHold(order);
            when(customers.creditTerms(order.customerId())).thenReturn(
                    terms(order.customerId(), true, true, true, "1000000", DepositBalancePolicy.ON_CREDIT));

            service.refuse(order.id().value(), CreditHoldService.RefusalAction.SWITCH_TO_DEPOSIT, null, "deposit",
                    STAFF);

            assertThat(order.balancePolicy()).isEqualTo(BalancePolicy.BEFORE_DELIVERY);
        }

        @Test
        @DisplayName("a full-truckload order whose customer's default is ON_DELIVERY pays before delivery (LP-2), and the note says so")
        void fullTruckloadGivesWayToBeforeDelivery() {
            Order order = onCreditHold();
            order.changeDeliveryMode(DeliveryMode.FULL_TRUCKLOAD);
            openHold(order);
            when(customers.creditTerms(order.customerId())).thenReturn(
                    terms(order.customerId(), true, true, true, "1000000", DepositBalancePolicy.ON_DELIVERY));

            service.refuse(order.id().value(), CreditHoldService.RefusalAction.SWITCH_TO_DEPOSIT, null, "deposit",
                    STAFF);

            assertThat(order.balancePolicy()).isEqualTo(BalancePolicy.BEFORE_DELIVERY);
            ArgumentCaptor<CreditChecks.Check> check = ArgumentCaptor.forClass(CreditChecks.Check.class);
            verify(creditChecks).record(check.capture());
            assertThat(check.getValue().note()).startsWith("deposit").contains("LP-2");
        }

        @Test
        @DisplayName("a long note is cut so the decision, with the LP-2 remark, fits the 1000-character columns")
        void aLongNoteIsCut() {
            Order order = onCreditHold();
            order.changeDeliveryMode(DeliveryMode.FULL_TRUCKLOAD);
            openHold(order);
            when(customers.creditTerms(order.customerId())).thenReturn(
                    terms(order.customerId(), true, true, true, "1000000", DepositBalancePolicy.ON_DELIVERY));

            service.refuse(order.id().value(), CreditHoldService.RefusalAction.SWITCH_TO_DEPOSIT, null, "x".repeat(990),
                    STAFF);

            ArgumentCaptor<CreditChecks.Check> check = ArgumentCaptor.forClass(CreditChecks.Check.class);
            verify(creditChecks).record(check.capture());
            assertThat(check.getValue().note()).hasSizeLessThanOrEqualTo(980);
            assertThat(check.getValue().note()).endsWith("does not apply (LP-2)]");
        }

        @Test
        @DisplayName("a refusal without a reason is VALIDATION_FAILED")
        void needsAReason() {
            assertThat(codeOf(() -> service.refuse(UUID.randomUUID(), CreditHoldService.RefusalAction.CANCEL, null, "",
                    STAFF))).isEqualTo(ErrorCode.VALIDATION_FAILED);
            verify(repository, never()).findByIdForUpdate(any());
            verify(orders, never()).cancelLoaded(any(), any());
        }
    }
}
