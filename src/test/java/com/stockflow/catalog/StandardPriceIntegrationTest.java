package com.stockflow.catalog;

import com.stockflow.catalog.api.CatalogService;
import com.stockflow.catalog.internal.service.StandardPriceService;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * kltn-docs 13 BR-04 as changed on 2026-11-26, against a real PostgreSQL: a catalogue variant's standard price, kept by
 * the Sales Manager by carton break, read by quotes through {@link CatalogService#standardPrice}. A customer's own SKU has
 * none. Security is off in this context; who may edit is pinned by {@code PermissionMatrixTest}.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class StandardPriceIntegrationTest {

    @Autowired StandardPriceService prices;
    @Autowired CatalogService catalog;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private static StandardPriceService.TierInput tier(int from, int price) {
        return new StandardPriceService.TierInput(from, BigDecimal.valueOf(price));
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    void theSalesManagerSetsReplacesAndRemovesAVariantsStandardPrice() {
        String variant = tx.execute(s -> ReferenceRows.product(entityManager)).sku();

        StandardPriceService.PriceView created = prices.create(variant, List.of(tier(50, 1150), tier(10, 1300)),
                "Mau in 1-2 mau");

        assertThat(created.moq()).isEqualTo(10);
        assertThat(created.tiers()).extracting(StandardPriceService.TierInput::minQuantity).containsExactly(10, 50);
        assertThat(catalog.standardPrice(variant.toLowerCase())).hasValueSatisfying(schedule -> {
            assertThat(schedule.moq()).isEqualTo(10);
            assertThat(schedule.tiers().getLast().unitPrice().amount()).isEqualByComparingTo("1150");
        });
        assertThatThrownBy(() -> prices.create(variant, List.of(tier(1, 1000)), null))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.CONFLICT));

        StandardPriceService.PriceView replaced = prices.replace(variant, List.of(tier(1, 1200)), null,
                created.version());
        assertThat(replaced.version()).isGreaterThan(created.version());
        assertThatThrownBy(() -> prices.replace(variant, List.of(tier(1, 900)), null, created.version()))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.OPTIMISTIC_LOCK));

        prices.delete(variant, replaced.version());
        assertThat(catalog.standardPrice(variant)).isEmpty();
        assertThatThrownBy(() -> prices.get(variant))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.STANDARD_PRICE_NOT_FOUND));
    }

    @Test
    void aCustomersOwnSkuOrAnUnknownSkuHasNone() {
        UUID customer = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
                VALUES (?, 'Khach gia chuan', ?, 'ACTIVE', 0, NOW())""", customer, "sp-" + customer + "@example.com"));
        String own = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 10).sku();

        assertThatThrownBy(() -> prices.create(own, List.of(tier(1, 1000)), null))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.STANDARD_PRICE_VARIANT_REQUIRED));
        assertThatThrownBy(() -> prices.create("NO-SUCH-VARIANT-" + customer.toString().substring(0, 8),
                List.of(tier(1, 1000)), null))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.VARIANT_NOT_FOUND));
    }
}
