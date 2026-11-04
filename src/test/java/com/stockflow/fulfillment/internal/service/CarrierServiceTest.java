package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The carrier catalogue of kltn-docs 09 §3: codes are unique, a change is checked against the version read. */
class CarrierServiceTest {

    private final CarrierJpaRepository repository = mock(CarrierJpaRepository.class);
    private final CarrierService service = new CarrierService(repository);

    private static Carriers.SaveCarrier save(String code, String name, String status, Long version) {
        return new Carriers.SaveCarrier(code, name, "  An  ", " 0900000000 ", status, version);
    }

    private CarrierJpaEntity existing(String code, String status) {
        CarrierJpaEntity row = new CarrierJpaEntity(UUID.randomUUID(), code);
        row.apply(code, "GHN Express", null, null, status);
        when(repository.findById(row.getId())).thenReturn(Optional.of(row));
        return row;
    }

    private BusinessException refusal(Runnable call) {
        return FulfillmentFixtures.refusal(call);
    }

    @Test
    @DisplayName("a new carrier is saved with its code upper-cased, its text trimmed and ACTIVE by default")
    void createNormalisesAndDefaultsToActive() {
        when(repository.findByCode("GHN-1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        Carriers.CarrierView view = service.create(save(" ghn-1 ", "  GHN Express ", null, null));

        assertThat(view.code()).isEqualTo("GHN-1");
        assertThat(view.name()).isEqualTo("GHN Express");
        assertThat(view.contactName()).isEqualTo("An");
        assertThat(view.contactPhone()).isEqualTo("0900000000");
        assertThat(view.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a blank contact is stored as nothing")
    void blankContactBecomesNull() {
        when(repository.findByCode("GHN-1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        Carriers.CarrierView view = service.create(new Carriers.SaveCarrier("GHN-1", "GHN", "  ", "", "inactive", null));

        assertThat(view.contactName()).isNull();
        assertThat(view.contactPhone()).isNull();
        assertThat(view.status()).isEqualTo("INACTIVE");
    }

    @Test
    @DisplayName("a code already taken is CARRIER_CODE_EXISTS and nothing is saved")
    void duplicateCodeIsRefused() {
        when(repository.findByCode("GHN-1")).thenReturn(Optional.of(new CarrierJpaEntity(UUID.randomUUID(), "GHN-1")));

        assertThat(refusal(() -> service.create(save("ghn-1", "GHN", null, null))).errorCode())
                .isEqualTo(ErrorCode.CARRIER_CODE_EXISTS);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a code of one character, with a space or starting with a dash is VALIDATION_FAILED on 'code'")
    void malformedCodesAreRefused() {
        for (String bad : new String[] {null, "", "A", "-AB", "AB CD", "A".repeat(33)}) {
            BusinessException thrown = refusal(() -> service.create(save(bad, "GHN", null, null)));
            assertThat(thrown.errorCode()).as("code %s", bad).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("code");
        }
    }

    @Test
    @DisplayName("a name that is blank or over 120 characters is VALIDATION_FAILED on 'name'")
    void nameIsRequiredAndBounded() {
        when(repository.findByCode("GHN-1")).thenReturn(Optional.empty());
        for (String bad : new String[] {null, "   ", "x".repeat(121)}) {
            BusinessException thrown = refusal(() -> service.create(save("GHN-1", bad, null, null)));
            assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("name");
        }
    }

    @Test
    @DisplayName("a status other than ACTIVE or INACTIVE is VALIDATION_FAILED on 'status'")
    void statusIsOneOfTwo() {
        when(repository.findByCode("GHN-1")).thenReturn(Optional.empty());

        BusinessException thrown = refusal(() -> service.create(save("GHN-1", "GHN", "PAUSED", null)));

        assertThat(thrown.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(thrown.fieldErrors()).extracting(f -> f.field()).containsExactly("status");
    }

    @Test
    @DisplayName("an update without the version read, or with a stale one, is OPTIMISTIC_LOCK")
    void updateChecksTheVersion() {
        CarrierJpaEntity row = existing("GHN-1", "ACTIVE");
        ReflectionTestUtils.setField(row, "version", 3L);

        assertThat(refusal(() -> service.update(row.getId(), save("GHN-1", "GHN", null, null))).errorCode())
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        assertThat(refusal(() -> service.update(row.getId(), save("GHN-1", "GHN", null, 2L))).errorCode())
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("updating a carrier that does not exist is CARRIER_NOT_FOUND")
    void updateOfUnknownCarrier() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThat(refusal(() -> service.update(id, save("GHN-1", "GHN", null, 0L))).errorCode())
                .isEqualTo(ErrorCode.CARRIER_NOT_FOUND);
        assertThat(refusal(() -> service.get(id)).errorCode()).isEqualTo(ErrorCode.CARRIER_NOT_FOUND);
    }

    @Test
    @DisplayName("an update to a code another carrier holds is CARRIER_CODE_EXISTS; to its own code it is fine")
    void updateCodeMustStayUnique() {
        CarrierJpaEntity row = existing("GHN-1", "ACTIVE");
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        CarrierJpaEntity other = new CarrierJpaEntity(UUID.randomUUID(), "VTP");
        when(repository.findByCode("VTP")).thenReturn(Optional.of(other));
        when(repository.findByCode("GHN-1")).thenReturn(Optional.of(row));

        assertThat(refusal(() -> service.update(row.getId(), save("VTP", "GHN", null, 0L))).errorCode())
                .isEqualTo(ErrorCode.CARRIER_CODE_EXISTS);
        assertThat(service.update(row.getId(), save("ghn-1", "GHN Express 2", null, 0L)).name())
                .isEqualTo("GHN Express 2");
    }

    @Test
    @DisplayName("an update without a status keeps the one the carrier has")
    void updateKeepsStatusWhenNoneGiven() {
        CarrierJpaEntity row = existing("GHN-1", "INACTIVE");
        when(repository.findByCode("GHN-1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        assertThat(service.update(row.getId(), save("GHN-1", "GHN", null, 0L)).status()).isEqualTo("INACTIVE");
        assertThat(service.update(row.getId(), save("GHN-1", "GHN", "active", 0L)).status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a list of carriers is mapped to views with the page numbers")
    void listMapsRows() {
        CarrierJpaEntity row = new CarrierJpaEntity(UUID.randomUUID(), "GHN-1");
        row.apply("GHN-1", "GHN", null, null, "ACTIVE");
        when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), org.springframework.data.domain.PageRequest.of(0, 20), 1));

        var page = service.list("active", " ghn ", 0, 20);

        assertThat(page.items()).extracting(Carriers.CarrierView::code).containsExactly("GHN-1");
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("filtering the list by an unknown status is VALIDATION_FAILED")
    void listRefusesUnknownStatus() {
        assertThat(refusal(() -> service.list("PAUSED", null, 0, 20)).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
