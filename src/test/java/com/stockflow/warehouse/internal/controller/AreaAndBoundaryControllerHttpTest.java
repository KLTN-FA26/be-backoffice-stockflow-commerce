package com.stockflow.warehouse.internal.controller;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.warehouse.internal.domain.AreaType;
import com.stockflow.warehouse.internal.domain.BoundaryType;
import com.stockflow.warehouse.internal.domain.DoorStatus;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.service.AreaCommands;
import com.stockflow.warehouse.internal.service.AreaLayoutService;
import com.stockflow.warehouse.internal.service.AreaSummary;
import com.stockflow.warehouse.internal.service.BoundaryCommands;
import com.stockflow.warehouse.internal.service.BoundaryLayoutService;
import com.stockflow.warehouse.internal.service.BoundarySummary;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP edge of areas and boundaries: the nested location becomes the domain's
 * {@code AreaDetails}, a {@code NON_STORAGE} area answers without location fields, a segment is
 * validated before the service, and deleting a boundary answers 200.
 */
class AreaAndBoundaryControllerHttpTest {

    private static final UUID WAREHOUSE = UUID.fromString("0190a000-0000-7000-8000-000000000001");
    private static final UUID BOUNDARY = UUID.fromString("0190a000-0000-7000-8000-000000000002");
    private static final Footprint FLOOR = new Footprint(new BigDecimal("20"), new BigDecimal("25"),
            new BigDecimal("5"), new BigDecimal("5"), 0);

    private final AreaLayoutService areas = mock(AreaLayoutService.class);
    private final BoundaryLayoutService boundaries = mock(BoundaryLayoutService.class);
    /**
     * Serialising as the application does ({@code spring.jackson.default-property-inclusion: non_null}),
     * so an absent field here is an absent field for the client too - the standalone default would
     * write {@code null}s.
     */
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new AreaController(areas, new AreaWebMapperImpl()),
                    new BoundaryController(boundaries, new BoundaryWebMapperImpl()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(
                    Jackson2ObjectMapperBuilder.json().serializationInclusion(JsonInclude.Include.NON_NULL).build()))
            .build();

    @Test
    void creatingAStorageAreaCarriesItsLocation() throws Exception {
        when(areas.createArea(any())).thenReturn(new AreaSummary(UUID.randomUUID(), WAREHOUSE, "RCV02",
                AreaType.RECEIVING, "Khu nhận 2", FLOOR, false, LocationStatus.ACTIVE, 0, UUID.randomUUID(),
                "HCM-RCV02", StorageClass.NORMAL, 500, null, false, true));

        mvc.perform(post("/api/v1/warehouses/{id}/areas", WAREHOUSE).contentType("application/json").content("""
                        {"code":"rcv02","type":"RECEIVING","name":"Khu nhận 2",
                         "footprint":{"x":20,"y":25,"width":5,"length":5,"rotation":0},
                         "location":{"capacityUnits":500,"pickable":false,"putawayTarget":true}}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.locationCode").value("HCM-RCV02"))
                .andExpect(jsonPath("$.data.footprint.width").value(5));

        ArgumentCaptor<AreaCommands.CreateArea> sent = ArgumentCaptor.forClass(AreaCommands.CreateArea.class);
        verify(areas).createArea(sent.capture());
        assertThat(sent.getValue().warehouseId()).isEqualTo(WAREHOUSE);
        assertThat(sent.getValue().details().footprint()).isEqualTo(FLOOR);
        assertThat(sent.getValue().details().storageClass()).isEqualTo(StorageClass.NORMAL);
        assertThat(sent.getValue().details().settings().capacityUnits()).isEqualTo(500);
    }

    @Test
    void aNonStorageAreaAnswersWithoutLocationFields() throws Exception {
        when(areas.createArea(any())).thenReturn(new AreaSummary(UUID.randomUUID(), WAREHOUSE, "OFFICE2",
                AreaType.NON_STORAGE, "Văn phòng 2", FLOOR, true, LocationStatus.ACTIVE, 0, null, null, null,
                null, null, null, null));

        mvc.perform(post("/api/v1/warehouses/{id}/areas", WAREHOUSE).contentType("application/json").content("""
                        {"code":"office2","type":"NON_STORAGE","name":"Văn phòng 2","obstacle":true,
                         "footprint":{"x":20,"y":25,"width":5,"length":5,"rotation":0}}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("OFFICE2"))
                .andExpect(jsonPath("$.data.locationId").doesNotExist())
                .andExpect(jsonPath("$.data.locationCode").doesNotExist())
                .andExpect(jsonPath("$.data.pickable").doesNotExist());

        ArgumentCaptor<AreaCommands.CreateArea> sent = ArgumentCaptor.forClass(AreaCommands.CreateArea.class);
        verify(areas).createArea(sent.capture());
        assertThat(sent.getValue().details().settings()).isNull();
    }

    @Test
    void updatingAnAreaCarriesTheVersionAndTheAreaId() throws Exception {
        UUID area = UUID.fromString("0190a000-0000-7000-8000-000000000003");
        when(areas.updateArea(any())).thenReturn(new AreaSummary(area, WAREHOUSE, "RCV01", AreaType.OVERFLOW,
                "Khu tràn", FLOOR, false, LocationStatus.ACTIVE, 8, UUID.randomUUID(), "HCM-RCV01",
                StorageClass.OVERSIZE, null, null, false, true));

        mvc.perform(put("/api/v1/areas/{id}", area).contentType("application/json").content("""
                        {"type":"OVERFLOW","name":"Khu tràn","version":7,
                         "footprint":{"x":20,"y":25,"width":5,"length":5,"rotation":0},
                         "location":{"storageClass":"OVERSIZE","pickable":false,"putawayTarget":true}}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(8));

        ArgumentCaptor<AreaCommands.UpdateArea> sent = ArgumentCaptor.forClass(AreaCommands.UpdateArea.class);
        verify(areas).updateArea(sent.capture());
        assertThat(sent.getValue().areaId()).isEqualTo(area);
        assertThat(sent.getValue().expectedVersion()).isEqualTo(7);
        assertThat(sent.getValue().details().storageClass()).isEqualTo(StorageClass.OVERSIZE);
    }

    @Test
    void changingAnAreasStatusSendsItToTheService() throws Exception {
        UUID area = UUID.fromString("0190a000-0000-7000-8000-000000000003");
        when(areas.changeAreaStatus(any(), any())).thenReturn(new AreaSummary(area, WAREHOUSE, "QC01",
                AreaType.QUARANTINE, "Khu QC", FLOOR, false, LocationStatus.BLOCKED, 4, UUID.randomUUID(),
                "HCM-QC01", StorageClass.NORMAL, null, null, false, false));

        mvc.perform(put("/api/v1/areas/{id}/status", area).contentType("application/json")
                        .content("{\"status\":\"BLOCKED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("BLOCKED"));

        verify(areas).changeAreaStatus(area, LocationStatus.BLOCKED);
    }

    @Test
    void aStatusChangeWithoutAStatusIsRejectedBeforeTheService() throws Exception {
        mvc.perform(put("/api/v1/areas/{id}/status", UUID.randomUUID()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(areas);
    }

    /** No location for a storage type: refused while building AreaDetails, before any transaction. */
    @Test
    void aStorageAreaWithoutALocationNeverReachesTheService() {
        assertThatThrownBy(() -> mvc.perform(post("/api/v1/warehouses/{id}/areas", WAREHOUSE)
                .contentType("application/json").content("""
                        {"code":"rcv02","type":"RECEIVING","name":"Khu nhận 2",
                         "footprint":{"x":20,"y":25,"width":5,"length":5,"rotation":0}}
                        """)))
                .rootCause().isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(areas);
    }

    @Test
    void creatingADoorBuildsItsSegment() throws Exception {
        when(boundaries.createBoundary(any())).thenReturn(new BoundarySummary(BOUNDARY, WAREHOUSE, BoundaryType.DOOR,
                new BigDecimal("60"), new BigDecimal("12"), new BigDecimal("60"), new BigDecimal("16"), true,
                DoorStatus.OPEN, 0));

        mvc.perform(post("/api/v1/warehouses/{id}/boundaries", WAREHOUSE).contentType("application/json").content("""
                        {"type":"DOOR","startX":60,"startY":12,"endX":60,"endY":16,
                         "passable":true,"operationalStatus":"OPEN"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.operationalStatus").value("OPEN"));

        ArgumentCaptor<BoundaryCommands.CreateBoundary> sent =
                ArgumentCaptor.forClass(BoundaryCommands.CreateBoundary.class);
        verify(boundaries).createBoundary(sent.capture());
        assertThat(sent.getValue().segment().endY()).isEqualByComparingTo("16");
        assertThat(sent.getValue().operationalStatus()).isEqualTo(DoorStatus.OPEN);
    }

    @Test
    void aZeroLengthBoundaryNeverReachesTheService() {
        assertThatThrownBy(() -> mvc.perform(post("/api/v1/warehouses/{id}/boundaries", WAREHOUSE)
                .contentType("application/json").content("""
                        {"type":"WALL","startX":5,"startY":5,"endX":5,"endY":5}
                        """)))
                .rootCause().isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(boundaries);
    }

    @Test
    void deletingABoundaryCarriesTheVersionAndAnswers200() throws Exception {
        mvc.perform(delete("/api/v1/boundaries/{id}", BOUNDARY).param("version", "3")).andExpect(status().isOk());

        verify(boundaries).deleteBoundary(BOUNDARY, 3);
    }

    /** A delete cannot be undone, so it is never sent without saying which version it removes. */
    @Test
    void aDeleteWithoutAVersionIsRejectedBeforeTheService() throws Exception {
        mvc.perform(delete("/api/v1/boundaries/{id}", BOUNDARY)).andExpect(status().isBadRequest());
        verifyNoInteractions(boundaries);
    }
}
