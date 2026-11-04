package com.stockflow.product.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.domain.AxisSpec;
import com.stockflow.product.internal.domain.DescriptiveSpec;
import com.stockflow.product.internal.service.ProductAttributeService;
import com.stockflow.product.internal.service.ProductAttributeService.ProductAttributesView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP edge of a product's axes and descriptive attributes: typed specs reach the service, malformed input does not. */
class ProductAttributeControllerHttpTest {

    private static final UUID PRODUCT = UUID.fromString("0190a000-0000-7000-8000-000000000031");
    private static final UUID ATTR_A = UUID.fromString("0190a000-0000-7000-8000-000000000032");
    private static final UUID ATTR_B = UUID.fromString("0190a000-0000-7000-8000-000000000033");
    private static final UUID VALUE = UUID.fromString("0190a000-0000-7000-8000-000000000034");

    private final ProductAttributeService service = mock(ProductAttributeService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ProductAttributeController(service, new ProductAttributeWebMapper())).build();

    private static ProductAttributesView empty() {
        return new ProductAttributesView(PRODUCT, List.of(), List.of());
    }

    @Test
    void putAxesReachesTheServiceWithMappedSpecsAndPositionalSortOrder() throws Exception {
        when(service.replaceAxes(eq(PRODUCT), any())).thenReturn(empty());

        mvc.perform(put("/api/v1/products/{id}/axes", PRODUCT).contentType("application/json").content("""
                        {"axes":[{"attributeId":"%s","optionValueIds":["%s"]},
                                 {"attributeId":"%s","sortOrder":7,"optionValueIds":["%s"]}]}"""
                        .formatted(ATTR_A, VALUE, ATTR_B, VALUE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(PRODUCT.toString()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AxisSpec>> specs = ArgumentCaptor.forClass(List.class);
        verify(service).replaceAxes(eq(PRODUCT), specs.capture());
        assertThat(specs.getValue()).containsExactly(
                new AxisSpec(ATTR_A, 0, List.of(VALUE)), new AxisSpec(ATTR_B, 7, List.of(VALUE)));
    }

    @Test
    void anAxisWithoutOptionsIsABadRequest() throws Exception {
        mvc.perform(put("/api/v1/products/{id}/axes", PRODUCT).contentType("application/json").content("""
                        {"axes":[{"attributeId":"%s","optionValueIds":[]}]}""".formatted(ATTR_A)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void putDescriptivesMapsEntries() throws Exception {
        when(service.replaceDescriptives(eq(PRODUCT), any())).thenReturn(empty());

        mvc.perform(put("/api/v1/products/{id}/descriptive-attributes", PRODUCT)
                        .contentType("application/json").content("""
                                {"attributes":[{"attributeId":"%s","text":"hello"}]}""".formatted(ATTR_A)))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DescriptiveSpec>> specs = ArgumentCaptor.forClass(List.class);
        verify(service).replaceDescriptives(eq(PRODUCT), specs.capture());
        assertThat(specs.getValue()).containsExactly(new DescriptiveSpec(ATTR_A, 0, null, "hello", null, null));
    }

    @Test
    void aNumberWithFifteenIntegerDigitsIsABadRequest() throws Exception {
        mvc.perform(put("/api/v1/products/{id}/descriptive-attributes", PRODUCT)
                        .contentType("application/json").content("""
                                {"attributes":[{"attributeId":"%s","number":123456789012345}]}""".formatted(ATTR_A)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void getReturnsTheView() throws Exception {
        when(service.get(PRODUCT)).thenReturn(empty());
        mvc.perform(get("/api/v1/products/{id}/attributes", PRODUCT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.axes").isEmpty());
    }

    @Test
    void everyHandlerCarriesItsPermission() {
        int guarded = 0;
        for (Method m : ProductAttributeController.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()) {
                RequiresPermission p = m.getAnnotation(RequiresPermission.class);
                assertThat(p).as(m.getName()).isNotNull();
                assertThat(p.resource()).as(m.getName())
                        .isEqualTo(m.getName().equals("replaceDescriptives") ? "product-products" : "product-variants");
                assertThat(p.action()).as(m.getName() + " action")
                        .isEqualTo(m.getName().equals("get") ? Action.READ : Action.UPDATE);
                guarded++;
            }
        }
        assertThat(guarded).isEqualTo(3);
    }
}
