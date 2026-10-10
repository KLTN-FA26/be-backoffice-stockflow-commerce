package com.stockflow.procurement.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ProcurementBusinessValidationTest {
    private static final Instant NOW = Instant.parse("2026-10-01T03:00:00Z");

    private static PurchaseOrder approved(LocalDate expected) {
        var po = PurchaseOrder.draft("PO-VALIDATION", UUID.randomUUID(), UUID.randomUUID(), Money.VND,
                List.of(PurchaseOrder.line(1, UUID.randomUUID(), new Sku("CHAIR-1"), "EACH", "Chair", 1,
                        Money.vnd(100), null)),
                LocalDate.of(2026, 10, 1), expected, null, 30, 7);
        po.submit(UUID.randomUUID(), UUID.randomUUID(), 0, NOW);
        po.approve(UUID.randomUUID(), NOW);
        return po;
    }

    private PurchaseOrder sent() {
        var po = approved(LocalDate.of(2026, 10, 20));
        po.confirm(UUID.randomUUID(), null, null, NOW);
        return po;
    }

    @Test void rejectionAndRecoveryWithoutReasonAreBusinessErrors() {
        var po = sent();
        assertThatThrownBy(() -> po.recordSupplierConfirmation(SupplierConfirmationStatus.REJECTED, null, " ", NOW))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PO_REASON_REQUIRED);
        assertThatThrownBy(() -> po.requireDeliveryRecovery(LocalDate.of(2026, 10, 2), " ", true, true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PO_REASON_REQUIRED);
        assertThat(po.supplierConfirmationStatus()).isEqualTo(SupplierConfirmationStatus.PENDING);
    }

    @Test void firstDeliveryDateNeedsNoReasonButChangingItDoes() {
        var date = LocalDate.of(2026, 10, 8);
        var undated = approved(null);
        undated.confirm(UUID.randomUUID(), date, null, NOW);
        assertThat(undated.expectedAt()).isEqualTo(date);

        var dated = approved(date);
        assertThatThrownBy(() -> dated.confirm(UUID.randomUUID(), date.plusDays(1), null, NOW))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.PO_REASON_REQUIRED));
        assertThat(dated.expectedAt()).isEqualTo(date);
        assertThat(dated.status()).isEqualTo(PurchaseOrderStatus.APPROVED);
        dated.confirm(UUID.randomUUID(), date.plusDays(1), "Supplier requested another day", NOW);
        assertThat(dated.expectedAt()).isEqualTo(date.plusDays(1));
        assertThat(dated.status()).isEqualTo(PurchaseOrderStatus.CONFIRMED);
    }

    @Test void invalidSupplierProfileIsNotAnUnexpectedApplicationFailure() {
        assertThatThrownBy(() -> new SupplierProfile("SUP-1", "Supplier", "supplier@example.com", null,
                "TAX-001", 30, 7, "EMAIL", null, null, null, null)).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.SUPPLIER_PROFILE_INVALID);
    }
}
