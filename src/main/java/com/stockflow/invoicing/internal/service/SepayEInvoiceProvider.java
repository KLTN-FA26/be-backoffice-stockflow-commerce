package com.stockflow.invoicing.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.common.storage.Checksums;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceType;
import com.stockflow.invoicing.internal.domain.ProviderAccounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
 *       original by the reference it was issued under.</li>
 *   <li><b>query</b> is {@code GET /v1/invoices/{reference_code}}: SePay's documentation gives exactly this as the way
 *       to learn whether a request whose answer was lost was taken in - a 404 means it was not, and sending it again
 *       under the same reference is safe. SePay's tracking code is never needed, so nothing about SePay is stored.</li>
 *   <li><b>download</b> is {@code GET /v1/invoices/{reference_code}/download}, for {@code xml} and {@code pdf}, with the
 *       addresses the look-up gives ({@code xml_url}, {@code pdf_url}) as the fallback.</li>
 * </ul>
 *
 * <h2>Never twice</h2>
 *
 * <p>SePay refuses a second invoice under one {@code reference_code} ({@code EINVOICE_DOCUMENT_EXISTED}, answered
 * DUPLICATE here). The reference is derived from the system's transaction id ({@link #referenceCode}): the same for
 * every retry and question of an attempt, another for the next attempt. The look-up does not find an invoice
 * for a few seconds after SePay took it in (seen on its sandbox), and the documentation does not say whether the
 * refusal holds while the first request is still processed, so a transaction a look-up does not find is sent again only
 * after a quiet period ({@link #resubmission()}).</p>
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
    private final ObjectMapper json;
    private final Clock clock;
    /** The registration SePay lists first, when the deployment names none: looked up once. */
    private final AtomicReference<String> listedAccount = new AtomicReference<>();

    SepayEInvoiceProvider(SepayEInvoiceClient client, SepayEInvoiceProperties properties, ProviderAccounts accounts,
                          ObjectMapper json, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.accounts = accounts;
        this.json = json;
        this.clock = clock;
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
    public Submission submit(InvoiceDocument invoice) {
        try {
            SepayEInvoicePayload.requireRates(invoice.lines());
        } catch (SepayEInvoicePayload.UnsupportedRateException unsupported) {
            // Refused before anything was sent: no invoice exists for the transaction.
            return new Submission(SubmissionOutcome.REJECTED, "SEPAY_TAX_RATE_UNSUPPORTED", unsupported.getMessage());
        }
        String body = body(invoice, referenceCode(invoice.transactionId()));
        SepayEInvoiceClient.Answer answer = invoice.correction() == null ? client.create(body)
                : invoice.correction().type() == EInvoiceType.REPLACEMENT ? client.replace(body)
                : client.adjust(body);
        try {
            return SepayEInvoiceAnswers.submission(answer.status(), answer.json());
        } catch (SepayEInvoiceAnswers.AccountStateException account) {
            log.error("SePay eInvoice refused the account ({}): the invoice waits until it is fixed on SePay's portal",
                    account.code());
            throw new ProviderUnavailableException(account.getMessage());
        }
    }

    @Override
    public Result query(String transactionId) {
        String reference = referenceCode(transactionId);
        SepayEInvoiceClient.Answer answer = client.invoice(reference);
        ResultState state = SepayEInvoiceAnswers.state(answer.status(), answer.json());
        if (state != ResultState.ISSUED) {
            return state == ResultState.UNKNOWN
                    ? new Result(ResultState.UNKNOWN, null, null, null, null, "SEPAY_HTTP_" + answer.status(),
                    "SePay eInvoice's answer did not say where the invoice stands")
                    : Result.of(state);
        }
        JsonNode data = answer.json().get("data");
        Result issued = SepayEInvoiceAnswers.issued(data, xmlOrNull(reference, data), reference,
                !properties.production(), clock.instant());
        if (issued == null) {
            log.warn("SePay eInvoice issued transaction {} but gave no tax authority code yet",
                    LogSafe.text(transactionId));
            return Result.of(ResultState.PENDING);
        }
        return issued;
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

    private String body(InvoiceDocument invoice, String reference) {
        String account = providerAccountId();
        if (invoice.correction() == null) {
            String template = accounts.providerTemplateCode(CODE, invoice.templateCode(), invoice.symbol())
                    .orElseThrow(() -> new ProviderUnavailableException(
                            "Series %s has no SePay template code".formatted(invoice.symbol())));
            return SepayEInvoicePayload.original(json, invoice, reference, account, template, clock.instant());
        }
        String original = invoice.correction().originalTransactionId();
        if (original == null) {
            throw new ProviderUnavailableException("The corrected invoice's transaction is not known");
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
     * @throws ProviderUnavailableException when SePay lists none
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
        }
        throw new ProviderUnavailableException("SePay eInvoice lists no active invoicing registration (HTTP "
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
