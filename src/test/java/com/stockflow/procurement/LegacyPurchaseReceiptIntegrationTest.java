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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4A-8: the purchase receipts that were there before 4A-5 (a blind receipt of a supplier, as the data of an old
 * installation has them) are read and listed as they stand, and cancelled while they counted nothing in; counting or
 * confirming one is refused with INVALID_RECEIPT_TRANSITION - purchasing is gone. Rows are written by SQL: no service
 * makes them any more.
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
        UUID requester = tx.execute(s -> ReferenceRows.user(entityManager));
        boolean waiting = status.equals("PENDING_APPROVAL");
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, supplier_id, blind_reason,
                    warehouse_id, status, received_at, received_by, approval_requested_at, approval_requested_by)
                SELECT ?, ?, 'PURCHASE', sp.id, 'goods came without a purchase order', ?, ?, NOW(), ?,
                       CASE WHEN ? THEN NOW() END, CASE WHEN ? THEN CAST(? AS uuid) END
                  FROM procurement.suppliers sp ORDER BY sp.code LIMIT 1""",
                id, "GR-LEG-" + id.toString().substring(24), DemoData.WAREHOUSE_HCM, status, user, waiting, waiting,
                requester));
        return id;
    }

    private static ErrorCode code(Throwable e) {
        assertThat(e).isInstanceOf(BusinessException.class);
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("a historical purchase receipt is read and listed by its type")
    void readAndListed() {
        UUID draft = legacy("DRAFT");
        UUID waiting = legacy("PENDING_APPROVAL");

        assertThat(receipts.get(draft).receiptType()).isEqualTo(GoodsReceiptType.PURCHASE);
        assertThat(receipts.get(draft).status()).isEqualTo(GoodsReceiptStatus.DRAFT);
        assertThat(receipts.get(waiting).status()).isEqualTo(GoodsReceiptStatus.PENDING_APPROVAL);
        assertThat(receipts.list(null, DemoData.WAREHOUSE_HCM, null, null, null, null, GoodsReceiptType.PURCHASE, 0,
                200, null).items()).extracting(GoodsReceipts.Row::id).contains(draft, waiting);
    }

    @Test
    @DisplayName("counting or confirming one is INVALID_RECEIPT_TRANSITION: purchasing is gone")
    void countAndConfirmAreRefused() {
        for (String status : List.of("DRAFT", "PENDING_APPROVAL")) {
            UUID id = legacy(status);
            UUID clerk = tx.execute(s -> ReferenceRows.user(entityManager));
            assertThatThrownBy(() -> receipts.replaceLines(id, List.of(GoodsReceipts.NewLine.ofProductionOrderLine(
                    UUID.randomUUID(), "HCM-RCV01", ProductionReceivables.newCartons(1))))).as("count " + status)
                    .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION));
            assertThatThrownBy(() -> receipts.confirm(id, clerk)).as("confirm " + status)
                    .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.INVALID_RECEIPT_TRANSITION));
            assertThat(receipts.get(id).status()).as("untouched").isEqualTo(GoodsReceiptStatus.valueOf(status));
        }
    }

    @Test
    @DisplayName("a DRAFT and a PENDING_APPROVAL one are cancelled: they counted nothing in")
    void cancelled() {
        for (String status : List.of("DRAFT", "PENDING_APPROVAL")) {
            UUID id = legacy(status);
            assertThat(receipts.cancel(id).status()).as(status).isEqualTo(GoodsReceiptStatus.CANCELLED);
            assertThat(receipts.get(id).status()).isEqualTo(GoodsReceiptStatus.CANCELLED);
        }
    }
}
