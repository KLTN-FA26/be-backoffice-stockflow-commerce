package com.stockflow.notification.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.http.HttpClientProperties;
import com.stockflow.common.http.RestClientFactory;
import com.stockflow.common.security.DataScope;
import com.stockflow.contracts.EInvoiceIssued;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.service.EInvoiceIssuance;
import com.stockflow.invoicing.internal.service.EInvoiceQueryService;
import com.stockflow.invoicing.internal.service.FirstSend;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.support.Await;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.ShipmentRows;
import com.stockflow.support.StaffFixtures;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WF-05 E5 (plan §1.4 "Phát hành xong thì gửi email cho người mua kèm PDF, XML và mã tra cứu") end to end on
 * PostgreSQL with customer e-mail switched ON: a real order is delivered, its draft issued through the sandbox
 * provider, {@code EInvoiceIssued} commits, the listener runs after the commit, the message is read from invoicing
 * ({@code EInvoiceDirectory}: the address the invoice names, the lookup code, the stored PDF and XML), POSTed to
 * Resend's API - a stub here, never the real one - and the outcome lands in {@code notification.delivery_log}. The
 * portal account finds a notice in its inbox either way. Then what goes wrong: the event delivered twice, and a Resend
 * outage that the retry job recovers from with the same idempotency key and the same files.
 *
 * <p>The stub sits under the transport's {@code RestClient} as its request factory, as in
 * {@link CustomerEmailIntegrationTest}; here it fails by message, not by turn, because with customer mail on the order's
 * own steps are mailed too and would otherwise eat a scripted failure meant for the invoice.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {
        "stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=true",
        "stockflow.notification.email.provider=resend",
        "stockflow.notification.email.from=StockFlow <no-reply@stockflow.vn>",
        "stockflow.notification.email.resend.api-key=re_einvoice_mail_test_key",
        "stockflow.notification.email.resend.base-url=https://api.resend.test",
        "stockflow.notification.email.resend.requests-per-second=1000"})
class EInvoiceMailIntegrationTest {

    private static final Duration ASYNC = Duration.ofSeconds(30);
    private static final long PRICE = 2_000;
    private static final ResendStub RESEND = new ResendStub();

    /** The application's client factory, except that Resend's client talks to the stub. */
    @TestBean RestClientFactory restClientFactory;

    static RestClientFactory restClientFactory() {
        return new RestClientFactory(new HttpClientProperties(Duration.ofSeconds(3), Duration.ofSeconds(10))) {
            @Override
            public RestClient.Builder forService(String serviceName, String baseUrl) {
                return ResendEmailTransport.SERVICE.equals(serviceName)
                        ? RestClient.builder().baseUrl(baseUrl).requestFactory(RESEND)
                        : super.forService(serviceName, baseUrl);
            }
        };
    }

    @Autowired OrderService orders;
    @Autowired EInvoiceIssuance issuance;
    @Autowired EInvoiceQueryService queries;
    @Autowired CustomerEmailRetry retry;
    @Autowired ApplicationEventPublisher events;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;
    @Autowired ApplicationContext context;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;

    private ConfirmedOrders confirmedOrders;
    private UUID customer;
    private UUID portalAccount;
    private UUID staff;
    private UUID accountant;
    private String invoiceEmail;
    private String contactEmail;
    private CustomerSkuRows.Issued sku;
    private final List<UUID> placed = new ArrayList<>();

    @BeforeEach
    void aCustomerWithAPortalAccountAndAnInvoiceAddress() {
        confirmedOrders = new ConfirmedOrders(context);
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        portalAccount = new StaffFixtures(jdbc, tx, entityManager).staff("CUSTOMER");
        // Not a reserved domain (example.com, *.test): the transport drops those before the API, as RECIPIENT_RESERVED_DOMAIN.
        invoiceEmail = "ke.toan-" + customer + "@cup-maker.vn";
        contactEmail = jdbc.queryForObject("SELECT email FROM customer.customer WHERE id = ?", String.class, customer);
        confirmedOrders.legalProfile(customer);
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE customer.customer SET tax_code = ?, user_id = ? WHERE id = ?", "03" + (10_000_000
                    + ThreadLocalRandom.current().nextInt(89_999_999)), portalAccount, customer);
            jdbc.update("UPDATE customer.customer_legal_profile SET invoice_email = ? WHERE customer_id = ?",
                    invoiceEmail, customer);
        });
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50, "STANDARD", true);
        CustomerSkuRows.stock(jdbc, tx, sku.sku(), 500);
        RESEND.failing.clear();
    }

    @AfterEach
    void putTheStockBack() {
        WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> jdbc.queryForList(
                "SELECT id FROM inventory.stock_reservation WHERE order_id = ? AND status = 'HELD'", UUID.class, id)
                .forEach(reservation -> inventory.release(reservation, "MANUAL_OVERRIDE"))));
    }

    /** Two cartons ordered, paid, released, packed, handed over and delivered; the draft the listener drew up. */
    private EInvoice.Snapshot draft() {
        ConfirmedOrders.Confirmed confirmed = confirmedOrders.confirmed(customer,
                ConfirmedOrders.Line.cartons(sku, 2, PRICE).withTax(BigDecimal.TEN));
        placed.add(confirmed.orderId());
        confirmedOrders.release(confirmed.orderId(), DemoData.WAREHOUSE_HCM, staff);
        orders.startPicking(confirmed.orderId(), staff);
        OrderSummary order = orders.markPacked(confirmed.orderId(), staff);
        UUID shipment = ShipmentRows.ready(jdbc, tx, order.orderId(), UUID.randomUUID());
        orders.recordHandover(order.orderId(), new ShipmentHandover(shipment,
                List.of(new ShipmentHandover.Line(order.lines().getFirst().lineId(), 100)), Instant.now(), staff));
        orders.recordShipmentDelivered(order.orderId(), shipment, staff);
        Await.until("the draft e-invoice of shipment " + shipment, ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM invoicing.einvoice WHERE shipment_id = ?", Long.class, shipment) == 1);
        return queries.get(jdbc.queryForObject("SELECT id FROM invoicing.einvoice WHERE shipment_id = ?", UUID.class,
                shipment));
    }

    private static String reference(UUID invoice) {
        return "customer-mail:einvoice.issued:" + invoice;
    }

    private List<Map<String, Object>> rows(String reference) {
        return jdbc.queryForList("""
                SELECT status, error, terminal, recipient, provider, provider_message_id, next_attempt_at,
                       external_reference, attempt_number
                FROM notification.delivery_log WHERE operation_reference = ? ORDER BY created_at, id""", reference);
    }

    private long pending(UUID invoice) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM event_publication WHERE completion_date IS NULL AND serialized_event LIKE ?
                   AND listener_id LIKE '%EInvoiceIssuedNotificationListener%'""", Long.class, "%" + invoice + "%");
    }

    private List<ResendStub.Recorded> requests(String reference) {
        return RESEND.requests.stream().filter(r -> reference.equals(r.headers().get("idempotency-key"))).toList();
    }

    private List<Map<String, Object>> portalNotices(UUID invoice) {
        return jdbc.queryForList("""
                SELECT category, severity, title, link, dedupe_key FROM notification.user_notification
                 WHERE recipient_id = ? AND source_id = ?""", portalAccount, invoice.toString());
    }

    private static String decoded(JsonNode attachment) {
        return new String(Base64.getDecoder().decode(attachment.path("content").asText()), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("issued: one e-mail to the address the invoice names, the PDF and the XML attached, the lookup code "
            + "in its text, recorded SENT; the portal account is told; the event again sends and posts nothing twice")
    void sentOnceWithThePdfTheXmlAndTheLookupCode() throws Exception {
        EInvoice.Snapshot draft = draft();
        EInvoice.Snapshot issued = FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);
        assertThat(issued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        String reference = reference(issued.id());
        Await.until("the e-invoice e-mail is recorded", ASYNC, () -> !rows(reference).isEmpty());

        assertThat(rows(reference)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("SENT");
            assertThat(row.get("recipient")).as("the invoice's e-mail, not the contact address")
                    .isEqualTo(invoiceEmail).isNotEqualTo(contactEmail);
            assertThat(row.get("provider")).isEqualTo("RESEND");
            assertThat(row.get("external_reference")).isEqualTo(reference);
            assertThat((String) row.get("provider_message_id")).startsWith("stub-");
        });
        Await.until("the publication is complete", ASYNC, () -> pending(issued.id()) == 0);

        assertThat(requests(reference)).hasSize(1);
        JsonNode body = json.readTree(requests(reference).getFirst().body());
        String number = "%s%s-%08d".formatted(issued.templateCode(), issued.symbol(), issued.invoiceNumber());
        assertThat(body.at("/to")).hasSize(1);
        assertThat(body.at("/to/0").asText()).isEqualTo(invoiceEmail);
        assertThat(body.at("/subject").asText()).isEqualTo("Hoá đơn điện tử " + number);
        assertThat(body.at("/tags/0/value").asText()).isEqualTo("einvoice_issued");
        assertThat(body.at("/text").asText()).contains("Mã tra cứu: " + issued.lookupCode(),
                "Mã của cơ quan thuế: " + issued.taxAuthorityCode(), "Số hoá đơn: " + number, issued.orderNumber(),
                "220.000");
        assertThat(body.at("/html").asText()).contains(issued.lookupCode(), number);
        // The two files, as stored when the invoice was issued: the PDF first, then the XML.
        JsonNode attachments = body.at("/attachments");
        assertThat(attachments).hasSize(2);
        assertThat(attachments.get(0).path("filename").asText()).isEqualTo(number + ".pdf");
        assertThat(attachments.get(0).path("content_type").asText()).isEqualTo("application/pdf");
        assertThat(decoded(attachments.get(0))).startsWith("%PDF-");
        assertThat(attachments.get(1).path("filename").asText()).isEqualTo(number + ".xml");
        assertThat(attachments.get(1).path("content_type").asText()).isEqualTo("application/xml");
        assertThat(decoded(attachments.get(1))).contains("<SHDon>" + issued.invoiceNumber() + "</SHDon>",
                "<MCCQT>" + issued.taxAuthorityCode() + "</MCCQT>");

        // The portal: one notice, in system text, linking to the invoice.
        Await.until("the portal account is told", ASYNC, () -> portalNotices(issued.id()).size() == 1);
        assertThat(portalNotices(issued.id())).singleElement().satisfies(notice -> {
            assertThat(notice.get("category")).isEqualTo("EINVOICE_ISSUED");
            assertThat(notice.get("severity")).isEqualTo("INFO");
            assertThat((String) notice.get("title")).contains(issued.orderNumber());
            assertThat(notice.get("link")).isEqualTo("/einvoices/" + issued.id());
            assertThat(notice.get("dedupe_key")).isEqualTo("einvoice-issued:" + issued.id());
        });

        // The same event again - a redelivery, a restart's republication: found sent, nothing twice.
        tx.executeWithoutResult(s -> events.publishEvent(new EInvoiceIssued(issued.id(), issued.code(),
                issued.orderId(), issued.orderNumber(), issued.shipmentId(), customer, issued.issuedAt())));
        Await.until("the duplicate publication is complete", ASYNC, () -> pending(issued.id()) == 0);
        assertThat(requests(reference)).hasSize(1);
        assertThat(rows(reference)).hasSize(1);
        assertThat(portalNotices(issued.id())).hasSize(1);
        // Asking the provider again about an issued invoice publishes nothing either.
        issuance.reconcile(issued.id());
        assertThat(pending(issued.id())).isZero();
        assertThat(requests(reference)).hasSize(1);
    }

    @Test
    @DisplayName("Resend down: the attempt waits its back-off with the portal already told; the retry job sends it "
            + "once due - the same key, the same two files - and the publication completes")
    void anOutageIsRetriedWithTheSameMessage() throws Exception {
        EInvoice.Snapshot draft = draft();
        String reference = reference(draft.id());
        RESEND.failing.add(reference);
        EInvoice.Snapshot issued = FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);
        assertThat(issued.status()).as("the invoice is issued whatever becomes of its mail")
                .isEqualTo(EInvoiceStatus.ISSUED);
        Await.until("the failure is recorded", ASYNC, () -> !rows(reference).isEmpty());

        assertThat(rows(reference)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("error")).isEqualTo("RESEND_UNAVAILABLE");
            assertThat(row.get("terminal")).isEqualTo(false);
            assertThat(row.get("next_attempt_at")).isNotNull();
            assertThat(row.get("recipient")).isEqualTo(invoiceEmail);
        });
        assertThat(pending(issued.id())).as("kept for the retry").isEqualTo(1);
        Await.until("the portal account is told without waiting for the mail", ASYNC,
                () -> portalNotices(issued.id()).size() == 1);

        // Inside its back-off: the job passes it over.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE event_publication SET publication_date = publication_date - "
                + "interval '5 minutes' WHERE serialized_event LIKE ?", "%" + issued.id() + "%"));
        retry.retry();
        assertThat(requests(reference)).hasSize(1);

        // Resend is back and the attempt is due: sent, with the key and the files the first attempt had.
        RESEND.failing.clear();
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE notification.delivery_log SET next_attempt_at = now() - interval '1 second' WHERE "
                    + "operation_reference = ?", reference);
            // The job holds its ShedLock for at least 30 s after a run; expire it rather than wait (in UTC, like
            // ShedLock's own database-time columns).
            jdbc.update("UPDATE platform.shedlock SET lock_until = timezone('utc', now()) - interval '1 second' WHERE "
                    + "name = 'notification.retryCustomerEmails'");
        });
        retry.retry();
        Await.until("the retry is recorded", ASYNC, () -> rows(reference).size() == 2);
        Await.until("the publication is complete", ASYNC, () -> pending(issued.id()) == 0);

        assertThat(rows(reference)).extracting(row -> row.get("status")).containsExactly("FAILED", "SENT");
        assertThat(rows(reference).get(1).get("attempt_number")).isEqualTo(2);
        assertThat(requests(reference)).hasSize(2);
        JsonNode first = json.readTree(requests(reference).get(0).body());
        JsonNode second = json.readTree(requests(reference).get(1).body());
        assertThat(second.at("/to/0").asText()).isEqualTo(invoiceEmail);
        assertThat(second.at("/attachments")).hasSize(2);
        assertThat(second.at("/attachments")).as("the same files on every attempt").isEqualTo(first.at("/attachments"));
        assertThat(second.at("/subject")).isEqualTo(first.at("/subject"));
        assertThat(second.at("/text").asText()).contains("Mã tra cứu: " + issued.lookupCode());
        assertThat(portalNotices(issued.id())).as("the retry posts no second notice").hasSize(1);

        // Nothing left to retry: another run sends nothing.
        tx.executeWithoutResult(s -> jdbc.update("UPDATE platform.shedlock SET lock_until = timezone('utc', now()) - "
                + "interval '1 second' WHERE name = 'notification.retryCustomerEmails'"));
        retry.retry();
        assertThat(requests(reference)).hasSize(2);
    }

    /**
     * Resend's {@code POST /emails} as the transport's request factory: records each request and answers 200 with a
     * fresh id - or 503 while the request's idempotency key is among {@link #failing}.
     */
    static final class ResendStub implements ClientHttpRequestFactory {

        record Recorded(String uri, Map<String, String> headers, String body) {
        }

        final List<Recorded> requests = new CopyOnWriteArrayList<>();
        final Set<String> failing = ConcurrentHashMap.newKeySet();

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
            return new MockClientHttpRequest(method, uri) {
                @Override
                protected ClientHttpResponse executeInternal() {
                    Map<String, String> headers = new HashMap<>();
                    getHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.getFirst()));
                    requests.add(new Recorded(uri.toString(), headers, getBodyAsString(StandardCharsets.UTF_8)));
                    boolean down = failing.contains(headers.get("idempotency-key"));
                    var response = new MockClientHttpResponse((down ? "{\"name\":\"internal_server_error\"}"
                            : "{\"id\":\"stub-" + UUID.randomUUID() + "\"}").getBytes(StandardCharsets.UTF_8),
                            HttpStatusCode.valueOf(down ? 503 : 200));
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return response;
                }
            };
        }
    }
}
