package com.stockflow.product;

import com.stockflow.product.api.ProductService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots <b>only</b> the product module and the modules it declares in {@code package-info.java} (inventory and identity).
 *
 * <p>The point is the boundary: if product gains a dependency its {@code package-info.java} does not declare, this
 * context cannot start and the build says so, instead of an import nobody noticed in review. Fix the declaration
 * or the code; do not widen this test.</p>
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
@ActiveProfiles("test")
@Import(PostgresContainer.class)
class ProductModuleTest {

    @Autowired ProductService product;
    @Autowired ApplicationContext context;
    // A dependency of a bootstrapped dependency, not of this module: explicit ports only, the slice must not boot more.
    @MockitoBean com.stockflow.warehouse.api.WarehouseService warehouseServiceMock;
    @MockitoBean com.stockflow.warehouse.api.PutawayTasks putawayTasksMock;

    @Test
    @DisplayName("product starts and answers through its api with the other modules absent")
    void bootsInIsolation() {
        assertThat(product.containsSku(DemoData.PRODUCT_SOFA, "SOFA-3S-GREY")).isTrue();
        assertThat(product.containsSku(DemoData.PRODUCT_SOFA, "NO-SUCH-SKU")).isFalse();
    }

    /** The negative half: a module product has no business with is genuinely not in this context. */
    @Test
    @DisplayName("a module outside product's declared dependencies is not in this context")
    void unrelatedModulesAreAbsent() {
        assertThatThrownBy(() -> context.getBean(com.stockflow.order.api.OrderService.class))
                .isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
    }
}
