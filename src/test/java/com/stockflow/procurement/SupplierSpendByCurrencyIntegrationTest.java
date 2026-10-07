package com.stockflow.procurement;

import com.stockflow.common.id.Identifiers;
import com.stockflow.procurement.api.CreatePOLineCommand;
import com.stockflow.procurement.api.CreatePurchaseOrderCommand;
import com.stockflow.procurement.api.ProcurementService;
import com.stockflow.procurement.api.SupplierSpendReportQuery;
import com.stockflow.procurement.api.SupplierSpendSummary;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spend report never adds amounts in different currencies. It used to: 64,000,000 VND plus
 * 602.50 USD was reported as one total of 64,000,602.50 for the supplier.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class SupplierSpendByCurrencyIntegrationTest {

    @Autowired
    private ProcurementService procurement;

    @Autowired
    private JdbcTemplate jdbc;

    /** Hikari runs with auto-commit off, so a bare JdbcTemplate insert would be rolled back. */
    @Autowired
    private TransactionTemplate tx;

    /** With an email: a supplier reached by email must have one once the supplier PR's CHECK lands. */
    private UUID supplier() {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO procurement.supplier (id, code, name, email, status, version, created_at)
                VALUES (?, ?, 'Spend test supplier', 'spend@example.com', 'ACTIVE', 0, NOW())""",
                id, "SPEND-" + id.toString().substring(24)));
        return id;
    }

    private void approvedOrder(UUID supplierId, String currency, int quantity, String unitPrice) {
        var order = procurement.createPurchaseOrder(new CreatePurchaseOrderCommand(supplierId, currency,
                LocalDate.now().plusDays(10),
                List.of(new CreatePOLineCommand("SPEND-SKU", "spend test", quantity, new BigDecimal(unitPrice)))));
        procurement.approve(order.purchaseOrderId());
    }

    private List<SupplierSpendSummary> report(UUID supplierId, String currency) {
        return procurement.supplierSpend(new SupplierSpendReportQuery(0, 20, supplierId, currency, null, null))
                .items();
    }

    @Test
    void oneRowPerCurrencyAndAmountsAreNeverAddedAcrossCurrencies() {
        UUID supplierId = supplier();
        approvedOrder(supplierId, "VND", 10, "5000000");
        approvedOrder(supplierId, "VND", 4, "3500000");
        approvedOrder(supplierId, "USD", 5, "120.50");

        List<SupplierSpendSummary> rows = report(supplierId, null);

        assertThat(rows).extracting(SupplierSpendSummary::currency).containsExactly("USD", "VND");
        assertThat(rows.get(0).totalSpend()).isEqualByComparingTo("602.50");
        assertThat(rows.get(0).purchaseOrderCount()).isEqualTo(1);
        assertThat(rows.get(1).totalSpend()).isEqualByComparingTo("64000000");
        assertThat(rows.get(1).purchaseOrderCount()).isEqualTo(2);
        assertThat(procurement.supplierSpend(new SupplierSpendReportQuery(0, 20, supplierId, null, null, null))
                .totalElements()).isEqualTo(2);
    }

    @Test
    void theCurrencyFilterNarrowsToOneCurrencyAndIgnoresCase() {
        UUID supplierId = supplier();
        approvedOrder(supplierId, "VND", 1, "100000");
        approvedOrder(supplierId, "USD", 1, "10");

        assertThat(report(supplierId, "usd")).singleElement().satisfies(row -> {
            assertThat(row.currency()).isEqualTo("USD");
            assertThat(row.totalSpend()).isEqualByComparingTo("10");
        });
    }
}
