package com.stockflow.payment.internal.controller;

import com.stockflow.payment.internal.controller.dto.RecordTransferRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** A statement reference with line breaks could forge log lines; it is refused before it gets anywhere. */
class RecordTransferRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<String> invalid(String reference, String currency) {
        return validator.validate(new RecordTransferRequest(UUID.randomUUID(), reference, new BigDecimal("1000"),
                        currency, LocalDate.now().minusDays(1), null, null)).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("statement references as banks write them pass; control characters and odd currencies do not")
    void referenceAndCurrency() {
        assertThat(invalid("FT26283123456", "VND")).isEmpty();
        assertThat(invalid("MBVCB.1234/ABC Nguyen Van A-ck", null)).isEmpty();
        assertThat(invalid("FT1\r\n2026-10-11 INFO forged line", "VND")).contains("reference");
        assertThat(invalid("FT1\u0007", "VND")).contains("reference");
        assertThat(invalid("FT1", "vnd")).contains("currency");
        assertThat(invalid("FT1", "VND\n")).contains("currency");
    }
}
