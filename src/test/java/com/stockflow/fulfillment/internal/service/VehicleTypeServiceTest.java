package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.fulfillment.internal.entity.VehicleTypeJpaEntity;
import com.stockflow.fulfillment.internal.repository.VehicleTypeJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The duplicate-code race net of {@link VehicleTypeService#create}: two creates past the check, the constraint decides. */
class VehicleTypeServiceTest {

    private static final VehicleTypes.SaveVehicleType TRUCK = new VehicleTypes.SaveVehicleType("TRUCK-X", "Truck",
            "TRUCK", 4000, 1800, 1800, 0, null, null, new BigDecimal("2000"), 2, null, null, null);

    private final VehicleTypeJpaRepository repository = mock(VehicleTypeJpaRepository.class);
    private final VehicleTypeService service = new VehicleTypeService(repository);

    @Test
    @DisplayName("a unique violation on the code is 409 VEHICLE_TYPE_CODE_EXISTS")
    void uniqueViolationOnCodeIsConflict() {
        when(repository.findByCode("TRUCK-X")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("could not execute",
                new RuntimeException("ERROR: duplicate key value violates unique constraint \"uk_vehicle_type_code\"")));

        assertThatThrownBy(() -> service.create(TRUCK)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VEHICLE_TYPE_CODE_EXISTS));
    }

    @Test
    @DisplayName("any other integrity violation is not disguised")
    void otherViolationsPassThrough() {
        when(repository.findByCode("TRUCK-X")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("could not execute",
                new RuntimeException("ERROR: new row violates check constraint \"ck_vehicle_type_payload\"")));

        assertThatThrownBy(() -> service.create(TRUCK)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an update of a preset to another kind is refused before the table is touched")
    void presetKeepsItsKind() {
        VehicleTypeJpaEntity preset = new VehicleTypeJpaEntity(java.util.UUID.randomUUID(), "20DC");
        org.springframework.test.util.ReflectionTestUtils.setField(preset, "preset", true);
        preset.apply("20'", "CONTAINER", 5898, 2352, 2393, 150, null, null, new BigDecimal("28200"), 10, null, "ACTIVE");
        when(repository.findById(preset.getId())).thenReturn(Optional.of(preset));

        VehicleTypes.SaveVehicleType asTruck = new VehicleTypes.SaveVehicleType(null, "20'", "TRUCK", 5898, 2352, 2393,
                150, null, null, new BigDecimal("28200"), 10, null, null, 0L);
        assertThatThrownBy(() -> service.update(preset.getId(), asTruck)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.fieldErrors()).extracting(f -> f.code()).containsExactly("VEHICLE_TYPE_KIND_IMMUTABLE"));
    }
}
