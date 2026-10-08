package com.stockflow.inventory;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.internal.domain.TransferReason;
import com.stockflow.inventory.internal.domain.TransferStatus;
import com.stockflow.inventory.internal.service.TransferOrders;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-326/327 against a real database: the availability check reads the source warehouse only,
 * dispatch lowers the source stock FEFO and writes TRANSFER_OUT ledger lines, and the table's
 * four-eyes and status constraints hold. Each test uses its own lot of a demo SKU in HCM.
 */
@ApplicationModuleTest
@ActiveProfiles("test")
@Import(PostgresContainer.class)
class TransferOrderIntegrationTest {

    @Autowired TransferOrders transfers;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private UUID hcm;
    private UUID other;
    private String lot;
    private UUID planner;

    @BeforeEach
    void setUp() {
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        String prefix = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase().replace("-", "");
        other = Identifiers.newId();
        lot = "IT-" + UUID.randomUUID().toString().substring(0, 8);
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO warehouse.warehouse (id, code, name, status, prefix, version, created_at) "
                    + "VALUES (?, ?, 'Test warehouse', 'ACTIVE', ?, 0, NOW())", other, prefix, prefix);
            // Two rows of the lot in HCM, the earlier expiry first in FEFO.
            jdbc.update("INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, expiry_date, on_hand, reserved, status, version, created_at) "
                    + "VALUES (?, 'SOFA-3S-GREY', 'HCM-A01-1-B', ?, DATE '2027-01-01', 6, 0, 'AVAILABLE', 0, NOW())", Identifiers.newId(), lot);
            jdbc.update("INSERT INTO inventory.stock_item (id, sku, location_code, lot_number, expiry_date, on_hand, reserved, status, version, created_at) "
                    + "VALUES (?, 'SOFA-3S-GREY', 'HCM-A02-2-A', ?, DATE '2027-06-01', 10, 0, 'AVAILABLE', 0, NOW())", Identifiers.newId(), lot);
        });
        planner = tx.execute(s -> ReferenceRows.user(entityManager));
    }

    private TransferOrders.Transfer create(int quantity) {
        return transfers.create(new TransferOrders.Create(hcm, other, TransferReason.REBALANCING, null,
                List.of(new TransferOrders.NewLine("SOFA-3S-GREY", lot, quantity))));
    }

    private int onHand(String location) {
        return jdbc.queryForObject("SELECT on_hand FROM inventory.stock_item WHERE lot_number = ? AND location_code = ?",
                Integer.class, lot, location);
    }

    @Test
    @DisplayName("create checks the source, submit under the threshold approves, dispatch takes FEFO and writes the ledger")
    void happyPath() {
        TransferOrders.Transfer created = create(9);
        assertThat(created.status()).isEqualTo(TransferStatus.DRAFT);
        assertThat(created.number()).matches("TO-\\d{8}-\\d{4}");

        TransferOrders.Transfer approved = transfers.submit(created.transferId(), planner);
        assertThat(approved.status()).isEqualTo(TransferStatus.APPROVED);
        assertThat(approved.approvedBy()).isEqualTo(planner);

        transfers.startPicking(created.transferId());
        UUID lineId = created.lines().get(0).lineId();
        TransferOrders.Transfer dispatched = transfers.dispatch(created.transferId(), planner, Map.of(lineId, 9));

        assertThat(dispatched.status()).isEqualTo(TransferStatus.IN_TRANSIT);
        assertThat(dispatched.lines().get(0).shipped()).isEqualTo(9);
        // FEFO: the 2027-01 row is emptied first (6), the rest (3) comes from the 2027-06 row.
        assertThat(onHand("HCM-A01-1-B")).isZero();
        assertThat(onHand("HCM-A02-2-A")).isEqualTo(7);
        assertThat(jdbc.queryForObject("""
                SELECT string_agg(from_location_code || ':' || quantity, ',' ORDER BY quantity DESC)
                FROM inventory.stock_movement WHERE movement_type = 'TRANSFER_OUT' AND reference_id = ?""",
                String.class, lineId)).isEqualTo("HCM-A01-1-B:6,HCM-A02-2-A:3");

        assertThatThrownBy(() -> transfers.cancel(created.transferId(), "too late"))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INVALID_TRANSFER_TRANSITION);
    }

    @Test
    @DisplayName("more than the source holds is refused at creation; an unknown warehouse is a 404")
    void refusals() {
        assertThatThrownBy(() -> create(17))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThatThrownBy(() -> transfers.create(new TransferOrders.Create(hcm, UUID.randomUUID(), null, null,
                List.of(new TransferOrders.NewLine("SOFA-3S-GREY", lot, 1)))))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
        // Stock in the destination does not count for a transfer out of it.
        assertThatThrownBy(() -> transfers.create(new TransferOrders.Create(other, hcm, null, null,
                List.of(new TransferOrders.NewLine("SOFA-3S-GREY", lot, 1)))))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
    }

    @Test
    @DisplayName("stock gone between creation and dispatch fails the dispatch cleanly; pick, cancel, list")
    void stockGoneBeforeDispatch() {
        TransferOrders.Transfer created = create(16);
        transfers.submit(created.transferId(), planner);
        transfers.startPicking(created.transferId());
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE inventory.stock_item SET on_hand = 5 WHERE lot_number = ? AND location_code = 'HCM-A02-2-A'", lot));
        assertThatThrownBy(() -> transfers.dispatch(created.transferId(), planner,
                Map.of(created.lines().get(0).lineId(), 16)))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(transfers.get(created.transferId()).status()).isEqualTo(TransferStatus.PICKING);
        assertThat(onHand("HCM-A01-1-B")).isEqualTo(6);

        TransferOrders.Transfer stopped = transfers.cancelPicking(created.transferId());
        assertThat(stopped.status()).isEqualTo(TransferStatus.APPROVED);
        assertThat(transfers.cancel(created.transferId(), "Not enough left").status()).isEqualTo(TransferStatus.CANCELLED);

        var page = transfers.list(List.of(TransferStatus.CANCELLED), hcm, null, created.number(), 0, 20);
        assertThat(page.items()).extracting(TransferOrders.Row::transferId).containsExactly(created.transferId());
    }
}
