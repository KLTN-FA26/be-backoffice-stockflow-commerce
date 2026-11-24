package com.stockflow.order;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.inventory.internal.domain.StockPackageBalance;
import com.stockflow.inventory.internal.service.QuarantineDigestJob;
import com.stockflow.inventory.internal.service.StockPackageLookup;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ReturnEvidence;
import com.stockflow.order.internal.domain.ReturnReason;
import com.stockflow.order.internal.domain.ReturnRefusalReason;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnRequestRepository;
import com.stockflow.order.internal.domain.ReturnResolution;
import com.stockflow.order.internal.domain.ReturnStatus;
import com.stockflow.order.internal.service.ReturnRequestService;
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
import java.util.HashSet;
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
 * The WF-05 return decisions against the real database, each stated twice - by the application and by the schema
 * (CLAUDE.md §7): a refund refused with a reason by two people and its units never claimed again (Q-4, BE-12, BE-21,
 * BE-44, BE-45); a late parcel received on a document and nothing decided while one is counted (Q-11, BE-13, BE-23); a
 * return's receipt taking only what it approved, the rest kept in custody (BE-22); loose units with a photo and a cap
 * (Q-18, BE-43); an opened carton never made stock, confirmed by a manager who counted nothing (Q-18, BE-20), and shown
 * as such with the daily quarantine digest (BE-24); the return status transitions in the database (BE-47).
 *
 * <p>The orders have no installments (WF-05 TH-2: nothing paid in the system), so no refund moves money: what is under
 * test is the goods, the decisions and their guards. A piece is worth 2 200 dong.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class ReturnWf05IntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final AtomicLong ORDER_NUMBERS = new AtomicLong(760_000 + ThreadLocalRandom.current().nextInt(20_000));
    private static final Duration ASYNC = Duration.ofSeconds(20);
    private static final String QUARANTINE = "HCM-QC01";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D',
            'R', 0, 0, 0, 1, 0, 0, 0, 1};
    private static final int CARTON = 50;

    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired ReturnRequestService returns;
    @Autowired ReturnRequestRepository claims;
    @Autowired ReturnReceiptService returnReceipts;
    @Autowired StockPackageLookup packages;
    @Autowired QuarantineDigestJob digest;
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
                VALUES (?, 'Cong ty Tra Hang WF05', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                "wf05-" + customerId + "@example.com", "06" + (10_000_000 + ThreadLocalRandom.current()
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

    /** Delivered in {@code cartons} cartons of 50 on one shipment, with no installments (nothing paid, TH-2). */
    private Delivered delivered(int cartons) {
        UUID orderId = tx.execute(s -> {
            OrderLine line = Order.line(Identifiers.newId(), new Sku(sku.sku()), cartons * CARTON, Money.vnd(2_000), null)
                    .withCartons(sku.uomConversionId(), cartons, CARTON).withTax(BigDecimal.TEN);
            return orderRepository.save(Order.draft(OrderNumber.of(today, ORDER_NUMBERS.incrementAndGet()),
                    customerId, Identifiers.newId(), List.of(line), Instant.now())).id().value();
        });
        UUID lineId = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");   // not through the transitions in between
            jdbc.update("""
                    UPDATE ordering.customer_order SET status = 'DELIVERED', warehouse_id = ?,
                           shipped_at = NOW() - interval '2 days', delivered_at = NOW() - interval '1 day' WHERE id = ?""",
                    DemoData.WAREHOUSE_HCM, orderId);
        });
        ShippedCartons.Shipped shipped = ShippedCartons.ship(jdbc, tx, orderId, lineId, sku.sku(), cartons, CARTON,
                "LOT-WF05-" + orderId.toString().substring(0, 8), "DELIVERED", clerk);
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

    private ReturnRequest approved(Delivered d, int units) {
        ReturnRequest request = open(d, units);
        returns.addEvidence(request.id(), null, ReturnEvidence.Stage.CLAIM, png(), sales.userId());
        return returns.approve(request.id(), new ReturnRequestService.ApproveReturnCommand(null, null, "ok", null),
                coordinator);
    }

    private ReturnReceiptView draft(ReturnRequest request) {
        return asHcm(clerk, () -> returnReceipts.create(new ReturnReceiptService.Create(null, request.id(), QUARANTINE,
                null), clerk));
    }

    private ReturnReceiptView scan(UUID receipt, String lpn, Integer counted) {
        return asHcm(clerk, () -> returnReceipts.scan(receipt, lpn, null, counted, clerk));
    }

    private ReturnReceiptView confirm(UUID receipt, UUID by, boolean managerApproval) {
        return asHcm(by, () -> returnReceipts.confirm(receipt, by, managerApproval));
    }

    private void taken(UUID receipt) {
        Await.until("receipt " + receipt + " taken by its return", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM ordering.return_request_receipt WHERE goods_receipt_id = ?", Long.class,
                receipt) == 1);
    }

    private ReturnStatus statusOf(ReturnRequest request) {
        return ReturnStatus.valueOf(jdbc.queryForObject("SELECT status FROM ordering.return_request WHERE id = ?",
                String.class, request.id()));
    }

    private UUID draftOf(ReturnRequest request) {
        return jdbc.queryForObject("SELECT open_draft_receipt_id FROM ordering.return_request WHERE id = ?", UUID.class,
                request.id());
    }

    private int received(ReturnRequest request) {
        return jdbc.queryForObject("SELECT sum(received_quantity) FROM ordering.return_request_line "
                + "WHERE return_request_id = ?", Integer.class, request.id());
    }

    /** One of the receipt's own photos, as {@code POST /goods-receipts/{id}/photos} keeps it. */
    private UUID photo(UUID receipt) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO procurement.goods_receipt_photos (id, receipt_id, stage, storage_key, original_name,
                    content_type, size_bytes, checksum, taken_by, version, created_at)
                VALUES (?, ?, 'RECEIPT', ?, 'le.jpg', 'image/jpeg', 10, ?, ?, 0, NOW())""", id, receipt,
                "qc-evidence/" + id, "a".repeat(64), clerk));
        return id;
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private Throwable inDatabase(String sql, Object... args) {
        return catchThrowable(() -> tx.executeWithoutResult(s -> jdbc.update(sql, args)));
    }

    private long published(String type, String... fragments) {
        // One publication row per listener of the event: count the events, not the listeners.
        StringBuilder sql = new StringBuilder("SELECT count(DISTINCT serialized_event) FROM event_publication "
                + "WHERE event_type LIKE ?");
        Object[] args = new Object[fragments.length + 1];
        args[0] = "%" + type;
        for (int i = 0; i < fragments.length; i++) {
            sql.append(" AND serialized_event LIKE ?");
            args[i + 1] = "%" + fragments[i] + "%";
        }
        return jdbc.queryForObject(sql.toString(), Long.class, args);
    }

    // ---- BE-12, BE-21, BE-44, BE-45 ---------------------------------------------------------------------------------

    @Test
    @DisplayName("BE-12/21/44 (Q-4): goods back usable, refused with a reason, approved by a second person - RESOLVED "
            + "with nothing settled and no invoice adjustment; its units can never be claimed again; the database "
            + "refuses a refusal with no reason, by one person, or adjusting the invoice for it")
    void refusalByTwoPeople() {
        Delivered d = delivered(2);
        ReturnRequest request = approved(d, 100);
        ReturnReceiptView receipt = draft(request);
        scan(receipt.id(), d.lpn(0), null);
        scan(receipt.id(), d.lpn(1), null);
        confirm(receipt.id(), clerk, false);
        taken(receipt.id());
        assertThat(statusOf(request)).isEqualTo(ReturnStatus.RECEIVED);
        UUID line = request.lines().getFirst().id();

        assertThat(code(catchThrowable(() -> returns.proposeRefund(request.id(),
                new ReturnRequestService.ProposeRefundCommand(Map.of(line, 0), null, null), sales.userId()))))
                .isEqualTo(ErrorCode.RETURN_REFUSAL_REASON_REQUIRED);
        ReturnRequest proposed = returns.proposeRefund(request.id(), new ReturnRequestService.ProposeRefundCommand(
                Map.of(line, 0), "kho khách ẩm", null, ReturnRefusalReason.CUSTOMER_DAMAGE,
                "Thùng bị ẩm mốc do lưu kho"), sales.userId());
        assertThat(proposed.status()).isEqualTo(ReturnStatus.SETTLEMENT_PENDING);
        assertThat(jdbc.queryForMap("SELECT refusal_reason, refusal_note FROM ordering.return_request WHERE id = ?",
                request.id())).containsEntry("refusal_reason", "CUSTOMER_DAMAGE")
                .containsEntry("refusal_note", "Thùng bị ẩm mốc do lưu kho");
        assertThat(inDatabase("UPDATE ordering.return_request SET refusal_reason = NULL, refusal_note = NULL "
                + "WHERE id = ?", request.id())).as("check_return_request_refund").isNotNull()
                .hasMessageContaining("without a refusal reason");

        assertThat(code(catchThrowable(() -> returns.approveSettlement(request.id(), null, sales.userId()))))
                .isEqualTo(ErrorCode.RETURN_SELF_APPROVAL);
        ReturnRequest resolved = returns.approveSettlement(request.id(), null, accountant);
        assertThat(resolved.status()).isEqualTo(ReturnStatus.RESOLVED);
        assertThat(resolved.outcome()).isEqualTo(ReturnRequest.Outcome.REFUSED);
        assertThat(resolved.vatAdjustmentRequired()).isFalse();
        assertThat(published("ReturnResolved", request.id().toString(), "CUSTOMER_DAMAGE")).isEqualTo(1);
        assertThat(inDatabase("UPDATE ordering.return_request SET settled_by = proposed_by WHERE id = ?", request.id()))
                .as("ck_return_request_four_eyes").hasMessageContaining("ck_return_request_four_eyes");
        assertThat(inDatabase("UPDATE ordering.return_request SET vat_adjustment_required = TRUE WHERE id = ?",
                request.id())).as("BE-44: the invoice follows the units refunded")
                .hasMessageContaining("exactly when units were refunded");

        // BE-21: the 100 units back stay claimed by the refused return - the bound a new claim is checked against
        // (ReturnRequest.open, whatever the order's status; the domain refusal is ReturnWf05DecisionsTest's).
        assertThat(claims.claimsOf(d.orderId()).inAll()).containsEntry(d.lineId(), 100);
        assertThat(jdbc.queryForObject("SELECT ordering.return_line_claimed('RESOLVED', 100, 100, 100, 0)",
                Integer.class)).as("check_return_request_line counts them the same way").isEqualTo(100);
        // BE-45: nothing refunded - never "partially refunded" (where it goes instead is G1's X-8).
        assertThat(orders.findById(d.orderId()).orElseThrow().status())
                .isNotIn(OrderStatus.PARTIALLY_REFUNDED, OrderStatus.REFUNDED);
    }

    // ---- BE-13, BE-23 -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BE-13/23 (Q-11): nothing is decided while a parcel is on a draft; a parcel after the goods were marked "
            + "complete, or while the refund waits for approval, is received on a receipt of its own and sends the "
            + "proposal back; once RESOLVED, no receipt")
    void lateParcelsAndDrafts() {
        Delivered d = delivered(3);
        ReturnRequest request = approved(d, 150);
        ReturnReceiptView first = draft(request);
        assertThat(draftOf(request)).isEqualTo(first.id());
        scan(first.id(), d.lpn(0), null);
        confirm(first.id(), clerk, false);
        taken(first.id());
        assertThat(draftOf(request)).isNull();
        assertThat(statusOf(request)).isEqualTo(ReturnStatus.AWAITING_RETURN);

        ReturnReceiptView counting = draft(request);
        assertThat(code(catchThrowable(() -> returns.markGoodsComplete(request.id(), null, null, sales.userId()))))
                .isEqualTo(ErrorCode.RETURN_RECEIPT_DRAFT_OPEN);
        // Its deadline passes while the parcel is counted: the sweep leaves it alone.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.return_request SET return_deadline = NOW() - "
                + "interval '1 hour' WHERE id = ?", request.id()));
        assertThat(returns.expiryCandidates(10_000)).doesNotContain(request.id());
        assertThat(returns.expire(request.id())).isFalse();
        assertThat(statusOf(request)).isEqualTo(ReturnStatus.AWAITING_RETURN);
        asHcm(clerk, () -> returnReceipts.cancel(counting.id()));
        assertThat(draftOf(request)).isNull();
        assertThat(returns.expiryCandidates(10_000)).contains(request.id());
        assertThat(returns.expire(request.id())).isTrue();
        assertThat(statusOf(request)).as("50 back: received, not expired").isEqualTo(ReturnStatus.RECEIVED);

        // A late parcel: received on a document while RECEIVED; no proposal while it is counted.
        ReturnReceiptView late = draft(request);
        assertThat(code(catchThrowable(() -> returns.proposeRefund(request.id(),
                new ReturnRequestService.ProposeRefundCommand(null, null, null), sales.userId()))))
                .isEqualTo(ErrorCode.RETURN_RECEIPT_DRAFT_OPEN);
        scan(late.id(), d.lpn(1), null);
        confirm(late.id(), clerk, false);
        taken(late.id());
        assertThat(received(request)).isEqualTo(100);
        assertThat(statusOf(request)).isEqualTo(ReturnStatus.RECEIVED);

        // Another one while the refund waits for its approver: the proposal goes back to Sales.
        returns.proposeRefund(request.id(), new ReturnRequestService.ProposeRefundCommand(null, null, null),
                sales.userId());
        ReturnReceiptView later = draft(request);
        assertThat(code(catchThrowable(() -> returns.approveSettlement(request.id(), null, accountant))))
                .isEqualTo(ErrorCode.RETURN_RECEIPT_DRAFT_OPEN);
        scan(later.id(), d.lpn(2), null);
        confirm(later.id(), clerk, false);
        taken(later.id());
        assertThat(statusOf(request)).isEqualTo(ReturnStatus.RECEIVED);
        assertThat(received(request)).isEqualTo(150);
        assertThat(jdbc.queryForObject("SELECT sum(refund_quantity) FROM ordering.return_request_line "
                + "WHERE return_request_id = ?", Integer.class, request.id())).as("proposal sent back").isZero();

        returns.proposeRefund(request.id(), new ReturnRequestService.ProposeRefundCommand(null, null, null),
                sales.userId());
        assertThat(returns.approveSettlement(request.id(), null, accountant).status()).isEqualTo(ReturnStatus.RESOLVED);
        assertThat(code(catchThrowable(() -> draft(request)))).isEqualTo(ErrorCode.RETURN_NOT_AWAITING_GOODS);
    }

    // ---- BE-22 ------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BE-22 (Q-11): a return's receipt takes no carton beyond what it approved (RETURN_CARTON_NOT_CLAIMED, "
            + "the database too); such goods are kept in custody on the return - by staff, never by the customer")
    void onlyWhatWasApproved() {
        Delivered d = delivered(2);
        ReturnRequest request = approved(d, 50);
        ReturnReceiptView receipt = draft(request);
        scan(receipt.id(), d.lpn(0), null);
        assertThat(code(catchThrowable(() -> scan(receipt.id(), d.lpn(1), null))))
                .isEqualTo(ErrorCode.RETURN_CARTON_NOT_CLAIMED);
        Map<String, Object> pkg = jdbc.queryForMap("""
                SELECT p.id, s.lot_number FROM inventory.stock_package p
                  JOIN inventory.stock_item s ON s.stock_package_id = p.id WHERE p.lpn = ?""", d.lpn(1));
        assertThat(inDatabase("""
                INSERT INTO procurement.goods_receipt_return_cartons (id, receipt_id, stock_package_id, lpn, sku,
                    lot_number, quantity, scanned_by, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, NOW())""", Identifiers.newId(), receipt.id(), pkg.get("id"),
                d.lpn(1), sku.sku(), pkg.get("lot_number"), CARTON, clerk))
                .as("check_return_receipt_carton").hasMessageContaining("kept in custody");

        ReturnRequest kept = returns.addEvidence(request.id(), null, ReturnEvidence.Stage.CUSTODY,
                new ReturnEvidence.Custody(null, d.lpn(1), CARTON, "Biên bản giữ hộ số 12"), png(), clerk);
        assertThat(kept.evidence()).filteredOn(e -> e.stage() == ReturnEvidence.Stage.CUSTODY).singleElement()
                .satisfies(e -> {
                    assertThat(e.custody().lpn()).isEqualTo(d.lpn(1));
                    assertThat(e.custody().quantity()).isEqualTo(CARTON);
                    assertThat(e.uploadedBy()).isEqualTo(clerk);
                });
        assertThat(code(catchThrowable(() -> returns.addEvidence(request.id(), customerId, ReturnEvidence.Stage.CUSTODY,
                new ReturnEvidence.Custody(sku.sku(), null, 1, null), png(), clerk)))).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT status FROM inventory.stock_package WHERE lpn = ?", String.class,
                d.lpn(1))).as("not stock").isEqualTo("CONSUMED");
    }

    // ---- BE-43 ------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BE-43 (Q-18): loose units need a photo of the receipt and never take the SKU above what was approved "
            + "(the database too); the receipt is then confirmed only by a manager who recorded none of them")
    void looseUnitsControls() {
        Delivered d = delivered(2);
        ReturnRequest request = approved(d, 50);
        ReturnReceiptView receipt = draft(request);
        assertThat(code(catchThrowable(() -> asHcm(clerk, () -> returnReceipts.recordLoose(receipt.id(), sku.sku(),
                null, 10, null, clerk))))).isEqualTo(ErrorCode.RETURN_LOOSE_PHOTO_REQUIRED);
        assertThat(code(catchThrowable(() -> asHcm(clerk, () -> returnReceipts.recordLoose(receipt.id(), sku.sku(),
                null, 10, UUID.randomUUID(), clerk))))).isEqualTo(ErrorCode.RETURN_LOOSE_PHOTO_REQUIRED);
        UUID photo = photo(receipt.id());
        assertThat(code(catchThrowable(() -> asHcm(clerk, () -> returnReceipts.recordLoose(receipt.id(), sku.sku(),
                null, 51, photo, clerk))))).isEqualTo(ErrorCode.RETURN_LOOSE_EXCEEDS_APPROVED);
        asHcm(clerk, () -> returnReceipts.recordLoose(receipt.id(), sku.sku(), null, 30, photo, clerk));
        ReturnReceiptView recounted = asHcm(clerk, () -> returnReceipts.recordLoose(receipt.id(), sku.sku(), null, 50,
                photo, clerk));
        assertThat(recounted.looseLines()).singleElement().satisfies(l -> {
            assertThat(l.quantity()).isEqualTo(50);
            assertThat(l.photoId()).isEqualTo(photo);
        });
        assertThat(recounted.managerConfirmationRequired()).isTrue();
        assertThat(code(catchThrowable(() -> scan(receipt.id(), d.lpn(0), null))))
                .as("the 50 approved are back loose").isEqualTo(ErrorCode.RETURN_CARTON_NOT_CLAIMED);
        assertThat(inDatabase("""
                INSERT INTO procurement.goods_receipt_return_loose_lines (id, receipt_id, sku, lot_number, quantity,
                    recorded_by, photo_id, version, created_at)
                VALUES (?, ?, ?, 'LOT-OTHER', 1, ?, ?, 0, NOW())""", Identifiers.newId(), receipt.id(), sku.sku(), clerk,
                photo)).as("check_return_receipt_loose_line").hasMessageContaining("would bring back more");
        assertThat(inDatabase("""
                INSERT INTO procurement.goods_receipt_return_loose_lines (id, receipt_id, sku, lot_number, quantity,
                    recorded_by, version, created_at)
                VALUES (?, ?, ?, 'LOT-OTHER', 1, ?, 0, NOW())""", Identifiers.newId(), receipt.id(), sku.sku(), clerk))
                .as("no photo").isNotNull();

        assertThat(code(catchThrowable(() -> confirm(receipt.id(), clerk, false))))
                .isEqualTo(ErrorCode.RETURN_RECEIPT_MANAGER_CONFIRMATION_REQUIRED);
        assertThat(code(catchThrowable(() -> confirm(receipt.id(), clerk, true))))
                .isEqualTo(ErrorCode.RETURN_RECEIPT_SELF_CONFIRMATION);
        assertThat(confirm(receipt.id(), manager, true).confirmedBy()).isEqualTo(manager);
        taken(receipt.id());
        assertThat(received(request)).isEqualTo(50);
    }

    // ---- BE-20, BE-24 -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BE-20 (Q-18): an opened carton never becomes stock - it stays consumed, marked, out of the ledger, its "
            + "count the return's; the sealed one goes into quarantine; the carton views say opened (F-22) and the "
            + "daily digest counts what waits in quarantine")
    void openedCartonIsNotStock() {
        Delivered d = delivered(2);
        ReturnRequest request = approved(d, 100);
        ReturnReceiptView receipt = draft(request);
        scan(receipt.id(), d.lpn(0), null);
        ReturnReceiptView counted = scan(receipt.id(), d.lpn(1), 30);
        assertThat(counted.cartons()).filteredOn(c -> c.lpn().equals(d.lpn(1))).singleElement().satisfies(c -> {
            assertThat(c.opened()).isTrue();
            assertThat(c.stockEntered()).isFalse();
        });
        assertThat(counted.managerConfirmationRequired()).isTrue();
        assertThat(code(catchThrowable(() -> confirm(receipt.id(), clerk, true))))
                .isEqualTo(ErrorCode.RETURN_RECEIPT_SELF_CONFIRMATION);
        confirm(receipt.id(), manager, true);

        assertThat(jdbc.queryForMap("""
                SELECT p.status, p.return_opened_at IS NOT NULL AS opened, sum(s.on_hand) AS on_hand
                  FROM inventory.stock_package p JOIN inventory.stock_item s ON s.stock_package_id = p.id
                 WHERE p.lpn = ? GROUP BY p.status, p.return_opened_at""", d.lpn(1)))
                .containsEntry("status", "CONSUMED").containsEntry("opened", true);
        assertThat(((Number) jdbc.queryForObject("SELECT sum(s.on_hand) FROM inventory.stock_item s JOIN "
                + "inventory.stock_package p ON p.id = s.stock_package_id WHERE p.lpn = ?", Number.class, d.lpn(1)))
                .intValue()).as("no stock from a label").isZero();
        assertThat(jdbc.queryForMap("""
                SELECT p.status, s.status AS stock, s.on_hand FROM inventory.stock_package p
                  JOIN inventory.stock_item s ON s.stock_package_id = p.id WHERE p.lpn = ? AND s.on_hand > 0""",
                d.lpn(0))).containsEntry("status", "IN_STOCK").containsEntry("stock", "QUARANTINE")
                .containsEntry("on_hand", CARTON);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_movement WHERE reference_type = "
                + "'GOODS_RECEIPT' AND reference_id = ?", Long.class, receipt.id())).isEqualTo(1L);
        assertThat(jdbc.queryForList("SELECT lpn || ' ' || stock_entered FROM procurement.goods_receipt_return_cartons "
                + "WHERE receipt_id = ? ORDER BY lpn", String.class, receipt.id()))
                .containsExactlyInAnyOrder(d.lpn(0) + " true", d.lpn(1) + " false");
        assertThat(published("ReturnedGoodsReceived", receipt.id().toString(), "\"stockEntered\":false")).isEqualTo(1);
        taken(receipt.id());
        assertThat(received(request)).as("what was counted").isEqualTo(80);

        StockPackageBalance.Detail opened = asHcm(manager, () -> packages.byLpn(d.lpn(1)));
        assertThat(opened.carton().openedOnReturn()).isTrue();
        assertThat(opened.carton().returnOpenedAt()).isNotNull();
        assertThat(asHcm(manager, () -> packages.byLpn(d.lpn(0))).carton().openedOnReturn()).isFalse();

        // It is never taken back again.
        ReturnReceiptView again = draft(request);
        assertThat(code(catchThrowable(() -> scan(again.id(), d.lpn(1), null))))
                .isEqualTo(ErrorCode.RETURN_CARTON_IN_WAREHOUSE);
        asHcm(clerk, () -> returnReceipts.cancel(again.id()));

        digest.announce();
        assertThat(published("ReturnedCartonsInQuarantine", DemoData.WAREHOUSE_HCM.toString())).isPositive();
    }

    // ---- BE-47 ------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BE-47: the database's return transitions are ReturnStatus.canTransitionTo, and it refuses any other")
    void transitionsMirrored() {
        Set<String> rows = new HashSet<>(jdbc.queryForList("SELECT from_status || '>' || to_status "
                + "FROM ordering.return_request_status_transition", String.class));
        assertThat(rows).isEqualTo(ReturnStatus.edges());

        ReturnRequest request = open(delivered(1), 10);
        assertThat(inDatabase("UPDATE ordering.return_request SET status = 'RECEIVED' WHERE id = ?", request.id()))
                .hasMessageContaining("cannot move from REQUESTED to RECEIVED");
        assertThat(statusOf(request)).isEqualTo(ReturnStatus.REQUESTED);
    }
}
