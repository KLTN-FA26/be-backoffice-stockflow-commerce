package com.stockflow.fulfillment;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.fulfillment.api.FulfillmentService;
import com.stockflow.fulfillment.api.FulfillmentTaskQuery;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots <b>only</b> the fulfillment module and the modules it declares in {@code package-info.java} (order, inventory, warehouse, design and identity).
 *
 * <p>The point is the boundary: if fulfillment gains a dependency its {@code package-info.java} does not declare, this
 * context cannot start and the build says so, instead of an import nobody noticed in review. Fix the declaration
 * or the code; do not widen this test.</p>
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
@ActiveProfiles("test")
@Import(PostgresContainer.class)
class FulfillmentModuleTest {

    @Autowired FulfillmentService fulfillment;
    @Autowired ApplicationContext context;
    // A dependency of a bootstrapped dependency, not of this module: explicit ports only, the slice must not boot more.
    @MockitoBean com.stockflow.payment.api.PaymentService paymentServiceMock;
    @MockitoBean com.stockflow.payment.api.ReturnSettlement returnSettlementMock;
    @MockitoBean com.stockflow.catalog.api.CatalogService catalogServiceMock;
    @MockitoBean com.stockflow.customer.api.CustomerService customerServiceMock;
    @MockitoBean com.stockflow.warehouse.api.WarehouseService warehouseServiceMock;
    @MockitoBean com.stockflow.product.api.ProductService productServiceMock;

    @Test
    @DisplayName("fulfillment starts and answers through its api with the other modules absent")
    void bootsInIsolation() {
        // Services ask for permissions, not role names: the manager's oversight grant (V20261022000200).
        var manager = TestUsers.builder().role(Role.WAREHOUSE_MANAGER).scope(DataScope.ALL)
                .can(com.stockflow.fulfillment.internal.service.FulfillmentPermissions.TASK_OVERSIGHT,
                        com.stockflow.common.security.Action.READ)
                .build();
        var page = WithCurrentUser.call(manager, DataScope.ALL, () -> fulfillment.listTasks(manager, new FulfillmentTaskQuery(null, null, null, 0, 10)));
        assertThat(page.items()).isNotNull();
        // A role with no business in the warehouse is refused before any row is read.
        var customer = TestUsers.customer();
        assertThatThrownBy(() -> WithCurrentUser.call(customer, DataScope.OWN, () -> fulfillment.listTasks(customer, new FulfillmentTaskQuery(null, null, null, 0, 10))))
                .isInstanceOf(BusinessException.class);
    }

    /** The negative half: a module fulfillment has no business with is genuinely not in this context. */
    @Test
    @DisplayName("a module outside fulfillment's declared dependencies is not in this context")
    void unrelatedModulesAreAbsent() {
        assertThatThrownBy(() -> context.getBean(com.stockflow.procurement.api.ProcurementService.class))
                .isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
    }
}
