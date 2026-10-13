package com.stockflow.product.internal.service;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.domain.AttributeValueDetails;
import com.stockflow.product.internal.domain.AxisSpec;
import com.stockflow.product.internal.service.ProductAttributeService.OptionView;
import com.stockflow.support.DemoData;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Two overlapping axis replacements on one draft product, each committed for real. Not
 * {@code @Transactional}: what matters is what happens between two transactions, which a
 * test-managed one would hide.
 *
 * <p>Without the product row lock the second replacement loads the committed (empty) axis list
 * while the first is still open and ends in a unique-constraint failure; with it, the second waits
 * and then replaces what the first left. Everything committed here is deleted afterwards.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class ProductAttributeConcurrencyIntegrationTest {

    private static final UUID COLOR = DemoData.demoId("demo:attr:COLOR");

    @Autowired ProductAttributeService service;
    @Autowired ProductService products;
    @Autowired AttributeDictionaryService dictionary;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @MockitoBean CurrentUserProvider users;

    private UUID productId;
    private String code;
    private UUID user;
    private UUID blue;
    private UUID red;

    @AfterEach
    void cleanUp() {
        // Hikari runs with auto-commit off, so plain JdbcTemplate calls would never commit.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> deleteWhatWasCommitted());
    }

    private void deleteWhatWasCommitted() {
        if (productId != null) {
            jdbc.update("delete from inventory.inventory_items where sku = ?", code);
            jdbc.update("delete from product.variants where product_id = ?", productId);
            jdbc.update("delete from product.products where id = ?", productId);
        }
        if (blue != null) {
            jdbc.update("delete from product.attribute_values where id in (?, ?)", blue, red);
        }
        if (user != null) {
            jdbc.update("delete from identity.app_user where id = ?", user);
        }
    }

    @Test
    void concurrentAxisSavesSerialiseOnTheProduct() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        user = tx.execute(s -> ReferenceRows.user(entityManager));
        when(users.current()).thenReturn(Optional.of(new CurrentUser(user, "test-" + user, Set.of(), Set.of(),
                DataScope.ALL, Set.of())));
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        code = "CONC-" + suffix;
        productId = tx.execute(s -> products.create(new CreateProductCommand(code, "Ten " + suffix,
                "Name " + suffix, null, null, null, null, null, TaxClass.STANDARD, ProductKind.CUSTOMIZABLE))
                .productId());
        blue = tx.execute(s -> newValue("BLUE" + suffix));
        red = tx.execute(s -> newValue("RED" + suffix));

        CountDownLatch firstHoldsTheLock = new CountDownLatch(1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            service.replaceAxes(productId, List.of(new AxisSpec(COLOR, 0, List.of(blue))));
            firstHoldsTheLock.countDown();
            pause(800);
        }));
        assertThat(firstHoldsTheLock.await(30, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status ->
                service.replaceAxes(productId, List.of(new AxisSpec(COLOR, 0, List.of(red))))));

        first.get(30, TimeUnit.SECONDS);
        second.get(30, TimeUnit.SECONDS);

        var view = tx.execute(status -> service.get(productId));
        assertThat(view.axes()).singleElement().satisfies(a ->
                assertThat(a.options()).extracting(OptionView::valueId).containsExactly(red));
    }

    private UUID newValue(String valueCode) {
        return dictionary.addValue(COLOR, valueCode, new AttributeValueDetails(valueCode, null, "#123456", 5)).values()
                .stream().filter(v -> v.code().equals(valueCode)).findFirst().orElseThrow().id();
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
