package com.stockflow.procurement;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots <b>only</b> the procurement module and the modules it declares in {@code package-info.java} (product, inventory, warehouse, notification, identity; order for receiving against production orders, 4A-5; fulfillment and order for return receipts, 4D-2).
 *
 * <p>The point is the boundary: if procurement gains a dependency its {@code package-info.java} does not declare, this
 * context cannot start and the build says so, instead of an import nobody noticed in review. Fix the declaration
 * or the code; do not widen this test.</p>
 */
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
@ActiveProfiles("test")
@Import(PostgresContainer.class)
class ProcurementModuleTest {

    @Autowired GoodsReceipts receipts;
    @Autowired ApplicationContext context;
    // notification (booted here) asks the order module for production orders; order is not booted.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.notification.api.ProductionOrderDirectory productionOrderDirectoryMock;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.notification.api.ProductionOrderResends productionOrderResendsMock;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.product.api.CustomerSkus customerSkusMock;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.product.api.ProductService productServiceMock;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.product.api.ProductPublication productPublicationMock;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.notification.api.NotificationService notificationServiceMock;
    // order's SalesFollowUpService posts inbox notices (WF-02 A10)
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.notification.api.Inbox inboxMock;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.identity.api.PortalAccountService portalAccountServiceMock;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.identity.api.IdentityService identityServiceMock;
    /** Notification (booted here) asks customer for addresses through this; customer is not booted (SCRUM-352). */
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.notification.api.CustomerDirectory customerDirectory;
    // Order is booted for its production orders (4A-5); what order itself depends on is not: explicit ports only, the
    // slice must not boot more.
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.customer.api.CustomerService customerServiceMock;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.customer.api.CustomerLegalProfiles customerLegalProfilesMock;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.customer.api.CustomerPaymentSchedules customerPaymentSchedulesMock;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.catalog.api.CatalogService catalogServiceMock;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.design.api.DesignService designServiceMock;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.payment.api.PaymentService paymentServiceMock;
    /** Order's port for the customer's word in their conversation (a delivery window moved on it), chat's to answer. */
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.order.api.ConsentMessages consentMessagesMock;
    /** Order completion asks invoicing whether every delivery is invoiced (WF-05 condition 5); invoicing is not loaded here. */
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.stockflow.order.api.DeliveryInvoices deliveryInvoicesMock;

    @Test
    @DisplayName("procurement starts and answers through its goods receipts with the other modules absent")
    void bootsInIsolation() {
        assertThatThrownBy(() -> receipts.get(UUID.randomUUID())).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.GOODS_RECEIPT_NOT_FOUND);
    }

    /**
     * The negative half: a module procurement has no business with is genuinely not in this context. (Order is one of
     * its dependencies since 4A-5 and fulfillment since 4D-2, for the cartons a shipment carried; reporting, which
     * depends on both, is not.)
     */
    @Test
    @DisplayName("a module outside procurement's declared dependencies is not in this context")
    void unrelatedModulesAreAbsent() {
        assertThatThrownBy(() -> context.getBean(com.stockflow.reporting.api.ReportingService.class))
                .isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
        assertThat(context.getBean(com.stockflow.order.api.ProductionOrderReceiving.class)).isNotNull();
    }
}
