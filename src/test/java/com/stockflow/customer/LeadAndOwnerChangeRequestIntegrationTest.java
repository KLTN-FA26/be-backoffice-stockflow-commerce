package com.stockflow.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.LeadFormFromExistingCustomer;
import com.stockflow.contracts.OwnerChangeRequested;
import com.stockflow.contracts.QuoteStatusChanged;
import com.stockflow.contracts.SalesLeadAssigned;
import com.stockflow.contracts.SalesLeadSubmitted;
import com.stockflow.contracts.SalesOwnerChangeReason;
import com.stockflow.customer.api.AssignSalesOwnerCommand;
import com.stockflow.customer.api.SalesOwnership;
import com.stockflow.customer.internal.service.SalesContactListener;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Leads (sales-owner plan §10.3 channel B, §10.7) and a customer's request to change salesperson (§10.5, BR-S15, D11,
 * D12) over HTTP with security on: the public form and its defences, the managers' queue and the salesperson's steps,
 * the portal card, and the request from asking to the managers' decision.
 */
@SecuredIntegrationTest
@RecordApplicationEvents
class LeadAndOwnerChangeRequestIntegrationTest {

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired SalesOwnership ownership;
    @Autowired SalesContactListener contactListener;
    @Autowired ApplicationEvents events;

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
    // The public form
    // -------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("§10.7: the public form, signed out, makes a NEW lead with its first history row and answers 202 received")
    void theFormMakesALead() throws Exception {
        String code = taxCode();
        submit(form(code), address()).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.received").value(true));

        UUID lead = leadWithTaxCode(code);
        assertThat(jdbc.queryForObject("SELECT status || ':' || phone || ':' || array_to_string(interests, ',') "
                + "FROM customer.lead WHERE id = ?", String.class, lead)).isEqualTo("NEW:+84912345678:CUP,BOX");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer.lead_history WHERE lead_id = ? AND "
                + "from_status IS NULL AND to_status = 'NEW' AND changed_by IS NULL", Long.class, lead)).isEqualTo(1);
        assertThat(events.stream(SalesLeadSubmitted.class).filter(e -> e.leadId().equals(lead))).hasSize(1);
    }

    @Test
    @DisplayName("§10.7: a filled honeypot, or the same tax code or e-mail within 24 hours, makes no lead - same 202")
    void honeypotAndRepeatsMakeNoLead() throws Exception {
        String trapped = taxCode();
        Map<String, Object> bot = form(trapped);
        bot.put("website", "http://spam.example");
        submit(bot, address()).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.received").value(true));
        assertThat(leadsWithTaxCode(trapped)).isZero();

        String code = taxCode();
        submit(form(code), address()).andExpect(status().isAccepted());
        submit(form(code), address()).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.received").value(true));
        assertThat(leadsWithTaxCode(code)).as("same tax code within 24 hours").isEqualTo(1);

        Map<String, Object> sameMail = form(taxCode());
        String email = jdbc.queryForObject("SELECT email FROM customer.lead WHERE tax_code = ?", String.class, code);
        sameMail.put("email", email.toUpperCase());
        submit(sameMail, address()).andExpect(status().isAccepted());
        assertThat(leadsWithTaxCode((String) sameMail.get("taxCode"))).as("same e-mail within 24 hours").isZero();

        write("UPDATE customer.lead SET submitted_at = NOW() - INTERVAL '25 hours' WHERE tax_code = ?", code);
        submit(form(code), address()).andExpect(status().isAccepted());
        assertThat(leadsWithTaxCode(code)).as("after 24 hours it is a new lead").isEqualTo(2);
    }

    @Test
    @DisplayName("§10.7 anti-poaching: a customer's tax code makes no lead, its salesperson is told, the sender learns nothing")
    void anExistingCustomersTaxCodeMakesNoLead() throws Exception {
        UUID customer = customer();
        String code = taxCode();
        write("UPDATE customer.customer SET tax_code = ? WHERE id = ?", code, customer);
        events.clear();

        JsonNode answer = data(submit(form(code), address()).andExpect(status().isAccepted()));
        JsonNode newcomer = data(submit(form(taxCode()), address()).andExpect(status().isAccepted()));

        assertThat(answer).as("the same answer as for a new business").isEqualTo(newcomer);
        assertThat(leadsWithTaxCode(code)).isZero();
        assertThat(events.stream(LeadFormFromExistingCustomer.class).map(LeadFormFromExistingCustomer::customerId))
                .containsExactly(customer);
    }

    @Test
    @DisplayName("Finding S1: a customer's tax code and a newcomer's get the same status and body, whatever the body")
    void theFormNeverTellsACustomerFromANewcomer() throws Exception {
        UUID customer = customer();
        String customerCode = taxCode();
        write("UPDATE customer.customer SET tax_code = ? WHERE id = ?", customerCode, customer);

        List<java.util.function.Consumer<Map<String, Object>>> oddities = List.of(
                form -> form.put("interests", java.util.Arrays.asList((Object) null)),
                form -> form.put("interests", java.util.Arrays.asList("CUP", null)),
                form -> form.put("interests", List.of("CUP", "CUP", "BOX")),
                form -> form.put("interests", List.of()),
                form -> form.put("contactTitle", "   "),
                form -> form.put("companyName", "  Cong ty co khoang trang  "),
                form -> form.put("email", "  MIXED.Case@Example.TEST "),
                form -> form.remove("email"),
                form -> form.put("phone", "+84 912 345 678"));
        for (var oddity : oddities) {
            Map<String, Object> asCustomer = form(customerCode);
            oddity.accept(asCustomer);
            Map<String, Object> asNewcomer = form(taxCode());
            oddity.accept(asNewcomer);
            var customerAnswer = submit(asCustomer, address()).andReturn().getResponse();
            var newcomerAnswer = submit(asNewcomer, address()).andReturn().getResponse();

            assertThat(newcomerAnswer.getStatus()).as("status for %s", asCustomer)
                    .isEqualTo(customerAnswer.getStatus());
            assertThat(comparable(newcomerAnswer.getContentAsString())).as("body for %s", asCustomer)
                    .isEqualTo(comparable(customerAnswer.getContentAsString()));
        }
        assertThat(leadsWithTaxCode(customerCode)).isZero();
    }

    @Test
    @DisplayName("Finding S4: a customer's tax code tells its salesperson once per 24 hours, not once per form")
    void aCustomersTaxCodeTellsOncePerWindow() throws Exception {
        UUID customer = customer();
        String code = taxCode();
        write("UPDATE customer.customer SET tax_code = ? WHERE id = ?", code, customer);
        events.clear();

        for (int i = 0; i < 3; i++) {
            submit(form(code), address()).andExpect(status().isAccepted());
        }
        assertThat(events.stream(LeadFormFromExistingCustomer.class).filter(e -> e.customerId().equals(customer)))
                .hasSize(1);

        write("UPDATE customer.lead_form_existing_customer SET last_at = NOW() - INTERVAL '25 hours' "
                + "WHERE customer_id = ?", customer);
        submit(form(code), address()).andExpect(status().isAccepted());
        assertThat(events.stream(LeadFormFromExistingCustomer.class).filter(e -> e.customerId().equals(customer)))
                .as("after 24 hours the salesperson is told again").hasSize(2);
    }

    @Test
    @DisplayName("Finding P5: a repeat within 24 hours is kept on the open lead's history, so Sales see both senders")
    void aRepeatIsKeptOnTheLeadsHistory() throws Exception {
        String code = taxCode();
        submit(form(code), address()).andExpect(status().isAccepted());
        UUID lead = leadWithTaxCode(code);

        Map<String, Object> real = form(code);
        real.put("contactName", "Tran Thi B");
        real.put("email", "purchasing-" + code + "@real.example.test");
        real.put("phone", "0987654321");
        submit(real, address()).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.received").value(true));

        assertThat(leadsWithTaxCode(code)).as("still one lead").isEqualTo(1);
        JsonNode history = data(as(Role.SALES_MANAGER, manager, get("/api/v1/leads/" + lead + "/history"), null)
                .andExpect(status().isOk()));
        assertThat(history).hasSize(2);
        assertThat(history.at("/0/kind").asText()).isEqualTo("CHANGE");
        assertThat(history.at("/1/kind").asText()).isEqualTo("RESUBMITTED");
        assertThat(history.at("/1/fromStatus").asText()).isEqualTo("NEW");
        assertThat(history.at("/1/toStatus").asText()).isEqualTo("NEW");
        assertThat(history.at("/1/note").asText()).contains("Tran Thi B", "purchasing-" + code, "+84987654321");
        assertThat(jdbc.queryForObject("SELECT status FROM customer.lead WHERE id = ?", String.class, lead))
                .isEqualTo("NEW");

        UUID a = staff.staff("SALES_STAFF");
        as(Role.SALES_MANAGER, manager, put("/api/v1/leads/" + lead + "/assignment"), Map.of("salesUserId", a))
                .andExpect(status().isOk());
        as(Role.SALES_STAFF, a, post("/api/v1/leads/" + lead + "/disqualification"), Map.of("reason", "a fake"))
                .andExpect(status().isOk());
        submit(real, address()).andExpect(status().isAccepted());
        assertThat(leadsWithTaxCode(code)).as("a repeat of a closed lead is a lead of its own").isEqualTo(2);
    }

    @Test
    @DisplayName("§10.7: the form is rate-limited per address; a malformed form (no consent, no contact, bad interest) is 400")
    void theFormIsLimitedAndValidated() throws Exception {
        String one = address();
        for (int i = 0; i < 5; i++) {
            submit(form(taxCode()), one).andExpect(status().isAccepted());
        }
        submit(form(taxCode()), one).andExpect(status().isTooManyRequests());
        submit(form(taxCode()), address()).andExpect(status().isAccepted());

        Map<String, Object> noConsent = form(taxCode());
        noConsent.put("consent", false);
        submit(noConsent, address()).andExpect(status().isBadRequest());
        Map<String, Object> noContact = form(taxCode());
        noContact.remove("phone");
        noContact.remove("email");
        submit(noContact, address()).andExpect(status().isBadRequest());
        Map<String, Object> badInterest = form(taxCode());
        badInterest.put("interests", List.of("PLATE"));
        submit(badInterest, address()).andExpect(status().isBadRequest());
        Map<String, Object> tooLong = form(taxCode());
        tooLong.put("note", "x".repeat(1001));
        submit(tooLong, address()).andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------------------------------------------
    // The queue and the steps
    // -------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("§10.7: the manager assigns; only the lead's Sales sees and contacts it; disqualifying needs a reason; history")
    void aLeadIsWorked() throws Exception {
        UUID a = staff.staff("SALES_STAFF");
        UUID b = staff.staff("SALES_STAFF");
        String code = taxCode();
        submit(form(code), address()).andExpect(status().isAccepted());
        UUID lead = leadWithTaxCode(code);
        String path = "/api/v1/leads/" + lead;

        assertThat(ids(data(as(Role.SALES_MANAGER, manager, get("/api/v1/leads").param("status", "NEW")
                .param("size", "100"), null).andExpect(status().isOk())).at("/items"))).contains(lead.toString());
        as(Role.SALES_STAFF, a, put(path + "/assignment"), Map.of("salesUserId", a)).andExpect(status().isForbidden());
        as(Role.SALES_MANAGER, manager, put(path + "/assignment"), Map.of("salesUserId", manager))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SALES_OWNER_NOT_ASSIGNABLE"));
        as(Role.SALES_MANAGER, manager, put(path + "/assignment"), Map.of("salesUserId", a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.assignedTo").value(a.toString()))
                .andExpect(jsonPath("$.data.assignedToName").isNotEmpty());
        assertThat(events.stream(SalesLeadAssigned.class).filter(e -> e.leadId().equals(lead))).hasSize(1);

        assertThat(ids(data(as(Role.SALES_STAFF, b, get("/api/v1/leads").param("assignedTo", a.toString()), null))
                .at("/items"))).as("a Sales sees their own leads only").doesNotContain(lead.toString());
        assertThat(ids(data(as(Role.SALES_STAFF, a, get("/api/v1/leads"), null)).at("/items")))
                .containsExactly(lead.toString());
        as(Role.SALES_STAFF, b, get(path), null).andExpect(status().isForbidden());
        as(Role.SALES_STAFF, b, post(path + "/contact"), Map.of("note", "called")).andExpect(status().isForbidden());
        as(Role.SALES_STAFF, a, post(path + "/contact"), Map.of("note", "called the buyer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CONTACTED"))
                .andExpect(jsonPath("$.data.contactNote").value("called the buyer"));
        as(Role.SALES_STAFF, a, post(path + "/contact"), Map.of("note", "again"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("LEAD_ALREADY_HANDLED"));
        as(Role.SALES_STAFF, b, post(path + "/disqualification"), Map.of("reason", "spam"))
                .andExpect(status().isForbidden());
        as(Role.SALES_STAFF, a, post(path + "/disqualification"), Map.of("reason", " "))
                .andExpect(status().isBadRequest());
        as(Role.SALES_STAFF, a, post(path + "/disqualification"), Map.of("reason", "a retail shop, not B2B"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISQUALIFIED"))
                .andExpect(jsonPath("$.data.disqualifyReason").value("a retail shop, not B2B"));
        as(Role.SALES_MANAGER, manager, put(path + "/assignment"), Map.of("salesUserId", b))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("LEAD_ALREADY_HANDLED"));

        JsonNode history = data(as(Role.SALES_MANAGER, manager, get(path + "/history"), null).andExpect(status().isOk()));
        List<String> steps = new ArrayList<>();
        history.forEach(row -> steps.add(row.at("/toStatus").asText()));
        assertThat(steps).containsExactly("NEW", "ASSIGNED", "CONTACTED", "DISQUALIFIED");
        assertThat(history.at("/1/changedById").asText()).isEqualTo(manager.toString());
        as(Role.WAREHOUSE_STAFF, staff.staff("WAREHOUSE_STAFF"), get("/api/v1/leads"), null)
                .andExpect(status().isForbidden());
        as(Role.SALES_MANAGER, manager, get("/api/v1/leads/" + UUID.randomUUID()), null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("LEAD_NOT_FOUND"));
    }

    // -------------------------------------------------------------------------------------------------------------
    // The portal card and the request to change salesperson
    // -------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("§10.5: the portal card names the salesperson only; the options leave out the owner and the managers")
    void thePortalCard() throws Exception {
        UUID owner = staff.staff("SALES_STAFF");
        UUID other = staff.staff("SALES_STAFF");
        Portal portal = portalOwnedBy(owner);

        as(Role.CUSTOMER, portal.user(), get("/api/v1/customers/me/sales-owner"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").isNotEmpty()).andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.coverName").doesNotExist())
                .andExpect(jsonPath("$.data.email").doesNotExist());
        List<String> options = ids(data(as(Role.CUSTOMER, portal.user(), get("/api/v1/customers/me/sales-options"),
                null).andExpect(status().isOk())));
        assertThat(options).contains(other.toString()).doesNotContain(owner.toString(), manager.toString());
        as(Role.SALES_STAFF, owner, get("/api/v1/customers/me/sales-owner"), null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("CUSTOMER_NOT_FOUND"));
    }

    @Test
    @DisplayName("§10.5 / D12: one pending request; the manager sees the canvassing flag and approves; then a 30-day cooldown")
    void aRequestIsApprovedThenCoolsDown() throws Exception {
        UUID owner = staff.staff("SALES_STAFF");
        UUID preferred = staff.staff("SALES_STAFF");
        Portal portal = portalOwnedBy(owner);
        // The preferred salesperson sent this customer a quote lately: what the listener keeps from order's event
        // (called directly, so no other module's listener sees a quote that does not exist).
        contactListener.on(new QuoteStatusChanged(UUID.randomUUID(), "BG-TEST", portal.customer(), 1, "DRAFT", "SENT",
                preferred, Instant.now()));
        Await.until("the contact is recorded", Duration.ofSeconds(10), () -> jdbc.queryForObject(
                "SELECT count(*) FROM customer.customer_sales_contact WHERE customer_id = ? AND user_id = ?",
                Long.class, portal.customer(), preferred) == 1);
        String mine = "/api/v1/customers/me/sales-owner-change-requests";

        JsonNode asked = data(as(Role.CUSTOMER, portal.user(), post(mine), Map.of("reason", "SLOW_RESPONSE",
                "note", "slow answers", "preferredSalesUserId", preferred)).andExpect(status().isCreated()));
        assertThat(asked.at("/status").asText()).isEqualTo("PENDING");
        assertThat(events.stream(OwnerChangeRequested.class)
                .filter(e -> e.customerId().equals(portal.customer()))).hasSize(1);
        as(Role.CUSTOMER, portal.user(), post(mine), Map.of("reason", "OTHER")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OWNER_CHANGE_REQUEST_PENDING"));

        as(Role.SALES_STAFF, owner, get("/api/v1/sales-owner-change-requests"), null)
                .andExpect(status().isForbidden());
        as(Role.CUSTOMER, portal.user(), get("/api/v1/sales-owner-change-requests"), null)
                .andExpect(status().isForbidden());
        JsonNode row = rowFor(data(as(Role.SALES_MANAGER, manager, get("/api/v1/sales-owner-change-requests")
                .param("status", "PENDING").param("size", "100"), null).andExpect(status().isOk())).at("/items"),
                asked.at("/id").asText());
        assertThat(row.at("/preferredSalesRecentlyContacted").asBoolean()).isTrue();
        assertThat(row.at("/currentOwnerId").asText()).isEqualTo(owner.toString());
        assertThat(row.at("/customerName").asText()).isNotBlank();
        assertThat(row.at("/preferredSalesName").asText()).isNotBlank();

        String decide = "/api/v1/sales-owner-change-requests/" + asked.at("/id").asText();
        as(Role.SALES_MANAGER, manager, post(decide + "/approval"), Map.of("newSalesOwnerId", owner))
                .andExpect(status().isBadRequest());
        as(Role.SALES_STAFF, owner, post(decide + "/approval"), Map.of("newSalesOwnerId", preferred))
                .andExpect(status().isForbidden());
        as(Role.SALES_MANAGER, manager, post(decide + "/approval"), Map.of("newSalesOwnerId", preferred,
                "note", "agreed")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.newSalesOwnerId").value(preferred.toString()));
        assertThat(jdbc.queryForObject("SELECT sales_owner_id FROM customer.customer WHERE id = ?", UUID.class,
                portal.customer())).isEqualTo(preferred);
        assertThat(jdbc.queryForObject("""
                SELECT reason FROM customer.customer_sales_owner_history WHERE customer_id = ?
                 ORDER BY changed_at DESC, id DESC LIMIT 1""", String.class, portal.customer()))
                .isEqualTo("CUSTOMER_REQUEST");
        as(Role.SALES_MANAGER, manager, post(decide + "/rejection"), Map.of("note", "late"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OWNER_CHANGE_REQUEST_ALREADY_DECIDED"));

        as(Role.CUSTOMER, portal.user(), post(mine), Map.of("reason", "OTHER")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OWNER_CHANGE_REQUEST_COOLDOWN"));
        JsonNode own = data(as(Role.CUSTOMER, portal.user(), get(mine), null).andExpect(status().isOk()));
        assertThat(own.at("/0/status").asText()).isEqualTo("APPROVED");
        assertThat(own.at("/0/preferredSalesName").asText()).isNotBlank();
        assertThat(own.at("/0/decidedAt").asText()).isNotBlank();
        assertThat(own.at("/0/decidedBy").isMissingNode()).as("the customer is not told who decided").isTrue();

        write("UPDATE customer.sales_owner_change_request SET decided_at = NOW() - INTERVAL '31 days' WHERE id = ?",
                UUID.fromString(asked.at("/id").asText()));
        as(Role.CUSTOMER, portal.user(), post(mine), Map.of("reason", "NOT_SATISFIED"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("§10.5: a rejection needs a note the customer reads; no recent contact means no canvassing flag")
    void aRequestIsRejected() throws Exception {
        UUID owner = staff.staff("SALES_STAFF");
        UUID preferred = staff.staff("SALES_STAFF");
        Portal portal = portalOwnedBy(owner);
        String mine = "/api/v1/customers/me/sales-owner-change-requests";
        as(Role.CUSTOMER, portal.user(), post(mine), Map.of("reason", "PREFER_KNOWN_CONTACT",
                "preferredSalesUserId", owner)).andExpect(status().isBadRequest());
        as(Role.CUSTOMER, portal.user(), post(mine), Map.of("reason", "PREFER_KNOWN_CONTACT",
                "preferredSalesUserId", manager)).andExpect(status().isUnprocessableEntity());
        String id = data(as(Role.CUSTOMER, portal.user(), post(mine), Map.of("reason", "PREFER_KNOWN_CONTACT",
                "preferredSalesUserId", preferred)).andExpect(status().isCreated())).at("/id").asText();

        JsonNode row = rowFor(data(as(Role.SALES_MANAGER, manager, get("/api/v1/sales-owner-change-requests")
                .param("status", "PENDING").param("size", "100"), null)).at("/items"), id);
        assertThat(row.at("/preferredSalesRecentlyContacted").asBoolean()).isFalse();

        String decide = "/api/v1/sales-owner-change-requests/" + id;
        as(Role.SALES_MANAGER, manager, post(decide + "/rejection"), Map.of("note", " "))
                .andExpect(status().isBadRequest());
        as(Role.SALES_MANAGER, manager, post(decide + "/rejection"), Map.of("note", "your salesperson stays"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REJECTED"));
        assertThat(jdbc.queryForObject("SELECT sales_owner_id FROM customer.customer WHERE id = ?", UUID.class,
                portal.customer())).isEqualTo(owner);
        JsonNode own = data(as(Role.CUSTOMER, portal.user(), get(mine), null));
        assertThat(own.at("/0/status").asText()).isEqualTo("REJECTED");
        assertThat(own.at("/0/decisionNote").asText()).isEqualTo("your salesperson stays");
        as(Role.SALES_MANAGER, manager, post("/api/v1/sales-owner-change-requests/" + UUID.randomUUID()
                + "/rejection"), Map.of("note", "x")).andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------------------------------------------

    private record Portal(UUID user, UUID customer) {
    }

    private Portal portalOwnedBy(UUID owner) {
        UUID user = staff.staff("CUSTOMER");
        UUID customer = customer();
        write("UPDATE customer.customer SET user_id = ? WHERE id = ?", user, customer);
        ownership.assign(new AssignSalesOwnerCommand(customer, owner, SalesOwnerChangeReason.OTHER, "test owner",
                false, ownership.ownerOf(customer).orElseThrow().version(), null));
        return new Portal(user, customer);
    }

    private Map<String, Object> form(String taxCode) {
        Map<String, Object> form = new HashMap<>(Map.of("companyName", "Cong ty Lead " + taxCode, "taxCode", taxCode,
                "contactName", "Nguyen Van A", "phone", "0912345678", "email", "lead-" + taxCode + "@example.test",
                "interests", List.of("CUP", "BOX"), "consent", true));
        form.put("contactTitle", "Buyer");
        form.put("monthlyVolume", "50,000 cups");
        return form;
    }

    private ResultActions submit(Map<String, Object> form, String address) throws Exception {
        return http.anonymous(post("/api/v1/public/leads").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(form)).with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                }));
    }

    /** A source address of its own, so each test has a rate-limit bucket of its own. */
    private static String address() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
    }

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

    /** A response body without what differs between any two requests (the correlation id, the time). */
    private JsonNode comparable(String body) throws Exception {
        JsonNode node = mapper.readTree(body);
        if (node instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
            object.remove(List.of("correlationId", "timestamp", "traceId", "path"));
        }
        return node;
    }

    private static List<String> ids(JsonNode rows) {
        List<String> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.has("userId") ? row.at("/userId").asText() : row.at("/id").asText()));
        return ids;
    }

    private static JsonNode rowFor(JsonNode rows, String id) {
        for (JsonNode row : rows) {
            if (row.at("/id").asText().equals(id)) {
                return row;
            }
        }
        throw new AssertionError("row " + id + " not listed");
    }

    private UUID leadWithTaxCode(String code) {
        return jdbc.queryForObject("SELECT id FROM customer.lead WHERE tax_code = ? ORDER BY submitted_at DESC LIMIT 1",
                UUID.class, code);
    }

    private long leadsWithTaxCode(String code) {
        return jdbc.queryForObject("SELECT count(*) FROM customer.lead WHERE tax_code = ?", Long.class, code);
    }

    private UUID customer() {
        return tx.execute(status -> ReferenceRows.customer(entityManager));
    }

    private static String taxCode() {
        return String.valueOf(1_000_000_000L + ThreadLocalRandom.current().nextLong(8_999_999_999L));
    }

    private void write(String sql, Object... values) {
        tx.executeWithoutResult(status -> jdbc.update(sql, values));
    }
}
