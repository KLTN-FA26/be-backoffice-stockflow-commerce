package com.stockflow.inventory;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.api.CountReceivingPackagesCommand;
import com.stockflow.inventory.api.CountReceivingPackagesCommand.PackageCount;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.MoveReference;
import com.stockflow.inventory.api.MoveStockPackagesCommand;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.StockPackageView;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.service.StockPackageLookup;
import com.stockflow.inventory.internal.service.StockPackageLookup.Carton;
import com.stockflow.inventory.internal.service.StockPackageLookup.CartonQuery;
import com.stockflow.inventory.internal.service.StockPackageLookup.LocationLine;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * SCRUM-473 carton lookup against the real view and tables. Each test counts a line of 2000 as one carton of 1000
 * and one of 980 at the receiving area; most then receive it, which leaves 20 loose beside the two cartons.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class StockPackageLookupIntegrationTest {

    private static final String RECEIVING = "HCM-RCV01";
    private static final String BIN = "HCM-A01-1-A";
    private static final Instant RECEIVED = Instant.parse("2026-01-02T00:00:00Z");

    @Autowired StockPackageLookup lookup;
    @Autowired InventoryService inventory;
    @Autowired GoodsReceipts receipts;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private String sku;
    private UUID carton;
    private UUID clerk;
    private UUID line;
    private List<String> lpns;

    @BeforeEach
    void setUp() {
        sku = "SPL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        UUID poLine = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Carton lookup fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Carton lookup fixture");
            UUID item = jdbc.queryForObject("select id from inventory.inventory_items where sku = ?", UUID.class, sku);
            // BR-03: tg_goods_receipt_lines_item refuses a receipt line for an item with no weight or dimensions.
            jdbc.update("update inventory.inventory_items set weight_kg = 0.5, length_cm = 10, width_cm = 8, height_cm = 6"
                    + " where id = ?", item);
            carton = Identifiers.newId();
            jdbc.update("insert into inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor)"
                    + " values (?, ?, 'CARTON', 1000)", carton, item);

            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);   // four eyes (ck_purchase_orders_four_eyes)
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    SELECT ?, ?, 'CONFIRMED', s.id, w.id, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW()
                      FROM procurement.suppliers s, warehouse.warehouse w WHERE s.code = 'GOVIET' AND w.prefix = 'HCM'""",
                    po, "PO-SPL-" + po.toString().substring(24), submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, version, created_at) VALUES (?, ?, ?, 1, ?, 2000, 1000, 0, NOW())""",
                    poLine, po, revision, item);
        });
        GoodsReceipts.ReceiptView draft = receipts.create(new GoodsReceipts.Create(po, "DN-SPL", null), clerk);
        line = receipts.replaceLines(draft.id(), List.of(new GoodsReceipts.NewLine(poLine, 2000, null, null,
                RECEIVING, null))).lines().getFirst().id();
        lpns = count(new PackageCount(null, 1000), new PackageCount(null, 980)).stream()
                .map(StockPackageView::lpn).toList();
    }

    private List<StockPackageView> count(PackageCount... packages) {
        return inventory.countReceivingPackages(new CountReceivingPackagesCommand(line, new Sku(sku),
                packages.length == 0 ? null : carton, RECEIVING, List.of(packages), clerk));
    }

    /** Both cartons IN_STOCK, unopened, at the receiving area; 20 loose beside them. */
    private void receive() {
        inventory.receive(new ReceiveStockCommand(line, new Sku(sku), null, null, RECEIVING, 2000, clerk, RECEIVED));
    }

    private PageResponse<Carton> list(String location, String sku, List<StockPackageStatus> statuses) {
        return lookup.cartons(new CartonQuery(location, sku, statuses, 0, 20, null));
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("a carton by its LPN in lower case with spaces: what is in it, where, and the line it came on")
    void detailByLowerCaseLpn() {
        receive();

        StockPackageLookup.Detail detail = lookup.byLpn(" " + lpns.getFirst().toLowerCase(Locale.ROOT) + " ");

        assertThat(detail.lpn()).isEqualTo(lpns.getFirst());
        assertThat(detail.sku()).isEqualTo(sku);
        assertThat(detail.unitOfMeasure()).isEqualTo("CARTON");
        assertThat(detail.nominalQty()).isEqualTo(1000);
        assertThat(detail.receivedQty()).isEqualTo(1000);
        assertThat(detail.onHand()).isEqualTo(1000);
        assertThat(detail.reserved()).isZero();
        assertThat(detail.status()).isEqualTo("IN_STOCK");
        assertThat(detail.unopened()).isTrue();
        assertThat(detail.locationCode()).isEqualTo(RECEIVING);
        assertThat(detail.goodsReceiptLineId()).isEqualTo(line);
        assertThat(detail.closedAt()).isNull();
        assertThat(detail.contents()).singleElement().satisfies(content -> {
            assertThat(content.onHand()).isEqualTo(1000);
            assertThat(content.status()).isEqualTo("INBOUND");
            assertThat(content.receivedAt()).isEqualTo(RECEIVED);
        });
    }

    @Test
    @DisplayName("a carton still being received has no stock row yet: on hand 0, no contents")
    void aReceivingCartonHoldsNothingYet() {
        StockPackageLookup.Detail detail = lookup.byLpn(lpns.get(1));

        assertThat(detail.status()).isEqualTo("RECEIVING");
        assertThat(detail.receivedQty()).isEqualTo(980);
        assertThat(detail.onHand()).isZero();
        assertThat(detail.lotNumber()).isNull();
        assertThat(detail.contents()).isEmpty();
    }

    @Test
    @DisplayName("an unknown or blank LPN: STOCK_PACKAGE_NOT_FOUND")
    void anUnknownLpnIsNotFound() {
        assertThatThrownBy(() -> lookup.byLpn("LPN-19990101-999999"))
                .extracting(StockPackageLookupIntegrationTest::code).isEqualTo(ErrorCode.STOCK_PACKAGE_NOT_FOUND);
        assertThatThrownBy(() -> lookup.byLpn(" "))
                .extracting(StockPackageLookupIntegrationTest::code).isEqualTo(ErrorCode.STOCK_PACKAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("the receiving area after receipt: two whole cartons of 1980 units, 20 loose, 2000 in all")
    void summaryAtTheReceivingArea() {
        receive();

        assertThat(lookup.atLocation(RECEIVING.toLowerCase(Locale.ROOT))).filteredOn(l -> l.sku().equals(sku))
                .containsExactly(new LocationLine(sku, null, null, "INBOUND", 2, 1980, 0, 0, 20, 2000));
    }

    @Test
    @DisplayName("an opened carton is counted apart from the whole ones, with what is left in it")
    void anOpenedCartonIsCountedApart() {
        receive();
        // 100 taken out of the 980 carton: its row falls and the carton is opened, as one commit (the contents check
        // of V20261020000200 runs at commit and wants both). In tx: the pool runs with auto-commit off.
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    update inventory.stock_item set on_hand = on_hand - 100
                     where stock_package_id = (select id from inventory.stock_package where lpn = ?)""", lpns.get(1));
            jdbc.update("update inventory.stock_package set unopened = false where lpn = ?", lpns.get(1));
        });

        assertThat(lookup.atLocation(RECEIVING)).filteredOn(l -> l.sku().equals(sku))
                .containsExactly(new LocationLine(sku, null, null, "INBOUND", 1, 1000, 1, 880, 20, 1900));
        assertThat(lookup.byLpn(lpns.get(1))).satisfies(detail -> {
            assertThat(detail.onHand()).isEqualTo(880);
            assertThat(detail.unopened()).isFalse();
        });
    }

    @Test
    @DisplayName("an unknown location is LOCATION_NOT_FOUND, a blank one VALIDATION_FAILED")
    void anUnknownOrBlankLocation() {
        assertThatThrownBy(() -> lookup.atLocation("HCM-NOPE99"))
                .extracting(StockPackageLookupIntegrationTest::code).isEqualTo(ErrorCode.LOCATION_NOT_FOUND);
        assertThatThrownBy(() -> lookup.atLocation(" "))
                .extracting(StockPackageLookupIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("the list filters by SKU and by location; an unknown SKU is an empty page")
    void listFiltersBySkuAndLocation() {
        receive();
        inventory.moveStockPackages(new MoveStockPackagesCommand(lpns.getFirst(), BIN, MoveReference.MOVE_TASK,
                Identifiers.newId(), clerk));

        assertThat(list(null, sku.toLowerCase(Locale.ROOT), null).items())
                .extracting(Carton::lpn, Carton::locationCode, Carton::onHand)
                .containsExactly(tuple(lpns.get(0), BIN, 1000),
                        tuple(lpns.get(1), RECEIVING, 980));
        assertThat(list(BIN, sku, null).items()).extracting(Carton::lpn).containsExactly(lpns.getFirst());
        assertThat(list(null, "NO-SUCH-SKU", null).totalElements()).isZero();
    }

    @Test
    @DisplayName("a VOID carton is left out of the list unless its status is asked for")
    void listHidesClosedCartonsByDefault() {
        count(new PackageCount(lpns.getFirst(), 1000));   // the 980 carton, left out of the count, is voided

        assertThat(list(null, sku, null).items()).extracting(Carton::lpn, Carton::status)
                .containsExactly(tuple(lpns.getFirst(), "RECEIVING"));
        assertThat(list(null, sku, List.of(StockPackageStatus.VOID)).items()).extracting(Carton::lpn)
                .containsExactly(lpns.get(1));
    }
}
