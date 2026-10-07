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
@org.springframework.test.context.TestPropertySource(properties = {"stockflow.idempotency.enabled=true",
    "PO_BUYER_COMPANY_NAME=Buyer test", "PO_BUYER_COMPANY_ADDRESS=Company test address",
    "PO_BUYER_CONTACT_NAME=Buyer contact", "PO_BUYER_PHONE=0901234567",
    "PO_BUYER_EMAIL=buyer@example.com", "PO_RECEIVING_ADDRESS=Test warehouse"})
class SupplierProcurementIntegrationTest {
    @Autowired SupplierService suppliers;
    @Autowired ProcurementService orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired com.stockflow.notification.api.NotificationService notifications;
    @Autowired org.springframework.modulith.events.IncompleteEventPublications publications;
    @Autowired com.stockflow.notification.internal.repository.PoRetryCursorRepository retryCursor;
    @Autowired PurchaseOrderNotificationRetry retryJob;
    @Autowired com.stockflow.common.events.PendingPublicationReader pendingReader;
    @MockitoBean NotificationSender sender;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.stockflow.product.api.ProductService products;

    private PurchaseOrderSummary failedOrder(boolean terminal) {
        var po = order(supplier().supplierId());
        RuntimeException failure = terminal ? new IllegalArgumentException("invalid destination")
                : new org.springframework.mail.MailSendException("offline");
        doThrow(failure).when(sender).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())));
        orders.approve(po.purchaseOrderId());
        orders.send(po.purchaseOrderId());
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notifications.purchaseOrderDeliveryStatuses(List.of(po.purchaseOrderId())))
                        .containsEntry(po.purchaseOrderId(), terminal ? "FAILED" : "RETRYING"));
        return orders.findById(po.purchaseOrderId()).orElseThrow();
    }

    @Test void cancellationFailureDoesNotUndoCancellationAndRetryUsesOriginalRecipient() {
        var supplier = supplier();
        var po = order(supplier.supplierId());
        orders.approve(po.purchaseOrderId()); orders.send(po.purchaseOrderId());
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notifications.purchaseOrderDeliveryStatuses(List.of(po.purchaseOrderId())))
                        .containsEntry(po.purchaseOrderId(), "DELIVERED"));
        transactions.executeWithoutResult(tx -> jdbc.update("update procurement.supplier set email='changed@example.com' where id=?", supplier.supplierId()));
        doThrow(new org.springframework.mail.MailSendException("offline")).when(sender)
                .sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId()) && e.cancellation()));
        assertThat(orders.cancel(po.purchaseOrderId(), "Customer cancelled").status()).isEqualTo("CANCELLED");
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notifications.purchaseOrderCancellationStatuses(List.of(po.purchaseOrderId())))
                        .containsEntry(po.purchaseOrderId(), "RETRYING"));
        assertThat(orders.findById(po.purchaseOrderId()).orElseThrow().status()).isEqualTo("CANCELLED");
        doNothing().when(sender).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId()) && e.cancellation()));
        publications.resubmitIncompletePublications(p -> p.getEvent() instanceof PurchaseOrderSent e
                && e.purchaseOrderId().equals(po.purchaseOrderId()) && e.cancellation());
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notifications.purchaseOrderCancellationStatuses(List.of(po.purchaseOrderId())))
                        .containsEntry(po.purchaseOrderId(), "DELIVERED"));
        verify(sender, times(2)).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())
                && e.cancellation() && e.recipient().equals(supplier.email())));
        assertThat(notifications.purchaseOrderDeliveries(po.purchaseOrderId(), 0, 20).items())
                .extracting(com.stockflow.notification.api.DeliveryAttemptSummary::templateCode)
                .contains("purchase-order.sent", "purchase-order.cancelled");
        expectCode(() -> orders.cancel(po.purchaseOrderId(), "Duplicate cancellation"), ErrorCode.INVALID_PURCHASE_ORDER_TRANSITION);
    }

    @Test void unsentCancellationDoesNotNotifySupplier() {
        var po = order(supplier().supplierId());
        orders.cancel(po.purchaseOrderId(), "Draft withdrawn");
        assertThat(notifications.purchaseOrderCancellationStatuses(List.of(po.purchaseOrderId()))).isEmpty();
        verify(sender, never()).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())));
    }

    @Test void missingDescriptionComesFromProductMasterAndRecoveryKeepsSnapshot() {
        var productId = UUID.randomUUID();
        String sku = "PO-" + UUID.randomUUID().toString().substring(0, 8);
        transactions.executeWithoutResult(tx -> jdbc.update("insert into product.product(id,code,name,status,created_at) values (?,?,?,'DRAFT',now())",
                productId, sku, "Original product name"));
        assertThat(products.nameForSku(sku.toUpperCase(java.util.Locale.ROOT))).contains("Original product name");
        var po = orders.createPurchaseOrder(new CreatePurchaseOrderCommand(supplier().supplierId(), "VND", null,
                List.of(new CreatePOLineCommand(sku, null, 1, BigDecimal.TEN))));
        assertThat(po.lines().getFirst().description()).isEqualTo("Original product name");
        transactions.executeWithoutResult(tx -> jdbc.update("update product.product set name='Changed before sending' where id=?", productId));
        doThrow(new IllegalArgumentException("terminal transport error")).when(sender)
                .sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())));
        orders.approve(po.purchaseOrderId()); orders.send(po.purchaseOrderId());
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notifications.purchaseOrderDeliveryStatuses(List.of(po.purchaseOrderId())))
                        .containsEntry(po.purchaseOrderId(), "FAILED"));
        transactions.executeWithoutResult(tx -> jdbc.update("update product.product set name='Changed product name' where id=?", productId));
        assertThat(products.nameForSku(sku.toUpperCase(java.util.Locale.ROOT))).contains("Changed product name");
        doNothing().when(sender).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())));
        orders.recoverDelivery(po.purchaseOrderId(), new RecoverPurchaseOrderDeliveryCommand("Provider reconciled", true, false));
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notifications.purchaseOrderDeliveryStatuses(List.of(po.purchaseOrderId())))
                        .containsEntry(po.purchaseOrderId(), "DELIVERED"));
        verify(sender, times(2)).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())
                && e.lines().getFirst().description().equals("Original product name")
                && e.buyer().receivingAddress().equals("Test warehouse")));
    }

    @Test void cancelledOrderCannotBeDispatchedByAnOldPublication() {
        var po = failedOrder(false);
        orders.cancel(po.purchaseOrderId(), "No longer needed");
        doNothing().when(sender).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())));
        publications.resubmitIncompletePublications(p -> p.getEvent() instanceof PurchaseOrderSent e && e.purchaseOrderId().equals(po.purchaseOrderId()));
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select count(*) from event_publication where completion_date is null and serialized_event like ?",
                        Long.class, "%" + po.purchaseOrderId() + "%")).isZero());
        verify(sender, times(1)).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId()) && !e.cancellation()));
        assertThat(notifications.purchaseOrderDeliveryStatuses(List.of(po.purchaseOrderId()))).containsEntry(po.purchaseOrderId(), "SUPPRESSED");
        assertThat(notifications.purchaseOrderCancellationStatuses(List.of(po.purchaseOrderId()))).containsEntry(po.purchaseOrderId(), "DELIVERED");
        expectCode(() -> orders.recoverDelivery(po.purchaseOrderId(), new RecoverPurchaseOrderDeliveryCommand("checked", true, false)),
                ErrorCode.INVALID_PURCHASE_ORDER_TRANSITION);
    }

    @Test void recordedSupplierResponseSuppressesOutstandingRetries() {
        var po = failedOrder(false);
        orders.recordSupplierConfirmation(po.purchaseOrderId(), new RecordSupplierConfirmationCommand("REJECTED", "PHONE", "Cannot supply"));
        publications.resubmitIncompletePublications(p -> p.getEvent() instanceof PurchaseOrderSent e && e.purchaseOrderId().equals(po.purchaseOrderId()));
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select count(*) from event_publication where completion_date is null and serialized_event like ?",
                        Long.class, "%" + po.purchaseOrderId() + "%")).isZero());
        verify(sender, times(1)).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())));
    }

    @Test void recoveryIsReconciledSingleFlightAndPreservesHistory() throws Exception {
        var po = failedOrder(true);
        expectCode(() -> orders.recoverDelivery(po.purchaseOrderId(), new RecoverPurchaseOrderDeliveryCommand("checked", false, false)), ErrorCode.CONFLICT);
        doNothing().when(sender).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> recover = () -> {
                start.await();
                try {
                    orders.recoverDelivery(po.purchaseOrderId(), new RecoverPurchaseOrderDeliveryCommand("Provider restored and checked", true, false));
                    return true;
                } catch (BusinessException conflict) {
                    assertThat(conflict.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    return false;
                }
            };
            var a = pool.submit(recover); var b = pool.submit(recover); start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notifications.purchaseOrderDeliveryStatuses(List.of(po.purchaseOrderId()))).containsEntry(po.purchaseOrderId(), "DELIVERED"));
        verify(sender, times(2)).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())));
        var stored = orders.findById(po.purchaseOrderId()).orElseThrow();
        assertThat(stored.sentAt()).isEqualTo(po.sentAt());
        assertThat(stored.expectedAt()).isEqualTo(po.expectedAt());
        assertThat(stored.supplierConfirmationStatus()).isEqualTo("PENDING");
        assertThat(notifications.purchaseOrderDeliveries(po.purchaseOrderId(), 0, 20).items())
                .extracting(com.stockflow.notification.api.DeliveryAttemptSummary::generation).containsExactly(1, 0);
        assertThat(orders.deliveryDecisions(po.purchaseOrderId(), 0, 20).items())
                .extracting(PurchaseOrderDeliveryDecision::generation).containsExactly(1, 0);
        assertThat(orders.deliveryDecisions(po.purchaseOrderId(), 0, 20).items().getFirst().reason()).isEqualTo("Provider restored and checked");
        expectCode(() -> orders.recoverDelivery(po.purchaseOrderId(), new RecoverPurchaseOrderDeliveryCommand("again", true, false)), ErrorCode.CONFLICT);
    }

    @Test void recoveryHttpReplayAndDecisionReadUseExistingApiContract() throws Exception {
        var po = failedOrder(true);
        doNothing().when(sender).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())));
        String key = UUID.randomUUID().toString();
        String path = "/api/v1/purchase-orders/" + po.purchaseOrderId() + "/delivery-recovery";
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                    .header("Idempotency-Key", key).contentType("application/json")
                    .content("{\"reason\":\"Provider checked\",\"reconciled\":true}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        }
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notifications.purchaseOrderDeliveryStatuses(List.of(po.purchaseOrderId()))).containsEntry(po.purchaseOrderId(), "DELIVERED"));
        verify(sender, times(2)).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/purchase-orders/" + po.purchaseOrderId() + "/delivery-decisions"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.totalElements").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[0].reason").value("Provider checked"));
    }

    @Test void cancellationWaitsForAlreadyStartedDeliveryAndCannotUndoIt() throws Exception {
        var po = order(supplier().supplierId());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test transport timed out");
            return null;
        }).when(sender).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())));
        orders.approve(po.purchaseOrderId()); orders.send(po.purchaseOrderId());
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var cancel = pool.submit(() -> orders.cancel(po.purchaseOrderId(), "Cancel after dispatch started"));
            try {
                assertThatThrownBy(() -> cancel.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally { release.countDown(); }
            assertThat(cancel.get(10, TimeUnit.SECONDS).status()).isEqualTo("CANCELLED");
        } finally { release.countDown(); }
        assertThat(notifications.purchaseOrderDeliveryStatuses(List.of(po.purchaseOrderId()))).containsEntry(po.purchaseOrderId(), "DELIVERED");
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notifications.purchaseOrderCancellationStatuses(List.of(po.purchaseOrderId()))).containsEntry(po.purchaseOrderId(), "DELIVERED"));
        verify(sender, times(1)).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId()) && !e.cancellation()));
        verify(sender, times(1)).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId()) && e.cancellation()));
    }

    @Test void overdueInitialSendIsAllowedWithWarningWithoutInventingNewDeliveryDate() throws Exception {
        var yesterday = com.stockflow.common.domain.BusinessCalendar.date(java.time.Instant.now()).minusDays(1);
        var po = orders.createPurchaseOrder(new CreatePurchaseOrderCommand(supplier().supplierId(), "VND", yesterday,
                List.of(new CreatePOLineCommand("CHAIR-01", "Chair", 1, BigDecimal.TEN))));
        orders.approve(po.purchaseOrderId());
        var tomorrow = yesterday.plusDays(2);
        expectCode(() -> orders.send(po.purchaseOrderId(), new SendPurchaseOrderCommand(tomorrow, " ")), ErrorCode.PO_REASON_REQUIRED);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/v1/purchase-orders/" + po.purchaseOrderId() + "/sending").header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.status").value("SENT"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.warnings[0]").value("DELIVERY_DATE_IN_PAST"));
        assertThat(orders.findById(po.purchaseOrderId()).orElseThrow().expectedAt()).isEqualTo(yesterday);
        var decision = orders.deliveryDecisions(po.purchaseOrderId(), 0, 20).items().getFirst();
        assertThat(decision.previousExpectedAt()).isEqualTo(yesterday);
        assertThat(decision.expectedAt()).isEqualTo(yesterday);
        assertThat(decision.actor()).isNotBlank();
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                verify(sender).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId()) && e.expectedAt().equals(yesterday))));
    }

    @Test void invalidStatusAndConditionalFieldsAreClientErrors() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/suppliers").param("status", "FOO"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/suppliers")
                .contentType("application/json").content("""
                        {"code":"BAD-CONTACT","name":"Supplier","status":"ACTIVE","communicationChannel":"EMAIL"}
                        """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.fieldErrors[0].field").value("email"));
    }

    @Test void createDefaultsButPutCannotSilentlyResetTerms() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/suppliers")
                .contentType("application/json").content("""
                        {"code":"CREATE-DEFAULTS","name":"Supplier","status":"ACTIVE","communicationChannel":"EMAIL","email":"supplier@example.com"}
                        """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.paymentTermDays").value(30))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.leadTimeDays").value(7));
        var s = supplier();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/suppliers/" + s.supplierId())
                .contentType("application/json").content("""
                        {"code":"%s","name":"Supplier","status":"ACTIVE","communicationChannel":"EMAIL","email":"supplier@example.com"}
                        """.formatted(s.code())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        assertThat(suppliers.findById(s.supplierId()).orElseThrow().paymentTermDays()).isEqualTo(45);
    }

    @Test void literalSearchAndApiPreflight() {
        supplier();
        assertThat(suppliers.list(0, 20, "%", null, null).totalElements()).isZero();
        assertThat(suppliers.list(0, 20, "_", null, null).totalElements()).isZero();
        assertThatThrownBy(() -> suppliers.create(new SaveSupplierCommand("API-DENIED", "Supplier", null,
                null, null, null, "ACTIVE", 30, 7, "API", "https://unapproved.example.com/po")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.SUPPLIER_DELIVERY_CONTACT_INVALID);
    }

    @Test void permanentDeliveryFailureStopsAndIsVisibleOnPoDetail() throws Exception {
        var po = order(supplier().supplierId());
        doThrow(new IllegalArgumentException("invalid destination configuration"))
                .when(sender).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())));
        orders.approve(po.purchaseOrderId()); orders.send(po.purchaseOrderId());
        await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(notifications.purchaseOrderDeliveryStatuses(List.of(po.purchaseOrderId())))
                        .containsEntry(po.purchaseOrderId(), "FAILED"));
        publications.resubmitIncompletePublications(p -> p.getEvent() instanceof PurchaseOrderSent e && e.purchaseOrderId().equals(po.purchaseOrderId()));
        verify(sender, times(1)).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/purchase-orders/" + po.purchaseOrderId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.deliveryStatus").value("FAILED"));
    }

    @Test void transientDeliveryFailureStopsAfterFiveAttempts() {
        var po = order(supplier().supplierId());
        doThrow(new IllegalStateException("mail offline")).when(sender)
                .sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())));
        orders.approve(po.purchaseOrderId()); orders.send(po.purchaseOrderId());
        for (int i = 1; i <= 5; i++) {
            int count = i;
            await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() ->
                    assertThat(notifications.purchaseOrderDeliveries(po.purchaseOrderId(), 0, 20).totalElements()).isEqualTo(count));
            if (i < 5) publications.resubmitIncompletePublications(p -> p.getEvent() instanceof PurchaseOrderSent e && e.purchaseOrderId().equals(po.purchaseOrderId()));
        }
        assertThat(notifications.purchaseOrderDeliveryStatuses(List.of(po.purchaseOrderId()))).containsEntry(po.purchaseOrderId(), "FAILED");
    }

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

    @Test void missingDescriptionAndInvalidAmountsCannotCreateStuckOrders() {
        var supplierId = supplier().supplierId();
        expectCode(() -> orders.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId, "VND", null,
                List.of(new CreatePOLineCommand("UNKNOWN-SKU", null, 1, BigDecimal.TEN)))),
                ErrorCode.PO_LINE_DESCRIPTION_REQUIRED);
        for (var line : List.of(
                new CreatePOLineCommand("CHAIR-01", "Chair", 1, new BigDecimal("1000.5")),
                new CreatePOLineCommand("CHAIR-01", "Chair", 1_000_001, BigDecimal.ONE),
                new CreatePOLineCommand("CHAIR-01", "Chair", 2, new BigDecimal("9999999999999999")))) {
            expectCode(() -> orders.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId, "VND", null,
                    List.of(line))), ErrorCode.VALIDATION_FAILED);
        }
        assertThat(jdbc.queryForObject("select count(*) from procurement.purchase_order where supplier_id=?",
                Long.class, supplierId)).isZero();
    }

    @Test void httpValidationExposesCurrencyAndLineFields() throws Exception {
        var supplierId = supplier().supplierId();
        for (String[] example : List.of(
                new String[]{"XYZ", "CHAIR-01", "Chair", "1", "10", "currency"},
                new String[]{"VND", "bad_sku", "Chair", "1", "10", "lines[0].sku"},
                new String[]{"VND", "CHAIR-01", "x".repeat(301), "1", "10", "lines[0].description"},
                new String[]{"VND", "CHAIR-01", "Chair", "1", "1000.5", "lines[0].unitPrice"},
                new String[]{"USD", "CHAIR-01", "Chair", "1", "10.555", "lines[0].unitPrice"},
                new String[]{"USD", "CHAIR-01", "Chair", "2147483647", "10", "lines[0].quantityOrdered"},
                new String[]{"USD", "CHAIR-01", "Chair", "2", "9999999999999999", "lines"})) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/purchase-orders")
                    .contentType("application/json").content("""
                            {"supplierId":"%s","currency":"%s","lines":[{"sku":"%s","description":"%s",
                              "quantityOrdered":%s,"unitPrice":%s}]}
                            """.formatted(supplierId, example[0], example[1], example[2], example[3], example[4])))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.fieldErrors[*].field",
                            org.hamcrest.Matchers.hasItem(example[5])));
        }
    }

    @Test void purchaseOrderReadsContainSupplierIdentityWithoutSupplierEndpoint() throws Exception {
        var supplier = supplier();
        var po = order(supplier.supplierId());
        assertThat(po.supplierCode()).isEqualTo(supplier.code());
        assertThat(po.supplierName()).isEqualTo(supplier.name());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/purchase-orders/" + po.purchaseOrderId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.supplierCode").value(supplier.code()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.supplierName").value(supplier.name()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/purchase-orders")
                .param("supplierId", supplier.supplierId().toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items[0].supplierName").value(supplier.name()));
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

    @Test void failedDeliveryDoesNotRollbackSentAndLeavesDurableRetryEvidence() throws Exception {
        var po = order(supplier().supplierId());
        doThrow(new org.springframework.mail.MailSendException("offline"))
                .when(sender).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())));
        orders.approve(po.purchaseOrderId());
        orders.send(po.purchaseOrderId());
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select count(*) from notification.delivery_log where status='FAILED' and error='MAIL_SEND_FAILED' and operation_reference = ?",
                    Long.class, "purchase-order:" + po.purchaseOrderId())).isEqualTo(1);
        });
        assertThat(orders.findById(po.purchaseOrderId()).orElseThrow().status()).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("select count(*) from event_publication where completion_date is null and serialized_event like ?",
                Long.class, "%" + po.purchaseOrderId() + "%")).isGreaterThanOrEqualTo(1);
        assertThat(notifications.purchaseOrderDeliveries(po.purchaseOrderId(), 0, 20).items())
                .singleElement().satisfies(attempt -> {
                    assertThat(attempt.status()).isEqualTo("FAILED");
                    assertThat(attempt.sentAt()).isNull();
                    assertThat(attempt.failure()).isEqualTo("MAIL_SEND_FAILED");
                    assertThat(attempt.attemptNumber()).isEqualTo(1);
                });
        doNothing().when(sender).sendPurchaseOrder(argThat(e -> e.purchaseOrderId().equals(po.purchaseOrderId())));
        transactions.executeWithoutResult(tx -> {
            jdbc.update("update event_publication set publication_date=now()-interval '10 minutes' where serialized_event like ?",
                    "%" + po.purchaseOrderId() + "%");
            // The startup sweep owns a 30-second minimum lease. Advance only the test DB lease,
            // otherwise a direct call is correctly skipped by ShedLock rather than testing retry.
            jdbc.update("update platform.shedlock set lock_until=timestamp '2000-01-01 00:00:00' where name='notification.retryPurchaseOrders'");
        });
        var targetMethod = PurchaseOrderNotificationListener.class.getMethod("on", PurchaseOrderSent.class);
        var targetId = new org.springframework.transaction.event.TransactionalApplicationListenerMethodAdapter(
                null, PurchaseOrderNotificationListener.class, targetMethod).getListenerId();
        assertThat(pendingReader.page(PurchaseOrderSent.class.getName(), targetId,
                java.time.Instant.now().minusSeconds(300), null, 50))
                .as("retry candidates; registry rows: %s", jdbc.queryForList("select listener_id,event_type,publication_date from event_publication where completion_date is null"))
                .anyMatch(p -> p.serializedEvent().contains(po.purchaseOrderId().toString()));
        retryJob.retry();
        assertThat(retryCursor.load()).as("retry cursor; lock: %s", jdbc.queryForList("select * from platform.shedlock where name='notification.retryPurchaseOrders'"))
                .isNotNull();
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notifications.purchaseOrderDeliveries(po.purchaseOrderId(), 0, 20).items())
                        .extracting(com.stockflow.notification.api.DeliveryAttemptSummary::status)
                        .containsExactly("SENT", "FAILED"));
        assertThat(notifications.purchaseOrderDeliveries(po.purchaseOrderId(), 0, 20).items())
                .extracting(com.stockflow.notification.api.DeliveryAttemptSummary::attemptNumber)
                .containsExactly(2, 1);
        await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select count(*) from event_publication where completion_date is null and serialized_event like ?",
                        Long.class, "%" + po.purchaseOrderId() + "%")).isZero());
    }

    @Test void boundedReaderFiltersAndPaginatesWithoutLoadingOtherEvents() {
        transactions.executeWithoutResult(tx -> {
            for (int i = 1; i <= 120; i++) jdbc.update("""
                    insert into event_publication(id,listener_id,event_type,serialized_event,publication_date)
                    values (?, 'test-listener', 'test-event', '{}', now()-interval '10 minutes')
                    """, new UUID(2, i));
            var first = pendingReader.page("test-event", "test-listener", java.time.Instant.now().minusSeconds(300), null, 50);
            var second = pendingReader.page("test-event", "test-listener", java.time.Instant.now().minusSeconds(300), first.getLast().id(), 50);
            var third = pendingReader.page("test-event", "test-listener", java.time.Instant.now().minusSeconds(300), second.getLast().id(), 50);
            assertThat(first).hasSize(50);
            assertThat(second).hasSize(50).doesNotContainAnyElementsOf(first);
            assertThat(third).hasSize(20);
            assertThat(pendingReader.page("test-event", "another-listener", java.time.Instant.now(), null, 50)).isEmpty();
            tx.setRollbackOnly();
        });
    }

    @Test void deliveryHistoryLivesUnderProcurement() throws Exception {
        var po = order(supplier().supplierId());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/purchase-orders/" + po.purchaseOrderId() + "/deliveries"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/purchase-orders/" + UUID.randomUUID() + "/deliveries"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
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
