package com.stockflow.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * SCRUM-326/327 against a real database: the availability check reads the source warehouse only,
 * dispatch lowers the source stock FEFO and writes TRANSFER_OUT ledger lines, and the table's
 * four-eyes and status constraints hold. Each test uses its own canonical SKU and lot in HCM.
 */
// DIRECT_DEPENDENCIES: moves ask warehouse :: api whether a location may take stock (issue #67).
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.DIRECT_DEPENDENCIES)
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
    private String sku;
    private UUID planner;

    @BeforeEach
    void setUp() {
        hcm =
                jdbc.queryForObject(
                        "SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        String prefix =
                "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase().replace("-", "");
        other = Identifiers.newId();
        sku = "TRANSFER-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        lot = "IT-" + UUID.randomUUID().toString().substring(0, 8);
        tx.executeWithoutResult(
                s -> {
                    jdbc.update(
                            "INSERT INTO warehouse.warehouse (id, code, name, status, prefix,"
                                + " version, created_at) VALUES (?, ?, 'Test warehouse', 'ACTIVE',"
                                + " ?, 0, NOW())",
                            other,
                            prefix,
                            prefix);
                    UUID product = Identifiers.newId();
                    jdbc.update(
                            "insert into product.products(id,code,name,slug) values (?,?,?,?)",
                            product,
                            sku,
                            "Transfer fixture",
                            sku.toLowerCase(Locale.ROOT));
                    jdbc.update(
                            "insert into"
                                + " product.variants(id,product_id,sku,name,attribute_signature)"
                                + " values (?,?,?,'Transfer fixture','')",
                            Identifiers.newId(),
                            product,
                            sku);
                    jdbc.update(
                            "update inventory.inventory_items set"
                                    + " lot_tracked=true,expiry_tracked=true,policy_configured=true"
                                    + " where sku=?",
                            sku);
                    // Two rows of the lot in HCM, the earlier expiry first in FEFO.
                    jdbc.update(
                            "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number,"
                                + " expiry_date, on_hand, reserved, status, version, created_at)"
                                + " VALUES (?, ?, 'HCM-A01-1-B', ?, CURRENT_DATE + 30, 6, 0,"
                                + " 'AVAILABLE', 0, NOW())",
                            Identifiers.newId(),
                            sku,
                            lot);
                    jdbc.update(
                            "INSERT INTO inventory.stock_item (id, sku, location_code, lot_number,"
                                + " expiry_date, on_hand, reserved, status, version, created_at)"
                                + " VALUES (?, ?, 'HCM-A02-2-A', ?, CURRENT_DATE + 60, 10, 0,"
                                + " 'AVAILABLE', 0, NOW())",
                            Identifiers.newId(),
                            sku,
                            lot);
                });
        planner = tx.execute(s -> ReferenceRows.user(entityManager));
    }

    private TransferOrders.Transfer create(int quantity) {
        return transfers.create(
                new TransferOrders.Create(
                        hcm,
                        other,
                        TransferReason.REBALANCING,
                        null,
                        List.of(new TransferOrders.NewLine(sku, lot, quantity))));
    }

    private int onHand(String location) {
        return jdbc.queryForObject(
                "SELECT on_hand FROM inventory.stock_item WHERE lot_number = ? AND location_code ="
                        + " ?",
                Integer.class,
                lot,
                location);
    }

    @Test
    void dispatchUsesConfiguredFifoInsteadOfSmallestRemainder() {
        tx.executeWithoutResult(
                s -> {
                    jdbc.update("delete from inventory.stock_item where sku=?", sku);
                    jdbc.update(
                            "update inventory.inventory_items set"
                                    + " expiry_tracked=false,removal_strategy='FIFO' where sku=?",
                            sku);
                    jdbc.update(
                            """
insert into inventory.stock_item(id,sku,location_code,lot_number,received_at,on_hand,reserved,status,version,created_at)
values (?,?,'HCM-A01-1-B',?,TIMESTAMPTZ '2026-01-02 00:00:00Z',6,0,'AVAILABLE',0,now()),
       (?,?,'HCM-A02-2-A',?,TIMESTAMPTZ '2026-01-01 00:00:00Z',10,0,'AVAILABLE',0,now())
""",
                            Identifiers.newId(),
                            sku,
                            lot,
                            Identifiers.newId(),
                            sku,
                            lot);
                });
        TransferOrders.Transfer created = create(9);
        transfers.submit(created.transferId(), planner);
        transfers.startPicking(created.transferId());
        transfers.dispatch(
                created.transferId(), planner, Map.of(created.lines().getFirst().lineId(), 9));

        assertThat(onHand("HCM-A01-1-B")).isEqualTo(6);
        assertThat(onHand("HCM-A02-2-A")).isEqualTo(1);
    }

    @Test
    void expiredStockCannotCoverTransferCreation() {
        tx.executeWithoutResult(
                s -> {
                    jdbc.update("delete from inventory.stock_item where sku=?", sku);
                    // Historical stock can age after receipt or predate policy configuration.
                    // Do not exercise the live ingress guard with an invalid new receipt.
                    jdbc.update(
                            "update inventory.inventory_items set policy_configured=false where"
                                + " sku=?",
                            sku);
                    jdbc.update(
                            """
insert into inventory.stock_item(id,sku,location_code,lot_number,expiry_date,on_hand,reserved,status,version,created_at)
values (?,?,'HCM-A01-1-B',?,DATE '2000-01-01',6,0,'AVAILABLE',0,now())
""",
                            Identifiers.newId(),
                            sku,
                            lot);
                    jdbc.update(
                            "update inventory.inventory_items set policy_configured=true where"
                                + " sku=?",
                            sku);
                });
        assertThatThrownBy(() -> create(1))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(onHand("HCM-A01-1-B")).isEqualTo(6);
    }

    @Test
    @DisplayName(
            "create checks the source, submit under the threshold approves, dispatch takes FEFO and"
                    + " writes the ledger")
    void happyPath() {
        TransferOrders.Transfer created = create(9);
        assertThat(created.status()).isEqualTo(TransferStatus.DRAFT);
        assertThat(created.number()).matches("TO-\\d{8}-\\d{4}");

        TransferOrders.Transfer approved = transfers.submit(created.transferId(), planner);
        assertThat(approved.status()).isEqualTo(TransferStatus.APPROVED);
        assertThat(approved.approvedBy()).isEqualTo(planner);

        transfers.startPicking(created.transferId());
        UUID lineId = created.lines().get(0).lineId();
        TransferOrders.Transfer dispatched =
                transfers.dispatch(created.transferId(), planner, Map.of(lineId, 9));

        assertThat(dispatched.status()).isEqualTo(TransferStatus.IN_TRANSIT);
        assertThat(dispatched.lines().get(0).shipped()).isEqualTo(9);
        // FEFO: the earlier-expiring row is emptied first (6), then 3 from the later row.
        assertThat(onHand("HCM-A01-1-B")).isZero();
        assertThat(onHand("HCM-A02-2-A")).isEqualTo(7);
        assertThat(
                        jdbc.queryForObject(
                                """
SELECT string_agg(from_location_code || ':' || quantity, ',' ORDER BY quantity DESC)
FROM inventory.stock_movement WHERE movement_type = 'TRANSFER_OUT' AND reference_id = ?""",
                                String.class,
                                lineId))
                .isEqualTo("HCM-A01-1-B:6,HCM-A02-2-A:3");

        assertThatThrownBy(() -> transfers.cancel(created.transferId(), "too late"))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_TRANSFER_TRANSITION);
    }

    @Test
    @DisplayName("more than the source holds is refused at creation; an unknown warehouse is a 404")
    void refusals() {
        assertThatThrownBy(() -> create(17))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThatThrownBy(
                        () ->
                                transfers.create(
                                        new TransferOrders.Create(
                                                hcm,
                                                UUID.randomUUID(),
                                                null,
                                                null,
                                                List.of(new TransferOrders.NewLine(sku, lot, 1)))))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
        // Stock in the destination does not count for a transfer out of it.
        assertThatThrownBy(
                        () ->
                                transfers.create(
                                        new TransferOrders.Create(
                                                other,
                                                hcm,
                                                null,
                                                null,
                                                List.of(new TransferOrders.NewLine(sku, lot, 1)))))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
    }

    @Test
    @DisplayName(
            "stock gone between creation and dispatch fails the dispatch cleanly; pick, cancel,"
                    + " list")
    void stockGoneBeforeDispatch() {
        TransferOrders.Transfer created = create(16);
        transfers.submit(created.transferId(), planner);
        transfers.startPicking(created.transferId());
        tx.executeWithoutResult(
                s ->
                        jdbc.update(
                                "UPDATE inventory.stock_item SET on_hand = 5 WHERE lot_number = ?"
                                        + " AND location_code = 'HCM-A02-2-A'",
                                lot));
        assertThatThrownBy(
                        () ->
                                transfers.dispatch(
                                        created.transferId(),
                                        planner,
                                        Map.of(created.lines().get(0).lineId(), 16)))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(transfers.get(created.transferId()).status()).isEqualTo(TransferStatus.PICKING);
        assertThat(onHand("HCM-A01-1-B")).isEqualTo(6);

        TransferOrders.Transfer stopped = transfers.cancelPicking(created.transferId());
        assertThat(stopped.status()).isEqualTo(TransferStatus.APPROVED);
        assertThat(transfers.cancel(created.transferId(), "Not enough left").status())
                .isEqualTo(TransferStatus.CANCELLED);

        var page =
                transfers.list(
                        List.of(TransferStatus.CANCELLED), hcm, null, created.number(), 0, 20);
        assertThat(page.items())
                .extracting(TransferOrders.Row::transferId)
                .containsExactly(created.transferId());
    }
}
