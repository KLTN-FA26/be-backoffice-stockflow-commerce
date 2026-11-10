package com.stockflow.notification;

import com.stockflow.contracts.ProductionOrderSent;
import com.stockflow.support.AlertFixtures;
import com.stockflow.support.Await;
import com.stockflow.support.SecuredIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arrival notice of a sent production order reaches the people who receive goods at its warehouse, against the
 * real staff directory and the permission rows as V20261113000200 left them. The notice is addressed to the holders of
 * {@code procurement-goods-receipts:CREATE}; the migration deleted {@code procurement-goods-receipts:APPROVE} and every
 * other purchasing grant, so a notice addressed to a deleted permission would reach nobody and nothing else would say
 * so. Warehouse managers and warehouse staff hold CREATE; a locked account, a manager of another warehouse, a planner
 * and a customer do not receive it.
 */
@SecuredIntegrationTest
class ProductionOrderArrivalNoticeIntegrationTest {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @MockitoBean JwtDecoder decoder;

    private AlertFixtures fixtures;
    private UUID hcm;
    private UUID managerHcm;
    private UUID staffHcm;
    private UUID lockedManager;
    private UUID managerOther;
    private UUID planner;
    private UUID customer;

    @BeforeEach
    void setUp() {
        fixtures = new AlertFixtures(jdbc, tx, entityManager);
        hcm = fixtures.hcm();
        var other = fixtures.otherWarehouse();
        managerHcm = fixtures.staff("WAREHOUSE_MANAGER", hcm);
        staffHcm = fixtures.staff("WAREHOUSE_STAFF", hcm);
        lockedManager = fixtures.staff("WAREHOUSE_MANAGER", hcm);
        fixtures.setStatus(lockedManager, "LOCKED");
        managerOther = fixtures.staff("WAREHOUSE_MANAGER", other.id());
        planner = fixtures.staff("INVENTORY_PLANNER");
        customer = fixtures.staff("CUSTOMER");
    }

    private void publish(UUID productionOrder, String code) {
        tx.executeWithoutResult(s -> publisher.publishEvent(new ProductionOrderSent(UUID.randomUUID(), productionOrder,
                code, hcm, LocalDate.now().plusDays(20), 12, new BigDecimal("3.5"), Instant.now())));
    }

    @Test
    void theActiveWarehouseManagerOfTheReceivingWarehouseIsTold() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.permission WHERE code = "
                + "'procurement-goods-receipts:CREATE'", Long.class)).as("the audience permission survived the migration")
                .isOne();
        UUID order = UUID.randomUUID();
        publish(order, "PRD-20261110-7001");
        Await.until("the manager is told", Duration.ofSeconds(20), () -> fixtures.messages(managerHcm) >= 1);
        Await.until("the warehouse staff are told", Duration.ofSeconds(20), () -> fixtures.messages(staffHcm) >= 1);

        assertThat(fixtures.messages(managerHcm)).isEqualTo(1);
        assertThat(fixtures.titles(managerHcm).get(0)).contains("PRD-20261110-7001");
        assertThat(jdbc.queryForMap("SELECT category, link, source_type FROM notification.user_notification "
                + "WHERE recipient_id = ?", managerHcm)).containsEntry("category", "PRODUCTION_ORDER")
                .containsEntry("source_type", "PRODUCTION_ORDER").containsEntry("link", "/production/orders/" + order);
        for (UUID nobody : List.of(lockedManager, managerOther, planner, customer)) {
            assertThat(fixtures.messages(nobody)).as("messages of %s", nobody).isZero();
        }
    }

    @Test
    void aRedeliveredEventTellsNobodyTwice() {
        UUID order = UUID.randomUUID();
        publish(order, "PRD-20261110-7002");
        Await.until("told once", Duration.ofSeconds(20), () -> fixtures.messages(managerHcm) >= 1);
        publish(order, "PRD-20261110-7002");
        UUID next = UUID.randomUUID();
        publish(next, "PRD-20261110-7003");   // processed after the repeat: when it has arrived, the repeat has been seen
        Await.until("the next order's notice", Duration.ofSeconds(20), () -> fixtures.messages(managerHcm) >= 2);

        assertThat(fixtures.messages(managerHcm)).isEqualTo(2);
    }
}
