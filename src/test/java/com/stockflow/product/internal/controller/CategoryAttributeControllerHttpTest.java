package com.stockflow.product.internal.controller;

import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.service.CategoryAttributeService;
import com.stockflow.product.internal.service.CategoryAttributeService.CategoryAttributeEntry;
import com.stockflow.product.internal.service.CategoryAttributeService.CategoryAttributeView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;

import static com.stockflow.product.internal.domain.AttributeDataType.COLOR;
import static com.stockflow.product.internal.domain.AttributeRole.VARIANT_AXIS;
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

/** The HTTP edge of a category's attribute set: typed entries reach the service, malformed input stops before it. */
class CategoryAttributeControllerHttpTest {

    private static final UUID CATEGORY = UUID.fromString("0190a000-0000-7000-8000-000000000021");
    private static final UUID ATTRIBUTE = UUID.fromString("0190a000-0000-7000-8000-000000000022");

    private final CategoryAttributeService service = mock(CategoryAttributeService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new CategoryAttributeController(service, new AttributeWebMapper())).build();

    private static List<CategoryAttributeView> views() {
        return List.of(new CategoryAttributeView(ATTRIBUTE, "COLOR", "Màu sắc", COLOR, VARIANT_AXIS, true, 2));
    }

    @Test
    void aValidPutReachesTheServiceWithTheMappedEntries() throws Exception {
        when(service.replace(eq(CATEGORY), any())).thenReturn(views());

        mvc.perform(put("/api/v1/categories/{id}/attributes", CATEGORY).contentType("application/json").content("""
                        {"attributes":[{"attributeId":"%s","defaultRole":"VARIANT_AXIS","required":true}]}"""
                        .formatted(ATTRIBUTE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].attributeId").value(ATTRIBUTE.toString()))
                .andExpect(jsonPath("$.data[0].attributeCode").value("COLOR"))
                .andExpect(jsonPath("$.data[0].dataType").value("COLOR"))
                .andExpect(jsonPath("$.data[0].defaultRole").value("VARIANT_AXIS"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CategoryAttributeEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(service).replace(eq(CATEGORY), entries.capture());
        assertThat(entries.getValue()).containsExactly(new CategoryAttributeEntry(ATTRIBUTE, VARIANT_AXIS, true, 0));
    }

    @Test
    void getListsTheSet() throws Exception {
        when(service.list(CATEGORY)).thenReturn(views());
        mvc.perform(get("/api/v1/categories/{id}/attributes", CATEGORY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sortOrder").value(2));
    }

    @Test
    void anEntryWithoutAnAttributeIdIsABadRequest() throws Exception {
        mvc.perform(put("/api/v1/categories/{id}/attributes", CATEGORY).contentType("application/json").content("""
                        {"attributes":[{"defaultRole":"VARIANT_AXIS"}]}"""))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void everyHandlerIsGuardedByProductAttributes() {
        int guarded = 0;
        for (Method m : CategoryAttributeController.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()) {
                assertThat(m.getAnnotation(RequiresPermission.class)).as(m.getName()).isNotNull();
                assertThat(m.getAnnotation(RequiresPermission.class).resource()).isEqualTo("product-attributes");
                guarded++;
            }
        }
        assertThat(guarded).isEqualTo(2);
    }
}
