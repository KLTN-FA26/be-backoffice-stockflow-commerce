package com.stockflow.inventory;

import com.stockflow.inventory.api.InventoryService;

import com.stockflow.common.domain.Sku;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots <b>only</b> the inventory module, with every other module absent.
 *
 * <p>This is a capability a plain monolith does not have at all: {@code @ApplicationModuleTest}
 * starts inventory's beans plus the shared ones, and nothing else. Two consequences worth
 * having:</p>
 * <ul>
 *   <li><b>It runs in a second or two</b>, because thirteen modules' beans, listeners and
 *       schedulers never start.</li>
 *   <li><b>It fails loudly if inventory has an undeclared dependency.</b> If someone injects an
 *       order bean into an inventory class, the context cannot start — the boundary violation
 *       surfaces as a broken test rather than as an import nobody noticed in review.</li>
 * </ul>
 */
// DIRECT_DEPENDENCIES: moves ask warehouse :: api whether a location may take stock (issue #67).
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
@ActiveProfiles("test")
@Import(PostgresContainer.class)
class InventoryModuleTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");

    @Autowired InventoryService inventory;
    @Autowired ApplicationContext context;
    // Explicit ports only: the slice must not boot the external modules to satisfy these calls.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.notification.api.NotificationService notifications;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.identity.api.IdentityService identities;

    @Test
    @DisplayName("inventory starts and serves queries with no other module present")
    void bootsInIsolation() {
        assertThat(inventory.availableToPromise(SOFA)).isPositive();
        assertThat(inventory.availabilityOf(SOFA)).isNotEmpty();
    }

    /**
     * The batch read (one grouped query over the denormalised column) must say exactly what the
     * single read (entities plus their reservation rows) says, or a product page and a checkout
     * would disagree about the same SKU. TABLE-OAK-160 also holds QUARANTINE stock, which neither
     * may count.
     */
    @Test
    @DisplayName("ATP for several SKUs in one query equals ATP asked one SKU at a time")
    void batchAtpMatchesSingleAtp() {
        Sku table = new Sku("TABLE-OAK-160");
        Sku unknown = new Sku("NO-SUCH-SKU");

        var batch = inventory.availableToPromise(List.of(SOFA, table, unknown, SOFA));

        assertThat(batch).containsOnlyKeys(SOFA, table, unknown);
        assertThat(batch.get(SOFA)).isEqualTo(inventory.availableToPromise(SOFA)).isPositive();
        assertThat(batch.get(table)).isEqualTo(inventory.availableToPromise(table)).isPositive();
        assertThat(batch.get(unknown)).isZero();
        assertThat(inventory.availableToPromise(List.of())).isEmpty();
    }

    /**
     * The negative half, and the more informative one: order really is absent from this context.
     *
     * <p>If this ever starts passing by finding the bean, inventory has acquired a dependency on
     * order that {@code package-info.java} does not declare.</p>
     */
    @Test
    @DisplayName("the order module is genuinely not in this context")
    void otherModulesAreAbsent() {
        assertThatThrownBy(() -> context.getBean(com.stockflow.order.api.OrderService.class))
                .isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
    }
}
