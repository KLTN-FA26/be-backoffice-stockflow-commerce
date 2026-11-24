package com.stockflow.payment;

import com.stockflow.common.document.DocumentSamples;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.SaveCreditTermsCommand;
import com.stockflow.order.internal.service.SalesContracts;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.payment.api.SettleRemainderClosureCommand;
import com.stockflow.payment.internal.domain.PaymentReceipt;
import com.stockflow.payment.internal.service.RefundService;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The three papers about money over HTTP, through the real filter chain, permission aspect and data scope: the
 * confirmation of a payment, the confirmation of a refund, the debt reconciliation. Each comes back as a PDF to the
 * accountant and to the customer it is about; another customer gets 404, a customer on the back office's path and a
 * role without the permission 403, nobody signed in 401.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.catalog.projection-retry-ms=3600000"})
class PaymentPapersHttpTest {

    /** Half on signing, due at once; half after delivery, 30 days to pay. */
    private static final List<SalesContracts.Installment> HALF_AND_HALF = List.of(
            new SalesContracts.Installment("Đặt cọc khi ký", "ON_SIGNING", null, new BigDecimal("50"), 0, null),
            new SalesContracts.Installment("Sau giao hàng", "AFTER_DELIVERY", null, new BigDecimal("50"), 30, null));

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired PaymentService payments;
    @Autowired RefundService refunds;
    @Autowired CustomerService customers;

    private SecuredMvc http;
    private ConfirmedOrders orders;
    private UUID accountant;
    private UUID clerk;
    private UUID owner;
    private UUID stranger;
    private UUID customer;
    private ConfirmedOrders.Signed signed;
    private PaymentReceipt receipt;

    @BeforeEach
    void aDepositPaid() {
        http = new SecuredMvc(mvc, decoder);
        orders = new ConfirmedOrders(context);
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        owner = tx.execute(s -> ReferenceRows.user(entityManager));
        stranger = tx.execute(s -> ReferenceRows.user(entityManager));
        customer = customerOf(owner, "Công ty Cổ phần Cà phê Mộc Lam");
        customerOf(stranger, "Công ty TNHH Trà Sữa Hoa Sữa");
        // Paying after delivery is credit: approved by accounting, ample, so signing holds nothing for it.
        customers.saveCreditTerms(new SaveCreditTermsCommand(customer, new BigDecimal("1000000000"), 30,
                "ample credit", accountant, null));
        var sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50);
        signed = orders.signed(customer, HALF_AND_HALF, ConfirmedOrders.Line.cartons(sku, 10, 40_000));
        BigDecimal deposit = jdbc.queryForObject("SELECT amount FROM payment.installment WHERE id = ?",
                BigDecimal.class, signed.seq(1));
        receipt = orders.pay(customer, signed.seq(1), deposit);
    }

    private UUID customerOf(UUID user, String name) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, tax_code, status, version, created_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', 0, NOW())""", id, user, name, "papers-" + id + "@example.com",
                "03" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999))));
        return id;
    }

    /** "10.000.000 đồng", as the papers write an amount. */
    private static String dong(BigDecimal amount) {
        return String.format("%,d", amount.longValueExact()).replace(',', '.') + " đồng";
    }

    private static String text(ResultActions answer) throws Exception {
        byte[] pdf = answer.andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
        }
    }

    @Test
    @DisplayName("a payment confirmation: the accountant's and the paying customer's, as a PDF naming the contract, "
            + "the installment and the statement reference; another customer 404, a customer on the staff path and a "
            + "role without the permission 403, anonymous 401, a receipt of another installment 404")
    void paymentConfirmation() throws Exception {
        String staffPath = "/api/v1/installments/" + signed.seq(1) + "/receipts/" + receipt.id() + "/confirmation";
        String ownPath = "/api/v1/installments/me/" + signed.seq(1) + "/receipts/" + receipt.id() + "/confirmation";

        assertThat(DocumentSamples.downloaded(http.asRole(Role.ACCOUNTANT, accountant, get(staffPath)),
                "wired-payment-confirmation", "Phiếu xác nhận thanh toán XNTT-")).contains(
                "PHIẾU XÁC NHẬN THANH TOÁN", "Công ty Cổ phần Cà phê Mộc Lam",
                "Đợt 1/2 - Đặt cọc khi ký theo Hợp đồng số " + signed.contractNumber(), receipt.reference(),
                dong(receipt.amount()));
        assertThat(text(http.asCustomer(owner, get(ownPath)))).contains("PHIẾU XÁC NHẬN THANH TOÁN",
                receipt.reference());

        http.asCustomer(stranger, get(ownPath)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("INSTALLMENT_NOT_FOUND"));
        http.asCustomer(owner, get(staffPath)).andExpect(status().isForbidden());
        http.asRole(Role.WAREHOUSE_STAFF, clerk, get(staffPath)).andExpect(status().isForbidden());
        http.anonymous(get(staffPath)).andExpect(status().isUnauthorized());
        http.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/installments/" + signed.seq(2) + "/receipts/"
                        + receipt.id() + "/confirmation")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_RECEIPT_NOT_FOUND"));
    }

    @Test
    @DisplayName("a refund confirmation: once the refund is recorded, the accountant's and the refunded customer's; "
            + "another customer 404, a customer on the staff path 403, a voided record 409")
    void refundConfirmation() throws Exception {
        // The whole order is closed undelivered: nothing more is owed, and the deposit is to refund.
        tx.execute(s -> payments.settleRemainderClosure(new SettleRemainderClosureCommand(signed.orderId(),
                Identifiers.newId(), signed.total(), null, null, null, accountant, Instant.now())));
        String reference = "FTHOAN" + Long.toString(ThreadLocalRandom.current().nextLong(1L << 40, 1L << 41), 36)
                .toUpperCase();
        refunds.record(new RefundService.RecordRefundCommand(signed.orderId(), receipt.amount(), LocalDate.now(
                ConfirmedOrders.VIETNAM), reference, null, accountant));
        UUID record = jdbc.queryForObject("SELECT id FROM payment.refund_record WHERE order_id = ?", UUID.class,
                signed.orderId());
        String staffPath = "/api/v1/refunds/records/" + record + "/confirmation";

        // D-2 (demo run 24/11): the list of refund records answered 500 on every call, its SQL glued together as
        // "payment.refund_recordWHERE". Unfiltered, and by order, customer and status.
        http.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/refunds/records")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.refundRecordId == '" + record + "')].reference").value(reference));
        http.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/refunds/records?orderId=" + signed.orderId()
                        + "&customerId=" + customer + "&status=RECORDED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].refundRecordId").value(record.toString()))
                .andExpect(jsonPath("$.data.items[0].reference").value(reference));
        http.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/refunds/records?orderId=" + signed.orderId()
                + "&status=VOIDED")).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        http.asCustomer(owner, get("/api/v1/refunds/records")).andExpect(status().isForbidden());
        String ownPath = "/api/v1/refunds/me/records/" + record + "/confirmation";

        assertThat(DocumentSamples.downloaded(http.asRole(Role.ACCOUNTANT, accountant, get(staffPath)),
                "wired-refund-confirmation", "Phiếu hoàn tiền PHT-")).contains("PHIẾU HOÀN TIỀN",
                "Công ty Cổ phần Cà phê Mộc Lam", "Hợp đồng số " + signed.contractNumber(), signed.orderNumber(),
                reference, dong(receipt.amount()), "Hoàn tiền phần hàng không giao tiếp");
        assertThat(text(http.asCustomer(owner, get(ownPath)))).contains("PHIẾU HOÀN TIỀN", reference);

        http.asCustomer(stranger, get(ownPath)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("REFUND_RECORD_NOT_FOUND"));
        http.asCustomer(owner, get(staffPath)).andExpect(status().isForbidden());
        http.anonymous(get(staffPath)).andExpect(status().isUnauthorized());

        refunds.voidRecord(record, "recorded twice", accountant);
        http.asRole(Role.ACCOUNTANT, accountant, get(staffPath)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REFUND_RECORD_ALREADY_VOIDED"));
    }

    @Test
    @DisplayName("a debt reconciliation: the deposit fell due and was paid, so the period balances to nothing owed - "
            + "the accountant's for a customer, the customer's own under /me; a customer on the staff path 403, a "
            + "period ending after today 400")
    void debtReconciliation() throws Exception {
        LocalDate today = LocalDate.now(ConfirmedOrders.VIETNAM);
        String period = "from=" + today.minusDays(30) + "&to=" + today;

        String ledger = DocumentSamples.downloaded(http.asRole(Role.ACCOUNTANT, accountant,
                get("/api/v1/installments/debt-reconciliation?customerId=" + customer + "&" + period)),
                "wired-debt-reconciliation", "Biên bản đối chiếu công nợ BBDCCN-");
        assertThat(ledger).contains("BIÊN BẢN ĐỐI CHIẾU CÔNG NỢ", "Công ty Cổ phần Cà phê Mộc Lam",
                "Căn cứ Hợp đồng mua bán hàng hóa số " + signed.contractNumber(),
                "hai bên không còn công nợ");
        assertThat(ledger.replace(" ", "")).as("a long number breaks over two lines of its cell")
                .contains(("Đến hạn thanh toán đợt 1 - Đặt cọc khi ký, đơn hàng " + signed.orderNumber())
                                .replace(" ", ""),
                        ("Thanh toán đợt 1, Hợp đồng " + signed.contractNumber()).replace(" ", ""),
                        receipt.reference());
        assertThat(text(http.asCustomer(owner, get("/api/v1/installments/me/debt-reconciliation?" + period)))
                .replace(" ", "")).contains("BIÊNBẢNĐỐICHIẾUCÔNGNỢ", receipt.reference());
        assertThat(text(http.asCustomer(stranger, get("/api/v1/installments/me/debt-reconciliation?" + period)))
                .replace(" ", "")).as("another customer reads their own ledger, never this one").doesNotContain(receipt.reference());

        http.asCustomer(owner, get("/api/v1/installments/debt-reconciliation?customerId=" + customer + "&" + period))
                .andExpect(status().isForbidden());
        http.asRole(Role.WAREHOUSE_STAFF, clerk, get("/api/v1/installments/debt-reconciliation?customerId="
                + customer + "&" + period)).andExpect(status().isForbidden());
        http.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/installments/debt-reconciliation?customerId="
                        + customer + "&from=" + today + "&to=" + today.plusDays(1)))
                .andExpect(status().isBadRequest());
    }
}
