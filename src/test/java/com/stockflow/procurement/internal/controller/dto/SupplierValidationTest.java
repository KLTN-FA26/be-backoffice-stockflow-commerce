package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SupplierValidationTest {
    @Test void defaultsDistinguishOmittedValuesFromExplicitZero() {
        var request = request("supplier@example.com", "0901234567", "0312345678", null, null);
        assertThat(request.paymentTermDays()).isEqualTo(30);
        assertThat(request.leadTimeDays()).isEqualTo(7);
        assertThat(request("supplier@example.com", null, null, 0, 0).paymentTermDays()).isZero();
    }
    @Test void invalidContactsAndRejectionWithoutReasonAreRejectedBeforeService() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(request("supplier@example.com", "0901234567", "0312345678", 30, 7))).isEmpty();
            assertThat(validator.validate(request("", "--------", "--------", 30, 7))).isNotEmpty();
            assertThat(validator.validate(request("bad", "123", null, -1, 366))).isNotEmpty();
            assertThat(validator.validate(new SupplierConfirmationRequest("REJECTED", null, " "))).isNotEmpty();
        }
    }
    private SaveSupplierRequest request(String email, String phone, String tax, Integer terms, Integer lead) {
        return new SaveSupplierRequest("SUP-1", "Supplier", null, email, phone, tax, "ACTIVE", terms, lead, "EMAIL", null);
    }
}
