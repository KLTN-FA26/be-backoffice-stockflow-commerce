package com.stockflow.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.SalesOwnerChangeReason;
import com.stockflow.customer.api.AssignSalesOwnerCommand;
import com.stockflow.customer.api.SalesOwnership;
import com.stockflow.customer.internal.service.SalesOwnerTransferService;
import com.stockflow.identity.internal.service.StaffCoverService;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The sales-owner endpoints over HTTP with security on (sales-owner plan BR-S01..S14, §10.1, §10.4; API contract
 * "BE-A" and "Additions from back office BO-1"): the owner card and its change, the history, the customer list's owner
 * columns and filters, bulk and temporary transfers with their return, the salespeople pickers, the assignment
 * settings, the lock dialog's summary, the duplicate warning and the covers. Each test makes its own accounts and
 * customers; the database is shared.
 */
@SecuredIntegrationTest
class SalesOwnerEndpointsIntegrationTest {

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired SalesOwnership ownership;
    @Autowired SalesOwnerTransferService transfers;
    @Autowired StaffCoverService covers;
    @Autowired Clock clock;

    private SecuredMvc http;
    private StaffFixtures staff;
    private UUID manager;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mvc, decoder);
        staff = new StaffFixtures(jdbc, tx, entityManager);
        manager = staff.staff("SALES_MANAGER");
    }

    // -------------------------------------------------------------------------------------------------------------
    // Owner card, change, history
    // -------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BR-S01/S03 / case 15: a manager changes the owner with a reason and the version; the card and history follow")
    void aManagerChangesTheOwner() throws Exception {
        UUID a = staff.staff("SALES_STAFF");
        UUID b = staff.staff("SALES_STAFF");
        UUID customer = customerOwnedBy(a);
        long version = version(customer);

        as(Role.SALES_MANAGER, manager, put("/api/v1/customers/" + customer + "/sales-owner"), Map.of(
                "salesOwnerId", b, "reason", "WORKLOAD", "note", "balance", "notifyCustomer", true, "version", version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.salesOwnerId").value(b.toString()))
                .andExpect(jsonPath("$.data.state").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.active").value(true));

        as(Role.SALES_MANAGER, manager, put("/api/v1/customers/" + customer + "/sales-owner"), Map.of(
                "salesOwnerId", a, "reason", "WORKLOAD", "version", version))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SALES_OWNER_VERSION_CONFLICT"));

        JsonNode history = data(as(Role.SALES_STAFF, a, get("/api/v1/customers/" + customer + "/sales-owner-history"),
                null).andExpect(status().isOk()));
        assertThat(history.at("/totalElements").asLong()).isEqualTo(2);
        assertThat(history.at("/items/0/reason").asText()).isEqualTo("WORKLOAD");
        assertThat(history.at("/items/0/fromUserId").asText()).isEqualTo(a.toString());
        assertThat(history.at("/items/0/toUserId").asText()).isEqualTo(b.toString());
        assertThat(history.at("/items/0/changedById").asText()).isEqualTo(manager.toString());
        assertThat(history.at("/items/0/changedByName").asText()).isNotBlank();
    }

    @Test
    @DisplayName("BR-S01 / BR-S03 / BR-S04: a Sales cannot change an owner (403); no reason is 400; a manager as owner is 422")
    void changingTheOwnerIsRefused() throws Exception {
        UUID a = staff.staff("SALES_STAFF");
        UUID customer = customerOwnedBy(a);
        long version = version(customer);
        String path = "/api/v1/customers/" + customer + "/sales-owner";

        as(Role.SALES_STAFF, a, put(path), Map.of("salesOwnerId", a, "reason", "OTHER", "note", "mine",
                "version", version)).andExpect(status().isForbidden());
        as(Role.SALES_MANAGER, manager, put(path), Map.of("salesOwnerId", staff.staff("SALES_STAFF"),
                "version", version)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("SALES_OWNER_CHANGE_REASON_REQUIRED"));
        as(Role.SALES_MANAGER, manager, put(path), Map.of("salesOwnerId", manager, "reason", "WORKLOAD",
                "version", version)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SALES_OWNER_NOT_ASSIGNABLE"));
        as(Role.WAREHOUSE_STAFF, staff.staff("WAREHOUSE_STAFF"), get(path), null).andExpect(status().isForbidden());
        assertThat(owner(customer)).isEqualTo(a + ":ASSIGNED");
    }

    @Test
    @DisplayName("BR-S07: the owner card names the cover in force today")
    void theCardNamesTheCover() throws Exception {
        UUID a = staff.staff("SALES_STAFF");
        UUID standIn = staff.staff("SALES_STAFF");
        UUID customer = customerOwnedBy(a);
        covers.arrange(new StaffCoverService.ArrangeCover(a, standIn, today(), today().plusDays(3), "leave"), a,
                false);

        as(Role.ACCOUNTANT, staff.staff("ACCOUNTANT"), get("/api/v1/customers/" + customer + "/sales-owner"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.salesOwnerId").value(a.toString()))
                .andExpect(jsonPath("$.data.cover.userId").value(standIn.toString()))
                .andExpect(jsonPath("$.data.cover.until").value(today().plusDays(3).toString()));
    }

    // -------------------------------------------------------------------------------------------------------------
    // Customer list (BR-S11, D41)
    // -------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BR-S11 / D41: list rows carry the owner and taxCodeMissing; mine, salesOwnerState and taxCodeMissing filter")
    void theListShowsAndFiltersTheOwner() throws Exception {
        UUID a = staff.staff("SALES_STAFF");
        UUID mine = customerOwnedBy(a);
        UUID unassigned = customer();

        JsonNode rows = data(as(Role.SALES_STAFF, a, get("/api/v1/customers").param("mine", "true")
                .param("size", "50"), null).andExpect(status().isOk()));
        assertThat(rows.at("/totalElements").asLong()).isEqualTo(1);
        assertThat(rows.at("/items/0/customerId").asText()).isEqualTo(mine.toString());
        assertThat(rows.at("/items/0/salesOwnerId").asText()).isEqualTo(a.toString());
        assertThat(rows.at("/items/0/salesOwnerName").asText()).isNotBlank();
        assertThat(rows.at("/items/0/salesOwnerActive").asBoolean()).isTrue();
        assertThat(rows.at("/items/0/salesOwnerState").asText()).isEqualTo("ASSIGNED");
        assertThat(rows.at("/items/0/salesOwnerSince").asText()).isNotBlank();
        assertThat(rows.at("/items/0/taxCodeMissing").asBoolean()).as("ReferenceRows makes no tax code").isTrue();

        JsonNode byOwner = data(as(Role.SALES_MANAGER, manager, get("/api/v1/customers")
                .param("salesOwnerId", a.toString()).param("salesOwnerState", "ASSIGNED"), null));
        assertThat(byOwner.at("/totalElements").asLong()).isEqualTo(1);

        write("UPDATE customer.customer SET tax_code = ? WHERE id = ?", taxCode(), mine);
        assertThat(data(as(Role.SALES_STAFF, a, get("/api/v1/customers").param("mine", "true")
                .param("taxCodeMissing", "true"), null)).at("/totalElements").asLong()).isZero();

        for (UUID each : List.of(unassigned, mine)) {
            String email = jdbc.queryForObject("SELECT email FROM customer.customer WHERE id = ?", String.class, each);
            JsonNode waiting = data(as(Role.SALES_MANAGER, manager, get("/api/v1/customers")
                    .param("salesOwnerState", "UNASSIGNED").param("q", email), null));
            assertThat(waiting.at("/totalElements").asLong()).as(email).isEqualTo(each.equals(unassigned) ? 1 : 0);
        }

        as(Role.SALES_MANAGER, manager, get("/api/v1/customers").param("salesOwnerState", "NOPE"), null)
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------------------------------------------
    // Bulk transfers (BR-S09) and temporary ones (§10.1)
    // -------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BR-S09: a bulk transfer spreads evenly, skips what is not the source's, and a retry moves nothing twice")
    void aBulkTransferIsIdempotent() throws Exception {
        UUID leaving = staff.staff("SALES_STAFF");
        UUID b = staff.staff("SALES_STAFF");
        UUID c = staff.staff("SALES_STAFF");
        List<UUID> book = List.of(customerOwnedBy(leaving), customerOwnedBy(leaving), customerOwnedBy(leaving),
                customerOwnedBy(leaving));
        UUID someoneElses = customerOwnedBy(b);
        UUID unknown = UUID.randomUUID();
        List<UUID> named = new ArrayList<>(book);
        named.add(someoneElses);
        named.add(unknown);
        Map<String, Object> body = transferBody(leaving, List.of(b, c), named, "STAFF_LEFT", false);

        JsonNode first = data(as(Role.SALES_MANAGER, manager, post("/api/v1/customers/sales-owner-transfers"), body)
                .andExpect(status().isOk()));
        assertThat(first.at("/moved").asInt()).isEqualTo(4);
        Map<String, String> skipped = skipped(first);
        assertThat(skipped).containsEntry(someoneElses.toString(), "NOT_OWNED_BY_SOURCE")
                .containsEntry(unknown.toString(), "CUSTOMER_NOT_FOUND").hasSize(2);
        long toB = book.stream().filter(id -> owner(id).equals(b + ":ASSIGNED")).count();
        long toC = book.stream().filter(id -> owner(id).equals(c + ":ASSIGNED")).count();
        assertThat(toB).isEqualTo(2);
        assertThat(toC).isEqualTo(2);
        long rows = historyRows(book);

        JsonNode retried = data(as(Role.SALES_MANAGER, manager, post("/api/v1/customers/sales-owner-transfers"), body)
                .andExpect(status().isOk()));
        assertThat(retried.at("/batchId").asText()).isEqualTo(first.at("/batchId").asText());
        assertThat(retried.at("/moved").asInt()).isEqualTo(4);
        assertThat(skipped(retried)).isEqualTo(skipped);
        assertThat(historyRows(book)).as("nothing moved twice").isEqualTo(rows);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer.customer_sales_owner_history WHERE batch_id = ?",
                Long.class, UUID.fromString(first.at("/batchId").asText()))).isEqualTo(4);
    }

    @Test
    @DisplayName("BR-S09: a Sales may not transfer (403); a target who is not an active Sales is 422 and nothing moves")
    void aTransferIsRefused() throws Exception {
        UUID leaving = staff.staff("SALES_STAFF");
        UUID customer = customerOwnedBy(leaving);
        as(Role.SALES_STAFF, leaving, post("/api/v1/customers/sales-owner-transfers"),
                transferBody(leaving, List.of(staff.staff("SALES_STAFF")), null, "WORKLOAD", false))
                .andExpect(status().isForbidden());
        as(Role.SALES_MANAGER, manager, post("/api/v1/customers/sales-owner-transfers"),
                transferBody(leaving, List.of(manager), null, "WORKLOAD", false))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SALES_OWNER_NOT_ASSIGNABLE"));
        as(Role.SALES_MANAGER, manager, post("/api/v1/customers/sales-owner-transfers"),
                transferBody(leaving, List.of(staff.staff("SALES_STAFF")), null, "WORKLOAD", true))
                .andExpect(status().isBadRequest());
        assertThat(owner(customer)).isEqualTo(leaving + ":ASSIGNED");
    }

    @Test
    @DisplayName("Case 9: a customer changed while the batch runs is skipped OWNER_CHANGED, not overwritten")
    void aCustomerChangedWhileTheBatchRunsIsSkipped() throws Exception {
        UUID leaving = staff.staff("SALES_STAFF");
        UUID target = staff.staff("SALES_STAFF");
        UUID meanwhile = staff.staff("SALES_STAFF");
        List<UUID> book = new ArrayList<>(List.of(customerOwnedBy(leaving), customerOwnedBy(leaving)));
        book.sort(null);
        UUID first = book.get(0);
        UUID second = book.get(1);

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch change = new CountDownLatch(1);
        CompletableFuture<Void> other = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM customer.customer WHERE id = ? FOR UPDATE", UUID.class, second);
            held.countDown();
            await(change);
            jdbc.update("UPDATE customer.customer SET sales_owner_id = ?, sales_owner_since = NOW(), "
                    + "version = version + 1 WHERE id = ?", meanwhile, second);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<SalesOwnerTransferService.Result> batch = CompletableFuture.supplyAsync(() ->
                transfers.transfer(new SalesOwnerTransferService.Transfer(UUID.randomUUID(), leaving, List.of(target),
                        book, SalesOwnerChangeReason.STAFF_LEFT, null, false, false), manager));
        Thread.sleep(1500);
        assertThat(batch).as("the batch waits on the customer's row lock").isNotDone();
        change.countDown();
        other.get(10, TimeUnit.SECONDS);

        SalesOwnerTransferService.Result result = batch.get(20, TimeUnit.SECONDS);
        assertThat(result.moved()).isEqualTo(1);
        assertThat(result.skipped()).containsExactly(new SalesOwnerTransferService.Skipped(second,
                com.stockflow.customer.internal.domain.SalesOwnerTransfers.Skip.OWNER_CHANGED));
        assertThat(owner(first)).isEqualTo(target + ":ASSIGNED");
        assertThat(owner(second)).as("the change made meanwhile stands").isEqualTo(meanwhile + ":ASSIGNED");
    }

    @Test
    @DisplayName("§10.1: a temporary batch is listed, returned in one step (once), and a customer moved on since is skipped")
    void aTemporaryBatchIsReturned() throws Exception {
        UUID away = staff.staff("SALES_STAFF");
        UUID holder = staff.staff("SALES_STAFF");
        UUID elsewhere = staff.staff("SALES_STAFF");
        UUID kept = customerOwnedBy(away);
        UUID movedOn = customerOwnedBy(away);
        JsonNode batch = data(as(Role.SALES_MANAGER, manager, post("/api/v1/customers/sales-owner-transfers"),
                transferBody(away, List.of(holder), null, "TEMPORARY_LEAVE", true)).andExpect(status().isOk()));
        String batchId = batch.at("/batchId").asText();
        assertThat(batch.at("/moved").asInt()).isEqualTo(2);
        ownership.assign(new AssignSalesOwnerCommand(movedOn, elsewhere, SalesOwnerChangeReason.WORKLOAD, null, false,
                version(movedOn), manager));

        JsonNode listed = rowOf(data(as(Role.SALES_MANAGER, manager,
                get("/api/v1/customers/sales-owner-transfers").param("temporary", "true"), null)
                .andExpect(status().isOk())), batchId);
        assertThat(listed.at("/temporary").asBoolean()).isTrue();
        assertThat(listed.at("/moved").asInt()).isEqualTo(2);
        assertThat(listed.at("/stillHeld").asInt()).isEqualTo(1);
        assertThat(listed.at("/fromUserId").asText()).isEqualTo(away.toString());
        assertThat(listed.at("/toNames").size()).isEqualTo(1);
        assertThat(listed.at("/returnedAt").isMissingNode()).as("null fields are left out").isTrue();

        JsonNode returned = data(as(Role.SALES_MANAGER, manager,
                post("/api/v1/customers/sales-owner-transfers/" + batchId + "/return"), null)
                .andExpect(status().isOk()));
        assertThat(returned.at("/moved").asInt()).isEqualTo(1);
        assertThat(skipped(returned)).containsExactly(Map.entry(movedOn.toString(), "REASSIGNED_SINCE"));
        assertThat(owner(kept)).isEqualTo(away + ":ASSIGNED");
        assertThat(owner(movedOn)).isEqualTo(elsewhere + ":ASSIGNED");
        assertThat(jdbc.queryForObject("""
                SELECT reason FROM customer.customer_sales_owner_history WHERE customer_id = ?
                 ORDER BY changed_at DESC, id DESC LIMIT 1""", String.class, kept)).isEqualTo("RETURN_FROM_LEAVE");

        JsonNode again = data(as(Role.SALES_MANAGER, manager,
                post("/api/v1/customers/sales-owner-transfers/" + batchId + "/return"), null)
                .andExpect(status().isOk()));
        assertThat(again.at("/batchId").asText()).isEqualTo(returned.at("/batchId").asText());
        assertThat(rowOf(data(as(Role.SALES_MANAGER, manager,
                get("/api/v1/customers/sales-owner-transfers").param("temporary", "true"), null)), batchId)
                .at("/returnedAt").isMissingNode()).isFalse();

        String permanent = data(as(Role.SALES_MANAGER, manager, post("/api/v1/customers/sales-owner-transfers"),
                transferBody(away, List.of(holder), null, "WORKLOAD", false))).at("/batchId").asText();
        as(Role.SALES_MANAGER, manager, post("/api/v1/customers/sales-owner-transfers/" + permanent + "/return"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SALES_OWNER_TRANSFER_NOT_RETURNABLE"));
        as(Role.SALES_MANAGER, manager, post("/api/v1/customers/sales-owner-transfers/" + UUID.randomUUID()
                + "/return"), null).andExpect(status().isNotFound());
        as(Role.SALES_STAFF, holder, get("/api/v1/customers/sales-owner-transfers"), null)
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------------------------------------------
    // Pickers, settings, lock dialog, duplicates
    // -------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("D42 / D13: the pickers list active SALES_STAFF only, with paused and on-leave; settings are the manager's")
    void pickersAndAssignmentSettings() throws Exception {
        UUID agent = staff.staff("SALES_STAFF");
        UUID standIn = staff.staff("SALES_STAFF");
        UUID locked = staff.staff("SALES_STAFF");
        staff.setStatus(locked, "LOCKED");
        covers.arrange(new StaffCoverService.ArrangeCover(agent, standIn, today(), today().plusDays(1), null), agent,
                false);
        String settings = "/api/v1/sales/staff/" + agent + "/assignment-settings";

        as(Role.SALES_MANAGER, manager, get(settings), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paused").value(false))
                .andExpect(jsonPath("$.data.version").doesNotExist());
        as(Role.SALES_MANAGER, manager, put(settings), Map.of("paused", true, "capacityPerMonth", 5))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.paused").value(true))
                .andExpect(jsonPath("$.data.capacityPerMonth").value(5))
                .andExpect(jsonPath("$.data.version").value(0));
        as(Role.SALES_MANAGER, manager, put(settings), Map.of("paused", true, "version", 7))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK"));
        as(Role.SALES_MANAGER, manager, put(settings), Map.of("paused", true, "capacityPerMonth", 5000))
                .andExpect(status().isBadRequest());
        as(Role.SALES_MANAGER, manager, put("/api/v1/sales/staff/" + manager + "/assignment-settings"),
                Map.of("paused", true)).andExpect(status().isUnprocessableEntity());
        as(Role.SALES_STAFF, agent, get(settings), null).andExpect(status().isForbidden());
        as(Role.SALES_STAFF, agent, put(settings), Map.of("paused", false)).andExpect(status().isForbidden());

        JsonNode agents = data(as(Role.SALES_STAFF, agent, get("/api/v1/customers/sales-agents"), null)
                .andExpect(status().isOk()));
        Map<String, JsonNode> byId = new HashMap<>();
        agents.forEach(row -> byId.put(row.at("/userId").asText(), row));
        assertThat(byId).containsKeys(agent.toString(), standIn.toString())
                .doesNotContainKeys(manager.toString(), locked.toString());
        assertThat(byId.get(agent.toString()).at("/paused").asBoolean()).isTrue();
        assertThat(byId.get(agent.toString()).at("/onLeave").asBoolean()).isTrue();
        assertThat(byId.get(agent.toString()).at("/coverUntil").asText()).isEqualTo(today().plusDays(1).toString());
        assertThat(byId.get(standIn.toString()).at("/onLeave").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("BR-S08 lock dialog: the handover summary counts the customers and open leads; needs identity-users:READ")
    void theHandoverSummaryCountsWhatIsLeft() throws Exception {
        UUID leaving = staff.staff("SALES_STAFF");
        customerOwnedBy(leaving);
        customerOwnedBy(leaving);
        UUID lead = UUID.randomUUID();
        write("""
                INSERT INTO customer.lead (id, company_name, contact_name, phone, consent_at, submitted_at, status,
                                           assigned_to, assigned_at, created_at)
                VALUES (?, 'Lead Co', 'Contact', '+84901234567', NOW(), NOW(), 'ASSIGNED', ?, NOW(), NOW())""",
                lead, leaving);
        UUID admin = staff.staff("SYSTEM_ADMIN");

        as(Role.SYSTEM_ADMIN, admin, get("/api/v1/identity/users/" + leaving + "/handover-summary"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customers").value(2))
                .andExpect(jsonPath("$.data.openLeads").value(1))
                .andExpect(jsonPath("$.data.openConversations").doesNotExist());
        as(Role.SYSTEM_ADMIN, admin, get("/api/v1/identity/users/" + UUID.randomUUID() + "/handover-summary"), null)
                .andExpect(status().isNotFound());
        as(Role.SALES_STAFF, leaving, get("/api/v1/identity/users/" + leaving + "/handover-summary"), null)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("BR-S14 / case 16: duplicates match tax code, phone, e-mail and the folded name, naming the owner")
    void duplicatesAreFoundWithTheirOwner() throws Exception {
        UUID a = staff.staff("SALES_STAFF");
        UUID customer = customerOwnedBy(a);
        String code = taxCode();
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String phone = "+849" + code.substring(2);
        write("UPDATE customer.customer SET tax_code = ?, phone = ?, full_name = ? WHERE id = ?",
                code, phone, "Công ty TNHH Bao Bì " + marker, customer);
        String email = jdbc.queryForObject("SELECT email FROM customer.customer WHERE id = ?", String.class, customer);

        JsonNode byCode = data(as(Role.SALES_STAFF, a, get("/api/v1/customers/duplicates").param("taxCode", code),
                null).andExpect(status().isOk()));
        assertThat(byCode.size()).isEqualTo(1);
        assertThat(byCode.at("/0/customerId").asText()).isEqualTo(customer.toString());
        assertThat(byCode.at("/0/matchedOn/0").asText()).isEqualTo("TAX_CODE");
        assertThat(byCode.at("/0/salesOwnerName").asText()).isNotBlank();

        JsonNode byName = data(as(Role.SALES_STAFF, a, get("/api/v1/customers/duplicates")
                .param("name", "CTY TNHH bao bi " + marker.toUpperCase()), null));
        assertThat(byName.size()).isEqualTo(1);
        assertThat(byName.at("/0/matchedOn/0").asText()).isEqualTo("NAME");

        JsonNode byContact = data(as(Role.SALES_STAFF, a, get("/api/v1/customers/duplicates")
                .param("phone", "09" + phone.substring(4)).param("email", email.toUpperCase()), null));
        List<String> matched = new ArrayList<>();
        byContact.forEach(row -> {
            if (row.at("/customerId").asText().equals(customer.toString())) {
                row.at("/matchedOn").forEach(on -> matched.add(on.asText()));
            }
        });
        assertThat(matched).contains("PHONE", "EMAIL");
        assertThat(data(as(Role.SALES_STAFF, a, get("/api/v1/customers/duplicates"), null)).size()).isZero();

        as(Role.CUSTOMER, portalAccount(), get("/api/v1/customers/duplicates").param("taxCode", code), null)
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------------------------------------------
    // Covers (BR-S07)
    // -------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BR-S07 / D7: a Sales arranges and cancels their own cover; another's is 403; >60 days 422; self 422; overlap 409")
    void covers() throws Exception {
        UUID away = staff.staff("SALES_STAFF");
        UUID standIn = staff.staff("SALES_STAFF");
        UUID other = staff.staff("SALES_STAFF");
        LocalDate from = today().plusDays(1);

        JsonNode arranged = data(as(Role.SALES_STAFF, away, post("/api/v1/staff-covers"), Map.of("userId", away,
                "coverUserId", standIn, "from", from.toString(), "to", from.plusDays(59).toString(), "reason", "leave"))
                .andExpect(status().isCreated()));
        assertThat(arranged.at("/status").asText()).isEqualTo("UPCOMING");
        assertThat(arranged.at("/userName").asText()).isNotBlank();
        assertThat(arranged.at("/coverUserName").asText()).isNotBlank();
        String coverId = arranged.at("/id").asText();

        as(Role.SALES_STAFF, other, post("/api/v1/staff-covers"), Map.of("userId", away, "coverUserId", other,
                "from", from.plusDays(70).toString(), "to", from.plusDays(71).toString()))
                .andExpect(status().isForbidden());
        as(Role.SALES_STAFF, other, post("/api/v1/staff-covers"), Map.of("userId", other, "coverUserId", standIn,
                "from", from.toString(), "to", from.plusDays(60).toString()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errorCode").value("STAFF_COVER_TOO_LONG"));
        as(Role.SALES_STAFF, other, post("/api/v1/staff-covers"), Map.of("userId", other, "coverUserId", other,
                "from", from.toString(), "to", from.toString()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errorCode").value("STAFF_COVER_SELF"));
        as(Role.SALES_MANAGER, manager, post("/api/v1/staff-covers"), Map.of("userId", away, "coverUserId", other,
                "from", from.plusDays(5).toString(), "to", from.plusDays(6).toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("STAFF_COVER_OVERLAP"));

        JsonNode listed = data(as(Role.SALES_STAFF, standIn, get("/api/v1/staff-covers").param("userId", away.toString())
                .param("active", "true"), null).andExpect(status().isOk()));
        assertThat(listed.size()).isEqualTo(1);

        as(Role.SALES_STAFF, other, post("/api/v1/staff-covers/" + coverId + "/cancellation"), null)
                .andExpect(status().isForbidden());
        as(Role.SALES_MANAGER, manager, post("/api/v1/staff-covers/" + coverId + "/cancellation"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelledAt").isNotEmpty());
        as(Role.WAREHOUSE_STAFF, staff.staff("WAREHOUSE_STAFF"), get("/api/v1/staff-covers"), null)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Finding S3: a Sales sees only the covers they are in, and their reasons; a manager sees every one")
    void aSalesSeesOnlyTheirOwnCovers() throws Exception {
        UUID away = staff.staff("SALES_STAFF");
        UUID standIn = staff.staff("SALES_STAFF");
        UUID colleague = staff.staff("SALES_STAFF");
        LocalDate from = today().plusDays(1);
        String coverId = data(as(Role.SALES_STAFF, away, post("/api/v1/staff-covers"), Map.of("userId", away,
                "coverUserId", standIn, "from", from.toString(), "to", from.plusDays(2).toString(),
                "reason", "medical leave")).andExpect(status().isCreated())).at("/id").asText();

        for (UUID involved : List.of(away, standIn)) {
            JsonNode row = rowWithId(data(as(Role.SALES_STAFF, involved, get("/api/v1/staff-covers"), null)
                    .andExpect(status().isOk())), coverId);
            assertThat(row).as("listed to %s", involved).isNotNull();
            assertThat(row.at("/reason").asText()).isEqualTo("medical leave");
        }
        assertThat(rowWithId(data(as(Role.SALES_STAFF, colleague, get("/api/v1/staff-covers"), null)
                .andExpect(status().isOk())), coverId)).as("a colleague does not see it").isNull();
        assertThat(rowWithId(data(as(Role.SALES_STAFF, colleague, get("/api/v1/staff-covers")
                .param("userId", away.toString()), null).andExpect(status().isOk())), coverId))
                .as("not even by naming the absent person").isNull();
        JsonNode managers = rowWithId(data(as(Role.SALES_MANAGER, manager, get("/api/v1/staff-covers")
                .param("userId", away.toString()), null).andExpect(status().isOk())), coverId);
        assertThat(managers.at("/reason").asText()).isEqualTo("medical leave");
    }

    private static JsonNode rowWithId(JsonNode rows, String id) {
        for (JsonNode row : rows) {
            if (row.at("/id").asText().equals(id)) {
                return row;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------------------------------------------

    private ResultActions as(Role role, UUID user, MockHttpServletRequestBuilder request, Object body)
            throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        }
        return http.asRole(role, user, request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString()).at("/data");
    }

    private static Map<String, String> skipped(JsonNode result) {
        Map<String, String> skipped = new HashMap<>();
        result.at("/skipped").forEach(row -> skipped.put(row.at("/customerId").asText(), row.at("/reason").asText()));
        return skipped;
    }

    private static JsonNode rowOf(JsonNode rows, String batchId) {
        for (JsonNode row : rows) {
            if (row.at("/batchId").asText().equals(batchId)) {
                return row;
            }
        }
        throw new AssertionError("batch " + batchId + " not listed");
    }

    private Map<String, Object> transferBody(UUID from, List<UUID> to, List<UUID> customers, String reason,
                                             boolean temporary) {
        Map<String, Object> body = new HashMap<>(Map.of("requestId", UUID.randomUUID(), "fromUserId", from,
                "toUserIds", to, "reason", reason, "temporary", temporary, "notifyCustomer", false));
        if (customers != null) {
            body.put("customerIds", customers);
        }
        return body;
    }

    private UUID customer() {
        return tx.execute(status -> ReferenceRows.customer(entityManager));
    }

    private UUID customerOwnedBy(UUID owner) {
        UUID customer = customer();
        ownership.assign(new AssignSalesOwnerCommand(customer, owner, SalesOwnerChangeReason.OTHER, "test owner",
                false, 0, null));
        return customer;
    }

    /** A portal account (CUSTOMER) with a customer of its own. */
    private UUID portalAccount() {
        UUID user = staff.staff("CUSTOMER");
        UUID customer = customer();
        write("UPDATE customer.customer SET user_id = ? WHERE id = ?", user, customer);
        return user;
    }

    private long version(UUID customer) {
        return ownership.ownerOf(customer).orElseThrow().version();
    }

    private String owner(UUID customer) {
        return jdbc.queryForObject("SELECT coalesce(sales_owner_id::text, 'null') || ':' || sales_owner_state "
                + "FROM customer.customer WHERE id = ?", String.class, customer);
    }

    private long historyRows(List<UUID> customers) {
        long rows = 0;
        for (UUID id : customers) {
            rows += jdbc.queryForObject("SELECT count(*) FROM customer.customer_sales_owner_history WHERE customer_id = ?",
                    Long.class, id);
        }
        return rows;
    }

    private static String taxCode() {
        return String.valueOf(1_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 8_999_999_999L));
    }

    private void write(String sql, Object... values) {
        tx.executeWithoutResult(status -> jdbc.update(sql, values));
    }

    private LocalDate today() {
        return BusinessCalendar.date(clock.instant());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
