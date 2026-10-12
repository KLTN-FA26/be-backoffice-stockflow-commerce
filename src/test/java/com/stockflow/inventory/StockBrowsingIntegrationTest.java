package com.stockflow.inventory;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReserveStockCommand;
import com.stockflow.inventory.internal.domain.ReservationStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.service.StockBrowsing;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.WithCurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SCRUM-462: the holds and the stock of a warehouse, read page by page in any status, and only within
 * the caller's warehouses (BR-SEC-002).
 */
@IntegrationTest
@Import(PostgresContainer.class)
class StockBrowsingIntegrationTest {

    @Autowired StockBrowsing browsing;
    @Autowired InventoryService inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private String sku;
    private UUID order;

    private static final CurrentUser HCM_CLERK = clerkOf(DemoData.WAREHOUSE_HCM, "HCM");
    private static final CurrentUser OTHER_CLERK = clerkOf(UUID.randomUUID(), "ZZZ");

    /** As a login resolves a clerk: the warehouses they are assigned to, by id and by prefix. */
    private static CurrentUser clerkOf(UUID warehouse, String prefix) {
        return new CurrentUser(UUID.randomUUID(), "kho", Set.of(Role.WAREHOUSE_STAFF), Set.of(), DataScope.WAREHOUSE,
                Set.of(prefix), Set.of(warehouse));
    }

    @BeforeEach
    void stockAndAHold() {
        sku = "BROWSE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        order = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Browse fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature)"
                    + " values (?,?,?,'Browse fixture','')", Identifiers.newId(), product, sku);
            jdbc.update("update inventory.inventory_items set policy_configured=true where sku=?", sku);
            stock("HCM-A01-1-B", 10, "AVAILABLE");
            stock("HCM-A02-2-A", 4, "QUARANTINE");
            stock("HCM-A01-2-B", 0, "AVAILABLE");
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                         currency, placed_at, version, created_at)
                    VALUES (?, ?, ?, ?, 'PENDING_PAYMENT', 1000, 'VND', NOW(), 0, NOW())""",
                    order, "SO-20991231-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000)),
                    DemoData.CUSTOMER_ID, UUID.randomUUID());
        });
        inventory.reserve(new ReserveStockCommand(UUID.randomUUID(), new Sku(sku), 3, order));
    }

    private void stock(String location, int onHand, String status) {
        jdbc.update("""
                INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version, created_at)
                VALUES (?, ?, ?, ?, 0, ?, 0, NOW())""", Identifiers.newId(), sku, location, onHand, status);
    }

    private static ErrorCode codeOf(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    private StockBrowsing.ReservationQuery byOrder(List<ReservationStatus> statuses, String warehouse) {
        return new StockBrowsing.ReservationQuery(order, null, statuses, warehouse, 0, 20);
    }

    @Test
    @DisplayName("holds by order, status and warehouse; one hold by id; a clerk of another warehouse sees none")
    void reservations() {
        var page = browsing.reservations(byOrder(null, null));
        assertThat(page.items()).singleElement().satisfies(hold -> {
            assertThat(hold.sku()).isEqualTo(sku);
            assertThat(hold.locationCode()).isEqualTo("HCM-A01-1-B");
            assertThat(hold.quantity()).isEqualTo(3);
            assertThat(hold.status()).isEqualTo(ReservationStatus.HELD);
        });
        UUID holdId = page.items().get(0).reservationId();
        assertThat(browsing.reservations(byOrder(List.of(ReservationStatus.RELEASED), null)).items()).isEmpty();
        assertThat(browsing.reservations(byOrder(null, "hcm")).items()).hasSize(1);
        assertThat(browsing.reservations(new StockBrowsing.ReservationQuery(null, sku, List.of(ReservationStatus.HELD),
                null, 0, 20)).items()).hasSize(1);
        assertThat(browsing.reservation(holdId).orderId()).isEqualTo(order);
        assertThat(codeOf(catchThrowable(() -> browsing.reservation(UUID.randomUUID()))))
                .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);

        assertThat(WithCurrentUser.call(HCM_CLERK, HCM_CLERK.scope(),
                () -> browsing.reservations(byOrder(null, null))).items()).hasSize(1);
        assertThat(WithCurrentUser.call(OTHER_CLERK, OTHER_CLERK.scope(),
                () -> browsing.reservations(byOrder(null, null))).items()).isEmpty();
        assertThat(codeOf(catchThrowable(() -> WithCurrentUser.call(OTHER_CLERK, OTHER_CLERK.scope(),
                () -> browsing.reservation(holdId))))).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        assertThat(codeOf(catchThrowable(() -> WithCurrentUser.call(OTHER_CLERK, OTHER_CLERK.scope(),
                () -> browsing.reservations(byOrder(null, "HCM")))))).isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
    }

    private StockBrowsing.StockQuery query(String location, List<StockStatus> statuses, boolean includeEmpty) {
        return new StockBrowsing.StockQuery(location, sku, null, statuses, includeEmpty, 0, 50, null);
    }

    @Test
    @DisplayName("every stock row of a warehouse in any status, the empty ones on request, available only when sellable")
    void warehouseStock() {
        var rows = browsing.warehouseStock(DemoData.WAREHOUSE_HCM, query(null, null, false)).items();
        assertThat(rows).extracting(StockBrowsing.StockRow::locationCode)
                .containsExactly("HCM-A01-1-B", "HCM-A02-2-A");
        assertThat(rows).extracting(StockBrowsing.StockRow::available).containsExactly(7, 0);
        assertThat(rows).extracting(StockBrowsing.StockRow::reserved).containsExactly(3, 0);

        assertThat(browsing.warehouseStock(DemoData.WAREHOUSE_HCM, query(null, null, true)).items()).hasSize(3);
        assertThat(browsing.warehouseStock(DemoData.WAREHOUSE_HCM, query(null, List.of(StockStatus.QUARANTINE), false))
                .items()).extracting(StockBrowsing.StockRow::locationCode).containsExactly("HCM-A02-2-A");
        assertThat(browsing.warehouseStock(DemoData.WAREHOUSE_HCM, query("hcm-a01", null, true)).items())
                .extracting(StockBrowsing.StockRow::locationCode).containsExactly("HCM-A01-1-B", "HCM-A01-2-B");
        assertThat(codeOf(catchThrowable(() -> browsing.warehouseStock(DemoData.WAREHOUSE_HCM,
                query("HN-A01", null, false))))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(codeOf(catchThrowable(() -> browsing.warehouseStock(UUID.randomUUID(), query(null, null, false)))))
                .isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);

        assertThat(WithCurrentUser.call(HCM_CLERK, HCM_CLERK.scope(),
                () -> browsing.warehouseStock(DemoData.WAREHOUSE_HCM, query(null, null, false))).items()).hasSize(2);
        assertThat(codeOf(catchThrowable(() -> WithCurrentUser.call(OTHER_CLERK, OTHER_CLERK.scope(),
                () -> browsing.warehouseStock(DemoData.WAREHOUSE_HCM, query(null, null, false))))))
                .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
    }
}
