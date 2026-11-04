package com.stockflow.warehouse.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** The small values of the map model that carry a rule of their own. */
class MapValuesTest {

    @Test
    @DisplayName("a shelf with no pick face has none; any one face is enough (BR-08)")
    void pickFaces() {
        assertThat(PickFaces.NONE.hasAny()).isFalse();
        assertThat(new PickFaces(false, false, true, false).hasAny()).isTrue();
        assertThat(new PickFaces(true, true, true, true).hasAny()).isTrue();
    }

    @Test
    @DisplayName("every area type stores stock except NON_STORAGE (BR-11)")
    void storageAreaTypes() {
        assertThat(AreaType.NON_STORAGE.isStorage()).isFalse();
        assertThat(Arrays.stream(AreaType.values()).filter(t -> t != AreaType.NON_STORAGE))
                .isNotEmpty()
                .allMatch(AreaType::isStorage);
    }
}
