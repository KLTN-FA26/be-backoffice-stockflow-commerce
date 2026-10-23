package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.notification.api.NotificationService;
import com.stockflow.procurement.api.SaveSupplierCommand;
import com.stockflow.procurement.api.SupplierPerformanceSummary;
import com.stockflow.procurement.api.SupplierSummary;
import com.stockflow.procurement.internal.domain.Supplier;
import com.stockflow.procurement.internal.domain.SupplierCommunicationChannel;
import com.stockflow.procurement.internal.domain.SupplierDetails;
import com.stockflow.procurement.internal.domain.SupplierMetrics;
import com.stockflow.procurement.internal.domain.SupplierRepository;
import com.stockflow.procurement.internal.domain.SupplierStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** kltn-docs warehouse/02 §3 and BR-02/BR-07: supplier master data, its uniqueness, deactivation and performance. */
class SupplierServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-22T03:00:00Z");

    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final SupplierServiceImpl service =
            new SupplierServiceImpl(suppliers, Clock.fixed(NOW, ZoneOffset.UTC), notifications);

    @BeforeEach
    void saveReturnsArgument() {
        when(suppliers.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private static SaveSupplierCommand command(String code, String status) {
        return new SaveSupplierCommand(code, "  ACME Paper ", "Lan", "lan@acme.vn", "0901234567", "0312345678", status,
                30, 7, "EMAIL", null, new BigDecimal("5"), false, null);
    }

    private static Supplier existing(String code, SupplierStatus status) {
        return new Supplier(UUID.randomUUID(), new SupplierDetails(code, "ACME", null, "s@acme.vn", null, null, status,
                30, 7, SupplierCommunicationChannel.EMAIL, null, null, false, BigDecimal.valueOf(2)), NOW, NOW);
    }

    private static ErrorCode code(Runnable call) {
        BusinessException thrown = catchThrowableOfType(BusinessException.class, call::run);
        assertThat(thrown).isNotNull();
        return thrown.errorCode();
    }

    @Test
    @DisplayName("create: the code is upper-cased, names are trimmed, a missing loss tolerance defaults to 2%, "
            + "and the delivery contact is validated with the notification module")
    void create() {
        SupplierSummary summary = service.create(command("acme-01", "ACTIVE"));

        assertThat(summary.code()).isEqualTo("ACME-01");
        assertThat(summary.name()).isEqualTo("ACME Paper");
        assertThat(summary.status()).isEqualTo("ACTIVE");
        assertThat(summary.lossTolerancePercent()).isEqualByComparingTo("2");
        assertThat(summary.overReceiptTolerancePercent()).isEqualByComparingTo("5");
        verify(notifications).validateSupplierDelivery("EMAIL", "lan@acme.vn");
    }

    @Test
    @DisplayName("create: an API supplier is validated against its endpoint, not its email")
    void createApiSupplier() {
        var api = new SaveSupplierCommand("API-1", "API Co", null, null, null, null, "ACTIVE", 0, 0, "API",
                "https://api.example.com/po", null, false, null);
        service.create(api);
        verify(notifications).validateSupplierDelivery("API", "https://api.example.com/po");
    }

    @Test
    @DisplayName("create: a code or a tax code already used is SUPPLIER_CODE_ALREADY_EXISTS / SUPPLIER_TAX_CODE_ALREADY_EXISTS")
    void uniqueness() {
        when(suppliers.codeExists("ACME-01", null)).thenReturn(true);
        assertThat(code(() -> service.create(command("ACME-01", "ACTIVE"))))
                .isEqualTo(ErrorCode.SUPPLIER_CODE_ALREADY_EXISTS);

        when(suppliers.codeExists("ACME-02", null)).thenReturn(false);
        when(suppliers.taxCodeExists("0312345678", null)).thenReturn(true);
        assertThat(code(() -> service.create(command("ACME-02", "ACTIVE"))))
                .isEqualTo(ErrorCode.SUPPLIER_TAX_CODE_ALREADY_EXISTS);
        verify(suppliers, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BOGUS", ""})
    @DisplayName("create: an unknown status is SUPPLIER_PROFILE_INVALID")
    void unknownStatus(String status) {
        assertThat(code(() -> service.create(command("ACME-01", status)))).isEqualTo(ErrorCode.SUPPLIER_PROFILE_INVALID);
    }

    @Test
    @DisplayName("create: an unknown channel or a missing status is SUPPLIER_PROFILE_INVALID, not a 500")
    void unknownChannel() {
        var badChannel = new SaveSupplierCommand("A", "N", null, "a@b.c", null, null, "ACTIVE", 1, 1, "FAX", null, null,
                false, null);
        assertThat(code(() -> service.create(badChannel))).isEqualTo(ErrorCode.SUPPLIER_PROFILE_INVALID);
        var noStatus = new SaveSupplierCommand("A", "N", null, "a@b.c", null, null, null, 1, 1, "EMAIL", null, null,
                false, null);
        assertThat(code(() -> service.create(noStatus))).isEqualTo(ErrorCode.SUPPLIER_PROFILE_INVALID);
    }

    @Test
    @DisplayName("create: payment terms and lead time are 0..365 days; tolerances are 0..100 percent")
    void commercialLimits() {
        var longTerms = new SaveSupplierCommand("A", "N", null, "a@b.c", null, null, "ACTIVE", 366, 1, "EMAIL", null,
                null, false, null);
        assertThat(code(() -> service.create(longTerms))).isEqualTo(ErrorCode.SUPPLIER_PROFILE_INVALID);
        var badTolerance = new SaveSupplierCommand("A", "N", null, "a@b.c", null, null, "ACTIVE", 1, 1, "EMAIL", null,
                new BigDecimal("100.01"), false, null);
        assertThat(code(() -> service.create(badTolerance))).isEqualTo(ErrorCode.SUPPLIER_PROFILE_INVALID);
        var maxTolerance = new SaveSupplierCommand("A", "N", null, "a@b.c", null, null, "ACTIVE", 365, 0, "EMAIL",
                null, new BigDecimal("100"), false, BigDecimal.ZERO);
        assertThat(service.create(maxTolerance).paymentTermDays()).isEqualTo(365);
    }

    @Test
    @DisplayName("create: the notification module's refusal of the delivery contact reaches the caller and nothing is saved")
    void deliveryContactRefused() {
        doThrow(new BusinessException(ErrorCode.SUPPLIER_DELIVERY_CONTACT_INVALID))
                .when(notifications).validateSupplierDelivery(any(), any());
        assertThat(code(() -> service.create(command("ACME-01", "ACTIVE"))))
                .isEqualTo(ErrorCode.SUPPLIER_DELIVERY_CONTACT_INVALID);
        verify(suppliers, never()).save(any());
    }

    @Test
    @DisplayName("update: an unknown supplier is SUPPLIER_NOT_FOUND; the supplier is read for update (locked)")
    void updateUnknown() {
        UUID missing = UUID.randomUUID();
        when(suppliers.findByIdForUpdate(missing)).thenReturn(Optional.empty());
        assertThat(code(() -> service.update(missing, command("ACME-01", "ACTIVE"))))
                .isEqualTo(ErrorCode.SUPPLIER_NOT_FOUND);
    }

    @Test
    @DisplayName("update: the code is immutable (CONFLICT); a case-only change is not a change")
    void codeImmutable() {
        Supplier supplier = existing("ACME-01", SupplierStatus.ACTIVE);
        when(suppliers.findByIdForUpdate(supplier.id())).thenReturn(Optional.of(supplier));
        assertThat(code(() -> service.update(supplier.id(), command("OTHER", "ACTIVE")))).isEqualTo(ErrorCode.CONFLICT);
        SupplierSummary updated = service.update(supplier.id(), command("acme-01", "ACTIVE"));
        assertThat(updated.code()).isEqualTo("ACME-01");
        verify(suppliers).codeExists("ACME-01", supplier.id());
    }

    @Test
    @DisplayName("update: uniqueness is checked against the other suppliers, not the supplier itself")
    void updateUniqueness() {
        Supplier supplier = existing("ACME-01", SupplierStatus.ACTIVE);
        when(suppliers.findByIdForUpdate(supplier.id())).thenReturn(Optional.of(supplier));
        when(suppliers.taxCodeExists("0312345678", supplier.id())).thenReturn(true);
        assertThat(code(() -> service.update(supplier.id(), command("ACME-01", "ACTIVE"))))
                .isEqualTo(ErrorCode.SUPPLIER_TAX_CODE_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("update: making an active supplier inactive with open purchase orders is SUPPLIER_HAS_OPEN_PURCHASE_ORDERS")
    void cannotSuspendWithOpenOrders() {
        Supplier supplier = existing("ACME-01", SupplierStatus.ACTIVE);
        when(suppliers.findByIdForUpdate(supplier.id())).thenReturn(Optional.of(supplier));
        when(suppliers.hasOpenOrders(supplier.id())).thenReturn(true);
        assertThat(code(() -> service.update(supplier.id(), command("ACME-01", "INACTIVE"))))
                .isEqualTo(ErrorCode.SUPPLIER_HAS_OPEN_PURCHASE_ORDERS);
        assertThat(supplier.details().status()).isEqualTo(SupplierStatus.ACTIVE);
    }

    @Test
    @DisplayName("update: editing other fields of an active supplier with open orders is allowed")
    void editWithOpenOrders() {
        Supplier supplier = existing("ACME-01", SupplierStatus.ACTIVE);
        when(suppliers.findByIdForUpdate(supplier.id())).thenReturn(Optional.of(supplier));
        when(suppliers.hasOpenOrders(supplier.id())).thenReturn(true);
        assertThat(service.update(supplier.id(), command("ACME-01", "ACTIVE")).leadTimeDays()).isEqualTo(7);
    }

    @Test
    @DisplayName("deactivate: no open purchase orders -> INACTIVE and saved; with open orders refused and left ACTIVE")
    void deactivate() {
        Supplier supplier = existing("ACME-01", SupplierStatus.ACTIVE);
        when(suppliers.findByIdForUpdate(supplier.id())).thenReturn(Optional.of(supplier));
        when(suppliers.hasOpenOrders(supplier.id())).thenReturn(true);
        assertThat(code(() -> service.deactivate(supplier.id())))
                .isEqualTo(ErrorCode.SUPPLIER_HAS_OPEN_PURCHASE_ORDERS);
        assertThat(supplier.details().status()).isEqualTo(SupplierStatus.ACTIVE);
        verify(suppliers, never()).save(any());

        when(suppliers.hasOpenOrders(supplier.id())).thenReturn(false);
        service.deactivate(supplier.id());
        assertThat(supplier.details().status()).isEqualTo(SupplierStatus.INACTIVE);
        verify(suppliers).save(supplier);
    }

    @Test
    @DisplayName("deactivate: an unknown supplier is SUPPLIER_NOT_FOUND")
    void deactivateUnknown() {
        UUID missing = UUID.randomUUID();
        when(suppliers.findByIdForUpdate(missing)).thenReturn(Optional.empty());
        assertThat(code(() -> service.deactivate(missing))).isEqualTo(ErrorCode.SUPPLIER_NOT_FOUND);
    }

    @Test
    @DisplayName("findById: present or empty")
    void findById() {
        Supplier supplier = existing("ACME-01", SupplierStatus.ACTIVE);
        when(suppliers.findById(supplier.id())).thenReturn(Optional.of(supplier));
        assertThat(service.findById(supplier.id())).map(SupplierSummary::code).contains("ACME-01");
        assertThat(service.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("list: a blank status means any, a known status filters, an unknown one is SUPPLIER_PROFILE_INVALID")
    void listStatusFilter() {
        when(suppliers.list(anyInt(), anyInt(), any(), any(), any(), any())).thenReturn(PageResponse.empty(0, 20));
        service.list(0, 20, null, " ", null, null);
        verify(suppliers).list(0, 20, null, null, null, null);
        service.list(0, 20, "acme", "INACTIVE", true, "name");
        verify(suppliers).list(0, 20, "acme", SupplierStatus.INACTIVE, true, "name");
        assertThat(code(() -> service.list(0, 20, null, "bogus", null, null)))
                .isEqualTo(ErrorCode.SUPPLIER_PROFILE_INVALID);
    }

    // ------------------------------------------------------------------ performance

    @Test
    @DisplayName("performance: on-time rate = on time / (on time + late), quality = accepted / (accepted + rejected), "
            + "both rounded half up to 2 decimals, lead time to 2 decimals")
    void performanceRates() {
        Supplier supplier = existing("ACME-01", SupplierStatus.ACTIVE);
        when(suppliers.findById(supplier.id())).thenReturn(Optional.of(supplier));
        when(suppliers.performance(supplier.id())).thenReturn(new SupplierMetrics(12, 9, 2, 1, new BigDecimal("6.666"),
                199, 1));

        SupplierPerformanceSummary p = service.performance(supplier.id());

        assertThat(p.onTimeDeliveryRate()).isEqualByComparingTo("66.67");
        assertThat(p.qualityAcceptanceRate()).isEqualByComparingTo("99.50");
        assertThat(p.averageLeadTimeDays()).isEqualByComparingTo("6.67");
        assertThat(p.totalPurchaseOrders()).isEqualTo(12);
        assertThat(p.calculatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("performance: a supplier with no deliveries or inspections has no rates (null, not 0% or a division error)")
    void performanceWithoutHistory() {
        Supplier supplier = existing("ACME-01", SupplierStatus.ACTIVE);
        when(suppliers.findById(supplier.id())).thenReturn(Optional.of(supplier));
        when(suppliers.performance(supplier.id())).thenReturn(new SupplierMetrics(0, 0, 0, 0, null, 0, 0));

        SupplierPerformanceSummary p = service.performance(supplier.id());

        assertThat(p.onTimeDeliveryRate()).isNull();
        assertThat(p.qualityAcceptanceRate()).isNull();
        assertThat(p.averageLeadTimeDays()).isNull();
    }

    @Test
    @DisplayName("performance: all late is 0.00%, all on time 100.00%; an unknown supplier is SUPPLIER_NOT_FOUND")
    void performanceBounds() {
        Supplier supplier = existing("ACME-01", SupplierStatus.ACTIVE);
        when(suppliers.findById(supplier.id())).thenReturn(Optional.of(supplier));
        when(suppliers.performance(supplier.id())).thenReturn(new SupplierMetrics(3, 3, 0, 3, BigDecimal.ONE, 0, 5));
        assertThat(service.performance(supplier.id()).onTimeDeliveryRate()).isEqualByComparingTo("0");
        assertThat(service.performance(supplier.id()).qualityAcceptanceRate()).isEqualByComparingTo("0");

        when(suppliers.performance(supplier.id())).thenReturn(new SupplierMetrics(3, 3, 3, 0, BigDecimal.ONE, 5, 0));
        assertThat(service.performance(supplier.id()).onTimeDeliveryRate()).isEqualByComparingTo("100.00");

        UUID missing = UUID.randomUUID();
        when(suppliers.findById(missing)).thenReturn(Optional.empty());
        assertThat(code(() -> service.performance(missing))).isEqualTo(ErrorCode.SUPPLIER_NOT_FOUND);
        verify(suppliers, never()).performance(missing);
    }
}
