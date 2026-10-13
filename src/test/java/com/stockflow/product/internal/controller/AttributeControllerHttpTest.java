package com.stockflow.product.internal.controller;

import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.domain.AttributeDetails;
import com.stockflow.product.internal.service.AttributeDictionaryService;
import com.stockflow.product.internal.service.AttributeDictionaryService.AttributeValueView;
import com.stockflow.product.internal.service.AttributeDictionaryService.AttributeView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;

import static com.stockflow.product.internal.domain.AttributeDataType.SELECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP edge of the attribute dictionary: typed fields reach the service, malformed input stops before it. */
class AttributeControllerHttpTest {

    private static final UUID ATTRIBUTE = UUID.fromString("0190a000-0000-7000-8000-000000000011");
    private static final UUID VALUE = UUID.fromString("0190a000-0000-7000-8000-000000000012");

    private final AttributeDictionaryService service = mock(AttributeDictionaryService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new AttributeController(service, new AttributeWebMapper())).build();

    private static AttributeView view() {
        return new AttributeView(ATTRIBUTE, "CAPACITY", "Dung tích", SELECT, "oz", true, false, 0, true, 1,
                List.of(new AttributeValueView(VALUE, "12OZ", "12 oz", null, null, 0, true, 0)));
    }

    @Test
    void creatingAnAttributePassesTheTypedFields() throws Exception {
        when(service.create(eq("capacity"), eq(SELECT), any())).thenReturn(view());

        mvc.perform(post("/api/v1/attributes").contentType("application/json").content("""
                        {"code":"capacity","dataType":"SELECT","name":"Dung tích","unit":"oz","filterable":true}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("CAPACITY"))
                .andExpect(jsonPath("$.data.attributeId").value(ATTRIBUTE.toString()))
                .andExpect(jsonPath("$.data.values[0].code").value("12OZ"))
                .andExpect(jsonPath("$.data.values[0].valueId").value(VALUE.toString()));

        var details = ArgumentCaptor.forClass(AttributeDetails.class);
        verify(service).create(eq("capacity"), eq(SELECT), details.capture());
        assertThat(details.getValue().unit()).isEqualTo("oz");
        assertThat(details.getValue().filterable()).isTrue();
        assertThat(details.getValue().sortOrder()).isZero();
    }

    @Test
    void anUnknownDataTypeIsABadRequest() throws Exception {
        mvc.perform(post("/api/v1/attributes").contentType("application/json").content("""
                        {"code":"capacity","dataType":"DATE","name":"Dung tích"}"""))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void aValueNumberBeyondTheColumnPrecisionIsABadRequest() throws Exception {
        mvc.perform(post("/api/v1/attributes/{id}/values", ATTRIBUTE).contentType("application/json").content("""
                        {"code":"BIG","label":"Big","valueNumber":123456789012345.5}"""))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void updateWithoutAVersionIsABadRequest() throws Exception {
        mvc.perform(put("/api/v1/attributes/{id}", ATTRIBUTE).contentType("application/json").content("""
                        {"name":"Dung tích","active":true}"""))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void deleteRequiresTheVersion() throws Exception {
        mvc.perform(delete("/api/v1/attributes/{id}", ATTRIBUTE)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);

        mvc.perform(delete("/api/v1/attributes/{id}", ATTRIBUTE).param("version", "3"))
                .andExpect(status().isOk());
        verify(service).delete(ATTRIBUTE, 3L);
    }

    @Test
    void removingAValueTakesTheValueVersionAndReturnsTheAttribute() throws Exception {
        when(service.deleteValue(ATTRIBUTE, VALUE, 4L)).thenReturn(view());

        mvc.perform(delete("/api/v1/attributes/{a}/values/{v}", ATTRIBUTE, VALUE).param("version", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attributeId").value(ATTRIBUTE.toString()));
        mvc.perform(delete("/api/v1/attributes/{a}/values/{v}", ATTRIBUTE, VALUE))
                .andExpect(status().isBadRequest());
    }

    @Test
    void everyHandlerIsGuardedByProductAttributes() {
        int guarded = 0;
        for (Method m : AttributeController.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()) {
                assertThat(m.getAnnotation(RequiresPermission.class)).as(m.getName()).isNotNull();
                assertThat(m.getAnnotation(RequiresPermission.class).resource()).isEqualTo("product-attributes");
                guarded++;
            }
        }
        assertThat(guarded).isEqualTo(8);
    }
}
