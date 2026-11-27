package com.stockflow.assistant;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.ProductionReceivables;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.SecuredProductionReceiptTest;
import com.stockflow.support.StaffFixtures;
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

import java.nio.charset.StandardCharsets;
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
 * The customer and queue tools of 29/10 (ROLE-6, ROLE-7), as the 2026-11 rescope left them (ADR-0010), end to end
 * with the real security chain, the real permission aspect, real migrations and a scripted model:
 * {@code getCustomerPrice}, {@code findFulfillmentTasks} and {@code findGoodsReceipts} answer what the screen each
 * mirrors would show the asker, and {@code findOrders} names a customer as {@code CustomerMatch} resolves one. That
 * screen's permission decides whether, the asker's warehouses decide which. And the floor roles may ask. The credit
 * and receivable tool is gone with the money ledger (an order owns no money of its own; kltn-docs 15), and its cases
 * with it; how a customer is named, which they also covered, is held here through {@code findOrders}.
 *
 * <p>Rows are inserted the way the flows leave them; how they got there is the owning modules' tests' business. Every
 * fixture is this test's own - two fresh warehouses, customers and receipt numbers carrying a tag - so another test's
 * rows never change what is counted here. A price is the customer's own, on a SKU the customer owns (kltn-docs 13
 * BR-04, {@code tg_customer_price_own_sku}); a goods receipt is against a real production order
 * ({@link ProductionReceivables}), which is why the class runs as a {@link SecuredProductionReceiptTest}.</p>
 */
@SecuredProductionReceiptTest
class AssistantCustomerAndQueueToolsIntegrationTest {

    private static final String BASE = "/api/v1/assistant/conversations";
    /** Pieces in one carton of the customer's own SKU: what a price's tiers count in cartons of. */
    private static final int PER_CARTON = 24;
    /** {@code ProductionReceivables}' producer, as it registers it. */
    private static final String PRODUCER = "Receiving test workshop";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ProductionReceivables receivables;

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
        // Fresh warehouses with a receiving area, as a production order is received at one.
        StaffFixtures places = new StaffFixtures(jdbc, tx, entityManager);
        StaffFixtures.Warehouse first = places.otherWarehouse();
        StaffFixtures.Warehouse second = places.otherWarehouse();
        here = first.id();
        herePrefix = first.prefix();
        away = second.id();
        awayPrefix = second.prefix();
    }

    // ------------------------------------------------------------------------------------------------- prices

    @Test
    @DisplayName("Sales gets the customer's own tiers on its own SKU, counted in cartons, and the tier a quantity falls "
            + "in; a fragment of the name offers the customers it matches, by name only")
    void pricesAsSalesQuotesThem() throws Exception {
        assertThat(granted("SALES_STAFF", "catalog-customer-prices:READ")).as("the seeded grant this case relies on")
                .isTrue();
        Customers customers = customers();
        String sku = ownPrice(customers.abc());
        UUID seller = staff("SALES_STAFF");
        script(toolUse(call("toolu_exact", "getCustomerPrice", price(customers.abcName(),
                                sku.toLowerCase(Locale.ROOT), 120)),
                        call("toolu_fragment", "getCustomerPrice", price("In Ly " + tag, sku, 0))),
                answer("Giá riêng của KH#1: từ 100 thùng 1.400 ₫/cái."), answer("KH#1 không có giá nào khác."));
        String conversation = start(Role.SALES_STAFF, seller);

        String shown = http.asRole(Role.SALES_STAFF, seller, ask(conversation, "Khách " + customers.abcName()
                + " lấy 120 thùng ly thì giá bao nhiêu?")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        String exact = result(conversation, "toolu_exact");
        assertThat(outcome(conversation, "toolu_exact")).isEqualTo("OK");
        assertThat(named(conversation, JsonPath.read(exact, "$.customerName"))).isEqualTo(customers.abcName());
        assertThat(exact).as("the model provider is sent the alias, never the name").doesNotContain(
                customers.abcName());
        assertThat(JsonPath.<String>read(shown, "$.data.answer")).as("the panel shows the name")
                .isEqualTo("Giá riêng của " + customers.abcName() + ": từ 100 thùng 1.400 ₫/cái.");
        String reopened = http.asRole(Role.SALES_STAFF, seller, get(BASE + "/" + conversation + "/messages"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(reopened).as("and so does the conversation reopened").contains(customers.abcName())
                .doesNotContain("KH#1");

        // Asked again by the name the panel showed: the question goes to the provider with the alias.
        http.asRole(Role.SALES_STAFF, seller, ask(conversation, "Khách " + customers.abcName()
                + " còn giá nào khác không?")).andExpect(status().isOk());
        String asked = jdbc.queryForObject("""
                select content::text from assistant.message where conversation_id = ?::uuid and role = 'USER'
                order by sequence desc limit 1""", String.class, conversation);
        assertThat(asked).contains("KH#1").doesNotContain(customers.abcName());
        assertThat(http.asRole(Role.SALES_STAFF, seller, get(BASE + "/" + conversation + "/messages")).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("Khách " + customers.abcName() + " còn giá nào khác không?").doesNotContain("KH#1");
        assertThat(JsonPath.<String>read(exact, "$.sku")).as("normalised as the screen does").isEqualTo(sku);
        assertThat(JsonPath.<String>read(exact, "$.measure")).as("the customer's own SKU is priced by the carton")
                .isEqualTo("CARTON");
        assertThat(JsonPath.<Integer>read(exact, "$.unitsPerCarton")).isEqualTo(PER_CARTON);
        assertThat(JsonPath.<String>read(exact, "$.customerStatus")).isEqualTo("ACTIVE");
        assertThat(JsonPath.<Integer>read(exact, "$.moq")).isEqualTo(50);
        assertThat(JsonPath.<List<Integer>>read(exact, "$.tiers[*].minQuantity")).containsExactly(50, 100);
        assertThat(JsonPath.<Integer>read(exact, "$.tierForQuantity.minQuantity")).isEqualTo(100);
        assertThat(JsonPath.<Number>read(exact, "$.tierForQuantity.unitPrice").doubleValue()).isEqualTo(1_400d);
        assertThat(JsonPath.<Boolean>read(exact, "$.belowMoq")).isFalse();
        assertThat(exact).doesNotContain(customers.taxCode(), "0903123456", "@example.vn",
                customers.abc().toString());

        String fragment = result(conversation, "toolu_fragment");
        assertThat(JsonPath.<List<String>>read(fragment, "$.candidates[*].customerName").stream()
                .map(alias -> named(conversation, alias)).toList())
                .containsExactlyInAnyOrder(customers.abcName(), customers.branchName());
        assertThat(JsonPath.<List<String>>read(fragment, "$.candidates[*].customerName"))
                .as("one customer keeps one alias in the conversation").contains("KH#1");
        assertThat(JsonPath.<List<Object>>read(fragment, "$.tiers")).isEmpty();
        assertThat(fragment).doesNotContain(customers.taxCode(), "0903123456", "@example.vn");
    }

    @Test
    @DisplayName("the Sales Manager gets a price by the tax code typed with a blank, and a quantity under the first "
            + "tier is below the customer's minimum, with no tier; another customer has no price, and none behind it")
    void pricesByTaxCodeAndBelowTheMinimum() throws Exception {
        assertThat(granted("SALES_MANAGER", "catalog-customer-prices:READ")).isTrue();
        Customers customers = customers();
        String sku = ownPrice(customers.abc());
        UUID manager = staff("SALES_MANAGER");
        String spaced = customers.taxCode().substring(0, 4) + " " + customers.taxCode().substring(4);
        script(toolUse(call("toolu_tax", "getCustomerPrice", price(spaced, sku, 30)),
                        call("toolu_other", "getCustomerPrice", price(customers.branchName(), sku, 30))),
                answer("30 thùng dưới mức tối thiểu."));
        String conversation = start(Role.SALES_MANAGER, manager);

        http.asRole(Role.SALES_MANAGER, manager, ask(conversation, "Giá ly cho khách MST " + spaced + ", 30 thùng?"))
                .andExpect(status().isOk());

        String byTaxCode = result(conversation, "toolu_tax");
        assertThat(named(conversation, JsonPath.read(byTaxCode, "$.customerName"))).isEqualTo(customers.abcName());
        assertThat(JsonPath.<Boolean>read(byTaxCode, "$.belowMoq")).as("30 is under the minimum of 50").isTrue();
        assertThat(JsonPath.<Map<String, Object>>read(byTaxCode, "$")).doesNotContainKey("tierForQuantity");
        assertThat(byTaxCode).doesNotContain(customers.taxCode());

        String other = result(conversation, "toolu_other");
        assertThat(named(conversation, JsonPath.read(other, "$.customerName"))).isEqualTo(customers.branchName());
        assertThat(JsonPath.<List<Object>>read(other, "$.tiers")).isEmpty();
        assertThat(JsonPath.<String>read(other, "$.note")).contains("no price of its own");
    }

    // ------------------------------------------------------------------------------------------------- customers

    @Test
    @DisplayName("the production coordinator, who reads orders but may not list customers, names one exactly - by the "
            + "tax code typed with a blank - and is shown no names for a fragment")
    void customersNamedExactlyByWhoMayNotListThem() throws Exception {
        // The Accountant lists customers since QD15 (V20261227000400); the coordinator reads orders and still does not.
        assertThat(granted("PRODUCTION_COORDINATOR", "sales-orders:READ")).isTrue();
        assertThat(granted("PRODUCTION_COORDINATOR", "customer-customers:READ"))
                .as("the coordinator does not search the customer list under the seeded grants").isFalse();
        Customers customers = customers();
        UUID coordinator = staff("PRODUCTION_COORDINATOR");
        String spaced = customers.taxCode().substring(0, 3) + "." + customers.taxCode().substring(3);
        script(toolUse(call("toolu_fragment", "findOrders", orders("In Ly " + tag)),
                        call("toolu_tax", "findOrders", orders(spaced))),
                answer("Cần tên đầy đủ hoặc mã số thuế."));
        String conversation = start(Role.PRODUCTION_COORDINATOR, coordinator);

        http.asRole(Role.PRODUCTION_COORDINATOR, coordinator, ask(conversation, "Đơn của khách In Ly " + tag + "?"))
                .andExpect(status().isOk());

        String fragment = result(conversation, "toolu_fragment");
        assertThat(outcome(conversation, "toolu_fragment")).isEqualTo("OK");
        assertThat(JsonPath.<List<Object>>read(fragment, "$.candidates")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(fragment, "$.orders")).isEmpty();
        assertThat(JsonPath.<String>read(fragment, "$.note")).contains("exactly");
        assertThat(JsonPath.<Map<String, Object>>read(fragment, "$")).as("nothing was counted").doesNotContainKey(
                "total");
        assertThat(fragment).doesNotContain(customers.abcName(), customers.branchName());

        String byTaxCode = result(conversation, "toolu_tax");
        assertThat(named(conversation, JsonPath.read(byTaxCode, "$.customer"))).isEqualTo(customers.abcName());
        assertThat(JsonPath.<Integer>read(byTaxCode, "$.total")).as("the customer has no order yet").isZero();
        assertThat(byTaxCode).doesNotContain(customers.taxCode());
    }

    @Test
    @DisplayName("a company registered in capitals, as Vietnamese companies often are, is found by its full name typed "
            + "in any case, under the databases' C locale too (Specs.contains folds every letter)")
    void capitalsAreFoundByTheirName() throws Exception {
        String registered = "CÔNG TY TNHH IN BAO BÌ ĐẠI PHÁT " + tag;
        customer(registered, null);
        UUID coordinator = staff("PRODUCTION_COORDINATOR");
        String typed = "Công ty TNHH In Bao Bì Đại Phát " + tag;
        script(toolUse(call("toolu_capitals", "findOrders", orders(typed))), answer("Khách chưa có đơn nào."));
        String conversation = start(Role.PRODUCTION_COORDINATOR, coordinator);

        http.asRole(Role.PRODUCTION_COORDINATOR, coordinator, ask(conversation, "Đơn của " + typed + "?"))
                .andExpect(status().isOk());

        assertThat(outcome(conversation, "toolu_capitals")).isEqualTo("OK");
        assertThat(named(conversation, JsonPath.read(result(conversation, "toolu_capitals"), "$.customer")))
                .as("found by the exact name, the only way a role that may not list customers names one").isEqualTo(registered);
        assertThat(jdbc.queryForObject("SELECT lower('ĐẠI PHÁT' COLLATE \"C\") <> 'đại phát'", Boolean.class))
                .as("what the C locale's lower() does to Vietnamese capitals, and why the search may not rely on it")
                .isTrue();
    }

    @Test
    @DisplayName("Sales, who may list customers, is offered the customers a fragment matches before any order")
    void customerCandidatesForSales() throws Exception {
        Customers customers = customers();
        UUID seller = staff("SALES_STAFF");
        script(toolUse(call("toolu_fragment", "findOrders", orders("In Ly " + tag))),
                answer("Có hai khách khớp, bạn muốn xem khách nào?"));
        String conversation = start(Role.SALES_STAFF, seller);

        http.asRole(Role.SALES_STAFF, seller, ask(conversation, "Khách In Ly " + tag + " có những đơn nào?"))
                .andExpect(status().isOk());

        String fragment = result(conversation, "toolu_fragment");
        assertThat(JsonPath.<List<String>>read(fragment, "$.candidates[*].customerName").stream()
                .map(alias -> named(conversation, alias)).toList())
                .containsExactlyInAnyOrder(customers.abcName(), customers.branchName());
        assertThat(JsonPath.<List<Object>>read(fragment, "$.orders")).isEmpty();
        assertThat(JsonPath.<Map<String, Object>>read(fragment, "$")).doesNotContainKey("total");
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
    @DisplayName("warehouse staff see the open receipts of their warehouse only, each against its production order; "
            + "the administrator, who is not limited, sees both warehouses")
    void goodsReceiptsOfTheirWarehouseOnly() throws Exception {
        assertThat(granted("WAREHOUSE_STAFF", "procurement-goods-receipts:READ")).isTrue();
        UUID clerk = tx.execute(s -> ReferenceRows.user(entityManager));
        ProductionReceivables.Receivable herePo = receivables.sent(here, 2, 50);
        ProductionReceivables.Receivable awayPo = receivables.sent(away, 2, 50);
        String counting = receipt(herePo, here, "DRAFT", clerk, 1);
        String toPutAway = receipt(herePo, here, "IN_PUTAWAY", clerk, 2);
        String closed = receipt(herePo, here, "CLOSED", clerk, 3);
        String cancelled = receipt(herePo, here, "CANCELLED", clerk, 4);
        String elsewhere = receipt(awayPo, away, "IN_PUTAWAY", clerk, 5);
        UUID receiving = staff("WAREHOUSE_STAFF", here);
        script(toolUse(call("toolu_open", "findGoodsReceipts", receipts("OPEN", tag, 50)),
                        call("toolu_putaway", "findGoodsReceipts", receipts("IN_PUTAWAY", tag, 50))),
                answer("Một phiếu đang chờ cất hàng."));
        String asked = start(Role.WAREHOUSE_STAFF, receiving);

        http.asRole(Role.WAREHOUSE_STAFF, receiving, ask(asked, "Phiếu nhập nào đang chờ cất hàng?"))
                .andExpect(status().isOk());

        String open = result(asked, "toolu_open");
        assertThat(JsonPath.<List<String>>read(open, "$.receipts[*].receiptNumber"))
                .containsExactlyInAnyOrder(counting, toPutAway);
        assertThat(JsonPath.<Integer>read(open, "$.total")).isEqualTo(2);
        assertThat(JsonPath.<List<String>>read(open, "$.receipts[*].warehouseCode")).containsOnly(herePrefix);
        assertThat(JsonPath.<List<String>>read(open, "$.receipts[*].receiptType")).containsOnly("PRODUCTION");
        assertThat(JsonPath.<List<String>>read(open, "$.receipts[*].productionOrderCode"))
                .containsOnly(herePo.productionCode());
        assertThat(JsonPath.<List<String>>read(open, "$.receipts[*].producerName")).containsOnly(PRODUCER);
        assertThat(open).doesNotContain(closed, cancelled, elsewhere, awayPrefix, clerk.toString(),
                awayPo.productionCode());
        assertThat(JsonPath.<List<String>>read(result(asked, "toolu_putaway"), "$.receipts[*].receiptNumber"))
                .containsExactly(toPutAway);

        UUID admin = staff("SYSTEM_ADMIN");
        script(toolUse(call("toolu_any", "findGoodsReceipts", receipts("ANY", tag, 50))), answer("Có 5 phiếu."));
        String overseen = start(Role.SYSTEM_ADMIN, admin);
        http.asRole(Role.SYSTEM_ADMIN, admin, ask(overseen, "Các phiếu nhập " + tag + "?"))
                .andExpect(status().isOk());

        String any = result(overseen, "toolu_any");
        assertThat(JsonPath.<Integer>read(any, "$.total")).isEqualTo(5);
        assertThat(JsonPath.<List<String>>read(any, "$.receipts[*].warehouseCode"))
                .containsOnly(herePrefix, awayPrefix);
    }

    // ------------------------------------------------------------------------------------------------- refusals

    @Test
    @DisplayName("a role without a screen's permission is DENIED, told which permission, and sees no row")
    void deniedWithoutTheScreensPermission() throws Exception {
        assertThat(granted("ACCOUNTANT", "fulfillment-pick-lists:READ")).isFalse();
        assertThat(granted("ACCOUNTANT", "catalog-customer-prices:READ")).isFalse();
        // The Accountant reads goods receipts since V20261222000100 (it signs the return receipt's voucher); Sales do not.
        assertThat(granted("SALES_STAFF", "procurement-goods-receipts:READ")).isFalse();
        assertThat(granted("WAREHOUSE_MANAGER", "catalog-customer-prices:READ")).isFalse();
        Customers customers = customers();
        String sku = ownPrice(customers.abc());
        UUID accountant = staff("ACCOUNTANT");
        script(toolUse(call("toolu_tasks", "findFulfillmentTasks", tasks("ANY", 50)),
                        call("toolu_price", "getCustomerPrice", price(customers.abcName(), sku, 100))),
                answer("Bạn chưa có quyền xem các dữ liệu này."));
        String asked = start(Role.ACCOUNTANT, accountant);
        http.asRole(Role.ACCOUNTANT, accountant, ask(asked, "Kho còn task nào, giá ly bao nhiêu?"))
                .andExpect(status().isOk());

        assertThat(outcome(asked, "toolu_tasks")).isEqualTo("DENIED");
        assertThat(result(asked, "toolu_tasks")).startsWith("NOT_PERMITTED").contains("fulfillment-pick-lists:READ");
        assertThat(outcome(asked, "toolu_price")).isEqualTo("DENIED");
        assertThat(result(asked, "toolu_price")).contains("catalog-customer-prices:READ")
                .doesNotContain("1400", customers.abcName());

        UUID seller = staff("SALES_STAFF");
        script(toolUse(call("toolu_receipts", "findGoodsReceipts", receipts("ANY", "", 50))),
                answer("Bạn chưa có quyền xem phiếu nhập."));
        String sold = start(Role.SALES_STAFF, seller);
        http.asRole(Role.SALES_STAFF, seller, ask(sold, "Kho còn phiếu nhập nào?")).andExpect(status().isOk());

        assertThat(outcome(sold, "toolu_receipts")).isEqualTo("DENIED");
        assertThat(result(sold, "toolu_receipts")).contains("procurement-goods-receipts:READ");

        UUID manager = staff("WAREHOUSE_MANAGER", here);
        script(toolUse(call("toolu_manager_price", "getCustomerPrice", price(customers.abcName(), sku, 100))),
                answer("Bạn chưa có quyền xem giá."));
        String managed = start(Role.WAREHOUSE_MANAGER, manager);
        http.asRole(Role.WAREHOUSE_MANAGER, manager, ask(managed, "Khách " + customers.abcName() + " mua giá nào?"))
                .andExpect(status().isOk());

        assertThat(outcome(managed, "toolu_manager_price")).isEqualTo("DENIED");
        assertThat(result(managed, "toolu_manager_price")).contains("catalog-customer-prices:READ")
                .doesNotContain(customers.abcName());
    }

    // ------------------------------------------------------------------------------------------------- AQ-02

    @Test
    @DisplayName("warehouse staff (V20270106000500) and the production coordinator (V20270106000200) may open the "
            + "assistant; a portal customer still may not")
    void theFloorRolesMayAsk() throws Exception {
        for (String role : List.of("WAREHOUSE_STAFF", "PRODUCTION_COORDINATOR")) {
            for (String action : List.of("VIEW_PAGE", "READ", "CREATE")) {
                assertThat(granted(role, "assistant-conversations:" + action)).as("%s holds %s", role, action)
                        .isTrue();
            }
            UUID user = "WAREHOUSE_STAFF".equals(role) ? staff(role, here) : staff(role);
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

    /**
     * A SKU of the customer's own, in cartons of {@value #PER_CARTON} (as a confirmed design leaves it), and the
     * customer's price for it: from 50 cartons 1 500 ₫ a piece, from 100 cartons 1 400 ₫. There is no other price
     * behind it (kltn-docs 13 BR-04).
     *
     * @return the SKU
     */
    private String ownPrice(UUID customer) {
        String sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, PER_CARTON).sku();
        UUID price = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO catalog.customer_price (id, customer_id, sku, currency, version, created_at)
                    VALUES (?, ?, ?, 'VND', 0, NOW())""", price, customer, sku);
            jdbc.update("""
                    INSERT INTO catalog.customer_price_tier (id, customer_price_id, min_quantity, unit_price, version,
                                                             created_at)
                    VALUES (?, ?, 50, 1500, 0, NOW()), (?, ?, 100, 1400, 0, NOW())""",
                    Identifiers.newId(), price, Identifiers.newId(), price);
        });
        return sku;
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
                    VALUES (?, ?, ?, 1, 1000, 'VND')""", Identifiers.newId(), order, DemoData.SKU_STOCKED);
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

    /**
     * A goods receipt against {@code po} in {@code status}, received {@code n} minutes ago; its number carries this
     * test's tag. Written as rows the way receiving leaves them, registered with its production order as the database
     * checks at commit ({@code tg_goods_receipts_production_registered}): opened as a DRAFT, then, in a transaction of
 * its own, settled - confirmed
     * for IN_PUTAWAY and CLOSED, cancelled for CANCELLED. No lines: the list shows none, and counting cartons is
     * {@code ProductionReceiptIntegrationTest}'s.
     */
    private String receipt(ProductionReceivables.Receivable po, UUID warehouse, String status, UUID clerk, int n) {
        String number = "GR-" + tag + "-" + n;
        UUID id = Identifiers.newId();
        boolean confirmed = !"DRAFT".equals(status) && !"CANCELLED".equals(status);
        Timestamp received = Timestamp.from(Instant.now().minusSeconds(60L * n));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, production_order_id,
                        warehouse_id, status, received_at, received_by, version, created_at)
                    VALUES (?, ?, 'PRODUCTION', ?, ?, 'DRAFT', ?, ?, 0, NOW())""",
                    id, number, po.productionOrderId(), warehouse, received, clerk);
            jdbc.update("""
                    INSERT INTO ordering.production_order_receipt (production_order_id, goods_receipt_id,
                        receipt_number, status, opened_at)
                    VALUES (?, ?, ?, 'DRAFT', ?)""", po.productionOrderId(), id, number, received);
        });
        // Settled in a transaction of its own: the registration check is deferred to commit and judges each row
        // event by the status it wrote, so a DRAFT inserted and settled in one transaction fails its own insert's check.
        tx.executeWithoutResult(s -> {
            if (!"DRAFT".equals(status)) {
                jdbc.update("""
                        UPDATE procurement.goods_receipts SET status = ?, confirmed_at = ?, confirmed_by = ?,
                            closed_at = ? WHERE id = ?""", status, confirmed ? received : null,
                        confirmed ? clerk : null, "CLOSED".equals(status) ? Timestamp.from(Instant.now()) : null, id);
                jdbc.update("""
                        UPDATE ordering.production_order_receipt SET status = ?, settled_at = NOW()
                        WHERE goods_receipt_id = ?""", confirmed ? "CONFIRMED" : "CANCELLED", id);
            }
        });
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

    /**
     * The customer an alias of the conversation stands for, by its name (ADR-0015): what a tool result sends the model
     * provider instead.
     */
    private String named(String conversation, String alias) {
        assertThat(alias).as("the model provider is sent an alias, not a name").matches("KH#\\d+");
        return jdbc.queryForObject("""
                select c.full_name from assistant.customer_alias a join customer.customer c on c.id = a.customer_id
                where a.conversation_id = ?::uuid and a.alias_number = ?""", String.class, conversation,
                Integer.parseInt(alias.substring(3)));
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

    /** Every order of the customer the text names, whatever its status or day. */
    private static String orders(String customer) {
        return arguments().put("status", "ANY").put("holdReason", "ANY").put("placedFrom", "").put("placedTo", "")
                .put("customer", customer).put("limit", 50).toString();
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
