package com.stockflow.support;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.PermissionDeniedException;
import com.stockflow.common.security.StockflowJwtAuthenticationConverter;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ResolvedAuthorization;
import com.stockflow.common.security.Role;
import com.stockflow.common.security.RoleAuthorizationLookup;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The security decision table, built from the code that enforces it rather than from a list that can go stale.
 *
 * <p>Every request handler is found through {@code RequestMappingHandlerMapping}; the guard on it is read from its
 * real {@code @RequiresPermission} / {@code @PreAuthorize}; the permissions of every role are read from the seeded
 * role matrix. Three questions are asked of that table:</p>
 * <ol>
 *   <li><b>Does every guard refuse a role that lacks the permission?</b> ({@link #everyGuardRefusesEveryRoleThatLacksIt})
 *       The handler is invoked through its Spring proxy with the role's real authorities, so a request body that
 *       would fail validation (400) cannot hide a missing guard.</li>
 *   <li><b>Can a CUSTOMER reach another customer's data?</b> ({@link #aCustomerCannotReachAnotherCustomersData})
 *       The CUSTOMER role holds a handful of staff-looking permissions (orders, designs, customers, receivables) and
 *       its data scope is ALL, so the permission aspect filters nothing: each handler must do its own owner check.
 *       Every such handler is called with a CUSTOMER token and the ids of a <em>different</em> customer's real rows and
 *       must answer 403 or 404 - or be on {@link #OWN_DATA_ONLY} with a reason, in which case its answer must not
 *       contain the other customer's ids.</li>
 *   <li><b>Is every handler without a guard declared?</b> ({@link #everyUnguardedHandlerIsDeclared})</li>
 * </ol>
 *
 * <p><b>Adding an endpoint:</b> if the CUSTOMER role holds its permission and it is not a leak, the first test fails
 * until you either add an owner check or add the handler to {@link #OWN_DATA_ONLY} with a one-line reason. If it has
 * no guard at all, add it to {@link #UNGUARDED}. Reviewers: those two maps are the decision.</p>
 */
@SecuredIntegrationTest
class PermissionMatrixTest {

    /**
     * Handlers a CUSTOMER may call and get a 2xx from, because they answer only about the caller. The test still
     * checks that the answer contains none of another customer's ids.
     */
    private static final Map<String, String> OWN_DATA_ONLY = Map.ofEntries(
            Map.entry("GET /api/v1/customers/me", "the caller's own profile (resolved from the token, no id taken from the request)"),
            Map.entry("PUT /api/v1/customers/me", "edits the caller's own profile (resolved from the token)"),
            Map.entry("GET /api/v1/designs", "list filtered to designs the caller owns (owner predicate in the query)"),
            Map.entry("GET /api/v1/orders/me", "the caller's own orders, selected by the customer profile of the token"),
            Map.entry("GET /api/v1/orders/payment-terms", "payment terms offered to the caller's own customer profile"),
            Map.entry("GET /api/v1/receivables/me", "the caller's own receivables, selected by the customer profile of the token"),
            Map.entry("GET /api/v1/notifications/me", "the caller's own inbox: every query is recipient_id = the token's subject"),
            Map.entry("GET /api/v1/notifications/me/unread-count", "the caller's own unread count (recipient_id = the token's subject)"),
            Map.entry("POST /api/v1/notifications/me/read-all", "marks only the caller's own inbox read (recipient_id = the token's subject)"),
            Map.entry("GET /api/v1/quotes", "portal list: the caller's own customer's quotes, sent ones only (SCRUM-448)"),
            Map.entry("GET /api/v1/production/sample-requests", "portal list: the caller's own customer's sample requests (SCRUM-394)"));

    /**
     * Handlers with neither {@code @RequiresPermission} nor {@code @PreAuthorize}. They are open to anyone signed in
     * (or to anyone at all where the filter chain says permitAll); none of them takes another user's id.
     */
    private static final Map<String, String> UNGUARDED = Map.ofEntries(
            Map.entry("POST /api/v1/identity/auth/login", "public: this is what mints a token"),
            Map.entry("POST /api/v1/customers/portal-activation",
                    "public: a Sales-invited contact sets a password; the one-time token is the authorisation (permitAll for POST, SCRUM-450)"),
            Map.entry("GET /oauth2/jwks", "public: the signing keys the resource server validates tokens with (permitAll)"),
            Map.entry("GET /api/v1/public/products/{productId}/gallery", "public: published product images (permitAll)"),
            Map.entry("GET /api/v1/catalog/products", "any signed-in user: published storefront catalog, no private data"),
            Map.entry("GET /api/v1/catalog/products/availability",
                    "signed-in only (SCRUM-429): stock availability by SKU for an ACTIVE linked customer, checked in the service"),
            Map.entry("GET /api/v1/catalog/products/prices",
                    "signed-in CUSTOMER: the caller's own ACTIVE customer's prices, the customer taken from the token; staff 403 (StorefrontPriceService)"),
            Map.entry("GET /api/v1/catalog/products/{slug}", "any signed-in user: one published product"),
            Map.entry("GET /api/v1/catalog/products/{slug}/gallery", "any signed-in user: published images of one product"),
            Map.entry("GET /api/v1/catalog/search/suggestions", "any signed-in user: titles of published products, no price or stock"),
            Map.entry("GET /api/v1/identity/me", "any signed-in user: reads the caller's own account from the token"),
            Map.entry("GET /api/v1/identity/me/permissions", "any signed-in user: the caller's own roles and permissions"),
            Map.entry("GET /api/v1/identity/auth/sessions", "any signed-in user: the caller's own sessions"),
            Map.entry("POST /api/v1/identity/auth/logout", "any signed-in user: ends the caller's own session"),
            Map.entry("POST /api/v1/identity/auth/logout-others", "any signed-in user: ends the caller's other sessions"),
            Map.entry("PUT /api/v1/identity/auth/password", "any signed-in user: changes the caller's own password (current password required)"));

    /**
     * Deprecated routes kept for existing clients, and the canonical route each one stands for. The old one must carry
     * exactly the guard of the new one: a deprecated alias is the classic place for a permission to drift. (A
     * variant's logistics and inventory-control routes, old and new, are gone: both are edited on the inventory item,
     * {@code /api/v1/inventory/items/{id}}.)
     */
    private static final Map<String, String> DEPRECATED_ALIASES = Map.of(
            "GET /api/v1/products/{productId}/skus/{sku}/base-price", "GET /api/v1/products/{productId}/variants/{variantId}/base-price",
            "PUT /api/v1/products/{productId}/skus/{sku}/base-price", "PUT /api/v1/products/{productId}/variants/{variantId}/base-price");

    /** A valid body for the customer-reachable write handlers, so validation (400) cannot hide a missing owner check. */
    private Map<String, String> attackBodies() {
        String address = """
                {"type":"SHIPPING","recipientName":"Mallory","phone":"0901234567","line1":"1 Evil St","wardCode":"26734",
                 "wardName":"Ward","provinceCode":"79","provinceName":"Province","countryCode":"VN","defaultAddress":false,"version":0}""";
        return Map.ofEntries(
                Map.entry("POST /api/v1/customers/{customerId}/addresses", address),
                Map.entry("PUT /api/v1/customers/{customerId}/addresses/{addressId}", address),
                Map.entry("PUT /api/v1/customers/{customerId}", "{\"fullName\":\"Mallory\",\"version\":0}"),
                Map.entry("PUT /api/v1/customers/me", "{\"fullName\":\"Attacker renamed\",\"version\":0}"),
                Map.entry("POST /api/v1/orders", "{\"requestId\":\"" + UUID.randomUUID() + "\",\"customerId\":\"" + victimCustomer
                        + "\",\"lines\":[{\"sku\":\"SOFA-3S-GREY\",\"quantity\":1,\"unitPrice\":1000}]}"),
                Map.entry("PUT /api/v1/designs/{id}/specification", "{\"version\":0,\"spec\":\"stolen\"}"),
                Map.entry("POST /api/v1/designs/{id}/change-requests", "{\"version\":0,\"reason\":\"stolen\"}"),
                Map.entry("POST /api/v1/designs/{id}/confirmation-withdrawals", "{\"version\":0,\"reason\":\"stolen\"}"),
                Map.entry("POST /api/v1/designs/{id}/confirmations", "{\"version\":0,\"artifactId\":\"" + UUID.randomUUID() + "\"}"),
                // SCRUM-448 / 394 / R1: valid bodies, so only the owner check can refuse the victim's quote, sample and order.
                Map.entry("POST /api/v1/quotes/{quoteId}/acceptance", "{\"version\":0}"),
                Map.entry("POST /api/v1/quotes/{quoteId}/change-request", "{\"version\":0,\"feedback\":\"stolen\"}"),
                Map.entry("POST /api/v1/production/sample-requests/{sampleRequestId}/change-request", "{\"feedback\":\"stolen\"}"),
                Map.entry("POST /api/v1/orders/{orderId}/reorder", "{\"requestId\":\"" + UUID.randomUUID() + "\"}"));
    }


    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired RoleAuthorizationLookup roles;
    @Autowired StockflowJwtAuthenticationConverter converter;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    UUID attackerUser, victimUser, victimCustomer, victimAddress, victimOrder, victimDesign, victimReceivable, victimQuote,
            victimSampleRequest;

    @BeforeEach
    void victim() {
        attackerUser = tx.execute(s -> ReferenceRows.user(entityManager));
        victimUser = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID attackerCustomer = Identifiers.newId();
        victimCustomer = Identifiers.newId();
        victimAddress = Identifiers.newId();
        victimOrder = Identifiers.newId();
        victimDesign = Identifiers.newId();
        victimReceivable = Identifiers.newId();
        victimQuote = Identifiers.newId();
        victimSampleRequest = Identifiers.newId();
        UUID snapshot = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at) VALUES (?, ?, 'Attacker', ?, 'ACTIVE', 0, NOW())",
                    attackerCustomer, attackerUser, "pm-" + attackerCustomer + "@example.com");
            jdbc.update("INSERT INTO customer.customer (id, user_id, full_name, email, status, version, created_at) VALUES (?, ?, 'Victim', ?, 'ACTIVE', 0, NOW())",
                    victimCustomer, victimUser, "pm-" + victimCustomer + "@example.com");
            jdbc.update("""
                    INSERT INTO customer.address (id, customer_id, type, recipient_name, phone, line1, ward, ward_code, province, province_code, country, is_default, version, created_at)
                    VALUES (?, ?, 'SHIPPING', 'Victim', '0900000000', '1 Victim St', 'Ward', '26734', 'Province', '79', 'VN', TRUE, 0, NOW())""",
                    victimAddress, victimCustomer);
            jdbc.update("""
                    INSERT INTO design.design_draft (id, customer_id, product_id, owner_user_id, status, created_at, current_artifacts)
                    VALUES (?, ?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", victimDesign, victimCustomer, DemoData.PRODUCT_SOFA, victimUser);
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at, version, created_at)
                    VALUES (?, ?, ?, ?, 'ON_HOLD', 1000, 'VND', NOW(), 0, NOW())""",
                    victimOrder, "SO-20991231-%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000)), victimCustomer, UUID.randomUUID());
            jdbc.update("INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency) VALUES (?, ?, 'TABLE-OAK-160', 1, 1000, 'VND')",
                    Identifiers.newId(), victimOrder);
            jdbc.update("""
                    INSERT INTO payment.receivable (id, order_id, customer_id, amount, currency, issued_at, due_date, status)
                    VALUES (?, ?, ?, 1000, 'VND', NOW(), CURRENT_DATE + 30, 'OPEN')""", victimReceivable, victimOrder, victimCustomer);
            // The victim's own quote, sent to them, and a sample request of theirs (SCRUM-448 / 394).
            int n = ThreadLocalRandom.current().nextInt(1_000_000);
            jdbc.update("""
                    INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, payment_term, valid_until,
                        sent_at, sent_by, version, created_at)
                    VALUES (?, ?, ?, ?, 'SENT', 'PREPAID', NOW() + INTERVAL '7 days', NOW(), ?, 0, NOW())""",
                    victimQuote, "QT-20991231-%06d".formatted(n), UUID.randomUUID(), victimCustomer, victimUser);
            jdbc.update("""
                    INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
                    VALUES (?, ?, ?, 'https://example.com/victim.pdf', NOW(), NOW(), '[]'::jsonb)""",
                    snapshot, victimDesign, "c".repeat(64));
            jdbc.update("""
                    INSERT INTO production.sample_request (id, request_number, customer_id, blank_sku, design_snapshot_id,
                        quantity, status, version, created_at)
                    VALUES (?, ?, ?, 'SOFA-3S-GREY', ?, 1, 'DRAFT', 0, NOW())""",
                    victimSampleRequest, "SR-20991231-%06d".formatted(n), victimCustomer, snapshot);
            // A quote without a line is not a quote (Quote.Offer refuses to restore it, 400 before any owner check):
            // one line of the victim's design with one price tier, so only the owner check can refuse it.
            UUID victimQuoteLine = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO ordering.quote_line (id, quote_id, line_no, blank_sku, design_snapshot_id, quantity)
                    VALUES (?, ?, 1, 'SOFA-3S-GREY', ?, 1)""", victimQuoteLine, victimQuote, snapshot);
            jdbc.update("INSERT INTO ordering.quote_price_tier (quote_line_id, min_quantity, unit_price) VALUES (?, 1, 1000)",
                    victimQuoteLine);
        });
    }

    record Handler(String method, String template, HandlerMethod hm, RequiresPermission rp, boolean preAuthorize) {
        String key() { return method + " " + template; }
    }

    List<Handler> handlers() {
        List<Handler> list = new ArrayList<>();
        for (var e : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod hm = e.getValue();
            if (!hm.getBeanType().getName().startsWith("com.stockflow")) continue;
            RequestMappingInfo info = e.getKey();
            RequiresPermission rp = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), RequiresPermission.class);
            boolean pre = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), PreAuthorize.class) != null;
            Set<RequestMethod> ms = info.getMethodsCondition().getMethods();
            for (String p : info.getPathPatternsCondition().getPatternValues()) {
                list.add(new Handler(ms.isEmpty() ? "GET" : ms.iterator().next().name(), p, hm, rp, pre));
            }
        }
        list.sort(Comparator.comparing(Handler::key));
        return list;
    }

    Object value(Class<?> type, String name, Handler h) {
        if (type == UUID.class || type == String.class) {
            UUID v = switch (name) {
                case "customerId" -> victimCustomer;
                case "addressId" -> victimAddress;
                case "orderId" -> victimOrder;
                case "designId" -> victimDesign;
                case "receivableId" -> victimReceivable;
                case "quoteId" -> victimQuote;
                case "sampleRequestId" -> victimSampleRequest;
                case "id" -> h.template().startsWith("/api/v1/designs") ? victimDesign
                        : h.template().startsWith("/api/v1/orders") ? victimOrder : UUID.randomUUID();
                default -> UUID.randomUUID();
            };
            return v.toString();
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) return 1;
        if (type == boolean.class || type == Boolean.class) return true;
        if (type == java.time.LocalDate.class) return "2026-01-01";
        if (type.isEnum()) return type.getEnumConstants()[0].toString();
        return "x";
    }

    MockHttpServletRequestBuilder request(Handler h) {
        String path = h.template();
        var names = new org.springframework.core.DefaultParameterNameDiscoverer();
        for (MethodParameter p : h.hm().getMethodParameters()) {
            p.initParameterNameDiscovery(names);
            PathVariable pv = p.getParameterAnnotation(PathVariable.class);
            if (pv != null) {
                String name = pv.name().isEmpty() ? pv.value() : pv.name();
                if (name.isEmpty()) name = p.getParameterName();
                path = path.replace("{" + name + "}", String.valueOf(value(p.getParameterType(), name, h)));
            }
        }
        path = path.replaceAll("\\{[^}/]+}", UUID.randomUUID().toString());
        boolean multipart = Arrays.stream(h.hm().getMethodParameters()).anyMatch(p -> MultipartFile.class.isAssignableFrom(p.getParameterType()));
        MockHttpServletRequestBuilder b;
        if (multipart) {
            var mb = MockMvcRequestBuilders.multipart(HttpMethod.valueOf(h.method()), path);
            for (MethodParameter p : h.hm().getMethodParameters()) {
                p.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());
                if (MultipartFile.class.isAssignableFrom(p.getParameterType())) {
                    mb.file(new MockMultipartFile(p.getParameterName() == null ? "file" : p.getParameterName(), "a.png", "image/png", new byte[] {1}));
                }
            }
            b = mb;
        } else {
            b = MockMvcRequestBuilders.request(HttpMethod.valueOf(h.method()), path);
        }
        for (MethodParameter p : h.hm().getMethodParameters()) {
            p.initParameterNameDiscovery(names);
            RequestParam rq = p.getParameterAnnotation(RequestParam.class);
            if (rq != null && rq.required() && rq.defaultValue().equals("\n\t\t\n\t\t\n\uE000\uE001\uE002\n\t\t\t\t\n") && !MultipartFile.class.isAssignableFrom(p.getParameterType())) {
                String name = rq.name().isEmpty() ? rq.value() : rq.name();
                if (name.isEmpty()) name = p.getParameterName();
                b.param(name, String.valueOf(value(p.getParameterType(), name, h)));
            }
            if (p.hasParameterAnnotation(RequestBody.class)) {
                b.contentType(MediaType.APPLICATION_JSON).content(attackBodies().getOrDefault(h.key(), "{}"));
            }
        }
        return b;
    }

    private String send(Handler h) throws Exception {
        var response = new SecuredMvc(mvc, decoder).asCustomer(attackerUser, request(h)).andReturn().getResponse();
        return response.getStatus() + " " + response.getContentAsString();
    }

    @Test
    void aCustomerCannotReachAnotherCustomersData() throws Exception {
        ResolvedAuthorization customer = roles.resolve(List.of(Role.CUSTOMER.authority()));
        assertThat(customer.scope()).as("the premise of this test: a CUSTOMER's data scope filters nothing").isEqualTo(DataScope.ALL);
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Handler h : handlers()) {
            if (h.rp() == null || !customer.permissions().contains(PermissionCode.of(h.rp().resource(), h.rp().action()))) {
                continue;
            }
            checked++;
            String answer = send(h);
            int status = Integer.parseInt(answer.substring(0, 3));
            if (OWN_DATA_ONLY.containsKey(h.key())) {
                if (status >= 500) problems.add(h.key() + " answered " + status);
                for (UUID other : List.of(victimCustomer, victimAddress, victimOrder, victimDesign, victimReceivable,
                        victimQuote, victimSampleRequest)) {
                    if (answer.contains(other.toString())) problems.add(h.key() + " (own-data-only) exposed another customer's id " + other);
                }
            } else if (status != 403 && status != 404) {
                problems.add(h.key() + " answered " + status + " to a CUSTOMER using another customer's ids; expected 403 or 404"
                        + " (add an owner check, or list it in OWN_DATA_ONLY with a reason)");
            }
        }
        assertThat(checked).as("handlers the CUSTOMER role holds a permission for").isGreaterThan(20);
        assertThat(problems).isEmpty();
        // The own-profile edit must not have touched the other customer.
        assertThat(jdbc.queryForObject("SELECT full_name FROM customer.customer WHERE id = ?", String.class, victimCustomer))
                .isEqualTo("Victim");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer.address WHERE customer_id = ?", Long.class, victimCustomer))
                .isEqualTo(1L);
    }

    /**
     * The positive control for the test above: the same rows answer 200 to their owner, so the 404s a CUSTOMER
     * gets are the owner check at work and not a seed row the handlers cannot see.
     */
    @Test
    void theOwnerOfTheSameRowsIsServed() throws Exception {
        SecuredMvc http = new SecuredMvc(mvc, decoder);
        // The victim's quote and sample request are valid rows their owner reads (B-6a): the 404 the attacker gets is
        // the owner check, not a broken fixture.
        for (String path : List.of("/api/v1/orders/" + victimOrder, "/api/v1/orders/me/" + victimOrder,
                "/api/v1/designs/" + victimDesign, "/api/v1/customers/" + victimCustomer + "/addresses",
                "/api/v1/quotes/" + victimQuote, "/api/v1/production/sample-requests/" + victimSampleRequest)) {
            assertThat(http.asCustomer(victimUser, MockMvcRequestBuilders.get(path)).andReturn().getResponse().getStatus())
                    .as("the owner reading " + path).isEqualTo(200);
        }
    }

    @Test
    void theAllowListsHaveNoStaleEntries() {
        ResolvedAuthorization customer = roles.resolve(List.of(Role.CUSTOMER.authority()));
        Map<String, Handler> byKey = new HashMap<>();
        handlers().forEach(h -> byKey.put(h.key(), h));
        OWN_DATA_ONLY.keySet().forEach(key -> {
            assertThat(byKey).as("OWN_DATA_ONLY entry " + key + " no longer exists").containsKey(key);
            Handler h = byKey.get(key);
            assertThat(h.rp()).as(key + " has no @RequiresPermission").isNotNull();
            assertThat(customer.permissions()).as(key + ": the CUSTOMER role no longer holds its permission")
                    .contains(PermissionCode.of(h.rp().resource(), h.rp().action()));
        });
        UNGUARDED.keySet().forEach(key -> {
            assertThat(byKey).as("UNGUARDED entry " + key + " no longer exists").containsKey(key);
            assertThat(byKey.get(key).rp()).as(key + " is guarded now").isNull();
            assertThat(byKey.get(key).preAuthorize()).as(key + " is guarded now").isFalse();
        });
        attackBodies().keySet().forEach(key -> assertThat(byKey).as("attack body for " + key).containsKey(key));
    }

    @Test
    void aDeprecatedRouteKeepsTheGuardOfItsCanonicalRoute() {
        Map<String, Handler> byKey = new HashMap<>();
        handlers().forEach(h -> byKey.put(h.key(), h));
        DEPRECATED_ALIASES.forEach((old, canonical) -> {
            assertThat(byKey).as(old).containsKey(old);
            assertThat(byKey).as(canonical).containsKey(canonical);
            RequiresPermission was = byKey.get(old).rp();
            RequiresPermission is = byKey.get(canonical).rp();
            assertThat(was).as(old + " is guarded").isNotNull();
            assertThat(is).as(canonical + " is guarded").isNotNull();
            assertThat(PermissionCode.of(was.resource(), was.action())).as(old + " vs " + canonical)
                    .isEqualTo(PermissionCode.of(is.resource(), is.action()));
        });
    }

    @Test
    void everyUnguardedHandlerIsDeclared() {
        List<String> undeclared = handlers().stream()
                .filter(h -> h.rp() == null && !h.preAuthorize())
                .map(Handler::key)
                .filter(key -> !UNGUARDED.containsKey(key))
                .toList();
        assertThat(undeclared).as("handlers with no @RequiresPermission and no @PreAuthorize that are not in UNGUARDED").isEmpty();
    }

    /**
     * Invoked through the Spring proxy with the real authorities of each role, not over HTTP: Spring validates a
     * request body before the permission aspect runs, so an empty body yields 400 on every write handler whether or
     * not it is guarded.
     */
    @Test
    void everyGuardRefusesEveryRoleThatLacksIt() throws Exception {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Handler h : handlers()) {
            if (h.rp() == null) continue;
            PermissionCode need = PermissionCode.of(h.rp().resource(), h.rp().action());
            for (Role role : Role.values()) {
                if (roles.resolve(List.of(role.authority())).permissions().contains(need)) continue;
                checked++;
                Throwable outcome = invokeAs(role, h);
                if (!(outcome instanceof PermissionDeniedException) && !(outcome instanceof AccessDeniedException)) {
                    problems.add("%s as %s (lacks %s) was not refused: %s".formatted(h.key(), role, need, outcome));
                }
            }
        }
        assertThat(checked).isGreaterThan(500);
        assertThat(problems).isEmpty();
    }

    /** @return what the call threw, or null when it returned */
    private Throwable invokeAs(Role role, Handler h) throws Exception {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(UUID.randomUUID().toString())
                .claim("roles", List.of(role.authority())).build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(converter.convert(jwt));
        SecurityContextHolder.setContext(context);
        try {
            Method method = h.hm().getMethod();
            method.setAccessible(true);
            Object[] args = Arrays.stream(method.getParameterTypes()).map(PermissionMatrixTest::defaultOf).toArray();
            method.invoke(h.hm().createWithResolvedBean().getBean(), args);
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static Object defaultOf(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return (char) 0;
        return Array.get(Array.newInstance(type, 1), 0);
    }
}
