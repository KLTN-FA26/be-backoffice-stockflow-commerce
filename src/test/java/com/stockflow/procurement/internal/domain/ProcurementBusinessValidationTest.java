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
    private PurchaseOrder sent() {
        var po = PurchaseOrder.draft("PO-VALIDATION", UUID.randomUUID(), Money.VND,
                List.of(new PoLine(UUID.randomUUID(), new Sku("CHAIR-1"), "Chair", 1, 0, Money.vnd(100))), LocalDate.now());
        po.approve(Instant.now()); po.send(Instant.now());
        return po;
    }

    @Test void rejectionAndRecoveryWithoutReasonAreBusinessErrors() {
        var po = sent();
        assertThatThrownBy(() -> po.recordSupplierConfirmation(SupplierConfirmationStatus.REJECTED, null, " ", Instant.now()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PO_REASON_REQUIRED);
        assertThatThrownBy(() -> po.requireDeliveryRecovery(LocalDate.now(), " ", true, true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.PO_REASON_REQUIRED);
        assertThat(po.supplierConfirmationStatus()).isEqualTo(SupplierConfirmationStatus.PENDING);
    }

    @Test void invalidSupplierProfileIsNotAnUnexpectedApplicationFailure() {
        assertThatThrownBy(() -> new SupplierProfile("SUP-1", "Supplier", "supplier@example.com", null,
                "TAX-001", 30, 7, "EMAIL", null)).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.SUPPLIER_PROFILE_INVALID);
    }
}
