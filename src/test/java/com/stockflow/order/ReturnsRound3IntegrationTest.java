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
import com.stockflow.inventory.internal.domain.StockPackageBalance;
import com.stockflow.inventory.internal.service.CartonQuarantineService;
import com.stockflow.inventory.internal.service.CustomerGoodsCustody;
import com.stockflow.inventory.internal.service.StockPackageLookup;
import com.stockflow.order.internal.domain.CustodyOutcome;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ReturnCustodyChoice;
import com.stockflow.order.internal.domain.ReturnCustodyNoteChoice;
import com.stockflow.order.internal.domain.ReturnEvidence;
import com.stockflow.order.internal.domain.ReturnReason;
import com.stockflow.order.internal.domain.ReturnRefusalReason;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnResolution;
import com.stockflow.order.internal.domain.ReturnStatus;
import com.stockflow.order.internal.service.ReturnCustodyNotes;
import com.stockflow.order.internal.service.ReturnRequestQueries;
import com.stockflow.order.internal.service.ReturnRequestService;
import com.stockflow.procurement.internal.service.ReturnReceiptPaperService;
import com.stockflow.procurement.internal.service.ReturnReceiptService;
import com.stockflow.procurement.internal.service.ReturnReceiptService.ReturnReceiptView;
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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
 * Audit round 3, returns and returned stock, each scenario as the audit reproduced it and each rule stated twice - by
 * the application and by the schema (CLAUDE.md §7):
 * <ul>
 *   <li>RAD-1 (Q-4): cartons are settled per SKU and carton - a refused SKU's carton on a return that refunded another,
 *       and a whole carton beyond the units refunded, stay the customer's, with a custody exit;</li>
 *   <li>RAD-2: a clerk of another warehouse writes no custody note onto a return;</li>
 *   <li>RAD-3 (Q-11): goods kept on a custody note end as the customer chose, recorded by Sales and carried out by the
 *       warehouse, listed for it;</li>
 *   <li>RAD-4: a carton waiting for its return's refund says so - its ownership and the RMA - and the refusal names
 *       the return;</li>
 *   <li>RAD-5: a receipt's 01-VT does not change when its carton comes back again later on a return that is
 *       refused.</li>
 * </ul>
 * The orders have no installments (WF-05 TH-2: nothing paid in the system), so no refund moves money; a carton holds
 * 50.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class ReturnsRound3IntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final AtomicLong ORDER_NUMBERS = new AtomicLong(800_000 + ThreadLocalRandom.current().nextInt(15_000));
    private static final String QUARANTINE = "HCM-QC01";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D',
            'R', 0, 0, 0, 1, 0, 0, 0, 1};
    private static final int CARTON = 50;

    @Autowired OrderRepository orderRepository;
    @Autowired ReturnRequestService returns;
    @Autowired ReturnRequestQueries queries;
    @Autowired ReturnCustodyNotes custodyNotes;
    @Autowired ReturnReceiptService returnReceipts;
    @Autowired ReturnReceiptPaperService papers;
    @Autowired CartonQuarantineService quarantine;
    @Autowired CustomerGoodsCustody custody;
    @Autowired StockPackageLookup lookup;
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

    /** A delivered order: its lines, and the cartons each shipped. */
    private record Delivered(UUID orderId, List<UUID> lineIds, List<ShippedCartons.Shipped> shipped) {

        UUID lineId() {
            return lineIds.getFirst();
        }

        String lpn(int line, int i) {
            return shipped.get(line).lpns().get(i);
        }

        String lpn(int i) {
            return lpn(0, i);
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
                VALUES (?, 'Cong ty Tra Hang R3', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                "r3-" + customerId + "@example.com", "07" + (10_000_000 + ThreadLocalRandom.current()
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

    /** A clerk of another warehouse: WAREHOUSE scope, assigned none of HCM. */
    private <T> T asElsewhere(Supplier<T> call) {
        CurrentUser staff = new CurrentUser(clerk, "elsewhere-" + clerk, Set.of(Role.WAREHOUSE_STAFF), Set.of(),
                DataScope.WAREHOUSE, Set.of("ZZZ"), Set.of(Identifiers.newId()));
        return WithCurrentUser.call(staff, DataScope.WAREHOUSE, call);
    }

    /** An order of one line per SKU, {@code cartons} cartons each, delivered on one shipment. */
    private Delivered delivered(int cartons, CustomerSkuRows.Issued... skus) {
        return delivered(true, cartons, skus);
    }

    /** As above; {@code ship} false leaves the order DELIVERED with no shipment yet, for a carton shipped again. */
    private Delivered delivered(boolean ship, int cartons, CustomerSkuRows.Issued... skus) {
        List<CustomerSkuRows.Issued> lines = skus.length == 0 ? List.of(sku) : List.of(skus);
        UUID orderId = tx.execute(s -> {
            List<OrderLine> orderLines = new ArrayList<>();
            for (CustomerSkuRows.Issued each : lines) {
                orderLines.add(Order.line(Identifiers.newId(), new Sku(each.sku()), cartons * CARTON, Money.vnd(2_000),
                        null).withCartons(each.uomConversionId(), cartons, CARTON).withTax(BigDecimal.TEN));
            }
            return orderRepository.save(Order.draft(OrderNumber.of(today, ORDER_NUMBERS.incrementAndGet()),
                    customerId, Identifiers.newId(), orderLines, Instant.now())).id().value();
        });
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    UPDATE ordering.customer_order SET status = 'DELIVERED', warehouse_id = ?,
                           shipped_at = NOW() - interval '2 days', delivered_at = NOW() - interval '1 day' WHERE id = ?""",
                    DemoData.WAREHOUSE_HCM, orderId);
        });
        List<UUID> lineIds = new ArrayList<>();
        List<ShippedCartons.Shipped> shipped = new ArrayList<>();
        for (CustomerSkuRows.Issued each : lines) {
            UUID lineId = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ? AND sku = ?",
                    UUID.class, orderId, each.sku());
            lineIds.add(lineId);
            if (!ship) {
                continue;
            }
            String lot = "LOT-R3-" + orderId.toString().substring(0, 8);
            shipped.add(shipped.isEmpty()
                    ? ShippedCartons.ship(jdbc, tx, orderId, lineId, each.sku(), cartons, CARTON, lot, "DELIVERED", clerk)
                    : ShippedCartons.onShipment(jdbc, tx, orderId, lineId, each.sku(), cartons, CARTON, lot,
                    shipped.getFirst().shipmentId(), clerk));
        }
        return new Delivered(orderId, lineIds, shipped);
    }

    private static FileUpload png() {
        return new FileUpload("anh.png", "image/png", PNG.length, new ByteArrayInputStream(PNG));
    }

    /** Opened for every line of {@code d}, {@code units} each, and approved in full. */
    private ReturnRequest approved(Delivered d, int units) {
        ReturnRequest request = asSales(() -> returns.openForCustomer(new ReturnRequestService.OpenReturnCommand(
                UUID.randomUUID(), d.orderId(), ReturnReason.DAMAGED_IN_TRANSIT, "móp thùng", ReturnResolution.REFUND,
                d.lineIds().stream().map(line -> new ReturnRequest.Claim(line, units)).toList(), null, null),
                sales.userId()));
        returns.addEvidence(request.id(), null, ReturnEvidence.Stage.CLAIM, png(), sales.userId());
        return returns.approve(request.id(), new ReturnRequestService.ApproveReturnCommand(Map.of(), null, "ok",
                null), coordinator);
    }

    /** Back on a receipt of its own, every carton named sealed, confirmed. */
    private ReturnReceiptView back(ReturnRequest request, String... lpns) {
        ReturnReceiptView receipt = asHcm(clerk, () -> returnReceipts.create(new ReturnReceiptService.Create(null,
                request.id(), QUARANTINE, null), clerk));
        for (String lpn : lpns) {
            asHcm(clerk, () -> returnReceipts.scan(receipt.id(), lpn, null, null, clerk));
        }
        return asHcm(clerk, () -> returnReceipts.confirm(receipt.id(), clerk, false));
    }

    /** Sales propose {@code units} per return line, withholding the rest as customer damage; the accountant approves. */
    private ReturnRequest refunded(ReturnRequest request, Map<UUID, Integer> units) {
        returns.proposeRefund(request.id(), new ReturnRequestService.ProposeRefundCommand(units, "kiểm hàng",
                null, ReturnRefusalReason.CUSTOMER_DAMAGE, "Thùng ẩm do kho của khách"), sales.userId());
        return returns.approveSettlement(request.id(), null, accountant);
    }

    private UUID lineOf(ReturnRequest request, String skuCode) {
        return request.lines().stream().filter(l -> l.sku().equals(skuCode)).findFirst().orElseThrow().id();
    }

    private Map<String, Object> carton(String lpn) {
        return jdbc.queryForMap("""
                SELECT p.status, p.return_ownership, p.return_request_id, p.custody_choice, s.status AS stock,
                       s.on_hand FROM inventory.stock_package p JOIN inventory.stock_item s ON s.stock_package_id = p.id
                 WHERE p.lpn = ?""", lpn);
    }

    private long reversals(ReturnRequest request, String lpn) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_movement m JOIN inventory.stock_package p
                    ON p.id = m.stock_package_id
                 WHERE m.reference_type = 'RETURN_REQUEST' AND m.reference_id = ? AND p.lpn = ?""", Long.class,
                request.id(), lpn);
    }

    private String voucher(UUID receiptId, String sample) {
        try {
            return DocumentSamples.text(DocumentSamples.keep(sample, asHcm(clerk, () -> papers.voucher(receiptId))
                    .content()));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private Throwable inDatabase(String sql, Object... args) {
        return catchThrowable(() -> tx.executeWithoutResult(s -> jdbc.update(sql, args)));
    }

    // ---- RAD-1 ------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("RAD-1: 12oz refunded, 16oz refused on the same return - the 16oz carton stays the customer's (stock-in "
            + "reversed, never released), the 12oz carton is the company's; Sales record the custody choice on the "
            + "REFUNDED return; the 01-VT receives only the 12oz carton")
    void aRefusedSkuStaysTheCustomersOnARefundedReturn() {
        CustomerSkuRows.Issued other = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, CARTON, "STANDARD",
                true);
        Delivered d = delivered(1, sku, other);
        ReturnRequest request = approved(d, CARTON);
        ReturnReceiptView receipt = back(request, d.lpn(0, 0), d.lpn(1, 0));
        ReturnRequest resolved = refunded(request, Map.of(lineOf(request, sku.sku()), CARTON,
                lineOf(request, other.sku()), 0));

        assertThat(resolved.outcome()).isEqualTo(ReturnRequest.Outcome.REFUNDED);
        assertThat(resolved.vatAdjustmentRequired()).isTrue();
        assertThat(carton(d.lpn(0, 0))).containsEntry("status", "IN_STOCK").containsEntry("return_ownership", "COMPANY");
        assertThat(carton(d.lpn(1, 0))).containsEntry("status", "CONSUMED")
                .containsEntry("return_ownership", "CUSTOMER_HELD").containsEntry("on_hand", 0);
        assertThat(reversals(request, d.lpn(1, 0))).isEqualTo(1L);
        assertThat(reversals(request, d.lpn(0, 0))).isZero();
        assertThat(code(catchThrowable(() -> asHcm(manager, () -> quarantine.release(d.lpn(1, 0), manager)))))
                .as("the customer's goods are out of the books").isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
        assertThat(asHcm(manager, () -> quarantine.release(d.lpn(0, 0), manager))).isNotNull();

        assertThat(queries.detail(request.id(), null).custodyCartons()).singleElement()
                .satisfies(held -> assertThat(held.lpn()).isEqualTo(d.lpn(1, 0)));
        ReturnRequest chose = returns.recordCustodyChoice(request.id(), ReturnCustodyChoice.SEND_BACK, null, null,
                sales.userId());
        assertThat(chose.custodyChoice()).isEqualTo(ReturnCustodyChoice.SEND_BACK);
        assertThat(asHcm(manager, () -> custody.held(request.id(), false, 0, 20)).items()).singleElement()
                .satisfies(c -> assertThat(c.lpn()).isEqualTo(d.lpn(1, 0)));

        ReturnReceiptView seen = asHcm(clerk, () -> returnReceipts.get(receipt.id()));
        assertThat(seen.cartons()).filteredOn(ReturnReceiptService.CartonView::heldForCustomer)
                .extracting(ReturnReceiptService.CartonView::lpn).containsExactly(d.lpn(1, 0));
        assertThat(voucher(receipt.id(), "r3-01vt-refused-sku")).contains("không được hoàn tiền theo yêu cầu trả");
    }

    @Test
    @DisplayName("RAD-1 / RAD-4: two sealed cartons back, one carton's worth refunded - the first in label order is the "
            + "company's, the other whole carton the customer's; while the refund waited, the carton said so (PENDING, "
            + "the RMA) and its release named the return; the schema refuses a split other than the refund's")
    void wholeCartonsBeyondTheRefundStayTheCustomers() {
        Delivered d = delivered(2);
        ReturnRequest request = approved(d, 2 * CARTON);
        back(request, d.lpn(0), d.lpn(1));

        // RAD-4: the lookup tells the quarantine screen why it may not act, and the refusal names the return.
        StockPackageBalance waiting = asHcm(manager, () -> lookup.byLpn(d.lpn(0))).carton();
        assertThat(waiting.returnOwnership()).isEqualTo("PENDING");
        assertThat(waiting.returnRequestId()).isEqualTo(request.id());
        assertThat(waiting.returnRmaNumber()).isEqualTo(request.rmaNumber());
        assertThat(asHcm(manager, () -> lookup.cartons(new StockPackageLookup.CartonQuery(QUARANTINE, sku.sku(), null,
                0, 20, null))).items()).allSatisfy(c -> assertThat(c.returnRmaNumber()).isEqualTo(request.rmaNumber()));
        Throwable pending = catchThrowable(() -> asHcm(manager, () -> quarantine.release(d.lpn(0), manager)));
        assertThat(code(pending)).isEqualTo(ErrorCode.STOCK_PACKAGE_DECISION_PENDING);
        assertThat(((BusinessException) pending).details()).containsEntry("returnOwnership", "PENDING")
                .containsEntry("returnRequestId", request.id());

        String first = d.lpn(0).compareTo(d.lpn(1)) < 0 ? d.lpn(0) : d.lpn(1);
        String second = first.equals(d.lpn(0)) ? d.lpn(1) : d.lpn(0);
        returns.proposeRefund(request.id(), new ReturnRequestService.ProposeRefundCommand(
                Map.of(request.lines().getFirst().id(), CARTON), "kiểm hàng", null, ReturnRefusalReason.CUSTOMER_DAMAGE,
                "Thùng ẩm do kho của khách"), sales.userId());
        // The schema computes the same split at commit: the approval's transaction making the second carton the
        // company's as well is refused whole.
        Throwable refused = catchThrowable(() -> tx.executeWithoutResult(s -> {
            returns.approveSettlement(request.id(), null, accountant);
            jdbc.update("UPDATE inventory.stock_package SET return_ownership = 'COMPANY', custody_held_at = NULL "
                    + "WHERE lpn = ?", second);
        }));
        assertThat(refused).as("tg_return_request_goods_settled").hasMessageContaining("refund of their SKU");

        ReturnRequest resolved = returns.approveSettlement(request.id(), null, accountant);
        assertThat(resolved.outcome()).isEqualTo(ReturnRequest.Outcome.REFUNDED);
        assertThat(carton(first)).containsEntry("return_ownership", "COMPANY").containsEntry("status", "IN_STOCK");
        assertThat(carton(second)).containsEntry("return_ownership", "CUSTOMER_HELD")
                .containsEntry("status", "CONSUMED");
        assertThat(asHcm(manager, () -> lookup.byLpn(first)).carton().returnOwnership()).isEqualTo("COMPANY");

        returns.recordCustodyChoice(request.id(), ReturnCustodyChoice.DESTROY_ON_BEHALF, "Biên bản đồng ý huỷ hộ 7",
                null, sales.userId());
        assertThat(carton(second)).containsEntry("custody_choice", "DESTROY_ON_BEHALF");
        assertThat(carton(first)).containsEntry("custody_choice", null);
    }

    // ---- RAD-2, RAD-3 -----------------------------------------------------------------------------------------------

    private ReturnRequest withCustodyNote(ReturnRequest request, String lpn, int units) {
        return asHcm(clerk, () -> returns.addEvidence(request.id(), null, ReturnEvidence.Stage.CUSTODY,
                new ReturnEvidence.Custody(lpn == null ? "CUP-16OZ" : null, lpn, units, "16oz gửi nhầm"), png(),
                clerk));
    }

    private static UUID custodyNoteOf(ReturnRequest request) {
        return request.evidence().stream().filter(e -> e.stage() == ReturnEvidence.Stage.CUSTODY)
                .reduce((a, b) -> b).orElseThrow().id();
    }

    @Test
    @DisplayName("RAD-2: a clerk of another warehouse records no custody note on this warehouse's return - refused as "
            + "the return's detail refuses them, before any photo is kept")
    void custodyNoteIsWarehouseScoped() {
        Delivered d = delivered(1);
        ReturnRequest request = approved(d, CARTON);
        assertThat(code(catchThrowable(() -> asElsewhere(() -> returns.addEvidence(request.id(), null,
                ReturnEvidence.Stage.CUSTODY, new ReturnEvidence.Custody("X", null, 1, null), png(), clerk)))))
                .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        assertThat(code(catchThrowable(() -> asElsewhere(() -> queries.detail(request.id(), null)))))
                .as("the read refuses the same").isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.return_request_evidence WHERE "
                + "return_request_id = ? AND stage = 'CUSTODY'", Long.class, request.id())).isZero();
        assertThat(withCustodyNote(request, d.lpn(0), CARTON).evidence())
                .anySatisfy(e -> assertThat(e.stage()).isEqualTo(ReturnEvidence.Stage.CUSTODY));
    }

    @Test
    @DisplayName("RAD-3: goods kept on a custody note end as the customer chose - Sales record it (a supplementary "
            + "return, then a hand-back), the warehouse manager carries it out from its list, once; the schema states "
            + "the same")
    void custodyNoteGoodsEndAsChosen() {
        Delivered d = delivered(1);
        ReturnRequest request = withCustodyNote(approved(d, CARTON), "LPN-NHAM-16OZ", 40);
        UUID note = custodyNoteOf(request);

        assertThat(code(catchThrowable(() -> asHcm(manager, () -> custodyNotes.end(request.id(), note, "BB 1",
                manager))))).as("no choice yet").isEqualTo(ErrorCode.RETURN_CUSTODY_CHOICE_REQUIRED);
        assertThat(code(catchThrowable(() -> custodyNotes.choose(request.id(), note,
                ReturnCustodyNoteChoice.DESTROY_ON_BEHALF, null, null, sales.userId()))))
                .as("a destruction rests on a written consent").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(code(catchThrowable(() -> custodyNotes.choose(request.id(), note,
                ReturnCustodyNoteChoice.TAKEN_BY_RETURN, null, request.id(), sales.userId()))))
                .as("not taken by itself").isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(asHcm(manager, () -> custodyNotes.kept(request.id(), false, 0, 20)).items()).singleElement()
                .satisfies(row -> assertThat(row.choice()).isNull());
        assertThat(asElsewhere(() -> custodyNotes.kept(request.id(), false, 0, 20)).items())
                .as("another warehouse's list").isEmpty();

        // Sales open a supplementary return for the customer; the goods are to be taken on it.
        Delivered more = delivered(1);
        ReturnRequest supplementary = asSales(() -> returns.openForCustomer(new ReturnRequestService.OpenReturnCommand(
                UUID.randomUUID(), more.orderId(), ReturnReason.DAMAGED_IN_TRANSIT, "bổ sung", ReturnResolution.REFUND,
                List.of(new ReturnRequest.Claim(more.lineId(), CARTON)), null, null), sales.userId()));
        ReturnRequest chosen = custodyNotes.choose(request.id(), note, ReturnCustodyNoteChoice.TAKEN_BY_RETURN, null,
                supplementary.id(), sales.userId());
        assertThat(chosen.custodyOutcome(note)).hasValueSatisfying(o -> {
            assertThat(o.choice()).isEqualTo(ReturnCustodyNoteChoice.TAKEN_BY_RETURN);
            assertThat(o.takenByRmaNumber()).isEqualTo(supplementary.rmaNumber());
        });
        returns.reject(supplementary.id(), "không đủ căn cứ", null, coordinator);
        assertThat(code(catchThrowable(() -> asHcm(manager, () -> custodyNotes.end(request.id(), note, "PN-1",
                manager))))).as("a rejected return took nothing").isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);

        custodyNotes.choose(request.id(), note, ReturnCustodyNoteChoice.SEND_BACK, "khách chịu phí", null,
                sales.userId());
        CustodyOutcome ended = asHcm(manager, () -> custodyNotes.end(request.id(), note, "Biên bản bàn giao 12",
                manager));
        assertThat(ended.ended()).isTrue();
        assertThat(ended.endedBy()).isEqualTo(manager);
        assertThat(asHcm(manager, () -> custodyNotes.kept(request.id(), false, 0, 20)).items()).isEmpty();
        assertThat(asHcm(manager, () -> custodyNotes.kept(request.id(), true, 0, 20)).items()).singleElement()
                .satisfies(row -> assertThat(row.endedNote()).isEqualTo("Biên bản bàn giao 12"));
        assertThat(code(catchThrowable(() -> asHcm(manager, () -> custodyNotes.end(request.id(), note, "again",
                manager))))).isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
        assertThat(code(catchThrowable(() -> custodyNotes.choose(request.id(), note,
                ReturnCustodyNoteChoice.DESTROY_ON_BEHALF, "BB", null, sales.userId()))))
                .as("ended for good").isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
        assertThat(queries.detail(request.id(), null).request().custodyOutcome(note))
                .hasValueSatisfying(o -> assertThat(o.endedNote()).isEqualTo("Biên bản bàn giao 12"));

        // The schema: final once ended; only a custody note of that return; a destruction names its consent.
        assertThat(inDatabase("UPDATE ordering.return_custody_outcome SET ended_note = 'x' WHERE evidence_id = ?",
                note)).hasMessageContaining("its outcome is final");
        UUID claimPhoto = request.evidence().stream().filter(e -> e.stage() == ReturnEvidence.Stage.CLAIM)
                .findFirst().orElseThrow().id();
        assertThat(inDatabase("""
                INSERT INTO ordering.return_custody_outcome (evidence_id, return_request_id, choice, chosen_by, chosen_at)
                VALUES (?, ?, 'SEND_BACK', ?, NOW())""", claimPhoto, request.id(), sales.userId()))
                .hasMessageContaining("is not a custody note");
        UUID second = custodyNoteOf(withCustodyNote(request, null, 5));
        assertThat(inDatabase("""
                INSERT INTO ordering.return_custody_outcome (evidence_id, return_request_id, choice, chosen_by, chosen_at)
                VALUES (?, ?, 'DESTROY_ON_BEHALF', ?, NOW())""", second, request.id(), sales.userId()))
                .hasMessageContaining("ck_return_custody_outcome_consent");
    }

    @Test
    @DisplayName("confirmation audit CF-5: a custody note is ended as taken by a supplementary return only once that "
            + "return received the carton it names - not at once, not by hand in the database; a resolved return "
            + "is not chosen, it can receive nothing")
    void custodyNoteIsTakenOnlyOnceItsGoodsCameBackOnTheSupplementaryReturn() {
        Delivered d = delivered(1);
        Delivered more = delivered(1);
        // The carton of the other order came back with this return's parcel: kept for the customer on a note.
        ReturnRequest request = withCustodyNote(approved(d, CARTON), more.lpn(0).toLowerCase(), CARTON);
        UUID note = custodyNoteOf(request);

        // A resolved return of the same customer receives no parcel any more: not chosen.
        Delivered done = delivered(1);
        ReturnRequest finished = approved(done, CARTON);
        back(finished, done.lpn(0));
        returns.proposeRefund(finished.id(), new ReturnRequestService.ProposeRefundCommand(
                Map.of(lineOf(finished, sku.sku()), CARTON), "kiểm hàng", null, null, null), sales.userId());
        ReturnRequest resolved = returns.approveSettlement(finished.id(), null, accountant);
        assertThat(resolved.status()).isEqualTo(ReturnStatus.RESOLVED);
        assertThat(code(catchThrowable(() -> custodyNotes.choose(request.id(), note,
                ReturnCustodyNoteChoice.TAKEN_BY_RETURN, null, resolved.id(), sales.userId()))))
                .isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);

        // The supplementary return for the other order's carton, approved and waiting for it.
        ReturnRequest supplementary = approved(more, CARTON);
        custodyNotes.choose(request.id(), note, ReturnCustodyNoteChoice.TAKEN_BY_RETURN, null, supplementary.id(),
                sales.userId());
        assertThat(code(catchThrowable(() -> asHcm(manager, () -> custodyNotes.end(request.id(), note,
                "PN nhận hàng", manager))))).as("the carton has not come back on it yet")
                .isEqualTo(ErrorCode.RETURN_INVALID_TRANSITION);
        assertThat(inDatabase("UPDATE ordering.return_custody_outcome SET ended_at = NOW(), ended_by = ?, "
                + "ended_note = 'by hand' WHERE evidence_id = ?", manager, note))
                .as("stated twice: check_return_custody_outcome").hasMessageContaining("have not come back on return");

        // Its receipt takes the carton: now the note ends as taken by it.
        back(supplementary, more.lpn(0));
        CustodyOutcome ended = asHcm(manager, () -> custodyNotes.end(request.id(), note, "PN nhận hàng", manager));
        assertThat(ended.ended()).isTrue();
        assertThat(ended.takenByReturnId()).isEqualTo(supplementary.id());
    }

    // ---- RAD-5 ------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("RAD-5: a carton refunded on one return, sold again and refused on a later one - the first receipt's "
            + "01-VT still receives it; only the later receipt shows it held for the customer")
    void aFirstReceiptDoesNotChangeWhenItsCartonComesBackAgain() {
        Delivered d = delivered(1);
        ReturnRequest first = approved(d, CARTON);
        String lpn = d.lpn(0);
        ReturnReceiptView firstReceipt = back(first, lpn);
        returns.proposeRefund(first.id(), new ReturnRequestService.ProposeRefundCommand(null, null, null),
                sales.userId());
        returns.approveSettlement(first.id(), null, accountant);
        assertThat(carton(lpn)).containsEntry("return_ownership", "COMPANY");
        String before = voucher(firstReceipt.id(), "r3-01vt-first-receipt-before");

        Delivered again = delivered(false, 1);
        ShippedCartons.shipAgain(jdbc, tx, lpn, again.orderId(), again.lineId(), sku.sku(), clerk);
        ReturnRequest later = approved(again, CARTON);
        ReturnReceiptView laterReceipt = back(later, lpn);
        refunded(later, Map.of(later.lines().getFirst().id(), 0));
        assertThat(carton(lpn)).containsEntry("return_ownership", "CUSTOMER_HELD")
                .containsEntry("return_request_id", later.id());

        assertThat(asHcm(clerk, () -> returnReceipts.get(firstReceipt.id())).cartons())
                .allSatisfy(c -> assertThat(c.heldForCustomer()).isFalse());
        assertThat(asHcm(clerk, () -> returnReceipts.get(laterReceipt.id())).cartons())
                .allSatisfy(c -> assertThat(c.heldForCustomer()).isTrue());
        String after = voucher(firstReceipt.id(), "r3-01vt-first-receipt-after");
        assertThat(after).as("the first receipt's voucher, printed again").isEqualTo(before)
                .doesNotContain("không được hoàn tiền");
        assertThat(voucher(laterReceipt.id(), "r3-01vt-later-receipt-refused"))
                .contains("không được hoàn tiền theo yêu cầu trả");
    }
}
