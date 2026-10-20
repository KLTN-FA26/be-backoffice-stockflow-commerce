package com.stockflow.catalog;

import com.stockflow.catalog.api.CatalogService;
import com.stockflow.common.error.BusinessException;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots <b>only</b> the catalog module and the modules it declares in {@code package-info.java} (product and inventory).
 *
 * <p>The point is the boundary: if catalog gains a dependency its {@code package-info.java} does not declare, this
 * context cannot start and the build says so, instead of an import nobody noticed in review. Fix the declaration
 * or the code; do not widen this test.</p>
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
@ActiveProfiles("test")
@Import(PostgresContainer.class)
class CatalogModuleTest {

    @Autowired CatalogService catalog;
    @Autowired ApplicationContext context;
    // A dependency of a bootstrapped dependency, not of this module: explicit ports only, the slice must not boot more.
    @MockitoBean com.stockflow.identity.api.IdentityService identityServiceMock;
    @MockitoBean com.stockflow.warehouse.api.WarehouseService warehouseServiceMock;
    // customer (bootstrapped) invites portal accounts and mails the invitation (SCRUM-429): its ports, not the modules.
    @MockitoBean com.stockflow.identity.api.PortalAccountService portalAccountServiceMock;
    @MockitoBean com.stockflow.notification.api.NotificationService notificationServiceMock;

    @Test
    @DisplayName("catalog starts and answers through its api with the other modules absent")
    void bootsInIsolation() {
        // Reads the pricing tables: a SKU nobody sells is refused, not priced at zero.
        assertThatThrownBy(() -> catalog.checkoutPrices(Set.of("NO-SUCH-SKU"))).isInstanceOf(BusinessException.class);
        assertThat(catalog.checkoutPrices(Set.of())).isEmpty();
    }

    /** The negative half: a module catalog has no business with is genuinely not in this context. */
    @Test
    @DisplayName("a module outside catalog's declared dependencies is not in this context")
    void unrelatedModulesAreAbsent() {
        assertThatThrownBy(() -> context.getBean(com.stockflow.order.api.OrderService.class))
                .isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
    }
}
