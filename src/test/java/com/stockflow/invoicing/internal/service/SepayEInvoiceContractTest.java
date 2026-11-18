package com.stockflow.invoicing.internal.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.http.HttpClientProperties;
import com.stockflow.common.http.RestClientFactory;
import com.stockflow.common.security.SecretCipher;
import com.stockflow.common.security.SecretCipherProperties;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceLine;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Correction;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Documents;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.InvoiceDocument;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ProviderUnavailableException;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Result;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ResultState;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Submission;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.SubmissionOutcome;
import com.stockflow.invoicing.internal.domain.EInvoiceType;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.ProviderAccounts;
import com.stockflow.invoicing.internal.domain.ProviderCallLog;
import com.stockflow.invoicing.internal.domain.TaxRateTotal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SePay eInvoice's API v1 as the adapter calls it, against an HTTP server that answers the way SePay's developer
 * documentation (developer.sepay.vn, "eInvoice API") shows. A real server on a socket, not a mocked client: a dropped
 * connection and a status without a body only happen there.
 *
 * <p>What is pinned: the token by Basic authentication and the Bearer it gives, renewed once on a 401; what the
 * create, adjust and replace calls carry; a refusal of the invoice told apart from a refusal of the account and from no
 * answer; the reference SePay is given being the same for every retry of an attempt, so a retry is SePay's
 * {@code EINVOICE_DOCUMENT_EXISTED} and not a second invoice; the look-up by that reference; the XML and the PDF; and
 * that the secret and the token appear in no log line, no exception and no URL - checked after every test.</p>
 *
 * <p>The stub bodies are the documentation's samples. Where it is silent the adapter decides
 * ({@code SepayEInvoiceAnswers}, "ASSUMPTIONS"); the tests of those say so in their names.</p>
 */
class SepayEInvoiceContractTest {

    private static final String CLIENT_ID = "sepay-client-7f3a";
    private static final String CLIENT_SECRET = "S3cr3t-SePay-9c2e1b";
    private static final String TOKEN = "eyJ-first-token-AAAA";
    private static final String NEXT_TOKEN = "eyJ-second-token-BBBB";
    private static final String PROVIDER_ACCOUNT = "0aea3134-da40-11f0-aef4-52c7e9b4f41b";
    private static final String TRACKING = "084e179d-d95a-11f0-aef4-52c7e9b4f41b";
    private static final String TRANSACTION = "0193a5b0-7c3e-7d4a-8f10-2a6b9c4d5e6f-1";
    private static final String REFERENCE = SepayEInvoiceProvider.referenceCode(TRANSACTION);
    private static final String TAX_AUTHORITY_CODE = "M1-26-QWERT-00000000589";
    private static final byte[] XML = ("<HDon><DLHDon Id=\"data\"><TTChung><SHDon>589</SHDon></TTChung></DLHDon>"
            + "<MCCQT Id=\"id1\">" + TAX_AUTHORITY_CODE + "</MCCQT><DSCKS><NBan><Signature><Object><SignatureProperties>"
            + "<SignatureProperty><SigningTime>2026-11-12T10:05:00</SigningTime></SignatureProperty>"
            + "</SignatureProperties></Object></Signature></NBan></DSCKS></HDon>").getBytes(StandardCharsets.UTF_8);
    private static final byte[] PDF = "%PDF-1.4 the invoice people read".getBytes(StandardCharsets.UTF_8);

    private static final WireMockServer sepay = new WireMockServer(options().dynamicPort());

    private final ObjectMapper json = new ObjectMapper();
    private final ProviderAccounts accounts = mock(ProviderAccounts.class);
    private final ProviderCallLog calls = mock(ProviderCallLog.class);
    private final Clock clock = Clock.fixed(EInvoiceFixtures.NOW, ZoneOffset.UTC);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final List<Throwable> thrown = new ArrayList<>();
    private Level stockflowLevel;

    @BeforeAll
    static void sepayAnswers() {
        sepay.start();
    }

    @AfterAll
    static void sepayIsGone() {
        sepay.stop();
    }

    @BeforeEach
    void aSeriesNamedWithSepaysTemplateCode() {
        sepay.resetAll();
        when(accounts.providerTemplateCode(SepayEInvoiceProvider.CODE, "1", "C26TSE")).thenReturn(Optional.of("1"));
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
        List<String> said = new ArrayList<>(logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        thrown.forEach(failure -> said.add("thrown: " + failure));
        sepay.findAll(anyRequestedFor(anyUrl())).forEach(request -> said.add("url: " + request.getAbsoluteUrl()));
        assertThat(said).as("neither the secret nor a token is logged, thrown or put in a URL")
                .noneMatch(line -> line.contains(CLIENT_SECRET)).noneMatch(line -> line.contains(TOKEN))
                .noneMatch(line -> line.contains(NEXT_TOKEN));
    }

    // ---- submitting ------------------------------------------------------------------------------------------------

    @Test
    void anInvoiceIsIssuedAtOnceUnderItsReferenceWithTheSystemsOwnAmounts() throws Exception {
        signsIn();
        listsTheAccount();
        sepay.stubFor(post(urlEqualTo("/v1/invoices/create")).willReturn(okJson(taken("create"))));

        Submission submission = provider().submit(invoice(TRANSACTION));

        assertThat(submission.outcome()).isEqualTo(SubmissionOutcome.ACCEPTED);
        assertThat(submission.providerReference()).as("the tracking code, kept to learn a refusal after this")
                .isEqualTo("create/" + TRACKING);
        LoggedRequest token = only("/v1/token");
        assertThat(token.getHeader("Authorization")).isEqualTo("Basic " + Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8)));
        LoggedRequest create = only("/v1/invoices/create");
        assertThat(create.getHeader("Authorization")).isEqualTo("Bearer " + TOKEN);
        JsonNode body = json.readTree(create.getBodyAsString());
        assertThat(body.get("template_code").asText()).isEqualTo("1");
        assertThat(body.get("invoice_series").asText()).isEqualTo("C26TSE");
        assertThat(body.get("reference_code").asText()).isEqualTo(REFERENCE);
        assertThat(body.get("provider_account_id").asText()).isEqualTo(PROVIDER_ACCOUNT);
        assertThat(body.get("is_draft").isBoolean()).as("a JSON boolean, or SePay answers INVALID_IS_DRAFT_TYPE")
                .isTrue();
        assertThat(body.get("is_draft").asBoolean()).isFalse();
        assertThat(body.get("issued_date").asText()).as("today, in Viet Nam's time").isEqualTo("2026-11-12 10:00:00");
        assertThat(body.get("currency").asText()).isEqualTo("VND");
        assertThat(body.get("payment_method").asText()).isEqualTo("TM/CK");
        assertThat(body.at("/buyer/tax_code").asText()).isEqualTo("0398765432");
        assertThat(body.at("/buyer/legal_name").asText()).isEqualTo("Cong ty Tra Sua");
        assertThat(body.get("buyer").has("name")).as("the person buying is not recorded; the company is not sent twice")
                .isFalse();
        assertThat(body.has("notes")).as("SePay prints notes as the warehouse code").isFalse();
        assertThat(body.at("/items/0/tax_rate").asInt()).isEqualTo(10);
        assertThat(body.at("/items/0/quantity").asInt()).isEqualTo(20);
        assertThat(body.at("/items/0/unit_price").asLong()).isEqualTo(100_000);
        assertThat(body.at("/items/0/tax_amount").asLong()).as("the line's own tax, sent so SePay does not recompute it")
                .isEqualTo(200_000);
        assertThat(body.at("/items/1/item_code").asText()).isEqualTo("CUP12-0398765432-01");
        assertThat(body.at("/items/1/tax_rate").asInt()).isEqualTo(8);
        assertThat(body.get("total_amount").asLong()).isEqualTo(2_740_000);
    }

    @Test
    void theSameAttemptIsTheSameReferenceAndSepaysDuplicateRefusalIsTheFirstInvoice() {
        signsIn();
        listsTheAccount();
        sepay.stubFor(post(urlEqualTo("/v1/invoices/create")).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody(error("EINVOICE_DOCUMENT_EXISTED", "Hệ thống đã ghi nhận hóa đơn với mã này"))));

        assertThat(provider().submit(invoice(TRANSACTION)).outcome()).isEqualTo(SubmissionOutcome.DUPLICATE);
        assertThat(SepayEInvoiceProvider.referenceCode(TRANSACTION)).isEqualTo(REFERENCE)
                .isNotEqualTo(SepayEInvoiceProvider.referenceCode(TRANSACTION.replace("-1", "-2")));
    }

    @Test
    void theReferenceIsShortEnoughToTypeAndMadeOfUnambiguousCharacters() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            String reference = SepayEInvoiceProvider.referenceCode(UUID.randomUUID() + "-" + (i % 3 + 1));
            assertThat(reference).matches("[0-9A-HJKMNP-TV-Z]{14}");
            assertThat(seen.add(reference)).as("no two transactions share a reference").isTrue();
        }
    }

    @Test
    void sepaysRefusalOfTheInvoiceRejectsItWithSepaysCodeAndWords() {
        signsIn();
        listsTheAccount();
        sepay.stubFor(post(urlEqualTo("/v1/invoices/create")).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody(error("VALIDATION_ERROR", "invoice_series không hợp lệ"))));

        Submission submission = provider().submit(invoice(TRANSACTION));

        assertThat(submission.outcome()).isEqualTo(SubmissionOutcome.REJECTED);
        assertThat(submission.errorCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(submission.message()).isEqualTo("invoice_series không hợp lệ");
    }

    @Test
    void HD1_aRefusalOfTheAccountRefusesTheInvoiceUnderACodeOfTheCauseNeverWaitsForAnAnswer() {
        signsIn();
        listsTheAccount();
        String[][] causes = {
                {"500", "QUOTA_HAS_BEEN_USERD_UP", SepayEInvoiceAnswers.QUOTA_EXHAUSTED},
                {"500", "BILLING_IS_UNPAID", SepayEInvoiceAnswers.BILLING_UNPAID},
                {"500", "REGISTRATION_NOT_COMPLETE", SepayEInvoiceAnswers.ACCOUNT_NOT_READY},
                {"422", "REGISTRATION_NOT_TAX_APPROVED", SepayEInvoiceAnswers.ACCOUNT_NOT_READY},
                {"500", "EINVOICE_ACCOUNT_NOT_FOUND", SepayEInvoiceAnswers.ACCOUNT_NOT_READY}};
        for (String[] cause : causes) {
            sepay.stubFor(post(urlEqualTo("/v1/invoices/create")).willReturn(aResponse()
                    .withStatus(Integer.parseInt(cause[0])).withHeader("Content-Type", "application/json")
                    .withBody(error(cause[1], "Tài khoản chưa sẵn sàng"))));

            Submission submission = provider().submit(invoice(TRANSACTION));

            assertThat(submission.outcome()).as(cause[1]).isEqualTo(SubmissionOutcome.REJECTED);
            assertThat(submission.errorCode()).as(cause[1]).isEqualTo(cause[2]);
            assertThat(submission.message()).as("SePay's own code is kept in the words").startsWith(cause[1]);
            assertThat(provider().accountProblem(submission.errorCode())).as(cause[1]).isPresent();
        }
        assertThat(provider().accountProblem(SepayEInvoiceAnswers.QUOTA_EXHAUSTED))
                .contains(EInvoiceProvider.AccountProblem.QUOTA_EXHAUSTED);
        assertThat(provider().accountProblem("VALIDATION_ERROR")).as("the invoice's own refusal").isEmpty();
    }

    @Test
    void HD1_aSeriesWithoutSepaysTemplateCodeIsRefusedBeforeAnythingIsSent() {
        signsIn();
        listsTheAccount();
        when(accounts.providerTemplateCode(SepayEInvoiceProvider.CODE, "1", "C26TSE")).thenReturn(Optional.empty());

        Submission submission = provider().submit(invoice(TRANSACTION));

        assertThat(submission.outcome()).isEqualTo(SubmissionOutcome.REJECTED);
        assertThat(submission.errorCode()).isEqualTo(SepayEInvoiceAnswers.TEMPLATE_MISSING);
        sepay.verify(0, postRequestedFor(urlEqualTo("/v1/invoices/create")));
    }

    @Test
    void HD1_anAccountListingNoActiveRegistrationRefusesTheInvoice() {
        signsIn();
        sepay.stubFor(get(urlPathEqualTo("/v1/provider-accounts")).willReturn(okJson("""
                {"data": {"items": [{"id": "9d9d9d9d-0000-0000-0000-000000000000", "provider": "matbao",
                                     "active": false}]}}""")));

        Submission submission = provider().submit(invoice(TRANSACTION));

        assertThat(submission.outcome()).isEqualTo(SubmissionOutcome.REJECTED);
        assertThat(submission.errorCode()).isEqualTo(SepayEInvoiceAnswers.ACCOUNT_NOT_READY);
        sepay.verify(0, postRequestedFor(urlEqualTo("/v1/invoices/create")));
    }

    @Test
    void noAnswerSaysNothingAboutTheInvoice() {
        signsIn();
        listsTheAccount();
        sepay.stubFor(post(urlEqualTo("/v1/invoices/create"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThatThrownBy(() -> capture(() -> provider().submit(invoice(TRANSACTION))))
                .isInstanceOf(ProviderUnavailableException.class);

        sepay.stubFor(post(urlEqualTo("/v1/invoices/create")).willReturn(aResponse().withStatus(502)
                .withBody("<html>Bad gateway</html>")));
        assertThatThrownBy(() -> capture(() -> provider().submit(invoice(TRANSACTION))))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void anExpiredTokenIsRenewedOnceAndTheCallMadeAgain() {
        sepay.stubFor(post(urlEqualTo("/v1/token")).inScenario("token").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(tokenAnswer(TOKEN))).willSetStateTo("second"));
        sepay.stubFor(post(urlEqualTo("/v1/token")).inScenario("token").whenScenarioStateIs("second")
                .willReturn(okJson(tokenAnswer(NEXT_TOKEN))));
        listsTheAccount();
        sepay.stubFor(post(urlEqualTo("/v1/invoices/create")).withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json")
                        .withBody(error("TOKEN_EXPIRED", "Token đã hết hạn"))));
        sepay.stubFor(post(urlEqualTo("/v1/invoices/create"))
                .withHeader("Authorization", equalTo("Bearer " + NEXT_TOKEN)).willReturn(okJson(taken("create"))));

        assertThat(provider().submit(invoice(TRANSACTION)).outcome()).isEqualTo(SubmissionOutcome.ACCEPTED);
        sepay.verify(2, postRequestedFor(urlEqualTo("/v1/token")));
    }

    @Test
    void aWrongKeySendsNothingAndSaysNothingAboutTheInvoice() {
        sepay.stubFor(post(urlEqualTo("/v1/token")).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json").withBody(error("UNAUTHORIZED", "Sai thông tin"))));

        assertThatThrownBy(() -> capture(() -> provider().submit(invoice(TRANSACTION))))
                .isInstanceOf(ProviderUnavailableException.class);
        sepay.verify(0, postRequestedFor(urlEqualTo("/v1/invoices/create")));
    }

    @Test
    void theRegistrationTheDeploymentNamesIsUsedWithoutAskingSepay() throws Exception {
        signsIn();
        sepay.stubFor(post(urlEqualTo("/v1/invoices/create")).willReturn(okJson(taken("create"))));

        provider(properties("SANDBOX", "11111111-2222-3333-4444-555555555555")).submit(invoice(TRANSACTION));

        sepay.verify(0, getRequestedFor(urlPathEqualTo("/v1/provider-accounts")));
        assertThat(json.readTree(only("/v1/invoices/create").getBodyAsString()).get("provider_account_id").asText())
                .isEqualTo("11111111-2222-3333-4444-555555555555");
    }

    @Test
    void aRateSepayCannotCarryIsRefusedBeforeAnythingIsSent() {
        List<EInvoiceLine> lines = List.of(EInvoiceLine.goods(UUID.randomUUID(), 1, UUID.randomUUID(), "SKU-1",
                "Ly giay", "Thung", 1, null, new BigDecimal("100000"), new BigDecimal("7")));

        Submission submission = provider().submit(invoice(TRANSACTION, lines, null));

        assertThat(submission.outcome()).isEqualTo(SubmissionOutcome.REJECTED);
        assertThat(submission.errorCode()).isEqualTo("SEPAY_TAX_RATE_UNSUPPORTED");
        assertThat(sepay.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    // ---- corrections -----------------------------------------------------------------------------------------------

    @Test
    void anAdjustmentThatReducesGoesToAdjustNamingTheOriginalByItsReference() throws Exception {
        signsIn();
        listsTheAccount();
        sepay.stubFor(post(urlEqualTo("/v1/invoices/adjust")).willReturn(aResponse().withStatus(202)
                .withHeader("Content-Type", "application/json").withBody(taken("adjust"))));
        String original = "0193a5b0-0000-7000-8000-000000000001-1";
        List<EInvoiceLine> fewer = List.of(EInvoiceLine.goods(UUID.randomUUID(), 1, UUID.randomUUID(),
                "CUP12-0398765432-01", "Ly giay 12oz", "Thung", -5, 50, new BigDecimal("100000"),
                new BigDecimal("10")));
        Correction correction = new Correction(EInvoiceType.ADJUSTMENT, "1", "C26TSE", 589,
                EInvoiceFixtures.DELIVERED_ON, "Tra lai 5 thung", original);

        Submission submission = provider().submit(invoice(TRANSACTION, fewer, correction));

        assertThat(submission.outcome()).isEqualTo(SubmissionOutcome.ACCEPTED);
        JsonNode body = json.readTree(only("/v1/invoices/adjust").getBodyAsString());
        assertThat(body.get("original_invoice_reference_code").asText())
                .isEqualTo(SepayEInvoiceProvider.referenceCode(original));
        assertThat(body.get("adjustment_type").asText()).isEqualTo("decrease");
        assertThat(body.get("reference_code").asText()).isEqualTo(REFERENCE);
        assertThat(body.at("/items/0/quantity").asInt()).isEqualTo(-5);
        assertThat(body.at("/items/0/tax_amount").asLong()).isEqualTo(-50_000);
        assertThat(body.get("total_amount").asLong()).isEqualTo(-550_000);
        assertThat(body.has("notes")).isFalse();
        sepay.verify(0, postRequestedFor(urlEqualTo("/v1/invoices/create")));
    }

    @Test
    void aReplacementGoesToReplaceWithTheWholeInvoice() throws Exception {
        signsIn();
        listsTheAccount();
        sepay.stubFor(post(urlEqualTo("/v1/invoices/replace")).willReturn(aResponse().withStatus(202)
                .withHeader("Content-Type", "application/json").withBody(taken("replace"))));
        String original = "0193a5b0-0000-7000-8000-000000000001-1";
        Correction correction = new Correction(EInvoiceType.REPLACEMENT, "1", "C26TSE", 589,
                EInvoiceFixtures.DELIVERED_ON, "Sai don gia", original);

        provider().submit(invoice(TRANSACTION, EInvoiceFixtures.lines(), correction));

        JsonNode body = json.readTree(only("/v1/invoices/replace").getBodyAsString());
        assertThat(body.get("original_invoice_reference_code").asText())
                .isEqualTo(SepayEInvoiceProvider.referenceCode(original));
        assertThat(body.get("items")).hasSize(2);
        assertThat(body.at("/buyer/tax_code").asText()).isEqualTo("0398765432");
    }

    @Test
    void anAdjustmentsTypeFollowsWhatItsLinesDoToTheMoney() {
        EInvoiceLine more = EInvoiceLine.goods(UUID.randomUUID(), 1, UUID.randomUUID(), "SKU-1", "Ly", "Thung", 2,
                null, new BigDecimal("1000"), new BigDecimal("10"));
        EInvoiceLine fewer = EInvoiceLine.goods(UUID.randomUUID(), 2, UUID.randomUUID(), "SKU-2", "Ly", "Thung", -2,
                null, new BigDecimal("1000"), new BigDecimal("10"));
        assertThat(SepayEInvoicePayload.adjustmentType(List.of(more))).isEqualTo("increase");
        assertThat(SepayEInvoicePayload.adjustmentType(List.of(fewer))).isEqualTo("decrease");
        assertThat(SepayEInvoicePayload.adjustmentType(List.of(more, fewer))).isEqualTo("combined");
        assertThat(SepayEInvoicePayload.adjustmentType(List.of())).isEqualTo("info");
    }

    // ---- looking up ------------------------------------------------------------------------------------------------

    @Test
    void aReferenceSepayNeverRecordedIsNotFoundAndMaySafelyBeSentAgain() {
        signsIn();
        sepay.stubFor(get(urlEqualTo("/v1/invoices/" + REFERENCE)).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody(error("NOT_FOUND", "Không tìm thấy hóa đơn"))));

        assertThat(provider().query(TRANSACTION).state()).isEqualTo(ResultState.NOT_FOUND);
    }

    @Test
    void ASSUMPTION_a404WithoutSepaysErrorBodyIsNotKnownNeverNotFound() {
        signsIn();
        sepay.stubFor(get(urlEqualTo("/v1/invoices/" + REFERENCE)).willReturn(aResponse().withStatus(404)
                .withBody("<html>Not Found</html>")));

        assertThat(provider().query(TRANSACTION).state()).isEqualTo(ResultState.UNKNOWN);
    }

    @Test
    void ASSUMPTION_aDraftIsStillBeingProcessed() {
        signsIn();
        sepay.stubFor(get(urlEqualTo("/v1/invoices/" + REFERENCE)).willReturn(okJson(found("draft", "0"))));

        assertThat(provider().query(TRANSACTION).state()).isEqualTo(ResultState.PENDING);
    }

    @Test
    void anIssuedInvoiceCarriesItsNumberAndTheTaxAuthoritysCodeAndSigningTimeFromItsXml() {
        signsIn();
        issuedWithFiles();

        Result result = provider().query(TRANSACTION);

        assertThat(result.state()).isEqualTo(ResultState.ISSUED);
        assertThat(result.invoiceNumber()).isEqualTo(589);
        assertThat(result.taxAuthorityCode()).isEqualTo(TAX_AUTHORITY_CODE);
        assertThat(result.lookupCode()).isEqualTo(REFERENCE);
        assertThat(result.signedAt()).isEqualTo(Instant.parse("2026-11-12T03:05:00Z"));
    }

    @Test
    void ASSUMPTION_aSandboxInvoiceWithoutATaxAuthorityCodeIsGivenOneMarkedSandbox() {
        signsIn();
        sepay.stubFor(get(urlEqualTo("/v1/invoices/" + REFERENCE)).willReturn(okJson(found("issued", "589"))));
        sepay.stubFor(get(urlPathEqualTo("/v1/invoices/" + REFERENCE + "/download")).willReturn(aResponse()
                .withStatus(404).withHeader("Content-Type", "application/json").withBody(error("NOT_FOUND", "x"))));

        Result result = provider().query(TRANSACTION);

        assertThat(result.state()).isEqualTo(ResultState.ISSUED);
        assertThat(result.taxAuthorityCode()).isEqualTo(SepayEInvoiceAnswers.SANDBOX_CODE_PREFIX + REFERENCE);
        assertThat(result.signedAt()).isEqualTo(EInvoiceFixtures.NOW);
    }

    @Test
    void aProductionInvoiceWithoutATaxAuthorityCodeWaitsForIt() {
        signsIn();
        sepay.stubFor(get(urlEqualTo("/v1/invoices/" + REFERENCE)).willReturn(okJson(found("issued", "589"))));
        sepay.stubFor(get(urlPathEqualTo("/v1/invoices/" + REFERENCE + "/download")).willReturn(aResponse()
                .withStatus(404).withHeader("Content-Type", "application/json").withBody(error("NOT_FOUND", "x"))));

        assertThat(provider(properties("PRODUCTION", PROVIDER_ACCOUNT)).query(TRANSACTION).state())
                .isEqualTo(ResultState.PENDING);
    }

    @Test
    void HD1_aSubmissionSepayRefusedAfterTakingItInIsRejectedWithItsWords() {
        signsIn();
        notRecorded();
        tracked("create", "Failed", "Thông tin người mua không hợp lệ: mã số thuế sai");

        Result result = provider().query(TRANSACTION);

        assertThat(result.state()).isEqualTo(ResultState.REJECTED);
        assertThat(result.errorCode()).isEqualTo(SepayEInvoiceAnswers.ISSUANCE_FAILED);
        assertThat(result.message()).isEqualTo("Thông tin người mua không hợp lệ: mã số thuế sai");
        sepay.verify(1, getRequestedFor(urlEqualTo("/v1/invoices/create/check/" + TRACKING)));
    }

    @Test
    void HD1_aResultSepayCannotConfirmIsUnknownAndNeverSentAgain() {
        signsIn();
        notRecorded();
        tracked("adjust", "Failed", "Hệ thống chưa xác nhận được kết quả phát hành với nhà cung cấp hóa đơn.");

        Result result = provider().query(TRANSACTION);

        assertThat(result.state()).as("BR-05: never NOT_FOUND, which would be sent again").isEqualTo(ResultState.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo(SepayEInvoiceAnswers.RESULT_UNCONFIRMED);
        sepay.verify(1, getRequestedFor(urlEqualTo("/v1/invoices/adjust/check/" + TRACKING)));
    }

    @Test
    void HD1_aFailedTrackingWithoutWordsIsNeverTakenForARefusal() {
        signsIn();
        notRecorded();
        tracked("create", "Failed", null);

        assertThat(provider().query(TRANSACTION).state()).isEqualTo(ResultState.UNKNOWN);
    }

    @Test
    void HD1_aSubmissionSepayIsStillRetryingIsPendingThoughTheLookUpDoesNotFindIt() {
        signsIn();
        notRecorded();
        tracked("create", "Pending", "Đang thử lại");

        assertThat(provider().query(TRANSACTION).state()).as("never sent again while SePay retries it")
                .isEqualTo(ResultState.PENDING);
    }

    @Test
    void HD1_aSuccessTheLookUpDoesNotShowYetIsPending() {
        signsIn();
        notRecorded();
        tracked("create", "Success", "Xuất hóa đơn thành công");

        assertThat(provider().query(TRANSACTION).state()).isEqualTo(ResultState.PENDING);
    }

    @Test
    void HD1_ASSUMPTION_aLookUpShowingTheInvoiceRefusedOrVoidIsRejected() {
        signsIn();
        for (String status : List.of("failed", "rejected", "cancelled", "deleted")) {
            sepay.stubFor(get(urlEqualTo("/v1/invoices/" + REFERENCE)).willReturn(okJson(found(status, "0"))));

            Result result = provider().query(TRANSACTION);

            assertThat(result.state()).as(status).isEqualTo(ResultState.REJECTED);
            assertThat(result.errorCode()).isEqualTo("SEPAY_INVOICE_" + status.toUpperCase(java.util.Locale.ROOT));
        }
        sepay.verify(0, getRequestedFor(urlPathEqualTo("/v1/invoices/create/check/" + TRACKING)));
    }

    @Test
    void HD1_noTrackingCodeKeptLeavesTheLookUpToSay() {
        signsIn();
        notRecorded();
        when(calls.lastProviderReference(SepayEInvoiceProvider.CODE, TRANSACTION)).thenReturn(Optional.empty());

        assertThat(provider().query(TRANSACTION).state()).isEqualTo(ResultState.NOT_FOUND);
        sepay.verify(0, getRequestedFor(urlPathEqualTo("/v1/invoices/create/check/" + TRACKING)));
    }

    @Test
    void HD1_aTrackingReferenceThatIsNotSepaysGoesIntoNoPath() {
        signsIn();
        notRecorded();
        when(calls.lastProviderReference(SepayEInvoiceProvider.CODE, TRANSACTION))
                .thenReturn(Optional.of("create/../../v1/usage"));

        assertThat(provider().query(TRANSACTION).state()).isEqualTo(ResultState.NOT_FOUND);
        sepay.verify(0, getRequestedFor(urlEqualTo("/v1/usage")));
    }

    // ---- the account -----------------------------------------------------------------------------------------------

    @Test
    void HD1_theQuotaLeftIsSepaysUsage() {
        signsIn();
        sepay.stubFor(get(urlEqualTo("/v1/usage")).willReturn(okJson("""
                {"data": {"quota_remaining": "534"}}""")));

        assertThat(provider().remainingQuota()).hasValue(534);
        assertThat(provider(new SepayEInvoiceProperties("SANDBOX", null, null, null, sepay.baseUrl(), sepay.baseUrl(),
                Duration.ofSeconds(2), Duration.ofMinutes(10))).remainingQuota()).as("not configured: not asked")
                .isEmpty();
    }

    @Test
    void HD1_aSeriesIsCheckedAgainstTheAccountsTemplates() {
        signsIn();
        sepay.stubFor(get(urlEqualTo("/v1/provider-accounts/" + PROVIDER_ACCOUNT)).willReturn(okJson("""
                {"success": true, "data": {"id": "%s", "provider": "matbao", "active": true,
                 "tax_authority_approved_date": "2026-04-20",
                 "templates": [{"template_code": "1", "invoice_series": "C26TSE",
                                "invoice_label": "1 - C26TSE - Hóa đơn giá trị gia tăng"},
                               {"template_code": "2", "invoice_series": "C26TSE",
                                "invoice_label": "2 - C26TSE - Hóa đơn bán hàng"}]}}""".formatted(PROVIDER_ACCOUNT))));
        SepayEInvoiceProvider sepayProvider = provider(properties("SANDBOX", PROVIDER_ACCOUNT));

        assertThat(sepayProvider.checkSeries("1", "C26TSE")).isEqualTo(EInvoiceProvider.SeriesCheck.OFFERED);
        assertThat(sepayProvider.checkSeries("1", "C26TAA")).isEqualTo(EInvoiceProvider.SeriesCheck.NOT_OFFERED);
        assertThat(sepayProvider.checkSeries("3", "C26TSE")).isEqualTo(EInvoiceProvider.SeriesCheck.NOT_OFFERED);

        when(accounts.find(SepayEInvoiceProvider.CODE)).thenReturn(Optional.of(new ProviderAccounts.Account(
                UUID.randomUUID(), SepayEInvoiceProvider.CODE, "SANDBOX", null, null, true, false, 0)));
        EInvoiceProviderAccountService service = new EInvoiceProviderAccountService(accounts,
                new EInvoiceProviders(List.of(sepayProvider), accounts), mock(SecretCipher.class));
        assertThatThrownBy(() -> service.useSeries(SepayEInvoiceProvider.CODE, "1", "C26TAA", 2026, "1"))
                .isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EINVOICE_SERIES_NOT_AT_PROVIDER);
        verify(accounts, never()).useSeries(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        service.useSeries(SepayEInvoiceProvider.CODE, "1", "C26TSE", 2026, "1");
        verify(accounts).useSeries(new com.stockflow.invoicing.internal.domain.InvoiceSeries(
                SepayEInvoiceProvider.CODE, "1", "C26TSE", 2026), "1");
    }

    @Test
    void HD6_sepayCannotSendANoticeOfAnInvoiceDrawnUpWrongly() {
        assertThat(provider().sendsErrorNotices()).isFalse();
    }

    @Test
    void HD8_onlyAProductionAccountsDocumentsAreHeldToTheirSignature() {
        assertThat(provider().signsDocuments()).isFalse();
        assertThat(provider(properties("PRODUCTION", PROVIDER_ACCOUNT)).signsDocuments()).isTrue();
    }

    @Test
    void aLookUpWithNoAnswerSaysNothing() {
        signsIn();
        sepay.stubFor(get(urlEqualTo("/v1/invoices/" + REFERENCE)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> capture(() -> provider().query(TRANSACTION)))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    // ---- files -----------------------------------------------------------------------------------------------------

    @Test
    void theXmlAndThePdfOfAnIssuedInvoice() {
        signsIn();
        issuedWithFiles();

        Documents documents = provider().download(TRANSACTION);

        assertThat(documents.xml()).isEqualTo(XML);
        assertThat(documents.pdf()).isEqualTo(PDF);
    }

    @Test
    void aFileAddressFromAnAnswerIsFollowedOnlyOverHttps() {
        signsIn();
        sepay.stubFor(get(urlEqualTo("/v1/invoices/" + REFERENCE)).willReturn(okJson(found("issued", "589")
                .replace("\"pdf_url\": null", "\"pdf_url\": \"" + sepay.baseUrl() + "/portal/invoice.pdf\""))));
        sepay.stubFor(get(urlEqualTo("/v1/invoices/" + REFERENCE + "/download?type=xml"))
                .willReturn(okJson(downloaded("xml", XML))));
        sepay.stubFor(get(urlEqualTo("/v1/invoices/" + REFERENCE + "/download?type=pdf")).willReturn(aResponse()
                .withStatus(404).withHeader("Content-Type", "application/json").withBody(error("NOT_FOUND", "x"))));
        sepay.stubFor(get(urlEqualTo("/portal/invoice.pdf")).willReturn(aResponse().withBody(PDF)));

        assertThatThrownBy(() -> capture(() -> provider().download(TRANSACTION)))
                .isInstanceOf(ProviderUnavailableException.class).hasMessageContaining("PDF");
        sepay.verify(0, getRequestedFor(urlEqualTo("/portal/invoice.pdf")));
    }

    @Test
    void anInvoiceNotIssuedHasNoFilesToFetch() {
        signsIn();
        sepay.stubFor(get(urlEqualTo("/v1/invoices/" + REFERENCE)).willReturn(okJson(found("draft", "0"))));

        assertThatThrownBy(() -> capture(() -> provider().download(TRANSACTION)))
                .isInstanceOf(ProviderUnavailableException.class);
        sepay.verify(0, getRequestedFor(urlPathEqualTo("/v1/invoices/" + REFERENCE + "/download")));
    }

    // ---- switching to SePay ----------------------------------------------------------------------------------------

    @Test
    void sepayIsPutInUseOnlyWithAKeyInTheDeploymentAndTakesNoStoredAccount() {
        when(accounts.find(SepayEInvoiceProvider.CODE)).thenReturn(Optional.of(new ProviderAccounts.Account(
                UUID.randomUUID(), SepayEInvoiceProvider.CODE, "SANDBOX", null, null, false, false, 0)));
        SecretCipher cipher = new SecretCipher(new SecretCipherProperties(
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="));
        SepayEInvoiceProvider withoutKey = provider(new SepayEInvoiceProperties("SANDBOX", null, " ", null,
                sepay.baseUrl(), sepay.baseUrl(), Duration.ofSeconds(2), Duration.ofMinutes(10)));
        EInvoiceProviderAccountService service = new EInvoiceProviderAccountService(accounts,
                new EInvoiceProviders(List.of(withoutKey), accounts), cipher);

        assertThatThrownBy(() -> service.activate(SepayEInvoiceProvider.CODE, 0))
                .isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EINVOICE_PROVIDER_ACCOUNT_INCOMPLETE);
        assertThatThrownBy(() -> service.storeAccount(SepayEInvoiceProvider.CODE, "SANDBOX", "x", "y", 0))
                .isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(accounts, never()).activate(SepayEInvoiceProvider.CODE, 0);

        EInvoiceProviderAccountService withKey = new EInvoiceProviderAccountService(accounts,
                new EInvoiceProviders(List.of(provider()), accounts), cipher);
        withKey.activate(SepayEInvoiceProvider.CODE, 0);
        verify(accounts).activate(SepayEInvoiceProvider.CODE, 0);
    }

    @Test
    void thePropertiesNeverPrintTheSecret() {
        assertThat(properties("SANDBOX", null).toString()).doesNotContain(CLIENT_SECRET).doesNotContain(CLIENT_ID);
    }

    // ---- the pieces ------------------------------------------------------------------------------------------------

    private SepayEInvoiceProvider provider() {
        return provider(properties("SANDBOX", null));
    }

    private SepayEInvoiceProperties properties(String environment, String providerAccountId) {
        return new SepayEInvoiceProperties(environment, CLIENT_ID, CLIENT_SECRET, providerAccountId, sepay.baseUrl(),
                sepay.baseUrl(), Duration.ofSeconds(2), Duration.ofMinutes(10));
    }

    private SepayEInvoiceProvider provider(SepayEInvoiceProperties properties) {
        HttpClientProperties http = new HttpClientProperties(Duration.ofSeconds(2), Duration.ofSeconds(2));
        SepayEInvoiceClient client = new SepayEInvoiceClient(new RestClientFactory(http), http, properties, json,
                clock);
        return new SepayEInvoiceProvider(client, properties, accounts, calls, json, clock);
    }

    private static InvoiceDocument invoice(String transactionId) {
        return invoice(transactionId, EInvoiceFixtures.lines(), null);
    }

    private static InvoiceDocument invoice(String transactionId, List<EInvoiceLine> lines, Correction correction) {
        BigDecimal subtotal = lines.stream().map(EInvoiceLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tax = lines.stream().map(EInvoiceLine::taxAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new InvoiceDocument(transactionId, "EINV-20261112-0001", "1", "C26TSE", EInvoiceFixtures.DELIVERED_ON,
                EInvoiceFixtures.SELLER, EInvoiceFixtures.BUYER, "VND", "TM/CK", "SO-20261112-0001",
                "HD-20261112-0001", lines, TaxRateTotal.of(lines), subtotal, tax, subtotal.add(tax),
                "Hai trieu bay tram bon muoi nghin dong", correction);
    }

    private void signsIn() {
        sepay.stubFor(post(urlEqualTo("/v1/token")).willReturn(okJson(tokenAnswer(TOKEN))));
    }

    private void listsTheAccount() {
        sepay.stubFor(get(urlPathEqualTo("/v1/provider-accounts")).willReturn(okJson("""
                {"data": {"paging": {"per_page": 20, "total": 2, "has_more": false, "current_page": 1, "page_count": 1},
                  "items": [{"id": "9d9d9d9d-0000-0000-0000-000000000000", "provider": "matbao", "active": false,
                             "tax_authority_approved_date": "2026-01-02"},
                            {"id": "%s", "provider": "matbao", "active": true,
                             "tax_authority_approved_date": "2026-04-20"}]}}""".formatted(PROVIDER_ACCOUNT))));
    }

    /** The look-up has no invoice under the reference. */
    private void notRecorded() {
        sepay.stubFor(get(urlEqualTo("/v1/invoices/" + REFERENCE)).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody(error("NOT_FOUND", "Không tìm thấy hóa đơn"))));
    }

    /** The transaction's last submission was taken in under {@link #TRACKING}, and its tracking says {@code status}. */
    private void tracked(String kind, String status, String message) {
        when(calls.lastProviderReference(SepayEInvoiceProvider.CODE, TRANSACTION))
                .thenReturn(Optional.of(kind + "/" + TRACKING));
        sepay.stubFor(get(urlEqualTo("/v1/invoices/" + kind + "/check/" + TRACKING)).willReturn(okJson("""
                {"success": true, "data": {"reference_code": "%s", "status": "%s", "message": %s}}""".formatted(
                REFERENCE, status, message == null ? "null" : "\"" + message + "\""))));
    }

    private void issuedWithFiles() {
        sepay.stubFor(get(urlEqualTo("/v1/invoices/" + REFERENCE)).willReturn(okJson(found("issued", "589"))));
        sepay.stubFor(get(urlEqualTo("/v1/invoices/" + REFERENCE + "/download?type=xml"))
                .willReturn(okJson(downloaded("xml", XML))));
        sepay.stubFor(get(urlEqualTo("/v1/invoices/" + REFERENCE + "/download?type=pdf"))
                .willReturn(okJson(downloaded("pdf", PDF))));
    }

    private static String tokenAnswer(String token) {
        return """
                {"success": true, "data": {"access_token": "%s", "token_type": "Bearer", "expires_in": 86400}}"""
                .formatted(token);
    }

    private static String taken(String what) {
        return """
                {"success": true, "data": {"tracking_code": "084e179d-d95a-11f0-aef4-52c7e9b4f41b",
                 "tracking_url": "https://einvoice-api.sepay.vn/v1/invoices/%s/check/084e179d-d95a-11f0-aef4-52c7e9b4f41b",
                 "message": "Đã tạo yêu cầu"}}""".formatted(what);
    }

    private static String found(String status, String number) {
        return """
                {"success": true, "data": {"reference_code": "%s", "invoice_number": "%s", "issued_date": "2026-11-12",
                 "pdf_url": null, "xml_url": null, "status": "%s",
                 "buyer": {"name": "Cong ty Tra Sua", "tax_code": "0398765432", "address": "12 Nguyen Hue",
                           "email": "ke.toan@trasua.example.com", "phone": null},
                 "seller_store": null, "total_before_tax": 2500000, "tax_amount": 240000, "total_amount": 2740000,
                 "notes": null, "source": "api",
                 "items": [{"line_number": 1, "line_type": 1, "item_code": "CUP12", "item_name": "Ly giay 12oz",
                            "unit": "Thung", "quantity": "20.00", "unit_price": "100000.00",
                            "total_amount": "2000000.00", "tax_rate": "10.00", "tax_amount": "200000.00"}]}}"""
                .formatted(REFERENCE, number, status);
    }

    private static String downloaded(String type, byte[] bytes) {
        return """
                {"success": true, "data": {"file_type": "%s", "file_name": "HD_0000589_20261112.%s", "content": "%s"}}"""
                .formatted(type, type, Base64.getEncoder().encodeToString(bytes));
    }

    private static String error(String code, String message) {
        return """
                {"success": false, "error": {"code": "%s", "message": "%s"}}""".formatted(code, message);
    }

    private static LoggedRequest only(String path) {
        List<LoggedRequest> requests = sepay.findAll(anyRequestedFor(urlPathEqualTo(path)));
        assertThat(requests).as(path).hasSize(1);
        return requests.get(0);
    }

    private <T> T capture(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException failure) {
            thrown.add(failure);
            throw failure;
        }
    }
}
