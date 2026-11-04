package com.stockflow.product.internal.service;

import com.stockflow.product.internal.service.CategoryAttributeService.CategoryAttributeEntry;
import com.stockflow.product.internal.service.CategoryAttributeService.CategoryAttributeView;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.stockflow.product.internal.domain.AttributeRole.DESCRIPTIVE;
import static com.stockflow.product.internal.domain.AttributeRole.VARIANT_AXIS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two overlapping category-set replacements, each committed for real. Not {@code @Transactional}: the
 * point is what happens between two transactions, which a test-managed one would hide. The demo
 * category's set is captured first and restored afterwards, in transactions of their own.
 *
 * <p>Without the category row lock the second replacement reads the committed set while the first is
 * still open and ends in a raw constraint or optimistic-lock exception (a 500). With it, the second
 * waits, then replaces what the first left.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
class CategoryAttributeConcurrencyIntegrationTest {

    private static final UUID SOFA = DemoData.demoId("demo:cat:SOFA");
    private static final UUID COLOR = DemoData.demoId("demo:attr:COLOR");
    private static final UUID MATERIAL = DemoData.demoId("demo:attr:MATERIAL");
    private static final UUID LENGTH = DemoData.demoId("demo:attr:LENGTH");

    @Autowired CategoryAttributeService service;
    @Autowired PlatformTransactionManager transactionManager;

    private List<CategoryAttributeEntry> original;

    @BeforeEach
    void captureTheSet() {
        original = new TransactionTemplate(transactionManager).execute(status -> service.list(SOFA).stream()
                .map(v -> new CategoryAttributeEntry(v.attributeId(), v.defaultRole(), v.required(), v.sortOrder()))
                .toList());
    }

    @AfterEach
    void restoreTheSet() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> service.replace(SOFA, original));
    }

    @Test
    void aSecondReplacementWaitsForTheFirstAndThenWins() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstHoldsTheLock = new CountDownLatch(1);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            service.replace(SOFA, List.of(new CategoryAttributeEntry(LENGTH, VARIANT_AXIS, false, 0)));
            firstHoldsTheLock.countDown();
            pause(800);
        }));
        assertThat(firstHoldsTheLock.await(30, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status ->
                service.replace(SOFA, List.of(new CategoryAttributeEntry(COLOR, VARIANT_AXIS, true, 0),
                        new CategoryAttributeEntry(MATERIAL, DESCRIPTIVE, false, 1)))));

        first.get(30, TimeUnit.SECONDS);
        second.get(30, TimeUnit.SECONDS);

        List<CategoryAttributeView> now = tx.execute(status -> service.list(SOFA));
        assertThat(now).extracting(CategoryAttributeView::attributeCode).containsExactly("COLOR", "MATERIAL");
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
