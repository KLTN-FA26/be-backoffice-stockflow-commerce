package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.domain.StorageLocationKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The api's enums copy the domain's so that {@code api} imports nothing from {@code internal}; the
 * lookup converts between them by name. A constant added on one side only would fail there, at
 * runtime, on the first location that has it - this fails first.
 */
class StorageLocationViewTest {

    @Test
    @DisplayName("the api's enums have exactly the domain's constants")
    void enumsMatchTheDomain() {
        assertThat(names(StorageLocationView.Kind.values())).isEqualTo(names(StorageLocationKind.values()));
        assertThat(names(StorageLocationView.StorageClass.values())).isEqualTo(names(StorageClass.values()));
        assertThat(names(StorageLocationView.Status.values())).isEqualTo(names(LocationStatus.values()));
    }

    private static String[] names(Enum<?>[] constants) {
        return Arrays.stream(constants).map(Enum::name).sorted().toArray(String[]::new);
    }
}
