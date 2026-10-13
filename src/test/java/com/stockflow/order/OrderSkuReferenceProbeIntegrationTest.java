package com.stockflow.order;

import com.stockflow.product.api.SkuReference;
import com.stockflow.product.api.SkuReferenceProbe;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which order lines still refer to a SKU (SCRUM-461, BR-PRD-005), for every order status, against Postgres: an order
 * that has been placed and not shipped counts; a cart, a shipped order and the ones that ended do not.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class OrderSkuReferenceProbeIntegrationTest {

    @Autowired List<SkuReferenceProbe> probes;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SkuReferenceProbe probe;
    private String sku;
    private UUID staff;

    @BeforeEach
    void setUp() {
        probe = probes.stream().filter(p -> AopUtils.getTargetClass(p).getSimpleName().equals("OrderSkuReferenceProbe"))
                .findFirst().orElseThrow();
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        sku = tx.execute(s -> ReferenceRows.approvedProduct(entityManager).sku());
    }

    private UUID order(String status, String forSku) {
        return tx.execute(s -> ReferenceRows.orderWithLine(entityManager, forSku, status, DemoData.CUSTOMER_ID, staff,
                DemoData.WAREHOUSE_HCM));
    }

    @Test
    @DisplayName("PENDING_PAYMENT to ON_HOLD count; DRAFT, SHIPPED, DELIVERED, COMPLETED, CANCELLED and RETURNED do not")
    void everyOrderStatus() {
        Map<String, UUID> expected = new LinkedHashMap<>();
        List<String> counted = List.of("PENDING_PAYMENT", "CONFIRMED", "IN_PRODUCTION", "READY_TO_FULFILL", "IN_FULFILMENT",
                "ON_HOLD");
        List<String> notCounted = List.of("DRAFT", "SHIPPED", "DELIVERED", "COMPLETED", "CANCELLED", "RETURNED");
        counted.forEach(status -> expected.put(status, order(status, sku)));
        notCounted.forEach(status -> order(status, sku));

        List<SkuReference> found = probe.openReferences(List.of(sku), 100);

        assertThat(found).extracting(SkuReference::documentId).containsExactlyInAnyOrderElementsOf(expected.values());
        assertThat(found).allSatisfy(r -> {
            assertThat(r.kind()).isEqualTo(SkuReference.Kind.SALES_ORDER_LINE);
            assertThat(r.sku()).isEqualTo(sku);
            assertThat(r.documentNumber()).startsWith("SO-RR-");
        });
    }

    @Test
    @DisplayName("only the SKUs asked about, the most recently placed order first, at most the limit")
    void scopeAndLimit() {
        String other = tx.execute(s -> ReferenceRows.approvedProduct(entityManager).sku());
        UUID first = order("CONFIRMED", sku);
        UUID second = order("CONFIRMED", sku);
        UUID third = order("CONFIRMED", other);

        assertThat(probe.openReferences(List.of(sku), 100)).extracting(SkuReference::documentId).containsExactly(second, first);
        assertThat(probe.openReferences(List.of(other), 100)).extracting(SkuReference::documentId).containsExactly(third);
        assertThat(probe.openReferences(List.of(sku), 1)).hasSize(1);
        assertThat(probe.openReferences(List.of(), 10)).isEmpty();
        assertThat(probe.openReferences(List.of("NO-SUCH-SKU"), 10)).isEmpty();
    }
}
