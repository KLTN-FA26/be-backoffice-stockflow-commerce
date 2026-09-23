package com.stockflow.notification.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.procurement.api.*;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

/** Real commits, row locks and AFTER_COMMIT delivery on PostgreSQL, with only the transport mocked. */
@IntegrationTest
@Import(PostgresContainer.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = "stockflow.idempotency.enabled=true")
class SupplierProcurementIntegrationTest {
    @Autowired SupplierService suppliers;
    @Autowired ProcurementService orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired com.stockflow.notification.api.NotificationService notifications;
    @Autowired org.springframework.modulith.events.IncompleteEventPublications publications;
    @Autowired com.stockflow.notification.internal.repository.PoRetryCursorRepository retryCursor;
    @MockitoBean NotificationSender sender;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;

    @Test void retryCursorMigrationSupportsDurablePositionUpdates() {
        UUID previous = retryCursor.load();
        try {
            UUID position = UUID.randomUUID();
            retryCursor.save(position);
            // Separate proxy calls/transactions must see the committed position.
            assertThat(retryCursor.load()).isEqualTo(position);
        } finally {
            retryCursor.save(previous);
        }
    }

    private SaveSupplierCommand command(String code, String status, int terms, int lead) {
        return new SaveSupplierCommand(code, "Furniture supplier", "Contact", "supplier@example.com",
                "+84901234567", null, status, terms, lead, "EMAIL", null);
    }
    private SupplierSummary supplier() {
        return suppliers.create(command("S-" + UUID.randomUUID(), "ACTIVE", 45, 12));
    }
    private PurchaseOrderSummary order(UUID supplier) {
        return orders.createPurchaseOrder(new CreatePurchaseOrderCommand(supplier, "VND", null,
                List.of(new CreatePOLineCommand("CHAIR-01", "Oak chair", 2, new BigDecimal("100000")))));
    }
    private void expectCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Test void emptySupplierHasUnknownRatesAndDefaultsAreSnapshotted() {
        var s = supplier();
        var metric = suppliers.performance(s.supplierId());
        assertThat(metric.totalPurchaseOrders()).isZero();
        assertThat(metric.onTimeDeliveryRate()).isNull();
        assertThat(metric.qualityAcceptanceRate()).isNull();
        var po = order(s.supplierId());
        suppliers.update(s.supplierId(), command(s.code(), "ACTIVE", 10, 3));
        var stored = orders.findById(po.purchaseOrderId()).orElseThrow();
        assertThat(stored.paymentTermDays()).isEqualTo(45);
        assertThat(stored.leadTimeDays()).isEqualTo(12);
        assertThat(stored.expectedAt()).isNotNull();
    }

    @Test void deactivateIsBlockedUntilOpenOrderIsCancelledAndThenPreventsNewOrders() {
        var s = supplier();
        var po = order(s.supplierId());
        expectCode(() -> suppliers.deactivate(s.supplierId()), ErrorCode.SUPPLIER_HAS_OPEN_PURCHASE_ORDERS);
        orders.cancel(po.purchaseOrderId(), "No longer required");
        suppliers.deactivate(s.supplierId());
        expectCode(() -> order(s.supplierId()), ErrorCode.SUPPLIER_INACTIVE);
    }

    @Test void duplicateCodeIsAConflict() {
        var s = supplier();
        expectCode(() -> suppliers.create(command(s.code().toLowerCase(), "ACTIVE", 30, 7)),
                ErrorCode.SUPPLIER_CODE_ALREADY_EXISTS);
    }

    @Test void draftCannotBeSentAndConcurrentSendingProducesOnlyOneNotification() throws Exception {
        var po = order(supplier().supplierId());
        expectCode(() -> orders.send(po.purchaseOrderId()), ErrorCode.INVALID_PURCHASE_ORDER_TRANSITION);
        orders.approve(po.purchaseOrderId());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> send = () -> {
                start.await();
                try { orders.send(po.purchaseOrderId()); return true; }
                catch (BusinessException conflict) {
                    assertThat(conflict.errorCode()).isEqualTo(ErrorCode.INVALID_PURCHASE_ORDER_TRANSITION);
                    return false;
                }
            };
            var first = pool.submit(send);
            var second = pool.submit(send);
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                verify(sender, times(1)).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())
                        && e.lines().size() == 1 && e.lines().getFirst().quantity() == 2)));
        assertThat(orders.findById(po.purchaseOrderId()).orElseThrow().supplierConfirmationStatus()).isEqualTo("PENDING");
    }

    @Test void failedDeliveryDoesNotRollbackSentAndLeavesDurableRetryEvidence() {
        var po = order(supplier().supplierId());
        doThrow(new org.springframework.mail.MailSendException("offline"))
                .when(sender).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())));
        orders.approve(po.purchaseOrderId());
        orders.send(po.purchaseOrderId());
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select count(*) from notification.delivery_log where status='FAILED' and error like ?",
                    Long.class, "purchase-order:" + po.purchaseOrderId() + "%")).isEqualTo(1);
        });
        assertThat(orders.findById(po.purchaseOrderId()).orElseThrow().status()).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("select count(*) from event_publication where completion_date is null and serialized_event like ?",
                Long.class, "%" + po.purchaseOrderId() + "%")).isGreaterThanOrEqualTo(1);
        assertThat(notifications.purchaseOrderDeliveries(po.purchaseOrderId(), 0, 20).items())
                .singleElement().satisfies(attempt -> {
                    assertThat(attempt.status()).isEqualTo("FAILED");
                    assertThat(attempt.sentAt()).isNull();
                });
        doNothing().when(sender).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())));
        publications.resubmitIncompletePublications(p -> p.getEvent() instanceof PurchaseOrderSent event
                && event.purchaseOrderId().equals(po.purchaseOrderId()));
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notifications.purchaseOrderDeliveries(po.purchaseOrderId(), 0, 20).items())
                        .extracting(com.stockflow.notification.api.DeliveryAttemptSummary::status)
                        .containsExactly("SENT", "FAILED"));
    }

    @Test void rolledBackSendDoesNotNotifySupplier() {
        var po = order(supplier().supplierId());
        orders.approve(po.purchaseOrderId());
        transactions.executeWithoutResult(tx -> { orders.send(po.purchaseOrderId()); tx.setRollbackOnly(); });
        assertThat(orders.findById(po.purchaseOrderId()).orElseThrow().status()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select count(*) from event_publication where serialized_event like ?",
                Long.class, "%" + po.purchaseOrderId() + "%")).isZero();
        verify(sender, never()).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())));
    }

    @Test void sameHttpIdempotencyKeyReplaysButNewSendingRequestConflicts() throws Exception {
        var po = order(supplier().supplierId());
        orders.approve(po.purchaseOrderId());
        String path = "/api/v1/purchase-orders/" + po.purchaseOrderId() + "/sending";
        String key = UUID.randomUUID().toString();
        var first = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Idempotency-Key", key)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Idempotency-Key", key))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(first));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                verify(sender, times(1)).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId()))));
    }

    @Test void rejectionPreventsReceiptAndChangedReplayDoesNotOverwriteEvidence() {
        var po = order(supplier().supplierId());
        orders.approve(po.purchaseOrderId()); orders.send(po.purchaseOrderId());
        var response = new RecordSupplierConfirmationCommand("REJECTED", "R-1", "Out of stock");
        orders.recordSupplierConfirmation(po.purchaseOrderId(), response);
        orders.recordSupplierConfirmation(po.purchaseOrderId(), response);
        expectCode(() -> orders.recordSupplierConfirmation(po.purchaseOrderId(),
                new RecordSupplierConfirmationCommand("REJECTED", "R-2", "Different")), ErrorCode.INVALID_PURCHASE_ORDER_TRANSITION);
        expectCode(() -> orders.receiveGoods(po.purchaseOrderId(), new ReceiveGoodsCommand(List.of(
                new ReceiveGoodsLineCommand(po.lines().getFirst().lineId(), 1)))), ErrorCode.INVALID_PURCHASE_ORDER_TRANSITION);
    }

    @Test void deactivationWaitsForConcurrentOrderCreation() throws Exception {
        var s = supplier();
        var inserted = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var creation = pool.submit(() -> transactions.execute(tx -> {
                var po = order(s.supplierId());
                inserted.countDown();
                try { if (!commit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return po;
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            var deactivation = pool.submit(() -> {
                try { suppliers.deactivate(s.supplierId()); return "unexpected success"; }
                catch (BusinessException e) { return e.errorCode().name(); }
            });
            try {
                assertThatThrownBy(() -> deactivation.get(300, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally { commit.countDown(); }
            assertThat(creation.get(10, TimeUnit.SECONDS)).isNotNull();
            assertThat(deactivation.get(10, TimeUnit.SECONDS)).isEqualTo("SUPPLIER_HAS_OPEN_PURCHASE_ORDERS");
        }
    }

    @Test void racingTaxCodesMapUniqueConstraintToBusinessConflict() throws Exception {
        String tax = "TAX" + UUID.randomUUID().toString().replace("-", "").substring(0, 20) + "1";
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<String> create = () -> {
                start.await();
                try {
                    suppliers.create(new SaveSupplierCommand("SUP-" + UUID.randomUUID(), "Supplier", null,
                            "s@example.com", null, tax, "ACTIVE", 30, 7, "EMAIL", null));
                    return "created";
                } catch (BusinessException e) { return e.errorCode().name(); }
            };
            var a = pool.submit(create); var b = pool.submit(create); start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("created", "SUPPLIER_TAX_CODE_ALREADY_EXISTS");
        }
    }

    @Test void completionEvidenceSurvivesLaterUpdatesAndCloseShortIsNotFullDelivery() {
        var s = supplier();
        var po = order(s.supplierId());
        orders.approve(po.purchaseOrderId()); orders.send(po.purchaseOrderId());
        orders.receiveGoods(po.purchaseOrderId(), new ReceiveGoodsCommand(List.of(
                new ReceiveGoodsLineCommand(po.lines().getFirst().lineId(), 2))));
        var before = jdbc.queryForObject("select receipt_completed_at from procurement.purchase_order where id=?",
                java.sql.Timestamp.class, po.purchaseOrderId());
        assertThat(before).isNotNull();
        orders.recordSupplierConfirmation(po.purchaseOrderId(), new RecordSupplierConfirmationCommand("CONFIRMED", "ACK", null));
        assertThat(jdbc.queryForObject("select receipt_completed_at from procurement.purchase_order where id=?",
                java.sql.Timestamp.class, po.purchaseOrderId())).isEqualTo(before);
        var shortPo = order(s.supplierId());
        orders.approve(shortPo.purchaseOrderId()); orders.send(shortPo.purchaseOrderId());
        orders.receiveGoods(shortPo.purchaseOrderId(), new ReceiveGoodsCommand(List.of(
                new ReceiveGoodsLineCommand(shortPo.lines().getFirst().lineId(), 1))));
        orders.closeShort(shortPo.purchaseOrderId(), "Supplier unable to supply the remainder");
        var metrics = suppliers.performance(s.supplierId());
        assertThat(metrics.totalPurchaseOrders()).isEqualTo(2);
        assertThat(metrics.fulfilledPurchaseOrders()).isEqualTo(1);
        assertThat(metrics.onTimeOrders()).isEqualTo(1);
        assertThat(metrics.qualityAcceptanceRate()).isNull();
    }
}
