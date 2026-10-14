package com.stockflow.product.internal.controller;

import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.ProductService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP edge of product creation with axes (spec D2): the axes reach the command, and a bad axis is a 400. */
class ProductControllerCreateHttpTest {

    private static final UUID ATTR = UUID.fromString("0190a000-0000-7000-8000-000000000051");
    private static final UUID VALUE = UUID.fromString("0190a000-0000-7000-8000-000000000052");

    private final ProductService service = mock(ProductService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ProductController(service, new ProductWebMapperImpl())).build();

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
    void anAxisWithoutOptionsIsABadRequest() throws Exception {
        mvc.perform(post("/api/v1/products").contentType("application/json").content("""
                        {"code":"CUP-14","name":"Ly","nameEn":"Cup","taxClass":"STANDARD",
                         "axes":[{"attributeId":"%s","optionValueIds":[]}]}""".formatted(ATTR)))
                .andExpect(status().isBadRequest());
        verify(service, never()).create(any());
    }
}
