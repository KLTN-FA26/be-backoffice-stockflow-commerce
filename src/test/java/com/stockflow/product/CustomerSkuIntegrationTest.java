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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

/**
 * A customer's own SKU (kltn-docs 12 §4.5, 01 §4.8, B20): issued for an approved design snapshot as "product - customer
 * - number", ACTIVE at once, with the source variant's carton spec and logistics; one per snapshot; a second design of
 * the same customer numbers on; unseen in the public catalogue; refused from a variant that is not on sale.
 */
@IntegrationTest
@Import(PostgresContainer.class)
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
        productId = products.create(new CreateProductCommand(productCode, "Ly giấy 12oz", "Paper cup 12oz", null,
                null, "Ly", null, null, TaxClass.STANDARD, List.of())).productId();
        sourceSku = variants.list(productId, 0, 1).items().getFirst().sku();
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE product.products SET status = 'APPROVED', submitted_by = ?, submitted_at = NOW(), "
                    + "approved_by = ?, approved_at = NOW() WHERE id = ?", ReferenceRows.user(entityManager),
                    ReferenceRows.user(entityManager), productId);
            jdbc.update("UPDATE product.variants SET status = 'ACTIVE' WHERE product_id = ?", productId);
        });
        // The variant's carton spec: what a customer orders in (kltn-docs 14 BR-08).
        units.add(new Sku(sourceSku), new UomConversionDraft("CARTON", 50, new BigDecimal("4.2"),
                new BigDecimal("60"), new BigDecimal("40"), new BigDecimal("35"), false, true, true));
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
        // The variant's logistics as a person entered them: they follow; its barcode names its own goods and does not.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE inventory.inventory_items SET weight_kg = 0.012, "
                + "length_cm = 9, barcode = '8930000000017' WHERE sku = ?", sourceSku));
        CustomerSku sku = issue(snapshot(), "0312345678");
        var copied = jdbc.queryForMap("SELECT weight_kg, length_cm, barcode FROM "
                + "inventory.inventory_items WHERE sku = ?", sku.sku());
        assertThat((BigDecimal) copied.get("weight_kg")).isEqualByComparingTo("0.012");
        assertThat((BigDecimal) copied.get("length_cm")).isEqualByComparingTo("9");
        assertThat(copied.get("barcode")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.product_change_log WHERE subject_id = ? AND "
                + "change_type = 'VARIANT_ADDED'", Long.class, sku.variantId())).isEqualTo(1);

        assertThat(sku.sku()).isEqualTo(productCode + "-0312345678-01");
        assertThat(sku.status()).isEqualTo("ACTIVE");
        assertThat(sku.ownerCustomerId()).isEqualTo(customerId);
        assertThat(sku.sourceSku()).isEqualTo(sourceSku);
        assertThat(sku.name()).isEqualTo("Ly quán Cà phê A");
        assertThat(customerSkus.ownerOf(sku.sku())).contains(customerId);
        assertThat(customerSkus.ownerOf(sourceSku)).isEmpty();

        var cartons = units.conversionsOf(List.of(new Sku(sku.sku()))).get(sku.sku()).stream()
                .filter(u -> u.unitOfMeasure().equals("CARTON")).toList();
        assertThat(cartons).singleElement().satisfies(carton -> {
            assertThat(carton.factor()).isEqualTo(50);
            assertThat(carton.defaultShipping()).isTrue();
            assertThat(carton.weightKg()).isEqualByComparingTo("4.2");
        });
        assertThat(inventory.item(new Sku(sku.sku()))).isNotNull();
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
            for (String variantSku : List.of(sourceSku, sku.sku())) {
                jdbc.update("INSERT INTO product.media (id, variant_id, url, is_published, published_at, published_by, "
                        + "created_by) SELECT ?, v.id, 'https://example.test/' || v.sku || '.jpg', TRUE, NOW(), "
                        + "md5('demo:user:approver')::uuid, md5('demo:user:editor')::uuid::text FROM product.variants v "
                        + "WHERE v.sku = ?", UUID.randomUUID(), variantSku);
            }
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

        tx.executeWithoutResult(s -> jdbc.update("UPDATE product.variants SET status = 'BLOCKED' WHERE sku = ?",
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
        assertThatThrownBy(() -> tx.execute(s -> customerSkus.issue(new IssueCustomerSkuCommand(snapshot(),
                customerId, "0312345678", issued.sku(), null))))
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CUSTOMER_SKU_SOURCE_UNAVAILABLE);
        UUID other = tx.execute(s -> ReferenceRows.customer(entityManager));
        assertThatThrownBy(() -> tx.execute(s -> customerSkus.issue(new IssueCustomerSkuCommand(snapshot,
                other, "KH00000001", sourceSku, null))))
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void theDatabaseKeepsAnOwnersSkuAsIssued() {
        CustomerSku sku = issue(snapshot(), "0312345678");
        // The source is an ordinary variant of the same product, never another customer's SKU.
        UUID foreignSnapshot = snapshot();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("INSERT INTO product.variants (id, "
                + "product_id, sku, name, status, is_default, attribute_signature, position, version, created_at, "
                + "owner_customer_id, design_snapshot_id, source_variant_id) VALUES (gen_random_uuid(), ?, 'X-FORGED-01', "
                + "'x', 'ACTIVE', FALSE, 'CUSTOMER:X-FORGED-01', 99, 0, NOW(), ?, ?, ?)", productId, customerId,
                foreignSnapshot, sku.variantId())))
                .hasMessageContaining("ordinary variant of its own product");

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("UPDATE product.variants SET "
                + "owner_customer_id = NULL, design_snapshot_id = NULL, source_variant_id = NULL WHERE id = ?",
                sku.variantId())))
                .hasMessageContaining("keeps its owner");
        UUID other = tx.execute(s -> ReferenceRows.customer(entityManager));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("UPDATE product.variants SET "
                + "owner_customer_id = ? WHERE id = ?", other, sku.variantId())))
                .hasMessageContaining("keeps its owner");
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
