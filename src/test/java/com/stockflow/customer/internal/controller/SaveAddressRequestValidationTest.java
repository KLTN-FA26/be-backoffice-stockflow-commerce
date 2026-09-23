package com.stockflow.customer.internal.controller;

import com.stockflow.customer.internal.controller.dto.SaveAddressRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SaveAddressRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsInvalidPhoneAndMissingPostMergerAdministrativeFieldsBeforeSaving() {
        SaveAddressRequest request = new SaveAddressRequest("SHIPPING", "Minh", "not-a-phone",
                "12 Nguyen Hue", null, "", "Ben Nghe", "79", "Ho Chi Minh City", "VN", null,
                false, null);

        Set<String> fields = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(fields).contains("phone", "wardCode");
    }

    @Test
    void rejectsPreMergerOrForeignAddressShapes() {
        SaveAddressRequest request = new SaveAddressRequest("DELIVERY", "Minh", "0901234567",
                "12 Nguyen Hue", null, "26734", "Ben Nghe", "79", "Ho Chi Minh City", "US", null,
                false, null);

        Set<String> fields = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(fields).contains("type", "countryCode");
    }
}
