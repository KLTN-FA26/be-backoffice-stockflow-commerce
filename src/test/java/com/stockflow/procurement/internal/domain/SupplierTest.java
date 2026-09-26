package com.stockflow.procurement.internal.domain;
import com.stockflow.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SupplierTest {
    private SupplierDetails profile(String code, SupplierStatus status) {
        return new SupplierDetails(code, " Supplier ", null, "s@example.com", null, null,
                status, 30, 7, SupplierCommunicationChannel.EMAIL, null);
    }
    @Test void aggregateOwnsImmutableCodeAndDeactivation() {
        var supplier = Supplier.create(profile("sup-1", SupplierStatus.ACTIVE));
        assertThat(supplier.details().code()).isEqualTo("SUP-1");
        assertThatThrownBy(() -> supplier.update(profile("SUP-2", SupplierStatus.ACTIVE), false)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> supplier.deactivate(true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> supplier.update(profile("SUP-1", SupplierStatus.INACTIVE), true)).isInstanceOf(BusinessException.class);
        supplier.deactivate(false);
        assertThat(supplier.details().status()).isEqualTo(SupplierStatus.INACTIVE);
    }
}
