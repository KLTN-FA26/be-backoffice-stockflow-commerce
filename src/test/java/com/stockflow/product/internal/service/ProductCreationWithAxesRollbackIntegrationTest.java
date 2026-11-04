package com.stockflow.product.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.domain.AttributeDataType;
import com.stockflow.product.internal.domain.AttributeDetails;
import com.stockflow.product.internal.domain.AttributeValueDetails;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.when;

/**
 * A product created with axes is one transaction (spec D2): a rule-built SKU that is already taken
 * refuses the whole creation, and nothing of the product is left behind. Not {@code @Transactional},
 * since a test-managed transaction would hide whether the refused one really rolled back. Everything
 * committed here is deleted afterwards.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class ProductCreationWithAxesRollbackIntegrationTest {

    @Autowired ProductService products;
    @Autowired ProductVariantService variants;
    @Autowired AttributeDictionaryService dictionary;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @MockitoBean CurrentUserProvider users;

    private UUID user;
    private UUID holder;
    private UUID attribute;
    private String code;

    @AfterEach
    void cleanUp() {
        // Hikari runs with auto-commit off, so plain JdbcTemplate calls would never commit.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            if (code != null) {
                // Only there if the test failed: the refused product must not leak into later runs.
                jdbc.update("delete from inventory.inventory_items where sku like ?", code + "-%");
                jdbc.update("delete from product.variant_attribute_values where variant_id in (select v.id from "
                        + "product.variants v join product.products p on p.id = v.product_id where p.code = ?)", code);
                jdbc.update("delete from product.variants where product_id in "
                        + "(select id from product.products where code = ?)", code);
                jdbc.update("delete from product.product_attribute_options where product_attribute_id in (select pa.id "
                        + "from product.product_attributes pa join product.products p on p.id = pa.product_id "
                        + "where p.code = ?)", code);
                jdbc.update("delete from product.product_attributes where product_id in "
                        + "(select id from product.products where code = ?)", code);
                jdbc.update("delete from product.products where code = ?", code);
            }
            if (holder != null) {
                jdbc.update("delete from inventory.inventory_items where sku in "
                        + "(select sku from product.variants where product_id = ?)", holder);
                jdbc.update("delete from product.variants where product_id = ?", holder);
                jdbc.update("delete from product.products where id = ?", holder);
            }
            if (attribute != null) {
                jdbc.update("delete from product.attribute_values where attribute_id = ?", attribute);
                jdbc.update("delete from product.attributes where id = ?", attribute);
            }
            if (user != null) {
                jdbc.update("delete from identity.app_user where id = ?", user);
            }
        });
    }

    @Test
    void aTakenRuleSkuRollsBackTheWholeProduct() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        user = tx.execute(s -> ReferenceRows.user(entityManager));
        when(users.current()).thenReturn(Optional.of(new CurrentUser(user, "test-" + user, Set.of(), Set.of(),
                DataScope.ALL, Set.of())));
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        code = "ROLL-" + suffix;

        attribute = tx.execute(s -> dictionary.create("SIZE" + suffix, AttributeDataType.SELECT,
                new AttributeDetails("Size", null, false, false, 0)).id());
        UUID small = tx.execute(s -> dictionary.addValue(attribute, "S", new AttributeValueDetails("S", null, null, 1))
                .values().getFirst().id());
        // Another product already sells the SKU the rule would build for the new product's first combination.
        holder = tx.execute(s -> products.create(command("HOLD-" + suffix, List.of())).productId());
        tx.executeWithoutResult(s -> variants.add(holder,
                new ProductVariantService.NewVariant(code + "-S", "Taken", null, null)));

        BusinessException refused = catchThrowableOfType(BusinessException.class, () -> tx.executeWithoutResult(s ->
                products.create(command(code, List.of(new CreateProductCommand.Axis(attribute, null, List.of(small)))))));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.VARIANT_SKU_ALREADY_EXISTS);
        assertThat(jdbc.queryForObject("select count(*) from product.products where code = ?", Long.class, code))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from product.product_attributes where attribute_id = ?",
                Long.class, attribute)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from product.variants where sku like ?", Long.class,
                code + "%")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from inventory.inventory_items where sku like ?", Long.class,
                code + "%")).isEqualTo(1);
    }

    private static CreateProductCommand command(String code, List<CreateProductCommand.Axis> axes) {
        return new CreateProductCommand(code, "Ten " + code, "Name " + code, null, null, null, null, null,
                TaxClass.STANDARD, ProductKind.STANDARD, axes);
    }
}
