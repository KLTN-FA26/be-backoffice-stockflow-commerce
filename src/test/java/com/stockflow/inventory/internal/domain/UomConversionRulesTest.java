package com.stockflow.inventory.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionDraft;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UomConversionRulesTest {

    private static final UomConversion BASE = new UomConversion(UUID.randomUUID(), "CUP-1", "EACH", 1, true,
            BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, true, 0);

    @Test
    void aPackingUnitHasACodeAndAPositiveWholeFactor() {
        assertThatCode(() -> UomConversionRules.validatePacking(draft("CARTON", 1000, true))).doesNotThrowAnyException();
        refused(() -> UomConversionRules.validatePacking(draft("carton", 1000, true)), "unitOfMeasure");
        refused(() -> UomConversionRules.validatePacking(draft(null, 1000, true)), "unitOfMeasure");
        refused(() -> UomConversionRules.validatePacking(draft("CARTON", 0, true)), "factor");
        refused(() -> UomConversionRules.validatePacking(new UomConversionDraft("CARTON", 10, new BigDecimal("-1"),
                null, null, null, true)), "weightKg");
    }

    @Test
    void theBaseUnitIsNeverRetired() {
        assertThatCode(() -> UomConversionRules.validateBase(BASE, draft("IGNORED", 7, true)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> UomConversionRules.validateBase(BASE, draft("EACH", 1, false)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UOM_CONVERSION_IN_USE));
    }

    @Test
    void aRetiredUnitIsNotTheDefaultShippingUnit() {
        refused(() -> UomConversionRules.validatePacking(shipping("CARTON", 10, true, false)), "defaultShipping");
        assertThatCode(() -> UomConversionRules.validatePacking(shipping("CARTON", 10, true, true)))
                .doesNotThrowAnyException();
        assertThatCode(() -> UomConversionRules.validatePacking(shipping("CARTON", 10, false, false)))
                .doesNotThrowAnyException();
    }

    @Test
    void theBaseUnitMayBeTheDefaultShippingUnitOrNot() {
        assertThatCode(() -> UomConversionRules.validateBase(BASE, shipping("EACH", 1, true, true)))
                .doesNotThrowAnyException();
        assertThatCode(() -> UomConversionRules.validateBase(BASE, shipping("EACH", 1, false, true)))
                .doesNotThrowAnyException();
    }

    private static UomConversionDraft shipping(String code, int factor, boolean defaultShipping,
                                               boolean active) {
        return new UomConversionDraft(code, factor, null, null, null, null, active, defaultShipping);
    }

    private static UomConversionDraft draft(String code, int factor, boolean active) {
        return new UomConversionDraft(code, factor, null, null, null, null, active);
    }

    private static void refused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.fieldErrors()).extracting(FieldError::field).containsExactly(field);
        });
    }
}
