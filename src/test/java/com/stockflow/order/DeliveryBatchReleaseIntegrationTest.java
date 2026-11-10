package com.stockflow.order;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.DataScope;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.service.DeliveryBatchService;
import com.stockflow.order.internal.service.OrderReleases;
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
@Import(PostgresContainer.class)
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

    private DeliveryBatchService.Schedule schedule(UUID orderId) {
        return as(() -> batches.schedule(orderId));
    }

    private <T> T as(java.util.function.Supplier<T> call) {
        return WithCurrentUser.call(TestUsers.admin(), DataScope.ALL, call);
    }
}
