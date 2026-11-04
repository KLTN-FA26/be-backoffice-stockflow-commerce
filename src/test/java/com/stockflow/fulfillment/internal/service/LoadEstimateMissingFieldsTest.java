package com.stockflow.fulfillment.internal.service;

import com.stockflow.inventory.api.PackagingSpec;
import com.stockflow.inventory.api.RuleLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** LP2-3: a blocked line names only the item fields really missing. */
class LoadEstimateMissingFieldsTest {

    private static PackagingSpec missing(List<String> fields) {
        return new PackagingSpec("CUP", UUID.randomUUID(), 5, PackagingSpec.Status.MISSING, null, null, 0, 0, null,
                null, 0, List.of(), new PackagingSpec.SpecMissing("PACKAGING_MISSING", fields, "missing"));
    }

    private static PackagingSpec resolved(int length, String weight) {
        return new PackagingSpec("CUP", UUID.randomUUID(), 5, PackagingSpec.Status.RESOLVED, RuleLevel.ITEM_UNIT, "EA",
                1, 5, null, new PackagingSpec.CartonSpec(length, 200, 100, weight == null ? null : new BigDecimal(weight),
                true), 1, List.of(), null);
    }

    @Test
    @DisplayName("sizes present, weight empty: only weightKg")
    void onlyTheWeight() {
        assertThat(LoadEstimateService.missingFields(missing(List.of("weightKg")))).containsExactly("weightKg");
    }

    @Test
    @DisplayName("resolved with a zero side or weight: that field; a full carton: nothing")
    void zeroesOfAResolvedCarton() {
        assertThat(LoadEstimateService.missingFields(resolved(0, "1.5"))).containsExactly("lengthCm");
        assertThat(LoadEstimateService.missingFields(resolved(300, "0"))).containsExactly("weightKg");
        assertThat(LoadEstimateService.missingFields(resolved(300, "1.5"))).isEmpty();
    }

    @Test
    @DisplayName("a missing spec that names nothing: every unit field")
    void nothingNamed() {
        assertThat(LoadEstimateService.missingFields(missing(List.of())))
                .containsExactly("lengthCm", "widthCm", "heightCm", "weightKg");
    }
}
