package com.stockflow.invoicing.internal.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.stockflow.common.config.SellerProperties;
import com.stockflow.common.http.HttpClientProperties;
import com.stockflow.common.http.RestClientFactory;
import com.stockflow.common.security.SecretCipher;
import com.stockflow.common.security.SecretCipherProperties;
import com.stockflow.invoicing.internal.controller.dto.StoreEInvoiceProviderAccountRequest;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Documents;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.InvoiceDocument;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ProviderUnavailableException;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Result;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ResultState;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Submission;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.SubmissionOutcome;
import com.stockflow.invoicing.internal.domain.InvoiceParty;
import com.stockflow.invoicing.internal.domain.ProviderAccounts;
import com.stockflow.invoicing.internal.domain.TaxRateTotal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Viettel SInvoice's API v2 as the adapter calls it, against an HTTP server that answers the way Viettel's public
 * description ("Tài liệu mô tả webservice hóa đơn điện tử", SInvoice V2) and Odoo's {@code l10n_vn_edi_viettel} show
 * (WF-05 E3; plan §1.3). A real server on a socket, not a mocked client: a read timeout and a dropped connection only
 * happen there.
 *
 * <p>What is pinned: the four calls and what each carries; an issued invoice read back; a refusal (a wrong tax code)
 * told apart from no answer (a timeout, a 5xx, a dropped connection, a page that is not JSON); the transaction id
 * SInvoice is given being the same for every retry and question of an attempt, so a retry finds the first invoice
 * instead of making a second; the signed XML taken out of the ZIP, and the PDF; and that the account's password and the
 * token appear in no log line, no exception and no URL - checked after <i>every</i> test here.</p>
 *
 * <p>The stub bodies are the description's samples. Where the description is silent the adapter decides
 * ({@code ViettelSInvoiceAnswers}, "ASSUMPTION"); the tests of those answers say so in their names.</p>
 */
class ViettelSInvoiceContractTest {

    private static final String API = "/services/einvoiceapplication/api";
    private static final String LOGIN = "/auth/login";
    private static final String CREATE = API + "/InvoiceAPI/InvoiceWS/createInvoice/0312345678";
    private static final String SEARCH = API + "/InvoiceAPI/InvoiceWS/searchInvoiceByTransactionUuid";
    private static final String FILE = API + "/InvoiceAPI/InvoiceUtilsWS/getInvoiceRepresentationFile";

    private static final String USERNAME = "0312345678";
    private static final String PASSWORD = "S3cr3t-Viettel-pw-9f2c7e";
    private static final String TOKEN = "tok-AAAA-1111-first-session";
    private static final String NEXT_TOKEN = "tok-BBBB-2222-second-session";
    private static final String SECRETS_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final Duration READ_TIMEOUT = Duration.ofMillis(700);

    private static final String TRANSACTION = "0193a5b0-7c3e-7d4a-8f10-2a6b9c4d5e6f-1";
    private static final String INVOICE_NO = "C26TAA15";
    private static final String LOOKUP = "AXHBNK8I0H";
    private static final String TAX_AUTHORITY_CODE = "M1-26-ABCDE-00000000015";
    private static final long ISSUED_AT = 1_794_452_400_000L;
    private static final byte[] XML = "<HDon><DLHDon Id=\"signed\">C26TAA15</DLHDon></HDon>".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PDF = "%PDF-1.7 the invoice people read".getBytes(StandardCharsets.UTF_8);

    private static final WireMockServer viettel = new WireMockServer(options().dynamicPort());
    /** Where an account whose environment is PRODUCTION is called: another host. */
    private static final WireMockServer production = new WireMockServer(options().dynamicPort());

    private final ObjectMapper json = new ObjectMapper();
    private final ProviderAccounts accounts = mock(ProviderAccounts.class);
    private final SecretCipher cipher = new SecretCipher(new SecretCipherProperties(SECRETS_KEY));
    private final UUID configId = UUID.randomUUID();
    private final MovableClock clock = new MovableClock(EInvoiceFixtures.NOW);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final List<Throwable> thrown = new ArrayList<>();
    private Level stockflowLevel;
    private ViettelSInvoiceProvider provider;

    @BeforeAll
    static void viettelAnswers() {
        viettel.start();
        production.start();
    }

    @AfterAll
    static void viettelIsGone() {
        viettel.stop();
        production.stop();
    }

    @BeforeEach
    void anAccountStoredSealedAndAProviderCallingTheStub() {
        viettel.resetAll();
        production.resetAll();
        account("SANDBOX", cipher.seal(PASSWORD, EInvoiceProviderAccountService.secretContext(configId)));
        when(accounts.providerTemplateCode(ViettelSInvoiceProvider.CODE, "1", "C26TAA")).thenReturn(Optional.of("1/001"));
        when(accounts.providerTemplateCode(eq(ViettelSInvoiceProvider.CODE), isNull(), eq("C26TAA")))
                .thenReturn(Optional.of("1/001"));
        provider = provider(cipher);

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Logger stockflow = (Logger) LoggerFactory.getLogger("com.stockflow");
        stockflowLevel = stockflow.getLevel();
        stockflow.setLevel(Level.DEBUG);
        logged.start();
        root.addAppender(logged);
    }

    @AfterEach
    void nothingSecretWasLoggedThrownOrPutInAUrl() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.detachAppender(logged);
        ((Logger) LoggerFactory.getLogger("com.stockflow")).setLevel(stockflowLevel);

        String sealed = accounts.find(ViettelSInvoiceProvider.CODE).map(ProviderAccounts.Account::secretSealed).orElse("no sealed secret in this test");
        List<String> said = new ArrayList<>(logLines());
        for (Throwable failure : thrown) {
            for (Throwable t = failure; t != null; t = t.getCause()) {
                said.add("thrown: " + t);
            }
        }
        for (WireMockServer server : List.of(viettel, production)) {
            server.findAll(anyRequestedFor(anyUrl())).forEach(request -> said.add("url: " + request.getAbsoluteUrl()));
        }
        assertThat(said).as("21 BR-10: neither the password, nor the token, nor the sealed secret is logged, thrown or "
                        + "put in a URL")
                .noneMatch(line -> line.contains(PASSWORD)).noneMatch(line -> line.contains(TOKEN))
                .noneMatch(line -> line.contains(NEXT_TOKEN)).noneMatch(line -> line.contains(sealed));
    }

    // ---- the pieces ------------------------------------------------------------------------------------------------

    private ViettelSInvoiceProvider provider(SecretCipher opening) {
        HttpClientProperties http = new HttpClientProperties(Duration.ofSeconds(2), Duration.ofSeconds(2));
        ViettelSInvoiceProperties properties = new ViettelSInvoiceProperties(production.baseUrl(), viettel.baseUrl(),
                READ_TIMEOUT, Duration.ofSeconds(90), Duration.ofDays(3), Duration.ofMillis(10), true);
        ViettelSInvoiceClient client = new ViettelSInvoiceClient(new RestClientFactory(http), http, properties, json,
                clock);
        SellerProperties seller = new SellerProperties("CONG TY TNHH BAO BI STOCKFLOW", "0312345678",
                "1 Le Loi, P. Ben Nghe, TP HCM", "Nguyen Van A", "Giam doc");
        return new ViettelSInvoiceProvider(client, properties, accounts, opening, seller, json, clock);
    }

    private void account(String environment, String sealed) {
        when(accounts.find(ViettelSInvoiceProvider.CODE)).thenReturn(Optional.of(new ProviderAccounts.Account(configId,
                ViettelSInvoiceProvider.CODE, environment, USERNAME, sealed, true, false, 3)));
    }

    private static InvoiceDocument invoice(String transactionId) {
        return invoice(transactionId, EInvoiceFixtures.BUYER);
    }

    /** The fixtures' delivery: 20 cartons at 100,000 (10% VAT) and a plate fee of 500,000 (8%). */
    private static InvoiceDocument invoice(String transactionId, InvoiceParty buyer) {
        List<EInvoiceLine> lines = EInvoiceFixtures.lines();
        BigDecimal subtotal = lines.stream().map(EInvoiceLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tax = lines.stream().map(EInvoiceLine::taxAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new InvoiceDocument(transactionId, "EINV-20261112-0001", "1", "C26TAA", EInvoiceFixtures.DELIVERED_ON,
                EInvoiceFixtures.SELLER, buyer, "VND", "TM/CK", "SO-20261112-0001", "HD-20261112-0001", lines,
                TaxRateTotal.of(lines), subtotal, tax, subtotal.add(tax), "Hai trieu bay tram bon muoi nghin dong");
    }

    private static String uuidOf(String transactionId) {
        return ViettelSInvoiceProvider.transactionUuid(transactionId);
    }

    private void signsIn() {
        viettel.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson(loginAnswer(TOKEN))));
    }

    private static String loginAnswer(String token) {
        return """
                {"access_token":"%s","token_type":"bearer","refresh_token":"refresh-%s","expires_in":300,
                 "scope":"openid","iat":1794452000,"invoice_cluster":"cluster1","type":1,"jti":"7c0f"}"""
                .formatted(token, token);
    }

    /** {@code createInvoice}'s documented success: no error, and the number SInvoice gave. */
    private static String created() {
        return """
                {"errorCode":null,"description":null,"result":{"supplierTaxCode":"0312345678","invoiceNo":"%s",
                 "transactionID":"1794452400123","reservationCode":"%s","codeOfTax":null}}""".formatted(INVOICE_NO, LOOKUP);
    }

    /** The look-up's entry of an invoice the tax authority has given its code. */
    private static String foundIssued() {
        return """
                {"errorCode":null,"description":null,"result":[{"supplierTaxCode":"0312345678","invoiceNo":"%s",
                 "reservationCode":"%s","issueDate":%d,"status":"Hóa đơn gốc","exchangeStatus":"Hóa đơn đã được cấp mã",
                 "exchangeDes":null,"codeOfTax":"%s"}]}""".formatted(INVOICE_NO, LOOKUP, ISSUED_AT, TAX_AUTHORITY_CODE);
    }

    private static String foundWithoutTheTaxAuthoritysCode(String exchangeDes) {
        return """
                {"errorCode":null,"description":null,"result":[{"supplierTaxCode":"0312345678","invoiceNo":"%s",
                 "reservationCode":"%s","issueDate":%d,"status":"Hóa đơn gốc","exchangeStatus":"Chờ CQT xử lý",
                 "exchangeDes":%s,"codeOfTax":null}]}""".formatted(INVOICE_NO, LOOKUP, ISSUED_AT, exchangeDes);
    }

    private static String file(byte[] bytes) {
        return """
                {"errorCode":null,"description":null,"fileToBytes":"%s","paymentStatus":true,
                 "fileName":"0312345678-C26TAA15"}""".formatted(Base64.getEncoder().encodeToString(bytes));
    }

    private void handsOver(String fileType, byte[] bytes) {
        viettel.stubFor(post(urlEqualTo(FILE)).withRequestBody(matchingJsonPath("$.fileType", equalTo(fileType)))
                .willReturn(okJson(file(bytes))));
    }

    private static byte[] zip(String name, byte[] bytes) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("readme.txt"));
                zip.write("not the invoice".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry(name));
                zip.write(bytes);
                zip.closeEntry();
            }
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private JsonNode body(LoggedRequest request) {
        try {
            return json.readTree(request.getBodyAsString());
        } catch (IOException notJson) {
            throw new AssertionError("The request's body is not JSON: " + request.getBodyAsString(), notJson);
        }
    }

    private List<LoggedRequest> sent(String path) {
        return viettel.findAll(postRequestedFor(urlPathEqualTo(path)));
    }

    /** Runs a call that must end in "no answer", keeping what it threw for the check after the test. */
    private ProviderUnavailableException noAnswer(Runnable call) {
        try {
            call.run();
        } catch (ProviderUnavailableException unavailable) {
            thrown.add(unavailable);
            return unavailable;
        }
        throw new AssertionError("The call was answered, where the provider gave no answer");
    }

    /** What the application logged; the stub server's own lines are not the application's. */
    private List<String> logLines() {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : List.copyOf(logged.list)) {
            String logger = event.getLoggerName();
            if (logger.startsWith("wiremock") || logger.startsWith("com.github.tomakehurst")
                    || logger.startsWith("org.eclipse.jetty")) {
                continue;
            }
            StringBuilder line = new StringBuilder(logger).append(": ").append(event.getFormattedMessage());
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                line.append(" | ").append(t.getClassName()).append(": ").append(t.getMessage());
            }
            lines.add(line.toString());
        }
        return lines;
    }

    // ---- issuing ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an invoice is issued: signed in once, createInvoice under the seller's tax code with the token as a "
            + "cookie, the invoice as SInvoice's CommonInvoiceInput")
    void issuesAnInvoice() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(CREATE)).willReturn(okJson(created())));

        Submission submission = provider.submit(invoice(TRANSACTION));

        assertThat(submission.outcome()).isEqualTo(SubmissionOutcome.ACCEPTED);
        assertThat(submission.errorCode()).isNull();

        viettel.verify(1, postRequestedFor(urlEqualTo(LOGIN)).withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}")));
        assertThat(sent(LOGIN).getFirst().getHeader("Cookie")).as("the log-in carries no token").isNull();

        List<LoggedRequest> created = sent(CREATE);
        assertThat(created).hasSize(1);
        LoggedRequest request = created.getFirst();
        assertThat(request.getHeader("Cookie")).isEqualTo("access_token=" + TOKEN);
        assertThat(request.getHeader("Content-Type")).startsWith("application/json");
        assertThat(request.getHeader("Authorization")).as("v2 takes the token as a cookie, not Basic credentials")
                .isNull();

        JsonNode sent = body(request);
        JsonNode general = sent.get("generalInvoiceInfo");
        assertThat(general.get("transactionUuid").asText()).isEqualTo(uuidOf(TRANSACTION));
        assertThat(general.get("invoiceType").asText()).isEqualTo("1");
        assertThat(general.get("templateCode").asText()).as("SInvoice's own name of the template").isEqualTo("1/001");
        assertThat(general.get("invoiceSeries").asText()).isEqualTo("C26TAA");
        assertThat(general.get("currencyCode").asText()).isEqualTo("VND");
        assertThat(general.get("adjustmentType").asText()).as("1: an original invoice").isEqualTo("1");
        assertThat(general.get("paymentStatus").asBoolean()).isTrue();
        assertThat(general.get("invoiceIssuedDate").isIntegralNumber()).as("milliseconds since the epoch").isTrue();
        assertThat(Instant.ofEpochMilli(general.get("invoiceIssuedDate").asLong()).atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                .toLocalDate()).as("the invoice's date, in Vietnam").isEqualTo(EInvoiceFixtures.DELIVERED_ON);

        assertThat(sent.get("sellerInfo").get("sellerTaxCode").asText()).isEqualTo("0312345678");
        assertThat(sent.get("sellerInfo").get("sellerLegalName").asText()).isEqualTo(EInvoiceFixtures.SELLER.name());
        JsonNode buyer = sent.get("buyerInfo");
        assertThat(buyer.get("buyerLegalName").asText()).isEqualTo("Cong ty Tra Sua");
        assertThat(buyer.get("buyerTaxCode").asText()).isEqualTo("0398765432");
        assertThat(buyer.get("buyerAddressLine").asText()).isEqualTo(EInvoiceFixtures.BUYER.address());
        assertThat(buyer.get("buyerEmail").asText()).isEqualTo("ke.toan@trasua.example.com");
        assertThat(sent.get("payments").get(0).get("paymentMethodName").asText()).isEqualTo("TM/CK");

        JsonNode items = sent.get("itemInfo");
        assertThat(items).hasSize(2);
        JsonNode goods = items.get(0);
        assertThat(goods.get("selection").asInt()).isEqualTo(1);
        assertThat(goods.get("itemCode").asText()).isEqualTo("CUP12-0398765432-01");
        assertThat(goods.get("itemName").asText()).isEqualTo("Ly giay 12oz");
        assertThat(goods.get("unitName").asText()).isEqualTo("Thung");
        assertThat(goods.get("quantity").decimalValue()).isEqualByComparingTo("20");
        assertThat(goods.get("unitPrice").decimalValue()).isEqualByComparingTo("100000");
        assertThat(goods.get("itemTotalAmountWithoutTax").decimalValue()).isEqualByComparingTo("2000000");
        assertThat(goods.get("taxPercentage").decimalValue()).isEqualByComparingTo("10");
        assertThat(goods.get("taxAmount").decimalValue()).isEqualByComparingTo("200000");
        assertThat(goods.get("itemTotalAmountWithTax").decimalValue()).isEqualByComparingTo("2200000");
        JsonNode fee = items.get(1);
        assertThat(fee.get("itemTotalAmountWithoutTax").decimalValue()).isEqualByComparingTo("500000");
        assertThat(fee.get("taxPercentage").decimalValue()).isEqualByComparingTo("8");
        assertThat(fee.get("taxAmount").decimalValue()).isEqualByComparingTo("40000");

        JsonNode breakdowns = sent.get("taxBreakdowns");
        assertThat(breakdowns).hasSize(2);
        assertThat(breakdowns.get(0).get("taxPercentage").decimalValue()).isEqualByComparingTo("8");
        assertThat(breakdowns.get(0).get("taxableAmount").decimalValue()).isEqualByComparingTo("500000");
        assertThat(breakdowns.get(0).get("taxAmount").decimalValue()).isEqualByComparingTo("40000");
        assertThat(breakdowns.get(1).get("taxPercentage").decimalValue()).isEqualByComparingTo("10");
        assertThat(breakdowns.get(1).get("taxableAmount").decimalValue()).isEqualByComparingTo("2000000");
        assertThat(sent.get("summarizeInfo").get("totalAmountWithoutTax").decimalValue())
                .isEqualByComparingTo("2500000");
    }

    @Test
    @DisplayName("the result is read back by the transaction id: number, tax authority code, look-up code, signing time")
    void readsAnIssuedInvoiceBack() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(SEARCH)).willReturn(okJson(foundIssued())));

        Result result = provider.query(TRANSACTION);

        assertThat(result.state()).isEqualTo(ResultState.ISSUED);
        assertThat(result.invoiceNumber()).as("what follows the symbol in C26TAA15").isEqualTo(15);
        assertThat(result.taxAuthorityCode()).isEqualTo(TAX_AUTHORITY_CODE);
        assertThat(result.lookupCode()).isEqualTo(LOOKUP);
        assertThat(result.signedAt()).isEqualTo(Instant.ofEpochMilli(ISSUED_AT));
        assertThat(result.errorCode()).isNull();

        LoggedRequest search = sent(SEARCH).getFirst();
        assertThat(search.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded");
        assertThat(search.getHeader("Cookie")).isEqualTo("access_token=" + TOKEN);
        assertThat(search.getBodyAsString()).as("a form in the body, nothing in the URL")
                .isEqualTo("supplierTaxCode=0312345678&transactionUuid=" + uuidOf(TRANSACTION));
        assertThat(search.getUrl()).isEqualTo(SEARCH);
    }

    @Test
    @DisplayName("numbered at SInvoice but without the tax authority's code yet: still pending, not issued")
    void anInvoiceWithoutTheTaxAuthoritysCodeIsPending() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(SEARCH)).willReturn(okJson(foundWithoutTheTaxAuthoritysCode("null"))));

        assertThat(provider.query(TRANSACTION)).isEqualTo(Result.of(ResultState.PENDING));
    }

    @Test
    @DisplayName("trouble with the tax authority leaves the invoice pending (ASSUMPTION 3) and is logged without "
            + "SInvoice's words")
    void troubleWithTheTaxAuthorityStaysPending() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(SEARCH)).willReturn(okJson(
                foundWithoutTheTaxAuthoritysCode("\"CQT từ chối: MST người mua 0398765432 không tồn tại\""))));

        assertThat(provider.query(TRANSACTION).state()).as("an invoice the tax authority refused still exists at "
                + "SInvoice under its number: never the port's REJECTED").isEqualTo(ResultState.PENDING);
        assertThat(logLines()).anyMatch(line -> line.contains("trouble with the tax authority"))
                .as("what the trouble is names the buyer: it stays on SInvoice's portal")
                .noneMatch(line -> line.contains("0398765432"));
    }

    @Test
    @DisplayName("a transaction SInvoice has never received: no entry in result (ASSUMPTION 2)")
    void anUnknownTransactionIsNotFound() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(SEARCH)).inScenario("unknown").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"errorCode\":null,\"description\":null,\"result\":[]}")).willSetStateTo("null"));
        viettel.stubFor(post(urlEqualTo(SEARCH)).inScenario("unknown").whenScenarioStateIs("null")
                .willReturn(okJson("{\"errorCode\":null,\"description\":null,\"result\":null}")).willSetStateTo("none"));
        viettel.stubFor(post(urlEqualTo(SEARCH)).inScenario("unknown").whenScenarioStateIs("none")
                .willReturn(okJson("{\"errorCode\":null,\"description\":null}")));

        assertThat(provider.query(TRANSACTION)).isEqualTo(Result.of(ResultState.NOT_FOUND));
        assertThat(provider.query(TRANSACTION)).isEqualTo(Result.of(ResultState.NOT_FOUND));
        assertThat(provider.query(TRANSACTION)).isEqualTo(Result.of(ResultState.NOT_FOUND));
    }

    // ---- a refusal -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a wrong buyer tax code is refused with HTTP 400: REJECTED with SInvoice's code and words, no invoice")
    void aWrongTaxCodeIsRejected() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(CREATE)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"code":400,"message":"BAD_REQUEST_BUYER_TAX_CODE_INVALID",
                         "data":"Mã số thuế người mua không hợp lệ"}""")));

        Submission submission = provider.submit(invoice(TRANSACTION,
                new InvoiceParty("Cong ty Tra Sua", "0000000001", "12 Nguyen Hue", "ke.toan@trasua.example.com")));

        assertThat(submission.outcome()).isEqualTo(SubmissionOutcome.REJECTED);
        assertThat(submission.errorCode()).isEqualTo("BAD_REQUEST_BUYER_TAX_CODE_INVALID");
        assertThat(submission.message()).isEqualTo("Mã số thuế người mua không hợp lệ");
        assertThat(sent(CREATE)).as("a refusal is an answer: it is not sent again").hasSize(1);
        assertThat(body(sent(CREATE).getFirst()).get("buyerInfo").get("buyerTaxCode").asText()).isEqualTo("0000000001");
    }

    @Test
    @DisplayName("a refusal answered 200 with an errorCode is a refusal too")
    void anErrorCodeOnA200IsRejected() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(CREATE)).willReturn(okJson("""
                {"errorCode":"TEMPLATE_NOT_FOUND","description":"Không tìm thấy mẫu hóa đơn 1/001","result":null}""")));

        Submission submission = provider.submit(invoice(TRANSACTION));

        assertThat(submission.outcome()).isEqualTo(SubmissionOutcome.REJECTED);
        assertThat(submission.errorCode()).isEqualTo("TEMPLATE_NOT_FOUND");
        assertThat(submission.message()).isEqualTo("Không tìm thấy mẫu hóa đơn 1/001");
    }

    @Test
    @DisplayName("a refusal's code and words are cut to what the invoice stores: 64 and 500 characters")
    void aLongRefusalIsCut() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(CREATE)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"errorCode\":\"" + "E".repeat(80) + "\",\"description\":\"" + "x".repeat(900) + "\"}")));

        Submission submission = provider.submit(invoice(TRANSACTION));

        assertThat(submission.outcome()).isEqualTo(SubmissionOutcome.REJECTED);
        assertThat(submission.errorCode()).hasSize(64);
        assertThat(submission.message()).hasSize(500);
    }

    // ---- no answer -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("createInvoice that takes longer than the read timeout is no answer - never a refusal")
    void aSubmissionThatTimesOutIsNoAnswer() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(CREATE)).willReturn(okJson(created())
                .withFixedDelay((int) READ_TIMEOUT.toMillis() * 3)));

        long started = System.nanoTime();
        ProviderUnavailableException unavailable = noAnswer(() -> provider.submit(invoice(TRANSACTION)));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("given up at the read timeout")
                .isLessThan(READ_TIMEOUT.multipliedBy(3));
        assertThat(unavailable).hasMessageContaining("did not answer");
        assertThat(sent(CREATE)).as("not sent a second time by the adapter: SInvoice may be working on it").hasSize(1);
    }

    @ParameterizedTest(name = "createInvoice answered HTTP {0}")
    @ValueSource(ints = {500, 502, 503, 504, 403, 408, 429})
    @DisplayName("a server fault, or a status that says nothing about the invoice, is no answer")
    void aFaultOnSubmissionIsNoAnswer(int status) {
        signsIn();
        viettel.stubFor(post(urlEqualTo(CREATE)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":" + status + ",\"message\":\"INTERNAL_SERVER_ERROR\",\"data\":\"x\"}")));

        noAnswer(() -> provider.submit(invoice(TRANSACTION)));

        assertThat(sent(CREATE)).hasSize(1);
    }

    @Test
    @DisplayName("a connection dropped mid-answer is no answer")
    void aDroppedConnectionIsNoAnswer() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(CREATE)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        noAnswer(() -> provider.submit(invoice(TRANSACTION)));
    }

    @Test
    @DisplayName("a gateway's HTML page with 200, or a 200 without an invoice number, is no answer")
    void anAnswerThatCannotBeReadIsNoAnswer() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(CREATE)).inScenario("unreadable").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                        .withBody("<html><body>Bảo trì hệ thống</body></html>")).willSetStateTo("empty"));
        viettel.stubFor(post(urlEqualTo(CREATE)).inScenario("unreadable").whenScenarioStateIs("empty")
                .willReturn(okJson("{\"errorCode\":null,\"description\":null,\"result\":null}")));

        noAnswer(() -> provider.submit(invoice(TRANSACTION)));
        noAnswer(() -> provider.submit(invoice(TRANSACTION)));
    }

    @ParameterizedTest(name = "the look-up answered HTTP {0}")
    @ValueSource(ints = {500, 502, 503, 504, 403, 408, 429})
    @DisplayName("a fault on the look-up is no answer - never 'not found', which would let the invoice be sent again")
    void aFaultOnTheLookUpIsNoAnswer(int status) {
        signsIn();
        viettel.stubFor(post(urlEqualTo(SEARCH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"errorCode\":null,\"description\":null,\"result\":[]}")));

        noAnswer(() -> provider.query(TRANSACTION));
    }

    @Test
    @DisplayName("a look-up that times out, or answers with something that is not JSON, is no answer")
    void aLookUpWithoutAReadableAnswerIsNoAnswer() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(SEARCH)).inScenario("lookup").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(foundIssued()).withFixedDelay((int) READ_TIMEOUT.toMillis() * 3))
                .willSetStateTo("html"));
        viettel.stubFor(post(urlEqualTo(SEARCH)).inScenario("lookup").whenScenarioStateIs("html")
                .willReturn(aResponse().withStatus(200).withBody("<html>502 Bad Gateway</html>")));

        noAnswer(() -> provider.query(TRANSACTION));
        noAnswer(() -> provider.query(TRANSACTION));
    }

    // ---- never twice -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a submission whose answer was lost is found by its transaction id; sent again, it carries the same "
            + "id, which SInvoice answers with the first invoice")
    void aRetryReconcilesByTheTransactionId() {
        signsIn();
        // The first submission arrives and SInvoice issues - only the answer is too slow to be read.
        viettel.stubFor(post(urlEqualTo(CREATE)).inScenario("lost answer").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(created()).withFixedDelay((int) READ_TIMEOUT.toMillis() * 3))
                .willSetStateTo("held"));
        // SInvoice's rule: a transactionUuid it holds is answered with the invoice it made the first time.
        viettel.stubFor(post(urlEqualTo(CREATE)).inScenario("lost answer").whenScenarioStateIs("held")
                .withRequestBody(matchingJsonPath("$.generalInvoiceInfo.transactionUuid", equalTo(uuidOf(TRANSACTION))))
                .willReturn(okJson(created())));
        viettel.stubFor(post(urlEqualTo(SEARCH)).withRequestBody(equalTo("supplierTaxCode=0312345678&transactionUuid="
                + uuidOf(TRANSACTION))).willReturn(okJson(foundIssued())));

        noAnswer(() -> provider.submit(invoice(TRANSACTION)));
        Result found = provider.query(TRANSACTION);

        assertThat(found.state()).as("the look-up is by the id the submission carried").isEqualTo(ResultState.ISSUED);
        assertThat(found.invoiceNumber()).isEqualTo(15);
        assertThat(sent(CREATE)).as("asking did not send the invoice again").hasSize(1);

        Submission again = provider.submit(invoice(TRANSACTION));

        assertThat(again.outcome()).as("the same transaction, the same invoice: nothing refused, nothing new")
                .isEqualTo(SubmissionOutcome.ACCEPTED);
        List<String> ids = sent(CREATE).stream()
                .map(request -> body(request).get("generalInvoiceInfo").get("transactionUuid").asText()).toList();
        assertThat(ids).hasSize(2).containsOnly(uuidOf(TRANSACTION));
        assertThat(sent(CREATE).stream().map(LoggedRequest::getBodyAsString).distinct())
                .as("the retry is the first submission again, byte for byte").hasSize(1);
    }

    @Test
    @DisplayName("SInvoice's transactionUuid: 10 to 36 characters, the same for an attempt, another for the next")
    void theTransactionUuidNamesOneAttempt() {
        String first = uuidOf(TRANSACTION);

        assertThat(TRANSACTION.length()).as("the system's own id does not fit SInvoice's 36").isGreaterThan(36);
        assertThat(first).hasSizeBetween(10, 36).matches("[0-9a-f-]{36}");
        assertThat(uuidOf(TRANSACTION)).isEqualTo(first);
        assertThat(uuidOf(TRANSACTION.replaceAll("-1$", "-2"))).as("the invoice's next attempt is another invoice")
                .isNotEqualTo(first);
        assertThat(uuidOf("0193a5b0-7c3e-7d4a-8f10-2a6b9c4d5e70-1")).as("another invoice").isNotEqualTo(first);
    }

    @Test
    @DisplayName("the caller is told SInvoice's limits: no second submission inside 90 s, none after three days")
    void statesWhenATransactionMayBeSentAgain() {
        EInvoiceProvider.Resubmission resubmission = provider.resubmission();
        Instant submitted = EInvoiceFixtures.NOW;

        assertThat(resubmission.quietPeriod()).isEqualTo(Duration.ofSeconds(90));
        assertThat(resubmission.window()).isEqualTo(Duration.ofDays(3));
        assertThat(resubmission.allowedAt(submitted, submitted.plusSeconds(30)))
                .as("SInvoice may still be working on the first submission").isFalse();
        assertThat(resubmission.allowedAt(submitted, submitted.plusSeconds(89))).isFalse();
        assertThat(resubmission.allowedAt(submitted, submitted.plusSeconds(90))).isTrue();
        assertThat(resubmission.allowedAt(submitted, submitted.plus(Duration.ofDays(3)).minusSeconds(1))).isTrue();
        assertThat(resubmission.allowedAt(submitted, submitted.plus(Duration.ofDays(3))))
                .as("SInvoice has forgotten the id: the same id would be a second invoice").isFalse();
        assertThat(EInvoiceProvider.Resubmission.FREELY.allowedAt(submitted, submitted))
                .as("the sandbox takes a submission in atomically").isTrue();
    }

    @Test
    @DisplayName("the defaults wait for an answer longer than the quiet period, so a submission is never abandoned "
            + "and sent again while SInvoice may still work on it")
    void theDefaultsNeverAbandonASubmissionEarly() {
        ViettelSInvoiceProperties defaults = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource())
                .bindOrCreate("stockflow.invoicing.viettel", ViettelSInvoiceProperties.class);

        assertThat(defaults.readTimeout()).isGreaterThanOrEqualTo(defaults.resubmitQuietPeriod());
        assertThat(defaults.resubmitQuietPeriod()).as("Viettel: at least 90 seconds").isGreaterThanOrEqualTo(
                Duration.ofSeconds(90));
        assertThat(defaults.resubmitWindow()).isEqualTo(Duration.ofDays(3));
        assertThat(defaults.productionUrl()).isEqualTo("https://api-vinvoice.viettel.vn");
        assertThatThrownBy(() -> new ViettelSInvoiceProperties("https://a", "https://a", Duration.ofSeconds(95),
                Duration.ofDays(3), Duration.ofDays(3), Duration.ofSeconds(2), true))
                .as("a window no longer than the quiet period leaves no time to send again")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- the token -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the token is asked for once and kept; asked for again shortly before it expires")
    void theTokenIsKeptUntilItExpires() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(SEARCH)).willReturn(okJson(foundIssued())));

        provider.query(TRANSACTION);
        provider.query(TRANSACTION);
        clock.advance(Duration.ofSeconds(240));
        provider.query(TRANSACTION);
        assertThat(sent(LOGIN)).as("300 s stated: still good after 240").hasSize(1);

        clock.advance(Duration.ofSeconds(45));
        provider.query(TRANSACTION);
        assertThat(sent(LOGIN)).as("given up before SInvoice's own expiry, not on it").hasSize(2);
    }

    @Test
    @DisplayName("a call answered 401 signs in again and is made once more, with the new token")
    void anExpiredTokenIsReplacedOnce() {
        viettel.stubFor(post(urlEqualTo(LOGIN)).inScenario("401").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(loginAnswer(TOKEN))).willSetStateTo("signed in"));
        viettel.stubFor(post(urlEqualTo(LOGIN)).inScenario("401").whenScenarioStateIs("signed in")
                .willReturn(okJson(loginAnswer(NEXT_TOKEN))));
        viettel.stubFor(post(urlEqualTo(SEARCH)).withHeader("Cookie", equalTo("access_token=" + TOKEN))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"invalid_token\"}")));
        viettel.stubFor(post(urlEqualTo(SEARCH)).withHeader("Cookie", equalTo("access_token=" + NEXT_TOKEN))
                .willReturn(okJson(foundIssued())));

        assertThat(provider.query(TRANSACTION).state()).isEqualTo(ResultState.ISSUED);
        assertThat(sent(LOGIN)).hasSize(2);
        assertThat(sent(SEARCH)).hasSize(2);
    }

    @Test
    @DisplayName("401 again after signing in again is no answer: the call is not repeated for ever")
    void aTokenRefusedTwiceIsNoAnswer() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(CREATE)).willReturn(aResponse().withStatus(401)
                .withBody("{\"error\":\"invalid_token\"}")));

        noAnswer(() -> provider.submit(invoice(TRANSACTION)));

        assertThat(sent(CREATE)).hasSize(2);
        assertThat(sent(LOGIN)).hasSize(2);
    }

    @Test
    @DisplayName("an account SInvoice does not sign in is no answer, and no invoice leaves the system")
    void aWrongAccountSendsNothing() {
        viettel.stubFor(post(urlEqualTo(LOGIN)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Bad credentials\"}")));
        viettel.stubFor(post(urlEqualTo(CREATE)).willReturn(okJson(created())));

        ProviderUnavailableException unavailable = noAnswer(() -> provider.submit(invoice(TRANSACTION)));

        assertThat(unavailable).hasMessageContaining("did not sign the account in");
        assertThat(sent(CREATE)).isEmpty();
        assertThat(logLines()).anyMatch(line -> line.contains("did not sign the account in"));
    }

    @Test
    @DisplayName("a log-in answered 200 without a token is no answer")
    void aLogInWithoutATokenIsNoAnswer() {
        viettel.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"token_type\":\"bearer\"}")));

        noAnswer(() -> provider.query(TRANSACTION));

        assertThat(sent(SEARCH)).isEmpty();
    }

    // ---- the files -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the files of an issued invoice: the signed XML out of the ZIP, and the PDF, named by number and "
            + "template")
    void downloadsTheXmlAndThePdf() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(SEARCH)).willReturn(okJson(foundIssued())));
        handsOver("ZIP", zip("0312345678-C26TAA15.xml", XML));
        handsOver("PDF", PDF);

        Documents documents = provider.download(TRANSACTION);

        assertThat(documents.xml()).as("the invoice in law, as SInvoice signed it: byte for byte").isEqualTo(XML);
        assertThat(documents.pdf()).isEqualTo(PDF);

        List<LoggedRequest> files = sent(FILE);
        assertThat(files).hasSize(2);
        assertThat(files.stream().map(request -> body(request).get("fileType").asText())).containsExactly("ZIP", "PDF");
        for (LoggedRequest request : files) {
            JsonNode asked = body(request);
            assertThat(asked.get("supplierTaxCode").asText()).isEqualTo("0312345678");
            assertThat(asked.get("invoiceNo").asText()).isEqualTo(INVOICE_NO);
            assertThat(asked.get("templateCode").asText()).isEqualTo("1/001");
            assertThat(asked.get("transactionUuid").asText()).isEqualTo(uuidOf(TRANSACTION));
            assertThat(request.getHeader("Cookie")).isEqualTo("access_token=" + TOKEN);
        }
    }

    @Test
    @DisplayName("an empty file - SInvoice renders it a few seconds after issuing - is asked for again")
    void anEmptyFileIsAskedForAgain() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(SEARCH)).willReturn(okJson(foundIssued())));
        viettel.stubFor(post(urlEqualTo(FILE)).withRequestBody(matchingJsonPath("$.fileType", equalTo("ZIP")))
                .inScenario("rendering").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"errorCode\":null,\"description\":null,\"fileToBytes\":null}"))
                .willSetStateTo("rendered"));
        viettel.stubFor(post(urlEqualTo(FILE)).withRequestBody(matchingJsonPath("$.fileType", equalTo("ZIP")))
                .inScenario("rendering").whenScenarioStateIs("rendered")
                .willReturn(okJson(file(zip("invoice.XML", XML)))));
        handsOver("PDF", PDF);

        Documents documents = provider.download(TRANSACTION);

        assertThat(documents.xml()).isEqualTo(XML);
        assertThat(sent(FILE)).as("ZIP twice, PDF once").hasSize(3);
    }

    @Test
    @DisplayName("a file that stays empty is no answer after three tries: the invoice is downloaded on the next "
            + "reconciliation")
    void aFileThatStaysEmptyIsNoAnswer() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(SEARCH)).willReturn(okJson(foundIssued())));
        viettel.stubFor(post(urlEqualTo(FILE)).willReturn(okJson(
                "{\"errorCode\":null,\"description\":null,\"fileToBytes\":\"\"}")));

        noAnswer(() -> provider.download(TRANSACTION));

        assertThat(sent(FILE)).as("the PDF is not asked for without the XML").hasSize(3);
    }

    @Test
    @DisplayName("the XML is found one archive deeper too, as some accounts are handed it")
    void theXmlIsFoundInANestedArchive() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(SEARCH)).willReturn(okJson(foundIssued())));
        handsOver("ZIP", zip("0312345678-C26TAA15.zip", zip("inner/invoice.xml", XML)));
        handsOver("PDF", PDF);

        assertThat(provider.download(TRANSACTION).xml()).isEqualTo(XML);
    }

    @Test
    @DisplayName("an archive without an XML, a file that is not an archive, or an error instead of a file: no answer")
    void aFileThatIsNotTheInvoiceIsNoAnswer() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(SEARCH)).willReturn(okJson(foundIssued())));
        handsOver("PDF", PDF);

        handsOver("ZIP", zip("invoice.html", XML));
        noAnswer(() -> provider.download(TRANSACTION));

        handsOver("ZIP", "not an archive at all".getBytes(StandardCharsets.UTF_8));
        noAnswer(() -> provider.download(TRANSACTION));

        viettel.stubFor(post(urlEqualTo(FILE)).willReturn(okJson(
                "{\"errorCode\":\"INVOICE_NOT_FOUND\",\"description\":\"Không tìm thấy hóa đơn\"}")));
        noAnswer(() -> provider.download(TRANSACTION));

        viettel.stubFor(post(urlEqualTo(FILE)).willReturn(aResponse().withStatus(500)));
        noAnswer(() -> provider.download(TRANSACTION));
    }

    @Test
    @DisplayName("the files of a transaction that is not issued are not asked for")
    void nothingIsDownloadedForAnInvoiceNotIssued() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(SEARCH)).willReturn(okJson(foundWithoutTheTaxAuthoritysCode("null"))));

        ProviderUnavailableException unavailable = noAnswer(() -> provider.download(TRANSACTION));

        assertThat(unavailable).hasMessageContaining("is not issued");
        assertThat(sent(FILE)).isEmpty();
    }

    // ---- the account -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the account's environment chooses the host: PRODUCTION is called at the production address only")
    void theEnvironmentChoosesTheHost() {
        account("PRODUCTION", cipher.seal(PASSWORD, EInvoiceProviderAccountService.secretContext(configId)));
        production.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson(loginAnswer(TOKEN))));
        production.stubFor(post(urlEqualTo(SEARCH)).willReturn(okJson(foundIssued())));

        assertThat(provider.query(TRANSACTION).state()).isEqualTo(ResultState.ISSUED);

        assertThat(production.findAll(anyRequestedFor(anyUrl()))).hasSize(2);
        assertThat(viettel.findAll(anyRequestedFor(anyUrl()))).as("the test system never sees a production account")
                .isEmpty();
    }

    @Test
    @DisplayName("without an account - none stored, or half of one - nothing is sent anywhere")
    void withoutAnAccountNothingIsSent() {
        when(accounts.find(ViettelSInvoiceProvider.CODE)).thenReturn(Optional.empty());
        noAnswer(() -> provider.submit(invoice(TRANSACTION)));

        account("SANDBOX", null);
        noAnswer(() -> provider.query(TRANSACTION));
        noAnswer(() -> provider.download(TRANSACTION));

        assertThat(viettel.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    @DisplayName("a password sealed under another key, or for another row, does not open: nothing is sent, and the log "
            + "names neither")
    void aPasswordThatDoesNotOpenSendsNothing() {
        SecretCipher otherKey = new SecretCipher(new SecretCipherProperties(
                Base64.getEncoder().encodeToString(new byte[32])));
        ProviderUnavailableException wrongKey = noAnswer(() -> provider(otherKey).submit(invoice(TRANSACTION)));
        assertThat(wrongKey).hasMessageContaining("cannot be opened");

        // Sealed for another provider's row and copied here: AES-GCM's associated data does not match.
        account("SANDBOX", cipher.seal(PASSWORD, EInvoiceProviderAccountService.secretContext(UUID.randomUUID())));
        noAnswer(() -> provider.query(TRANSACTION));

        assertThat(viettel.findAll(anyRequestedFor(anyUrl()))).isEmpty();
        assertThat(logLines()).anyMatch(line -> line.contains("password cannot be opened"));
    }

    @Test
    @DisplayName("a series without SInvoice's template code is not submitted")
    void aSeriesWithoutATemplateCodeIsNotSubmitted() {
        when(accounts.providerTemplateCode(any(), any(), any())).thenReturn(Optional.empty());
        signsIn();

        ProviderUnavailableException unavailable = noAnswer(() -> provider.submit(invoice(TRANSACTION)));

        assertThat(unavailable).hasMessageContaining("template code");
        assertThat(sent(CREATE)).isEmpty();
    }

    // ---- secrets ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("what is logged about a call is its path and how it ended - no body, no buyer, no account")
    void aCallIsLoggedWithoutWhatItCarried() {
        signsIn();
        viettel.stubFor(post(urlEqualTo(CREATE)).willReturn(okJson(created())));
        viettel.stubFor(post(urlEqualTo(SEARCH)).willReturn(okJson(foundIssued())));
        handsOver("ZIP", zip("invoice.xml", XML));
        handsOver("PDF", PDF);

        provider.submit(invoice(TRANSACTION));
        provider.query(TRANSACTION);
        provider.download(TRANSACTION);

        List<String> lines = logLines();
        assertThat(lines).as("the calls are on the log, so the check after each test is not of an empty log")
                .anyMatch(line -> line.contains(LOGIN)).anyMatch(line -> line.contains("createInvoice"))
                .anyMatch(line -> line.contains("searchInvoiceByTransactionUuid"))
                .anyMatch(line -> line.contains("getInvoiceRepresentationFile"));
        assertThat(lines).as("an invoice names a buyer; a log is read by more people than an invoice is")
                .noneMatch(line -> line.contains("0398765432")).noneMatch(line -> line.contains("Cong ty Tra Sua"))
                .noneMatch(line -> line.contains("ke.toan@trasua.example.com"))
                .noneMatch(line -> line.contains(LOOKUP)).noneMatch(line -> line.contains(TAX_AUTHORITY_CODE))
                .noneMatch(line -> line.contains(Base64.getEncoder().encodeToString(PDF)));
    }

    @Test
    @DisplayName("the account and the request that stores it print without the password")
    void theAccountPrintsWithoutItsPassword() {
        assertThat(new ViettelSInvoiceClient.Account(false, USERNAME, PASSWORD).toString())
                .doesNotContain(PASSWORD).doesNotContain(USERNAME).contains("SANDBOX");
        assertThat(new ViettelSInvoiceClient.Account(true, USERNAME, PASSWORD).toString())
                .doesNotContain(PASSWORD).contains("PRODUCTION");
        assertThat(new StoreEInvoiceProviderAccountRequest("SANDBOX", USERNAME, PASSWORD, 3L).toString())
                .doesNotContain(PASSWORD);
        assertThat(accounts.find(ViettelSInvoiceProvider.CODE).orElseThrow().secretSealed())
                .as("what is stored is SecretCipher's sealed form").startsWith("v1:").doesNotContain(PASSWORD);
    }

    /** A clock the test moves, for the token's lifetime. */
    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("E0 L9: a bank transfer is printed CK, never the stored code; anything unsettled is TM/CK")
    void paymentMethodAsPrinted() {
        assertThat(ViettelSInvoicePayload.paymentMethodName("BANK_TRANSFER")).isEqualTo("CK");
        assertThat(ViettelSInvoicePayload.paymentMethodName(null)).isEqualTo("TM/CK");
        assertThat(ViettelSInvoicePayload.paymentMethodName("TM/CK")).isEqualTo("TM/CK");
    }
}
