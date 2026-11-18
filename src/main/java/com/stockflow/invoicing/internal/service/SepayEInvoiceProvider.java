package com.stockflow.invoicing.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.common.storage.Checksums;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceType;
import com.stockflow.invoicing.internal.domain.ProviderAccounts;
import com.stockflow.invoicing.internal.domain.ProviderCallLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SePay eInvoice behind the provider port. Not the default: the sandbox stays the provider in use until the deployment
 * configures SePay's key ({@link SepayEInvoiceProperties}) and an administrator switches to it.
 *
 * <h2>How SePay's flow becomes the port's</h2>
 * <ul>
 *   <li><b>submit</b> is {@code POST /v1/invoices/create} with {@code is_draft} false: SePay signs with the seller's
 *       certificate held on its side (HSM), numbers the invoice and sends it to the tax authority, after it answered.
 *       An adjustment is {@code /v1/invoices/adjust}, a replacement {@code /v1/invoices/replace}, both naming the
 *       original by the reference it was issued under - so only an invoice SePay issued is corrected here (HĐ-5: a
 *       correction goes through the provider that issued the original). The answer's tracking code is handed back
 *       ({@link Submission#providerReference()}) and kept in the call log.</li>
 *   <li><b>query</b> is {@code GET /v1/invoices/{reference_code}}: SePay's documentation gives exactly this as the way
 *       to learn whether a request whose answer was lost was taken in - a 404 means it was not, and sending it again
 *       under the same reference is safe. When it shows no issued invoice, the latest tracking code of the transaction
 *       is asked too ({@code GET /v1/invoices/{kind}/check/{tracking_code}}): it is the only answer that tells a
 *       submission SePay refused after taking it in ({@code Failed}, REJECTED), one it is still retrying
 *       ({@code Pending}, for up to two hours - never sent again meanwhile) and one whose result it cannot confirm
 *       (UNKNOWN, never sent again; kltn-docs 21 BR-05). Without it such an invoice stayed SUBMITTING for good
 *       (HĐ-1).</li>
 *   <li><b>download</b> is {@code GET /v1/invoices/{reference_code}/download}, for {@code xml} and {@code pdf}, with the
 *       addresses the look-up gives ({@code xml_url}, {@code pdf_url}) as the fallback.</li>
 *   <li><b>remainingQuota</b> is {@code GET /v1/usage} (kltn-docs 21 BR-09), <b>checkSeries</b> the account's
 *       templates ({@code GET /v1/provider-accounts/{id}}).</li>
 *   <li><b>No 04/SS-HĐĐT notice</b>: SePay's eInvoice API lists create, issue, adjust, replace, tracking, details,
 *       list, download, quota, e-mail history and draft deletion, and no call for a notice of an invoice drawn up
 *       wrongly (developer.sepay.vn/vi/einvoice-api/v1/tong-quan). {@link #sendsErrorNotices()} says no, and such a
 *       notice is filed on a portal by hand (HĐ-6).</li>
 * </ul>
 *
 * <h2>Never twice</h2>
 *
 * <p>SePay refuses a second invoice under one {@code reference_code} ({@code EINVOICE_DOCUMENT_EXISTED}, answered
 * DUPLICATE here). The reference is derived from the system's transaction id ({@link #referenceCode}): the same for
 * every retry and question of an attempt, another for the next attempt. The look-up does not find an invoice
 * for a few seconds after SePay took it in (seen on its sandbox), and the documentation does not say whether the
 * refusal holds while the first request is still processed, so a transaction a look-up does not find is sent again only
 * after a quiet period ({@link #resubmission()}), and never while its tracking says {@code Pending}.</p>
 *
 * <p>A refusal before anything was sent - a rate SePay cannot carry, a series without SePay's template code, an
 * account that lists no active registration - is REJECTED: no invoice exists for the transaction, and the accountant
 * corrects it and issues it again. Each used to be taken for "SePay did not answer", and the invoice waited for an
 * answer that could never come.</p>
 *
 * <p>What an answer means is {@link SepayEInvoiceAnswers}' alone, and that class says what in it is unverified.
 * Nothing here logs the key, a token, a payload or an answer's body.</p>
 */
@Component
class SepayEInvoiceProvider implements EInvoiceProvider {

    static final String CODE = "SEPAY_EINVOICE";
    private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int REFERENCE_LENGTH = 14;

    private static final Logger log = LoggerFactory.getLogger(SepayEInvoiceProvider.class);

    private final SepayEInvoiceClient client;
    private final SepayEInvoiceProperties properties;
    private final ProviderAccounts accounts;
    private final ProviderCallLog calls;
    private final ObjectMapper json;
    private final Clock clock;
    /** The registration SePay lists first, when the deployment names none: looked up once. */
    private final AtomicReference<String> listedAccount = new AtomicReference<>();

    SepayEInvoiceProvider(SepayEInvoiceClient client, SepayEInvoiceProperties properties, ProviderAccounts accounts,
                          ProviderCallLog calls, ObjectMapper json, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.accounts = accounts;
        this.calls = calls;
        this.json = json;
        this.clock = clock;
    }

    /** Refused before anything was sent: no invoice exists for the transaction. */
    private static final class NotSendable extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final String code;

        NotSendable(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Resubmission resubmission() {
        return new Resubmission(properties.resubmitQuietPeriod(), null);
    }

    /** Yes: SePay takes an adjustment and a replacement as such, each naming the invoice it changes. */
    @Override
    public boolean issuesCorrections() {
        return true;
    }

    @Override
    public boolean accountInDeployment() {
        return true;
    }

    @Override
    public boolean deploymentAccountReady() {
        return properties.configured();
    }

    @Override
    public Optional<AccountProblem> accountProblem(String errorCode) {
        if (errorCode == null) {
            return Optional.empty();
        }
        return switch (errorCode) {
            case SepayEInvoiceAnswers.QUOTA_EXHAUSTED -> Optional.of(AccountProblem.QUOTA_EXHAUSTED);
            case SepayEInvoiceAnswers.BILLING_UNPAID -> Optional.of(AccountProblem.BILLING_UNPAID);
            case SepayEInvoiceAnswers.ACCOUNT_NOT_READY -> Optional.of(AccountProblem.ACCOUNT_NOT_READY);
            default -> Optional.empty();
        };
    }

    /**
     * Only in production: what SePay's sandbox hands back is simulated data that reaches no tax authority, and whether
     * it is signed is not documented - an unsigned sandbox file must not hold back every test invoice's mail.
     */
    @Override
    public boolean signsDocuments() {
        return properties.production();
    }

    @Override
    public OptionalLong remainingQuota() {
        if (!properties.configured()) {
            return OptionalLong.empty();
        }
        SepayEInvoiceClient.Answer answer = client.usage();
        Long remaining = SepayEInvoiceAnswers.quotaRemaining(answer.status(), answer.json());
        return remaining == null ? OptionalLong.empty() : OptionalLong.of(remaining);
    }

    @Override
    public SeriesCheck checkSeries(String providerTemplateCode, String symbol) {
        if (!properties.configured()) {
            return SeriesCheck.NOT_CHECKED;
        }
        String account;
        try {
            account = providerAccountId();
        } catch (NotSendable noRegistration) {
            return SeriesCheck.NOT_OFFERED;
        }
        SepayEInvoiceClient.Answer answer = client.providerAccount(account);
        Boolean offered = SepayEInvoiceAnswers.offersSeries(answer.status(), answer.json(), providerTemplateCode,
                symbol);
        return offered == null ? SeriesCheck.NOT_CHECKED : offered ? SeriesCheck.OFFERED : SeriesCheck.NOT_OFFERED;
    }

    @Override
    public Submission submit(InvoiceDocument invoice) {
        try {
            SepayEInvoicePayload.requireRates(invoice.lines());
        } catch (SepayEInvoicePayload.UnsupportedRateException unsupported) {
            // Refused before anything was sent: no invoice exists for the transaction.
            return new Submission(SubmissionOutcome.REJECTED, "SEPAY_TAX_RATE_UNSUPPORTED", unsupported.getMessage());
        }
        String body;
        try {
            body = body(invoice, referenceCode(invoice.transactionId()));
        } catch (NotSendable refused) {
            return new Submission(SubmissionOutcome.REJECTED, refused.code, refused.getMessage());
        }
        String kind = invoice.correction() == null ? "create"
                : invoice.correction().type() == EInvoiceType.REPLACEMENT ? "replace" : "adjust";
        SepayEInvoiceClient.Answer answer = switch (kind) {
            case "create" -> client.create(body);
            case "replace" -> client.replace(body);
            default -> client.adjust(body);
        };
        Submission submission = SepayEInvoiceAnswers.submission(answer.status(), answer.json(), kind);
        if (accountProblem(submission.errorCode()).isPresent()) {
            log.error("SePay eInvoice refused the account ({}): invoices are refused until it is fixed on SePay's "
                    + "portal", submission.errorCode());
        }
        return submission;
    }

    @Override
    public Result query(String transactionId) {
        String reference = referenceCode(transactionId);
        SepayEInvoiceClient.Answer answer = client.invoice(reference);
        ResultState state = SepayEInvoiceAnswers.state(answer.status(), answer.json());
        JsonNode data = answer.json() == null ? null : answer.json().get("data");
        if (state == ResultState.ISSUED) {
            Result issued = SepayEInvoiceAnswers.issued(data, xmlOrNull(reference, data), reference,
                    !properties.production(), clock.instant());
            if (issued == null) {
                log.warn("SePay eInvoice issued transaction {} but gave no tax authority code yet",
                        LogSafe.text(transactionId));
                return Result.of(ResultState.PENDING);
            }
            return issued;
        }
        if (state == ResultState.REJECTED) {
            String status = SepayEInvoiceAnswers.text(data, "status");
            return new Result(ResultState.REJECTED, null, null, null, null,
                    "SEPAY_INVOICE_" + status.toUpperCase(Locale.ROOT),
                    "SePay eInvoice shows the invoice as " + status);
        }
        TrackingAnswer tracking = tracking(transactionId);
        return switch (tracking.state()) {
            case FAILED -> new Result(ResultState.REJECTED, null, null, null, null,
                    SepayEInvoiceAnswers.ISSUANCE_FAILED, tracking.message());
            case UNCONFIRMED -> new Result(ResultState.UNKNOWN, null, null, null, null,
                    SepayEInvoiceAnswers.RESULT_UNCONFIRMED, tracking.message() != null ? tracking.message()
                    : "SePay eInvoice cannot confirm the result; ask SePay before anything is sent again");
            // SePay is still retrying it, for up to two hours: never "not there", or it would be sent again.
            case PENDING -> Result.of(ResultState.PENDING);
            // Done, and the look-up does not show it yet (it lags a few seconds): asked again, never sent again.
            case SUCCESS -> state == ResultState.NOT_FOUND ? Result.of(ResultState.PENDING) : lookedUp(state, answer);
            case NONE -> lookedUp(state, answer);
        };
    }

    /** What the look-up alone says, when the tracking adds nothing. */
    private static Result lookedUp(ResultState state, SepayEInvoiceClient.Answer answer) {
        return state == ResultState.UNKNOWN
                ? new Result(ResultState.UNKNOWN, null, null, null, null, "SEPAY_HTTP_" + answer.status(),
                "SePay eInvoice's answer did not say where the invoice stands")
                : Result.of(state);
    }

    private record TrackingAnswer(SepayEInvoiceAnswers.Tracking state, String message) {
    }

    /** The latest tracking of the transaction; NONE when none was recorded (a submission from before it was kept). */
    private TrackingAnswer tracking(String transactionId) {
        Optional<String> reference = calls.lastProviderReference(CODE, transactionId);
        if (reference.isEmpty()) {
            return new TrackingAnswer(SepayEInvoiceAnswers.Tracking.NONE, null);
        }
        SepayEInvoiceClient.Answer answer;
        try {
            answer = client.tracking(reference.get());
        } catch (IllegalArgumentException notOurs) {
            return new TrackingAnswer(SepayEInvoiceAnswers.Tracking.NONE, null);
        }
        return new TrackingAnswer(SepayEInvoiceAnswers.tracking(answer.status(), answer.json()),
                SepayEInvoiceAnswers.trackingMessage(answer.json()));
    }

    @Override
    public Documents download(String transactionId) {
        String reference = referenceCode(transactionId);
        SepayEInvoiceClient.Answer found = client.invoice(reference);
        if (SepayEInvoiceAnswers.state(found.status(), found.json()) != ResultState.ISSUED) {
            throw new ProviderUnavailableException("SePay eInvoice: transaction " + transactionId + " is not issued");
        }
        JsonNode data = found.json().get("data");
        byte[] xml = xmlOrNull(reference, data);
        if (xml == null) {
            throw new ProviderUnavailableException("SePay eInvoice has no XML of the invoice yet");
        }
        byte[] pdf = file(reference, "pdf", SepayEInvoiceAnswers.text(data, "pdf_url"));
        if (pdf == null) {
            throw new ProviderUnavailableException("SePay eInvoice has no PDF of the invoice yet");
        }
        return new Documents(xml, pdf);
    }

    /** The signed XML, or null while SePay has none to hand over. */
    private byte[] xmlOrNull(String reference, JsonNode data) {
        try {
            return SepayEInvoiceAnswers.xmlOf(file(reference, "xml", SepayEInvoiceAnswers.text(data, "xml_url")));
        } catch (ProviderUnavailableException notNow) {
            return null;
        }
    }

    /** A file by the download call, else from the address the look-up gave; null when neither hands it over. */
    private byte[] file(String reference, String type, String url) {
        SepayEInvoiceClient.Answer answer = client.download(reference, type);
        byte[] bytes = SepayEInvoiceAnswers.file(answer.status(), answer.json());
        if (bytes == null && url != null) {
            bytes = client.fetch(url);
        }
        return bytes == null || bytes.length == 0 ? null : bytes;
    }

    /**
     * @throws NotSendable when the invoice cannot be written for SePay: nothing is sent
     */
    private String body(InvoiceDocument invoice, String reference) {
        String account = providerAccountId();
        if (invoice.correction() == null) {
            String template = accounts.providerTemplateCode(CODE, invoice.templateCode(), invoice.symbol())
                    .orElseThrow(() -> new NotSendable(SepayEInvoiceAnswers.TEMPLATE_MISSING,
                            "Series %s%s has no SePay template code; declare it again with SePay's template code"
                                    .formatted(invoice.templateCode(), invoice.symbol())));
            return SepayEInvoicePayload.original(json, invoice, reference, account, template, clock.instant());
        }
        String original = invoice.correction().originalTransactionId();
        if (original == null) {
            throw new NotSendable("SEPAY_ORIGINAL_UNKNOWN",
                    "The corrected invoice's transaction at SePay is not known; it cannot be named to SePay");
        }
        return invoice.correction().type() == EInvoiceType.REPLACEMENT
                ? SepayEInvoicePayload.replacement(json, invoice, reference, account, referenceCode(original),
                clock.instant())
                : SepayEInvoicePayload.adjustment(json, invoice, reference, account, referenceCode(original),
                clock.instant());
    }

    /**
     * The registration that issues: the deployment's, else the first active one SePay lists.
     *
     * @throws NotSendable                  when SePay lists none active: the account is not ready
     * @throws ProviderUnavailableException when SePay does not answer the list
     */
    private String providerAccountId() {
        if (properties.providerAccountId() != null) {
            return properties.providerAccountId();
        }
        String kept = listedAccount.get();
        if (kept != null) {
            return kept;
        }
        SepayEInvoiceClient.Answer answer = client.providerAccounts();
        JsonNode data = answer.json() == null ? null : answer.json().get("data");
        JsonNode items = data == null ? null : data.get("items");
        if (answer.status() >= 200 && answer.status() < 300 && items != null && items.isArray()) {
            for (JsonNode item : items) {
                JsonNode active = item.get("active");
                String id = SepayEInvoiceAnswers.text(item, "id");
                if (id != null && active != null && active.asBoolean()) {
                    listedAccount.set(id);
                    return id;
                }
            }
            throw new NotSendable(SepayEInvoiceAnswers.ACCOUNT_NOT_READY,
                    "SePay eInvoice lists no active invoicing registration for the account");
        }
        throw new ProviderUnavailableException("SePay eInvoice did not list its invoicing registrations (HTTP "
                + answer.status() + ")");
    }

    /**
     * SePay's {@code reference_code} for a transaction id of the system: 14 characters of Crockford's base32 (no I, L,
     * O or U) taken from a SHA-256 of the id. SePay prints it as the invoice's "Mã tra cứu", which the buyer types on
     * the invoicing portal, and as its "Số nội bộ": a UUID's 36 characters break over three lines of the template.
     * Seventy bits keep two transactions from sharing one for any number of invoices this system will issue - which
     * matters, because SePay would answer the second with the first one's invoice ({@code EINVOICE_DOCUMENT_EXISTED}).
     */
    static String referenceCode(String transactionId) {
        byte[] hash = Checksums.sha256().digest(("stockflow-einvoice:" + transactionId).getBytes(StandardCharsets.UTF_8));
        StringBuilder code = new StringBuilder(REFERENCE_LENGTH);
        long bits = 0;
        int held = 0;
        for (int i = 0; code.length() < REFERENCE_LENGTH; i++) {
            bits = (bits << 8) | (hash[i] & 0xFF);
            held += 8;
            while (held >= 5 && code.length() < REFERENCE_LENGTH) {
                held -= 5;
                code.append(CROCKFORD.charAt((int) ((bits >>> held) & 31)));
            }
        }
        return code.toString();
    }
}
