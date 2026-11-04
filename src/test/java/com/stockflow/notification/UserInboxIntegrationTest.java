package com.stockflow.notification;

import com.stockflow.common.id.Identifiers;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.notification.internal.service.UserInbox;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The in-app inbox (SCRUM-147, G5a-3 S1a + S2) through the real filter chain and permission aspect: posting
 * through {@link Inbox}, the four {@code /api/v1/notifications/me} endpoints, the purge, and above all that
 * a user reaches only their own rows - a staff member and a customer each see theirs and nothing else.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
// The same properties and mock as the other security integration tests, so the application context is shared.
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000"})
class UserInboxIntegrationTest {

    private static final String ME = "/api/v1/notifications/me";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired Inbox inbox;
    @Autowired UserInbox userInbox;

    private UUID planner;
    private UUID clerk;
    private UUID customer;
    private String plannerToken;
    private String clerkToken;
    private String customerToken;

    @BeforeEach
    void setUp() {
        planner = account("INVENTORY_PLANNER");
        clerk = account("WAREHOUSE_STAFF");
        customer = account("CUSTOMER");
        plannerToken = token(planner, "INVENTORY_PLANNER");
        clerkToken = token(clerk, "WAREHOUSE_STAFF");
        customerToken = token(customer, "CUSTOMER");
    }

    // ------------------------------------------------------------------ fixtures

    private UUID account(String role) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                SELECT ?, ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", Identifiers.newId(), user, role));
        return user;
    }

    private String token(UUID user, String role) {
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(user.toString()).claim("roles", List.of(role)).build());
        return "Bearer " + value;
    }

    private static InboxMessage message(String title, String dedupeKey) {
        return new InboxMessage("INVENTORY_ALERT", InboxSeverity.WARN, title, "Detail of " + title,
                "/admin/inventory/alerts", "STOCK_ALERT", "alert-" + dedupeKey, dedupeKey);
    }

    /** A row at a chosen age, for ordering and purge tests: {@code createdDaysAgo}, read {@code readDaysAgo} (or unread). */
    private UUID row(UUID recipient, String title, int createdDaysAgo, Integer readDaysAgo) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO notification.user_notification (id, recipient_id, category, severity, title, dedupe_key,
                                                            created_at, read_at)
                VALUES (?, ?, 'INVENTORY_ALERT', 'INFO', ?, ?, NOW() - make_interval(days => ?),
                        CASE WHEN ?::int IS NULL THEN NULL ELSE NOW() - make_interval(days => ?::int) END)""",
                id, recipient, title, "row-" + id, createdDaysAgo, readDaysAgo, readDaysAgo));
        return id;
    }

    private long rows(UUID recipient) {
        return jdbc.queryForObject("SELECT count(*) FROM notification.user_notification WHERE recipient_id = ?",
                Long.class, recipient);
    }

    private boolean isRead(UUID id) {
        return jdbc.queryForObject("SELECT read_at IS NOT NULL FROM notification.user_notification WHERE id = ?",
                Boolean.class, id);
    }

    private ResultActions list(String token, String query) throws Exception {
        return mvc.perform(get(ME + query).header("Authorization", token));
    }

    private ResultActions markRead(String token, UUID id) throws Exception {
        return mvc.perform(post(ME + "/" + id + "/read").header("Authorization", token));
    }

    // ------------------------------------------------------------------ posting

    private int posted(List<UUID> recipients, InboxMessage message) {
        Integer created = tx.execute(s -> inbox.post(recipients, message));
        return created == null ? -1 : created;
    }

    @Test
    void postsOneRowPerRecipientAndTheSameKeyOnlyOnce() {
        InboxMessage first = message("3 lots near expiry", "EXPIRING:HCM:day1");

        assertThat(posted(List.of(planner, clerk), first)).isEqualTo(2);
        assertThat(rows(planner)).isEqualTo(1);
        assertThat(rows(clerk)).isEqualTo(1);

        // Same key again, even with other words: the first message stands (once per day, once per episode).
        assertThat(posted(List.of(planner, clerk), message("4 lots near expiry", "EXPIRING:HCM:day1")))
                .isZero();
        assertThat(rows(planner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM notification.user_notification WHERE recipient_id = ?",
                String.class, planner)).isEqualTo("3 lots near expiry");

        // Another key is another message; a recipient that already has the key is skipped, a new one gets it.
        assertThat(posted(List.of(planner), message("5 lots near expiry", "EXPIRING:HCM:day2")))
                .isEqualTo(1);
        assertThat(posted(List.of(planner, customer), first)).isEqualTo(1);
        assertThat(rows(planner)).isEqualTo(2);
        assertThat(rows(customer)).isEqualTo(1);
    }

    @Test
    void theSameKeyForTwoRecipientsIsTwoMessages() {
        InboxMessage m = message("Low stock", "REORDER:ALL:day1");

        tx.executeWithoutResult(s -> inbox.post(Arrays.asList(planner, null, planner, clerk), m));

        assertThat(rows(planner)).isEqualTo(1);
        assertThat(rows(clerk)).isEqualTo(1);
    }

    @Test
    void concurrentPostsOfOneKeyCreateOneRow() throws Exception {
        InboxMessage m = message("Race", "RACE:" + UUID.randomUUID());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> posts = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                posts.add(() -> tx.execute(s -> inbox.post(List.of(planner), m)));
            }
            int created = 0;
            for (Future<Integer> f : pool.invokeAll(posts)) {
                created += f.get();
            }
            assertThat(created).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.user_notification WHERE dedupe_key = ?",
                    Long.class, m.dedupeKey())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anUnknownRecipientFailsTheWholePostAndLeavesNothing() {
        InboxMessage m = message("Unknown", "UNKNOWN:" + UUID.randomUUID());

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> inbox.post(List.of(planner, UUID.randomUUID()), m)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.user_notification WHERE dedupe_key = ?",
                Long.class, m.dedupeKey())).isZero();
    }

    @Test
    void aMessageIsPostedWhenTheCallersTransactionCommitsAndNotBefore() {
        InboxMessage m = message("Rolled back", "TX:" + UUID.randomUUID());

        tx.executeWithoutResult(s -> {
            inbox.post(List.of(planner), m);
            s.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.user_notification WHERE dedupe_key = ?",
                Long.class, m.dedupeKey())).isZero();
    }

    // ------------------------------------------------------------------ reading

    @Test
    void readsOwnMessagesNewestFirstWithoutInternals() throws Exception {
        row(planner, "oldest", 3, null);
        row(planner, "middle", 2, 1);
        row(planner, "newest", 1, null);

        list(plannerToken, "").andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].title").value("newest"))
                .andExpect(jsonPath("$.data.items[1].title").value("middle"))
                .andExpect(jsonPath("$.data.items[2].title").value("oldest"))
                .andExpect(jsonPath("$.data.items[0].read").value(false))
                .andExpect(jsonPath("$.data.items[1].read").value(true))
                .andExpect(jsonPath("$.data.items[1].readAt").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].category").value("INVENTORY_ALERT"))
                .andExpect(jsonPath("$.data.items[0].severity").value("INFO"))
                .andExpect(jsonPath("$.data.items[0].dedupeKey").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].recipientId").doesNotExist())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.unreadCount").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void unreadOnlyKeepsTheBadgeOfTheWholeInbox() throws Exception {
        row(planner, "unread 1", 3, null);
        row(planner, "read", 2, 1);
        row(planner, "unread 2", 1, null);

        list(plannerToken, "?unreadOnly=true").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.unreadCount").value(2))
                .andExpect(jsonPath("$.data.items[0].title").value("unread 2"))
                .andExpect(jsonPath("$.data.items[1].title").value("unread 1"));
    }

    @Test
    void pagesWithoutRepeatingOrSkippingARow() throws Exception {
        for (int i = 5; i >= 1; i--) {
            row(planner, "m" + i, i, null);   // m1 is the newest
        }

        list(plannerToken, "?page=0&size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].title").value("m1"))
                .andExpect(jsonPath("$.data.items[1].title").value("m2"))
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.hasPrevious").value(false));
        list(plannerToken, "?page=1&size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].title").value("m3"))
                .andExpect(jsonPath("$.data.items[1].title").value("m4"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.hasPrevious").value(true));
        list(plannerToken, "?page=2&size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("m5"))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        list(plannerToken, "?page=3&size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(5));
    }

    @Test
    void refusesPagingOutOfRange() throws Exception {
        for (String query : new String[] {"?page=-1", "?size=0", "?size=201", "?size=-3"}) {
            list(plannerToken, query).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_PARAMETER"));
        }
        list(plannerToken, "?size=abc").andExpect(status().isBadRequest());
    }

    @Test
    void anInboxWithNothingInItIsEmptyNotAnError() throws Exception {
        list(clerkToken, "").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.unreadCount").value(0));
        mvc.perform(get(ME + "/unread-count").header("Authorization", clerkToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(0));
    }

    @Test
    void unreadCountCountsOnlyTheCallersUnreadMessages() throws Exception {
        row(planner, "a", 2, null);
        row(planner, "b", 1, null);
        row(planner, "c", 1, 0);
        row(clerk, "someone else's", 1, null);

        mvc.perform(get(ME + "/unread-count").header("Authorization", plannerToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(2));
    }

    // ------------------------------------------------------------------ marking read

    @Test
    void marksOneReadAndAgainWithoutChangingWhenItWasRead() throws Exception {
        UUID id = row(planner, "to read", 1, null);
        assertThat(isRead(id)).isFalse();

        markRead(plannerToken, id).andExpect(status().isNoContent());
        assertThat(isRead(id)).isTrue();
        var firstRead = jdbc.queryForObject("SELECT read_at FROM notification.user_notification WHERE id = ?",
                java.sql.Timestamp.class, id);

        markRead(plannerToken, id).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT read_at FROM notification.user_notification WHERE id = ?",
                java.sql.Timestamp.class, id)).isEqualTo(firstRead);
        list(plannerToken, "?unreadOnly=true").andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void anotherUsersMessageIsNotFoundAndStaysUnread() throws Exception {
        UUID plannersMessage = row(planner, "private", 1, null);

        // A staff member and a customer both get the same 404 as for an id that does not exist.
        markRead(clerkToken, plannersMessage).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOTIFICATION_NOT_FOUND"));
        markRead(customerToken, plannersMessage).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOTIFICATION_NOT_FOUND"));
        markRead(plannerToken, UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOTIFICATION_NOT_FOUND"));

        assertThat(isRead(plannersMessage)).isFalse();
    }

    @Test
    void marksAllReadOnlyForTheCaller() throws Exception {
        UUID a = row(planner, "a", 3, null);
        UUID b = row(planner, "b", 2, null);
        row(planner, "already read", 2, 1);
        UUID clerksMessage = row(clerk, "clerk's", 1, null);
        UUID customersMessage = row(customer, "customer's", 1, null);

        mvc.perform(post(ME + "/read-all").header("Authorization", plannerToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(2));

        assertThat(isRead(a)).isTrue();
        assertThat(isRead(b)).isTrue();
        assertThat(isRead(clerksMessage)).isFalse();
        assertThat(isRead(customersMessage)).isFalse();
        mvc.perform(post(ME + "/read-all").header("Authorization", plannerToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(0));
    }

    // ------------------------------------------------------------------ isolation

    @Test
    void aStaffMemberAndACustomerEachSeeOnlyTheirOwn() throws Exception {
        inbox.post(List.of(planner), message("planner only", "ISO:planner:" + UUID.randomUUID()));
        tx.executeWithoutResult(s -> {
            inbox.post(List.of(planner), message("planner only", "ISO:planner:" + UUID.randomUUID()));
            inbox.post(List.of(clerk), message("clerk only", "ISO:clerk:" + UUID.randomUUID()));
            inbox.post(List.of(customer), message("customer only", "ISO:customer:" + UUID.randomUUID()));
        });

        list(plannerToken, "").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items[*].title").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("planner only"))));
        list(clerkToken, "").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("clerk only"));
        list(customerToken, "").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("customer only"))
                .andExpect(jsonPath("$.data.unreadCount").value(1));
    }

    @Test
    void theCallerIsTheTokensSubjectNeverARequestParameter() throws Exception {
        row(planner, "planner's", 1, null);

        // There is no way to ask for another user's inbox: unknown parameters are ignored, not honoured.
        mvc.perform(get(ME + "?recipientId=" + planner + "&userId=" + planner).header("Authorization", clerkToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    // ------------------------------------------------------------------ access

    @Test
    void everyRoleThatCanSignInHasTheBell() {
        // 11 staff roles (V20261014000400) + CUSTOMER (V20260903000100), each with VIEW_PAGE, READ and UPDATE.
        List<String> roles = List.of("WAREHOUSE_STAFF", "WAREHOUSE_MANAGER", "INVENTORY_PLANNER", "QC_STAFF",
                "SALES_STAFF", "ORDER_COORDINATOR", "ECOMMERCE_ADMIN", "PROCUREMENT_STAFF", "ACCOUNTANT",
                "PRODUCTION_STAFF", "SYSTEM_ADMIN", "CUSTOMER");
        for (String role : roles) {
            assertThat(jdbc.queryForList("""
                    SELECT p.action FROM identity.role_permission rp
                    JOIN identity.app_role r ON r.id = rp.role_id
                    JOIN identity.permission p ON p.id = rp.permission_id
                    WHERE r.code = ? AND p.resource = 'notification-notifications' ORDER BY p.action""",
                    String.class, role)).as(role).containsExactly("READ", "UPDATE", "VIEW_PAGE");
        }
    }

    @Test
    void everyStaffRoleAndTheCustomerCanOpenTheirOwnInbox() throws Exception {
        for (String role : List.of("WAREHOUSE_STAFF", "WAREHOUSE_MANAGER", "INVENTORY_PLANNER", "QC_STAFF",
                "SALES_STAFF", "ORDER_COORDINATOR", "ECOMMERCE_ADMIN", "PROCUREMENT_STAFF", "ACCOUNTANT",
                "PRODUCTION_STAFF", "SYSTEM_ADMIN", "CUSTOMER")) {
            String bearer = token(account(role), role);
            list(bearer, "").andExpect(status().isOk());
            mvc.perform(post(ME + "/read-all").header("Authorization", bearer)).andExpect(status().isOk());
        }
    }

    @Test
    void aRoleWithoutTheGrantIsRefusedAndNoTokenIsUnauthorized() throws Exception {
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO identity.app_role (id, code, name, description, version, created_at)
                VALUES (?, 'IT_NO_BELL', 'No bell', 'Holds no permission', 0, NOW())
                ON CONFLICT (code) DO NOTHING""", Identifiers.newId()));
        String bearer = token(account("IT_NO_BELL"), "IT_NO_BELL");
        UUID id = row(planner, "x", 1, null);

        list(bearer, "").andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.perform(get(ME + "/unread-count").header("Authorization", bearer)).andExpect(status().isForbidden());
        markRead(bearer, id).andExpect(status().isForbidden());
        mvc.perform(post(ME + "/read-all").header("Authorization", bearer)).andExpect(status().isForbidden());

        mvc.perform(get(ME)).andExpect(status().isUnauthorized());
        mvc.perform(post(ME + "/read-all")).andExpect(status().isUnauthorized());
        mvc.perform(post(ME + "/" + id + "/read")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ purge

    @Test
    void purgesReadMessagesOlderThanNinetyDaysAndNothingElse() {
        UUID oldRead = row(planner, "old read", 120, 100);
        UUID boundaryRead = row(planner, "read 89 days ago", 120, 89);
        UUID recentRead = row(planner, "recent read", 5, 1);
        UUID oldUnread = row(planner, "old unread", 400, null);
        UUID otherUsersOldRead = row(clerk, "clerk old read", 200, 95);

        assertThat(userInbox.purgeRead()).isGreaterThanOrEqualTo(2);

        assertThat(exists(oldRead)).isFalse();
        assertThat(exists(otherUsersOldRead)).isFalse();
        assertThat(exists(boundaryRead)).isTrue();
        assertThat(exists(recentRead)).isTrue();
        assertThat(exists(oldUnread)).isTrue();
        // Running it again finds nothing more to delete among these.
        userInbox.purgeRead();
        assertThat(exists(boundaryRead)).isTrue();
        assertThat(exists(oldUnread)).isTrue();
    }

    private boolean exists(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM notification.user_notification WHERE id = ?", Long.class, id) == 1;
    }
}
