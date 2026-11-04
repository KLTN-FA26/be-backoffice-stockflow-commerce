package com.stockflow.product.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.product.internal.domain.VariantStatus;
import com.stockflow.product.internal.service.ProductVariantService;
import com.stockflow.product.internal.service.ProductVariantService.Generated;
import com.stockflow.product.internal.service.ProductVariantService.NewVariant;
import com.stockflow.product.internal.service.ProductVariantService.VariantOption;
import com.stockflow.product.internal.service.ProductVariantService.VariantView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP edge of a product's variants: options reach the service, generation answers 201, permissions per handler. */
class ProductVariantControllerHttpTest {

    private static final UUID PRODUCT = UUID.fromString("0190a000-0000-7000-8000-000000000041");
    private static final UUID VARIANT = UUID.fromString("0190a000-0000-7000-8000-000000000042");
    private static final UUID ATTR = UUID.fromString("0190a000-0000-7000-8000-000000000043");
    private static final UUID VALUE = UUID.fromString("0190a000-0000-7000-8000-000000000044");

    private final ProductVariantService service = mock(ProductVariantService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new ProductVariantController(service, new ProductAdminWebMapper())).build();

    private static VariantView view() {
        return new VariantView(VARIANT, PRODUCT, "CUP-RED", "Cup / Red", VariantStatus.DRAFT, false,
                "COLOR=RED", 1, null, 0L, List.of(new VariantOption(ATTR, "COLOR", VALUE, "RED", "Red")));
    }

    @Test
    void postWithOptionValueIdsReachesAddWithThem() throws Exception {
        when(service.add(eq(PRODUCT), any())).thenReturn(view());

        mvc.perform(post("/api/v1/products/{id}/variants", PRODUCT).contentType("application/json")
                        .content("{\"optionValueIds\":[\"%s\"]}".formatted(VALUE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.options[0].attributeCode").value("COLOR"))
                .andExpect(jsonPath("$.data.options[0].valueId").value(VALUE.toString()))
                .andExpect(jsonPath("$.data.options[0].label").value("Red"));

        ArgumentCaptor<NewVariant> command = ArgumentCaptor.forClass(NewVariant.class);
        verify(service).add(eq(PRODUCT), command.capture());
        assertThat(command.getValue().optionValueIds()).containsExactly(VALUE);
        assertThat(command.getValue().sku()).isNull();
    }

    @Test
    void getCarriesTheInventoryItemIdOfItsSku() throws Exception {
        UUID item = UUID.fromString("0190a000-0000-7000-8000-000000000045");
        when(service.get(PRODUCT, VARIANT)).thenReturn(view());
        when(service.inventoryItemIds(any())).thenReturn(Map.of("CUP-RED", item));

        mvc.perform(get("/api/v1/products/{id}/variants/{v}", PRODUCT, VARIANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inventoryItemId").value(item.toString()));
    }

    @Test
    void postWithoutSkuIsNotABadRequest() throws Exception {
        when(service.add(eq(PRODUCT), any())).thenReturn(view());
        mvc.perform(post("/api/v1/products/{id}/variants", PRODUCT).contentType("application/json").content("{}"))
                .andExpect(status().isCreated());
    }

    @Test
    void aMalformedSkuIsABadRequest() throws Exception {
        mvc.perform(post("/api/v1/products/{id}/variants", PRODUCT).contentType("application/json")
                        .content("{\"sku\":\"-bad\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void generationWithAnEmptyBodyPassesNoRestrictionAndAnswersCreated() throws Exception {
        when(service.generate(eq(PRODUCT), any())).thenReturn(new Generated(List.of(view()), 2));

        mvc.perform(post("/api/v1/products/{id}/variants/generation", PRODUCT).contentType("application/json")
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.created[0].sku").value("CUP-RED"))
                .andExpect(jsonPath("$.data.skipped").value(2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<UUID, List<UUID>>> restriction = ArgumentCaptor.forClass(Map.class);
        verify(service).generate(eq(PRODUCT), restriction.capture());
        assertThat(restriction.getValue() == null || restriction.getValue().isEmpty()).isTrue();
    }

    @Test
    void generationWithoutABodyIsAccepted() throws Exception {
        when(service.generate(eq(PRODUCT), any())).thenReturn(new Generated(List.of(), 0));
        mvc.perform(post("/api/v1/products/{id}/variants/generation", PRODUCT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.skipped").value(0));
    }

    @Test
    void generationPassesTheRestriction() throws Exception {
        when(service.generate(eq(PRODUCT), any())).thenReturn(new Generated(List.of(), 0));
        mvc.perform(post("/api/v1/products/{id}/variants/generation", PRODUCT).contentType("application/json")
                        .content("{\"optionValueIds\":{\"%s\":[\"%s\"]}}".formatted(ATTR, VALUE)))
                .andExpect(status().isCreated());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<UUID, List<UUID>>> restriction = ArgumentCaptor.forClass(Map.class);
        verify(service).generate(eq(PRODUCT), restriction.capture());
        assertThat(restriction.getValue()).isEqualTo(Map.of(ATTR, List.of(VALUE)));
    }

    @Test
    void everyHandlerCarriesItsPermission() {
        Map<String, Action> expected = Map.of(
                "list", Action.READ, "get", Action.READ,
                "add", Action.CREATE, "generate", Action.CREATE,
                "update", Action.UPDATE, "block", Action.UPDATE,
                "activate", Action.APPROVE, "obsolete", Action.APPROVE);
        int guarded = 0;
        for (Method m : ProductVariantController.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()) {
                RequiresPermission p = m.getAnnotation(RequiresPermission.class);
                assertThat(p).as(m.getName()).isNotNull();
                assertThat(p.resource()).as(m.getName()).isEqualTo("product-variants");
                assertThat(p.action()).as(m.getName() + " action").isEqualTo(expected.get(m.getName()));
                guarded++;
            }
        }
        assertThat(guarded).isEqualTo(expected.size());
    }
}
