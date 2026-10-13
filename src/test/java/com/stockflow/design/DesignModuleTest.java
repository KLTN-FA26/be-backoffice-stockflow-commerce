package com.stockflow.design;

import com.stockflow.common.error.BusinessException;
import com.stockflow.design.api.DesignService;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots <b>only</b> the design module and the modules it declares in {@code package-info.java} (product and identity).
 *
 * <p>The point is the boundary: if design gains a dependency its {@code package-info.java} does not declare, this
 * context cannot start and the build says so, instead of an import nobody noticed in review. Fix the declaration
 * or the code; do not widen this test.</p>
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
@ActiveProfiles("test")
@Import(PostgresContainer.class)
class DesignModuleTest {

    @Autowired DesignService design;
    @Autowired ApplicationContext context;
    // A dependency of a bootstrapped dependency, not of this module: explicit ports only, the slice must not boot more.
    @MockitoBean com.stockflow.inventory.api.InventoryControlService inventoryControlServiceMock;
    @MockitoBean com.stockflow.warehouse.api.WarehouseService warehouseServiceMock;
    // customer (bootstrapped) invites portal accounts and mails the invitation (SCRUM-429): its ports, not the modules.
    @MockitoBean com.stockflow.identity.api.PortalAccountService portalAccountServiceMock;
    @MockitoBean com.stockflow.notification.api.NotificationService notificationServiceMock;

    @Test
    @DisplayName("design starts and answers through its api with the other modules absent")
    void bootsInIsolation() {
        assertThatThrownBy(() -> design.verifySnapshot(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(BusinessException.class);
    }

    /** The negative half: a module design has no business with is genuinely not in this context. */
    @Test
    @DisplayName("a module outside design's declared dependencies is not in this context")
    void unrelatedModulesAreAbsent() {
        assertThatThrownBy(() -> context.getBean(com.stockflow.order.api.OrderService.class))
                .isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
    }
}
