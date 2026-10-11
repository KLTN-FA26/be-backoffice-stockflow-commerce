package com.stockflow.procurement;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.procurement.api.CreatePOLineCommand;
import com.stockflow.procurement.api.CreatePurchaseOrderCommand;
import com.stockflow.procurement.api.ListPurchaseOrdersQuery;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.PurchaseOrderSummary;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * SCRUM-459, BR-SEC-002: warehouse staff read purchase orders to prepare receiving (kltn-docs 02
 * §2), but only those of the warehouses they are assigned to. Planners and buyers are not limited.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class PurchaseOrderWarehouseScopeIntegrationTest {

    @Autowired ProcurementService procurement;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private static CurrentUser clerkOf(UUID... warehouseIds) {
        return new CurrentUser(UUID.randomUUID(), "kho", Set.of(Role.WAREHOUSE_STAFF), Set.of(),
                DataScope.WAREHOUSE, Set.of(), Set.of(warehouseIds));
    }

    private UUID supplier() {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO procurement.suppliers (id, code, name, status, version, created_at)
                VALUES (?, ?, 'Scope test supplier', 'ACTIVE', 0, NOW())""",
                id, "SCOPE-" + id.toString().substring(24).toUpperCase()));
        return id;
    }

    private PurchaseOrderSummary draftAtHcm(UUID supplierId) {
        return procurement.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId, DemoData.WAREHOUSE_HCM,
                "VND", LocalDate.now().plusDays(10), null,
                List.of(new CreatePOLineCommand("SOFA-3S-GREY", "scope test", 2, new BigDecimal("1000000")))));
    }

    private List<UUID> listedFor(CurrentUser user, UUID supplierId) {
        return WithCurrentUser.call(user, user.scope(), () -> procurement.list(
                        new ListPurchaseOrdersQuery(0, 50, supplierId, null, null, null, null)))
                .items().stream().map(PurchaseOrderSummary::purchaseOrderId).toList();
    }

    private static ErrorCode codeOf(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    @Test
    @DisplayName("a clerk sees and acts on purchase orders of their own warehouses only")
    void clerkIsLimitedToTheirWarehouses() {
        UUID supplierId = supplier();
        PurchaseOrderSummary order = draftAtHcm(supplierId);
        UUID submitter = tx.execute(s -> ReferenceRows.user(entityManager));
        CurrentUser hcm = clerkOf(DemoData.WAREHOUSE_HCM);
        CurrentUser elsewhere = clerkOf(UUID.randomUUID());
        CurrentUser nowhere = clerkOf();

        assertThat(listedFor(hcm, supplierId)).containsExactly(order.purchaseOrderId());
        assertThat(WithCurrentUser.call(hcm, hcm.scope(), () -> procurement.findById(order.purchaseOrderId())))
                .isPresent();

        assertThat(listedFor(elsewhere, supplierId)).isEmpty();
        assertThat(listedFor(nowhere, supplierId)).isEmpty();
        assertThat(codeOf(catchThrowable(() -> WithCurrentUser.call(elsewhere, elsewhere.scope(),
                () -> procurement.findById(order.purchaseOrderId())))))
                .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        assertThat(codeOf(catchThrowable(() -> WithCurrentUser.run(elsewhere,
                () -> procurement.submit(order.purchaseOrderId(), submitter)))))
                .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        assertThat(codeOf(catchThrowable(() -> WithCurrentUser.run(elsewhere,
                () -> procurement.deliveryDecisions(order.purchaseOrderId(), 0, 20)))))
                .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        assertThat(codeOf(catchThrowable(() -> WithCurrentUser.run(hcm, procurement::statusDashboard))))
                .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);

        // Not limited: a buyer or planner, and the system itself.
        CurrentUser planner = new CurrentUser(UUID.randomUUID(), "planner", Set.of(Role.INVENTORY_PLANNER),
                Set.of(), DataScope.ALL, Set.of(), Set.of());
        assertThat(listedFor(planner, supplierId)).containsExactly(order.purchaseOrderId());
        assertThat(procurement.findById(order.purchaseOrderId())).isPresent();
    }
}
