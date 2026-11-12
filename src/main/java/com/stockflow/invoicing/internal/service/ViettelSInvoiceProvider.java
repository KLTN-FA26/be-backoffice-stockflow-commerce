package com.stockflow.invoicing.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.config.SellerProperties;
import com.stockflow.common.logging.LogSafe;
import com.stockflow.common.security.SecretCipher;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.ProviderAccounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

/**
 * Viettel SInvoice behind the provider port (plan §1.3, package E3). Not the default: the sandbox stays the provider in
 * use until an administrator stores an account and switches ({@code EInvoiceProviderAccountService}).
 *
 * <h2>How SInvoice's flow becomes the port's</h2>
 * <ul>
 *   <li><b>submit</b> is {@code createInvoice}: SInvoice signs with the seller's certificate held on its side (HSM) and
 *       numbers the invoice in the same call. The draft API ({@code createOrUpdateInvoiceDraft}) is not used: a draft
 *       is issued only by hand on SInvoice's portal and its number never comes back. A USB token is not supported -
 *       that flow signs on a person's machine.</li>
 *   <li><b>query</b> is {@code searchInvoiceByTransactionUuid}. The invoice is ISSUED for the system only once the tax
 *       authority's code is on it ({@code codeOfTax}); numbered and still waiting for the code is PENDING.</li>
 *   <li><b>download</b> is {@code getInvoiceRepresentationFile}, twice: {@code ZIP} for the signed XML - the invoice in
 *       law - and {@code PDF}. SInvoice names an invoice by its number and template, so the transaction is looked up
 *       first.</li>
 * </ul>
 *
 * <h2>Never twice</h2>
 *
 * <p>SInvoice answers a transaction id it holds with the invoice it made the first time - for three days, and not
 * while the first submission is still running, when it may make two. {@link #resubmission()} hands both limits to the
 * caller, and the client waits longer for an answer than the quiet period, so a submission is never abandoned and sent
 * again while SInvoice may still be working on it.</p>
 *
 * <p>The system's transaction id (the invoice's id and its attempt, 38 characters or more) is longer than the 36
 * SInvoice takes, so SInvoice is given a UUID derived from it: the same for every retry and question of an attempt,
 * another for the next attempt.</p>
 *
 * <p>What an answer means is {@link ViettelSInvoiceAnswers}' alone, and that class says what in it is unverified.
 * Nothing here logs an account, a token, a payload or an answer's body.</p>
 */
@Component
class ViettelSInvoiceProvider implements EInvoiceProvider {

    static final String CODE = "VIETTEL_SINVOICE";
    /** How often an empty file is asked for again, a moment apart: SInvoice renders it a few seconds after issuing. */
    private static final int FILE_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(ViettelSInvoiceProvider.class);

    private final ViettelSInvoiceClient client;
    private final ViettelSInvoiceProperties properties;
    private final ProviderAccounts accounts;
    private final SecretCipher cipher;
    private final SellerProperties seller;
    private final ObjectMapper json;
    private final Clock clock;

    ViettelSInvoiceProvider(ViettelSInvoiceClient client, ViettelSInvoiceProperties properties,
                            ProviderAccounts accounts, SecretCipher cipher, SellerProperties seller, ObjectMapper json,
                            Clock clock) {
        this.client = client;
        this.properties = properties;
        this.accounts = accounts;
        this.cipher = cipher;
        this.seller = seller;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Resubmission resubmission() {
        return new Resubmission(properties.resubmitQuietPeriod(), properties.resubmitWindow());
    }

    @Override
    public Submission submit(InvoiceDocument invoice) {
        ViettelSInvoiceClient.Account account = account();
        String template = accounts.providerTemplateCode(CODE, invoice.templateCode(), invoice.symbol())
                .orElseThrow(() -> new ProviderUnavailableException(
                        "Series %s has no SInvoice template code".formatted(invoice.symbol())));
        String body = ViettelSInvoicePayload.of(json, invoice, transactionUuid(invoice.transactionId()), template,
                properties.paymentStatusPaid(), clock.instant());
        // The invoice's own seller, not the configured one: it is what the invoice says, and SInvoice checks it
        // against the account.
        ViettelSInvoiceClient.Answer answer = client.createInvoice(account, invoice.seller().taxCode(), body);
        return ViettelSInvoiceAnswers.submission(answer.status(), answer.json());
    }

    @Override
    public Result query(String transactionId) {
        ViettelSInvoiceClient.Answer answer = client.searchByTransactionUuid(account(), supplierTaxCode(),
                transactionUuid(transactionId));
        Result result = ViettelSInvoiceAnswers.result(answer.status(), answer.json());
        if (result.state() == ResultState.PENDING && ViettelSInvoiceAnswers.exchangeTrouble(answer.json())) {
            // Numbered at SInvoice, no tax authority code, and SInvoice reports trouble with the tax authority: it
            // will not settle by waiting. What the trouble is stays on SInvoice's portal, not in this log.
            log.warn("Viettel SInvoice reports trouble with the tax authority for transaction {}; it needs a look on "
                    + "SInvoice's portal", LogSafe.text(transactionId));
        }
        return result;
    }

    @Override
    public Documents download(String transactionId) {
        ViettelSInvoiceClient.Account account = account();
        String taxCode = supplierTaxCode();
        String uuid = transactionUuid(transactionId);
        ViettelSInvoiceClient.Answer found = client.searchByTransactionUuid(account, taxCode, uuid);
        if (ViettelSInvoiceAnswers.result(found.status(), found.json()).state() != ResultState.ISSUED) {
            throw new ProviderUnavailableException("Viettel SInvoice: transaction " + transactionId + " is not issued");
        }
        String invoiceNo = ViettelSInvoiceAnswers.invoiceNo(found.json());
        String symbol = ViettelSInvoiceAnswers.symbol(invoiceNo);
        String template = accounts.providerTemplateCode(CODE, null, symbol)
                .orElseThrow(() -> new ProviderUnavailableException(
                        "Series %s has no SInvoice template code".formatted(symbol)));
        byte[] zip = file(account, taxCode, template, invoiceNo, uuid, "ZIP");
        byte[] pdf = file(account, taxCode, template, invoiceNo, uuid, "PDF");
        return new Documents(ViettelSInvoiceAnswers.xmlOf(zip), pdf);
    }

    private byte[] file(ViettelSInvoiceClient.Account account, String taxCode, String template, String invoiceNo,
                        String uuid, String fileType) {
        for (int attempt = 1; ; attempt++) {
            ViettelSInvoiceClient.Answer answer = client.representationFile(account, taxCode, template, invoiceNo, uuid,
                    fileType);
            byte[] bytes = ViettelSInvoiceAnswers.file(answer.status(), answer.json());
            if (bytes != null && bytes.length > 0) {
                return bytes;
            }
            if (attempt == FILE_ATTEMPTS) {
                // Still SUBMITTING for the system: the next reconciliation downloads again.
                throw new ProviderUnavailableException("Viettel SInvoice has no " + fileType + " of the invoice yet");
            }
            try {
                Thread.sleep(properties.filePause().toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ProviderUnavailableException("Interrupted while waiting for Viettel SInvoice's file");
            }
        }
    }

    /**
     * The account, its password opened for this call.
     *
     * @throws ProviderUnavailableException when no account is stored, or its password does not open (the secrets key
     *         changed since it was sealed). Nothing was sent, so nothing about the invoice changed
     */
    private ViettelSInvoiceClient.Account account() {
        ProviderAccounts.Account stored = accounts.find(CODE).filter(ProviderAccounts.Account::complete)
                .orElseThrow(() -> new ProviderUnavailableException("Viettel SInvoice has no account stored"));
        String password;
        try {
            password = cipher.open(stored.secretSealed(), EInvoiceProviderAccountService.secretContext(stored.id()));
        } catch (RuntimeException doesNotOpen) {
            log.error("The Viettel SInvoice account's password cannot be opened: {}",
                    doesNotOpen.getClass().getSimpleName());
            throw new ProviderUnavailableException("The Viettel SInvoice account's password cannot be opened");
        }
        return new ViettelSInvoiceClient.Account("PRODUCTION".equals(stored.environment()), stored.username(),
                password);
    }

    /** The seller's tax code, which SInvoice names the issuing company by on every call. */
    private String supplierTaxCode() {
        if (seller.taxCode() == null || seller.taxCode().isBlank()) {
            throw new ProviderUnavailableException("The seller's tax code is not configured (stockflow.seller.tax-code)");
        }
        return seller.taxCode().strip();
    }

    /** SInvoice's {@code transactionUuid} (10 to 36 characters) for a transaction id of the system: a name-based UUID. */
    static String transactionUuid(String transactionId) {
        return UUID.nameUUIDFromBytes(("stockflow-einvoice:" + transactionId).getBytes(StandardCharsets.UTF_8))
                .toString();
    }
}
