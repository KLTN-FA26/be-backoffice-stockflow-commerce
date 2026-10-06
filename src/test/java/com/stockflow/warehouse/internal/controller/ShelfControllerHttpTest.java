package com.stockflow.warehouse.internal.controller;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.warehouse.internal.domain.BinNamingScheme;
import com.stockflow.warehouse.internal.domain.BinType;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.PickFaces;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.service.ShelfCommands;
import com.stockflow.warehouse.internal.service.ShelfLayoutService;
import com.stockflow.warehouse.internal.service.ShelfSummary;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP edge of shelves: nested footprint and pick faces become domain values, location settings
 * reach the command, and malformed geometry stops before the service.
 */
class ShelfControllerHttpTest {

    private static final UUID WAREHOUSE = UUID.fromString("0190a000-0000-7000-8000-000000000001");
    private static final UUID SHELF = UUID.fromString("0190a000-0000-7000-8000-000000000002");
    private static final UUID LEVEL = UUID.fromString("0190a000-0000-7000-8000-000000000003");
    private static final Footprint FOOTPRINT = new Footprint(new BigDecimal("5"), new BigDecimal("5"),
            new BigDecimal("10"), new BigDecimal("1.2"), 90);

    private final ShelfLayoutService shelves = mock(ShelfLayoutService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ShelfController(shelves, new ShelfWebMapperImpl())).build();

    @Test
    void creatingAShelfTurnsTheNestedShapesIntoDomainValues() throws Exception {
        when(shelves.createShelf(any())).thenReturn(new ShelfSummary(SHELF, WAREHOUSE, null, "A01", "Kệ A01", null,
                FOOTPRINT, true, new PickFaces(false, false, true, false), StorageClass.NORMAL,
                LocationStatus.ACTIVE, 0, List.of()));

        mvc.perform(post("/api/v1/warehouses/{id}/shelves", WAREHOUSE).contentType("application/json").content("""
                        {"code":"a01","name":"Kệ A01","obstacle":true,"defaultStorageClass":"NORMAL",
                         "footprint":{"x":5,"y":5,"width":10,"length":1.2,"rotation":90},
                         "pickFaces":{"north":false,"east":false,"south":true,"west":false}}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.footprint.rotation").value(90))
                .andExpect(jsonPath("$.data.pickFaces.south").value(true));

        ArgumentCaptor<ShelfCommands.CreateShelf> sent = ArgumentCaptor.forClass(ShelfCommands.CreateShelf.class);
        verify(shelves).createShelf(sent.capture());
        assertThat(sent.getValue().warehouseId()).isEqualTo(WAREHOUSE);
        assertThat(sent.getValue().footprint()).isEqualTo(FOOTPRINT);
        assertThat(sent.getValue().pickFaces().south()).isTrue();
    }

    @Test
    void aShelfWithoutAFootprintIsRejectedBeforeTheService() throws Exception {
        mvc.perform(post("/api/v1/warehouses/{id}/shelves", WAREHOUSE).contentType("application/json").content("""
                        {"code":"A01","name":"Kệ A01","defaultStorageClass":"NORMAL",
                         "pickFaces":{"north":false,"east":false,"south":true,"west":false}}
                        """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(shelves);
    }

    /** Not a quarter turn: refused while building the Footprint, so no transaction ever starts. */
    @Test
    void aRotationThatIsNotAQuarterTurnNeverReachesTheService() {
        assertThatThrownBy(() -> mvc.perform(post("/api/v1/warehouses/{id}/shelves", WAREHOUSE)
                .contentType("application/json").content("""
                        {"code":"A01","name":"Kệ A01","defaultStorageClass":"NORMAL",
                         "footprint":{"x":5,"y":5,"width":10,"length":1.2,"rotation":45},
                         "pickFaces":{"north":false,"east":false,"south":true,"west":false}}
                        """)))
                .rootCause().isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(shelves);
    }

    @Test
    void addingABinCarriesItsLocationSettings() throws Exception {
        when(shelves.addBin(any())).thenReturn(new ShelfSummary.BinEntry(UUID.randomUUID(), "03", null,
                new Footprint(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, 0), BinType.PALLET,
                null, UUID.randomUUID(), "HCM-A01-2-03", StorageClass.NORMAL, 40, new BigDecimal("250"), true,
                true, LocationStatus.ACTIVE));

        mvc.perform(post("/api/v1/shelves/{s}/levels/{l}/bins", SHELF, LEVEL).contentType("application/json")
                        .content("""
                                {"code":"03","type":"PALLET","capacityUnits":40,"maxWeight":250,
                                 "pickable":true,"putawayTarget":true,
                                 "footprint":{"x":0,"y":0,"width":1,"length":1,"rotation":0}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.locationCode").value("HCM-A01-2-03"));

        ArgumentCaptor<ShelfCommands.AddBin> sent = ArgumentCaptor.forClass(ShelfCommands.AddBin.class);
        verify(shelves).addBin(sent.capture());
        assertThat(sent.getValue().levelId()).isEqualTo(LEVEL);
        assertThat(sent.getValue().settings().capacityUnits()).isEqualTo(40);
        assertThat(sent.getValue().settings().pickable()).isTrue();
    }

    @Test
    void generatingBinsDefaultsToSequentialAndAnswersOneFlatListAcrossLevels() throws Exception {
        UUID second = UUID.fromString("0190a000-0000-7000-8000-000000000004");
        Footprint cell = new Footprint(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5"), new BigDecimal("1.2"), 0);
        when(shelves.generateBins(any())).thenReturn(List.of(
                level(LEVEL, 1, bin("01", "HCM-A01-1-01", cell)),
                level(second, 2, bin("01", "HCM-A01-2-01", cell))));

        mvc.perform(post("/api/v1/shelves/{s}/bin-generation", SHELF).contentType("application/json").content("""
                        {"levelIds":["%s","%s"],"rowCount":1,"columnCount":2,
                         "defaults":{"type":"PALLET","capacityUnits":40,"pickable":true,"putawayTarget":true}}
                        """.formatted(LEVEL, second)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].levelId").value(second.toString()))
                .andExpect(jsonPath("$.data[1].locationCode").value("HCM-A01-2-01"))
                .andExpect(jsonPath("$.data[1].footprint.width").value(5));

        ArgumentCaptor<ShelfCommands.GenerateBins> sent = ArgumentCaptor.forClass(ShelfCommands.GenerateBins.class);
        verify(shelves).generateBins(sent.capture());
        assertThat(sent.getValue().shelfId()).isEqualTo(SHELF);
        assertThat(sent.getValue().levelIds()).containsExactly(LEVEL, second);
        assertThat(sent.getValue().scheme()).isEqualTo(BinNamingScheme.SEQUENTIAL);
        assertThat(sent.getValue().defaults().type()).isEqualTo(BinType.PALLET);
        assertThat(sent.getValue().defaults().settings().capacityUnits()).isEqualTo(40);
    }

    @Test
    void generatingBinsWithoutLevelsOrWithZeroColumnsIsRejectedBeforeTheService() throws Exception {
        mvc.perform(post("/api/v1/shelves/{s}/bin-generation", SHELF).contentType("application/json").content("""
                        {"levelIds":[],"rowCount":1,"columnCount":2,"defaults":{"type":"PALLET"}}
                        """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/shelves/{s}/bin-generation", SHELF).contentType("application/json").content("""
                        {"levelIds":["%s"],"rowCount":1,"columnCount":0,"defaults":{"type":"PALLET"}}
                        """.formatted(LEVEL)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(shelves);
    }

    private static ShelfSummary.Level level(UUID id, int index, ShelfSummary.BinEntry bin) {
        return new ShelfSummary.Level(id, index, null, null, null, List.of(bin));
    }

    private static ShelfSummary.BinEntry bin(String code, String locationCode, Footprint footprint) {
        return new ShelfSummary.BinEntry(UUID.randomUUID(), code, null, footprint, BinType.PALLET, null,
                UUID.randomUUID(), locationCode, StorageClass.NORMAL, 40, null, true, true, LocationStatus.ACTIVE);
    }
}
