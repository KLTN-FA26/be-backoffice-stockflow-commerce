package com.stockflow.product.internal.domain;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.stockflow.common.error.ErrorCode.VALIDATION_FAILED;
import static com.stockflow.common.error.ErrorCode.VARIANT_MATRIX_TOO_LARGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VariantRulesTest {

    @Test
    void signaturesAreSortedByAttributeCode() {
        assertThat(VariantSignature.of(Map.of("SIZE", "12OZ", "COLOR", "CLEAR")).value()).isEqualTo("COLOR=CLEAR;SIZE=12OZ");
        assertThat(VariantSignature.defaultSignature().isDefault()).isTrue();
        assertThat(VariantSignature.ofSku(new Sku("CUP-1")).value()).isEqualTo("SKU=CUP-1");
    }

    @Test
    void skuCodesFollowAxisOrder() {
        assertThat(SkuCodeRule.generate("CUP-PET", List.of("12OZ", "CLEAR")).code()).isEqualTo("CUP-PET-12OZ-CLEAR");
        assertCode(() -> SkuCodeRule.generate("P".repeat(50), List.of("A".repeat(10), "B".repeat(10))), VALIDATION_FAILED);
    }

    @Test
    void theMatrixIsEnumeratedFirstAxisSlowest() {
        AxisOption oz12 = option();
        AxisOption oz16 = option();
        AxisOption clear = option();
        AxisOption red = option();
        List<VariantMatrix.Combination> all = VariantMatrix.of(List.of(List.of(oz12, oz16), List.of(clear, red)));
        assertThat(all).extracting(VariantMatrix.Combination::options).containsExactly(
                List.of(oz12, clear), List.of(oz12, red), List.of(oz16, clear), List.of(oz16, red));
    }

    @Test
    void existingCombinationsAreSkippedAndTheLimitAppliesAfter() {
        List<AxisOption> first = options(11);
        List<AxisOption> second = options(10);
        List<VariantMatrix.Combination> all = VariantMatrix.of(List.of(first, second));
        assertThat(all).hasSize(110);

        Set<VariantSignature> tenExisting = signatures(all.subList(0, 10));
        List<VariantMatrix.Combination> missing = VariantMatrix.missing(all, tenExisting, VariantRulesTest::sign);
        assertThat(missing).hasSize(100).doesNotContainAnyElementsOf(all.subList(0, 10));
        assertThat(missing.getFirst()).isEqualTo(all.get(10));

        Set<VariantSignature> nineExisting = signatures(all.subList(0, 9));
        assertCode(() -> VariantMatrix.missing(all, nineExisting, VariantRulesTest::sign), VARIANT_MATRIX_TOO_LARGE);
    }

    private static Set<VariantSignature> signatures(List<VariantMatrix.Combination> combinations) {
        Set<VariantSignature> result = new HashSet<>();
        combinations.forEach(c -> result.add(sign(c)));
        return result;
    }

    private static VariantSignature sign(VariantMatrix.Combination combination) {
        Map<String, String> codes = new java.util.HashMap<>();
        for (int i = 0; i < combination.options().size(); i++) {
            codes.put("AXIS" + i, combination.options().get(i).valueId().toString());
        }
        return VariantSignature.of(codes);
    }

    private static List<AxisOption> options(int n) {
        List<AxisOption> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(option());
        }
        return list;
    }

    private static AxisOption option() {
        return new AxisOption(Identifiers.newId(), Identifiers.newId());
    }

    private static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }
}
