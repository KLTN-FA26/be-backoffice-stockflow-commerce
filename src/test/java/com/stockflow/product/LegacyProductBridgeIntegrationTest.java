package com.stockflow.product;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.api.TaxClass;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #68 hotfix: a product created and approved through the admin service — which still writes
 * the legacy tables — is visible to the publication path, carries its category, and takes a variant,
 * because V20261009000100 copies it to the PIM tables in the same transaction.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class LegacyProductBridgeIntegrationTest {

    @Autowired ProductService products;
    @Autowired ProductPublication publication;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    @Test
    @DisplayName("admin create -> submit -> approve reaches product.products; publication sees it; a variant attaches")
    void adminProductReachesPim() {
        UUID category = Identifiers.newId();
        String suffix = category.toString().substring(0, 6).toUpperCase();
        tx.executeWithoutResult(s -> jdbc.update(
                "INSERT INTO product.category (id, code, name, version, created_at) VALUES (?, ?, 'Ly giấy', 0, NOW())",
                category, "CUPS-" + suffix));
        UUID submitter = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID approver = tx.execute(s -> ReferenceRows.user(entityManager));

        ProductSummary created = products.create(new CreateProductCommand("cup-" + suffix.toLowerCase(), "Ly giấy 12oz",
                "Paper cup 12oz", category, null, null, "StockFlow", TaxClass.STANDARD, true, List.of(),
                null, null, null, null, null, null, null, null, null, false, false, null, false, null));
        UUID id = created.productId();

        // Before the bridge this was PRODUCT_NOT_FOUND: the publication path reads product.products.
        assertThat(publication.read(id).status()).isEqualTo(ProductStatus.DRAFT);
        assertThat(publication.ecommerce(id)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT code FROM product.products WHERE id = ?", String.class, id))
                .isEqualTo("CUP-" + suffix);

        products.submit(id, submitter);
        products.approve(id, approver);
        var approved = publication.read(id);
        assertThat(approved.status()).isEqualTo(ProductStatus.APPROVED);
        assertThat(approved.categoryId()).isEqualTo(category);

        // Publishing now stops at the next real gap - no variant yet (SCRUM-45) - not at "not found".
        assertThatThrownBy(() -> publication.publish(id))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PRODUCT_SKU_REQUIRED));

        // The variant table's foreign key to product.products now accepts it.
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO product.variants (id, product_id, sku, name, attribute_signature)
                VALUES (?, ?, ?, 'Trắng', '')""", Identifiers.newId(), id, "CUP-" + suffix + "-WHITE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.variants WHERE product_id = ?", Integer.class, id))
                .isEqualTo(1);
    }
}
