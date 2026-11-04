package com.stockflow.warehouse.internal.controller;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.web.GlobalExceptionHandler;
import com.stockflow.warehouse.internal.domain.LabelSelection;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.domain.StorageLocationKind;
import com.stockflow.common.label.LabelFormat;
import com.stockflow.warehouse.internal.service.LabelWords;
import com.stockflow.warehouse.internal.service.LocationLabel;
import com.stockflow.warehouse.internal.service.LocationLabelPdfRenderer;
import com.stockflow.warehouse.internal.service.LocationLabelService;
import com.stockflow.warehouse.internal.service.LocationLabelSheet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP edge of printing location labels (SCRUM-97): the body's shape, the PDF as an attachment,
 * and an error that stays a JSON envelope although the client asked for a PDF.
 */
class LocationLabelControllerHttpTest {

    private static final UUID WAREHOUSE = UUID.fromString("0190a000-0000-7000-8000-000000000001");
    private static final byte[] PDF = "%PDF-1.7 test".getBytes();
    private static final LocationLabelSheet SHEET = new LocationLabelSheet("HCM", "Kho Hồ Chí Minh",
            List.of(new LocationLabel("HCM-A01-1-A", StorageLocationKind.BIN, "A01", 1, "A", null,
                    StorageClass.OVERSIZE)));

    private final LocationLabelService service = mock(LocationLabelService.class);
    private final LocationLabelPdfRenderer renderer = mock(LocationLabelPdfRenderer.class);
    private final StaticMessageSource messages = new StaticMessageSource();
    /** 08:30 UTC is 15:30 in Ho Chi Minh City, which is what the file name must show. */
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T08:30:00Z"), ZoneOffset.UTC);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new LocationLabelController(service, renderer, new Messages(messages), clock))
            .setMessageConverters(new ByteArrayHttpMessageConverter(), new MappingJackson2HttpMessageConverter())
            .setControllerAdvice(new GlobalExceptionHandler(new Messages(messages), messages))
            .build();

    @BeforeEach
    void stubs() {
        when(service.sheetFor(eq(WAREHOUSE), any())).thenReturn(SHEET);
        when(renderer.render(eq(SHEET), any(), any())).thenReturn(PDF);
    }

    private ResultActions print(String body) throws Exception {
        return mvc.perform(post("/api/v1/warehouses/{id}/location-labels", WAREHOUSE)
                .accept(MediaType.APPLICATION_PDF, MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void returnsThePdfAsAnAttachment() throws Exception {
        print("{\"format\":\"THERMAL_ROLL\",\"shelfId\":\"0190a000-0000-7000-8000-0000000000aa\"}")
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"location-labels-HCM-20261014-1530.pdf\""))
                .andExpect(content().bytes(PDF));
        verify(renderer).render(SHEET, LabelFormat.THERMAL_ROLL, new LabelWords("Shelf", "Level", "Bin"));
    }

    @Test
    void noSelectorMeansTheWholeWarehouse() throws Exception {
        print("{\"format\":\"A4_SHEET\"}").andExpect(status().isOk());
        verify(service).sheetFor(WAREHOUSE, new LabelSelection.WholeWarehouse());
    }

    @Test
    void aShelfWithLevelsBecomesAShelfSelection() throws Exception {
        UUID shelf = UUID.randomUUID();
        print("{\"format\":\"A4_SHEET\",\"shelfId\":\"" + shelf + "\",\"levelIndexes\":[2,1,2]}")
                .andExpect(status().isOk());
        verify(service).sheetFor(WAREHOUSE, new LabelSelection.OfShelf(shelf, Set.of(1, 2)));
    }

    @Test
    void codesAreNormalisedBeforeTheService() throws Exception {
        print("{\"format\":\"A4_SHEET\",\"locationCodes\":[\"hcm-a01-1-a\"]}").andExpect(status().isOk());
        verify(service).sheetFor(WAREHOUSE, new LabelSelection.OfCodes(Set.of("HCM-A01-1-A")));
    }

    @Test
    void twoSelectorsAre400() throws Exception {
        print("{\"format\":\"A4_SHEET\",\"shelfId\":\"" + UUID.randomUUID() + "\",\"zoneId\":\""
                + UUID.randomUUID() + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    @Test
    void levelsWithoutAShelfAre400() throws Exception {
        print("{\"format\":\"A4_SHEET\",\"levelIndexes\":[1]}").andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void aMissingFormatIs400() throws Exception {
        print("{}").andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void aBadCodeIs400() throws Exception {
        print("{\"format\":\"A4_SHEET\",\"locationCodes\":[\"HCM_A01\"]}").andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void anEmptyCodeListIs400() throws Exception {
        print("{\"format\":\"A4_SHEET\",\"locationCodes\":[]}").andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void aServiceErrorUnderAcceptPdfIsJson() throws Exception {
        when(service.sheetFor(eq(WAREHOUSE), any()))
                .thenThrow(new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No storage location of this "
                        + "warehouse has the code HCM-ZZ9-1-A"));
        mvc.perform(post("/api/v1/warehouses/{id}/location-labels", WAREHOUSE)
                        .accept(MediaType.APPLICATION_PDF)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"format\":\"A4_SHEET\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorCode").value("LOCATION_NOT_FOUND"));
    }
}
