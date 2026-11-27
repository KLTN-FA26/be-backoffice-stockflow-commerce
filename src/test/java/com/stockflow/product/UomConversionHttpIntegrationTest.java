package com.stockflow.product;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.service.ProductVariantService;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The uom-conversion endpoints with the default shipping flag (SCRUM-287 PR P): request and response carry it,
 * marking a second unit moves the mark instead of answering 409, and the PUT needs it spelled out.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@AutoConfigureMockMvc
class UomConversionHttpIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProductService products;
    @Autowired ProductVariantService variants;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean UploadInspection inspection;
    @MockitoBean CurrentUserProvider users;

    private String base;

    @BeforeEach
    void product() {
        UUID editor = tx.execute(s -> ReferenceRows.user(entityManager));
        when(users.current()).thenReturn(Optional.of(new CurrentUser(editor, "test-" + editor, Set.of(), Set.of(),
                DataScope.ALL, Set.of())));
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        ProductSummary created = products.create(new CreateProductCommand("UOM-" + suffix, "Ly giấy 12oz",
                "Paper cup 12oz", null, null, "Ly", null, null, TaxClass.STANDARD,
                List.of()));
        UUID variantId = variants.list(created.productId(), 0, 1).items().getFirst().id();
        base = "/api/v1/products/" + created.productId() + "/variants/" + variantId + "/uom-conversions";
    }

    private static String create(String code, int factor, Boolean defaultShipping) {
        return "{\"unitOfMeasure\":\"" + code + "\",\"factor\":" + factor
                + ",\"weightKg\":5,\"lengthCm\":50,\"widthCm\":40,\"heightCm\":30"
                + (defaultShipping == null ? "" : ",\"defaultShipping\":" + defaultShipping) + "}";
    }

    private static String update(long version, String code, int factor, Boolean defaultShipping) {
        return "{\"version\":" + version + ",\"unitOfMeasure\":\"" + code + "\",\"factor\":" + factor
                + ",\"weightKg\":5,\"lengthCm\":50,\"widthCm\":40,\"heightCm\":30,"
                + "\"active\":true" + (defaultShipping == null ? "" : ",\"defaultShipping\":" + defaultShipping) + "}";
    }

    /**
     * There is no default shipping mark (V20261229000100): the SKU ships in its one active carton. The response keeps
     * {@code defaultShipping} for old clients, true on the active carton.
     */
    @Test
    void theActiveCartonReadsAsShippedInAndASecondActiveUnitIsRefused() throws Exception {
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(create("BOX", 10, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.defaultShipping").value(true))
                .andExpect(jsonPath("$.data.active").value(true));
        // 01 BR-03: the SKU has one active carton, and a second one is refused.
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(create("CARTON", 50, true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("UOM_CARTON_ALREADY_DECLARED"));
        mvc.perform(get(base))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.unitOfMeasure=='BOX')].defaultShipping").value(true))
                .andExpect(jsonPath("$.data.items[?(@.unitOfMeasure=='CARTON')]").isEmpty())
                // The item's own unit (EACH) has no row and is not listed (V20261226000200).
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[?(@.base==true)]").isEmpty());
    }

    /** An old client still sends defaultShipping, a new one does not: both are accepted, and the value is ignored. */
    @Test
    void putNeedsNoMarkAndIgnoresOne() throws Exception {
        String box = mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(create("BOX", 10, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = box.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        long version = mvc.perform(get(base + "/" + id)).andReturn().getResponse().getContentAsString()
                .transform(body -> Long.parseLong(body.replaceAll(".*\"version\":(\\d+).*", "$1")));

        mvc.perform(put(base + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(update(version, "BOX", 10, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultShipping").value(true));
        mvc.perform(put(base + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(update(version + 1, "BOX", 10, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(version + 2))
                .andExpect(jsonPath("$.data.defaultShipping").value(true));
    }

    @Test
    void aRetiredUnitReadsAsNotShippedIn() throws Exception {
        String box = mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(create("BOX", 10, null)))
                .andReturn().getResponse().getContentAsString();
        String id = box.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        String retired = update(0, "BOX", 10, true).replace("\"active\":true", "\"active\":false");
        mvc.perform(put(base + "/" + id).contentType(MediaType.APPLICATION_JSON).content(retired))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.defaultShipping").value(false));
    }

    /** The default purchase unit is gone; a client that still sends it is not refused, and the response says the constant (base: true). */
    @Test
    void defaultPurchaseInTheBodyIsIgnored() throws Exception {
        String body = create("CARTON", 50, null).replace("\"heightCm\":30", "\"heightCm\":30,\"defaultPurchase\":true");
        String created = mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.defaultPurchase").value(false))
                .andReturn().getResponse().getContentAsString();
        String id = created.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        String put = update(0, "CARTON", 50, false).replace("\"active\":true", "\"active\":true,\"defaultPurchase\":true");
        mvc.perform(put(base + "/" + id).contentType(MediaType.APPLICATION_JSON).content(put))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultPurchase").value(false));
    }
}
