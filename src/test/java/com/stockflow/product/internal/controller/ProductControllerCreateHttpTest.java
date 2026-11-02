package com.stockflow.product.internal.controller;

import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.service.ProductDuplicateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP edge of product creation with axes (spec D2): the axes reach the command, and a bad axis is a 400. */
class ProductControllerCreateHttpTest {

    private static final UUID ATTR = UUID.fromString("0190a000-0000-7000-8000-000000000051");
    private static final UUID VALUE = UUID.fromString("0190a000-0000-7000-8000-000000000052");

    private final ProductService service = mock(ProductService.class);
    private final ProductDuplicateService duplicates = mock(ProductDuplicateService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ProductController(service, duplicates, new ProductWebMapperImpl())).build();

    /** What the service answers a create with; its content does not matter to these tests. */
    @BeforeEach
    void created() {
        Instant now = Instant.parse("2026-10-28T03:00:00Z");
        when(service.create(any())).thenReturn(new ProductSummary(UUID.randomUUID(), "CUP-12", "Ly", "Cup", "cup-12",
                null, null, null, null, null, null, TaxClass.STANDARD, ProductKind.STANDARD, ProductStatus.DRAFT, now,
                "u", now, "u", null, null, null, null, null, null, null, 0L, null, null, null));
    }

    @Test
    void axesReachTheCommand() throws Exception {
        mvc.perform(post("/api/v1/products").contentType("application/json").content("""
                        {"code":"cup-12","name":"Ly","nameEn":"Cup","taxClass":"STANDARD",
                         "axes":[{"attributeId":"%s","optionValueIds":["%s"]}]}""".formatted(ATTR, VALUE)))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateProductCommand> command = ArgumentCaptor.forClass(CreateProductCommand.class);
        verify(service).create(command.capture());
        assertThat(command.getValue().code()).isEqualTo("CUP-12");
        assertThat(command.getValue().axes()).singleElement().satisfies(axis -> {
            assertThat(axis.attributeId()).isEqualTo(ATTR);
            assertThat(axis.sortOrder()).isNull();
            assertThat(axis.optionValueIds()).containsExactly(VALUE);
        });
    }

    @Test
    void withoutAxesTheCommandHasNone() throws Exception {
        mvc.perform(post("/api/v1/products").contentType("application/json").content("""
                        {"code":"CUP-13","name":"Ly","nameEn":"Cup","taxClass":"STANDARD"}"""))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateProductCommand> command = ArgumentCaptor.forClass(CreateProductCommand.class);
        verify(service).create(command.capture());
        assertThat(command.getValue().axes()).isEmpty();
    }

    @Test
    void anAxisWithoutAttributeOrANullAxisIsABadRequest() throws Exception {
        mvc.perform(post("/api/v1/products").contentType("application/json").content("""
                        {"code":"CUP-15","name":"Ly","nameEn":"Cup","taxClass":"STANDARD",
                         "axes":[{"optionValueIds":["%s"]}]}""".formatted(VALUE)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/products").contentType("application/json").content("""
                        {"code":"CUP-16","name":"Ly","nameEn":"Cup","taxClass":"STANDARD","axes":[null]}"""))
                .andExpect(status().isBadRequest());
        verify(service, never()).create(any());
    }

    /** The product is saved by then: a failed duplicate read must not turn the create into a 5xx (SCRUM-88). */
    @Test
    void aFailedDuplicateLookupStillAnswersTheCreate() throws Exception {
        when(duplicates.possibleDuplicates(any(), any(), any(), any()))
                .thenThrow(new QueryTimeoutException("statement timeout"));

        mvc.perform(post("/api/v1/products").contentType("application/json").content("""
                        {"code":"CUP-17","name":"Ly","nameEn":"Cup","taxClass":"STANDARD"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("CUP-12"))
                .andExpect(jsonPath("$.data.possibleDuplicates").doesNotExist());
    }

    @Test
    void anAxisWithoutOptionsIsABadRequest() throws Exception {
        mvc.perform(post("/api/v1/products").contentType("application/json").content("""
                        {"code":"CUP-14","name":"Ly","nameEn":"Cup","taxClass":"STANDARD",
                         "axes":[{"attributeId":"%s","optionValueIds":[]}]}""".formatted(ATTR)))
                .andExpect(status().isBadRequest());
        verify(service, never()).create(any());
    }
}
