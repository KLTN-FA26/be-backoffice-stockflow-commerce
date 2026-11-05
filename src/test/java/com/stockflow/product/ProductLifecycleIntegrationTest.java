package com.stockflow.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.catalog.api.CatalogService;
import com.stockflow.catalog.internal.service.CatalogProjectionService;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.internal.service.ProductTaxonomyService;
import com.stockflow.procurement.api.CreatePOLineCommand;
import com.stockflow.procurement.api.CreatePurchaseOrderCommand;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.SupplierItemService;
import com.stockflow.support.ApprovalLimitFixtures;
import com.stockflow.support.Await;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ProductFixtures;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SupplierItemFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SCRUM-461 over HTTP, through the real filter chain, the permission aspect and the seeded role grants
 * (ECOMMERCE_ADMIN, SYSTEM_ADMIN, SALES_STAFF, WAREHOUSE_STAFF, PROCUREMENT_STAFF), against Postgres: the brand a
 * submission needs, the version a PUT quotes, discontinuing with a reason (warnings, never a refusal; variants
 * OBSOLETE), suspending and resuming, the storefront's view of an INACTIVE product, deleting a draft, and the
 * history. Every refusal is asserted by its exact {@code errorCode} and, where the rule names one, its
 * {@code fieldErrors}.
 *
 * <p>People are real {@code identity.app_user} rows, because the history and the approval evidence are foreign
 * keys to them; only the token decoding is stubbed.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
// The same properties and mock as the other security tests, so the application context is shared.
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000"})
class ProductLifecycleIntegrationTest {

    private static final String JSON = "application/json";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ProductService products;
    @Autowired ProductTaxonomyService taxonomy;
    @Autowired ProcurementService procurement;
    @Autowired SupplierItemService supplierCatalog;
    @Autowired CatalogService catalog;
    @Autowired CatalogProjectionService projection;

    private UUID editorUser;
    private UUID approverUser;
    private String suffix;
    private ProductFixtures.Taxonomy tax;

    @BeforeEach
    void setUp() {
        editorUser = tx.execute(s -> ReferenceRows.user(entityManager));
        approverUser = tx.execute(s -> ApprovalLimitFixtures.approver(entityManager));
        suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        tax = ProductFixtures.taxonomy(taxonomy, suffix);
    }

    // ------------------------------------------------------------------ people and requests

    private String token(UUID user, String role) {
        String token = UUID.randomUUID().toString();
        when(decoder.decode(token)).thenReturn(Jwt.withTokenValue(token).header("alg", "RS256")
                .subject(user.toString()).claim("roles", List.of(role)).build());
        return "Bearer " + token;
    }

    /** Product people: ECOMMERCE_ADMIN has every product-products permission (create, update, approve, delete). */
    private String editor() {
        return token(editorUser, "ECOMMERCE_ADMIN");
    }

    /** SYSTEM_ADMIN: approves, and alone suspends, resumes and discontinues (product-lifecycle:APPROVE). */
    private String approver() {
        return token(approverUser, "SYSTEM_ADMIN");
    }

    private ResultActions send(String method, String auth, String path, String body) throws Exception {
        var request = switch (method) {
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "DELETE" -> delete(path);
            default -> get(path);
        };
        request.header("Authorization", auth);
        if (body != null) {
            request.contentType(JSON).content(body);
        }
        return mvc.perform(request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** The status, with the body in the failure message: a refusal's code is what tells which rule spoke. */
    private static ResultActions expectStatus(ResultActions result, int http) throws Exception {
        var response = result.andReturn().getResponse();
        String body = response.getContentAsString();
        assertThat(response.getStatus()).as("HTTP status, body " + body).isEqualTo(http);
        return result;
    }

    private static void assertRefused(ResultActions result, int http, String errorCode) throws Exception {
        expectStatus(result, http).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(errorCode));
    }

    private JsonNode product(UUID id) throws Exception {
        return data(send("GET", editor(), "/api/v1/products/" + id, null).andExpect(status().isOk()));
    }

    private long versionOf(UUID id) throws Exception {
        return product(id).path("version").asLong();
    }

    private String createBody(String code, UUID brand, UUID category) {
        return """
                {"code":"%s","name":"Product %s","nameEn":"Product %s","taxClass":"STANDARD"%s%s}"""
                .formatted(code, code, code, brand == null ? "" : ",\"brandId\":\"" + brand + "\"",
                        category == null ? "" : ",\"categoryId\":\"" + category + "\"");
    }

    /** A product made through the module's own service, approved by someone else: APPROVED, variant ACTIVE. */
    private ProductSummary approvedProduct() {
        String code = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        return ProductFixtures.approved(products, tax, code, editorUser, approverUser);
    }

    private ProductSummary draftProduct() {
        return ProductFixtures.draft(products, tax, UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
    }

    private UUID supplier() {
        UUID supplier = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO procurement.suppliers (id, code, name, status, version, created_at)
                VALUES (?, ?, 'Lifecycle supplier', 'ACTIVE', 0, NOW())""",
                supplier, "LC-" + supplier.toString().substring(24).toUpperCase(Locale.ROOT)));
        return supplier;
    }

    /** A supplier that lists each of {@code skus} (BR-PO-001), while they are still purchasable. */
    private UUID supplierListing(String... skus) {
        UUID supplier = supplier();
        for (String sku : skus) {
            SupplierItemFixtures.list(supplierCatalog, supplier, sku);
        }
        return supplier;
    }

    private UUID draftPurchaseOrder(String sku) {
        return procurement.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierListing(sku), DemoData.WAREHOUSE_HCM, "VND",
                LocalDate.now().plusDays(10), null, List.of(new CreatePOLineCommand(sku, null, 5, new BigDecimal("1000")))))
                .purchaseOrderId();
    }

    /** A placed order with one line of {@code sku}, in the given status. */
    private UUID orderWithLine(String sku, String status) {
        return tx.execute(s -> ReferenceRows.orderWithLine(entityManager, sku, status, DemoData.CUSTOMER_ID, editorUser,
                DemoData.WAREHOUSE_HCM));
    }

    private static String reasonAndVersion(String reason, long version) {
        return "{\"reason\":\"%s\",\"version\":%d}".formatted(reason, version);
    }

    // ------------------------------------------------------------------ brand on submission

    @Test
    @DisplayName("a DRAFT is saved without a brand, but submitting it is 409 PRODUCT_BRAND_REQUIRED naming brandId")
    void brandIsRequiredToSubmit() throws Exception {
        JsonNode created = data(send("POST", editor(), "/api/v1/products", createBody("BRD-" + suffix, null, tax.categoryId()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("DRAFT")));
        UUID id = UUID.fromString(created.path("productId").asText());

        assertRefused(send("POST", editor(), "/api/v1/products/" + id + "/submission", null), 409, "PRODUCT_BRAND_REQUIRED");
        send("POST", editor(), "/api/v1/products/" + id + "/submission", null)
                .andExpect(jsonPath("$.fieldErrors[0].field").value("brandId"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("PRODUCT_BRAND_REQUIRED"))
                .andExpect(jsonPath("$.fieldErrors[0].message").isNotEmpty());
        assertThat(product(id).path("status").asText()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.product_change_log WHERE product_id = ? "
                + "AND change_type = 'SUBMITTED'", Long.class, id)).isZero();

        String update = """
                {"name":"Product","nameEn":"Product","taxClass":"STANDARD","brandId":"%s","categoryId":"%s","version":%d}"""
                .formatted(tax.brandId(), tax.categoryId(), created.path("version").asLong());
        send("PUT", editor(), "/api/v1/products/" + id, update).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.brandId").value(tax.brandId().toString()));
        send("POST", editor(), "/api/v1/products/" + id + "/submission", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));
    }

    @Test
    @DisplayName("the database holds the same rule: no row may wait for approval without a brand")
    void databaseRefusesAPendingProductWithoutABrand() {
        ProductSummary draft = draftProduct();
        UUID brandless = Identifiers.newId();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO product.products (id, code, name, slug, status, submitted_by, submitted_at, version, created_at)
                VALUES (?, ?, 'x', ?, 'PENDING_APPROVAL', ?, NOW(), 0, NOW())""",
                brandless, "NB-" + suffix, "nb-" + suffix.toLowerCase(Locale.ROOT), editorUser)))
                .hasMessageContaining("ck_products_brand_on_submit");
        assertThat(draft.brandId()).isNotNull();
    }

    // ------------------------------------------------------------------ PUT with a version

    @Test
    @DisplayName("PUT quotes the version it read: missing is 400, stale is 409 OPTIMISTIC_LOCK, a non-draft is 409")
    void putNeedsTheVersionItRead() throws Exception {
        ProductSummary draft = draftProduct();
        String path = "/api/v1/products/" + draft.productId();
        String withoutVersion = """
                {"name":"Renamed","nameEn":"Renamed","taxClass":"STANDARD","brandId":"%s","categoryId":"%s"}"""
                .formatted(tax.brandId(), tax.categoryId());
        assertRefused(send("PUT", editor(), path, withoutVersion), 400, "VALIDATION_FAILED");
        send("PUT", editor(), path, withoutVersion).andExpect(jsonPath("$.fieldErrors[0].field").value("version"));

        String update = """
                {"name":"Renamed","nameEn":"Renamed","taxClass":"STANDARD","brandId":"%s","categoryId":"%s","version":%d}""";
        JsonNode first = data(send("PUT", editor(), path, update.formatted(tax.brandId(), tax.categoryId(), draft.version()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("Renamed")));
        assertThat(first.path("version").asLong()).isGreaterThan(draft.version());

        // The version the caller read is now stale.
        assertRefused(send("PUT", editor(), path, update.formatted(tax.brandId(), tax.categoryId(), draft.version())),
                409, "OPTIMISTIC_LOCK");
        // The current one passes the version check, and an approved product is then refused on its status.
        ProductSummary approved = approvedProduct();
        assertRefused(send("PUT", editor(), "/api/v1/products/" + approved.productId(),
                update.formatted(tax.brandId(), tax.categoryId(), approved.version())),
                409, "INVALID_PRODUCT_STATUS_TRANSITION");
        assertRefused(send("PUT", editor(), "/api/v1/products/" + UUID.randomUUID(),
                update.formatted(tax.brandId(), tax.categoryId(), 0)), 404, "PRODUCT_NOT_FOUND");
    }

    // ------------------------------------------------------------------ discontinue

    @Test
    @DisplayName("discontinue with an open PO line and an unshipped order: 200 with both warnings and the documents")
    void discontinueWarnsAndNeverRefuses() throws Exception {
        ProductSummary approved = approvedProduct();
        UUID id = approved.productId();
        String sku = approved.code();
        UUID po = draftPurchaseOrder(sku);
        UUID order = orderWithLine(sku, "CONFIRMED");
        String poNumber = jdbc.queryForObject("SELECT po_number FROM procurement.purchase_orders WHERE id = ?", String.class, po);
        String orderNumber = jdbc.queryForObject("SELECT order_number FROM ordering.customer_order WHERE id = ?", String.class, order);

        // Who may discontinue: product-lifecycle:APPROVE, the System Admin only (kltn-docs 01 §5.1). ECOMMERCE_ADMIN
        // approves and publishes but does not end a product's life; WAREHOUSE_STAFF and SALES_STAFF have neither.
        String path = "/api/v1/products/" + id + "/discontinuation";
        String body = reasonAndVersion("Het dong san pham", approved.version());
        assertRefused(send("POST", editor(), path, body), 403, "FORBIDDEN");
        assertRefused(send("POST", token(UUID.randomUUID(), "WAREHOUSE_STAFF"), path, body), 403, "FORBIDDEN");
        assertRefused(send("POST", token(UUID.randomUUID(), "SALES_STAFF"), path, body), 403, "FORBIDDEN");
        mvc.perform(post(path).contentType(JSON).content(body)).andExpect(status().isUnauthorized());

        send("POST", approver(), path, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISCONTINUED"))
                .andExpect(jsonPath("$.data.productId").value(id.toString()))
                .andExpect(jsonPath("$.data.discontinueReason").value("Het dong san pham"))
                .andExpect(jsonPath("$.data.discontinuedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.warnings.length()").value(2))
                .andExpect(jsonPath("$.data.warnings[0]").value("OPEN_PURCHASE_ORDER_LINES"))
                .andExpect(jsonPath("$.data.warnings[1]").value("UNSHIPPED_ORDER_LINES"))
                .andExpect(jsonPath("$.data.openReferences.length()").value(2))
                .andExpect(jsonPath("$.data.openReferencesTruncated").value(false))
                .andExpect(jsonPath("$.data.openReferences[?(@.kind == 'PURCHASE_ORDER_LINE')].documentNumber").value(poNumber))
                .andExpect(jsonPath("$.data.openReferences[?(@.kind == 'PURCHASE_ORDER_LINE')].documentId").value(po.toString()))
                .andExpect(jsonPath("$.data.openReferences[?(@.kind == 'SALES_ORDER_LINE')].documentNumber").value(orderNumber))
                .andExpect(jsonPath("$.data.openReferences[?(@.kind == 'SALES_ORDER_LINE')].sku").value(sku));

        // Every variant, the default one included, is OBSOLETE (docs 01 §5.2), not BLOCKED; the documents are untouched.
        assertThat(jdbc.queryForList("SELECT status FROM product.variants WHERE product_id = ?", String.class, id))
                .containsOnly("OBSOLETE");
        assertThat(jdbc.queryForObject("SELECT status FROM procurement.purchase_orders WHERE id = ?", String.class, po))
                .isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT status FROM ordering.customer_order WHERE id = ?", String.class, order))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT discontinue_reason FROM product.products WHERE id = ?", String.class, id))
                .isEqualTo("Het dong san pham");
    }

    @Test
    @DisplayName("discontinue refusals: body, reason, version and status, each by its exact code and field")
    void discontinueRefusals() throws Exception {
        ProductSummary approved = approvedProduct();
        String path = "/api/v1/products/" + approved.productId() + "/discontinuation";

        // The old call without a body no longer works: the reason is required (docs 01 §5.2).
        assertRefused(send("POST", approver(), path, null), 400, "MALFORMED_REQUEST");
        assertRefused(send("POST", approver(), path, "{}"), 400, "VALIDATION_FAILED");
        JsonNode both = body(send("POST", approver(), path, "{}"));
        assertThat(both.path("fieldErrors")).extracting(f -> f.path("field").asText()).containsExactlyInAnyOrder("reason", "version");
        assertRefused(send("POST", approver(), path, "{\"reason\":\"  \",\"version\":%d}".formatted(approved.version())),
                400, "VALIDATION_FAILED");
        send("POST", approver(), path, "{\"reason\":\"  \",\"version\":%d}".formatted(approved.version()))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));
        assertRefused(send("POST", approver(), path, reasonAndVersion("x".repeat(501), approved.version())), 400, "VALIDATION_FAILED");
        assertRefused(send("POST", approver(), path, reasonAndVersion("end", approved.version() + 5)), 409, "OPTIMISTIC_LOCK");
        assertRefused(send("POST", approver(), "/api/v1/products/" + UUID.randomUUID() + "/discontinuation",
                reasonAndVersion("end", 0)), 404, "PRODUCT_NOT_FOUND");
        // Nothing above changed anything.
        assertThat(product(approved.productId()).path("status").asText()).isEqualTo("APPROVED");

        // A draft cannot be discontinued; a discontinued product cannot be discontinued again.
        ProductSummary draft = draftProduct();
        assertRefused(send("POST", approver(), "/api/v1/products/" + draft.productId() + "/discontinuation",
                reasonAndVersion("end", draft.version())), 409, "INVALID_PRODUCT_STATUS_TRANSITION");
        send("POST", approver(), path, reasonAndVersion("end of line", approved.version()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.warnings.length()").value(0))
                .andExpect(jsonPath("$.data.openReferences.length()").value(0));
        assertRefused(send("POST", approver(), path, reasonAndVersion("again", versionOf(approved.productId()))),
                409, "INVALID_PRODUCT_STATUS_TRANSITION");
        // Two screens discontinue the same product: the second quotes the version the first read.
        assertRefused(send("POST", approver(), path, reasonAndVersion("again", approved.version())), 409, "OPTIMISTIC_LOCK");
    }

    @Test
    @DisplayName("a suspended product can still be discontinued, and a discontinued one cannot be resumed")
    void discontinueFromInactive() throws Exception {
        ProductSummary approved = approvedProduct();
        UUID id = approved.productId();
        JsonNode suspended = data(send("POST", approver(), "/api/v1/products/" + id + "/suspension",
                reasonAndVersion("recall", approved.version())).andExpect(status().isOk()));

        send("POST", approver(), "/api/v1/products/" + id + "/discontinuation",
                reasonAndVersion("supplier gone", suspended.path("version").asLong()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISCONTINUED"))
                .andExpect(jsonPath("$.data.suspendReason").value("recall"));
        assertRefused(send("POST", approver(), "/api/v1/products/" + id + "/resumption",
                "{\"version\":%d}".formatted(versionOf(id))), 409, "INVALID_PRODUCT_STATUS_TRANSITION");
    }

    // ------------------------------------------------------------------ suspend / resume

    @Test
    @DisplayName("suspend then resume: INACTIVE with when and why, back to APPROVED; each refusal by its code")
    void suspendAndResume() throws Exception {
        ProductSummary approved = approvedProduct();
        UUID id = approved.productId();
        String suspension = "/api/v1/products/" + id + "/suspension";
        String resumption = "/api/v1/products/" + id + "/resumption";

        assertRefused(send("POST", token(UUID.randomUUID(), "SALES_STAFF"), suspension,
                reasonAndVersion("recall", approved.version())), 403, "FORBIDDEN");
        // product-lifecycle:APPROVE is the System Admin's alone (kltn-docs 01 §5.1): not the E-commerce Admin's.
        assertRefused(send("POST", editor(), suspension, reasonAndVersion("recall", approved.version())), 403, "FORBIDDEN");
        assertRefused(send("POST", editor(), resumption, "{\"version\":%d}".formatted(approved.version())), 403, "FORBIDDEN");
        assertRefused(send("POST", approver(), suspension, "{\"version\":%d}".formatted(approved.version())), 400, "VALIDATION_FAILED");
        send("POST", approver(), suspension, "{\"version\":%d}".formatted(approved.version()))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));
        assertRefused(send("POST", approver(), suspension, reasonAndVersion("recall", approved.version() + 1)), 409, "OPTIMISTIC_LOCK");
        // Resuming a product that is not suspended.
        assertRefused(send("POST", approver(), resumption, "{\"version\":%d}".formatted(approved.version())),
                409, "INVALID_PRODUCT_STATUS_TRANSITION");

        JsonNode suspended = data(send("POST", approver(), suspension, reasonAndVersion("Nha cung cap thu hoi", approved.version()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("INACTIVE"))
                .andExpect(jsonPath("$.data.suspendReason").value("Nha cung cap thu hoi"))
                .andExpect(jsonPath("$.data.suspendedAt").isNotEmpty()));
        long version = suspended.path("version").asLong();
        assertThat(version).isGreaterThan(approved.version());
        assertRefused(send("POST", approver(), suspension, reasonAndVersion("again", version)), 409, "INVALID_PRODUCT_STATUS_TRANSITION");
        // The variants of a suspended product are not touched: only discontinuing makes them OBSOLETE.
        assertThat(jdbc.queryForList("SELECT status FROM product.variants WHERE product_id = ?", String.class, id))
                .containsOnly("ACTIVE");

        assertRefused(send("POST", approver(), resumption, "{}"), 400, "VALIDATION_FAILED");
        assertRefused(send("POST", approver(), resumption, "{\"version\":%d}".formatted(version - 1)), 409, "OPTIMISTIC_LOCK");
        send("POST", approver(), resumption, "{\"version\":%d}".formatted(version))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.suspendedAt").doesNotExist())
                .andExpect(jsonPath("$.data.suspendReason").doesNotExist());
        // Draft and pending products cannot be suspended.
        ProductSummary draft = draftProduct();
        assertRefused(send("POST", approver(), "/api/v1/products/" + draft.productId() + "/suspension",
                reasonAndVersion("recall", draft.version())), 409, "INVALID_PRODUCT_STATUS_TRANSITION");
    }

    /** A PUBLISHED product with a price and a published image, made by SQL the way the catalog tests make one. */
    private record Published(UUID id, String sku, String slug) {
    }

    private Published publishedProduct(String tag) {
        UUID id = Identifiers.newId();
        UUID variant = Identifiers.newId();
        String sku = tag + "-" + suffix;
        String slug = tag.toLowerCase(Locale.ROOT) + "-" + suffix.toLowerCase(Locale.ROOT);
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO product.products (id, code, name, slug, status, brand_id, submitted_by, submitted_at,
                        approved_by, approved_at, published_at, seo_title, seo_description, version, created_at)
                    VALUES (?, ?, 'Published', ?, 'PUBLISHED', ?, ?, NOW(), ?, NOW(), NOW(), 'Seo', 'Seo text', 0, NOW())""",
                    id, sku, slug, tax.brandId(), editorUser, approverUser);
            jdbc.update("INSERT INTO product.product_categories (id, product_id, category_id, is_primary) VALUES (?, ?, ?, true)",
                    Identifiers.newId(), id, tax.categoryId());
            jdbc.update("INSERT INTO product.variants (id, product_id, sku, name, status, is_default, attribute_signature) "
                    + "VALUES (?, ?, ?, 'Published', 'ACTIVE', TRUE, 'DEFAULT')", variant, id, sku);
            jdbc.update("""
                    INSERT INTO product.media (id, variant_id, url, is_published, published_at, published_by, created_by)
                    VALUES (?, ?, 'https://example.test/pub.jpg', true, NOW(), md5('demo:user:approver')::uuid,
                            md5('demo:user:editor')::uuid::text)""", Identifiers.newId(), variant);
            jdbc.update("INSERT INTO catalog.pricing_rule (id, name, sku, price, currency, active, created_at) "
                    + "VALUES (?, ?, ?, 12500000, 'VND', true, NOW())", Identifiers.newId(), "BASE:" + sku, sku);
        });
        projection.project(id);
        return new Published(id, sku, slug);
    }

    private void awaitCatalogHides(Published product) {
        Await.until("the catalog hides " + product.sku(), Duration.ofSeconds(20), () ->
                !jdbc.queryForObject("SELECT published FROM catalog.catalog_entry WHERE sku = ?", Boolean.class, product.sku())
                        && !jdbc.queryForObject("SELECT enabled FROM catalog.product_listing WHERE product_id = ?",
                        Boolean.class, product.id()));
    }

    @Test
    @DisplayName("a discontinued PUBLISHED product leaves the storefront and checkout, and its catalog rows are hidden")
    void discontinuedProductIsHiddenFromTheCatalog() throws Exception {
        Published product = publishedProduct("DISC");
        String shopper = token(UUID.randomUUID(), "SALES_STAFF");
        send("GET", shopper, "/api/v1/catalog/products/" + product.slug(), null).andExpect(status().isOk());

        send("POST", approver(), "/api/v1/products/" + product.id() + "/discontinuation",
                reasonAndVersion("end of line", versionOf(product.id()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISCONTINUED"));

        assertRefused(send("GET", shopper, "/api/v1/catalog/products/" + product.slug(), null), 404, "NOT_FOUND");
        send("GET", shopper, "/api/v1/catalog/products?size=100", null)
                .andExpect(jsonPath("$.data.items[?(@.slug == '" + product.slug() + "')]").isEmpty());
        assertThatThrownBy(() -> catalog.checkoutPrices(Set.of(product.sku()))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_PURCHASABLE));
        awaitCatalogHides(product);
        // Terminal: it cannot come back through the catalog either.
        assertRefused(send("POST", editor(), "/api/v1/products/" + product.id() + "/publication", null), 409, "PRODUCT_NOT_APPROVED");
    }

    @Test
    @DisplayName("a suspended PUBLISHED product leaves the storefront and checkout; resuming gives APPROVED, publishing shows it again")
    void suspendedProductIsHiddenFromTheCatalog() throws Exception {
        Published product = publishedProduct("SUSP");
        UUID id = product.id();
        String sku = product.sku();
        String slug = product.slug();
        String shopper = token(UUID.randomUUID(), "SALES_STAFF");
        send("GET", shopper, "/api/v1/catalog/products/" + slug, null).andExpect(status().isOk());
        send("GET", shopper, "/api/v1/catalog/products?size=100", null)
                .andExpect(jsonPath("$.data.items[?(@.slug == '" + slug + "')]").isNotEmpty());
        assertThat(catalog.checkoutPrices(Set.of(sku))).containsKey(sku);

        long version = versionOf(id);
        send("POST", approver(), "/api/v1/products/" + id + "/suspension", reasonAndVersion("recall", version))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("INACTIVE"))
                .andExpect(jsonPath("$.data.publishedAt").isNotEmpty());

        // At once: the detail page, the list and the checkout look at the product's own status.
        assertRefused(send("GET", shopper, "/api/v1/catalog/products/" + slug, null), 404, "NOT_FOUND");
        send("GET", shopper, "/api/v1/catalog/products?size=100", null)
                .andExpect(jsonPath("$.data.items[?(@.slug == '" + slug + "')]").isEmpty());
        assertThatThrownBy(() -> catalog.checkoutPrices(Set.of(sku))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_PURCHASABLE));
        // And the catalog's own rows follow, once it has been told (CatalogListingChanged).
        awaitCatalogHides(product);

        send("POST", approver(), "/api/v1/products/" + id + "/resumption", "{\"version\":%d}".formatted(versionOf(id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"));
        assertRefused(send("GET", shopper, "/api/v1/catalog/products/" + slug, null), 404, "NOT_FOUND");

        send("POST", editor(), "/api/v1/products/" + id + "/publication", null).andExpect(status().isOk());
        send("GET", shopper, "/api/v1/catalog/products/" + slug, null).andExpect(status().isOk());
        assertThat(product(id).path("status").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("a suspended product is neither published nor unpublished by the catalog: 409 PRODUCT_NOT_APPROVED / CONFLICT")
    void aSuspendedProductCannotBePublished() throws Exception {
        ProductSummary approved = approvedProduct();
        UUID id = approved.productId();
        JsonNode suspended = data(send("POST", approver(), "/api/v1/products/" + id + "/suspension",
                reasonAndVersion("recall", approved.version())).andExpect(status().isOk()));
        // A listing to publish: its revision is the product's version.
        send("PUT", editor(), "/api/v1/products/" + id + "/ecommerce",
                "{\"revision\":%d,\"slug\":\"susp-%s\"}".formatted(suspended.path("version").asLong(), suffix.toLowerCase(Locale.ROOT)))
                .andExpect(status().isOk());

        assertRefused(send("POST", editor(), "/api/v1/products/" + id + "/publication", null), 409, "PRODUCT_NOT_APPROVED");
        assertRefused(send("POST", editor(), "/api/v1/products/" + id + "/unpublication", null), 409, "CONFLICT");
        assertThat(product(id).path("status").asText()).isEqualTo("INACTIVE");
    }

    // ------------------------------------------------------------------ delete

    @Test
    @DisplayName("DELETE a DRAFT: 204, the product, its variant and the SKU's inventory item are gone; GET is 404")
    void deleteDraft() throws Exception {
        ProductSummary draft = draftProduct();
        UUID id = draft.productId();
        String sku = draft.code();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.inventory_items WHERE sku = ?", Long.class, sku)).isOne();
        String path = "/api/v1/products/" + id + "?version=" + draft.version();

        // product-products:DELETE: ECOMMERCE_ADMIN and SYSTEM_ADMIN have it; SALES_STAFF (create/update) and WAREHOUSE_STAFF do not.
        assertRefused(send("DELETE", token(UUID.randomUUID(), "WAREHOUSE_STAFF"), path, null), 403, "FORBIDDEN");
        assertRefused(send("DELETE", token(UUID.randomUUID(), "SALES_STAFF"), path, null), 403, "FORBIDDEN");
        mvc.perform(delete(path)).andExpect(status().isUnauthorized());
        assertRefused(send("DELETE", editor(), "/api/v1/products/" + id, null), 400, "VALIDATION_FAILED");
        assertRefused(send("DELETE", editor(), "/api/v1/products/" + id + "?version=" + (draft.version() + 1), null),
                409, "OPTIMISTIC_LOCK");
        assertRefused(send("DELETE", editor(), "/api/v1/products/" + UUID.randomUUID() + "?version=0", null),
                404, "PRODUCT_NOT_FOUND");
        assertThat(product(id).path("status").asText()).isEqualTo("DRAFT");

        send("DELETE", approver(), path, null).andExpect(status().isNoContent());

        assertRefused(send("GET", editor(), "/api/v1/products/" + id, null), 404, "PRODUCT_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.variants WHERE product_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.inventory_items WHERE sku = ?", Long.class, sku)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.product_change_log WHERE product_id = ?", Long.class, id)).isZero();
        // The audit trail is what is left: who deleted what (DELETE is a security-relevant action).
        Await.until("the delete is audited", Duration.ofSeconds(10), () -> jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log WHERE resource_type = 'product' AND resource_id = ?
                   AND action = 'DELETE' AND outcome = 'SUCCESS'""", Long.class, id.toString()) == 1);
    }

    @Test
    @DisplayName("a product that is not a draft is 409 PRODUCT_NOT_DELETABLE, whatever its status")
    void deleteRefusedWhenNotADraft() throws Exception {
        ProductSummary approved = approvedProduct();
        assertRefused(send("DELETE", editor(), "/api/v1/products/" + approved.productId() + "?version=" + approved.version(), null),
                409, "PRODUCT_NOT_DELETABLE");

        long version = approved.version();
        JsonNode suspended = data(send("POST", approver(), "/api/v1/products/" + approved.productId() + "/suspension",
                reasonAndVersion("recall", version)).andExpect(status().isOk()));
        assertRefused(send("DELETE", editor(), "/api/v1/products/" + approved.productId() + "?version=" + suspended.path("version").asLong(),
                null), 409, "PRODUCT_NOT_DELETABLE");
        send("POST", approver(), "/api/v1/products/" + approved.productId() + "/discontinuation",
                reasonAndVersion("end", suspended.path("version").asLong())).andExpect(status().isOk());
        assertRefused(send("DELETE", editor(), "/api/v1/products/" + approved.productId() + "?version=" + versionOf(approved.productId()),
                null), 409, "PRODUCT_NOT_DELETABLE");

        ProductSummary pending = draftProduct();
        products.submit(pending.productId(), editorUser);
        assertRefused(send("DELETE", editor(), "/api/v1/products/" + pending.productId() + "?version=" + versionOf(pending.productId()),
                null), 409, "PRODUCT_NOT_DELETABLE");
        // Everything is still there: the refusal changed nothing.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.inventory_items WHERE sku = ?", Long.class, approved.code())).isOne();
    }

    @Test
    @DisplayName("a draft sent back by a rejection can be deleted; a draft a design already points at is 409 PRODUCT_IN_USE and kept whole")
    void deleteRejectedAndInUseDrafts() throws Exception {
        ProductSummary rejected = draftProduct();
        products.submit(rejected.productId(), editorUser);
        products.reject(rejected.productId(), approverUser, "Missing dimensions");
        send("DELETE", editor(), "/api/v1/products/" + rejected.productId() + "?version=" + versionOf(rejected.productId()), null)
                .andExpect(status().isNoContent());

        ProductSummary inUse = draftProduct();
        UUID design = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
                VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", design, DemoData.CUSTOMER_ID, inUse.productId()));
        assertRefused(send("DELETE", editor(), "/api/v1/products/" + inUse.productId() + "?version=" + inUse.version(), null),
                409, "PRODUCT_IN_USE");
        // The whole deletion was undone, including the inventory item that was deleted first.
        assertThat(product(inUse.productId()).path("status").asText()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.inventory_items WHERE sku = ?", Long.class, inUse.code())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.variants WHERE product_id = ?", Long.class, inUse.productId())).isOne();
    }

    @Test
    @DisplayName("a draft whose SKU has an open purchase order line, stock history or an order is 409 PRODUCT_IN_USE")
    void deleteRefusedWhenPurchasingOrOrdersReferToTheSku() throws Exception {
        // A PO line on a draft's SKU cannot be made through the service (a draft is not purchasable): by SQL.
        ProductSummary draft = draftProduct();
        UUID po = Identifiers.newId();
        UUID supplier = supplier();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency, order_date,
                                                             version, created_at)
                    VALUES (?, ?, 'DRAFT', ?, ?, 'VND', CURRENT_DATE, 0, NOW())""",
                    po, "PO-LC-" + po.toString().substring(24), supplier, DemoData.WAREHOUSE_HCM);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, line_no, inventory_item_id, ordered_qty, unit_price,
                                                                  version, created_at)
                    SELECT ?, ?, 1, id, 3, 1000, 0, NOW() FROM inventory.inventory_items WHERE sku = ?""",
                    Identifiers.newId(), po, draft.code());
        });
        assertRefused(send("DELETE", editor(), "/api/v1/products/" + draft.productId() + "?version=" + draft.version(), null),
                409, "PRODUCT_IN_USE");

        // A cancelled line no longer counts as open, but it still holds the item: the foreign key answers the same.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE procurement.purchase_order_lines SET status = 'CANCELLED' WHERE po_id = ?", po));
        assertThat(products.openReferences(draft.productId()).references()).isEmpty();
        assertRefused(send("DELETE", editor(), "/api/v1/products/" + draft.productId() + "?version=" + draft.version(), null),
                409, "PRODUCT_IN_USE");
        assertThat(product(draft.productId()).path("status").asText()).isEqualTo("DRAFT");
    }

    // ------------------------------------------------------------------ open references

    @Test
    @DisplayName("GET open-references lists what still refers to the SKUs; READ permission, 404 for an unknown product")
    void openReferencesEndpoint() throws Exception {
        ProductSummary approved = approvedProduct();
        String path = "/api/v1/products/" + approved.productId() + "/open-references";
        send("GET", editor(), path, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.references.length()").value(0))
                .andExpect(jsonPath("$.data.truncated").value(false));

        UUID po = draftPurchaseOrder(approved.code());
        UUID order = orderWithLine(approved.code(), "IN_PRODUCTION");
        orderWithLine(approved.code(), "SHIPPED");   // shipped: nothing left to fulfil, not listed
        orderWithLine(approved.code(), "DRAFT");     // a cart is not an order
        orderWithLine(approved.code(), "CANCELLED");

        send("GET", token(UUID.randomUUID(), "SALES_STAFF"), path, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.references.length()").value(2))
                .andExpect(jsonPath("$.data.references[?(@.kind == 'PURCHASE_ORDER_LINE')].documentId").value(po.toString()))
                .andExpect(jsonPath("$.data.references[?(@.kind == 'SALES_ORDER_LINE')].documentId").value(order.toString()));
        assertRefused(send("GET", token(UUID.randomUUID(), "WAREHOUSE_STAFF"), path, null), 403, "FORBIDDEN");
        assertRefused(send("GET", editor(), "/api/v1/products/" + UUID.randomUUID() + "/open-references", null),
                404, "PRODUCT_NOT_FOUND");
    }

    // ------------------------------------------------------------------ history

    @Test
    @DisplayName("GET versions: the status moves with from, to and reason, and the variant changes, newest first")
    void versionsShowStatusMovesWithReasonAndVariants() throws Exception {
        ProductSummary approved = approvedProduct();
        UUID id = approved.productId();
        JsonNode suspended = data(send("POST", approver(), "/api/v1/products/" + id + "/suspension",
                reasonAndVersion("recall", approved.version())).andExpect(status().isOk()));
        JsonNode resumed = data(send("POST", approver(), "/api/v1/products/" + id + "/resumption",
                "{\"version\":%d}".formatted(suspended.path("version").asLong())).andExpect(status().isOk()));
        send("POST", approver(), "/api/v1/products/" + id + "/discontinuation",
                reasonAndVersion("end of line", resumed.path("version").asLong())).andExpect(status().isOk());

        JsonNode page = data(send("GET", token(UUID.randomUUID(), "SALES_STAFF"), "/api/v1/products/" + id + "/versions", null)
                .andExpect(status().isOk()));
        List<String> types = new java.util.ArrayList<>();
        page.path("items").forEach(item -> types.add(item.path("changeType").asText()));
        // Newest first: the discontinuation, then the variant it made OBSOLETE, then the earlier moves.
        assertThat(types).containsExactly("DISCONTINUED", "VARIANT_STATUS", "RESUMED", "SUSPENDED", "APPROVED", "VARIANT_STATUS",
                "SUBMITTED", "CREATED");

        JsonNode discontinued = page.path("items").get(0);
        assertThat(discontinued.path("subjectType").asText()).isEqualTo("PRODUCT");
        assertThat(discontinued.path("fromStatus").asText()).isEqualTo("APPROVED");
        assertThat(discontinued.path("toStatus").asText()).isEqualTo("DISCONTINUED");
        assertThat(discontinued.path("reason").asText()).isEqualTo("end of line");
        assertThat(discontinued.path("actorName").asText()).startsWith("test-");
        assertThat(discontinued.path("action").asText()).isEqualTo("TRANSITION");
        assertThat(discontinued.path("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(discontinued.path("occurredAt").asText()).isNotEmpty();
        JsonNode variantMove = page.path("items").get(1);
        assertThat(variantMove.path("subjectType").asText()).isEqualTo("VARIANT");
        assertThat(variantMove.path("subjectId").asText()).isEqualTo(jdbc.queryForObject(
                "SELECT id FROM product.variants WHERE product_id = ?", UUID.class, id).toString());
        assertThat(variantMove.path("fromStatus").asText()).isEqualTo("ACTIVE");
        assertThat(variantMove.path("toStatus").asText()).isEqualTo("OBSOLETE");
        assertThat(variantMove.path("reason").asText()).isEqualTo("end of line");
        JsonNode suspension = page.path("items").get(3);
        assertThat(suspension.path("changeType").asText()).isEqualTo("SUSPENDED");
        assertThat(suspension.path("fromStatus").asText()).isEqualTo("APPROVED");
        assertThat(suspension.path("toStatus").asText()).isEqualTo("INACTIVE");
        assertThat(suspension.path("reason").asText()).isEqualTo("recall");
        // The product's creation is the oldest entry, by whoever created it (the system here: no user in a service call).
        JsonNode created = page.path("items").get(7);
        assertThat(created.path("toStatus").asText()).isEqualTo("DRAFT");
        assertThat(created.path("actorName").asText()).isEqualTo("system");

        assertRefused(send("GET", token(UUID.randomUUID(), "WAREHOUSE_STAFF"), "/api/v1/products/" + id + "/versions", null),
                403, "FORBIDDEN");
        assertRefused(send("GET", editor(), "/api/v1/products/" + UUID.randomUUID() + "/versions", null), 404, "PRODUCT_NOT_FOUND");
        send("GET", editor(), "/api/v1/products/" + id + "/versions?size=3&page=1", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.totalElements").value(8))
                .andExpect(jsonPath("$.data.items[0].changeType").value("SUSPENDED"));
    }

    // ------------------------------------------------------------------ purchase order endpoints

    private String poBody(UUID supplierId, String... skus) {
        StringBuilder lines = new StringBuilder();
        for (String sku : skus) {
            lines.append(lines.length() == 0 ? "" : ",")
                    .append("{\"sku\":\"%s\",\"quantityOrdered\":1,\"unitPrice\":1000}".formatted(sku));
        }
        return "{\"supplierId\":\"%s\",\"warehouseId\":\"%s\",\"currency\":\"VND\",\"lines\":[%s]}"
                .formatted(supplierId, DemoData.WAREHOUSE_HCM, lines);
    }

    @Test
    @DisplayName("purchase order endpoints: an active SKU is 201; a discontinued or suspended one is 409 SKU_NOT_PURCHASABLE on lines[i].sku "
            + "at create, edit, submission and approval")
    void purchaseOrderEndpointsRefuseSkusThatAreNotActive() throws Exception {
        ProductSummary alive = approvedProduct();
        ProductSummary dying = approvedProduct();
        UUID listed = supplierListing(alive.code(), dying.code());
        // The buyer is a real user: the purchase order's history points at who acted.
        String buyer = token(tx.execute(s -> ReferenceRows.user(entityManager)), "PROCUREMENT_STAFF");

        // No permission, no purchase order.
        assertRefused(send("POST", token(UUID.randomUUID(), "WAREHOUSE_STAFF"), "/api/v1/purchase-orders", poBody(listed, alive.code())),
                403, "FORBIDDEN");
        JsonNode created = data(expectStatus(send("POST", buyer, "/api/v1/purchase-orders", poBody(listed, alive.code(), dying.code())), 201));
        String po = created.path("purchaseOrderId").asText();
        long version = created.path("version").asLong();
        assertThat(created.path("lines").size()).isEqualTo(2);

        send("POST", approver(), "/api/v1/products/" + dying.productId() + "/discontinuation",
                reasonAndVersion("end of line", dying.version())).andExpect(status().isOk());

        // Create: the line at fault is named, with why, translated (Vietnamese by default, English on request).
        assertRefused(send("POST", buyer, "/api/v1/purchase-orders", poBody(listed, alive.code(), dying.code())), 409, "SKU_NOT_PURCHASABLE");
        send("POST", buyer, "/api/v1/purchase-orders", poBody(listed, alive.code(), dying.code()))
                .andExpect(jsonPath("$.fieldErrors.length()").value(1))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lines[1].sku"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("PRODUCT_DISCONTINUED"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Sản phẩm đã ngừng kinh doanh"));
        mvc.perform(post("/api/v1/purchase-orders").header("Authorization", buyer).header("Accept-Language", "en")
                        .contentType(JSON).content(poBody(listed, alive.code(), dying.code())))
                .andExpect(jsonPath("$.errorCode").value("SKU_NOT_PURCHASABLE"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("The product is discontinued"));
        assertRefused(send("POST", buyer, "/api/v1/purchase-orders", poBody(listed, dying.code(), dying.code())), 400, "VALIDATION_FAILED");

        // Submission of the draft that holds the SKU: refused at the line, still a draft.
        assertRefused(send("POST", buyer, "/api/v1/purchase-orders/" + po + "/submission", null), 409, "SKU_NOT_PURCHASABLE");
        send("POST", buyer, "/api/v1/purchase-orders/" + po + "/submission", null)
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lines[1].sku"));
        // Edit: keep the line of the active SKU, drop the other; the dead SKU cannot be added back.
        String keptLine = created.path("lines").get(0).path("lineId").asText();
        String edit = """
                {"version":%d,"warehouseId":"%s","expectedAt":"%s","lines":[%s]}""";
        String expectedAt = LocalDate.now().plusDays(10).toString();
        assertRefused(send("PUT", buyer, "/api/v1/purchase-orders/" + po, edit.formatted(version, DemoData.WAREHOUSE_HCM, expectedAt,
                "{\"lineId\":\"%s\",\"sku\":\"%s\",\"quantityOrdered\":1,\"unitPrice\":1000},{\"sku\":\"%s\",\"quantityOrdered\":1,\"unitPrice\":1000}"
                        .formatted(keptLine, alive.code(), dying.code()))), 409, "SKU_NOT_PURCHASABLE");
        JsonNode edited = data(send("PUT", buyer, "/api/v1/purchase-orders/" + po, edit.formatted(version, DemoData.WAREHOUSE_HCM, expectedAt,
                "{\"lineId\":\"%s\",\"sku\":\"%s\",\"quantityOrdered\":1,\"unitPrice\":1000}".formatted(keptLine, alive.code())))
                .andExpect(status().isOk()));
        assertThat(edited.path("lines").size()).isEqualTo(1);
        send("POST", buyer, "/api/v1/purchase-orders/" + po + "/submission", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));

        // Approval: the active SKU is suspended while the order waits.
        JsonNode suspended = data(send("POST", approver(), "/api/v1/products/" + alive.productId() + "/suspension",
                reasonAndVersion("recall", alive.version())).andExpect(status().isOk()));
        assertRefused(send("POST", approver(), "/api/v1/purchase-orders/" + po + "/approval", null), 409, "SKU_NOT_PURCHASABLE");
        send("POST", approver(), "/api/v1/purchase-orders/" + po + "/approval", null)
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lines[0].sku"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("PRODUCT_INACTIVE"));
        send("POST", approver(), "/api/v1/products/" + alive.productId() + "/resumption",
                "{\"version\":%d}".formatted(suspended.path("version").asLong())).andExpect(status().isOk());
        send("POST", approver(), "/api/v1/purchase-orders/" + po + "/approval", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    // ------------------------------------------------------------------ permissions

    @Test
    @DisplayName("the seeded grants: ECOMMERCE_ADMIN and SYSTEM_ADMIN hold product-products:DELETE, nobody else does")
    void deletePermissionIsSeededForTwoRoles() {
        List<String> holders = jdbc.queryForList("""
                SELECT r.code FROM identity.role_permission rp
                  JOIN identity.app_role r ON r.id = rp.role_id
                  JOIN identity.permission p ON p.id = rp.permission_id
                 WHERE p.code = 'product-products:DELETE' ORDER BY r.code""", String.class);
        assertThat(holders).containsExactly("ECOMMERCE_ADMIN", "SYSTEM_ADMIN");
    }
}
