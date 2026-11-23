package com.stockflow.order;

import com.stockflow.common.document.DocumentSamples;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.internal.service.DeliveryBatchService;
import com.stockflow.payment.internal.service.RefundService;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.WithCurrentUser;
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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two papers that follow a signed contract over HTTP, through the real filter chain, permission aspect and data
 * scope: its appendix once a delivery window was moved, and its liquidation once the order ended. Each comes back as
 * a PDF to Sales and to the customer whose order it is; another customer gets 404, a customer on the back office's
 * path and a role without the permission 403, nobody signed in 401.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.catalog.projection-retry-ms=3600000"})
class ContractPapersHttpTest {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired DeliveryBatchService batches;
    @Autowired OrderService orderService;
    @Autowired RefundService refunds;

    private SecuredMvc http;
    private ConfirmedOrders orders;
    private UUID sales;
    private UUID clerk;
    private UUID owner;
    private UUID stranger;
    private UUID customer;
    private ConfirmedOrders.Signed signed;

    @BeforeEach
    void aContractSignedInTwoBatches() {
        http = new SecuredMvc(mvc, decoder);
        orders = new ConfirmedOrders(context);
        sales = tx.execute(s -> ReferenceRows.user(entityManager));
        clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        owner = tx.execute(s -> ReferenceRows.user(entityManager));
        stranger = tx.execute(s -> ReferenceRows.user(entityManager));
        customer = customerOf(owner, "Công ty Cổ phần Cà phê Mộc Lam");
        customerOf(stranger, "Công ty TNHH Trà Sữa Hoa Sữa");
        var sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50);
        signed = orders.signedInBatches(customer, 2, ConfirmedOrders.Line.cartons(sku, 10, 40_000));
    }

    private UUID customerOf(UUID user, String name) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, user_id, full_name, email, tax_code, status, version, created_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', 0, NOW())""", id, user, name, "contract-papers-" + id + "@example.com",
                "03" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999))));
        return id;
    }

    private static String text(ResultActions answer) throws Exception {
        byte[] pdf = answer.andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
        }
    }

    @Test
    @DisplayName("an appendix: none while the contract stands as signed (404); once batch 2's window is moved, Sales' "
            + "and the customer's PDF states the window the contract gave beside the one in force; another customer "
            + "404, a customer on the staff path and a role without the permission 403, anonymous 401")
    void appendix() throws Exception {
        String staffPath = "/api/v1/orders/" + signed.orderId() + "/contract/appendix";
        String ownPath = "/api/v1/orders/me/" + signed.orderId() + "/contract/appendix";
        http.asRole(Role.SALES_STAFF, sales, get(staffPath)).andExpect(status().isNotFound());
        appendixAvailable(false);

        // Batch 2 moves a week later with the customer's consent on record (17 BR-28), written as the service writes
        // it: the move itself is DeliveryBatchReleaseIntegrationTest's to prove.
        DeliveryBatchService.BatchView second = WithCurrentUser.call(orders.sales(), DataScope.ALL,
                () -> batches.schedule(signed.orderId())).batches().get(1);
        LocalDate start = second.windowStart().plusDays(7);
        LocalDate end = second.windowEnd().plusDays(7);
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.delivery_batch_window_change (id, delivery_batch_id, order_id, old_window_start,
                    old_window_end, new_window_start, new_window_end, reason_code, note, evidence_file_key,
                    evidence_file_name, changed_by, changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'CUSTOMER_REQUEST', 'the shop opens a week later', 'consent/key',
                        'consent.pdf', ?, NOW())""", Identifiers.newId(), second.batchId(), signed.orderId(),
                second.windowStart(), second.windowEnd(), start, end, sales));

        String appendix = DocumentSamples.downloaded(http.asRole(Role.SALES_STAFF, sales, get(staffPath)),
                "wired-contract-appendix", "Phụ lục hợp đồng PL01-" + signed.contractNumber() + ".pdf");
        assertThat(appendix).contains("PHỤ LỤC HỢP ĐỒNG SỐ 01", "Số: PL01-" + signed.contractNumber(),
                "Căn cứ Hợp đồng mua bán hàng hóa số " + signed.contractNumber(),
                "Công ty Cổ phần Cà phê Mộc Lam", "Bên B đề nghị thay đổi thời gian nhận hàng",
                "Thời gian giao hàng đợt 2",
                "Từ ngày " + DAY.format(second.windowStart()) + " đến ngày " + DAY.format(second.windowEnd()),
                "Từ ngày " + DAY.format(start) + " đến ngày " + DAY.format(end), "ĐẠI DIỆN BÊN A", "ĐẠI DIỆN BÊN B");
        assertThat(appendix).as("batch 1 was not moved").doesNotContain("Thời gian giao hàng đợt 1");
        appendixAvailable(true);
        assertThat(text(http.asCustomer(owner, get(ownPath)))).contains("PHỤ LỤC HỢP ĐỒNG SỐ 01",
                "Thời gian giao hàng đợt 2");

        http.asCustomer(stranger, get(ownPath)).andExpect(status().isNotFound());
        http.asCustomer(owner, get(staffPath)).andExpect(status().isForbidden());
        http.asRole(Role.WAREHOUSE_STAFF, clerk, get(staffPath)).andExpect(status().isForbidden());
        http.anonymous(get(staffPath)).andExpect(status().isUnauthorized());

        // Moved back to the window signed: nothing differs, so no appendix and no flag.
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.delivery_batch_window_change (id, delivery_batch_id, order_id, old_window_start,
                    old_window_end, new_window_start, new_window_end, reason_code, note, evidence_file_key,
                    evidence_file_name, changed_by, changed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'CUSTOMER_REQUEST', 'back as agreed', 'consent/key2',
                        'consent2.pdf', ?, NOW() + INTERVAL '1 second')""", Identifiers.newId(), second.batchId(),
                signed.orderId(), start, end, second.windowStart(), second.windowEnd(), sales));
        http.asRole(Role.SALES_STAFF, sales, get(staffPath)).andExpect(status().isNotFound());
        appendixAvailable(false);
    }

    /**
     * {@code appendixAvailable} on the contract Sales and the customer read, which decides whether their screens show
     * the appendix button: it must say exactly what the appendix endpoint answers.
     */
    private void appendixAvailable(boolean expected) throws Exception {
        http.asRole(Role.SALES_STAFF, sales, get("/api/v1/orders/" + signed.orderId() + "/contract"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.appendixAvailable").value(expected));
        http.asCustomer(owner, get("/api/v1/orders/me/" + signed.orderId() + "/contract"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.appendixAvailable").value(expected));
    }

    @Test
    @DisplayName("a liquidation: refused while the contract is being performed (409); once the paid order is "
            + "cancelled, still refused while its refund is owed (409, owner 2026-11-23: nothing may be left); once "
            + "paid back, Sales' and the customer's PDF says why the contract ends, that nothing was delivered, the "
            + "contract's value as signed, a value performed of 0, the payment, the refund and 0 left; another "
            + "customer 404, a customer on the staff path 403")
    void liquidation() throws Exception {
        String staffPath = "/api/v1/orders/" + signed.orderId() + "/contract/liquidation";
        String ownPath = "/api/v1/orders/me/" + signed.orderId() + "/contract/liquidation";
        ConfirmedOrders.Confirmed paid = orders.pay(signed);
        http.asRole(Role.SALES_STAFF, sales, get(staffPath)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SALES_CONTRACT_STATUS_INVALID"))
                .andExpect(jsonPath("$.details.reason").value("STILL_PERFORMED"));

        WithCurrentUser.run(orders.sales(), DataScope.ALL, () -> orderService.cancel(signed.orderId(),
                new CancelOrderCommand(CancellationReasonCode.CUSTOMER_REQUEST, "agreed with the customer", null,
                        orders.sales().userId(), null)));
        http.asRole(Role.SALES_STAFF, sales, get(staffPath)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SALES_CONTRACT_STATUS_INVALID"))
                .andExpect(jsonPath("$.details.reason").value("NOT_SETTLED"))
                .andExpect(jsonPath("$.details.outstanding").value(0))
                .andExpect(jsonPath("$.details.toRefund").value(signed.total().doubleValue()));
        http.asCustomer(owner, get(ownPath)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.details.reason").value("NOT_SETTLED"));

        refunds.record(new RefundService.RecordRefundCommand(signed.orderId(), signed.total(), LocalDate.now(),
                "FT-REFUND-" + signed.orderId().toString().substring(0, 8), null, sales));
        String reference = jdbc.queryForObject("SELECT reference FROM payment.payment_receipt WHERE id = ?",
                String.class, paid.receiptId());
        String total = String.format("%,d", signed.total().longValueExact()).replace(',', '.');

        String record = DocumentSamples.downloaded(http.asRole(Role.SALES_STAFF, sales, get(staffPath)),
                "wired-contract-liquidation", "Biên bản thanh lý hợp đồng TL-" + signed.contractNumber() + ".pdf");
        assertThat(record).contains("BIÊN BẢN THANH LÝ HỢP ĐỒNG MUA BÁN HÀNG HÓA",
                "Số: TL-" + signed.contractNumber(), "Công ty Cổ phần Cà phê Mộc Lam",
                "Do đơn đặt hàng số " + signed.orderNumber() + " theo Hợp đồng đã được hủy",
                "Bên A chưa giao hàng hóa nào cho Bên B theo Hợp đồng.", reference,
                "không còn nghĩa vụ nào khác đối với nhau")
                .containsSubsequence("Giá trị Hợp đồng", total, "Giá trị thực hiện", "0", "Đã thanh toán", total,
                        "Đã hoàn trả", total, "Còn lại", "0")
                .doesNotContain("còn phải");
        assertThat(text(http.asCustomer(owner, get(ownPath)))).contains("BIÊN BẢN THANH LÝ HỢP ĐỒNG", reference);

        http.asCustomer(stranger, get(ownPath)).andExpect(status().isNotFound());
        http.asCustomer(owner, get(staffPath)).andExpect(status().isForbidden());
        http.asRole(Role.WAREHOUSE_STAFF, clerk, get(staffPath)).andExpect(status().isForbidden());
        http.anonymous(get(staffPath)).andExpect(status().isUnauthorized());
    }
}
