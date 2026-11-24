package com.stockflow.order;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderNumber;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ReturnEvidence;
import com.stockflow.order.internal.domain.ReturnReason;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnResolution;
import com.stockflow.order.internal.service.ReturnRequestService;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.ShippedCartons;
import com.stockflow.support.StaffFixtures;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit round 3 over HTTP, through the real filter chain, permission aspect and data scope:
 * <ul>
 *   <li>RAD-2: a clerk of another warehouse is refused the custody endpoints of this warehouse's return
 *       ({@code OUT_OF_DATA_SCOPE}), on all three paths, as {@code GET /returns/{id}} refuses them;</li>
 *   <li>RAD-3: the custody notes' list is the warehouse's, limited to its returns; Sales record the customer's choice
 *       (sales-rmas:UPDATE), the warehouse manager the ending (sales-rma-custody:UPDATE), in their warehouses only.</li>
 * </ul>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false", "stockflow.catalog.projection-retry-ms=3600000"})
class ReturnCustodyHttpTest {

    private static final AtomicLong ORDER_NUMBERS = new AtomicLong(830_000 + ThreadLocalRandom.current().nextInt(15_000));
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D',
            'R', 0, 0, 0, 1, 0, 0, 0, 1};
    private static final int CARTON = 50;

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean JavaMailSenderImpl mail;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired OrderRepository orderRepository;
    @Autowired ReturnRequestService returns;

    private SecuredMvc mvc;
    private UUID hcmClerk;
    private UUID hcmManager;
    private UUID otherClerk;
    private UUID otherManager;
    private UUID sales;
    private ReturnRequest request;

    @BeforeEach
    void setUp() {
        mvc = new SecuredMvc(mockMvc, decoder);
        StaffFixtures staff = new StaffFixtures(jdbc, tx, entityManager);
        StaffFixtures.Warehouse other = staff.otherWarehouse();
        hcmClerk = staff.staff("WAREHOUSE_STAFF", DemoData.WAREHOUSE_HCM);
        hcmManager = staff.staff("WAREHOUSE_MANAGER", DemoData.WAREHOUSE_HCM);
        otherClerk = staff.staff("WAREHOUSE_STAFF", other.id());
        otherManager = staff.staff("WAREHOUSE_MANAGER", other.id());
        sales = staff.staff("SALES_STAFF");
        UUID approver = staff.staff("SALES_STAFF");

        UUID customerId = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, full_name, email, tax_code, status, version, created_at)
                VALUES (?, 'Cong ty Giu Ho', ?, ?, 'ACTIVE', 0, NOW())""", customerId,
                "custody-" + customerId + "@example.com", "07" + (10_000_000 + ThreadLocalRandom.current()
                        .nextInt(89_999_999))));
        CustomerSkuRows.Issued sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customerId, CARTON, "STANDARD",
                true);
        UUID orderId = tx.execute(s -> orderRepository.save(Order.draft(OrderNumber.of(LocalDate.now(ZoneId.of(
                        "Asia/Ho_Chi_Minh")), ORDER_NUMBERS.incrementAndGet()), customerId, Identifiers.newId(),
                List.of(Order.line(Identifiers.newId(), new Sku(sku.sku()), CARTON, Money.vnd(2_000), null)
                        .withCartons(sku.uomConversionId(), 1, CARTON).withTax(BigDecimal.TEN)), Instant.now()))
                .id().value());
        UUID lineId = jdbc.queryForObject("SELECT id FROM ordering.order_line WHERE order_id = ?", UUID.class, orderId);
        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                    UPDATE ordering.customer_order SET status = 'DELIVERED', warehouse_id = ?,
                           shipped_at = NOW() - interval '2 days', delivered_at = NOW() - interval '1 day' WHERE id = ?""",
                    DemoData.WAREHOUSE_HCM, orderId);
        });
        ShippedCartons.ship(jdbc, tx, orderId, lineId, sku.sku(), 1, CARTON, "LOT-CUS-" + orderId.toString()
                .substring(0, 8), "DELIVERED", hcmClerk);
        CurrentUser seller = new CurrentUser(sales, "sales", Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
        ReturnRequest opened = WithCurrentUser.call(seller, DataScope.ALL, () -> returns.openForCustomer(
                new ReturnRequestService.OpenReturnCommand(UUID.randomUUID(), orderId, ReturnReason.DAMAGED_IN_TRANSIT,
                        "móp", ReturnResolution.REFUND, List.of(new ReturnRequest.Claim(lineId, CARTON)), null, null),
                sales));
        returns.addEvidence(opened.id(), null, ReturnEvidence.Stage.CLAIM,
                new FileUpload("a.png", "image/png", PNG.length, new ByteArrayInputStream(PNG)), sales);
        request = returns.approve(opened.id(), new ReturnRequestService.ApproveReturnCommand(Map.of(), null, "ok",
                null), approver);
    }

    private MockMultipartFile photo() {
        return new MockMultipartFile("file", "giu-ho.png", "image/png", PNG);
    }

    private String path(String tail) {
        return "/api/v1/returns/" + request.id() + tail;
    }

    @Test
    @DisplayName("RAD-2: another warehouse's clerk is refused all three custody paths of this return with 403 "
            + "OUT_OF_DATA_SCOPE, as the return's detail; this warehouse's clerk records the note")
    void custodyPathsAreWarehouseScoped() throws Exception {
        mvc.asRole(Role.WAREHOUSE_STAFF, otherClerk, multipart(path("/custody")).file(photo()).param("sku", "CUP-16")
                        .param("quantity", "3"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        mvc.asRole(Role.WAREHOUSE_STAFF, otherClerk, post(path("/custody/uploads")).contentType(MediaType
                        .APPLICATION_JSON).content("""
                        {"fileName": "a.png", "contentType": "image/png", "sizeBytes": 24,
                         "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"}"""))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        mvc.asRole(Role.WAREHOUSE_STAFF, otherClerk, post(path("/custody")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uploadId\": \"" + Identifiers.newId() + "\", \"sku\": \"CUP-16\", \"quantity\": 3}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        mvc.asRole(Role.WAREHOUSE_STAFF, otherClerk, get(path(""))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));

        mvc.asRole(Role.WAREHOUSE_STAFF, hcmClerk, multipart(path("/custody")).file(photo()).param("sku", "CUP-16")
                        .param("quantity", "3"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.evidence[?(@.stage == 'CUSTODY')].custody.quantity").value(3));
    }

    @Test
    @DisplayName("RAD-3: the warehouse lists its custody notes, another warehouse none of them; Sales record the "
            + "customer's choice, a warehouse role cannot; the warehouse manager of the return's warehouse records "
            + "the ending, a clerk (no permission) and another warehouse's manager cannot")
    void custodyNoteOutcomeEndpoints() throws Exception {
        String answer = mvc.asRole(Role.WAREHOUSE_STAFF, hcmClerk, multipart(path("/custody")).file(photo())
                        .param("sku", "CUP-16").param("quantity", "3")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String note = com.jayway.jsonpath.JsonPath.<List<String>>read(answer,
                "$.data.evidence[?(@.stage == 'CUSTODY')].id").getFirst();
        String list = "/api/v1/returns/custody-notes?returnRequestId=" + request.id();

        mvc.asRole(Role.WAREHOUSE_MANAGER, hcmManager, get(list)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].evidenceId").value(note))
                .andExpect(jsonPath("$.data.items[0].rmaNumber").value(request.rmaNumber()))
                .andExpect(jsonPath("$.data.items[0].choice").doesNotExist());
        mvc.asRole(Role.WAREHOUSE_MANAGER, otherManager, get(list)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        String choice = "{\"choice\": \"SEND_BACK\", \"note\": \"khách chịu phí\"}";
        mvc.asRole(Role.WAREHOUSE_MANAGER, hcmManager, post(path("/custody/" + note + "/choice"))
                .contentType(MediaType.APPLICATION_JSON).content(choice)).andExpect(status().isForbidden());
        mvc.asRole(Role.SALES_STAFF, sales, post(path("/custody/" + note + "/choice"))
                        .contentType(MediaType.APPLICATION_JSON).content(choice)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence[?(@.stage == 'CUSTODY')].custody.outcome.choice")
                        .value("SEND_BACK"));

        String end = "{\"note\": \"Biên bản bàn giao 3\"}";
        mvc.asRole(Role.WAREHOUSE_STAFF, hcmClerk, post(path("/custody/" + note + "/end"))
                .contentType(MediaType.APPLICATION_JSON).content(end)).andExpect(status().isForbidden());
        mvc.asRole(Role.WAREHOUSE_MANAGER, otherManager, post(path("/custody/" + note + "/end"))
                        .contentType(MediaType.APPLICATION_JSON).content(end)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_DATA_SCOPE"));
        mvc.asRole(Role.WAREHOUSE_MANAGER, hcmManager, post(path("/custody/" + note + "/end"))
                        .contentType(MediaType.APPLICATION_JSON).content(end)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.endedNote").value("Biên bản bàn giao 3"));
        mvc.asRole(Role.WAREHOUSE_MANAGER, hcmManager, get(list + "&ended=true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].choice").value("SEND_BACK"));
    }
}
