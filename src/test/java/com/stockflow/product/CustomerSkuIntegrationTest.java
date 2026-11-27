package com.stockflow.product;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.domain.Sku;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.UomConversionDraft;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import com.stockflow.product.api.IssueCustomerSkuCommand;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.service.ProductVariantService;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A customer's own SKU (kltn-docs 12 §4.5, 01 §4.8): a row of {@code product.customer_skus} since V20270105000100,
 * issued for an approved design snapshot as "product - customer - number", ACTIVE at once, packed in the source
 * variant's carton and with no inventory item until an order names it (owner decisions Q1, Q4, Q5); one per snapshot;
 * a second design of the same customer numbers on; unseen in the public catalogue; refused from a variant that is not on
 * sale; blocked, put back on sale and retired through its own endpoints. Security is off: the permissions of those
 * endpoints are {@code ProductVariantSecurityIntegrationTest}'s.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@AutoConfigureMockMvc
class CustomerSkuIntegrationTest {

    @Autowired CustomerSkus customerSkus;
    @Autowired ProductService products;
    @Autowired ProductVariantService variants;
    @Autowired ProductPublication publication;
    @Autowired UomConversionService units;
    @Autowired com.stockflow.product.internal.service.ProductMediaService media;
    @Autowired InventoryControlService inventory;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;
    @MockitoBean CurrentUserProvider users;

    private UUID productId;
    private String productCode;
    private String sourceSku;
    private UUID customerId;

    @BeforeEach
    void approvedCustomizableProduct() {
        UUID editor = tx.execute(s -> ReferenceRows.user(entityManager));
        when(users.current()).thenReturn(Optional.of(new CurrentUser(editor, "test-" + editor, Set.of(), Set.of(),
                DataScope.ALL, Set.of())));
        productCode = "CSKU-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        productId = products.create(new CreateProductCommand(productCode, "Ly giấy 12oz", null,
                null, "Ly", null, TaxClass.STANDARD, List.of())).productId();
        sourceSku = variants.list(productId, 0, 1).items().getFirst().sku();
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE product.products SET status = 'APPROVED', submitted_by = ?, submitted_at = NOW(), "
                    + "approved_by = ?, approved_at = NOW() WHERE id = ?", ReferenceRows.user(entityManager),
                    ReferenceRows.user(entityManager), productId);
            jdbc.update("UPDATE product.variants SET status = 'ACTIVE' WHERE product_id = ?", productId);
        });
        // The variant's carton spec: what a customer orders in (kltn-docs 14 BR-08).
        units.add(new Sku(sourceSku), new UomConversionDraft("CARTON", 50, new BigDecimal("4.2"),
                new BigDecimal("60"), new BigDecimal("40"), new BigDecimal("35"), true));
        customerId = tx.execute(s -> ReferenceRows.customer(entityManager));
    }

    private UUID snapshot() {
        UUID draft = Identifiers.newId();
        UUID snapshot = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, "
                    + "current_artifacts) VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)", draft, customerId, productId);
            jdbc.update("INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, "
                    + "created_at, artifact_manifest) VALUES (?, ?, ?, 'https://example.com/print.pdf', NOW(), NOW(), "
                    + "'[]'::jsonb)", snapshot, draft, "a".repeat(64));
        });
        return snapshot;
    }

    private CustomerSku issue(UUID snapshot, String code) {
        return tx.execute(s -> customerSkus.issue(new IssueCustomerSkuCommand(snapshot, customerId, code, sourceSku,
                "Ly quán Cà phê A")));
    }

    @Test
    void anApprovedDesignGetsAnActiveSkuWithTheVariantsCartonSpec() {
        CustomerSku sku = issue(snapshot(), "0312345678");
        UUID sourceVariant = jdbc.queryForObject("SELECT id FROM product.variants WHERE sku = ?", UUID.class,
                sourceSku);
        // Its own table, drawn on the variant; nothing is made in inventory and nothing copied (Q1, Q5): the item
        // comes when an order names the SKU, and neither the variant nor the SKU has one now.
        assertThat(jdbc.queryForObject("SELECT variant_id FROM product.customer_skus WHERE id = ?", UUID.class,
                sku.id())).isEqualTo(sourceVariant);
        assertThat(sku.sourceVariantId()).isEqualTo(sourceVariant);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.variants WHERE sku = ?", Long.class, sku.sku()))
                .as("not a variant").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.inventory_items WHERE sku IN (?, ?)",
                Long.class, sku.sku(), sourceSku)).as("no item at approval").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.product_change_log WHERE subject_id = ? AND "
                + "change_type = 'VARIANT_ADDED'", Long.class, sku.id())).isEqualTo(1);
        assertThat(customerSkus.findById(sku.id())).contains(sku);
        assertThat(customerSkus.find(sku.sku())).contains(sku);

        assertThat(sku.sku()).isEqualTo(productCode + "-0312345678-01");
        assertThat(sku.status()).isEqualTo("ACTIVE");
        assertThat(sku.ownerCustomerId()).isEqualTo(customerId);
        assertThat(sku.sourceSku()).isEqualTo(sourceSku);
        assertThat(sku.name()).isEqualTo("Ly quán Cà phê A");
        assertThat(customerSkus.ownerOf(sku.sku())).contains(customerId);
        assertThat(customerSkus.ownerOf(sourceSku)).isEmpty();

        var cartons = units.conversionsOf(List.of(new Sku(sku.sku()))).get(sku.sku()).stream()
                .filter(u -> u.unitOfMeasure().equals("CARTON")).toList();
        var sourceCartons = units.conversionsOf(List.of(new Sku(sourceSku))).get(sourceSku).stream()
                .filter(u -> u.unitOfMeasure().equals("CARTON")).toList();
        assertThat(cartons).singleElement().satisfies(carton -> {
            assertThat(carton.id()).as("the variant's own carton row").isEqualTo(sourceCartons.getFirst().id());
            assertThat(carton.factor()).isEqualTo(50);
            assertThat(carton.active()).isTrue();
            assertThat(carton.weightKg()).isEqualByComparingTo("4.2");
        });
        // The carton spec reads through the variant too, item or not.
        assertThat(inventory.cartonSpec(new Sku(sku.sku()))).hasValueSatisfying(spec ->
                assertThat(spec.unitsPerCarton()).isEqualTo(50));

        // The warehouse weighs the variant's carton again: the customer's SKU reads the new weight at once.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE inventory.uom_conversions SET weight_kg = 9.2 WHERE id = ?",
                sourceCartons.getFirst().id()));
        assertThat(units.conversionsOf(List.of(new Sku(sku.sku()))).get(sku.sku()))
                .filteredOn(u -> u.unitOfMeasure().equals("CARTON")).singleElement()
                .satisfies(carton -> assertThat(carton.weightKg()).isEqualByComparingTo("9.2"));
    }

    @Test
    void issuingAgainForTheSameSnapshotReturnsTheSameSkuAndARevisionNumbersOn() {
        UUID first = snapshot();
        CustomerSku issued = issue(first, "0312345678");

        assertThat(issue(first, "0312345678")).isEqualTo(issued);
        CustomerSku revision = issue(snapshot(), "0312345678");
        assertThat(revision.sku()).isEqualTo(productCode + "-0312345678-02");
        assertThat(customerSkus.ownedBy(customerId, productId)).extracting(CustomerSku::sku)
                .containsExactly(issued.sku(), revision.sku());
        // The old SKU stays ACTIVE when a revision is approved (kltn-docs 12 BR-12).
        assertThat(customerSkus.ownedBy(customerId, null)).extracting(CustomerSku::status).containsOnly("ACTIVE");
    }

    @Test
    void aCustomersSkuIsNotInThePublicCatalogueNorItsAnonymousGallery() {
        CustomerSku sku = issue(snapshot(), "0312345678");
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE product.products SET status = 'PUBLISHED', published_at = NOW(), seo_title = 'SEO', "
                    + "seo_description = 'SEO description' WHERE id = ?", productId);
            // Only a variant has images: a customer's SKU is not one (V20270105000100).
            jdbc.update("INSERT INTO product.media (id, variant_id, url, is_published, published_at, published_by, "
                    + "created_by) SELECT ?, v.id, 'https://example.test/' || v.sku || '.jpg', TRUE, NOW(), "
                    + "md5('demo:user:approver')::uuid, md5('demo:user:editor')::uuid::text FROM product.variants v "
                    + "WHERE v.sku = ?", UUID.randomUUID(), sourceSku);
        });

        assertThat(publication.read(productId).skus()).extracting(s -> s.sku())
                .contains(sourceSku)
                .doesNotContain(sku.sku());
        assertThat(publication.cards(Set.of(productId)).get(productId).skus()).extracting(s -> s.sku())
                .contains(sourceSku)
                .doesNotContain(sku.sku());
        assertThat(media.publicGallery(productId, sourceSku).images()).isNotEmpty();
        assertThatThrownBy(() -> media.publicGallery(productId, sku.sku()))
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void anotherCustomersSkuIsNotThereForAnOrderOrAQuote() {
        CustomerSku sku = issue(snapshot(), "0312345678");
        UUID other = tx.execute(s -> ReferenceRows.customer(entityManager));

        customerSkus.requireVisibleTo(List.of(sku.sku(), sourceSku), customerId);
        customerSkus.requireVisibleTo(List.of(sourceSku, "NO-SUCH-SKU"), other);
        assertThatThrownBy(() -> customerSkus.requireVisibleTo(List.of(sourceSku, sku.sku().toLowerCase()), other))
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VARIANT_NOT_FOUND);
        assertThatThrownBy(() -> customerSkus.requireVisibleTo(List.of(sku.sku()), null))
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VARIANT_NOT_FOUND);
    }

    /** 5-3 (kltn-docs 13 BR-01, BR-02): what a customer may be told about is its own ACTIVE SKUs, and only those asked. */
    @Test
    void theActiveSkusACustomerOwnsAmongThoseAsked() {
        CustomerSku sku = issue(snapshot(), "0312345678");
        UUID other = tx.execute(s -> ReferenceRows.customer(entityManager));

        assertThat(customerSkus.activeOwnedBy(customerId, List.of(sku.sku(), sourceSku, "NO-SUCH-SKU")))
                .containsExactly(sku.sku());
        assertThat(customerSkus.activeOwnedBy(other, List.of(sku.sku(), sourceSku))).isEmpty();
        assertThat(customerSkus.activeOwnedBy(customerId, List.of())).isEmpty();
        assertThat(customerSkus.activeOwnedBy(null, List.of(sku.sku()))).isEmpty();

        tx.executeWithoutResult(s -> jdbc.update("UPDATE product.customer_skus SET status = 'BLOCKED' WHERE sku = ?",
                sku.sku()));
        assertThat(customerSkus.activeOwnedBy(customerId, List.of(sku.sku()))).as("blocked").isEmpty();
    }

    @Test
    void aVariantNotOnSaleOrAnotherCustomersSkuCannotBeASource() {
        UUID snapshot = snapshot();
        tx.executeWithoutResult(s -> jdbc.update("UPDATE product.variants SET status = 'BLOCKED' WHERE sku = ?", sourceSku));
        assertThatThrownBy(() -> issue(snapshot, "0312345678"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CUSTOMER_SKU_SOURCE_UNAVAILABLE);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE product.variants SET status = 'ACTIVE' WHERE sku = ?", sourceSku));

        CustomerSku issued = issue(snapshot, "0312345678");
        // A customer's SKU is not a variant at all, so it is no source.
        assertThatThrownBy(() -> tx.execute(s -> customerSkus.issue(new IssueCustomerSkuCommand(snapshot(),
                customerId, "0312345678", issued.sku(), null))))
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VARIANT_NOT_FOUND);
        UUID other = tx.execute(s -> ReferenceRows.customer(entityManager));
        assertThatThrownBy(() -> tx.execute(s -> customerSkus.issue(new IssueCustomerSkuCommand(snapshot,
                other, "KH00000001", sourceSku, null))))
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void theDatabaseKeepsAnOwnersSkuAsIssued() {
        CustomerSku sku = issue(snapshot(), "0312345678");
        // The source is a variant of the same product.
        UUID elsewhere = tx.execute(s -> ReferenceRows.product(entityManager)).variantId();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("INSERT INTO product.customer_skus (id, "
                + "product_id, variant_id, customer_id, design_snapshot_id, sku, name) VALUES (gen_random_uuid(), ?, ?, "
                + "?, ?, 'X-FORGED-01', 'x')", productId, elsewhere, customerId, snapshot())))
                .hasMessageContaining("drawn on a variant of its own product");
        // A code is one thing's: never a catalogue variant's.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("INSERT INTO product.customer_skus (id, "
                + "product_id, variant_id, customer_id, design_snapshot_id, sku, name) VALUES (gen_random_uuid(), ?, ?, "
                + "?, ?, ?, 'x')", productId, sku.sourceVariantId(), customerId, snapshot(), sourceSku)))
                .hasMessageContaining("is a catalogue variant's");
        // Who and what it is never changes.
        UUID other = tx.execute(s -> ReferenceRows.customer(entityManager));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE product.customer_skus SET customer_id = ? WHERE id = ?", other, sku.id())))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE product.customer_skus SET sku = 'X-RENAMED-01' WHERE id = ?", sku.id())))
                .hasMessageContaining("immutable");
        // A variant cannot take the code either.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("INSERT INTO product.variants (id, "
                + "product_id, sku, name, attribute_signature) VALUES (gen_random_uuid(), ?, ?, 'x', 'S-X')", productId,
                sku.sku())))
                .hasMessageContaining("is a customer's SKU");
    }

    @Test
    void aSkuIsBlockedPutBackOnSaleAndRetiredThroughItsOwnEndpoints() throws Exception {
        CustomerSku sku = issue(snapshot(), "0312345678");
        String base = "/api/v1/customer-skus/" + sku.id();

        mvc.perform(post(base + "/activation")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CUSTOMER_SKU_TRANSITION"));
        mvc.perform(post(base + "/blocking")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(sku.id().toString()))
                .andExpect(jsonPath("$.data.sku").value(sku.sku()))
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andExpect(jsonPath("$.data.sourceVariantId").value(sku.sourceVariantId().toString()))
                .andExpect(jsonPath("$.data.unitsPerCarton").value(50));
        assertThat(customerSkus.activeOwnedBy(customerId, List.of(sku.sku()))).isEmpty();

        // Back on sale only while its variant is: a blocked blank keeps its SKUs off sale too.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE product.variants SET status = 'BLOCKED' WHERE sku = ?",
                sourceSku));
        mvc.perform(post(base + "/activation")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CUSTOMER_SKU_TRANSITION"));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE product.variants SET status = 'ACTIVE' WHERE sku = ?",
                sourceSku));
        mvc.perform(post(base + "/activation")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mvc.perform(post(base + "/obsoletion")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OBSOLETE"));
        assertThat(jdbc.queryForObject("SELECT obsoleted_at IS NOT NULL FROM product.customer_skus WHERE id = ?",
                Boolean.class, sku.id())).isTrue();
        mvc.perform(post(base + "/blocking")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CUSTOMER_SKU_TRANSITION"));
        assertThat(jdbc.queryForList("""
                SELECT to_status FROM product.product_change_log
                 WHERE subject_id = ? AND change_type = 'VARIANT_STATUS' ORDER BY occurred_at, id""", String.class,
                sku.id())).containsExactly("BLOCKED", "ACTIVE", "OBSOLETE");

        // An id that is no customer's SKU - a variant's included - is 404, here and on the variant endpoints alike.
        mvc.perform(post("/api/v1/customer-skus/" + sku.sourceVariantId() + "/blocking"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_SKU_NOT_FOUND"));
        mvc.perform(post("/api/v1/products/" + productId + "/variants/" + sku.id() + "/blocking"))
                .andExpect(status().isNotFound());
    }

    @Test
    void discontinuingTheProductRetiresItsCustomersSkus() {
        CustomerSku sku = issue(snapshot(), "0312345678");
        long version = jdbc.queryForObject("SELECT version FROM product.products WHERE id = ?", Long.class, productId);
        products.discontinue(productId, "Ngung san xuat", version);

        assertThat(customerSkus.findById(sku.id())).hasValueSatisfying(s -> assertThat(s.status()).isEqualTo("OBSOLETE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.product_change_log WHERE subject_id = ? AND "
                + "change_type = 'VARIANT_STATUS' AND to_status = 'OBSOLETE' AND reason = 'Ngung san xuat'",
                Long.class, sku.id())).isOne();
    }

    @Test
    void aCustomersCodeIsTheirTaxCodeOrAShortIdCode() {
        UUID id = UUID.fromString("0192f0a0-0000-7000-8000-00000abcdef1");
        assertThat(new CustomerSummary(id, null, "A", "a@x", null, "0312345678-001", null, "ACTIVE", 0,
                Instant.EPOCH).code()).isEqualTo("0312345678-001");
        assertThat(new CustomerSummary(id, null, "A", "a@x", null, null, null, "ACTIVE", 0, Instant.EPOCH).code())
                .isEqualTo("KH0ABCDEF1");
    }
}
