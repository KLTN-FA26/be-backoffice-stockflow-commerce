package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SupplierValidationTest {
    @Test void defaultsDistinguishOmittedValuesFromExplicitZero() {
        var request = request("supplier@example.com", "0901234567", "0312345678", null, null);
        assertThat(request.paymentTermDays()).isNull();
        assertThat(request.leadTimeDays()).isNull();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request, SaveSupplierRequest.Update.class))
                    .extracting(v -> v.getPropertyPath().toString()).containsExactlyInAnyOrder("paymentTermDays", "leadTimeDays");
        }
        assertThat(request("supplier@example.com", null, null, 0, 0).paymentTermDays()).isZero();
    }
    @Test void invalidContactsAndRejectionWithoutReasonAreRejectedBeforeService() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(request("supplier@example.com", "0901234567", "0312345678", 30, 7))).isEmpty();
            assertThat(validator.validate(request("", "--------", "--------", 30, 7))).isNotEmpty();
            assertThat(validator.validate(request("bad", "123", null, -1, 366))).isNotEmpty();
            assertThat(validator.validate(new SupplierConfirmationRequest("REJECTED", null, " ")))
                    .extracting(v -> v.getPropertyPath().toString()).containsExactly("note");
            assertThat(validator.validate(new SaveSupplierRequest("SUP-API", "Supplier", null, null, null, null,
                    "ACTIVE", 30, 7, "API", "http://example.com")))
                    .extracting(v -> v.getPropertyPath().toString()).contains("apiEndpoint");
        }
    }
    private SaveSupplierRequest request(String email, String phone, String tax, Integer terms, Integer lead) {
        return new SaveSupplierRequest("SUP-1", "Supplier", null, email, phone, tax, "ACTIVE", terms, lead, "EMAIL", null);
    }
}
