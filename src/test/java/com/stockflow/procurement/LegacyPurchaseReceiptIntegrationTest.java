package com.stockflow.procurement;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.domain.GoodsReceiptType;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ProductionReceivables;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4A-8, as V20261113000200 left it (4A-9): the purchase receipts that were there before 4A-5 are read and listed as they
 * stand, but only those the migration let through exist - CLOSED and IN_PUTAWAY ones and cancelled ones; a draft, a
 * confirmed one and one waiting for approval stopped the migration. Counting, confirming or cancelling one is refused
 * with INVALID_RECEIPT_TRANSITION - purchasing is gone - and no new one can be written at all: the database refuses the
 * insert. Rows are written by SQL with the row triggers off, as the migration's own test does: no service makes them.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class LegacyPurchaseReceiptIntegrationTest {

    @Autowired GoodsReceipts receipts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private UUID legacy(String status) {
        UUID id = Identifiers.newId();
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        boolean posted = status.equals("CLOSED") || status.equals("IN_PUTAWAY");
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, warehouse_id, status,
                        received_at, received_by, confirmed_at, confirmed_by, closed_at)
                    VALUES (?, ?, 'PURCHASE', ?, ?, NOW(), ?, CASE WHEN ? THEN NOW() END,
                            CASE WHEN ? THEN CAST(? AS uuid) END, CASE WHEN ? THEN NOW() END)""",
                    id, "GR-LEG-" + id.toString().substring(24), DemoData.WAREHOUSE_HCM, status, user, posted, posted,
                    user, status.equals("CLOSED"));
        });
        return id;
    }

    private static ErrorCode code(Throwable e) {
        assertThat(e).isInstanceOf(BusinessException.class);
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("a historical purchase receipt is read and listed by its type")
    void readAndListed() {
        UUID closed = legacy("CLOSED");
        UUID putaway = legacy("IN_PUTAWAY");

        assertThat(receipts.get(closed).receiptType()).isEqualTo(GoodsReceiptType.PURCHASE);
        assertThat(receipts.get(closed).status()).isEqualTo(GoodsReceiptStatus.CLOSED);
        assertThat(receipts.get(putaway).status()).isEqualTo(GoodsReceiptStatus.IN_PUTAWAY);
        assertThat(receipts.list(null, DemoData.WAREHOUSE_HCM, null, null, null, null, GoodsReceiptType.PURCHASE, 0,
                200, null).items()).extracting(GoodsReceipts.Row::id).contains(closed, putaway);
    }

    @Test
    @DisplayName("counting, confirming or cancelling one is INVALID_RECEIPT_TRANSITION: purchasing is gone")
    void countConfirmAndCancelAreRefused() {
        for (String status : List.of("CLOSED", "IN_PUTAWAY")) {
            UUID id = legacy(status);
            UUID clerk = tx.execute(s -> ReferenceRows.user(entityManager));
            assertThatThrownBy(() -> receipts.replaceLines(id, List.of(GoodsReceipts.NewLine.ofProductionOrderLine(
                    UUID.randomUUID(), "HCM-RCV01", ProductionReceivables.newCartons(1))))).as("count " + status)
                    .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION));
            assertThatThrownBy(() -> receipts.confirm(id, clerk)).as("confirm " + status)
                    .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION));
            assertThatThrownBy(() -> receipts.cancel(id)).as("cancel " + status)
                    .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION));
            assertThat(receipts.get(id).status()).as("untouched").isEqualTo(GoodsReceiptStatus.valueOf(status));
        }
    }

    @Test
    @DisplayName("no new purchase receipt can be written: the database refuses it")
    void aNewOneIsRefusedByTheDatabase() {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, warehouse_id, status,
                    received_at, received_by)
                VALUES (?, 'GR-LEG-NEW', 'PURCHASE', ?, 'DRAFT', NOW(), ?)""",
                Identifiers.newId(), DemoData.WAREHOUSE_HCM, user)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("purchasing was removed");
    }
}
