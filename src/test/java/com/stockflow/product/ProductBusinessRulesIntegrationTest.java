package com.stockflow.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.internal.service.ProductTaxonomyService;
import com.stockflow.product.internal.service.ProductTaxonomyService.CategoryDetails;
import com.stockflow.support.ApprovalLimitFixtures;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ProductFixtures;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * SCRUM-73 over HTTP, against Postgres: a brand or category retired by deactivation is not newly chosen
 * (SCRUM-87), and a product that looks like another one is reported, never refused (SCRUM-88). Every refusal
 * is asserted by its exact {@code errorCode} and field.
 *
 * <p>Same class annotations, properties and mock as {@code ProductLifecycleIntegrationTest}, so the
 * application context is shared.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000"})
class ProductBusinessRulesIntegrationTest {

    private static final String JSON = "application/json";
    private static final String PRODUCTS = "/api/v1/products";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ObjectMapper json;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ProductTaxonomyService taxonomy;
    @Autowired ProductService products;

    private UUID editorUser;
    private String suffix;
    private ProductFixtures.Taxonomy tax;

    @BeforeEach
    void setUp() {
        editorUser = tx.execute(s -> ReferenceRows.user(entityManager));
        suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        tax = ProductFixtures.taxonomy(taxonomy, suffix);
    }

    /** ECOMMERCE_ADMIN holds every product-products permission. */
    private String editor() {
        String token = UUID.randomUUID().toString();
        when(decoder.decode(token)).thenReturn(Jwt.withTokenValue(token).header("alg", "RS256")
                .subject(editorUser.toString()).claim("roles", List.of("ECOMMERCE_ADMIN")).build());
        return "Bearer " + token;
    }

    private ResultActions send(String method, String path, String body) throws Exception {
        var request = switch (method) {
            case "POST" -> post(path);
            case "PUT" -> put(path);
            default -> get(path);
        };
        request.header("Authorization", editor());
        if (body != null) {
            request.contentType(JSON).content(body);
        }
        return mvc.perform(request);
    }

    /** The whole body, with the HTTP status checked first and the body in the failure message. */
    private JsonNode answer(ResultActions result, int http) throws Exception {
        var response = result.andReturn().getResponse();
        String body = response.getContentAsString();
        assertThat(response.getStatus()).as("HTTP status, body " + body).isEqualTo(http);
        return json.readTree(body);
    }

    private static String createBody(String code, String name, UUID brand, UUID category) {
        return """
                {"code":"%s","name":"%s","nameEn":"Product %s","taxClass":"STANDARD","brandId":"%s","categoryId":"%s"}"""
                .formatted(code, name, code, brand, category);
    }

    private static String updateBody(String name, UUID brand, UUID category, long version) {
        return """
                {"name":"%s","nameEn":"Product","taxClass":"STANDARD","brandId":"%s","categoryId":"%s","version":%d}"""
                .formatted(name, brand, category, version);
    }

    private void retireBrand(UUID brandId) {
        taxonomy.updateBrand(brandId, "Brand " + suffix, null, false);
    }

    private void retireCategory(UUID categoryId) {
        taxonomy.updateCategory(categoryId, new CategoryDetails("Category " + suffix, 0, null, null, null, false));
    }

    // ------------------------------------------------------------------ SCRUM-87: inactive brand or category

    @Test
    @DisplayName("an inactive brand is 409 BRAND_INACTIVE on brandId, an inactive category 409 CATEGORY_INACTIVE on categoryId")
    void inactiveBrandOrCategoryIsRefusedWhenChosen() throws Exception {
        retireBrand(tax.brandId());
        JsonNode brand = answer(send("POST", PRODUCTS,
                createBody("IB-" + suffix, "Ly", tax.brandId(), tax.categoryId())), 409);
        assertThat(brand.path("errorCode").asText()).isEqualTo("BRAND_INACTIVE");
        assertThat(brand.path("fieldErrors").get(0).path("field").asText()).isEqualTo("brandId");

        ProductFixtures.Taxonomy other = ProductFixtures.taxonomy(taxonomy, suffix + "B");
        retireCategory(other.categoryId());
        JsonNode category = answer(send("POST", PRODUCTS,
                createBody("IC-" + suffix, "Ly", other.brandId(), other.categoryId())), 409);
        assertThat(category.path("errorCode").asText()).isEqualTo("CATEGORY_INACTIVE");
        assertThat(category.path("fieldErrors").get(0).path("field").asText()).isEqualTo("categoryId");
    }

    @Test
    @DisplayName("a draft keeps a brand and category deactivated after it chose them: PUT with the same ones is 200")
    void retiredReferencesAreKeptOnEdit() throws Exception {
        JsonNode created = answer(send("POST", PRODUCTS,
                createBody("KB-" + suffix, "Ly", tax.brandId(), tax.categoryId())), 201).path("data");
        UUID id = UUID.fromString(created.path("productId").asText());
        retireBrand(tax.brandId());
        retireCategory(tax.categoryId());

        JsonNode updated = answer(send("PUT", PRODUCTS + "/" + id,
                updateBody("Ly mới", tax.brandId(), tax.categoryId(), created.path("version").asLong())), 200);

        assertThat(updated.path("data").path("name").asText()).isEqualTo("Ly mới");
        assertThat(updated.path("data").path("brandId").asText()).isEqualTo(tax.brandId().toString());
    }

    // ------------------------------------------------------------------ SCRUM-88: possible duplicates

    private JsonNode create(String code, String name, UUID brand, UUID category) throws Exception {
        return answer(send("POST", PRODUCTS, createBody(code, name, brand, category)), 201).path("data");
    }

    private static List<String> codes(JsonNode matches) {
        List<String> codes = new ArrayList<>();
        matches.forEach(m -> codes.add(m.path("code").asText()));
        return codes;
    }

    @Test
    @DisplayName("the same name, spelt differently, under the same brand and category is created (201) with a warning")
    void secondProductWithTheSameNameIsCreatedWithAWarning() throws Exception {
        JsonNode first = create("DA-" + suffix, "Ly giấy 12oz", tax.brandId(), tax.categoryId());
        assertThat(first.path("possibleDuplicates")).isEmpty();

        JsonNode second = create("DB-" + suffix, "LY GIAY 12 OZ", tax.brandId(), tax.categoryId());

        assertThat(second.path("status").asText()).isEqualTo("DRAFT");
        assertThat(codes(second.path("possibleDuplicates"))).containsExactly("DA-" + suffix);
        JsonNode match = second.path("possibleDuplicates").get(0);
        assertThat(match.path("productId").asText()).isEqualTo(first.path("productId").asText());
        assertThat(match.path("name").asText()).isEqualTo("Ly giấy 12oz");
        assertThat(match.path("status").asText()).isEqualTo("DRAFT");

        JsonNode detail = answer(send("GET", PRODUCTS + "/" + second.path("productId").asText(), null), 200);
        assertThat(codes(detail.path("data").path("possibleDuplicates"))).containsExactly("DA-" + suffix);

        JsonNode rows = answer(send("GET", PRODUCTS + "?brandId=" + tax.brandId(), null), 200);
        assertThat(rows.path("data").path("items")).hasSize(2)
                .allSatisfy(row -> assertThat(row.path("possibleDuplicates").isNull()
                        || row.path("possibleDuplicates").isMissingNode()).isTrue());
    }

    @Test
    @DisplayName("editing a product reports the others, never the product itself")
    void editingKeepsItselfOut() throws Exception {
        JsonNode first = create("EA-" + suffix, "Hộp giấy", tax.brandId(), tax.categoryId());
        create("EB-" + suffix, "hop giay", tax.brandId(), tax.categoryId());

        JsonNode updated = answer(send("PUT", PRODUCTS + "/" + first.path("productId").asText(),
                updateBody("Hộp giấy", tax.brandId(), tax.categoryId(), first.path("version").asLong())), 200);

        assertThat(codes(updated.path("data").path("possibleDuplicates"))).containsExactly("EB-" + suffix);
    }

    @Test
    @DisplayName("another brand, or a discontinued product, is not a duplicate")
    void otherBrandOrDiscontinuedIsNotADuplicate() throws Exception {
        UUID approverUser = tx.execute(s -> ApprovalLimitFixtures.approver(entityManager));
        var old = ProductFixtures.approved(products, tax, "OLD" + suffix, editorUser, approverUser);
        products.discontinue(old.productId(), "replaced", old.version());
        ProductFixtures.Taxonomy other = ProductFixtures.taxonomy(taxonomy, suffix + "O");
        create("OB-" + suffix, "Ly nhựa", other.brandId(), other.categoryId());

        JsonNode sameNameOfDiscontinued = create("ND-" + suffix, old.name(), tax.brandId(), tax.categoryId());
        JsonNode sameNameOtherBrand = create("NB-" + suffix, "Ly nhựa", tax.brandId(), tax.categoryId());

        assertThat(sameNameOfDiscontinued.path("possibleDuplicates")).isEmpty();
        assertThat(sameNameOtherBrand.path("possibleDuplicates")).isEmpty();
    }

    @Test
    @DisplayName("GET /products/duplicates checks a name before anything is saved; excludeProductId leaves one out")
    void duplicatesEndpoint() throws Exception {
        JsonNode first = create("QA-" + suffix, "Ly giấy 12oz", tax.brandId(), tax.categoryId());
        // No percent-escapes in the name: MockMvc's get(String) is a URI template and would encode them again.
        String query = PRODUCTS + "/duplicates?name=LY-GIAY-12OZ&brandId=" + tax.brandId()
                + "&categoryId=" + tax.categoryId();

        assertThat(codes(answer(send("GET", query, null), 200).path("data"))).containsExactly("QA-" + suffix);
        assertThat(answer(send("GET", query + "&excludeProductId=" + first.path("productId").asText(), null), 200)
                .path("data")).isEmpty();
        assertThat(answer(send("GET", PRODUCTS + "/duplicates?brandId=" + tax.brandId(), null), 400)
                .path("success").asBoolean()).isFalse();
    }
}
