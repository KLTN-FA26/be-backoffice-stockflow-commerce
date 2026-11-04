package com.stockflow.assistant;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The customer and queue tools of 29/10 (ROLE-6, ROLE-7, ROLE-8) end to end, with the real security chain, the real
 * permission aspect, real migrations and a scripted model: {@code getCustomerPrice}, {@code getCustomerCredit},
 * {@code findFulfillmentTasks} and {@code findGoodsReceipts} answer what the screen each mirrors would show the asker.
 * That screen's permission decides whether, the asker's warehouses decide which. And the two floor roles that
 * V20261029000200 opened the assistant to may ask.
 *
 * <p>Rows are inserted the way the flows leave them; how they got there is the owning modules' tests' business. Every
 * fixture is this test's own - two fresh warehouses, customers and receipt numbers carrying a tag - so another test's
 * rows never change what is counted here. Declares the same mocks as {@link AssistantIntegrationTest}, so the two share
 * one application context.</p>
 */
@SecuredIntegrationTest
class AssistantCustomerAndQueueToolsIntegrationTest {

    private static final String BASE = "/api/v1/assistant/conversations";
    private static final String SKU = "SOFA-3S-GREY";
    /** Staff text on the commercial terms: it must not leave, phone number and all. */
    private static final String TERMS_NOTE = "Duyệt hạn mức theo đề nghị của anh Nam, gọi 0903 123 456";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc http;
    private String tag;
    /** A warehouse of this test's own, and another one a user limited to the first must not see. */
    private UUID here;
    private UUID away;
    private String herePrefix;
    private String awayPrefix;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        when(model.available()).thenReturn(true);
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{\"closing\":true}",
                call.getArgument(0), List.of()));
        tag = UUID.randomUUID().toString().replace("-", "").substring(0, 7).toUpperCase(Locale.ROOT);
        herePrefix = "Q" + tag;
        awayPrefix = "R" + tag;
        here = warehouse(herePrefix);
        away = warehouse(awayPrefix);
    }

    // ------------------------------------------------------------------------------------------------- prices

    @Test
    @DisplayName("Sales gets the customer's own tiers and the tier a quantity falls in; a fragment of the name offers "
            + "the customers it matches, by name only")
    void pricesAsSalesQuotesThem() throws Exception {
        assertThat(granted("SALES_STAFF", "catalog-customer-prices:READ")).as("the seeded grant this case relies on")
                .isTrue();
        Customers customers = customers();
        ownPrice(customers.abc());
        UUID seller = staff("SALES_STAFF");
        script(toolUse(call("toolu_exact", "getCustomerPrice", price(customers.abcName(), "sofa-3s-grey", 120)),
                        call("toolu_fragment", "getCustomerPrice", price("In Ly " + tag, SKU, 0))),
                answer("Giá riêng của khách: từ 100 cái 4.800.000 ₫."));
        String conversation = start(Role.SALES_STAFF, seller);

        http.asRole(Role.SALES_STAFF, seller, ask(conversation, "Khách " + customers.abcName()
                + " lấy 120 bộ sofa thì giá bao nhiêu?")).andExpect(status().isOk());

        String exact = result(conversation, "toolu_exact");
        assertThat(outcome(conversation, "toolu_exact")).isEqualTo("OK");
        assertThat(JsonPath.<String>read(exact, "$.customerName")).isEqualTo(customers.abcName());
        assertThat(JsonPath.<String>read(exact, "$.sku")).as("normalised as the screen does").isEqualTo(SKU);
        assertThat(JsonPath.<String>read(exact, "$.source")).isEqualTo("CUSTOMER");
        assertThat(JsonPath.<Integer>read(exact, "$.moq")).isEqualTo(50);
        assertThat(JsonPath.<List<Integer>>read(exact, "$.tiers[*].minQuantity")).containsExactly(50, 100);
        assertThat(JsonPath.<Integer>read(exact, "$.tierForQuantity.minQuantity")).isEqualTo(100);
        assertThat(JsonPath.<Number>read(exact, "$.tierForQuantity.unitPrice").doubleValue()).isEqualTo(4_800_000d);
        assertThat(JsonPath.<Boolean>read(exact, "$.belowMoq")).isFalse();
        assertThat(exact).doesNotContain(customers.taxCode(), "0903123456", "@example.vn",
                customers.abc().toString());

        String fragment = result(conversation, "toolu_fragment");
        assertThat(JsonPath.<List<String>>read(fragment, "$.candidates[*].customerName"))
                .containsExactlyInAnyOrder(customers.abcName(), customers.branchName());
        assertThat(JsonPath.<List<Object>>read(fragment, "$.tiers")).isEmpty();
        assertThat(fragment).doesNotContain(customers.taxCode(), "0903123456", "@example.vn");
    }

    @Test
    @DisplayName("the coordinator, who may not list customers, gets a price by the exact tax code and no names for a "
            + "fragment")
    void pricesForWhoMayNotListCustomers() throws Exception {
        assertThat(granted("ORDER_COORDINATOR", "catalog-customer-prices:READ")).isTrue();
        assertThat(granted("ORDER_COORDINATOR", "customer-customers:READ"))
                .as("the coordinator does not search the customer list under the seeded grants").isFalse();
        Customers customers = customers();
        ownPrice(customers.abc());
        UUID coordinator = staff("ORDER_COORDINATOR");
        String spaced = customers.taxCode().substring(0, 4) + " " + customers.taxCode().substring(4);
        script(toolUse(call("toolu_fragment", "getCustomerPrice", price("In Ly " + tag, SKU, 0)),
                        call("toolu_tax", "getCustomerPrice", price(spaced, SKU, 30))),
                answer("Cần tên đầy đủ hoặc mã số thuế."));
        String conversation = start(Role.ORDER_COORDINATOR, coordinator);

        http.asRole(Role.ORDER_COORDINATOR, coordinator, ask(conversation, "Giá sofa cho khách In Ly " + tag + "?"))
                .andExpect(status().isOk());

        String fragment = result(conversation, "toolu_fragment");
        assertThat(outcome(conversation, "toolu_fragment")).isEqualTo("OK");
        assertThat(JsonPath.<List<Object>>read(fragment, "$.candidates")).isEmpty();
        assertThat(JsonPath.<String>read(fragment, "$.note")).contains("exactly");
        assertThat(fragment).doesNotContain(customers.abcName(), customers.branchName());

        String byTaxCode = result(conversation, "toolu_tax");
        assertThat(JsonPath.<String>read(byTaxCode, "$.customerName")).isEqualTo(customers.abcName());
        assertThat(JsonPath.<Boolean>read(byTaxCode, "$.belowMoq")).as("30 is under the minimum of 50").isTrue();
        assertThat(byTaxCode).doesNotContain(customers.taxCode());
    }

    // ------------------------------------------------------------------------------------------------- credit

    @Test
    @DisplayName("the accountant gets a customer's terms and what it owes, overdue included; never a credit-left figure, "
            + "the approver's note or a contact")
    void creditAndReceivablesOfOneCustomer() throws Exception {
        assertThat(granted("ACCOUNTANT", "customer-credit-profiles:READ")).isTrue();
        assertThat(granted("ACCOUNTANT", "payment-receivables:READ")).isTrue();
        Customers customers = customers();
        UUID approver = tx.execute(s -> ReferenceRows.user(entityManager));
        creditTerms(customers.abc(), approver);
        // 30M open and 15M of 20M overdue: 45M owed, 15M overdue. Paid and carrier-collected rows are not the customer's
        // debt: neither counts.
        receivable(customers.abc(), "OPEN", "CUSTOMER", 30_000_000, 0, 10);
        receivable(customers.abc(), "OVERDUE", "CUSTOMER", 20_000_000, 5_000_000, -15);
        receivable(customers.abc(), "PAID", "CUSTOMER", 10_000_000, 10_000_000, -40);
        receivable(customers.abc(), "OPEN", "CARRIER", 7_000_000, 0, 0);
        UUID accountant = staff("ACCOUNTANT");
        String spaced = customers.taxCode().substring(0, 3) + "." + customers.taxCode().substring(3);
        script(toolUse(call("toolu_owed", "getCustomerCredit", customer(spaced)),
                        call("toolu_fragment", "getCustomerCredit", customer("In Ly " + tag))),
                answer("Khách đang nợ 45.000.000 ₫, quá hạn 15.000.000 ₫."));
        String conversation = start(Role.ACCOUNTANT, accountant);

        http.asRole(Role.ACCOUNTANT, accountant, ask(conversation, "Công nợ hiện tại của khách MST " + spaced + "?"))
                .andExpect(status().isOk());

        String owed = result(conversation, "toolu_owed");
        assertThat(outcome(conversation, "toolu_owed")).isEqualTo("OK");
        assertThat(JsonPath.<String>read(owed, "$.customerName")).isEqualTo(customers.abcName());
        assertThat(JsonPath.<List<String>>read(owed, "$.terms.allowedTerms")).containsExactly("PREPAID", "CREDIT");
        assertThat(JsonPath.<String>read(owed, "$.terms.defaultTerm")).isEqualTo("CREDIT");
        assertThat(JsonPath.<Number>read(owed, "$.terms.creditLimit").doubleValue()).isEqualTo(100_000_000d);
        assertThat(JsonPath.<Integer>read(owed, "$.terms.creditTermDays")).isEqualTo(30);
        assertThat(JsonPath.<Number>read(owed, "$.receivables.outstanding").doubleValue()).isEqualTo(45_000_000d);
        assertThat(JsonPath.<Boolean>read(owed, "$.receivables.hasOverdue")).isTrue();
        assertThat(JsonPath.<Number>read(owed, "$.receivables.overdueAmount").doubleValue()).isEqualTo(15_000_000d);
        assertThat(JsonPath.<String>read(owed, "$.receivables.oldestOverdueDueDate"))
                .isEqualTo(jdbc.queryForObject("SELECT (CURRENT_DATE - 15)::text", String.class));
        assertThat(owed).doesNotContainIgnoringCase("available")
                .doesNotContain("anh Nam", "0903", approver.toString(), customers.taxCode(), "@example.vn",
                        customers.abc().toString());

        String fragment = result(conversation, "toolu_fragment");
        assertThat(JsonPath.<Map<String, Object>>read(fragment, "$").get("terms")).isNull();
        assertThat(JsonPath.<List<Object>>read(fragment, "$.candidates")).as("the accountant may not list customers")
                .isEmpty();
        assertThat(fragment).doesNotContain(customers.abcName(), customers.branchName());
    }

    @Test
    @DisplayName("a company registered in capitals, as Vietnamese companies often are, is found by its full name typed "
            + "in any case, under the databases' C locale too (Specs.contains folds every letter)")
    void capitalsAreFoundByTheirName() throws Exception {
        String registered = "CÔNG TY TNHH IN BAO BÌ ĐẠI PHÁT " + tag;
        customer(registered, null);
        UUID accountant = staff("ACCOUNTANT");
        String typed = "Công ty TNHH In Bao Bì Đại Phát " + tag;
        script(toolUse(call("toolu_capitals", "getCustomerCredit", customer(typed))),
                answer("Khách chưa có điều khoản công nợ."));
        String conversation = start(Role.ACCOUNTANT, accountant);

        http.asRole(Role.ACCOUNTANT, accountant, ask(conversation, "Công nợ của " + typed + "?"))
                .andExpect(status().isOk());

        assertThat(outcome(conversation, "toolu_capitals")).isEqualTo("OK");
        assertThat(JsonPath.<String>read(result(conversation, "toolu_capitals"), "$.customerName"))
                .as("found by the exact name, the only way an accountant may name a customer").isEqualTo(registered);
        assertThat(jdbc.queryForObject("SELECT lower('ĐẠI PHÁT' COLLATE \"C\") <> 'đại phát'", Boolean.class))
                .as("what the C locale's lower() does to Vietnamese capitals, and why the search may not rely on it")
                .isTrue();
    }

    @Test
    @DisplayName("Sales, who may list customers, is offered the customers a fragment matches before any figure")
    void creditCandidatesForSales() throws Exception {
        Customers customers = customers();
        UUID seller = staff("SALES_STAFF");
        script(toolUse(call("toolu_fragment", "getCustomerCredit", customer("In Ly " + tag))),
                answer("Có hai khách khớp, bạn muốn xem khách nào?"));
        String conversation = start(Role.SALES_STAFF, seller);

        http.asRole(Role.SALES_STAFF, seller, ask(conversation, "Khách In Ly " + tag + " còn nợ bao nhiêu?"))
                .andExpect(status().isOk());

        String fragment = result(conversation, "toolu_fragment");
        assertThat(JsonPath.<List<String>>read(fragment, "$.candidates[*].customerName"))
                .containsExactlyInAnyOrder(customers.abcName(), customers.branchName());
        assertThat(JsonPath.<Map<String, Object>>read(fragment, "$").get("receivables")).isNull();
    }

    // ------------------------------------------------------------------------------------------------- queues

    @Test
    @DisplayName("a manager sees every pick task of their warehouse and none of another; a picker sees only their own")
    void pickTasksAsTheScreenShowsThem() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", here);
        UUID picker = staff("WAREHOUSE_STAFF", here);
        UUID colleague = tx.execute(s -> ReferenceRows.user(entityManager));
        String mine = picking(here, picker, "PENDING", manager);
        String theirs = picking(here, colleague, "PICKING", manager);
        String elsewhere = picking(away, picker, "PENDING", manager);

        script(toolUse(call("toolu_all", "findFulfillmentTasks", tasks("ANY", 50))), answer("Kho có 2 task."));
        String managed = start(Role.WAREHOUSE_MANAGER, manager);
        http.asRole(Role.WAREHOUSE_MANAGER, manager, ask(managed, "Kho còn những task nhặt nào?"))
                .andExpect(status().isOk());

        String all = result(managed, "toolu_all");
        assertThat(JsonPath.<String>read(all, "$.visibility")).isEqualTo("EVERY_TASK");
        assertThat(JsonPath.<List<String>>read(all, "$.tasks[*].orderNumber")).containsExactlyInAnyOrder(mine, theirs);
        assertThat(JsonPath.<Integer>read(all, "$.total")).isEqualTo(2);
        assertThat(JsonPath.<List<String>>read(all, "$.tasks[*].warehouseCode")).containsOnly(herePrefix);
        assertThat(JsonPath.<List<Boolean>>read(all, "$.tasks[*].assignedToUser")).containsOnly(false);
        assertThat(all).doesNotContain(elsewhere, awayPrefix, picker.toString(), colleague.toString());

        script(toolUse(call("toolu_mine", "findFulfillmentTasks", tasks("ANY", 50)),
                        call("toolu_picking", "findFulfillmentTasks", tasks("PICKING", 50))),
                answer("Bạn có 1 task chờ nhặt."));
        String asked = start(Role.WAREHOUSE_STAFF, picker);
        http.asRole(Role.WAREHOUSE_STAFF, picker, ask(asked, "Tôi còn task nào?")).andExpect(status().isOk());

        String own = result(asked, "toolu_mine");
        assertThat(JsonPath.<String>read(own, "$.visibility")).as("a picker is told the list is their own")
                .isEqualTo("OWN_TASKS");
        assertThat(JsonPath.<List<String>>read(own, "$.tasks[*].orderNumber"))
                .as("their own, and only in the warehouse they are assigned to").containsExactly(mine);
        assertThat(JsonPath.<Boolean>read(own, "$.tasks[0].assignedToUser")).isTrue();
        assertThat(JsonPath.<String>read(own, "$.tasks[0].pickStatus")).isEqualTo("PENDING");
        assertThat(JsonPath.<Integer>read(result(asked, "toolu_picking"), "$.total"))
                .as("the task being picked is a colleague's").isZero();
    }

    @Test
    @DisplayName("QC sees the open receipts of their warehouse only; purchasing, who is not limited, sees both")
    void goodsReceiptsOfTheirWarehouseOnly() throws Exception {
        assertThat(granted("QC_STAFF", "procurement-goods-receipts:READ")).isTrue();
        UUID clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        Po herePo = purchaseOrder(here, "PO-IT-" + tag + "-H");
        Po awayPo = purchaseOrder(away, "PO-IT-" + tag + "-A");
        String counting = receipt(herePo, here, "DRAFT", clerk, 1);
        String inQc = receipt(herePo, here, "IN_QC", clerk, 2);
        String toPutAway = receipt(herePo, here, "IN_PUTAWAY", clerk, 3);
        String closed = receipt(herePo, here, "CLOSED", clerk, 4);
        String elsewhere = receipt(awayPo, away, "IN_QC", clerk, 5);
        UUID qc = staff("QC_STAFF", here);
        script(toolUse(call("toolu_open", "findGoodsReceipts", receipts("OPEN", tag, 50)),
                        call("toolu_qc", "findGoodsReceipts", receipts("IN_QC", tag, 50))),
                answer("Một phiếu đang chờ QC."));
        String asked = start(Role.QC_STAFF, qc);

        http.asRole(Role.QC_STAFF, qc, ask(asked, "Phiếu nhập nào đang chờ QC?")).andExpect(status().isOk());

        String open = result(asked, "toolu_open");
        assertThat(JsonPath.<List<String>>read(open, "$.receipts[*].receiptNumber"))
                .containsExactlyInAnyOrder(counting, inQc, toPutAway);
        assertThat(JsonPath.<Integer>read(open, "$.total")).isEqualTo(3);
        assertThat(JsonPath.<List<String>>read(open, "$.receipts[*].warehouseCode")).containsOnly(herePrefix);
        assertThat(JsonPath.<List<String>>read(open, "$.receipts[*].purchaseOrderNumber"))
                .containsOnly("PO-IT-" + tag + "-H");
        assertThat(JsonPath.<List<String>>read(open, "$.receipts[*].supplierName")).containsOnly("Xưởng gỗ Việt");
        assertThat(open).doesNotContain(closed, elsewhere, awayPrefix, clerk.toString());
        assertThat(JsonPath.<List<String>>read(result(asked, "toolu_qc"), "$.receipts[*].receiptNumber"))
                .containsExactly(inQc);

        UUID buyer = staff("PROCUREMENT_STAFF");
        script(toolUse(call("toolu_any", "findGoodsReceipts", receipts("ANY", tag, 50))), answer("Có 5 phiếu."));
        String bought = start(Role.PROCUREMENT_STAFF, buyer);
        http.asRole(Role.PROCUREMENT_STAFF, buyer, ask(bought, "Các phiếu nhập " + tag + "?"))
                .andExpect(status().isOk());

        String any = result(bought, "toolu_any");
        assertThat(JsonPath.<Integer>read(any, "$.total")).isEqualTo(5);
        assertThat(JsonPath.<List<String>>read(any, "$.receipts[*].warehouseCode"))
                .containsOnly(herePrefix, awayPrefix);
    }

    // ------------------------------------------------------------------------------------------------- refusals

    @Test
    @DisplayName("a role without a screen's permission is DENIED, told which permission, and sees no row")
    void deniedWithoutTheScreensPermission() throws Exception {
        assertThat(granted("ACCOUNTANT", "fulfillment-pick-lists:READ")).isFalse();
        assertThat(granted("ACCOUNTANT", "procurement-goods-receipts:READ")).isFalse();
        assertThat(granted("ACCOUNTANT", "catalog-customer-prices:READ")).isFalse();
        assertThat(granted("WAREHOUSE_MANAGER", "customer-credit-profiles:READ")).isFalse();
        Customers customers = customers();
        ownPrice(customers.abc());
        UUID accountant = staff("ACCOUNTANT");
        script(toolUse(call("toolu_tasks", "findFulfillmentTasks", tasks("ANY", 50)),
                        call("toolu_receipts", "findGoodsReceipts", receipts("ANY", "", 50)),
                        call("toolu_price", "getCustomerPrice", price(customers.abcName(), SKU, 100))),
                answer("Bạn chưa có quyền xem các dữ liệu này."));
        String asked = start(Role.ACCOUNTANT, accountant);
        http.asRole(Role.ACCOUNTANT, accountant, ask(asked, "Kho còn task nào, phiếu nhập nào, giá sofa bao nhiêu?"))
                .andExpect(status().isOk());

        assertThat(outcome(asked, "toolu_tasks")).isEqualTo("DENIED");
        assertThat(result(asked, "toolu_tasks")).startsWith("NOT_PERMITTED").contains("fulfillment-pick-lists:READ");
        assertThat(outcome(asked, "toolu_receipts")).isEqualTo("DENIED");
        assertThat(result(asked, "toolu_receipts")).contains("procurement-goods-receipts:READ");
        assertThat(outcome(asked, "toolu_price")).isEqualTo("DENIED");
        assertThat(result(asked, "toolu_price")).contains("catalog-customer-prices:READ")
                .doesNotContain("4800000", customers.abcName());

        UUID manager = staff("WAREHOUSE_MANAGER", here);
        script(toolUse(call("toolu_credit", "getCustomerCredit", customer(customers.abcName()))),
                answer("Bạn chưa có quyền xem công nợ."));
        String managed = start(Role.WAREHOUSE_MANAGER, manager);
        http.asRole(Role.WAREHOUSE_MANAGER, manager, ask(managed, "Khách " + customers.abcName() + " nợ bao nhiêu?"))
                .andExpect(status().isOk());

        assertThat(outcome(managed, "toolu_credit")).isEqualTo("DENIED");
        assertThat(result(managed, "toolu_credit")).contains("customer-credit-profiles:READ")
                .doesNotContain(customers.abcName());
    }

    // ------------------------------------------------------------------------------------------------- AQ-02

    @Test
    @DisplayName("warehouse and production staff may open the assistant (V20261029000200); a portal customer still may "
            + "not")
    void theFloorRolesMayAsk() throws Exception {
        for (String role : List.of("WAREHOUSE_STAFF", "PRODUCTION_STAFF")) {
            for (String action : List.of("VIEW_PAGE", "READ", "CREATE")) {
                assertThat(granted(role, "assistant-conversations:" + action)).as("%s holds %s", role, action)
                        .isTrue();
            }
            UUID user = staff(role, here);
            http.asRole(Role.valueOf(role), user, get("/api/v1/assistant/capabilities")).andExpect(status().isOk());
            start(Role.valueOf(role), user);
        }
        assertThat(granted("CUSTOMER", "assistant-conversations:READ")).isFalse();
        assertThat(granted("CUSTOMER", "assistant-conversations:CREATE")).isFalse();
    }

    // ------------------------------------------------------------------------------------------------- fixtures

    /** Two customers whose names share a fragment; {@code abc} has a tax code of its own. */
    private record Customers(UUID abc, String abcName, String taxCode, UUID branch, String branchName) {
    }

    private Customers customers() {
        String abcName = "Công ty In Ly " + tag;
        String branchName = "Công ty In Ly " + tag + " Chi nhánh Hà Nội";
        String taxCode = "9%09d".formatted(ThreadLocalRandom.current().nextInt(1_000_000_000));
        return new Customers(customer(abcName, taxCode), abcName, taxCode, customer(branchName, null), branchName);
    }

    private UUID customer(String name, String taxCode) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.customer (id, full_name, email, phone, status, tax_code, version, created_at)
                VALUES (?, ?, ?, '0903123456', 'ACTIVE', ?, 0, NOW())""",
                id, name, "it-" + id + "@example.vn", taxCode));
        return id;
    }

    /** The customer's own price for the demo sofa: from 50 units 5 000 000 ₫, from 100 units 4 800 000 ₫. */
    private void ownPrice(UUID customer) {
        UUID price = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO catalog.customer_price (id, customer_id, sku, currency, version, created_at)
                    VALUES (?, ?, ?, 'VND', 0, NOW())""", price, customer, SKU);
            jdbc.update("""
                    INSERT INTO catalog.customer_price_tier (id, customer_price_id, min_quantity, unit_price, version,
                                                             created_at)
                    VALUES (?, ?, 50, 5000000, 0, NOW()), (?, ?, 100, 4800000, 0, NOW())""",
                    Identifiers.newId(), price, Identifiers.newId(), price);
        });
    }

    /** Credit on 30 days up to 100 000 000 ₫, or prepaid; approved with a note that must stay in the system. */
    private void creditTerms(UUID customer, UUID approver) {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO customer.credit_profile (id, customer_id, allow_prepaid, allow_deposit, allow_credit,
                    default_payment_term, credit_limit, credit_term_days, currency, approved_by, approved_at, note,
                    version, created_at)
                VALUES (?, ?, TRUE, FALSE, TRUE, 'CREDIT', 100000000, 30, 'VND', ?, NOW(), ?, 0, NOW())""",
                Identifiers.newId(), customer, approver, TERMS_NOTE));
    }

    /** A receivable on a delivered credit order of its own, due {@code dueInDays} from today. */
    private void receivable(UUID customer, String status, String collector, long amount, long paid, int dueInDays) {
        tx.executeWithoutResult(s -> {
            UUID order = ReferenceRows.orderWithLine(entityManager, SKU, "COMPLETED", customer, null, null);
            jdbc.update("""
                    INSERT INTO payment.receivable (id, order_id, customer_id, kind, amount, paid_amount, currency,
                        issued_at, due_date, status, settled_at, collector)
                    VALUES (?, ?, ?, ?, ?, ?, 'VND', NOW() - INTERVAL '40 days', CURRENT_DATE + ?, ?, ?, ?)""",
                    Identifiers.newId(), order, customer,
                    "CARRIER".equals(collector) ? "DEPOSIT_BALANCE" : "CREDIT_ORDER", amount, paid, dueInDays,
                    status, "PAID".equals(status) ? Timestamp.from(Instant.now()) : null, collector);
        });
    }

    private UUID warehouse(String prefix) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO warehouse.warehouse (id, name, status, prefix, address, map_unit, map_width, map_height,
                                                 version, created_at)
                VALUES (?, 'Queue fixture', 'ACTIVE', ?, 'Test road', 'M', 50, 50, 0, NOW())""", id, prefix));
        return id;
    }

    /** A released order being picked in {@code warehouse}, its task assigned to {@code assignee}; its number. */
    private String picking(UUID warehouse, UUID assignee, String pickStatus, UUID releasedBy) {
        String number = "SO-20991227-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000));
        tx.executeWithoutResult(s -> {
            UUID order = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status,
                                                         total_amount, currency, placed_at, version, created_at,
                                                         warehouse_id, released_at, released_by)
                    VALUES (?, ?, ?, ?, 'PICKING', 1000, 'VND', NOW(), 0, NOW(), ?, NOW(), ?)""",
                    order, number, DemoData.CUSTOMER_ID, UUID.randomUUID(), warehouse, releasedBy);
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
                    VALUES (?, ?, ?, 1, 1000, 'VND')""", Identifiers.newId(), order, SKU);
            UUID pick = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, assigned_user_id,
                                                  assigned_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())""", pick, order, number, warehouse, pickStatus, assignee);
            jdbc.update("INSERT INTO fulfillment.pack (id, pick_id, status, created_at) VALUES (?, ?, 'PENDING', NOW())",
                    Identifiers.newId(), pick);
        });
        return number;
    }

    private record Po(UUID id, UUID revision) {
    }

    /** A CONFIRMED purchase order of the demo supplier into {@code warehouse}, with its first revision. */
    private Po purchaseOrder(UUID warehouse, String number) {
        Po po = new Po(Identifiers.newId(), Identifiers.newId());
        tx.executeWithoutResult(s -> {
            UUID submitter = ReferenceRows.user(entityManager);
            UUID approver = ReferenceRows.user(entityManager);
            jdbc.update("""
                    INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                        order_date, submitted_at, submitted_by, approved_at, approved_by, confirmed_at, confirmed_by,
                        active_revision_id, version, created_at)
                    SELECT ?, ?, 'CONFIRMED', s.id, ?, 'VND', CURRENT_DATE, NOW(), ?, NOW(), ?, NOW(), ?, ?, 0, NOW()
                      FROM procurement.suppliers s WHERE s.code = 'GOVIET'""",
                    po.id(), number, warehouse, submitter, approver, approver, po.revision());
            jdbc.update("""
                    INSERT INTO procurement.purchase_order_revisions (id, po_id, revision_no, kind, snapshot_header,
                        changed_by, version, created_at) VALUES (?, ?, 0, 'INITIAL', '{}'::jsonb, ?, 0, NOW())""",
                    po.revision(), po.id(), submitter);
        });
        return po;
    }

    /** A goods receipt in {@code status}, received {@code n} minutes ago; its number carries this test's tag. */
    private String receipt(Po po, UUID warehouse, String status, UUID clerk, int n) {
        String number = "GR-" + tag + "-" + n;
        boolean confirmed = !"DRAFT".equals(status) && !"CANCELLED".equals(status);
        Timestamp received = Timestamp.from(Instant.now().minusSeconds(60L * n));
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO procurement.goods_receipts (id, receipt_number, po_id, po_revision_id, warehouse_id, status,
                    received_at, received_by, confirmed_at, confirmed_by, closed_at, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NOW())""",
                Identifiers.newId(), number, po.id(), po.revision(), warehouse, status, received, clerk,
                confirmed ? received : null, confirmed ? clerk : null,
                "CLOSED".equals(status) ? Timestamp.from(Instant.now()) : null));
        return number;
    }

    private boolean granted(String role, String permission) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from identity.role_permission rp
                               join identity.app_role r on r.id = rp.role_id
                               join identity.permission p on p.id = rp.permission_id
                               where r.code = ? and p.code = ?)""", Boolean.class, role, permission));
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

    private String start(Role role, UUID user) throws Exception {
        String body = http.asRole(role, user, post(BASE)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private String result(String conversation, String callId) {
        return jdbc.queryForObject("select result from assistant.tool_call where conversation_id = ?::uuid "
                + "and call_id = ?", String.class, conversation, callId);
    }

    private String outcome(String conversation, String callId) {
        return jdbc.queryForObject("select outcome from assistant.tool_call where conversation_id = ?::uuid "
                + "and call_id = ?", String.class, conversation, callId);
    }

    private static MockHttpServletRequestBuilder ask(String conversation, String text) {
        return post(BASE + "/" + conversation + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":" + com.fasterxml.jackson.databind.node.TextNode.valueOf(text) + "}");
    }

    private static ObjectNode arguments() {
        return JsonNodeFactory.instance.objectNode();
    }

    private static String price(String customer, String sku, int quantity) {
        return arguments().put("customer", customer).put("sku", sku).put("quantity", quantity).toString();
    }

    private static String customer(String customer) {
        return arguments().put("customer", customer).toString();
    }

    private static String tasks(String status, int limit) {
        return arguments().put("status", status).put("limit", limit).toString();
    }

    private static String receipts(String status, String number, int limit) {
        return arguments().put("status", status).put("receiptNumber", number).put("limit", limit).toString();
    }

    private static ToolCall call(String id, String tool, String arguments) {
        return new ToolCall(id, tool, arguments);
    }

    private void script(LanguageModel.Reply first, LanguageModel.Reply... rest) {
        when(model.next(any())).thenReturn(first, rest);
    }

    private static LanguageModel.Reply toolUse(ToolCall... calls) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"tool_use\"}", null, List.of(calls)),
                LanguageModel.StopReason.TOOL_USE, new LanguageModel.Usage(100, 20, 0, 0), "scripted");
    }

    private static LanguageModel.Reply answer(String text) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"answer\"}", text, List.of()),
                LanguageModel.StopReason.END_TURN, new LanguageModel.Usage(120, 30, 100, 0), "scripted");
    }
}
