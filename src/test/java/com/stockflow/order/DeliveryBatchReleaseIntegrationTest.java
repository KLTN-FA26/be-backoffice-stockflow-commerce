package com.stockflow.order;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.storage.UploadSlotRequest;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.domain.WindowChangeReason;
import com.stockflow.order.internal.service.DeliveryBatchService;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.support.ChatRows;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 4B-8 (kltn-docs 17 §4.2 step 3, §4.5, BR-03, BR-29): an order of a contract with two delivery batches is released batch
 * by batch - the next one only - at its shipping warehouse, and the schedule says where each batch stands. The batch
 * after the first is refused while the first is still in the warehouse ({@code DELIVERY_BATCH_NOT_NEXT}), whether it is
 * named or not; the first goes to READY_TO_FULFILL with only its own holds allocated and its release recorded.
 */
@IntegrationTest
@Import({PostgresContainer.class, com.stockflow.support.RedisContainer.class})
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class DeliveryBatchReleaseIntegrationTest {

    @Autowired OrderReleases releases;
    @Autowired DeliveryBatchService batches;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    @Test
    void theNextBatchOnlyIsReleased() {
        ConfirmedOrders orders = new ConfirmedOrders(context);
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        UUID by = tx.execute(s -> ReferenceRows.user(entityManager));
        CustomerSkuRows.Issued sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 2);
        CustomerSkuRows.stock(jdbc, tx, sku.sku(), 8);
        UUID orderId = orders.pay(orders.signedInBatches(customer, 2, ConfirmedOrders.Line.cartons(sku, 4, 6_000)))
                .orderId();
        orders.ship(orderId, DemoData.WAREHOUSE_HCM);

        DeliveryBatchService.Schedule before = schedule(orderId);
        assertThat(before.batches()).extracting(DeliveryBatchService.BatchView::seq).containsExactly(1, 2);
        assertThat(before.batches()).extracting(b -> b.lines().getFirst().cartons()).containsExactly(2, 2);
        assertThat(before.batches()).allSatisfy(b -> assertThat(b.releasedAt()).isNull());
        UUID second = before.batches().get(1).batchId();

        Throwable early = catchThrowable(() -> as(() -> releases.release(orderId, DemoData.WAREHOUSE_HCM, second, by)));
        assertThat(((BusinessException) early).errorCode()).as("batch 2 named first")
                .isEqualTo(ErrorCode.DELIVERY_BATCH_NOT_NEXT);

        OrderSummary released = as(() -> releases.release(orderId, DemoData.WAREHOUSE_HCM, by));
        assertThat(released.status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        DeliveryBatchService.Schedule after = schedule(orderId);
        assertThat(after.batches().get(0).releasedAt()).as("batch 1's release is recorded").isNotNull();
        assertThat(after.batches().get(1).releasedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.delivery_batch_release WHERE order_id = ?",
                Long.class, orderId)).isEqualTo(1L);

        Throwable again = catchThrowable(() -> as(() -> releases.release(orderId, DemoData.WAREHOUSE_HCM, by)));
        assertThat(((BusinessException) again).errorCode()).as("batch 2 while batch 1 is still in the warehouse")
                .isIn(ErrorCode.DELIVERY_BATCH_NOT_NEXT, ErrorCode.ORDER_INVALID_TRANSITION);
    }

    /**
     * BR-28 and review fixes R1 / R4: Sales moves batch 2's window with the customer's consent in chat - only a message
     * the customer sent in their own conversation counts; never into the past, never ahead of the batch before it,
     * with exactly one kind of evidence. The schedule shows the contract's window and the one in force with its history,
     * to the staff and to the customer who owns the order, to nobody else's customer. The evidence file's upload slot
     * reads the order under its lock in a read-write transaction (R1): it gets as far as the storage, which in the test
     * profile signs no browser upload (STORAGE_ERROR), instead of failing on {@code SELECT ... FOR UPDATE} in a read-only
     * transaction.
     */
    @Test
    void aWindowMovesWithTheCustomersConsent() {
        ConfirmedOrders orders = new ConfirmedOrders(context);
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        UUID stranger = tx.execute(s -> ReferenceRows.customer(entityManager));
        CustomerSkuRows.Issued sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 2);
        UUID orderId = orders.pay(orders.signedInBatches(customer, 2, ConfirmedOrders.Line.cartons(sku, 4, 6_000)))
                .orderId();
        UUID elsewhere = orders.pay(orders.signed(customer, ConfirmedOrders.Line.cartons(sku, 1, 6_000))).orderId();
        CurrentUser sales = orders.sales();
        LocalDate today = LocalDate.now(ConfirmedOrders.VIETNAM);
        DeliveryBatchService.BatchView second = schedule(orderId).batches().get(1);
        UUID first = schedule(orderId).batches().get(0).batchId();
        assertThat(second.windowStart()).isEqualTo(today.plusDays(14)).isEqualTo(second.contractWindowStart());

        UUID agents = ChatRows.message(jdbc, tx, customer, orderId, "AGENT");
        UUID strangers = ChatRows.message(jdbc, tx, stranger, null, "CUSTOMER");
        UUID otherOrder = ChatRows.message(jdbc, tx, customer, elsewhere, "CUSTOMER");
        UUID consent = ChatRows.message(jdbc, tx, customer, orderId, "CUSTOMER");
        java.util.function.BiFunction<LocalDate, UUID, DeliveryBatchService.WindowChange> later = (start, message) ->
                new DeliveryBatchService.WindowChange(start, start.plusDays(2), WindowChangeReason.SELLER_DELAY,
                        "The producer is a week late", null, message);

        assertThat(refused(sales, orderId, second.batchId(), later.apply(today.plusDays(21), agents)))
                .as("R4: an agent's message is no consent").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refused(sales, orderId, second.batchId(), later.apply(today.plusDays(21), strangers)))
                .as("R4: another customer's conversation").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refused(sales, orderId, second.batchId(), later.apply(today.plusDays(21), otherOrder)))
                .as("R4: a conversation about another order").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refused(sales, orderId, second.batchId(), later.apply(today.minusDays(1), consent)))
                .as("not into the past").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refused(sales, orderId, first, later.apply(today.plusDays(20), consent)))
                .as("batch 1 would start after batch 2").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refused(sales, orderId, second.batchId(), new DeliveryBatchService.WindowChange(today.plusDays(21),
                today.plusDays(23), WindowChangeReason.SELLER_DELAY, "late", null, null)))
                .as("no evidence").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refused(sales, orderId, second.batchId(), new DeliveryBatchService.WindowChange(today.plusDays(21),
                today.plusDays(23), WindowChangeReason.SELLER_DELAY, "late", UUID.randomUUID(), consent)))
                .as("a file and a message at once").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(refused(sales, orderId, UUID.randomUUID(), later.apply(today.plusDays(21), consent)))
                .isEqualTo(ErrorCode.DELIVERY_BATCH_NOT_FOUND);
        Throwable noFile = catchThrowable(() -> WithCurrentUser.call(sales, DataScope.ALL, () -> batches.changeWindow(
                sales, orderId, second.batchId(), new DeliveryBatchService.WindowChange(today.plusDays(21),
                        today.plusDays(23), WindowChangeReason.CUSTOMER_REQUEST, "asked by e-mail", UUID.randomUUID(),
                        null))));
        assertThat(noFile).as("a file nobody uploaded").isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.delivery_batch_window_change WHERE order_id = ?",
                Long.class, orderId)).as("nothing recorded by a refusal").isZero();

        Throwable slot = catchThrowable(() -> WithCurrentUser.call(sales, DataScope.ALL, () ->
                batches.beginEvidenceUpload(sales, orderId, second.batchId(), new UploadSlotRequest("consent.pdf",
                        "application/pdf", 1024, "a".repeat(64)))));
        assertThat(slot).as("R1: as far as the storage").isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) slot).errorCode()).isEqualTo(ErrorCode.STORAGE_ERROR);

        DeliveryBatchService.Schedule moved = WithCurrentUser.call(sales, DataScope.ALL, () -> batches.changeWindow(
                sales, orderId, second.batchId(), later.apply(today.plusDays(21), consent)));
        DeliveryBatchService.BatchView now = moved.batches().get(1);
        assertThat(now.contractWindowStart()).as("the signed contract stays as signed").isEqualTo(today.plusDays(14));
        assertThat(now.windowStart()).isEqualTo(today.plusDays(21));
        assertThat(now.windowEnd()).isEqualTo(today.plusDays(23));
        assertThat(now.windowChanges()).singleElement().satisfies(c -> {
            assertThat(c.oldStart()).isEqualTo(today.plusDays(14));
            assertThat(c.newStart()).isEqualTo(today.plusDays(21));
            assertThat(c.reason()).isEqualTo(WindowChangeReason.SELLER_DELAY);
            assertThat(c.evidenceMessageId()).isEqualTo(consent);
            assertThat(c.evidenceFileName()).isNull();
            assertThat(c.changedBy()).isEqualTo(sales.userId());
        });
        assertThat(now.state()).isEqualTo(DeliveryBatchService.BatchState.AWAITING_STOCK);
        assertThat(now.late()).isFalse();

        DeliveryBatchService.Schedule theirs = as(() -> batches.scheduleOfCustomer(customer, orderId));
        assertThat(theirs.batches().get(1).windowStart()).as("the customer sees the window in force")
                .isEqualTo(today.plusDays(21));
        assertThat(theirs.contractNumber()).isNotBlank();
        Throwable notTheirs = catchThrowable(() -> as(() -> batches.scheduleOfCustomer(stranger, orderId)));
        assertThat(((BusinessException) notTheirs).errorCode()).as("another customer's order")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    private ErrorCode refused(CurrentUser sales, UUID orderId, UUID batchId, DeliveryBatchService.WindowChange change) {
        Throwable thrown = catchThrowable(() -> WithCurrentUser.call(sales, DataScope.ALL,
                () -> batches.changeWindow(sales, orderId, batchId, change)));
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private DeliveryBatchService.Schedule schedule(UUID orderId) {
        return as(() -> batches.schedule(orderId));
    }

    private <T> T as(java.util.function.Supplier<T> call) {
        return WithCurrentUser.call(TestUsers.admin(), DataScope.ALL, call);
    }
}
