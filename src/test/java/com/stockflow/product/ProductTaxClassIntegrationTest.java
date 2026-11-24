package com.stockflow.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.internal.service.ProductTaxonomyService;
import com.stockflow.support.ProductFixtures;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * WF-05 demo D-8 over HTTP, through the real filter chain, the permission aspect and the seeded grants
 * (V20261224000350): the tax class of a PUBLISHED product that never had one (HĐ-7's LY-TRE-DEMO) is set by the
 * accountant and changed by the Sales Manager, from the SKU an invoice line names; nobody else may; the version read is
 * quoted back; the change is in the product's history; and the database never lets the class be cleared again.
 */
@SecuredIntegrationTest
class ProductTaxClassIntegrationTest {

    private static final String JSON = "application/json";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ProductService products;
    @Autowired ProductTaxonomyService taxonomy;

    private UUID user;
    private ProductSummary published;
    private String sku;

    @BeforeEach
    void aPublishedProductWithoutAClass() {
        user = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID approver = tx.execute(s -> ReferenceRows.user(entityManager));
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        ProductSummary approved = ProductFixtures.approved(products, ProductFixtures.taxonomy(taxonomy, suffix),
                suffix, user, approver);
        // As the demo seeds LY-TRE-DEMO: published, its class never set (a row from before Q-21 required one). Written
        // with triggers off for this transaction only: tg_products_tax_class_kept refuses to clear a class, and such a
        // row is only ever inserted without one.
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE product.products SET status = 'PUBLISHED', published_at = NOW(), tax_class = NULL "
                    + "WHERE id = ?", approved.productId());
        });
        published = approved;
        sku = "FX-" + suffix;
    }

    private String token(String role) {
        String token = UUID.randomUUID().toString();
        when(decoder.decode(token)).thenReturn(Jwt.withTokenValue(token).header("alg", "RS256")
                .subject(user.toString()).claim("roles", List.of(role)).build());
        return "Bearer " + token;
    }

    private ResultActions read(String role) throws Exception {
        return mvc.perform(get("/api/v1/products/tax-classes").param("sku", sku).param("sku", "NO-SUCH-SKU")
                .header("Authorization", token(role)));
    }

    private ResultActions change(String role, String taxClass, String reason, long version) throws Exception {
        String body = reason == null
                ? "{\"taxClass\":\"%s\",\"version\":%d}".formatted(taxClass, version)
                : "{\"taxClass\":\"%s\",\"reason\":\"%s\",\"version\":%d}".formatted(taxClass, reason, version);
        return mvc.perform(put("/api/v1/products/" + published.productId() + "/tax-class")
                .header("Authorization", token(role)).contentType(JSON).content(body));
    }

    private JsonNode body(ResultActions result, int status) throws Exception {
        var response = result.andReturn().getResponse();
        String text = response.getContentAsString();
        assertThat(response.getStatus()).as("HTTP status, body " + text).isEqualTo(status);
        return json.readTree(text);
    }

    @Test
    @DisplayName("HĐ-7: the accountant finds the SKU's product without a class and sets STANDARD on the PUBLISHED "
            + "product; the Sales Manager changes it at the new version; history and status follow")
    void accountantSetsAndSalesManagerChanges() throws Exception {
        JsonNode found = body(read("ACCOUNTANT"), 200).path("data");
        assertThat(found).as("an unknown SKU is left out").hasSize(1);
        assertThat(found.get(0).path("sku").asText()).isEqualTo(sku);
        assertThat(found.get(0).path("productId").asText()).isEqualTo(published.productId().toString());
        assertThat(found.get(0).path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(found.get(0).hasNonNull("taxClass")).as("never set").isFalse();
        long version = found.get(0).path("version").asLong();

        JsonNode set = body(change("ACCOUNTANT", "STANDARD", "NĐ 174: ly tre chịu thuế 10% giảm còn 8%", version), 200)
                .path("data");
        assertThat(set.path("taxClass").asText()).isEqualTo("STANDARD");
        assertThat(set.path("status").asText()).as("no workflow move").isEqualTo("PUBLISHED");
        assertThat(set.path("version").asLong()).isGreaterThan(version);
        assertThat(jdbc.queryForObject("SELECT tax_class FROM product.products WHERE id = ?", String.class,
                published.productId())).isEqualTo("STANDARD");
        assertThat(jdbc.queryForObject("SELECT reason FROM product.product_change_log WHERE product_id = ? "
                        + "AND change_type = 'UPDATED' AND changed_fields::text LIKE '%taxClass%'", String.class,
                published.productId())).startsWith("taxClass none -> STANDARD: NĐ 174");

        assertThat(body(change("SALES_MANAGER", "REDUCED", "sai nhóm", version), 409).path("errorCode").asText())
                .as("the version read before the accountant's change").isEqualTo("OPTIMISTIC_LOCK");
        JsonNode changed = body(change("SALES_MANAGER", "REDUCED", "sai nhóm", set.path("version").asLong()), 200)
                .path("data");
        assertThat(changed.path("taxClass").asText()).isEqualTo("REDUCED");
    }

    @Test
    @DisplayName("403 for every role without product-tax-class:UPDATE - Sales staff and the warehouse hold none; "
            + "a reason is required; the database never lets the class be cleared")
    void othersAreRefused() throws Exception {
        assertThat(body(read("SALES_STAFF"), 403).path("success").asBoolean()).isFalse();
        assertThat(body(read("WAREHOUSE_STAFF"), 403).path("success").asBoolean()).isFalse();
        assertThat(body(change("SALES_STAFF", "STANDARD", "x", published.version()), 403).path("success").asBoolean())
                .isFalse();
        assertThat(body(change("WAREHOUSE_MANAGER", "STANDARD", "x", published.version()), 403).path("success")
                .asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT tax_class FROM product.products WHERE id = ?", String.class,
                published.productId())).as("nothing changed").isNull();

        long version = body(read("ACCOUNTANT"), 200).path("data").get(0).path("version").asLong();
        assertThat(body(change("ACCOUNTANT", "STANDARD", null, version), 400).path("errorCode").asText())
                .isEqualTo("VALIDATION_FAILED");
        body(change("ACCOUNTANT", "EXEMPT", "hàng không chịu thuế", version), 200);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE product.products SET tax_class = NULL WHERE id = ?", published.productId())))
                .hasMessageContaining("is never cleared");
    }
}
