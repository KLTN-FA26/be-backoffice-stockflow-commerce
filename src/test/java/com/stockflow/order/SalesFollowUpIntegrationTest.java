package com.stockflow.order;

import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.SalesFollowUps;
import com.stockflow.order.internal.service.CreditHoldService;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SalesOwners;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-02 FLOW-1..3, CONC-2 against a real database: the scans the hourly follow-up reads find an order held for credit
 * (since its hold was raised, with its customer), an order confirmed with no shipping warehouse, an order of a signed
 * contract still waiting for its installment on signing, and an order a voided receipt put on PAYMENT hold (not one put
 * on it by hand); and accounting's credit decision reaches the inbox of whoever looks after the customer once (15 BR-22
 * as changed on 2026-11-28: the customer's owner, here the salesperson who also sent the contract).
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class SalesFollowUpIntegrationTest {

    private static final long PRICE = 40_000;

    @Autowired SalesFollowUps followUps;
    @Autowired CustomerService customers;
    @Autowired CreditHoldService credit;
    @Autowired OrderService orders;
    @Autowired ApplicationContext context;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private ConfirmedOrders confirmedOrders;
    private UUID customer;
    private UUID accountant;
    private CustomerSkuRows.Issued sku;

    @BeforeEach
    void setUp() {
        confirmedOrders = new ConfirmedOrders(context);
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50);
        confirmedOrders.legalProfile(customer);
        // The salesperson who drafts and sends the contracts owns the customer (an owner is an ACTIVE SALES_STAFF).
        SalesOwners owners = new SalesOwners(context);
        owners.own(customer, confirmedOrders.sales().userId());
    }

    private UUID heldForCredit() {
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, new BigDecimal("1000000"), 30, "test terms",
                accountant, null));
        UUID order = confirmedOrders.signedHeldForCredit(customer, List.of(new SalesContracts.Installment("After delivery",
                "AFTER_DELIVERY", null, new BigDecimal("100"), 30, null)), ConfirmedOrders.Line.cartons(sku, 10, PRICE))
                .orderId();
        assertThat(confirmedOrders.status(order)).isEqualTo(OrderStatus.ON_HOLD);
        return order;
    }

    private static SalesFollowUps.WaitingOrder find(List<SalesFollowUps.WaitingOrder> found, UUID orderId) {
        return found.stream().filter(o -> o.orderId().equals(orderId)).findFirst().orElse(null);
    }

    private List<Map<String, Object>> told(UUID recipient, String dedupeKey) {
        return jdbc.queryForList("""
                SELECT severity, title, body, link FROM notification.user_notification
                WHERE recipient_id = ? AND dedupe_key = ?""", recipient, dedupeKey);
    }

    @Test
    @DisplayName("FLOW-1: held for credit it is found since its hold was raised; the decision reaches its salesperson once")
    void creditHold() {
        UUID order = heldForCredit();
        UUID sales = confirmedOrders.sales().userId();

        SalesFollowUps.WaitingOrder held = find(followUps.heldFor("CREDIT", 200), order);
        assertThat(held).isNotNull();
        assertThat(held.customerId()).as("whose order it is: its owner is told").isEqualTo(customer);
        Timestamp raised = jdbc.queryForObject("""
                SELECT raised_at FROM ordering.order_hold WHERE order_id = ? AND reason = 'CREDIT'
                AND resolved_at IS NULL""", Timestamp.class, order);
        assertThat(held.since()).isEqualTo(raised.toInstant());
        assertThat(find(followUps.heldFor("PAYMENT", 200), order)).isNull();

        credit.approve(order, "long-standing customer", accountant);

        assertThat(find(followUps.heldFor("CREDIT", 200), order)).isNull();
        List<Map<String, Object>> decided = told(sales, "credit-decided:" + order);
        assertThat(decided).hasSize(1);
        assertThat(decided.getFirst().get("severity")).isEqualTo("INFO");
        assertThat((String) decided.getFirst().get("body")).doesNotContain("long-standing customer");
        assertThat(find(followUps.confirmedWithoutWarehouse(200), order))
                .as("FLOW-2: confirmed, nothing due on signing, no warehouse chosen yet").isNotNull();
    }

    @Test
    @DisplayName("FLOW-1b: refused, the salesperson is told the order is cancelled, once")
    void creditRefused() {
        UUID order = heldForCredit();

        credit.refuse(order, "overdue elsewhere", accountant);

        List<Map<String, Object>> decided = told(confirmedOrders.sales().userId(), "credit-decided:" + order);
        assertThat(decided).hasSize(1);
        assertThat(decided.getFirst().get("severity")).isEqualTo("WARN");
        assertThat((String) decided.getFirst().get("body")).contains("CREDIT_REJECTED");
    }

    @Test
    @DisplayName("CONC-2: signed and waiting for its installment on signing it is listed; once paid and confirmed, not")
    void awaitingOnSigning() {
        ConfirmedOrders.Signed signed = confirmedOrders.signed(customer, ConfirmedOrders.Line.cartons(sku, 10, PRICE));
        assertThat(confirmedOrders.status(signed.orderId())).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(followUps.awaitingOnSigning(2000)).contains(signed.orderId());

        ConfirmedOrders.Confirmed confirmed = confirmedOrders.pay(signed);

        assertThat(followUps.awaitingOnSigning(2000)).doesNotContain(confirmed.orderId());
        SalesFollowUps.WaitingOrder unplaced = find(followUps.confirmedWithoutWarehouse(200), confirmed.orderId());
        assertThat(unplaced).isNotNull();
        assertThat(unplaced.customerId()).isEqualTo(customer);
    }

    @Test
    @DisplayName("FLOW-3: a PAYMENT hold raised by hand is not a voided receipt's; one raised by a void is reported")
    void paymentHoldByHandIsNotReported() {
        ConfirmedOrders.Confirmed confirmed = confirmedOrders.pay(
                confirmedOrders.signed(customer, ConfirmedOrders.Line.cartons(sku, 10, PRICE)));
        UUID order = confirmed.orderId();

        orders.putOnHold(order, HoldReason.PAYMENT, "customer asked to wait for its own bank", accountant);

        assertThat(find(followUps.heldFor("PAYMENT", 200), order)).isNotNull();
        assertThat(find(followUps.heldAfterVoidedReceipt(200), order)).as("raised by hand").isNull();

        // The note OrderServiceImpl.installmentsShort raises its hold with.
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE ordering.order_hold SET note = ? WHERE order_id = ? AND resolved_at IS NULL",
                SalesFollowUps.VOIDED_RECEIPT_HOLD_NOTE.formatted("PT-0001"), order));
        SalesFollowUps.WaitingOrder voided = find(followUps.heldAfterVoidedReceipt(200), order);
        assertThat(voided).isNotNull();
        assertThat(voided.customerId()).isEqualTo(customer);
    }
}
