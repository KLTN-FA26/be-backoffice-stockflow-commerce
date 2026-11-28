package com.stockflow.order.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.common.storage.RecordRetention;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.order.internal.domain.ContractSignatures;
import com.stockflow.order.internal.domain.ContractSignatures.Method;
import com.stockflow.order.internal.domain.ContractSignatures.OutsideDocument;
import com.stockflow.order.internal.domain.ContractSignatures.Party;
import com.stockflow.order.internal.service.SalesContracts.ContractView;
import com.stockflow.order.internal.service.SalesContracts.SignatureView;
import com.stockflow.order.internal.service.SalesContracts.SigningFacts;
import com.stockflow.order.internal.service.signing.CertificatePage;
import com.stockflow.order.internal.service.signing.ContractSignOtps;
import com.stockflow.order.internal.service.signing.ContractSigner;
import com.stockflow.order.internal.service.signing.ContractSigningProperties;
import com.stockflow.order.internal.service.signing.PadesSigner;
import com.stockflow.order.internal.service.signing.SignedPdfInspector;
import com.stockflow.order.internal.service.signing.SignedPdfInspector.Inspection;
import com.stockflow.order.internal.service.signing.SignerIdentity;
import com.stockflow.order.internal.service.signing.VerificationCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ContractSigning}. Deliberately not transactional itself: each step is a few short transactions around the
 * slow work (see the interface), and a method-wide transaction would hold the order's row lock - taken first by
 * {@link SalesContracts#customerSigned} and {@link SalesContracts#companySigned} - while a PDF is drawn and signed.
 *
 * <p>The order of a step is always: read what it assumes (no lock), do the slow work, then in one transaction change the
 * contract through {@link SalesContracts} (which locks the order, asks again that the version is the one read and that
 * the contract is still signable), write the evidence, adopt the stored files. A refusal in that transaction rolls the
 * evidence back and deletes the files, so evidence and state stand or fall together.</p>
 */
@Service
class ContractSigningService implements ContractSigning {

    private static final Logger log = LoggerFactory.getLogger(ContractSigningService.class);

    private static final String REASON = "Ký hợp đồng";
    private static final String LOCATION = "Việt Nam";
    private static final int TEXT_MAX = 200;

    private final SalesContracts contracts;
    private final ContractSignatures signatures;
    private final ContractEvidence evidence;
    private final ContractSignOtps otps;
    private final ContractSigner signer;
    private final PadesSigner pades;
    private final CertificatePage certificatePage;
    private final ContractSigningProperties properties;
    private final CustomerService customers;
    private final IdentityService identity;
    private final FileTransfers files;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final OrderEventPublisher events;

    ContractSigningService(SalesContracts contracts, ContractSignatures signatures, ContractEvidence evidence,
                           ContractSignOtps otps, ContractSigner signer, PadesSigner pades,
                           CertificatePage certificatePage, ContractSigningProperties properties,
                           CustomerService customers, IdentityService identity, FileTransfers files,
                           TransactionTemplate tx, Clock clock, OrderEventPublisher events) {
        this.contracts = contracts;
        this.signatures = signatures;
        this.evidence = evidence;
        this.otps = otps;
        this.signer = signer;
        this.pades = pades;
        this.certificatePage = certificatePage;
        this.properties = properties;
        this.customers = customers;
        this.identity = identity;
        this.files = files;
        this.tx = tx;
        this.clock = clock;
        this.events = events;
    }

    // ---- the customer ----------------------------------------------------------------------------------------------

    @Override
    public ContractSignOtps.Issued requestOtp(CurrentUser user, UUID orderId, String ip) {
        CustomerSummary me = customerOf(user);
        SigningFacts facts = contracts.customerSigningFacts(me.customerId(), orderId, null, true);
        return otps.issue(facts.contractId(), facts.contractNumber(), facts.revision(), user.userId(), emailOf(me), ip);
    }

    @Override
    public ContractView customerSignWithOtp(CurrentUser user, UUID orderId, long version, String otp,
                                            String signerName, String signerTitle, boolean consent, Origin origin) {
        if (!consent) {
            throw invalid("consent", "The customer must agree to sign electronically");
        }
        String name = requiredText(signerName, "signerName");
        String title = requiredText(signerTitle, "signerTitle");
        CustomerSummary me = customerOf(user);
        // Asked before the code is used up: a stale version or a passed deadline must not cost the customer their code.
        SigningFacts facts = contracts.customerSigningFacts(me.customerId(), orderId, version, true);
        byte[] sent = evidence.read(facts.sentDocumentKey());
        otps.verify(facts.contractId(), facts.revision(), user.userId(), otp);
        Instant now = clock.instant();
        return tx.execute(status -> {
            ContractView signed = contracts.customerSigned(me.customerId(), user.userId(), orderId, version, now);
            signatures.record(new ContractSignatures.Signature(facts.contractId(), facts.revision(), Party.CUSTOMER,
                    Method.OTP, name, title, emailOf(me), user.userId(), now, origin.ip(),
                    truncated(origin.userAgent()), ContractEvidence.sha256Hex(sent), null, null, null, null, null, null,
                    false));
            return afterCustomerSigned(signed, facts, now);
        });
    }

    @Override
    public ContractView customerSignWithFile(CurrentUser user, UUID orderId, long version, String signerName,
                                             String signerTitle, String fileName, byte[] file, Origin origin) {
        String name = requiredText(signerName, "signerName");
        String title = requiredText(signerTitle, "signerTitle");
        CustomerSummary me = customerOf(user);
        SigningFacts facts = contracts.customerSigningFacts(me.customerId(), orderId, version, true);
        byte[] sent = evidence.read(facts.sentDocumentKey());
        ContractEvidence.Inspected inspected = evidence.inspect(file, sent);
        Inspection found = inspected.raw();
        // A scan - a PDF with no digital signature - is how a contract signed on paper comes back, and is accepted and
        // marked as such. What is refused: a file that cannot be read, and a digital signature that does not hold or is
        // over some other document: that is a signature, claimed, which is not the one it claims to be.
        if (found.kind() == SignedPdfInspector.Kind.UNREADABLE
                || (found.kind() == SignedPdfInspector.Kind.DIGITAL && !found.acceptedAsSentContract())) {
            throw rejected(found);
        }
        Instant now = clock.instant();
        try (PreparedFiles prepared = files.prepare(FileCategory.DOCUMENT, List.of(pdf(fileName, file)))) {
            String key = prepared.single().file().key();
            return tx.execute(status -> {
                ContractView signed = contracts.customerSigned(me.customerId(), user.userId(), orderId, version, now);
                signatures.record(new ContractSignatures.Signature(facts.contractId(), facts.revision(),
                        Party.CUSTOMER, Method.UPLOAD, name, title, addressOf(me), user.userId(), now, origin.ip(),
                        truncated(origin.userAgent()), ContractEvidence.sha256Hex(sent), key, inspected.json(), null,
                        null, null, null, false));
                prepared.adopt();
                files.retainAfterCommit(key, RecordRetention.SALES_CONTRACT);
                return afterCustomerSigned(signed, facts, now);
            });
        }
    }

    private ContractView afterCustomerSigned(ContractView signed, SigningFacts facts, Instant now) {
        events.publish(new com.stockflow.contracts.ContractCustomerSigned(facts.orderId(), facts.contractId(),
                facts.revision(), facts.contractNumber(), facts.orderNumber(), facts.customerId(), now));
        log.info("Contract {} signed by the customer (revision {})", facts.contractNumber(), facts.revision());
        SignatureView view = evidence.view(facts.contractId(), facts.revision(), false);
        return signed.withSignatures(view.customer(), SalesContractService.portalCompanySignature(view.company()),
                null);
    }

    @Override
    public DownloadLink customerCertificatePage(CurrentUser user, UUID orderId) {
        CustomerSummary me = customerOf(user);
        SigningFacts facts = contracts.customerSigningFacts(me.customerId(), orderId, null, false);
        if (!List.of("SIGNED", "COMPLETED", "LIQUIDATED").contains(facts.status())) {
            throw notFound(orderId);
        }
        String key = signatures.find(facts.contractId(), facts.revision(), Party.COMPANY)
                .map(ContractSignatures.Signature::certificatePageKey).orElseThrow(() -> notFound(orderId));
        return files.downloadLink(key, facts.contractNumber() + "-certificate.pdf");
    }

    // ---- the company -----------------------------------------------------------------------------------------------

    @Override
    public CompanyCertificate companyCertificate() {
        SignerIdentity who = signer.identity();
        return new CompanyCertificate(who.subject(), who.issuer(), who.serial().toString(16), who.notBefore(),
                who.notAfter(), who.testCertificate());
    }

    @Override
    public ContractView companySign(CurrentUser user, UUID orderId, long version, String signerName,
                                    String signerTitle, Origin origin) {
        SigningFacts facts = contracts.companySigningFacts(user, orderId, version);
        ContractSignatures.Signature customer = customerEvidence(facts);
        byte[] sent = sentDocument(facts, customer);
        Instant now = clock.instant();
        SignerIdentity who = signer.identity();
        String name = textOr(signerName, facts.sellerSignerName(), "signerName");
        String title = textOr(signerTitle, facts.sellerSignerTitle(), "signerTitle");
        String code = signatures.codeOf(facts.contractId()).orElseGet(evidence::newUnusedCode);
        byte[] page = certificatePage.render(pageContent(facts, customer, code, new CertificatePage.CompanySignature(
                name, title, accountName(user), now, who.subject(), who.issuer()), who.testCertificate()));
        byte[] signed = pades.sign(certificatePage.append(sent, page), signer, name, REASON, LOCATION, now);
        try (PreparedFiles prepared = files.prepare(FileCategory.DOCUMENT, List.of(
                pdf(facts.contractNumber() + "-signed.pdf", signed),
                pdf(facts.contractNumber() + "-certificate.pdf", page)))) {
            String signedKey = prepared.files().get(0).file().key();
            String pageKey = prepared.files().get(1).file().key();
            return tx.execute(status -> {
                ContractView done = contracts.companySigned(user, orderId, version, signedKey,
                        facts.contractNumber() + "-signed.pdf", now);
                sealCode(facts.contractId(), code);
                signatures.record(new ContractSignatures.Signature(facts.contractId(), facts.revision(),
                        Party.COMPANY, Method.SYSTEM, name, title, null, user.userId(), now, origin.ip(),
                        truncated(origin.userAgent()), prepared.files().get(0).sha256(), signedKey, null,
                        who.subject(), who.issuer(), who.serial().toString(16), pageKey, who.testCertificate()));
                prepared.adopt();
                files.retainAfterCommit(pageKey, RecordRetention.SALES_CONTRACT);
                return afterCompanySigned(done, facts, code);
            });
        }
    }

    @Override
    public DownloadLink companyDocumentToSign(CurrentUser user, UUID orderId) {
        SigningFacts facts = contracts.companySigningFacts(user, orderId, null);
        OutsideDocument document = outsideDocument(facts);
        return files.downloadLink(document.key(), facts.contractNumber() + "-to-sign.pdf");
    }

    @Override
    public ContractView companySignWithFile(CurrentUser user, UUID orderId, long version, String signerName,
                                            String signerTitle, String fileName, byte[] file, Origin origin) {
        SigningFacts facts = contracts.companySigningFacts(user, orderId, version);
        OutsideDocument expected = outsideDocument(facts);
        ContractEvidence.Inspected inspected = evidence.inspect(file, HexFormat.of().parseHex(expected.sha256()),
                expected.length());
        Inspection found = inspected.raw();
        // The company's own file is held to more than the customer's: it has to be a digital signature that holds, over
        // the whole of the very PDF the signer was handed. A scan, or a truncated file that reads as one, is refused.
        if (!found.acceptedAsSentContract()) {
            throw rejected(found);
        }
        Instant now = clock.instant();
        String name = textOr(signerName, facts.sellerSignerName(), "signerName");
        String title = textOr(signerTitle, facts.sellerSignerTitle(), "signerTitle");
        var first = found.signers().isEmpty() ? null : found.signers().get(0);
        try (PreparedFiles prepared = files.prepare(FileCategory.DOCUMENT, List.of(pdf(fileName, file)))) {
            String key = prepared.single().file().key();
            return tx.execute(status -> {
                ContractView done = contracts.companySigned(user, orderId, version, key,
                        facts.contractNumber() + "-signed.pdf", now);
                String code = evidenceCode(facts.contractId());
                signatures.record(new ContractSignatures.Signature(facts.contractId(), facts.revision(),
                        Party.COMPANY, Method.UPLOAD, name, title, null, user.userId(), now, origin.ip(),
                        truncated(origin.userAgent()), prepared.single().sha256(), key, inspected.json(),
                        first == null ? null : first.subject(), first == null ? null : first.issuer(),
                        first == null ? null : first.serial(), expected.pageKey(), false));
                prepared.adopt();
                return afterCompanySigned(done, facts, code);
            });
        }
    }

    private ContractView afterCompanySigned(ContractView done, SigningFacts facts, String code) {
        log.info("Contract {} signed by the company (revision {})", facts.contractNumber(), facts.revision());
        SignatureView view = evidence.view(facts.contractId(), facts.revision(), true);
        return done.withSignatures(view.customer(), view.company(), code);
    }

    /**
     * The PDF the company's signer signs outside - the sent contract with its certificate page added as an incremental
     * update, so it begins with the sent bytes - made once and kept, with the verification code its page prints (the
     * contract's code is fixed from here on), so that the file that comes back is held against exactly these bytes.
     * Its page is prepared before anyone signs, so it names no company signer, account or time.
     */
    private OutsideDocument outsideDocument(SigningFacts facts) {
        Optional<OutsideDocument> kept = signatures.outsideDocument(facts.contractId());
        if (kept.isPresent()) {
            return kept.get();
        }
        ContractSignatures.Signature customer = customerEvidence(facts);
        byte[] sent = sentDocument(facts, customer);
        String code = signatures.codeOf(facts.contractId()).orElseGet(evidence::newUnusedCode);
        // Nobody has signed for the company yet, and whoever will signs with a certificate of their own: the page
        // says it was prepared to be signed outside, names no signer, account, time or certificate, and does not
        // carry the system's trial-certificate notice.
        byte[] page = certificatePage.render(pageContent(facts, customer, code, null, false));
        byte[] appended = certificatePage.append(sent, page);
        try (PreparedFiles prepared = files.prepare(FileCategory.DOCUMENT, List.of(
                pdf(facts.contractNumber() + "-to-sign.pdf", appended),
                pdf(facts.contractNumber() + "-certificate.pdf", page)))) {
            String key = prepared.files().get(0).file().key();
            String pageKey = prepared.files().get(1).file().key();
            OutsideDocument made = new OutsideDocument(key, prepared.files().get(0).sha256(), appended.length,
                    pageKey);
            return tx.execute(status -> {
                if (!signatures.recordOutsideDocument(facts.contractId(), made)) {
                    // Another request made it first: theirs stands, this one's files are deleted unadopted.
                    return signatures.outsideDocument(facts.contractId()).orElseThrow();
                }
                sealCode(facts.contractId(), code);
                prepared.adopt();
                files.retainAfterCommit(key, RecordRetention.SALES_CONTRACT);
                files.retainAfterCommit(pageKey, RecordRetention.SALES_CONTRACT);
                return made;
            });
        }
    }

    // ---- reading ---------------------------------------------------------------------------------------------------

    @Override
    public SignatureView signatures(UUID orderId) {
        ContractView view = contracts.get(orderId);
        return evidence.view(view.id(), view.revision(), true);
    }

    @Override
    public Optional<Verification> verification(String code) {
        String normalized = VerificationCodes.normalize(code);
        if (normalized == null) {
            return Optional.empty();
        }
        return signatures.findByCode(normalized).map(v -> new Verification(v.contractNumber(), v.revision(),
                v.sellerName(), v.buyerName(), v.status(), v.customerSignedAt(), v.companySignedAt(),
                v.documentSha256(), v.testCertificate()));
    }

    @Override
    public Optional<Boolean> matches(String code, byte[] file) {
        String normalized = VerificationCodes.normalize(code);
        if (normalized == null) {
            return Optional.empty();
        }
        String sha = ContractEvidence.sha256Hex(file);
        return signatures.findByCode(normalized).map(v -> v.documentSha256().equals(sha));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private CertificatePage.Content pageContent(SigningFacts facts, ContractSignatures.Signature customer, String code,
                                                CertificatePage.CompanySignature company, boolean testCertificate) {
        return new CertificatePage.Content(facts.contractNumber(), facts.revision(), customer.documentSha256(),
                new CertificatePage.CustomerSignature(customer.signerName(), customer.signerTitle(), customer.email(),
                        customer.signedAt(), customer.ip(), switch (customer.method()) {
                            case OTP -> CertificatePage.Method.OTP;
                            case UPLOAD -> CertificatePage.Method.UPLOAD;
                            default -> CertificatePage.Method.STAFF_RECORDED;
                        }), company, code, properties.verificationUrl(code), testCertificate);
    }

    /** What the customer signed: without it there is nothing for the company to sign after. */
    private ContractSignatures.Signature customerEvidence(SigningFacts facts) {
        return signatures.find(facts.contractId(), facts.revision(), Party.CUSTOMER).orElseThrow(() ->
                new BusinessException(ErrorCode.CONTRACT_NOT_SIGNABLE, "Contract %s has no customer signature on record"
                        .formatted(facts.contractNumber())).localized("refusal.contract.notSignable",
                        facts.contractNumber(), facts.status()));
    }

    /** The PDF that was sent, checked to be the one the customer's signature was made over. */
    private byte[] sentDocument(SigningFacts facts, ContractSignatures.Signature customer) {
        byte[] sent = evidence.read(facts.sentDocumentKey());
        if (!ContractEvidence.sha256Hex(sent).equals(customer.documentSha256())) {
            // The stored PDF is not what the customer signed: never sign over it. A fault, not a refusal.
            throw new IllegalStateException("The stored PDF of contract " + facts.contractNumber()
                    + " is not the one the customer signed");
        }
        return sent;
    }

    private void sealCode(UUID contractId, String code) {
        Optional<String> kept = signatures.codeOf(contractId);
        if (kept.isEmpty()) {
            signatures.sealWithCode(contractId, code);
        } else if (!kept.get().equals(code)) {
            // The PDF just signed prints another code than the one the contract was given meanwhile: sign again.
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, "The contract changed while it was being signed");
        }
    }

    private String evidenceCode(UUID contractId) {
        return signatures.codeOf(contractId).orElseGet(() -> evidence.ensureCode(contractId));
    }

    private CustomerSummary customerOf(CurrentUser user) {
        return customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
    }

    private static String emailOf(CustomerSummary customer) {
        if (customer.email() == null || customer.email().isBlank()) {
            throw invalid("email", "The customer's account has no e-mail address to send the code to");
        }
        return customer.email();
    }

    /**
     * The customer's address, if the account has one, kept with a signature made by sending a file: the file is the
     * evidence there, nothing was mailed, so an account without an e-mail address signs that way too.
     */
    private static String addressOf(CustomerSummary customer) {
        return customer.email() == null || customer.email().isBlank() ? null : customer.email();
    }

    private String accountName(CurrentUser user) {
        Map<UUID, String> names = identity.displayNames(List.of(user.userId()));
        String name = names.get(user.userId());
        return name == null || name.isBlank() ? user.username() : name;
    }

    private static FileUpload pdf(String name, byte[] bytes) {
        String safe = name == null || name.isBlank() ? "contract.pdf" : name.length() > 255 ? name.substring(0, 255)
                : name;
        return new FileUpload(safe, "application/pdf", bytes.length, new ByteArrayInputStream(bytes));
    }

    private static String requiredText(String text, String field) {
        if (text == null || text.isBlank()) {
            throw invalid(field, "A value is required");
        }
        String trimmed = text.strip();
        if (trimmed.length() > TEXT_MAX) {
            throw invalid(field, "At most " + TEXT_MAX + " characters");
        }
        return trimmed;
    }

    private static String textOr(String text, String fallback, String field) {
        return text == null || text.isBlank() ? fallback : requiredText(text, field);
    }

    private static String truncated(String agent) {
        return agent == null ? null : agent.length() > 300 ? agent.substring(0, 300) : agent;
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, field + ": " + message);
    }

    private static BusinessException notFound(UUID orderId) {
        return new BusinessException(ErrorCode.NOT_FOUND, "No certificate page for order " + orderId);
    }

    /** The refusal of a signed file: {@code details.reason} says why, one of {@code SignedPdfInspector.Reasons}. */
    private static BusinessException rejected(Inspection found) {
        String reason = found.reason() != null ? found.reason()
                : found.kind() == SignedPdfInspector.Kind.SCAN ? SignedPdfInspector.Reasons.NO_DIGITAL_SIGNATURE
                : SignedPdfInspector.Reasons.NOT_SENT_DOCUMENT;
        return new BusinessException(ErrorCode.CONTRACT_SIGNED_FILE_REJECTED,
                ErrorCode.CONTRACT_SIGNED_FILE_REJECTED.defaultMessage(), List.of(), Map.of("reason", reason));
    }
}
