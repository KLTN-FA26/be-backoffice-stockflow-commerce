package com.stockflow.procurement;

import com.stockflow.common.document.DocumentSamples;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.procurement.internal.service.ReturnReceiptService;
import com.stockflow.procurement.internal.service.ReturnReceiptService.ReturnReceiptView;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.ShippedCartons;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The stock-in voucher of a return receipt (Mẫu số 01 - VT) over HTTP, through the real filter chain, permission
 * aspect and warehouse scope: a PDF for the warehouse's own staff once the receipt is confirmed; not before (409), not
 * for staff of no warehouse or a customer (403), not without signing in (401). The customer who asked for a return
 * reads the vouchers of what came back of it under {@code /returns/me}; another customer finds no such return (404).
 */
@SecuredIntegrationTest
class ReturnReceiptVoucherHttpTest {

    private static final int PER_CARTON = 1000;
    private static final String QUARANTINE = "HCM-QC01";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ReturnReceiptService receipts;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private SecuredMvc http;
    private UUID staff;
    private UUID staffElsewhere;
    private UUID accountant;
    private UUID portalUser;
    private UUID strangerUser;
    private UUID customer;
    private String sku;
    private ShippedCartons.Shipped shipped;
    private ReturnReceiptView draft;

    @BeforeEach
    void aShipmentBroughtBack() {
        http = new SecuredMvc(mockMvc, decoder);
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        staffElsewhere = tx.execute(s -> ReferenceRows.user(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        portalUser = tx.execute(s -> ReferenceRows.user(entityManager));
        strangerUser = tx.execute(s -> ReferenceRows.user(entityManager));
        // A clerk acts on the receipts of the warehouses they are assigned to (BR-SEC-002).
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                VALUES (gen_random_uuid(), ?, ?, 0, NOW())""", staff, DemoData.WAREHOUSE_HCM));
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        UUID stranger = tx.execute(s -> ReferenceRows.customer(entityManager));
        // The portal accounts of the order's customer and of another one.
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE customer.customer SET user_id = ? WHERE id = ?", portalUser, customer);
            jdbc.update("UPDATE customer.customer SET user_id = ? WHERE id = ?", strangerUser, stranger);
        });
        sku = CustomerSkuRows.like(jdbc, tx, entityManager, customer, DemoData.SKU_STOCKED, PER_CARTON).sku();
        UUID order = tx.execute(s -> ReferenceRows.orderWithLine(entityManager, sku, "DELIVERED", customer, staff,
                DemoData.WAREHOUSE_HCM));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET warehouse_id = ? WHERE id = ?",
                DemoData.WAREHOUSE_HCM, order));
        UUID line = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, order);
        shipped = ShippedCartons.ship(jdbc, tx, order, line, sku, 2, PER_CARTON,
                "LOT-RTO-" + order.toString().substring(0, 8), "RETURNED", staff);
        draft = asHcmStaff(() -> receipts.create(new ReturnReceiptService.Create(shipped.shipmentId(), null,
                QUARANTINE, "back from the carrier"), staff));
        shipped.lpns().forEach(lpn -> asHcmStaff(() -> receipts.scan(draft.id(), lpn, null, staff)));
    }

    private <T> T asHcmStaff(Supplier<T> call) {
        CurrentUser clerk = new CurrentUser(staff, "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(),
                DataScope.WAREHOUSE, Set.of("HCM"), Set.of(DemoData.WAREHOUSE_HCM));
        return WithCurrentUser.call(clerk, DataScope.WAREHOUSE, call);
    }

    @Test
    @DisplayName("a confirmed return receipt prints its 01-VT voucher for its warehouse's staff: the form's number, "
            + "the receipt's number, where the goods went in, a line per SKU with its cartons and lot; a draft 409, "
            + "another warehouse's staff and a customer 403, anonymous 401, no such receipt 404")
    void voucher() throws Exception {
        String path = "/api/v1/goods-receipts/returns/" + draft.id() + "/voucher";
        http.asRole(Role.WAREHOUSE_STAFF, staff, get(path)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_RECEIPT_TRANSITION"));

        asHcmStaff(() -> receipts.confirm(draft.id(), staff));
        String lot = jdbc.queryForObject("SELECT lot_number FROM inventory.stock_item WHERE stock_package_id = ?",
                String.class, shipped.cartons().getFirst().id());
        MockHttpServletResponse answer = http.asRole(Role.WAREHOUSE_STAFF, staff, get(path))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse();
        assertThat(answer.getHeader(HttpHeaders.CONTENT_DISPOSITION)).as("saved under its Vietnamese name")
                .contains("filename*=UTF-8''" + UriUtils.encode("Phiếu nhập kho " + draft.number() + ".pdf",
                        StandardCharsets.UTF_8));
        byte[] pdf = DocumentSamples.keep("wired-return-goods-receipt-rto", answer.getContentAsByteArray());
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
            assertThat(text).contains("Mẫu số: 01 - VT", "PHIẾU NHẬP KHO", "Số: " + draft.number(),
                    "Phiếu giao hàng số " + draft.shipmentCode(), "Nhập tại kho:", "của Test customer", "2 thùng",
                    "Lô: " + lot, "2.000", "Đơn đặt hàng số " + draft.orderNumber(), "Thủ kho");
            assertThat(text.replace(" ", "")).as("the SKU, whole or broken over two lines of its cell").contains(sku);
        }

        http.asRole(Role.WAREHOUSE_STAFF, staffElsewhere, get(path)).andExpect(status().isForbidden());
        http.asCustomer(portalUser, get(path)).andExpect(status().isForbidden());
        http.anonymous(get(path)).andExpect(status().isUnauthorized());
        http.asRole(Role.WAREHOUSE_STAFF, staff, get("/api/v1/goods-receipts/returns/" + UUID.randomUUID()
                        + "/voucher")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("GOODS_RECEIPT_NOT_FOUND"));
    }

    @Test
    @DisplayName("WF-05 Q-18: \"actually received\" counts only the cartons that went into stock - a carton opened on "
            + "its way back is counted on the receipt, destroyed and named in the note, never received")
    void anOpenedCartonIsNotReceivedIntoStock() throws Exception {
        UUID manager = tx.execute(s -> ReferenceRows.user(entityManager));
        String opened = shipped.lpns().getLast();
        asHcmStaff(() -> receipts.removeCarton(draft.id(), opened));
        asHcmStaff(() -> receipts.scan(draft.id(), opened, "torn open", 400, staff));
        asHcmStaff(() -> receipts.confirm(draft.id(), manager, true));

        byte[] pdf = http.asRole(Role.WAREHOUSE_STAFF, staff,
                        get("/api/v1/goods-receipts/returns/" + draft.id() + "/voucher"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        String text = DocumentSamples.text(DocumentSamples.keep("wired-return-goods-receipt-opened", pdf));
        assertThat(text).as("by the labels 2,000; into stock the sealed carton's 1,000")
                .contains("2.000", "1.000", "1 thùng bị mở hoặc rỗng khi hoàn về: đếm được 400, hủy, không nhập kho");
    }

    @Test
    @DisplayName("owner 2026-11-23: the Accountant - who signs the 01-VT and books returned goods from it - views and "
            + "prints the voucher and reads the receipt, assigned to no warehouse; every write to a receipt is 403")
    void theAccountantPrintsTheVoucherButWritesNoReceipt() throws Exception {
        String receipt = "/api/v1/goods-receipts/returns/" + draft.id();
        ReturnReceiptView confirmed = asHcmStaff(() -> receipts.confirm(draft.id(), staff));

        MockHttpServletResponse answer = http.asRole(Role.ACCOUNTANT, accountant, get(receipt + "/voucher"))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse();
        assertThat(DocumentSamples.text(answer.getContentAsByteArray())).contains("PHIẾU NHẬP KHO",
                "Số: " + draft.number());
        http.asRole(Role.ACCOUNTANT, accountant, get(receipt)).andExpect(status().isOk());
        http.asRole(Role.ACCOUNTANT, accountant, get("/api/v1/goods-receipts/returns")).andExpect(status().isOk());

        String lpn = shipped.lpns().getFirst();
        http.asRole(Role.ACCOUNTANT, accountant, post("/api/v1/goods-receipts/returns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shipmentId\":\"" + shipped.shipmentId() + "\",\"quarantineLocationCode\":\""
                                + QUARANTINE + "\"}"))
                .andExpect(status().isForbidden());
        http.asRole(Role.ACCOUNTANT, accountant, post(receipt + "/cartons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lpn\":\"" + lpn + "\"}")).andExpect(status().isForbidden());
        http.asRole(Role.ACCOUNTANT, accountant, delete(receipt + "/cartons/" + lpn)).andExpect(status().isForbidden());
        http.asRole(Role.ACCOUNTANT, accountant, delete(receipt + "/loose-lines/" + UUID.randomUUID()))
                .andExpect(status().isForbidden());
        http.asRole(Role.ACCOUNTANT, accountant, post(receipt + "/confirmation")).andExpect(status().isForbidden());
        http.asRole(Role.ACCOUNTANT, accountant, post(receipt + "/cancellation")).andExpect(status().isForbidden());
        assertThat(asHcmStaff(() -> receipts.get(draft.id())).status()).as("untouched")
                .isEqualTo(confirmed.status());
    }

    @Test
    @DisplayName("the customer who asked for a return reads the voucher of what came back of it once the warehouse "
            + "confirmed it - not before (404); another customer, and a return that does not exist, 404; the staff "
            + "path stays closed to the customer (403), anonymous 401")
    void aCustomerReadsTheVoucherOfItsOwnReturn() throws Exception {
        UUID order = tx.execute(s -> ReferenceRows.orderWithLine(entityManager, sku, "DELIVERED", customer, staff,
                DemoData.WAREHOUSE_HCM));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE ordering.customer_order SET warehouse_id = ? WHERE id = ?",
                DemoData.WAREHOUSE_HCM, order));
        UUID line = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, order);
        ShippedCartons.Shipped delivered = ShippedCartons.ship(jdbc, tx, order, line, sku, 2, PER_CARTON,
                "LOT-RMA-" + order.toString().substring(0, 8), "DELIVERED", staff);
        String rma = "RMA-VT-" + order.toString().substring(24);
        UUID returnId = awaitingReturn(order, rma, 2 * PER_CARTON);
        String path = "/api/v1/returns/me/" + returnId + "/receipt-voucher";

        http.asCustomer(portalUser, get(path)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("GOODS_RECEIPT_NOT_FOUND"));
        ReturnReceiptView parcel = asHcmStaff(() -> receipts.create(new ReturnReceiptService.Create(null, returnId,
                QUARANTINE, null), staff));
        delivered.lpns().forEach(lpn -> asHcmStaff(() -> receipts.scan(parcel.id(), lpn, null, staff)));
        asHcmStaff(() -> receipts.confirm(parcel.id(), staff));

        MockHttpServletResponse answer = http.asCustomer(portalUser, get(path)).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF)).andReturn().getResponse();
        assertThat(answer.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("filename*=UTF-8''"
                + UriUtils.encode("Phiếu nhập kho " + parcel.number() + ".pdf", StandardCharsets.UTF_8));
        byte[] pdf = DocumentSamples.keep("wired-return-goods-receipt-rma", answer.getContentAsByteArray());
        assertThat(DocumentSamples.text(pdf)).contains("PHIẾU NHẬP KHO", "Số: " + parcel.number(),
                "Yêu cầu trả hàng số " + rma, "2.000");

        http.asCustomer(strangerUser, get(path)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RETURN_REQUEST_NOT_FOUND"));
        http.asCustomer(portalUser, get("/api/v1/returns/me/" + UUID.randomUUID() + "/receipt-voucher"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("RETURN_REQUEST_NOT_FOUND"));
        http.asCustomer(portalUser, get("/api/v1/goods-receipts/returns/" + parcel.id() + "/voucher"))
                .andExpect(status().isForbidden());
        http.anonymous(get(path)).andExpect(status().isUnauthorized());
    }

    /**
     * A return of the order's line, approved and awaiting its goods, as a bare row: the voucher reads only its number,
     * its order and its customer, and the receipt only that it awaits goods (the review that approves it is not this
     * test's subject).
     */
    private UUID awaitingReturn(UUID order, String rma, int units) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    INSERT INTO ordering.return_request (id, rma_number, order_id, customer_id, status, reason_code,
                        requested_at, order_number, request_id, channel, requested_by, return_required,
                        return_deadline, decided_by, decided_at)
                    SELECT ?, ?, o.id, o.customer_id, 'AWAITING_RETURN', 'PRINT_DEFECT', NOW(), o.order_number, ?,
                           'PORTAL', ?, TRUE, NOW() + INTERVAL '14 days', ?, NOW()
                      FROM ordering.customer_order o WHERE o.id = ?""", id, rma, UUID.randomUUID(), portalUser, staff,
                    order);
            // The order delivered what the return claims: its goods are taken by the return when the receipt is
            // confirmed (audit round 2, M1), and the line check refuses a return of more than was delivered.
            jdbc.update("UPDATE ordering.order_line SET quantity = ? WHERE order_id = ?", units, order);
            jdbc.update("""
                    INSERT INTO ordering.return_request_line (id, return_request_id, order_line_id, quantity, sku,
                        custom_printed, unit_price, approved_quantity)
                    SELECT ?, ?, l.id, ?, l.sku, TRUE, l.unit_price, ? FROM ordering.order_line l
                     WHERE l.order_id = ?""", Identifiers.newId(), id, units, units, order);
        });
        return id;
    }
}
