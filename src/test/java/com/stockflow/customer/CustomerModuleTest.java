package com.stockflow.customer;

import com.stockflow.customer.api.CustomerService;
import com.stockflow.support.DemoData;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots <b>only</b> the customer module and the modules it declares in {@code package-info.java} (identity).
 *
 * <p>The point is the boundary: if customer gains a dependency its {@code package-info.java} does not declare, this
 * context cannot start and the build says so, instead of an import nobody noticed in review. Fix the declaration
 * or the code; do not widen this test.</p>
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
@ActiveProfiles("test")
@Import(PostgresContainer.class)
class CustomerModuleTest {

    @Autowired CustomerService customer;
    @Autowired ApplicationContext context;
    // A dependency of a bootstrapped dependency, not of this module: explicit ports only, the slice must not boot more.
    @MockitoBean com.stockflow.warehouse.api.WarehouseService warehouseServiceMock;

    @Test
    @DisplayName("customer starts and answers through its api with the other modules absent")
    void bootsInIsolation() {
        assertThat(customer.findById(DemoData.CUSTOMER_ID)).isPresent();
        assertThat(customer.findById(UUID.randomUUID())).isEmpty();
    }

    /** The negative half: a module customer has no business with is genuinely not in this context. */
    @Test
    @DisplayName("a module outside customer's declared dependencies is not in this context")
    void unrelatedModulesAreAbsent() {
        assertThatThrownBy(() -> context.getBean(com.stockflow.order.api.OrderService.class))
                .isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
    }
}
