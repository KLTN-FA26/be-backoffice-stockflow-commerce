package com.stockflow.order;

import com.stockflow.common.document.DocumentSamples;
import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.inventory.api.CartonReturns;
import com.stockflow.inventory.internal.domain.ReturnedCartonOwnership;
import com.stockflow.inventory.internal.service.CartonQuarantineService;
import com.stockflow.inventory.internal.service.CustomerGoodsCustody;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ReturnCustodyChoice;
import com.stockflow.order.internal.domain.ReturnEvidence;
import com.stockflow.order.internal.domain.ReturnReason;
import com.stockflow.order.internal.domain.ReturnRefusalReason;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnResolution;
import com.stockflow.order.internal.domain.ReturnStatus;
import com.stockflow.order.internal.service.ReturnRequestService;
import com.stockflow.procurement.internal.service.ReturnReceiptPaperService;
import com.stockflow.procurement.internal.service.ReturnReceiptService;
import com.stockflow.procurement.internal.service.ReturnReceiptService.ReturnReceiptView;
import com.stockflow.support.Await;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.ShippedCartons;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Audit round 2, returns and returned stock, each scenario as the audit reproduced it and each rule stated twice - by
 * the application and by the schema (CLAUDE.md §7):
 * <ul>
 *   <li>H1 (Q-4): goods of a return that refunded nothing never stay company stock - the stock-in reversed, held for the
 *       customer, never released, the 01-VT "thực nhập" 0 - and leave as the customer chose;</li>
 *   <li>M1: a late parcel's goods are the return's in the receipt's own transaction, so no refund is approved between;</li>
 *   <li>M2: a withdrawn return whose units another return claimed takes no parcel - refused at the dock, naming that
 *       return - and its draft holds the units against a new claim;</li>
 *   <li>M3, L6: a carton never takes a SKU above its approval, on the scan or a recount;</li>
 *   <li>lifecycle L-3: no parcel for a return of a CLOSED order;</li>
 *   <li>L4 / M-3: no withdrawal while a parcel is counted, in the schema too.</li>
 * </ul>
 * The orders have no installments (WF-05 TH-2: nothing paid in the system), so no refund moves money; a piece is worth
 * 2 200 dong and a carton holds 50.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class ReturnsRound2IntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final AtomicLong ORDER_NUMBERS = new AtomicLong(780_000 + ThreadLocalRandom.current().nextInt(15_000));
    private static final Duration ASYNC = Duration.ofSeconds(20);
    private static final String QUARANTINE = "HCM-QC01";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D',
            'R', 0, 0, 0, 1, 0, 0, 0, 1};
    private static final int CARTON = 50;

    @Autowired OrderRepository orderRepository;
    @Autowired ReturnRequestService returns;
    @Autowired ReturnReceiptService returnReceipts;
    @Autowired ReturnReceiptPaperService papers;
    @Autowired CartonQuarantineService quarantine;
    @Autowired CustomerGoodsCustody custody;
    @Autowired CartonReturns cartonReturns;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean JavaMailSenderImpl mail;

    private CurrentUser sales;
    private UUID coordinator;
    private UUID accountant;
    private UUID clerk;
    private UUID manager;
    private UUID customerId;
    private CustomerSkuRows.Issued sku;
    private LocalDate today;

    private record Delivered(UUID orderId, UUID lineId, ShippedCartons.Shipped shipped) {

        String lpn(int i) {
            return shipped.lpns().get(i);
        }
    }

    @BeforeEach
    void setUp() {
        UUID salesUser = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                VALUES (?, 'Cong ty Tra Hang R2', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                "r2-" + customerId + "@example.com", "07" + (10_000_000 + ThreadLocalRandom.current()
                        .nextInt(89_999_999))));
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, CARTON, "STANDARD", true);
        today = LocalDate.now(VIETNAM);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

    private <T> T asSales(Supplier<T> call) {
        return WithCurrentUser.call(sales, DataScope.ALL, call);
    }

    private <T> T asHcm(UUID user, Supplier<T> call) {
        CurrentUser staff = new CurrentUser(user, "hcm-" + user, Set.of(Role.WAREHOUSE_STAFF), Set.of(),
                DataScope.WAREHOUSE, Set.of("HCM"), Set.of(DemoData.WAREHOUSE_HCM));
        return WithCurrentUser.call(staff, DataScope.WAREHOUSE, call);
    }

    private Delivered delivered(int cartons) {
        UUID orderId = tx.execute(s -> {
            OrderLine line = Order.line(Identifiers.newId(), new Sku(sku.sku()), cartons * CARTON, Money.vnd(2_000), null)
                    .withCartons(sku.uomConversionId(), cartons, CARTON).withTax(BigDecimal.TEN);
            return orderRepository.save(Order.draft(OrderNumber.of(today, ORDER_NUMBERS.incrementAndGet()),
                    customerId, Identifiers.newId(), List.of(line), Instant.now())).id().value();
        });
        UUID lineId = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    UPDATE ordering.customer_order SET status = 'DELIVERED', warehouse_id = ?,
                           shipped_at = NOW() - interval '2 days', delivered_at = NOW() - interval '1 day' WHERE id = ?""",
                    DemoData.WAREHOUSE_HCM, orderId);
        });
        ShippedCartons.Shipped shipped = ShippedCartons.ship(jdbc, tx, orderId, lineId, sku.sku(), cartons, CARTON,
                "LOT-R2-" + orderId.toString().substring(0, 8), "DELIVERED", clerk);
        return new Delivered(orderId, lineId, shipped);
    }

    private ReturnRequest open(Delivered d, int units) {
        return asSales(() -> returns.openForCustomer(new ReturnRequestService.OpenReturnCommand(UUID.randomUUID(),
                d.orderId(), ReturnReason.DAMAGED_IN_TRANSIT, "móp thùng", ReturnResolution.REFUND,
                List.of(new ReturnRequest.Claim(d.lineId(), units)), null, null), sales.userId()));
    }

    private static FileUpload png() {
        return new FileUpload("anh.png", "image/png", PNG.length, new ByteArrayInputStream(PNG));
    }

    /** Opened for {@code asked} units and approved for {@code approved} of them. */
    private ReturnRequest approved(Delivered d, int asked, int approved) {
        ReturnRequest request = open(d, asked);
        returns.addEvidence(request.id(), null, ReturnEvidence.Stage.CLAIM, png(), sales.userId());
        return returns.approve(request.id(), new ReturnRequestService.ApproveReturnCommand(
                Map.of(request.lines().getFirst().id(), approved), null, "ok", null), coordinator);
    }

    private ReturnRequest approved(Delivered d, int units) {
        return approved(d, units, units);
    }

    private ReturnReceiptView draft(ReturnRequest request) {
        return asHcm(clerk, () -> returnReceipts.create(new ReturnReceiptService.Create(null, request.id(), QUARANTINE,
                null), clerk));
    }

    private ReturnReceiptView scan(UUID receipt, String lpn, Integer counted) {
        return asHcm(clerk, () -> returnReceipts.scan(receipt, lpn, null, counted, clerk));
    }

    private ReturnReceiptView confirm(UUID receipt) {
        return asHcm(clerk, () -> returnReceipts.confirm(receipt, clerk, false));
    }

    /** Back on a receipt of its own, every carton named sealed, confirmed. */
    private ReturnReceiptView back(ReturnRequest request, String... lpns) {
        ReturnReceiptView receipt = draft(request);
        for (String lpn : lpns) {
            scan(receipt.id(), lpn, null);
        }
        return confirm(receipt.id());
    }

    private ReturnStatus statusOf(ReturnRequest request) {
        return ReturnStatus.valueOf(jdbc.queryForObject("SELECT status FROM ordering.return_request WHERE id = ?",
                String.class, request.id()));
    }

    private Map<String, Object> carton(String lpn) {
        return jdbc.queryForMap("""
                SELECT p.status, p.return_ownership, p.return_request_id, p.custody_choice, s.status AS stock,
                       s.on_hand FROM inventory.stock_package p JOIN inventory.stock_item s ON s.stock_package_id = p.id
                 WHERE p.lpn = ?""", lpn);
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private Throwable inDatabase(String sql, Object... args) {
        return catchThrowable(() -> tx.executeWithoutResult(s -> jdbc.update(sql, args)));
    }

    private ReturnRequest refusedAll(ReturnRequest request) {
        UUID line = request.lines().getFirst().id();
        returns.proposeRefund(request.id(), new ReturnRequestService.ProposeRefundCommand(Map.of(line, 0),
                "kho khách ẩm", null, ReturnRefusalReason.CUSTOMER_DAMAGE, "Thùng ẩm mốc do lưu kho"), sales.userId());
        return returns.approveSettlement(request.id(), null, accountant);
    }

    // ---- H1 ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("H1 (Q-4): goods back on a return wait for its refund - not released meanwhile; refused, their stock-in "
            + "is reversed, they are the customer's, held for them, never released, the 01-VT receives 0; the "
            + "warehouse manager hands them back or destroys them only as the customer chose")
    void refusedGoodsAreTheCustomers() throws Exception {
        Delivered d = delivered(2);
        ReturnRequest request = approved(d, 100);
        ReturnReceiptView receipt = back(request, d.lpn(0), d.lpn(1));
        assertThat(carton(d.lpn(0))).containsEntry("status", "IN_STOCK").containsEntry("return_ownership", "PENDING")
                .containsEntry("return_request_id", request.id()).containsEntry("stock", "QUARANTINE");
        // Before the refund is decided, the carton is not the company's to sell.
        assertThat(code(catchThrowable(() -> asHcm(manager, () -> quarantine.release(d.lpn(0), manager)))))
                .isEqualTo(ErrorCode.STOCK_PACKAGE_DECISION_PENDING);
        assertThat(inDatabase("""
                UPDATE inventory.stock_item SET status = 'AVAILABLE' WHERE stock_package_id =
                    (SELECT id FROM inventory.stock_package WHERE lpn = ?)""", d.lpn(0)))
                .as("tg_stock_item_returned_carton").hasMessageContaining("stay in quarantine");

        ReturnRequest resolved = refusedAll(request);
        assertThat(resolved.outcome()).isEqualTo(ReturnRequest.Outcome.REFUSED);
        for (int i = 0; i < 2; i++) {
            assertThat(carton(d.lpn(i))).as(d.lpn(i)).containsEntry("status", "CONSUMED")
                    .containsEntry("return_ownership", "CUSTOMER_HELD").containsEntry("on_hand", 0);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_movement WHERE reference_type = 'RETURN_REQUEST'
                   AND reference_id = ? AND movement_type = 'ADJUSTMENT' AND quantity = ?
                   AND from_location_code = ? AND to_location_code IS NULL""", Long.class, request.id(), CARTON,
                QUARANTINE)).as("one reversing line per carton").isEqualTo(2L);
        assertThat(code(catchThrowable(() -> asHcm(manager, () -> quarantine.release(d.lpn(0), manager)))))
                .as("out of the books: nothing to release").isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
        assertThat(inDatabase("UPDATE inventory.stock_package SET return_ownership = 'COMPANY' WHERE lpn = ?",
                d.lpn(0))).as("ck_stock_package_custody_held").isNotNull();
        assertThat(inDatabase("UPDATE inventory.stock_package SET status = 'IN_STOCK', closed_at = NULL WHERE lpn = ?",
                d.lpn(1))).as("tg_stock_package_return_ownership: the customer's is out of the books").isNotNull();

        // The 01-VT: nothing received - the goods are the customer's, held for them.
        ReturnReceiptView seen = asHcm(clerk, () -> returnReceipts.get(receipt.id()));
        assertThat(seen.cartons()).allSatisfy(c -> assertThat(c.heldForCustomer()).isTrue());
        String voucher = DocumentSamples.text(asHcm(clerk, () -> papers.voucher(receipt.id())).content());
        assertThat(voucher).contains("giữ hộ, không nhập kho");

        // Never taken back on a receipt: it never left (the scan and inventory's receive refuse it).
        assertThat(cartonReturns.find(d.lpn(0)).orElseThrow().customersGoods()).isTrue();

        // The customer's choice, by Sales; then the warehouse manager does exactly that.
        Map<String, Object> held = custodyRow(d.lpn(0));
        assertThat(held).containsEntry("custody_choice", null);
        assertThat(code(catchThrowable(() -> asHcm(manager, () -> custody.end(d.lpn(0), "BB 01", manager)))))
                .as("no choice yet").isEqualTo(ErrorCode.STOCK_PACKAGE_DECISION_PENDING);
        assertThat(code(catchThrowable(() -> returns.recordCustodyChoice(request.id(),
                ReturnCustodyChoice.DESTROY_ON_BEHALF, null, null, sales.userId()))))
                .as("a destruction rests on a written consent").isEqualTo(ErrorCode.VALIDATION_FAILED);
        ReturnRequest chose = returns.recordCustodyChoice(request.id(), ReturnCustodyChoice.DESTROY_ON_BEHALF,
                "Biên bản đồng ý huỷ hộ số 3", null, sales.userId());
        assertThat(chose.custodyChoice()).isEqualTo(ReturnCustodyChoice.DESTROY_ON_BEHALF);
        var list = asHcm(manager, () -> custody.held(request.id(), false, 0, 20));
        assertThat(list.items()).hasSize(2).allSatisfy(c -> assertThat(c.choice()).isEqualTo("DESTROY_ON_BEHALF"));
        ReturnedCartonOwnership.Custody ended = asHcm(manager, () -> custody.end(d.lpn(0), "Biên bản huỷ số 9",
                manager));
        assertThat(ended.ownership()).isEqualTo(ReturnedCartonOwnership.Ownership.CUSTOMER_DESTROYED);
        assertThat(ended.endedBy()).isEqualTo(manager);
        assertThat(code(catchThrowable(() -> returns.recordCustodyChoice(request.id(), ReturnCustodyChoice.SEND_BACK,
                null, null, sales.userId())))).as("carried out already")
                .isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
        assertThat(inDatabase("UPDATE inventory.stock_package SET return_ownership = 'CUSTOMER_SENT_BACK' WHERE lpn = ?",
                d.lpn(1))).as("ck_stock_package_custody_ended: only as chosen, with its record").isNotNull();
    }

    private Map<String, Object> custodyRow(String lpn) {
        return jdbc.queryForMap("SELECT custody_choice, custody_held_at FROM inventory.stock_package WHERE lpn = ?", lpn);
    }

    @Test
    @DisplayName("H1: goods of a return that refunded units are the company's - released as any quarantined carton; "
            + "the custody choice is refused for them, by the application and the schema")
    void refundedGoodsAreTheCompanys() {
        Delivered d = delivered(1);
        ReturnRequest request = approved(d, 50);
        back(request, d.lpn(0));
        returns.proposeRefund(request.id(), new ReturnRequestService.ProposeRefundCommand(null, null, null),
                sales.userId());
        assertThat(returns.approveSettlement(request.id(), null, accountant).outcome())
                .isEqualTo(ReturnRequest.Outcome.REFUNDED);
        assertThat(carton(d.lpn(0))).containsEntry("status", "IN_STOCK").containsEntry("return_ownership", "COMPANY");
        assertThat(asHcm(manager, () -> quarantine.release(d.lpn(0), manager))).isNotNull();
        assertThat(code(catchThrowable(() -> returns.recordCustodyChoice(request.id(), ReturnCustodyChoice.SEND_BACK,
                null, null, sales.userId())))).isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
        assertThat(inDatabase("UPDATE ordering.return_request SET custody_choice = 'SEND_BACK', custody_choice_by = ?, "
                + "custody_choice_at = NOW() WHERE id = ?", sales.userId(), request.id()))
                .as("ck_return_request_custody_choice").hasMessageContaining("ck_return_request_custody_choice");
    }

    // ---- M1 ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("M1: a late parcel's goods are the return's when its receipt commits - the proposal goes back at once, "
            + "so no refund is approved in between; the schema closes no receipt its return has not taken")
    void lateParcelTakenInTheReceiptsTransaction() {
        Delivered d = delivered(2);
        ReturnRequest request = approved(d, 100);
        back(request, d.lpn(0));
        returns.markGoodsComplete(request.id(), null, null, sales.userId());
        returns.proposeRefund(request.id(), new ReturnRequestService.ProposeRefundCommand(null, null, null),
                sales.userId());
        ReturnReceiptView late = draft(request);
        scan(late.id(), d.lpn(1), null);
        confirm(late.id());
        // No waiting for the listener: the commit that closed the receipt carried the goods to the return.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.return_request_receipt WHERE goods_receipt_id = ?",
                Long.class, late.id())).isEqualTo(1L);
        assertThat(statusOf(request)).isEqualTo(ReturnStatus.RECEIVED);
        assertThat(code(catchThrowable(() -> returns.approveSettlement(request.id(), null, accountant))))
                .isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
        assertThat(jdbc.queryForObject("SELECT sum(received_quantity) FROM ordering.return_request_line "
                + "WHERE return_request_id = ?", Integer.class, request.id())).isEqualTo(100);
        // The listener that runs after it finds the receipt taken and changes nothing.
        Await.until("the receipt's publications completed", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE completion_date IS NULL
                   AND event_type LIKE '%ReturnedGoodsReceived' AND serialized_event LIKE ?""", Long.class,
                "%" + late.id() + "%") == 0);
    }

    // ---- M2 ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("M2: a withdrawn return whose units another return claimed since takes no parcel - refused when the "
            + "draft is opened, naming that return, by the application and the schema; the parcel goes on that return "
            + "and is refunded there, and no publication is left failing")
    void withdrawnReturnRoutesToTheLiveOne() {
        Delivered d = delivered(2);
        ReturnRequest first = approved(d, 100);
        asSales(() -> returns.cancel(first.id(), null, "khách đổi ý", sales.userId()));
        ReturnRequest second = approved(d, 100);

        Throwable refused = catchThrowable(() -> draft(first));
        assertThat(code(refused)).isEqualTo(ErrorCode.RETURN_NOT_AWAITING_GOODS);
        assertThat(((BusinessException) refused).details()).containsEntry("claimedBy",
                List.of(second.rmaNumber()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM procurement.goods_receipts WHERE return_request_id = ?",
                Long.class, first.id())).as("nothing opened").isZero();
        UUID draftOfSecond = draft(second).id();
        assertThat(inDatabase("UPDATE ordering.return_request SET open_draft_receipt_id = ? WHERE id = ?",
                draftOfSecond, first.id())).as("tg_return_request_draft_opened")
                .hasMessageContaining("claimed by another return");
        asHcm(clerk, () -> returnReceipts.cancel(draftOfSecond));

        back(second, d.lpn(0), d.lpn(1));
        assertThat(statusOf(second)).isEqualTo(ReturnStatus.RECEIVED);
        assertThat(statusOf(first)).isEqualTo(ReturnStatus.CANCELLED);
    }

    @Test
    @DisplayName("M2: while a withdrawn return's parcel is counted, it claims what it approved again - no new return "
            + "takes those units meanwhile, so its goods are always takeable")
    void draftOfWithdrawnReturnHoldsItsUnits() {
        Delivered d = delivered(2);
        ReturnRequest first = approved(d, 100);
        asSales(() -> returns.cancel(first.id(), null, "khách đổi ý", sales.userId()));
        ReturnReceiptView counting = draft(first);
        assertThat(code(catchThrowable(() -> open(d, 100)))).isEqualTo(ErrorCode.RETURN_QUANTITY_EXCEEDED);
        scan(counting.id(), d.lpn(0), null);
        confirm(counting.id());
        assertThat(statusOf(first)).as("reopened by its parcel").isEqualTo(ReturnStatus.RECEIVED);
    }

    // ---- M3, L6 -----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("M3/L6 (Q-11): a carton never takes the SKU above its approval - a sealed one that does not fit is "
            + "refused whole, and so is a recount above it; the schema says the same")
    void neverAboveTheApproval() {
        Delivered d = delivered(3);
        ReturnRequest request = approved(d, 150, 75);
        ReturnReceiptView receipt = draft(request);
        scan(receipt.id(), d.lpn(0), null);
        assertThat(code(catchThrowable(() -> scan(receipt.id(), d.lpn(1), null))))
                .as("50 back, a sealed 50 on 75 approved").isEqualTo(ErrorCode.RETURN_CARTON_NOT_CLAIMED);
        scan(receipt.id(), d.lpn(1), 20);
        assertThat(code(catchThrowable(() -> scan(receipt.id(), d.lpn(1), 30))))
                .as("a recount to 30 brings 80").isEqualTo(ErrorCode.RETURN_CARTON_NOT_CLAIMED);
        assertThat(inDatabase("UPDATE procurement.goods_receipt_return_cartons SET counted_qty = 30 WHERE lpn = ? "
                + "AND receipt_id = ?", d.lpn(1), receipt.id())).as("check_return_receipt_carton on update")
                .hasMessageContaining("kept in custody");
        scan(receipt.id(), d.lpn(1), 25);
        Map<String, Object> pkg = jdbc.queryForMap("""
                SELECT p.id, s.lot_number FROM inventory.stock_package p
                  JOIN inventory.stock_item s ON s.stock_package_id = p.id WHERE p.lpn = ?""", d.lpn(2));
        assertThat(inDatabase("""
                INSERT INTO procurement.goods_receipt_return_cartons (id, receipt_id, stock_package_id, lpn, sku,
                    lot_number, quantity, scanned_by, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, NOW())""", Identifiers.newId(), receipt.id(), pkg.get("id"),
                d.lpn(2), sku.sku(), pkg.get("lot_number"), CARTON, clerk))
                .as("check_return_receipt_carton on insert").hasMessageContaining("kept in custody");
    }

    // ---- lifecycle L-3 ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("L-3: an expired return of a CLOSED order takes no parcel - the order would stay CLOSED while the "
            + "return settled money; the goods are kept in custody. The schema refuses it too")
    void closedOrderTakesNoParcel() {
        Delivered d = delivered(1);
        ReturnRequest request = approved(d, 50);
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.return_request SET return_deadline = NOW() - "
                + "interval '1 hour' WHERE id = ?", request.id()));
        assertThat(returns.expire(request.id())).isTrue();
        ReturnReceiptView counting = draft(request);
        Throwable refused = catchThrowable(() -> tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE ordering.customer_order SET status = 'CLOSED', closed_at = NOW() WHERE id = ?",
                    d.orderId());
            jdbc.execute("SET LOCAL session_replication_role = origin");
            jdbc.update("UPDATE ordering.return_request SET open_draft_receipt_id = NULL WHERE id = ?", request.id());
            jdbc.update("UPDATE ordering.return_request SET open_draft_receipt_id = ? WHERE id = ?", counting.id(),
                    request.id());
        }));
        assertThat(refused).as("tg_return_request_draft_opened").hasMessageContaining("order is CLOSED");
        asHcm(clerk, () -> returnReceipts.cancel(counting.id()));
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE ordering.customer_order SET status = 'CLOSED', closed_at = NOW() WHERE id = ?",
                    d.orderId());
        });
        assertThat(code(catchThrowable(() -> draft(request)))).isEqualTo(ErrorCode.RETURN_NOT_AWAITING_GOODS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM procurement.goods_receipts WHERE return_request_id = ? "
                + "AND status <> 'CANCELLED'", Long.class, request.id())).isZero();
    }

    // ---- L4 / M-3 ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("L4/M-3 (BE-23): no withdrawal - by the customer or Sales for them - while a parcel is counted; the "
            + "return says so (receiptDraftOpen), and the schema refuses the move too")
    void noWithdrawalWhileCounted() {
        Delivered d = delivered(1);
        ReturnRequest request = approved(d, 50);
        draft(request);
        assertThat(code(catchThrowable(() -> asSales(() -> returns.cancel(request.id(), null, "rút thay khách",
                sales.userId()))))).isEqualTo(ErrorCode.RETURN_RECEIPT_DRAFT_OPEN);
        assertThat(code(catchThrowable(() -> returns.cancel(request.id(), customerId, null, sales.userId()))))
                .isEqualTo(ErrorCode.RETURN_RECEIPT_DRAFT_OPEN);
        assertThat(inDatabase("UPDATE ordering.return_request SET status = 'CANCELLED', closed_at = NOW(), "
                + "cancelled_by = ?, cancellation_reason = 'x' WHERE id = ?", sales.userId(), request.id()))
                .as("tg_return_request_draft_holds").hasMessageContaining("while a parcel of it is counted");
        assertThat(statusOf(request)).isEqualTo(ReturnStatus.AWAITING_RETURN);
    }
}
