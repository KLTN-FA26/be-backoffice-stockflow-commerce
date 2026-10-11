package com.stockflow.warehouse.internal.controller;

import com.stockflow.warehouse.internal.domain.MapUnit;
import com.stockflow.warehouse.internal.domain.WarehouseStatus;
import com.stockflow.warehouse.internal.service.CreateZoneCommand;
import com.stockflow.warehouse.internal.service.RegisterWarehouseCommand;
import com.stockflow.warehouse.internal.service.UpdateWarehouseCommand;
import com.stockflow.warehouse.internal.service.WarehouseLayoutService;
import com.stockflow.warehouse.internal.service.WarehouseSummary;
import com.stockflow.warehouse.internal.service.ZoneSummary;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP edge of warehouses and zones: request validation before the service is reached, and the
 * mapping both ways. The rules behind it are tested on the aggregates and in
 * {@code WarehouseLayoutServiceIntegrationTest}.
 */
class WarehouseControllerHttpTest {

    private static final UUID WAREHOUSE_ID = UUID.fromString("0190a000-0000-7000-8000-000000000001");

    private final WarehouseLayoutService layout = mock(WarehouseLayoutService.class);
    private final WarehouseWebMapper mapper = new WarehouseWebMapperImpl();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new WarehouseController(layout, mapper), new ZoneController(layout, mapper)).build();

    private static WarehouseSummary hanoi(long version) {
        return new WarehouseSummary(WAREHOUSE_ID, "HN", "Kho Hà Nội", "1 Phố Huế", null, MapUnit.M,
                new BigDecimal("40.000"), new BigDecimal("25.000"), WarehouseStatus.ACTIVE, version, null);
    }

    @Test
    void registeringReturns201WithTheStoredWarehouse() throws Exception {
        when(layout.register(any())).thenReturn(hanoi(0));

        mvc.perform(post("/api/v1/warehouses").contentType("application/json").content("""
                        {"prefix":"hn","name":"Kho Hà Nội","address":"1 Phố Huế","mapUnit":"M",
                         "mapWidth":40,"mapHeight":25}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.prefix").value("HN"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        ArgumentCaptor<RegisterWarehouseCommand> sent = ArgumentCaptor.forClass(RegisterWarehouseCommand.class);
        verify(layout).register(sent.capture());
        assertThat(sent.getValue().prefix()).isEqualTo("hn");
        assertThat(sent.getValue().mapUnit()).isEqualTo(MapUnit.M);
    }

    @Test
    void aPrefixWithAHyphenIsRejectedBeforeTheService() throws Exception {
        mvc.perform(post("/api/v1/warehouses").contentType("application/json").content("""
                        {"prefix":"H-N","name":"Kho","address":"1 Phố Huế","mapUnit":"M",
                         "mapWidth":40,"mapHeight":25}
                        """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(layout);
    }

    @Test
    void aMapMeasureFinerThanAMillimetreIsRejectedBeforeTheService() throws Exception {
        mvc.perform(post("/api/v1/warehouses").contentType("application/json").content("""
                        {"prefix":"HN","name":"Kho","address":"1 Phố Huế","mapUnit":"M",
                         "mapWidth":40.0001,"mapHeight":25}
                        """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(layout);
    }

    @Test
    void anUpdateCarriesThePathIdAndTheVersionItWasBasedOn() throws Exception {
        when(layout.update(any())).thenReturn(hanoi(4));

        mvc.perform(put("/api/v1/warehouses/{id}", WAREHOUSE_ID).contentType("application/json").content("""
                        {"name":"Kho Hà Nội","address":"1 Phố Huế","mapWidth":40,"mapHeight":25,"version":3}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(4));

        ArgumentCaptor<UpdateWarehouseCommand> sent = ArgumentCaptor.forClass(UpdateWarehouseCommand.class);
        verify(layout).update(sent.capture());
        assertThat(sent.getValue().warehouseId()).isEqualTo(WAREHOUSE_ID);
        assertThat(sent.getValue().expectedVersion()).isEqualTo(3L);
    }

    @Test
    void creatingAZoneTakesTheWarehouseFromThePath() throws Exception {
        when(layout.createZone(any())).thenReturn(
                new ZoneSummary(UUID.randomUUID(), WAREHOUSE_ID, "Khu A", "#22C55E", 0));

        mvc.perform(post("/api/v1/warehouses/{id}/zones", WAREHOUSE_ID).contentType("application/json")
                        .content("""
                                {"name":"Khu A","color":"#22c55e"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.color").value("#22C55E"));

        ArgumentCaptor<CreateZoneCommand> sent = ArgumentCaptor.forClass(CreateZoneCommand.class);
        verify(layout).createZone(sent.capture());
        assertThat(sent.getValue().warehouseId()).isEqualTo(WAREHOUSE_ID);
    }

    @Test
    void aZoneColourThatIsNotRrggbbIsRejectedBeforeTheService() throws Exception {
        mvc.perform(post("/api/v1/warehouses/{id}/zones", WAREHOUSE_ID).contentType("application/json")
                        .content("""
                                {"name":"Khu A","color":"red"}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(layout);
    }
}
