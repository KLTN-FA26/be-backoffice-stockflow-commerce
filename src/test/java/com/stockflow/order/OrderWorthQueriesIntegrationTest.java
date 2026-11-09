package com.stockflow.order;

import com.stockflow.order.internal.domain.CreditChecks;
import com.stockflow.order.internal.domain.SettlementCandidates;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4B-3: the two native queries that value an order in SQL read what it is worth now as {@code total_amount -
 * closed_amount}, the closed units' share of their lines with tax as the order computed it - never {@code
 * cancelled_quantity x unit_price}, which leaves the VAT of the closed units in the order.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class OrderWorthQueriesIntegrationTest {

    private static final String SKU = "SOFA-3S-GREY";

    @Autowired CreditChecks creditChecks;
    @Autowired SettlementCandidates settlementCandidates;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    /**
     * 1,000 pieces at 2,000 VND with 10% VAT: 2,200,000 with tax; 100 pieces closed out of it, worth 220,000 with
     * their VAT. What the order is worth now is 1,980,000; the units at their price alone would say 2,000,000.
     */
    private UUID orderWithVatAndClosedUnits(UUID customer, String status) {
        UUID order = tx.execute(s -> ReferenceRows.orderWithLine(entityManager, SKU, status, customer, null, null));
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE ordering.order_line SET quantity = 1000, unit_price = 2000, tax_rate = 10, "
                    + "tax_amount = 200000, cancelled_quantity = 100 WHERE order_id = ?", order);
            jdbc.update("UPDATE ordering.customer_order SET total_amount = 2200000, tax_amount = 200000, "
                    + "closed_amount = 220000 WHERE id = ?", order);
        });
        return order;
    }

    @Test
    @DisplayName("credit exposure: the order's total less its closed units with their VAT")
    void creditExposureLeavesNoClosedVat() {
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        orderWithVatAndClosedUnits(customer, "CONFIRMED");
        assertThat(creditChecks.undeliveredCreditExposure(customer, null)).isEqualByComparingTo("1980000");
    }

    @Test
    @DisplayName("a delivered order that paid what it is worth now, with tax, is ready to complete")
    void paidWhatItIsWorthWithTax() {
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        UUID order = orderWithVatAndClosedUnits(customer, "DELIVERED");
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET paid_amount = 1979999, "
                + "delivered_at = NOW() - interval '30 days' WHERE id = ?", order));
        assertThat(settlementCandidates.deliveredBy(Instant.now(), 10_000)).as("still owing a dong").doesNotContain(order);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET paid_amount = 1980000 WHERE id = ?",
                order));
        assertThat(settlementCandidates.deliveredBy(Instant.now(), 10_000)).contains(order);
    }
}
