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
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderLine;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ReturnEvidence;
import com.stockflow.order.internal.domain.ReturnReason;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnResolution;
import com.stockflow.order.internal.domain.ReturnStatus;
import com.stockflow.order.internal.service.ReturnRequestService;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.domain.ReturnCartonEligibility;
import com.stockflow.procurement.internal.service.ReturnReceiptService;
import com.stockflow.procurement.internal.service.ReturnReceiptService.CandidateCarton;
import com.stockflow.procurement.internal.service.ReturnReceiptService.ReturnProgress;
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
import java.math.BigDecimal;
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
 * The return receipt screen's read model against the real database (WF-05 receiving progress): a return request is
 * received by the units of each SKU it approved, not by cartons, and every carton of its source is offered or excluded
 * by the very rule the scan refuses by ({@link ReturnCartonEligibility}). Each scenario also checks the scan still
 * decides on its own - the candidates are advice, never a way round the cap.
 *
 * <p>An order of two of the customer's cups: A, five cartons of 20, and B, two cartons of 10, each delivered on a
 * shipment of its own. The returns claim A only, unless a test says otherwise.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class ReturnReceivingProgressIntegrationTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final AtomicLong ORDER_NUMBERS = new AtomicLong(820_000 + ThreadLocalRandom.current().nextInt(15_000));
    private static final String QUARANTINE = "HCM-QC01";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D',
            'R', 0, 0, 0, 1, 0, 0, 0, 1};
    private static final int A_CARTON = 20;
    private static final int B_CARTON = 10;

    @Autowired OrderRepository orderRepository;
    @Autowired ReturnRequestService returns;
    @Autowired ReturnReceiptService returnReceipts;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean JavaMailSenderImpl mail;

    private CurrentUser sales;
    private UUID coordinator;
    private UUID clerk;
    private UUID manager;
    private UUID customerId;
    private CustomerSkuRows.Issued skuA;
    private CustomerSkuRows.Issued skuB;

    private record Delivered(UUID orderId, UUID lineA, UUID lineB, ShippedCartons.Shipped a,
                             ShippedCartons.Shipped b) {

        String a(int i) {
            return a.lpns().get(i);
        }
    }

    @BeforeEach
    void setUp() {
        UUID salesUser = tx.execute(s -> ReferenceRows.user(entityManager));
        sales = new CurrentUser(salesUser, "sales-" + salesUser, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        manager = tx.execute(s -> ReferenceRows.user(entityManager));
        customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                VALUES (?, 'Cong ty Tien Do Tra Hang', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                "progress-" + customerId + "@example.com", "08" + (10_000_000 + ThreadLocalRandom.current()
                        .nextInt(89_999_999))));
        skuA = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, A_CARTON, "STANDARD", true);
        skuB = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, B_CARTON, "STANDARD", true);
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

    /** Five cartons of A and two of B delivered, on a shipment each. */
    private Delivered delivered() {
        LocalDate today = LocalDate.now(VIETNAM);
        UUID orderId = tx.execute(s -> {
            OrderLine a = Order.line(Identifiers.newId(), new Sku(skuA.sku()), 5 * A_CARTON, Money.vnd(2_000), null)
                    .withCartons(skuA.uomConversionId(), 5, A_CARTON).withTax(BigDecimal.TEN);
            OrderLine b = Order.line(Identifiers.newId(), new Sku(skuB.sku()), 2 * B_CARTON, Money.vnd(3_000), null)
                    .withCartons(skuB.uomConversionId(), 2, B_CARTON).withTax(BigDecimal.TEN);
            return orderRepository.save(Order.draft(OrderNumber.of(today, ORDER_NUMBERS.incrementAndGet()),
                    customerId, Identifiers.newId(), List.of(a, b), Instant.now())).id().value();
        });
        UUID lineA = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ? AND sku = ?",
                UUID.class, orderId, skuA.sku());
        UUID lineB = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ? AND sku = ?",
                UUID.class, orderId, skuB.sku());
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    UPDATE ordering.customer_order SET status = 'DELIVERED', warehouse_id = ?,
                           shipped_at = NOW() - interval '2 days', delivered_at = NOW() - interval '1 day' WHERE id = ?""",
                    DemoData.WAREHOUSE_HCM, orderId);
        });
        String lot = "LOT-PG-" + orderId.toString().substring(0, 8);
        ShippedCartons.Shipped a = ShippedCartons.ship(jdbc, tx, orderId, lineA, skuA.sku(), 5, A_CARTON, lot + "A",
                "DELIVERED", clerk);
        ShippedCartons.Shipped b = ShippedCartons.ship(jdbc, tx, orderId, lineB, skuB.sku(), 2, B_CARTON, lot + "B",
                "DELIVERED", clerk);
        return new Delivered(orderId, lineA, lineB, a, b);
    }

    private static FileUpload png() {
        return new FileUpload("anh.png", "image/png", PNG.length, new ByteArrayInputStream(PNG));
    }

    /** Asks {@code asked} units of A back; Sales approve {@code approved} of them. */
    private ReturnRequest approvedA(Delivered d, int asked, int approved) {
        ReturnRequest request = asSales(() -> returns.openForCustomer(new ReturnRequestService.OpenReturnCommand(
                UUID.randomUUID(), d.orderId(), ReturnReason.DAMAGED_IN_TRANSIT, "móp thùng", ReturnResolution.REFUND,
                List.of(new ReturnRequest.Claim(d.lineA(), asked)), null, null), sales.userId()));
        returns.addEvidence(request.id(), null, ReturnEvidence.Stage.CLAIM, png(), sales.userId());
        return returns.approve(request.id(), new ReturnRequestService.ApproveReturnCommand(
                Map.of(request.lines().getFirst().id(), approved), null, "ok", null), coordinator);
    }

    private ReturnReceiptView draft(ReturnRequest request) {
        return asHcm(clerk, () -> returnReceipts.create(new ReturnReceiptService.Create(null, request.id(), QUARANTINE,
                null), clerk));
    }

    private ReturnReceiptView scan(UUID receipt, String lpn, Integer counted) {
        return asHcm(clerk, () -> returnReceipts.scan(receipt, lpn, null, counted, clerk));
    }

    private ReturnReceiptView view(UUID receipt) {
        return asHcm(clerk, () -> returnReceipts.get(receipt));
    }

    private UUID photo(UUID receipt) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO procurement.goods_receipt_photos (id, receipt_id, stage, storage_key, original_name,
                    content_type, size_bytes, checksum, taken_by, version, created_at)
                VALUES (?, ?, 'RECEIPT', ?, 'le.jpg', 'image/jpeg', 10, ?, ?, 0, NOW())""", id, receipt,
                "qc-evidence/" + id, "a".repeat(64), clerk));
        return id;
    }

    private ReturnStatus statusOf(ReturnRequest request) {
        return ReturnStatus.valueOf(jdbc.queryForObject("SELECT status FROM ordering.return_request WHERE id = ?",
                String.class, request.id()));
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private ReturnProgress progressOfA(ReturnReceiptView view) {
        return view.returnProgress().stream().filter(p -> p.sku().equals(skuA.sku())).findFirst().orElseThrow();
    }

    private static ReturnCartonEligibility eligibilityOf(ReturnReceiptView view, String lpn) {
        return view.candidateCartons().stream().filter(c -> c.lpn().equals(lpn)).findFirst()
                .map(CandidateCarton::eligibility).orElseThrow();
    }

    // ---- scenarios --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("one SKU approved of a two-SKU order, 20 units = one carton of five: B is never a candidate; every A "
            + "carton is a genuine alternative until one is scanned, which meets the target; the same LPN again counts "
            + "once; removing it gives the allowance back")
    void unitsNotCartonsAreTheTarget() {
        Delivered d = delivered();
        ReturnRequest request = approvedA(d, 20, 20);
        ReturnReceiptView open = draft(request);

        assertThat(open.returnProgress()).extracting(ReturnProgress::sku).containsExactly(skuA.sku());
        assertThat(progressOfA(open)).isEqualTo(new ReturnProgress(skuA.sku(), 20, 0, 0, 20));
        assertThat(open.candidateCartons()).hasSize(7);
        assertThat(d.b().lpns()).allSatisfy(lpn ->
                assertThat(eligibilityOf(open, lpn)).isEqualTo(ReturnCartonEligibility.SKU_NOT_APPROVED));
        assertThat(d.a().lpns()).allSatisfy(lpn ->
                assertThat(eligibilityOf(open, lpn)).isEqualTo(ReturnCartonEligibility.RECEIVABLE));
        assertThat(open.candidateCartons()).filteredOn(c -> c.eligibility().candidate())
                .allSatisfy(c -> assertThat(c.maxReceivableQuantity()).isEqualTo(A_CARTON));
        // Advisory only: the scan refuses B by itself.
        assertThat(code(catchThrowable(() -> scan(open.id(), d.b().lpns().getFirst(), null))))
                .isEqualTo(ErrorCode.RETURN_CARTON_NOT_CLAIMED);

        // The customer sent back the third carton, not the first: any of them is the one.
        ReturnReceiptView one = scan(open.id(), d.a(2), null);
        assertThat(progressOfA(one)).isEqualTo(new ReturnProgress(skuA.sku(), 20, 0, 20, 0));
        assertThat(eligibilityOf(one, d.a(2))).isEqualTo(ReturnCartonEligibility.ON_THIS_RECEIPT);
        assertThat(List.of(d.a(0), d.a(1), d.a(3), d.a(4))).allSatisfy(lpn ->
                assertThat(eligibilityOf(one, lpn)).isEqualTo(ReturnCartonEligibility.APPROVED_QUANTITY_REACHED));
        assertThat(code(catchThrowable(() -> scan(open.id(), d.a(0), null))))
                .isEqualTo(ErrorCode.RETURN_CARTON_NOT_CLAIMED);

        ReturnReceiptView again = scan(open.id(), d.a(2).toLowerCase(), null);
        assertThat(again.cartons()).hasSize(1);
        assertThat(progressOfA(again).currentReceiptQuantity()).isEqualTo(20);

        ReturnReceiptView removed = asHcm(clerk, () -> returnReceipts.removeCarton(open.id(), d.a(2)));
        assertThat(progressOfA(removed)).isEqualTo(new ReturnProgress(skuA.sku(), 20, 0, 0, 20));
        assertThat(d.a().lpns()).allSatisfy(lpn ->
                assertThat(eligibilityOf(removed, lpn)).isEqualTo(ReturnCartonEligibility.RECEIVABLE));
    }

    @Test
    @DisplayName("15 approved, cartons of 20: each is offered as needing a count, max 15 - not dropped for its label; "
            + "sealed it is refused; opened at its real count it is taken and needs the manager; a recount gives the "
            + "rest back; loose units count toward the SKU too")
    void aCartonAboveWhatRemainsNeedsACount() {
        Delivered d = delivered();
        ReturnRequest request = approvedA(d, 20, 15);
        ReturnReceiptView open = draft(request);
        assertThat(open.candidateCartons()).filteredOn(c -> c.sku().equals(skuA.sku()))
                .allSatisfy(c -> {
                    assertThat(c.eligibility()).isEqualTo(ReturnCartonEligibility.COUNT_REQUIRED);
                    assertThat(c.maxReceivableQuantity()).isEqualTo(15);
                    assertThat(c.quantity()).isEqualTo(A_CARTON);
                });

        assertThat(code(catchThrowable(() -> scan(open.id(), d.a(0), null))))
                .as("sealed, its label does not fit").isEqualTo(ErrorCode.RETURN_CARTON_NOT_CLAIMED);
        ReturnReceiptView counted = scan(open.id(), d.a(0), 15);
        assertThat(counted.managerConfirmationRequired()).isTrue();
        assertThat(progressOfA(counted)).isEqualTo(new ReturnProgress(skuA.sku(), 15, 0, 15, 0));

        ReturnReceiptView recounted = scan(open.id(), d.a(0), 10);
        assertThat(progressOfA(recounted)).isEqualTo(new ReturnProgress(skuA.sku(), 15, 0, 10, 5));
        assertThat(recounted.candidateCartons()).filteredOn(c -> c.lpn().equals(d.a(0))).singleElement()
                .satisfies(c -> assertThat(c.maxReceivableQuantity()).as("a recount up to what fits").isEqualTo(15));
        assertThat(eligibilityOf(recounted, d.a(1))).isEqualTo(ReturnCartonEligibility.COUNT_REQUIRED);

        UUID photo = photo(open.id());
        ReturnReceiptView loose = asHcm(clerk, () -> returnReceipts.recordLoose(open.id(), skuA.sku(), null, 3, photo,
                clerk));
        assertThat(progressOfA(loose)).isEqualTo(new ReturnProgress(skuA.sku(), 15, 0, 13, 2));
        assertThat(code(catchThrowable(() -> asHcm(clerk, () -> returnReceipts.recordLoose(open.id(), skuA.sku(),
                "LOT-X", 3, photo, clerk))))).isEqualTo(ErrorCode.RETURN_LOOSE_EXCEEDS_APPROVED);

        // The manager who counted nothing confirms; the return is whole only once 15 are back - 13 here.
        ReturnReceiptView closed = asHcm(manager, () -> returnReceipts.confirm(open.id(), manager, true));
        assertThat(closed.status()).isEqualTo(GoodsReceiptStatus.CLOSED);
        assertThat(closed.candidateCartons()).as("nothing to offer on a closed receipt").isEmpty();
        assertThat(progressOfA(closed)).isEqualTo(new ReturnProgress(skuA.sku(), 15, 0, 13, 2));
        assertThat(statusOf(request)).isEqualTo(ReturnStatus.AWAITING_RETURN);
    }

    @Test
    @DisplayName("goods back over two receipts: the first's units are confirmed on the second, its carton is back and "
            + "no longer a candidate, not missing either; the second completes the return, which moves on")
    void severalReceiptsAddUp() {
        Delivered d = delivered();
        ReturnRequest request = approvedA(d, 40, 40);
        ReturnReceiptView first = draft(request);
        scan(first.id(), d.a(0), null);
        asHcm(clerk, () -> returnReceipts.confirm(first.id(), clerk, false));
        assertThat(statusOf(request)).as("partial: still awaiting").isEqualTo(ReturnStatus.AWAITING_RETURN);

        ReturnReceiptView second = draft(request);
        assertThat(progressOfA(second)).isEqualTo(new ReturnProgress(skuA.sku(), 40, 20, 0, 20));
        assertThat(eligibilityOf(second, d.a(0))).isEqualTo(ReturnCartonEligibility.IN_WAREHOUSE);
        assertThat(second.expectedCartons()).filteredOn(c -> c.lpn().equals(d.a(0))).singleElement()
                .satisfies(c -> {
                    assertThat(c.receivedEarlier()).isTrue();
                    assertThat(c.scanned()).isFalse();
                });
        assertThat(code(catchThrowable(() -> scan(second.id(), d.a(0), null))))
                .isEqualTo(ErrorCode.RETURN_CARTON_IN_WAREHOUSE);

        scan(second.id(), d.a(4), null);
        ReturnReceiptView closed = asHcm(clerk, () -> returnReceipts.confirm(second.id(), clerk, false));
        assertThat(progressOfA(closed)).isEqualTo(new ReturnProgress(skuA.sku(), 40, 20, 20, 0));
        assertThat(statusOf(request)).isEqualTo(ReturnStatus.RECEIVED);

        // The first receipt, viewed afterwards, still lists exactly what came back on it.
        ReturnReceiptView history = view(first.id());
        assertThat(history.cartons()).extracting(ReturnReceiptService.CartonView::lpn).containsExactly(d.a(0));
        assertThat(progressOfA(history)).isEqualTo(new ReturnProgress(skuA.sku(), 40, 20, 20, 0));
    }

    @Test
    @DisplayName("a cancelled draft no longer takes any of the approval: its cartons are offered again on the next")
    void aCancelledDraftGivesTheAllowanceBack() {
        Delivered d = delivered();
        ReturnRequest request = approvedA(d, 20, 20);
        ReturnReceiptView open = draft(request);
        scan(open.id(), d.a(1), null);
        ReturnReceiptView cancelled = asHcm(clerk, () -> returnReceipts.cancel(open.id()));
        assertThat(cancelled.status()).isEqualTo(GoodsReceiptStatus.CANCELLED);
        assertThat(progressOfA(cancelled)).isEqualTo(new ReturnProgress(skuA.sku(), 20, 0, 0, 20));
        assertThat(cancelled.cartons()).as("what was counted stays on the cancelled document").hasSize(1);

        ReturnReceiptView next = draft(request);
        assertThat(progressOfA(next)).isEqualTo(new ReturnProgress(skuA.sku(), 20, 0, 0, 20));
        assertThat(eligibilityOf(next, d.a(1))).isEqualTo(ReturnCartonEligibility.RECEIVABLE);
        assertThat(scan(next.id(), d.a(1), null).cartons()).hasSize(1);
    }

    @Test
    @DisplayName("a carton that came back opened is never offered again, and its count is the return's")
    void anOpenedCartonIsNotOfferedAgain() {
        Delivered d = delivered();
        ReturnRequest request = approvedA(d, 40, 40);
        ReturnReceiptView first = draft(request);
        scan(first.id(), d.a(0), 5);
        asHcm(manager, () -> returnReceipts.confirm(first.id(), manager, true));

        ReturnReceiptView second = draft(request);
        assertThat(eligibilityOf(second, d.a(0))).isEqualTo(ReturnCartonEligibility.RETURNED_OPENED);
        assertThat(progressOfA(second)).isEqualTo(new ReturnProgress(skuA.sku(), 40, 5, 0, 35));
        assertThat(eligibilityOf(second, d.a(1))).isEqualTo(ReturnCartonEligibility.RECEIVABLE);
    }

    @Test
    @DisplayName("a carrier's return (RTO) keeps its carton reconciliation and has no SKU progress or candidates; a "
            + "carton of an earlier trip is not reported missing on the next")
    void aCarriersReturnIsUntouched() {
        UUID orderId = delivered().orderId();
        UUID line = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ? AND sku = ?",
                UUID.class, orderId, skuA.sku());
        ShippedCartons.Shipped rto = ShippedCartons.ship(jdbc, tx, orderId, line, skuA.sku(), 2, A_CARTON,
                "LOT-RTO-" + orderId.toString().substring(0, 8), "RETURNED", clerk);
        ReturnReceiptView first = asHcm(clerk, () -> returnReceipts.create(new ReturnReceiptService.Create(
                rto.shipmentId(), null, QUARANTINE, null), clerk));
        assertThat(first.returnProgress()).isEmpty();
        assertThat(first.candidateCartons()).isEmpty();
        scan(first.id(), rto.lpns().getFirst(), null);
        asHcm(clerk, () -> returnReceipts.confirm(first.id(), clerk, false));

        ReturnReceiptView second = asHcm(clerk, () -> returnReceipts.create(new ReturnReceiptService.Create(
                rto.shipmentId(), null, QUARANTINE, null), clerk));
        assertThat(second.expectedCartons()).hasSize(2);
        assertThat(second.expectedCartons()).filteredOn(c -> c.lpn().equals(rto.lpns().getFirst())).singleElement()
                .satisfies(c -> assertThat(c.receivedEarlier()).isTrue());
        assertThat(second.expectedCartons()).filteredOn(c -> c.lpn().equals(rto.lpns().get(1))).singleElement()
                .satisfies(c -> assertThat(c.receivedEarlier()).isFalse());
    }
}
