package com.stockflow.warehouse.internal.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.internal.domain.AreaType;
import com.stockflow.warehouse.internal.domain.BinType;
import com.stockflow.warehouse.internal.domain.BoundaryType;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.MapUnit;
import com.stockflow.warehouse.internal.domain.PickFaces;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;
import com.stockflow.warehouse.internal.service.BoundarySummary;
import com.stockflow.warehouse.internal.service.WarehouseLayout;
import com.stockflow.warehouse.internal.service.WarehouseLayoutService;
import com.stockflow.warehouse.internal.service.ZoneSummary;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP edge of reading the map: the layout nests levels and bins under shelves with their
 * effective status, a {@code NON_STORAGE} area answers without location fields or {@code usable},
 * and a code nobody has is {@code LOCATION_NOT_FOUND}.
 */
class LayoutControllerHttpTest {

    private static final UUID WAREHOUSE = UUID.fromString("0190a000-0000-7000-8000-000000000001");
    private static final Footprint SHELF = new Footprint(new BigDecimal("5"), new BigDecimal("5"),
            new BigDecimal("10"), new BigDecimal("1.2"), 0);
    private static final Footprint BIN = new Footprint(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5"),
            new BigDecimal("1.2"), 0);
    private static final Footprint FLOOR = new Footprint(new BigDecimal("2"), new BigDecimal("30"),
            new BigDecimal("10"), new BigDecimal("8"), 0);

    private final WarehouseLayoutService layout = mock(WarehouseLayoutService.class);
    private final WarehouseService locations = mock(WarehouseService.class);
    /** Serialising as the application does ({@code non_null}); see {@code AreaAndBoundaryControllerHttpTest}. */
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new LayoutController(layout, locations, new LayoutWebMapperImpl()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(
                    Jackson2ObjectMapperBuilder.json().serializationInclusion(JsonInclude.Include.NON_NULL).build()))
            .build();

    @Test
    void theLayoutNestsBinsUnderShelvesWithTheirEffectiveStatus() throws Exception {
        var bin = new WarehouseLayout.Bin(UUID.randomUUID(), "A", null, BIN, BinType.PALLET, null, UUID.randomUUID(),
                "HCM-A01-1-A", StorageClass.OVERSIZE, 40, new BigDecimal("250"), true, true, LocationStatus.ACTIVE,
                LocationStatus.MAINTENANCE, false);
        var shelf = new WarehouseLayout.Shelf(UUID.randomUUID(), null, "A01", "Kệ A01", null, SHELF, true,
                new PickFaces(false, false, true, false), StorageClass.OVERSIZE, LocationStatus.MAINTENANCE,
                LocationStatus.MAINTENANCE, 3, List.of(new WarehouseLayout.Level(UUID.randomUUID(), 1,
                BigDecimal.ZERO, null, null, List.of(bin))));
        var office = new WarehouseLayout.Area(UUID.randomUUID(), "OFFICE", AreaType.NON_STORAGE, "Văn phòng kho",
                FLOOR, true, LocationStatus.ACTIVE, LocationStatus.ACTIVE, null, 0, null, null, null, null, null,
                null, null);
        when(layout.layoutOf(WAREHOUSE)).thenReturn(new WarehouseLayout(
                new WarehouseLayout.Warehouse(WAREHOUSE, "HCM", "Kho HCM", MapUnit.M, new BigDecimal("60"),
                        new BigDecimal("40"), WarehouseStatus.ACTIVE, 2),
                List.of(new ZoneSummary(UUID.randomUUID(), WAREHOUSE, "Khu A", "#4F81BD", 0)),
                List.of(shelf), List.of(office),
                List.of(new BoundarySummary(UUID.randomUUID(), WAREHOUSE, BoundaryType.WALL, BigDecimal.ZERO,
                        BigDecimal.ZERO, new BigDecimal("60"), BigDecimal.ZERO, false, null, 0))));

        mvc.perform(get("/api/v1/warehouses/{id}/layout", WAREHOUSE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warehouse.mapWidth").value(60))
                .andExpect(jsonPath("$.data.zones[0].color").value("#4F81BD"))
                .andExpect(jsonPath("$.data.shelves[0].pickFaces.south").value(true))
                .andExpect(jsonPath("$.data.shelves[0].effectiveStatus").value("MAINTENANCE"))
                .andExpect(jsonPath("$.data.shelves[0].levels[0].bins[0].locationCode").value("HCM-A01-1-A"))
                .andExpect(jsonPath("$.data.shelves[0].levels[0].bins[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.shelves[0].levels[0].bins[0].effectiveStatus").value("MAINTENANCE"))
                .andExpect(jsonPath("$.data.shelves[0].levels[0].bins[0].usable").value(false))
                .andExpect(jsonPath("$.data.areas[0].code").value("OFFICE"))
                .andExpect(jsonPath("$.data.areas[0].usable").doesNotExist())
                .andExpect(jsonPath("$.data.areas[0].locationCode").doesNotExist())
                .andExpect(jsonPath("$.data.boundaries[0].operationalStatus").doesNotExist());
    }

    @Test
    void aLocationIsFoundByItsCode() throws Exception {
        when(locations.findLocation("hcm-a01-2-b")).thenReturn(Optional.of(new StorageLocationView(
                UUID.randomUUID(), StorageLocationView.Kind.BIN, WAREHOUSE, "HCM-A01-2-B",
                StorageLocationView.StorageClass.OVERSIZE, StorageLocationView.Status.ACTIVE,
                StorageLocationView.Status.ACTIVE, true)));

        mvc.perform(get("/api/v1/locations/{code}", "hcm-a01-2-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("BIN"))
                .andExpect(jsonPath("$.data.locationCode").value("HCM-A01-2-B"))
                .andExpect(jsonPath("$.data.storageClass").value("OVERSIZE"))
                .andExpect(jsonPath("$.data.usable").value(true));
    }

    @Test
    void anUnknownCodeIsLocationNotFound() {
        when(locations.findLocation("HCM-OFFICE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mvc.perform(get("/api/v1/locations/{code}", "HCM-OFFICE")))
                .rootCause().isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.LOCATION_NOT_FOUND);
    }
}
