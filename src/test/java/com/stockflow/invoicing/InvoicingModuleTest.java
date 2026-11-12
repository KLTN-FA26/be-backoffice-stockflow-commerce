package com.stockflow.invoicing;

import com.stockflow.invoicing.internal.service.EInvoiceSettingsService;
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
 * Boots <b>only</b> the invoicing module and the modules it declares in {@code package-info.java} (order and customer).
 *
 * <p>The point is the boundary: if invoicing gains a dependency its {@code package-info.java} does not declare, this
 * context cannot start and the build says so, instead of an import nobody noticed in review. Fix the declaration
 * or the code; do not widen this test.</p>
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
@ActiveProfiles("test")
@Import(PostgresContainer.class)
class InvoicingModuleTest {

    @Autowired EInvoiceSettingsService settings;
    @Autowired ApplicationContext context;
    // Dependencies of the bootstrapped dependencies (order, customer), not of this module: explicit ports only, the
    // slice must not boot more.
    @MockitoBean com.stockflow.inventory.api.InventoryService inventoryServiceMock;
    @MockitoBean com.stockflow.inventory.api.UomConversionService uomConversionServiceMock;
    @MockitoBean com.stockflow.inventory.api.PackagingSpecResolver packagingSpecResolverMock;
    @MockitoBean com.stockflow.design.api.DesignService designServiceMock;
    @MockitoBean com.stockflow.warehouse.api.WarehouseService warehouseServiceMock;
    @MockitoBean com.stockflow.product.api.ProductService productServiceMock;
    @MockitoBean com.stockflow.product.api.CustomerSkus customerSkusMock;
    @MockitoBean com.stockflow.payment.api.PaymentService paymentServiceMock;
    @MockitoBean com.stockflow.catalog.api.CatalogService catalogServiceMock;
    @MockitoBean com.stockflow.identity.api.IdentityService identityServiceMock;
    @MockitoBean com.stockflow.identity.api.PortalAccountService portalAccountServiceMock;
    @MockitoBean com.stockflow.notification.api.NotificationService notificationServiceMock;
    @MockitoBean com.stockflow.notification.api.ProductionOrderResends productionOrderResendsMock;
    // Order's own port for the customer's chat consent, implemented by chat, which is not booted here.
    @MockitoBean com.stockflow.order.api.ConsentMessages consentMessagesMock;

    @Test
    @DisplayName("invoicing starts and reads its settings with the other modules absent")
    void bootsInIsolation() {
        var current = settings.get();
        assertThat(current.provider().provider()).as("the sandbox is the provider in use").isEqualTo("SANDBOX");
        assertThat(current.series()).isNotEmpty();
    }

    /** The negative half: a module invoicing has no business with is genuinely not in this context. */
    @Test
    @DisplayName("a module outside invoicing's declared dependencies is not in this context")
    void unrelatedModulesAreAbsent() {
        assertThatThrownBy(() -> context.getBean(com.stockflow.reporting.api.ReportingService.class))
                .isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
        assertThatThrownBy(() -> context.getBean(com.stockflow.fulfillment.api.FulfillmentService.class))
                .isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
    }
}
