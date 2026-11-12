package com.stockflow.invoicing.internal.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.SecretCipher;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.invoicing.internal.controller.dto.EInvoiceProviderResponse;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.ProviderAccounts;
import com.stockflow.invoicing.internal.domain.ProviderCallLog.Operation;
import com.stockflow.invoicing.internal.domain.ProviderCallLog.Outcome;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
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
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * WF-05 E3 with everything real but Viettel: the database, the order module, the issuing flow, the sealed account -
 * and SInvoice's API v2 stood up as a stub that answers as Viettel's public description shows (plan §1.3; kltn-docs 21
 * BR-10).
 *
 * <p>What only the whole application can show: the sandbox is the provider in use until an administrator stores an
 * account and switches; the password is sealed in the row and nowhere else; an invoice goes to the provider configured
 * and stays with it when the configuration changes; and a submission whose answer never came is found again by its
 * transaction id - <b>one</b> {@code createInvoice} per invoice, counted at the stub.</p>
 *
 * <p>Viettel's answer is waited for one second here ({@code read-timeout}), so "no answer" is a real read timeout on a
 * socket. The quiet period stays at Viettel's 90 seconds: a transaction the look-up does not find is not sent again
 * while a test runs.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false", "stockflow.invoicing.viettel.read-timeout=PT1S",
        "stockflow.invoicing.viettel.file-pause=PT0.05S"})
class ViettelSInvoiceIntegrationTest {

    private static final String VIETTEL = "VIETTEL_SINVOICE";
    private static final String SANDBOX = "SANDBOX";
    private static final String API = "/services/einvoiceapplication/api";
    private static final String LOGIN = "/auth/login";
    private static final String CREATE = API + "/InvoiceAPI/InvoiceWS/createInvoice/.+";
    private static final String SEARCH = API + "/InvoiceAPI/InvoiceWS/searchInvoiceByTransactionUuid";
    private static final String FILE = API + "/InvoiceAPI/InvoiceUtilsWS/getInvoiceRepresentationFile";
    private static final String USERNAME = "0000000000";
    private static final String PASSWORD = "S3cr3t-Viettel-pw-integration-51ab";
    private static final String TOKEN = "tok-CCCC-3333-integration-session";
    private static final Duration ASYNC = Duration.ofSeconds(20);
    private static final long PRICE = 2_000;
    private static final byte[] PDF = "%PDF-1.7 Viettel's rendering".getBytes(StandardCharsets.UTF_8);

    private static final WireMockServer viettel = new WireMockServer(options().dynamicPort());

    static {
        viettel.start();
    }

    @DynamicPropertySource
    static void viettelIsTheStub(DynamicPropertyRegistry registry) {
        registry.add("stockflow.invoicing.viettel.sandbox-url", viettel::baseUrl);
        // Nothing listens there: a call that went to production by mistake fails instead of leaving the machine.
        registry.add("stockflow.invoicing.viettel.production-url", () -> "http://localhost:1");
    }

    @AfterAll
    static void viettelIsGone() {
        viettel.stop();
    }

    @Autowired OrderService orders;
    @Autowired com.stockflow.inventory.api.InventoryService inventory;
    @Autowired EInvoiceIssuance issuance;
    @Autowired EInvoiceQueryService queries;
    @Autowired EInvoiceSettingsService settings;
    @Autowired EInvoiceProviderAccountService providerAccounts;
    @Autowired EInvoiceProviders providers;
    @Autowired SecretCipher cipher;
    @Autowired FileStorage storage;
    @Autowired ApplicationContext context;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final List<UUID> placed = Collections.synchronizedList(new ArrayList<>());
    private ConfirmedOrders confirmedOrders;
    private UUID customer;
    private UUID staff;
    private UUID accountant;
    private String taxCode;
    private String symbol;
    private int year;
    private CustomerSkuRows.Issued sku;

    @BeforeEach
    void aCustomerAndTheProvidersAsMigrated() {
        viettel.resetAll();
        asMigrated();
        confirmedOrders = new ConfirmedOrders(context);
        customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        staff = tx.execute(s -> ReferenceRows.user(entityManager));
        accountant = tx.execute(s -> ReferenceRows.user(entityManager));
        taxCode = "03" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
        confirmedOrders.legalProfile(customer);
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE customer.customer SET tax_code = ? WHERE id = ?", taxCode, customer);
            jdbc.update("UPDATE customer.customer_legal_profile SET invoice_email = ? WHERE customer_id = ?",
                    "ke.toan@" + customer + ".example.com", customer);
        });
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, customer, 50, "STANDARD", true);
        CustomerSkuRows.stock(jdbc, tx, sku.sku(), 1_000);
        year = LocalDate.now(ConfirmedOrders.VIETNAM).getYear();
        symbol = "C%02dTVT".formatted(year % 100);

        logged.start();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(logged);
    }

    @AfterEach
    void nothingSecretWasLoggedAndTheSandboxIsBack() {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(logged);
        String sealed = jdbc.queryForObject("SELECT COALESCE(secret_sealed, 'no sealed secret') FROM "
                + "invoicing.einvoice_provider_config WHERE provider = ?", String.class, VIETTEL);
        List<String> said = new ArrayList<>();
        for (ILoggingEvent event : List.copyOf(logged.list)) {
            if (event.getLoggerName().startsWith("wiremock") || event.getLoggerName().startsWith("org.eclipse.jetty")
                    || event.getLoggerName().startsWith("com.github.tomakehurst")) {
                continue;
            }
            StringBuilder line = new StringBuilder(event.getLoggerName()).append(": ").append(event.getFormattedMessage());
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                line.append(" | ").append(t.getClassName()).append(": ").append(t.getMessage());
            }
            said.add(line.toString());
        }
        viettel.findAll(anyRequestedFor(anyUrl())).forEach(request -> said.add("url: " + request.getAbsoluteUrl()));
        try {
            assertThat(said).as("21 BR-10: the whole application at DEBUG logs neither the password, nor the token, "
                            + "nor the sealed secret")
                    .noneMatch(line -> line.contains(PASSWORD)).noneMatch(line -> line.contains(TOKEN))
                    .noneMatch(line -> line.contains(sealed));
        } finally {
            WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> placed.forEach(id -> {
                OrderStatus status = orders.findById(id).orElseThrow().status();
                Long packed = jdbc.queryForObject("SELECT count(*) FROM ordering.customer_order WHERE id = ? AND "
                        + "packed_at IS NOT NULL", Long.class, id);
                if (status.canTransitionTo(OrderStatus.CANCELLED) && packed == 0) {
                    orders.cancel(id, new CancelOrderCommand(CancellationReasonCode.OTHER, "test cleanup", null, null));
                } else {
                    jdbc.queryForList("SELECT id FROM inventory.stock_reservation WHERE order_id = ? AND status = "
                            + "'HELD'", UUID.class, id).forEach(held -> inventory.release(held, "MANUAL_OVERRIDE"));
                }
            }));
            asMigrated();
        }
    }

    /** The two providers as V20261120000400 leaves them: the sandbox in use, Viettel SInvoice without an account. */
    private void asMigrated() {
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE invoicing.einvoice_provider_config SET active = FALSE WHERE provider = ?", VIETTEL);
            jdbc.update("UPDATE invoicing.einvoice_provider_config SET active = TRUE WHERE provider = ?", SANDBOX);
            jdbc.update("UPDATE invoicing.einvoice_provider_config SET username = NULL, secret_sealed = NULL, "
                    + "environment = 'SANDBOX' WHERE provider = ?", VIETTEL);
            // No series in use at Viettel either: the first test to switch would otherwise leave one for the next.
            jdbc.update("UPDATE invoicing.einvoice_series SET active = FALSE, version = version + 1 "
                    + "WHERE provider = ? AND active", VIETTEL);
        });
    }

    // ---- the pieces ------------------------------------------------------------------------------------------------

    private ProviderAccounts.Account configured(String provider) {
        return providerAccounts.providers().stream().filter(account -> account.provider().equals(provider)).findFirst()
                .orElseThrow();
    }

    /** What the administrator does to switch: the account, the series as created on SInvoice's portal, then in use. */
    private void viettelInUse() {
        providerAccounts.storeAccount(VIETTEL, "SANDBOX", USERNAME, PASSWORD, configured(VIETTEL).version());
        providerAccounts.useSeries(VIETTEL, "1", symbol, year, "1/001");
        providerAccounts.activate(VIETTEL, configured(VIETTEL).version());
    }

    /** One carton ordered at 10% VAT, paid, released, packed, handed over and delivered; its draft drawn up. */
    private EInvoice.Snapshot deliveredDraft() {
        ConfirmedOrders.Confirmed confirmed = confirmedOrders.confirmed(customer,
                ConfirmedOrders.Line.cartons(sku, 1, PRICE).withTax(new java.math.BigDecimal("10")));
        placed.add(confirmed.orderId());
        confirmedOrders.release(confirmed.orderId(), DemoData.WAREHOUSE_HCM, staff);
        orders.startPicking(confirmed.orderId(), staff);
        orders.markPacked(confirmed.orderId(), staff);
        OrderSummary order = orders.findById(confirmed.orderId()).orElseThrow();
        UUID shipment = ShipmentRows.ready(jdbc, tx, order.orderId(), UUID.randomUUID());
        var line = order.lines().getFirst();
        orders.recordHandover(order.orderId(), new ShipmentHandover(shipment,
                List.of(new ShipmentHandover.Line(line.lineId(), line.quantity())), Instant.now(), staff));
        orders.recordShipmentDelivered(order.orderId(), shipment, staff);
        Await.until("the draft e-invoice of shipment " + shipment, ASYNC, () -> invoiceOf(shipment) != null);
        return queries.get(invoiceOf(shipment));
    }

    private UUID invoiceOf(UUID shipmentId) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM invoicing.einvoice WHERE shipment_id = ? AND type = "
                + "'ORIGINAL' AND status NOT IN ('VOIDED_DRAFT', 'REPLACED')", UUID.class, shipmentId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private List<org.assertj.core.groups.Tuple> calls(UUID invoiceId) {
        return queries.calls(invoiceId).stream().map(call -> tuple(call.operation(), call.outcome())).toList();
    }

    private static ErrorCode code(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private byte[] stored(String key) throws IOException {
        try (InputStream in = storage.download(key)) {
            return in.readAllBytes();
        }
    }

    private int created() {
        return viettel.findAll(postRequestedFor(urlPathMatching(CREATE))).size();
    }

    private void signsIn() {
        viettel.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson(
                "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"bearer\",\"expires_in\":300}")));
    }

    private static String nothingFound() {
        return "{\"errorCode\":null,\"description\":null,\"result\":[]}";
    }

    private String createdAnswer(int number) {
        return """
                {"errorCode":null,"description":null,"result":{"supplierTaxCode":"0000000000","invoiceNo":"%s%d",
                 "transactionID":"1794452400123","reservationCode":"LOOKUP%d","codeOfTax":null}}"""
                .formatted(symbol, number, number);
    }

    private String foundIssued(int number, Instant signedAt) {
        return """
                {"errorCode":null,"description":null,"result":[{"supplierTaxCode":"0000000000","invoiceNo":"%s%d",
                 "reservationCode":"LOOKUP%d","issueDate":%d,"status":"Hóa đơn gốc","exchangeStatus":"Đã cấp mã",
                 "exchangeDes":null,"codeOfTax":"M1-26-VTTEL-%011d"}]}"""
                .formatted(symbol, number, number, signedAt.toEpochMilli(), number);
    }

    private void handsOverTheFiles(byte[] xml) {
        viettel.stubFor(post(urlEqualTo(FILE)).withRequestBody(matchingJsonPath("$.fileType", equalTo("ZIP")))
                .willReturn(okJson(file(zip(xml)))));
        viettel.stubFor(post(urlEqualTo(FILE)).withRequestBody(matchingJsonPath("$.fileType", equalTo("PDF")))
                .willReturn(okJson(file(PDF))));
    }

    private static String file(byte[] bytes) {
        return "{\"errorCode\":null,\"description\":null,\"fileToBytes\":\"" + Base64.getEncoder().encodeToString(bytes)
                + "\",\"paymentStatus\":true,\"fileName\":\"invoice\"}";
    }

    private static byte[] zip(byte[] xml) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("invoice.xml"));
                zip.write(xml);
                zip.closeEntry();
            }
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] signedXml(int number) {
        return ("<HDon><DLHDon Id=\"viettel-signed\"><SHDon>" + number + "</SHDon></DLHDon><DSCKS>signature</DSCKS></HDon>")
                .getBytes(StandardCharsets.UTF_8);
    }

    // ---- which provider --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the sandbox is the provider in use; Viettel SInvoice is there, not in use, and not put in use "
            + "without an account")
    void theSandboxIsTheDefault() {
        assertThat(settings.get().provider().provider()).isEqualTo(SANDBOX);
        assertThat(providerAccounts.providers()).extracting(ProviderAccounts.Account::provider,
                        ProviderAccounts.Account::active, ProviderAccounts.Account::complete)
                .containsExactly(tuple(SANDBOX, true, false), tuple(VIETTEL, false, false));
        assertThat(providers.require(SANDBOX)).isInstanceOf(SandboxProvider.class);
        assertThat(providers.require(VIETTEL)).isInstanceOf(ViettelSInvoiceProvider.class);
        assertThatThrownBy(() -> providers.require("MISA")).extracting(ViettelSInvoiceIntegrationTest::code)
                .isEqualTo(ErrorCode.EINVOICE_PROVIDER_UNAVAILABLE);

        long version = configured(VIETTEL).version();
        assertThatThrownBy(() -> providerAccounts.activate(VIETTEL, version))
                .extracting(ViettelSInvoiceIntegrationTest::code)
                .isEqualTo(ErrorCode.EINVOICE_PROVIDER_ACCOUNT_INCOMPLETE);
        assertThatThrownBy(() -> providerAccounts.activate("MISA", 0))
                .extracting(ViettelSInvoiceIntegrationTest::code).isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> providerAccounts.storeAccount(SANDBOX, "SANDBOX", "someone", "x-password",
                configured(SANDBOX).version())).as("the sandbox has no account")
                .extracting(ViettelSInvoiceIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> providerAccounts.storeAccount(VIETTEL, "STAGING", USERNAME, PASSWORD, version))
                .extracting(ViettelSInvoiceIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> providerAccounts.useSeries(VIETTEL, "1", symbol, year, null))
                .as("every SInvoice call names the template by SInvoice's own code")
                .extracting(ViettelSInvoiceIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> providerAccounts.useSeries(SANDBOX, "1", "C%02dTSC".formatted(year % 100), year,
                "1/001")).extracting(ViettelSInvoiceIntegrationTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(settings.get().provider().provider()).as("nothing above changed who issues").isEqualTo(SANDBOX);
        assertThat(viettel.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    @DisplayName("the account is stored sealed and never handed back; with it Viettel is put in use, and the sandbox "
            + "again after it")
    void theAccountIsSealedAndTheProviderSwitched() {
        ProviderAccounts.Account before = configured(VIETTEL);

        ProviderAccounts.Account stored = providerAccounts.storeAccount(VIETTEL, "SANDBOX", "  " + USERNAME + " ",
                PASSWORD, before.version());

        assertThat(stored.username()).isEqualTo(USERNAME);
        assertThat(stored.complete()).isTrue();
        assertThat(stored.active()).as("storing an account does not switch").isFalse();
        String sealed = jdbc.queryForObject("SELECT secret_sealed FROM invoicing.einvoice_provider_config WHERE "
                + "provider = ?", String.class, VIETTEL);
        assertThat(sealed).startsWith("v1:").doesNotContain(PASSWORD)
                .doesNotContain(Base64.getEncoder().encodeToString(PASSWORD.getBytes(StandardCharsets.UTF_8)));
        assertThat(jdbc.queryForObject("SELECT row_to_json(c)::text FROM invoicing.einvoice_provider_config c WHERE "
                + "provider = ?", String.class, VIETTEL)).as("a dump of the row").doesNotContain(PASSWORD);
        assertThat(cipher.open(sealed, EInvoiceProviderAccountService.secretContext(before.id())))
                .as("it opens for this row").isEqualTo(PASSWORD);
        UUID sandboxRow = configured(SANDBOX).id();
        assertThatThrownBy(() -> cipher.open(sealed, EInvoiceProviderAccountService.secretContext(sandboxRow)))
                .as("and for no other: a sealed value copied onto another row does not open there")
                .isInstanceOf(RuntimeException.class);
        assertThat(Arrays.stream(EInvoiceProviderResponse.class.getRecordComponents()).map(c -> c.getName()))
                .as("what the API answers with has no place for a secret, sealed or not")
                .containsExactly("provider", "environment", "username", "secretSet", "active", "autoIssue", "version");
        assertThat(settings.get().provider().secretSet()).as("the sandbox, still in use, has none").isFalse();

        assertThatThrownBy(() -> providerAccounts.storeAccount(VIETTEL, "PRODUCTION", USERNAME, null, before.version()))
                .as("against the version read").extracting(ViettelSInvoiceIntegrationTest::code)
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        ProviderAccounts.Account moved = providerAccounts.storeAccount(VIETTEL, "PRODUCTION", USERNAME, null,
                stored.version());
        assertThat(moved.environment()).isEqualTo("PRODUCTION");
        assertThat(moved.secretSealed()).as("left out, the password stored is kept").isEqualTo(sealed);

        ProviderAccounts.Account inUse = providerAccounts.activate(VIETTEL, moved.version());
        assertThat(inUse.active()).isTrue();
        assertThat(settings.get().provider().provider()).isEqualTo(VIETTEL);
        assertThat(settings.get().provider().secretSet()).isTrue();
        assertThat(configured(SANDBOX).active()).as("one provider issues at a time").isFalse();
        assertThat(providerAccounts.providers().getFirst().provider()).as("the one in use first").isEqualTo(VIETTEL);

        ProviderAccounts.Account back = providerAccounts.activate(SANDBOX, configured(SANDBOX).version());
        assertThat(back.active()).as("the sandbox needs no account").isTrue();
        assertThat(settings.get().provider().provider()).isEqualTo(SANDBOX);
        assertThat(configured(VIETTEL).complete()).as("the account stays stored for the next switch").isTrue();
        assertThat(viettel.findAll(anyRequestedFor(anyUrl()))).as("configuring calls nobody").isEmpty();
    }

    // ---- issuing through Viettel -----------------------------------------------------------------------------------

    @Test
    @DisplayName("in use, Viettel issues: one createInvoice, the number and codes read back, Viettel's signed XML and "
            + "PDF stored as they came")
    void anInvoiceIsIssuedThroughViettel() throws Exception {
        providerAccounts.storeAccount(VIETTEL, "SANDBOX", USERNAME, PASSWORD, configured(VIETTEL).version());
        providerAccounts.activate(VIETTEL, configured(VIETTEL).version());
        EInvoice.Snapshot draft = deliveredDraft();
        assertThatThrownBy(() -> issuance.issue(draft.id(), draft.version(), accountant))
                .as("no series created at Viettel for the year yet")
                .extracting(ViettelSInvoiceIntegrationTest::code).isEqualTo(ErrorCode.EINVOICE_SERIES_NOT_CONFIGURED);
        assertThat(queries.get(draft.id()).status()).isEqualTo(EInvoiceStatus.DRAFT);
        providerAccounts.useSeries(VIETTEL, "1", symbol, year, "1/001");

        int number = 21;
        Instant signedAt = Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        byte[] xml = signedXml(number);
        signsIn();
        viettel.stubFor(post(urlEqualTo(SEARCH)).inScenario("issue").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(nothingFound())));
        viettel.stubFor(post(urlPathMatching(CREATE)).inScenario("issue").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(createdAnswer(number))).willSetStateTo("held"));
        viettel.stubFor(post(urlEqualTo(SEARCH)).inScenario("issue").whenScenarioStateIs("held")
                .willReturn(okJson(foundIssued(number, signedAt))));
        handsOverTheFiles(xml);

        EInvoice.Snapshot issued = FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);

        assertThat(issued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(issued.provider()).isEqualTo(VIETTEL);
        assertThat(issued.templateCode()).isEqualTo("1");
        assertThat(issued.symbol()).isEqualTo(symbol);
        assertThat(issued.invoiceNumber()).isEqualTo(number);
        assertThat(issued.taxAuthorityCode()).isEqualTo("M1-26-VTTEL-%011d".formatted(number));
        assertThat(issued.lookupCode()).isEqualTo("LOOKUP" + number);
        assertThat(issued.signedAt()).isEqualTo(signedAt);
        assertThat(calls(draft.id())).containsExactly(tuple(Operation.QUERY, Outcome.NOT_FOUND),
                tuple(Operation.SUBMIT, Outcome.ACCEPTED), tuple(Operation.QUERY, Outcome.ISSUED),
                tuple(Operation.DOWNLOAD, Outcome.DOWNLOADED));
        assertThat(queries.calls(draft.id())).allSatisfy(call -> assertThat(call.provider()).isEqualTo(VIETTEL));
        assertThat(created()).isEqualTo(1);

        var request = viettel.findAll(postRequestedFor(urlPathMatching(CREATE))).getFirst();
        assertThat(request.getUrl()).as("under the seller's tax code as the invoice states it")
                .endsWith("/createInvoice/" + issued.seller().taxCode());
        assertThat(request.getHeader("Cookie")).isEqualTo("access_token=" + TOKEN);
        var sent = new com.fasterxml.jackson.databind.ObjectMapper().readTree(request.getBodyAsString());
        assertThat(sent.get("generalInvoiceInfo").get("transactionUuid").asText())
                .isEqualTo(ViettelSInvoiceProvider.transactionUuid(issued.transactionId()));
        assertThat(sent.get("generalInvoiceInfo").get("templateCode").asText()).isEqualTo("1/001");
        assertThat(sent.get("generalInvoiceInfo").get("invoiceSeries").asText()).isEqualTo(symbol);
        assertThat(sent.get("buyerInfo").get("buyerTaxCode").asText()).isEqualTo(taxCode);
        assertThat(sent.get("summarizeInfo").get("totalAmountWithoutTax").decimalValue())
                .as("one carton of 50 at 2,000").isEqualByComparingTo("100000");
        assertThat(sent.get("itemInfo").get(0).get("taxAmount").decimalValue()).isEqualByComparingTo("10000");

        assertThat(stored(issued.xmlFileKey())).as("the invoice in law is Viettel's signed XML, byte for byte - not one "
                + "the system wrote").isEqualTo(xml);
        assertThat(stored(issued.pdfFileKey())).isEqualTo(PDF);

        assertThat(jdbc.queryForList("SELECT row_to_json(c)::text FROM invoicing.einvoice_provider_call c WHERE "
                + "einvoice_id = ?", String.class, draft.id())).as("the call log keeps what happened, never what was "
                + "said").hasSize(4).noneMatch(row -> row.contains(PASSWORD)).noneMatch(row -> row.contains(TOKEN))
                .noneMatch(row -> row.contains(taxCode));
    }

    @Test
    @DisplayName("Viettel's answer never comes: the invoice waits, is not sent again, and is found by its transaction "
            + "id - one createInvoice, one invoice - even after the sandbox is back in use")
    void aLostAnswerNeverIssuesTwice() throws Exception {
        viettelInUse();
        EInvoice.Snapshot draft = deliveredDraft();
        int number = 22;
        byte[] xml = signedXml(number);
        signsIn();
        viettel.stubFor(post(urlEqualTo(SEARCH)).willReturn(okJson(nothingFound())));
        // Viettel takes the invoice in and works on it for longer than its answer is waited for.
        viettel.stubFor(post(urlPathMatching(CREATE)).willReturn(okJson(createdAnswer(number)).withFixedDelay(3_000)));

        EInvoice.Snapshot waiting = FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);

        assertThat(waiting.status()).as("nothing is known: it waits under its transaction id")
                .isEqualTo(EInvoiceStatus.SUBMITTING);
        assertThat(waiting.provider()).isEqualTo(VIETTEL);
        assertThat(calls(draft.id())).containsExactly(tuple(Operation.QUERY, Outcome.NOT_FOUND),
                tuple(Operation.SUBMIT, Outcome.TIMEOUT));
        assertThat(created()).isEqualTo(1);
        assertThatThrownBy(() -> issuance.issue(draft.id(), waiting.version(), accountant))
                .extracting(ViettelSInvoiceIntegrationTest::code).isEqualTo(ErrorCode.EINVOICE_INVALID_TRANSITION);

        // Asked while Viettel is still working: the look-up does not show the invoice yet.
        EInvoice.Snapshot stillWaiting = issuance.reconcile(draft.id());

        assertThat(stillWaiting.status()).isEqualTo(EInvoiceStatus.SUBMITTING);
        assertThat(calls(draft.id())).as("inside Viettel's 90 seconds an unknown transaction is asked about, not sent "
                + "again: two overlapping submissions can become two invoices").containsExactly(
                tuple(Operation.QUERY, Outcome.NOT_FOUND), tuple(Operation.SUBMIT, Outcome.TIMEOUT),
                tuple(Operation.QUERY, Outcome.NOT_FOUND));
        assertThat(created()).isEqualTo(1);

        // The administrator goes back to the sandbox meanwhile; the invoice handed to Viettel stays Viettel's.
        providerAccounts.activate(SANDBOX, configured(SANDBOX).version());
        String transaction = ViettelSInvoiceProvider.transactionUuid(waiting.transactionId());
        viettel.stubFor(post(urlEqualTo(SEARCH)).withRequestBody(equalTo("supplierTaxCode=0000000000&transactionUuid="
                + transaction)).willReturn(okJson(foundIssued(number, Instant.now()))));
        handsOverTheFiles(xml);

        EInvoice.Snapshot issued = issuance.reconcile(draft.id());

        assertThat(issued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(issued.provider()).isEqualTo(VIETTEL);
        assertThat(issued.transactionId()).isEqualTo(waiting.transactionId());
        assertThat(issued.invoiceNumber()).isEqualTo(number);
        assertThat(calls(draft.id())).as("asked, found, fetched - never submitted a second time").containsExactly(
                tuple(Operation.QUERY, Outcome.NOT_FOUND), tuple(Operation.SUBMIT, Outcome.TIMEOUT),
                tuple(Operation.QUERY, Outcome.NOT_FOUND), tuple(Operation.QUERY, Outcome.ISSUED),
                tuple(Operation.DOWNLOAD, Outcome.DOWNLOADED));
        assertThat(created()).as("one delivery, one createInvoice, one invoice").isEqualTo(1);
        assertThat(stored(issued.xmlFileKey())).isEqualTo(xml);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoicing.sandbox_issuance WHERE transaction_id = ?",
                Long.class, waiting.transactionId())).as("the sandbox, back in use, never heard of it").isZero();
    }

    @Test
    @DisplayName("Viettel refuses a wrong tax code: the invoice is REJECTED with Viettel's code, nothing is numbered, "
            + "and it is not sent again")
    void aWrongTaxCodeIsRejected() {
        viettelInUse();
        EInvoice.Snapshot draft = deliveredDraft();
        signsIn();
        viettel.stubFor(post(urlEqualTo(SEARCH)).willReturn(okJson(nothingFound())));
        viettel.stubFor(post(urlPathMatching(CREATE)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"code":400,"message":"BAD_REQUEST_BUYER_TAX_CODE_INVALID",
                         "data":"Mã số thuế người mua không hợp lệ"}""")));

        EInvoice.Snapshot rejected = FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);

        assertThat(rejected.status()).isEqualTo(EInvoiceStatus.REJECTED);
        assertThat(rejected.rejectionCode()).isEqualTo("BAD_REQUEST_BUYER_TAX_CODE_INVALID");
        assertThat(rejected.rejectionMessage()).isEqualTo("Mã số thuế người mua không hợp lệ");
        assertThat(rejected.invoiceNumber()).isNull();
        assertThat(rejected.xmlFileKey()).isNull();
        assertThat(calls(draft.id())).containsExactly(tuple(Operation.QUERY, Outcome.NOT_FOUND),
                tuple(Operation.SUBMIT, Outcome.REJECTED));
        assertThat(queries.calls(draft.id()).getLast().errorCode()).isEqualTo("BAD_REQUEST_BUYER_TAX_CODE_INVALID");

        assertThat(issuance.reconcile(draft.id()).status()).as("settled: reconciling asks nobody")
                .isEqualTo(EInvoiceStatus.REJECTED);
        assertThat(created()).isEqualTo(1);
    }

    @Test
    @DisplayName("Viettel is down (503 on everything): the invoice waits and nothing about it is decided")
    void aProviderThatIsDownDecidesNothing() {
        viettelInUse();
        EInvoice.Snapshot draft = deliveredDraft();
        signsIn();
        viettel.stubFor(post(urlEqualTo(SEARCH)).willReturn(aResponse().withStatus(503)
                .withBody("<html>Service Unavailable</html>")));
        viettel.stubFor(post(urlPathMatching(CREATE)).willReturn(aResponse().withStatus(503)));

        EInvoice.Snapshot waiting = FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);

        assertThat(waiting.status()).isEqualTo(EInvoiceStatus.SUBMITTING);
        assertThat(calls(draft.id())).as("the look-up gave no answer, so the invoice was not sent on a guess")
                .containsExactly(tuple(Operation.QUERY, Outcome.TIMEOUT));
        assertThat(created()).isZero();
        assertThat(issuance.reconcile(draft.id()).status()).isEqualTo(EInvoiceStatus.SUBMITTING);
        assertThat(created()).isZero();
    }

    @Test
    @DisplayName("back on the sandbox, an invoice is issued by the sandbox and Viettel hears nothing")
    void backOnTheSandboxViettelHearsNothing() {
        viettelInUse();
        providerAccounts.activate(SANDBOX, configured(SANDBOX).version());
        EInvoice.Snapshot draft = deliveredDraft();

        EInvoice.Snapshot issued = FirstSend.issue(issuance, queries, draft.id(), draft.version(), accountant);

        assertThat(issued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(issued.provider()).isEqualTo(SANDBOX);
        assertThat(issued.taxAuthorityCode()).startsWith("SBX");
        assertThat(viettel.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }
}
