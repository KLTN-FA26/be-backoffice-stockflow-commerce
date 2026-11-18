package com.stockflow.warehouse.internal.controller;

import com.stockflow.common.i18n.Messages;
import com.stockflow.common.web.GlobalExceptionHandler;
import com.stockflow.warehouse.internal.service.ShelfLayoutService;
import com.stockflow.warehouse.spi.BinOccupants;
import com.stockflow.warehouse.spi.LocationOccupants;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the warehouse map's screen receives when a change would leave cartons without their place (SCRUM-668): a 409
 * whose {@code details} name the cartons, so the screen can list them and the warehouse manager can move them first.
 * The refusals are built by the very helpers the services throw through ({@link LocationOccupants#refuse},
 * kltn-docs 06 BR-04, and {@link BinOccupants#refuse}, BR-23), so the shape tested here is the shape sent.
 */
class PlaceRefusalHttpTest {

    private static final UUID SHELF = UUID.fromString("0190a000-0000-7000-8000-000000000002");
    private static final UUID LEVEL = UUID.fromString("0190a000-0000-7000-8000-000000000003");
    private static final UUID BIN = UUID.fromString("0190a000-0000-7000-8000-000000000004");

    private final ShelfLayoutService shelves = mock(ShelfLayoutService.class);
    private final StaticMessageSource messages = new StaticMessageSource();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new ShelfController(shelves, new ShelfWebMapperImpl()))
            .setControllerAdvice(new GlobalExceptionHandler(new Messages(messages), messages))
            .build();

    @Test
    void deactivatingABinWithCartonsAnswers409WithTheRealTotalAndTheListedCartons() throws Exception {
        when(shelves.changeBinStatus(eq(SHELF), eq(LEVEL), eq(BIN), any())).thenAnswer(call -> {
            LocationOccupants.refuse(new LocationOccupants.Cartons(51, List.of(
                    new LocationOccupants.Carton("HCM-A01-2-B", "LPN-0001"),
                    new LocationOccupants.Carton("HCM-A01-2-B", "LPN-0002"))), "bin HCM-A01-2-B");
            return null;
        });

        mvc.perform(put("/api/v1/shelves/{s}/levels/{l}/bins/{b}/status", SHELF, LEVEL, BIN)
                        .contentType("application/json").content("""
                                {"status":"INACTIVE"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("LOCATION_HOLDS_CARTONS"))
                .andExpect(jsonPath("$.details.total").value(51))
                .andExpect(jsonPath("$.details.cartons.length()").value(2))
                .andExpect(jsonPath("$.details.cartons[0].locationCode").value("HCM-A01-2-B"))
                .andExpect(jsonPath("$.details.cartons[0].lpn").value("LPN-0001"));
    }

    @Test
    void resizingABinUnderItsCartonsAnswers409NamingEachCartonAndTheShortVolume() throws Exception {
        doAnswer(call -> {
            BinOccupants.refuse(List.of(
                    new BinOccupants.Stranded("HCM-B01-2-A", "LPN-0003", BinOccupants.Why.DOES_NOT_FIT),
                    new BinOccupants.Stranded("HCM-B01-2-A", null, BinOccupants.Why.OVER_VOLUME)),
                    "the bin's new size");
            return null;
        }).when(shelves).updateBin(any());

        mvc.perform(put("/api/v1/shelves/{s}/levels/{l}/bins/{b}", SHELF, LEVEL, BIN)
                        .contentType("application/json").content("""
                                {"type":"PALLET","maxWeight":250,"pickable":true,"putawayTarget":true,
                                 "footprint":{"x":0,"y":0,"width":0.35,"length":1.2,"rotation":0}}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CARTONS_WOULD_NOT_FIT"))
                .andExpect(jsonPath("$.details.stranded.length()").value(2))
                .andExpect(jsonPath("$.details.stranded[0].lpn").value("LPN-0003"))
                .andExpect(jsonPath("$.details.stranded[0].why").value("DOES_NOT_FIT"))
                .andExpect(jsonPath("$.details.stranded[1].lpn").value(""))
                .andExpect(jsonPath("$.details.stranded[1].why").value("OVER_VOLUME"));
    }
}
