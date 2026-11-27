package com.stockflow.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.service.ConversationReassignment;
import com.stockflow.chat.internal.service.ConversationWriter;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.CustomerSalesOwnerChanged;
import com.stockflow.contracts.SalesOwnerChangeReason;
import com.stockflow.contracts.StaffAccountDeactivated;
import com.stockflow.customer.api.AssignSalesOwnerCommand;
import com.stockflow.customer.api.SalesOwnership;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.UserStatusChange;
import com.stockflow.identity.internal.service.StaffCoverService;
import com.stockflow.support.Await;
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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The chat follows the customer's Sales owner, over HTTP with the real security chain, the real customer and identity
 * modules, the database and the after-commit listeners (sales-owner plan 2026-11-28 §2.3, BR-S05, S06, S08, S12, F9;
 * kltn-docs 16 §4.1, BR-12..BR-14): routing, claiming, opening a conversation, the owner changing, an agent's account
 * locked, and the managers' workload board. Existing chat behaviour (only the assignee answers) is checked where it
 * meets these.
 */
@SecuredIntegrationTest
class ChatSalesOwnerIntegrationTest {

    private static final String MINE = "/api/v1/chat/me/conversations";
    private static final String STAFF = "/api/v1/chat/conversations";
    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;
    @Autowired SalesOwnership ownership;
    @Autowired StaffCoverService covers;
    @Autowired IdentityService identity;
    @Autowired ConversationReassignment reassignment;
    @Autowired ConversationWriter writer;
    @Autowired Clock clock;

    private SecuredMvc http;
    private StaffFixtures people;
    private UUID owner;
    private UUID otherSales;
    private UUID manager;

    private record Portal(UUID customer, UUID account) {
    }

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mvc, decoder);
        people = new StaffFixtures(jdbc, tx, entityManager);
        owner = people.staff("SALES_STAFF");
        otherSales = people.staff("SALES_STAFF");
        manager = people.staff("SALES_MANAGER");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Routing (§2.3, 16 §4.1 step 2)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("§2.3 / case 3: a customer with an owner at work - the owner, whatever the conversation is about")
    void routesToTheOwner() throws Exception {
        Portal alice = portal();
        own(alice, owner);

        UUID conversation = open(alice, "Xin bao gia");

        assertThat(statusOf(conversation)).isEqualTo("ASSIGNED");
        assertThat(agentOf(conversation)).isEqualTo(owner);
    }

    @Test
    @DisplayName("§2.3 / case 2: no owner - the queue, and the chat supervisors are told")
    void noOwnerQueuesAndTellsTheSupervisors() throws Exception {
        Portal alice = portal();
        long before = people.messages(manager);

        UUID conversation = open(alice, "Lan dau");

        assertThat(statusOf(conversation)).isEqualTo("QUEUED");
        assertThat(people.messages(manager)).as("the supervisors' bell").isEqualTo(before + 1);
        assertThat(people.titles(manager)).last().asString().contains("hàng chờ");
    }

    @Test
    @DisplayName("BR-S08 / case 8: an owner whose account was locked behind the chat's back - the queue, never them")
    void aLockedOwnerQueues() throws Exception {
        Portal alice = portal();
        own(alice, owner);
        people.setStatus(owner, "LOCKED");

        UUID conversation = open(alice, "Co ai khong");

        assertThat(statusOf(conversation)).isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("D42: a Sales Manager is never an owner, so their customer waits in the queue; a manager's claim does "
            + "not make them the owner either")
    void aManagerIsNeverTheOwner() throws Exception {
        Portal alice = portal();
        assertThat(refusalOf(() -> ownership.assign(new AssignSalesOwnerCommand(alice.customer(), manager,
                SalesOwnerChangeReason.OTHER, "manager as owner", false, 0, null))))
                .isEqualTo("SALES_OWNER_NOT_ASSIGNABLE");
        UUID conversation = open(alice, "Hoi gia");
        assertThat(statusOf(conversation)).isEqualTo("QUEUED");

        staff(Role.SALES_MANAGER, manager, post(STAFF + "/" + conversation + "/claim")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignedAgentId").value(manager.toString()));

        assertThat(ownerState(alice)).as("the manager coordinates, owns nobody").isEqualTo("UNASSIGNED");
        assertThat(salesOwner(alice)).isNull();
    }

    @Test
    @DisplayName("BR-S07 / F9: a cover routes the new conversations to the stand-in; starting or ending it moves no "
            + "open conversation")
    void aCoverRoutesNewConversationsOnly() throws Exception {
        Portal alice = portal();
        own(alice, owner);
        UUID before = open(alice, "Truoc khi nghi");
        assertThat(agentOf(before)).isEqualTo(owner);

        StaffCoverService.CoverView cover = covers.arrange(new StaffCoverService.ArrangeCover(owner, otherSales,
                today(), today().plusDays(2), "nghi phep"), owner, false);
        UUID during = openThroughTheRouter(alice);

        assertThat(agentOf(during)).as("the cover in force gets the new one").isEqualTo(otherSales);
        assertThat(agentOf(before)).as("F9: the open one stays with the owner").isEqualTo(owner);

        covers.cancel(cover.id(), owner, false);
        assertThat(agentOf(during)).as("F9: the cover ending moves nothing back").isEqualTo(otherSales);
        assertThat(agentOf(openThroughTheRouter(alice))).as("after the cover: the owner").isEqualTo(owner);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Claiming (BR-S05, 16 BR-13, case 18)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BR-S05 / D2: a salesperson who claims a customer's conversation from the queue becomes its owner, the "
            + "managers are told, and the customer's other open conversations follow them")
    void claimingMakesTheOwner() throws Exception {
        Portal alice = portal();
        UUID first = open(alice, "Lan dau");
        UUID second = queuedRow(alice);
        long before = people.messages(manager);

        staff(Role.SALES_STAFF, otherSales, post(STAFF + "/" + first + "/claim")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignedAgentId").value(otherSales.toString()));

        assertThat(salesOwner(alice)).isEqualTo(otherSales);
        assertThat(ownerState(alice)).isEqualTo("ASSIGNED");
        assertThat(jdbc.queryForObject("SELECT reason FROM customer.customer_sales_owner_history WHERE customer_id = ?"
                + " ORDER BY changed_at DESC LIMIT 1", String.class, alice.customer())).isEqualTo("CLAIMED_FROM_QUEUE");
        assertThat(people.messages(manager)).as("the managers hear of it").isEqualTo(before + 1);
        Await.until("the other queued conversation follows the new owner", WAIT,
                () -> otherSales.equals(agentOf(second)));
        assertThat(statusOf(second)).isEqualTo("ASSIGNED");

        // Claimed again by the same salesperson (a retry, or the race with the move above): nothing changes.
        staff(Role.SALES_STAFF, otherSales, post(STAFF + "/" + second + "/claim")).andExpect(status().isOk());
        assertThat(history(alice)).as("one history row for the claim").isEqualTo(1);
    }

    @Test
    @DisplayName("Case 18: another salesperson's claim of an owned customer's conversation is refused, naming the owner; "
            + "the owner and the cover in force may take it")
    void claimOfAnOwnedCustomer() throws Exception {
        Portal alice = portal();
        own(alice, owner);
        UUID queued = queuedRow(alice);
        String ownerName = identity.displayNames(List.of(owner)).get(owner);

        staff(Role.SALES_STAFF, otherSales, post(STAFF + "/" + queued + "/claim")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_UNASSIGNED_CLAIM_ONLY"))
                .andExpect(jsonPath("$.details.salesOwnerName").value(ownerName))
                .andExpect(jsonPath("$.details.salesOwnerId").value(owner.toString()));
        assertThat(statusOf(queued)).as("the refused claim took nothing").isEqualTo("QUEUED");
        assertThat(salesOwner(alice)).isEqualTo(owner);

        UUID stillQueued = queuedRow(alice);
        covers.arrange(new StaffCoverService.ArrangeCover(owner, otherSales, today(), today(), null), owner, false);
        staff(Role.SALES_STAFF, otherSales, post(STAFF + "/" + stillQueued + "/claim")).andExpect(status().isOk());
        assertThat(salesOwner(alice)).as("the cover acts for the owner, does not take the customer").isEqualTo(owner);

        staff(Role.SALES_STAFF, owner, post(STAFF + "/" + queued + "/claim")).andExpect(status().isOk());
        assertThat(agentOf(queued)).isEqualTo(owner);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Staff open a conversation (BR-S12, 16 BR-12)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BR-S12 / 16 BR-12: another salesperson cannot open a conversation with an owned customer "
            + "(CUSTOMER_OWNED_BY_OTHER_SALES, naming the owner); the owner, the cover and a manager can")
    void openingAConversation() throws Exception {
        Portal alice = portal();
        own(alice, owner);
        String ownerName = identity.displayNames(List.of(owner)).get(owner);

        openAsStaff(Role.SALES_STAFF, otherSales, alice).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_OWNED_BY_OTHER_SALES"))
                .andExpect(jsonPath("$.details.salesOwnerName").value(ownerName));

        assertThat(agentOf(id(openAsStaff(Role.SALES_STAFF, owner, alice).andExpect(status().isCreated()))))
                .isEqualTo(owner);
        assertThat(agentOf(id(openAsStaff(Role.SALES_MANAGER, manager, alice).andExpect(status().isCreated()))))
                .as("a manager may open it; one customer, one contact").isEqualTo(owner);

        covers.arrange(new StaffCoverService.ArrangeCover(owner, otherSales, today(), today(), null), owner, false);
        assertThat(agentOf(id(openAsStaff(Role.SALES_STAFF, otherSales, alice).andExpect(status().isCreated()))))
                .as("the cover in force").isEqualTo(otherSales);

        Portal bob = portal();
        assertThat(agentOf(id(openAsStaff(Role.SALES_STAFF, otherSales, bob).andExpect(status().isCreated()))))
                .as("a customer with no owner: anyone, and it is theirs").isEqualTo(otherSales);
        assertThat(ownerState(bob)).as("D43: opening a conversation makes nobody the owner").isEqualTo("UNASSIGNED");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The owner changes (BR-S06, 16 BR-14)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BR-S06 / case 10: the open conversations follow the new owner keeping their status, with the staff "
            + "line 'Chuyển từ A sang B: lý do' and one customer line; an ended one stays; delivered twice, nothing more")
    void theOpenConversationsFollowTheNewOwner() throws Exception {
        Portal alice = portal();
        own(alice, owner);
        UUID ended = open(alice, "Cu");
        reply(owner, ended, "Da xong").andExpect(status().isCreated());
        move(owner, ended, "RESOLVED");
        UUID active = open(alice, "Moi");
        reply(owner, active, "Em nghe a").andExpect(status().isCreated());
        UUID assigned = id(openAsStaff(Role.SALES_STAFF, owner, alice).andExpect(status().isCreated()));

        change(alice, otherSales, SalesOwnerChangeReason.WORKLOAD, true);

        Await.until("the open conversations follow the new owner", WAIT,
                () -> otherSales.equals(agentOf(active)) && otherSales.equals(agentOf(assigned)));
        assertThat(statusOf(active)).as("16 §5: only the agent changes").isEqualTo("ACTIVE");
        assertThat(statusOf(assigned)).isEqualTo("ASSIGNED");
        assertThat(agentOf(ended)).as("an ended conversation stays").isEqualTo(owner);
        assertThat(statusOf(ended)).isEqualTo("RESOLVED");

        String from = identity.displayNames(List.of(owner)).get(owner);
        String to = identity.displayNames(List.of(otherSales)).get(otherSales);
        assertThat(lines(active, "STAFF")).containsExactly("Chuyển từ " + from + " sang " + to + ": chia lại tải");
        assertThat(lines(assigned, "STAFF")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat.conversation_event WHERE conversation_id = ? AND"
                + " type = 'TRANSFERRED' AND actor_type = 'SYSTEM' AND target_user_id = ?", Long.class, active,
                otherSales)).isEqualTo(1L);
        assertThat(customerLines(alice)).as("one line for the customer").containsExactly(
                "Từ nay anh/chị được " + to + " hỗ trợ.");
        String seen = body(http.asCustomer(alice.account(), get(MINE + "/" + newestOpen(alice) + "/messages")));
        assertThat(seen).contains("Từ nay anh/chị được").doesNotContain("Chuyển từ");

        // Only the assignee answers (16 BR-12): the new owner now, not the old one.
        reply(owner, active, "Em van o day").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CHAT_NOT_ASSIGNEE"));
        reply(otherSales, active, "Em tiep nhan a").andExpect(status().isCreated());

        // Delivered again: the change is applied once.
        assertThat(reassignment.followOwner(lastChange(alice))).isZero();
        assertThat(customerLines(alice)).hasSize(1);
        assertThat(lines(active, "STAFF")).hasSize(1);
    }

    @Test
    @DisplayName("BR-S06 / D3: a change marked internal tells the customer nothing; with nothing open the customer "
            + "hears of it on the bell; a stale change moves nothing")
    void tellingTheCustomer() throws Exception {
        Portal alice = portal();
        own(alice, owner);
        UUID active = open(alice, "Hoi");
        change(alice, otherSales, SalesOwnerChangeReason.REGION, false);
        Await.until("the conversation follows", WAIT, () -> otherSales.equals(agentOf(active)));
        assertThat(customerLines(alice)).as("an internal change").isEmpty();

        Portal bob = portal();
        own(bob, owner);
        long before = customerBells(bob);
        change(bob, otherSales, SalesOwnerChangeReason.CUSTOMER_REQUEST, true);
        Await.until("the customer is told on the bell", WAIT, () -> customerBells(bob) == before + 1);

        CustomerSalesOwnerChanged stale = new CustomerSalesOwnerChanged(alice.customer(), otherSales, owner,
                SalesOwnerChangeReason.OTHER, null, true, null, Instant.now());
        assertThat(reassignment.followOwner(stale)).as("alice is not owner's any more").isZero();
        assertThat(agentOf(active)).isEqualTo(otherSales);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // An agent's account locked (BR-S08, 16 BR-14)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BR-S08 / case 7: locking an agent puts their open conversations back in the queue, flagged first, "
            + "and tells the supervisors how many; a salesperson who claims one becomes the owner (case 8)")
    void lockingAnAgentRequeuesTheirConversations() throws Exception {
        Portal alice = portal();
        own(alice, owner);
        UUID ended = open(alice, "Cu");
        reply(owner, ended, "Xong").andExpect(status().isCreated());
        move(owner, ended, "RESOLVED");
        UUID active = open(alice, "Dang hoi");
        reply(owner, active, "Em day").andExpect(status().isCreated());
        UUID waiting = id(openAsStaff(Role.SALES_STAFF, owner, alice).andExpect(status().isCreated()));
        reply(owner, waiting, "Anh xem bao gia").andExpect(status().isCreated());
        move(owner, waiting, "WAITING_ON_CUSTOMER");
        long before = people.messages(manager);

        identity.changeUserStatus(owner, UserStatusChange.LOCK, "Nghi viec");

        Await.until("the open conversations go back to the queue", WAIT,
                () -> "QUEUED".equals(statusOf(active)) && "QUEUED".equals(statusOf(waiting)));
        assertThat(agentOf(active)).isNull();
        assertThat(statusOf(ended)).as("an ended one stays").isEqualTo("RESOLVED");
        assertThat(people.titles(manager)).as("the supervisors hear how many")
                .contains("2 hội thoại chat đã về hàng chờ");
        assertThat(people.messages(manager)).isGreaterThan(before);
        staff(Role.SALES_MANAGER, manager, get(STAFF).param("customerId", alice.customer().toString())
                .param("unassigned", "true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].priority").value(true))
                .andExpect(jsonPath("$.data.items[1].priority").value(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat.conversation_event WHERE conversation_id = ? AND"
                + " type = 'QUEUED' AND detail ->> 'reason' = 'AGENT_DEACTIVATED'", Long.class, active)).isEqualTo(1L);

        // Delivered again: nothing left to move, nobody told twice.
        long told = people.messages(manager);
        assertThat(reassignment.requeueAgent(new StaffAccountDeactivated(owner, "LOCKED", null, Instant.now())))
                .isZero();
        assertThat(people.messages(manager)).isEqualTo(told);

        // Case 8: the customer waits for an owner; a salesperson who claims becomes it, and takes the other one too.
        Await.until("the customer needs an owner", WAIT, () -> "NEEDS_ASSIGNMENT".equals(ownerState(alice)));
        staff(Role.SALES_STAFF, otherSales, post(STAFF + "/" + active + "/claim")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.priority").value(false));
        assertThat(salesOwner(alice)).isEqualTo(otherSales);
        Await.until("the other one follows the new owner", WAIT, () -> otherSales.equals(agentOf(waiting)));
        assertThat(jdbc.queryForObject("SELECT requeued_at IS NULL FROM chat.conversation WHERE id = ?", Boolean.class,
                waiting)).isTrue();
    }

    @Test
    @DisplayName("BR-S08: a deactivation delivered after the account works again moves nothing")
    void aStaleDeactivationMovesNothing() throws Exception {
        Portal alice = portal();
        own(alice, owner);
        UUID active = open(alice, "Hoi");

        assertThat(reassignment.requeueAgent(new StaffAccountDeactivated(owner, "LOCKED", null, Instant.now())))
                .isZero();

        assertThat(agentOf(active)).isEqualTo(owner);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The workload board (§10.4)
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("§10.4: GET /chat/agents/workload counts each agent's open and overdue conversations for "
            + "sales-workload:READ; a salesperson is refused")
    void workload() throws Exception {
        Portal alice = portal();
        own(alice, owner);
        UUID late = open(alice, "Gap");
        id(openAsStaff(Role.SALES_STAFF, owner, alice).andExpect(status().isCreated()));
        UUID ended = id(openAsStaff(Role.SALES_STAFF, owner, alice).andExpect(status().isCreated()));
        reply(owner, ended, "Xong").andExpect(status().isCreated());
        move(owner, ended, "RESOLVED");
        tx.executeWithoutResult(s -> jdbc.update("UPDATE chat.conversation SET first_response_due_at = NOW() -"
                + " INTERVAL '1 minute' WHERE id = ?", late));

        String board = body(staff(Role.SALES_MANAGER, manager, get("/api/v1/chat/agents/workload"))
                .andExpect(status().isOk()));
        JsonNode rows = json.readTree(board).path("data");
        JsonNode mine = null;
        JsonNode idle = null;
        for (JsonNode row : rows) {
            if (row.path("userId").asText().equals(owner.toString())) {
                mine = row;
            }
            if (row.path("userId").asText().equals(otherSales.toString())) {
                idle = row;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.path("openConversations").asLong()).isEqualTo(2);
        assertThat(mine.path("overdueConversations").asLong()).isEqualTo(1);
        assertThat(idle).as("an active salesperson with nothing open is on the board").isNotNull();
        assertThat(idle.path("openConversations").asLong()).isZero();

        staff(Role.SALES_STAFF, owner, get("/api/v1/chat/agents/workload")).andExpect(status().isForbidden());
    }

    // ---- helpers

    private Portal portal() {
        UUID account = people.staff("CUSTOMER");
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("UPDATE customer.customer SET user_id = ? WHERE id = ?", account,
                customer));
        return new Portal(customer, account);
    }

    private void own(Portal portal, UUID salesOwner) {
        ownership.assign(new AssignSalesOwnerCommand(portal.customer(), salesOwner, SalesOwnerChangeReason.OTHER,
                "test owner", false, version(portal), null));
        Await.until("the first assignment is applied by the chat", WAIT, () -> applied(portal) >= 1);
    }

    private void change(Portal portal, UUID to, SalesOwnerChangeReason reason, boolean notify) {
        long before = applied(portal);
        ownership.assign(new AssignSalesOwnerCommand(portal.customer(), to, reason, "test change", notify,
                version(portal), manager));
        Await.until("the change reaches the chat", WAIT, () -> applied(portal) > before);
    }

    private long applied(Portal portal) {
        return jdbc.queryForObject("SELECT count(*) FROM chat.sales_owner_change_applied WHERE customer_id = ?",
                Long.class, portal.customer());
    }

    private long version(Portal portal) {
        return ownership.ownerOf(portal.customer()).orElseThrow().version();
    }

    /** The newest change of the customer's owner, as {@code customer} published it. */
    private CustomerSalesOwnerChanged lastChange(Portal portal) {
        return jdbc.queryForObject("""
                        SELECT from_user_id, to_user_id, reason, changed_by, changed_at
                          FROM customer.customer_sales_owner_history WHERE customer_id = ?
                         ORDER BY changed_at DESC LIMIT 1""",
                (rs, n) -> new CustomerSalesOwnerChanged(portal.customer(), rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), SalesOwnerChangeReason.valueOf(rs.getString(3)), null, true,
                        rs.getObject(4, UUID.class), rs.getTimestamp(5).toInstant()), portal.customer());
    }

    /** A conversation of the customer waiting in the queue, as one routed while nobody acted for them. */
    private UUID queuedRow(Portal portal) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO chat.conversation (id, customer_id, subject, status, opened_by_type, opened_by,
                                               status_changed_at, first_response_due_at, version, created_at)
                VALUES (?, ?, 'Hang cho', 'QUEUED', 'CUSTOMER', ?, NOW(), NOW() + INTERVAL '15 minutes', 0, NOW())""",
                id, portal.customer(), portal.account()));
        return id;
    }

    /** A new conversation of the customer routed by the router, as the portal or a card opens one. */
    private UUID openThroughTheRouter(Portal portal) {
        return tx.execute(s -> writer.open(portal.customer(), "Qua bo dinh tuyen", Actor.customer(portal.account()),
                List.of(), null, null).id());
    }

    private UUID open(Portal portal, String text) throws Exception {
        return id(http.asCustomer(portal.account(), post(MINE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}")).andExpect(status().isCreated()));
    }

    private ResultActions openAsStaff(Role role, UUID user, Portal portal) throws Exception {
        return staff(role, user, post(STAFF).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + portal.customer() + "\"}"));
    }

    private ResultActions reply(UUID agent, UUID conversation, String text) throws Exception {
        return staff(Role.SALES_STAFF, agent, post(STAFF + "/" + conversation + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + text + "\"}"));
    }

    private void move(UUID agent, UUID conversation, String target) throws Exception {
        staff(Role.SALES_STAFF, agent, post(STAFF + "/" + conversation + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + target + "\"}"))
                .andExpect(status().isOk());
    }

    private ResultActions staff(Role role, UUID user, MockHttpServletRequestBuilder request) throws Exception {
        return http.asRole(role, user, request);
    }

    private String statusOf(UUID conversation) {
        return jdbc.queryForObject("SELECT status FROM chat.conversation WHERE id = ?", String.class, conversation);
    }

    private UUID agentOf(UUID conversation) {
        return jdbc.queryForObject("SELECT assigned_agent_id FROM chat.conversation WHERE id = ?", UUID.class,
                conversation);
    }

    private UUID salesOwner(Portal portal) {
        return jdbc.queryForObject("SELECT sales_owner_id FROM customer.customer WHERE id = ?", UUID.class,
                portal.customer());
    }

    private String ownerState(Portal portal) {
        return jdbc.queryForObject("SELECT sales_owner_state FROM customer.customer WHERE id = ?", String.class,
                portal.customer());
    }

    private long history(Portal portal) {
        return jdbc.queryForObject("SELECT count(*) FROM customer.customer_sales_owner_history WHERE customer_id = ?",
                Long.class, portal.customer());
    }

    /** The system lines of {@code visibility} in a conversation, oldest first. */
    private List<String> lines(UUID conversation, String visibility) {
        return jdbc.queryForList("SELECT body FROM chat.message WHERE conversation_id = ? AND kind = 'SYSTEM' AND"
                + " visibility = ? ORDER BY seq", String.class, conversation, visibility);
    }

    /** The system lines the customer can read, in any of their conversations. */
    private List<String> customerLines(Portal portal) {
        return jdbc.queryForList("SELECT m.body FROM chat.message m JOIN chat.conversation c ON c.id ="
                + " m.conversation_id WHERE c.customer_id = ? AND m.kind = 'SYSTEM' AND m.visibility = 'CUSTOMER'"
                + " ORDER BY m.sent_at", String.class, portal.customer());
    }

    private UUID newestOpen(Portal portal) {
        return jdbc.queryForObject("SELECT c.id FROM chat.message m JOIN chat.conversation c ON c.id ="
                + " m.conversation_id WHERE c.customer_id = ? AND m.kind = 'SYSTEM' AND m.visibility = 'CUSTOMER'"
                + " LIMIT 1", UUID.class, portal.customer());
    }

    private long customerBells(Portal portal) {
        return jdbc.queryForObject("SELECT count(*) FROM notification.user_notification WHERE recipient_id = ? AND"
                + " title = 'Anh/chị có Sale phụ trách mới'", Long.class, portal.account());
    }

    private LocalDate today() {
        return BusinessCalendar.date(clock.instant());
    }

    private String refusalOf(Runnable call) {
        try {
            call.run();
            return null;
        } catch (com.stockflow.common.error.BusinessException refused) {
            return refused.errorCode().name();
        }
    }

    private UUID id(ResultActions result) throws Exception {
        return UUID.fromString(json.readTree(body(result)).path("data").path("id").asText());
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
