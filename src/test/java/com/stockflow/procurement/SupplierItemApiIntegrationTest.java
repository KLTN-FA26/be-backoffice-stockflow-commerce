package com.stockflow.procurement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.security.Role;
import com.stockflow.procurement.api.SaveSupplierCommand;
import com.stockflow.procurement.api.SaveSupplierItemCommand;
import com.stockflow.procurement.api.SupplierItemService;
import com.stockflow.procurement.api.SupplierService;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-77 supplier catalog REST API through the real filter chain and permission aspect: the five
 * routes, who may call them (the seeded {@code procurement-supplier-items} grants), and that a bad
 * body is refused with the offending field named.
 */
@SecuredIntegrationTest
class SupplierItemApiIntegrationTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired SupplierService suppliers;
    @Autowired SupplierItemService catalog;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private SecuredMvc mvc;
    private final UUID buyer = UUID.randomUUID();
    private UUID supplierId;
    private String sku;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        supplierId = supplier("Alpha supplier");
        sku = approvedSku();
    }

    private String approvedSku() {
        return tx.execute(s -> ReferenceRows.approvedProduct(entityManager).sku());
    }

    private UUID supplier(String name) {
        String code = "API-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        return suppliers.create(new SaveSupplierCommand(code, name, "Contact", "supplier@example.com",
                "+84901234567", null, "ACTIVE", 30, 7, "EMAIL", null, null, false, null)).supplierId();
    }

    private static String items(UUID supplier) {
        return "/api/v1/suppliers/" + supplier + "/items";
    }

    private static String body(String sku, String price, int moq, int packSize, Integer leadTimeDays,
                               boolean preferred, Long version) {
        return "{" + (sku == null ? "" : "\"sku\":\"" + sku + "\",")
                + "\"supplierSkuCode\":\"SUP-1\",\"price\":" + price + ",\"moq\":" + moq
                + ",\"packSize\":" + packSize
                + (leadTimeDays == null ? "" : ",\"leadTimeDays\":" + leadTimeDays)
                + ",\"preferred\":" + preferred
                + (version == null ? "" : ",\"version\":" + version) + "}";
    }

    private ResultActions as(Role role, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.asRole(role, buyer, request);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String content) {
        return request.contentType(MediaType.APPLICATION_JSON).content(content);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    @Test
    @DisplayName("a buyer lists a SKU, finds it on the page, reprices it and removes it")
    void procurementStaffMaintainsACatalog() throws Exception {
        JsonNode created = data(as(Role.PROCUREMENT_STAFF, json(post(items(supplierId)),
                body(sku.toLowerCase(Locale.ROOT), "1500", 10, 5, null, false, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sku").value(sku))
                .andExpect(jsonPath("$.data.currency").value("VND"))
                .andExpect(jsonPath("$.data.effectiveLeadTimeDays").value(7))
                .andExpect(jsonPath("$.data.version").value(0)));
        String itemId = created.get("supplierItemId").asText();

        as(Role.PROCUREMENT_STAFF, get(items(supplierId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].supplierItemId").value(itemId));

        as(Role.PROCUREMENT_STAFF, json(put(items(supplierId) + "/" + itemId),
                body(null, "1600", 10, 5, 3, true, 0L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(1600))
                .andExpect(jsonPath("$.data.preferred").value(true))
                .andExpect(jsonPath("$.data.effectiveLeadTimeDays").value(3))
                .andExpect(jsonPath("$.data.version").value(1));

        as(Role.PROCUREMENT_STAFF, delete(items(supplierId) + "/" + itemId).param("version", "1"))
                .andExpect(status().isNoContent());
        assertThat(catalog.list(supplierId, 0, 20, null).totalElements()).isZero();
    }

    @Test
    @DisplayName("an accountant, who holds no grant on the supplier catalog, can neither read nor edit it")
    void anAccountantHasNoCatalogAccess() throws Exception {
        as(Role.ACCOUNTANT, get(items(supplierId))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        as(Role.ACCOUNTANT, json(post(items(supplierId)), body(sku, "1500", 10, 5, null, false, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        assertThat(catalog.list(supplierId, 0, 20, null).totalElements()).isZero();
    }

    @Test
    @DisplayName("warehouse staff, who have no supplier-catalog grants, cannot read a catalog")
    void warehouseStaffCannotRead() throws Exception {
        as(Role.WAREHOUSE_STAFF, get(items(supplierId))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        as(Role.WAREHOUSE_STAFF, get("/api/v1/procurement/skus/" + sku + "/suppliers"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a request without a token is 401 on every route")
    void anonymousIsRefused() throws Exception {
        UUID row = UUID.randomUUID();
        mvc.anonymous(get(items(supplierId))).andExpect(status().isUnauthorized());
        mvc.anonymous(json(post(items(supplierId)), body(sku, "1500", 10, 5, null, false, null)))
                .andExpect(status().isUnauthorized());
        mvc.anonymous(json(put(items(supplierId) + "/" + row), body(null, "1500", 10, 5, null, false, 0L)))
                .andExpect(status().isUnauthorized());
        mvc.anonymous(delete(items(supplierId) + "/" + row).param("version", "0"))
                .andExpect(status().isUnauthorized());
        mvc.anonymous(get("/api/v1/procurement/skus/" + sku + "/suppliers")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a SKU's suppliers come preferred first, and the SKU may be written in lower case")
    void skuSuppliersListsThePreferredFirst() throws Exception {
        UUID beta = supplier("Beta supplier");
        var alphaItem = catalog.create(supplierId,
                new SaveSupplierItemCommand(sku, "A-1", new BigDecimal("1500"), 10, 5, null, false, null));
        var betaItem = catalog.create(beta,
                new SaveSupplierItemCommand(sku, "B-1", new BigDecimal("1400"), 10, 5, 3, true, null));

        as(Role.PROCUREMENT_STAFF, get("/api/v1/procurement/skus/" + sku.toLowerCase(Locale.ROOT) + "/suppliers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].supplierItemId").value(betaItem.supplierItemId().toString()))
                .andExpect(jsonPath("$.data[0].preferred").value(true))
                .andExpect(jsonPath("$.data[0].supplierName").value("Beta supplier"))
                .andExpect(jsonPath("$.data[0].supplierStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].effectiveLeadTimeDays").value(3))
                .andExpect(jsonPath("$.data[1].supplierItemId").value(alphaItem.supplierItemId().toString()))
                .andExpect(jsonPath("$.data[1].preferred").value(false));
    }

    @Test
    @DisplayName("a bad body is 400 VALIDATION_FAILED and names the field")
    void badBodiesAre400() throws Exception {
        refusedOn(json(post(items(supplierId)), body(sku, "0", 10, 5, null, false, null)), "price");
        refusedOn(json(post(items(supplierId)), body(sku, "-5", 10, 5, null, false, null)), "price");
        refusedOn(json(post(items(supplierId)), body(sku, "1.005", 10, 5, null, false, null)), "price");
        refusedOn(json(post(items(supplierId)), body(sku, "1500", 0, 5, null, false, null)), "moq");
        refusedOn(json(post(items(supplierId)), body(sku, "1500", 10, 0, null, false, null)), "packSize");
        refusedOn(json(post(items(supplierId)), body(sku, "1500", 10, 5, 366, false, null)), "leadTimeDays");
        refusedOn(json(post(items(supplierId)), body(null, "1500", 10, 5, null, false, null)), "sku");
        refusedOn(json(put(items(supplierId) + "/" + UUID.randomUUID()),
                body(null, "1500", 10, 5, null, false, null)), "version");
        assertThat(catalog.list(supplierId, 0, 20, null).totalElements()).isZero();
    }

    private void refusedOn(MockHttpServletRequestBuilder request, String field) throws Exception {
        as(Role.PROCUREMENT_STAFF, request).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[?(@.field=='" + field + "')]").isNotEmpty());
    }

    @Test
    @DisplayName("a malformed SKU in the path is 400 on sku, not a 500")
    void aMalformedSkuInThePathIs400() throws Exception {
        // A URI, not a template: get(String) would encode the % signs a second time.
        as(Role.PROCUREMENT_STAFF, get(URI.create("/api/v1/procurement/skus/%20bad%21/suppliers")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("sku"));
    }
}
