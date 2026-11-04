package com.stockflow.assistant;

import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.Role;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The receipt draft's guard with the real security chain, real grants and real orders (design §8.1, §5.4): it is the
 * goods-receipt screen's permission and warehouse rule, nothing more and nothing less, and every refusal comes before
 * a photo could leave. The test context has no model provider, so a request that passes every guard ends at
 * {@code ASSISTANT_DISABLED}: that is how passing is told apart from being refused.
 */
@SecuredIntegrationTest
class AssistantReceiptDraftIntegrationTest {

    private static final String DRAFTS = "/api/v1/assistant/receipt-drafts";

    @Autowired MockMvc mockMvc;
    // The two mocks every assistant integration test declares, so they all share one application context.
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    private SecuredMvc http;
    private byte[] photo;

    @BeforeEach
    void setUp() throws IOException {
        http = new SecuredMvc(mockMvc, decoder);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "jpeg", jpeg);
        photo = jpeg.toByteArray();
    }

    @Test
    @DisplayName("the endpoint carries exactly the guard of POST /api/v1/goods-receipts, the receipt it drafts")
    void mirrorsTheReceiptScreen() {
        RequiresPermission drafting = guard(RequestMethod.POST, DRAFTS);
        RequiresPermission receiving = guard(RequestMethod.POST, "/api/v1/goods-receipts");

        assertThat(List.of(drafting.resource(), drafting.action(), drafting.scope()))
                .isEqualTo(List.of(receiving.resource(), receiving.action(), receiving.scope()));
    }

    @Test
    @DisplayName("a role that may not create a goods receipt is refused with 403, whatever else it may do")
    void withoutTheReceiptPermission() throws Exception {
        String role = jdbc.queryForObject("""
                select r.code from identity.app_role r
                where r.code in ('INVENTORY_PLANNER', 'SALES_STAFF', 'ACCOUNTANT', 'QC_STAFF', 'ORDER_COORDINATOR')
                  and exists (select 1 from identity.role_permission rp join identity.permission p on p.id = rp.permission_id
                              where rp.role_id = r.id and p.code = 'assistant-conversations:CREATE')
                  and not exists (select 1 from identity.role_permission rp join identity.permission p on p.id = rp.permission_id
                                  where rp.role_id = r.id and p.code = 'procurement-goods-receipts:CREATE')
                order by r.code limit 1""", String.class);
        assertThat(role).as("a role that may use the assistant but may not receive goods").isNotNull();
        String number = confirmedOrder();

        http.asRole(Role.valueOf(role), staff(role), draft(number))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a portal customer is refused")
    void customersAreRefused() throws Exception {
        UUID account = tx.execute(s -> ReferenceRows.user(entityManager));

        http.asCustomer(account, draft(confirmedOrder())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a manager of another warehouse is told the order does not exist, as the order list tells them; one "
            + "of the order's warehouse passes every guard")
    void theOrdersWarehouseOnly() throws Exception {
        String number = confirmedOrder();
        UUID elsewhere = tx.execute(s -> warehouse());
        UUID stranger = staff("WAREHOUSE_MANAGER", elsewhere);
        UUID local = staff("WAREHOUSE_MANAGER", WAREHOUSE_HCM);

        http.asRole(Role.WAREHOUSE_MANAGER, stranger, draft(number))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PURCHASE_ORDER_NOT_FOUND"));
        http.asRole(Role.WAREHOUSE_MANAGER, local, draft(number))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("ASSISTANT_DISABLED"));
    }

    @Test
    @DisplayName("the dock clerks, who cannot open the chat, may have a note read: the receipt's permission decides")
    void warehouseStaffMayUseIt() throws Exception {
        String number = confirmedOrder();

        http.asRole(Role.WAREHOUSE_STAFF, staff("WAREHOUSE_STAFF", WAREHOUSE_HCM), draft(number))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("ASSISTANT_DISABLED"));
    }

    @Test
    @DisplayName("an order nothing can be received against is 409 for someone who may receive, before any photo "
            + "is read; a file that is not an image is 415")
    void refusalsBeforeReading() throws Exception {
        UUID local = staff("WAREHOUSE_MANAGER", WAREHOUSE_HCM);
        String number = confirmedOrder();
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE procurement.purchase_order_lines SET status = 'RECEIVED'
                 WHERE po_id = (SELECT id FROM procurement.purchase_orders WHERE po_number = ?)""", number));

        http.asRole(Role.WAREHOUSE_MANAGER, local, draft(number))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PURCHASE_ORDER_NOT_RECEIVABLE"));
        http.asRole(Role.WAREHOUSE_MANAGER, local, multipart(DRAFTS)
                        .file(new MockMultipartFile("file", "note.jpg", "image/jpeg",
                                "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
                        .param("poNumber", number))
                .andExpect(status().isUnsupportedMediaType());
    }

    // ------------------------------------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder draft(String number) {
        return multipart(DRAFTS).file(new MockMultipartFile("file", "note.jpg", "image/jpeg", photo))
                .param("poNumber", number);
    }

    /** A CONFIRMED order to the demo supplier, received at HCM, as the goods-receipt tests build one. */
    private String confirmedOrder() {
        UUID po = Identifiers.newId();
        UUID revision = Identifiers.newId();
        String number = "PO-RD-" + po.toString().substring(24).toUpperCase();
        tx.executeWithoutResult(s -> {
            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    SELECT ?, ?, 'CONFIRMED', s.id, w.id, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW()
                      FROM procurement.suppliers s, warehouse.warehouse w WHERE s.code = 'GOVIET' AND w.prefix = 'HCM'""",
                    po, number, submitter, approver, approver, revision);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    revision, po, submitter);
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_lines (id, po_id, po_revision_id, line_no, inventory_item_id,
                        ordered_qty, unit_price, version, created_at)
                    SELECT ?, ?, ?, 1, id, 20, 1000, 0, NOW() FROM inventory.inventory_items WHERE sku = 'TABLE-OAK-160'""",
                    Identifiers.newId(), po, revision);
        });
        return number;
    }

    private UUID warehouse() {
        UUID id = Identifiers.newId();
        String prefix = "RD" + id.toString().substring(30).toUpperCase();
        jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Receipt draft fixture', 'ACTIVE', ?, 'Test road', 'M', 50, 50, 0, NOW())""", id, prefix);
        return id;
    }

    private UUID staff(String role, UUID... warehouses) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role);
            for (UUID warehouse : warehouses) {
                jdbc.update("""
                        INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                        VALUES (gen_random_uuid(), ?, ?, 0, NOW())""", user, warehouse);
            }
        });
        return user;
    }

    private RequiresPermission guard(RequestMethod method, String path) {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            if (info.getMethodsCondition().getMethods().contains(method)
                    && info.getPathPatternsCondition() != null
                    && info.getPathPatternsCondition().getPatternValues().contains(path)) {
                RequiresPermission guard = AnnotatedElementUtils.findMergedAnnotation(entry.getValue().getMethod(),
                        RequiresPermission.class);
                assertThat(guard).as("%s %s is guarded", method, path).isNotNull();
                return guard;
            }
        }
        throw new AssertionError("no handler for " + method + " " + path);
    }
}
