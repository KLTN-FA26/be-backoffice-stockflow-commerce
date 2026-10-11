package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PurchaseOrderValidationTest {
    @ParameterizedTest
    @CsvSource({"VND,1000.5", "USD,10.555", "JPY,0.01"})
    void fractionalPricesAreRejectedAtTheEditableLine(String currency, String amount) {
        var po = request(currency, new CreatePOLineRequest("CHAIR-01", "Chair", 1, new BigDecimal(amount), null));
        assertThat(fields(po)).contains("lines[0].unitPrice");
    }

    @ParameterizedTest
    @CsvSource({"VND,1000.00", "USD,10.55", "KWD,10.55"})
    void exactAmountsAreAccepted(String currency, String amount) {
        assertThat(fields(request(currency, new CreatePOLineRequest("CHAIR-01", "Chair", 1,
                new BigDecimal(amount), null)))).isEmpty();
    }

    @Test
    void unknownCurrencyAndOversizedLinesExposeFieldPaths() {
        assertThat(fields(request("XYZ", new CreatePOLineRequest("bad sku", "x".repeat(301),
                1_000_001, new BigDecimal("10000000000000000"), null))))
                .contains("currency", "lines[0].sku", "lines[0].description", "lines[0].quantityOrdered",
                        "lines[0].unitPrice");
        assertThat(fields(request("XXX", new CreatePOLineRequest("CHAIR-01", "Chair", 1, BigDecimal.ONE, null))))
                .contains("currency");
    }

    @Test
    void missingValuesDoNotReachTheService() {
        assertThat(fields(request(null, new CreatePOLineRequest("CHAIR-01", "Chair", 1, null, null))))
                .contains("currency", "lines[0].unitPrice");
        assertThat(fields(new CreatePurchaseOrderRequest(UUID.randomUUID(), UUID.randomUUID(), "VND", null, null,
                Arrays.asList((CreatePOLineRequest) null)))).isNotEmpty();
    }

    @Test
    void extremeExponentsAreFieldErrorsWithoutAttemptingAnUnboundedTotal() {
        for (String amount : List.of("1E+1000000000", "1E-1000000000")) {
            assertThat(fields(request("USD", new CreatePOLineRequest("CHAIR-01", "Chair", 1,
                    new BigDecimal(amount), null)))).contains("lines[0].unitPrice");
        }
    }

    @Test
    void totalIncludesEveryLineAndRespectsTheDatabaseBoundary() {
        var maximum = new CreatePOLineRequest("CHAIR-01", "Chair", 1, new BigDecimal("9999999999999999.99"), null);
        assertThat(fields(request("USD", maximum))).isEmpty();
        assertThat(fields(new CreatePurchaseOrderRequest(UUID.randomUUID(), UUID.randomUUID(), "USD", null, null,
                List.of(maximum, new CreatePOLineRequest("TABLE-01", "Table", 1, new BigDecimal("0.01"), null)))))
                .contains("lines");
    }

    private CreatePurchaseOrderRequest request(String currency, CreatePOLineRequest line) {
        return new CreatePurchaseOrderRequest(UUID.randomUUID(), UUID.randomUUID(), currency, null, null, List.of(line));
    }

    private List<String> fields(CreatePurchaseOrderRequest request) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(request).stream()
                    .map(v -> v.getPropertyPath().toString()).toList();
        }
    }
}
