package com.stockflow.order.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.storage.Checksums;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.StorageException;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.order.internal.domain.ContractSignatures;
import com.stockflow.order.internal.domain.ContractSignatures.Method;
import com.stockflow.order.internal.domain.ContractSignatures.Party;
import com.stockflow.order.internal.domain.SalesContract;
import com.stockflow.order.internal.service.SalesContracts.CompanySignatureView;
import com.stockflow.order.internal.service.SalesContracts.CustomerSignatureView;
import com.stockflow.order.internal.service.SalesContracts.InspectedSigner;
import com.stockflow.order.internal.service.SalesContracts.InspectionView;
import com.stockflow.order.internal.service.SalesContracts.SignatureView;
import com.stockflow.order.internal.service.signing.SignedPdfInspector;
import com.stockflow.order.internal.service.signing.SignedPdfInspector.Inspection;
import com.stockflow.order.internal.service.signing.VerificationCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What the signing steps share, so that neither {@link SalesContractService} (which records a contract signed by staff,
 * and draws every view) nor {@link ContractSigningService} (the customer's and the company's own signing) owns it twice:
 * reading a stored PDF, inspecting a signed file and keeping what was found as JSON, the evidence rows read back as the
 * signature view, and the verification code.
 *
 * <p>The signature view is built from the rows and never from the contract's own columns, so what a contract shows of
 * who signed is exactly what was recorded - once, in the transaction that changed its state - and cannot drift.</p>
 */
@Component
class ContractEvidence {

    private static final Logger log = LoggerFactory.getLogger(ContractEvidence.class);

    /** A code that collides with another is made again; 59 bits make a second try a rarity, so this is a bound, not a loop. */
    private static final int CODE_TRIES = 5;

    private final ContractSignatures signatures;
    private final SignedPdfInspector inspector;
    private final FileStorage storage;
    private final ObjectMapper mapper;
    private final IdentityService identity;

    ContractEvidence(ContractSignatures signatures, SignedPdfInspector inspector, FileStorage storage,
                     ObjectMapper mapper, IdentityService identity) {
        this.signatures = signatures;
        this.inspector = inspector;
        this.storage = storage;
        this.mapper = mapper;
        this.identity = identity;
    }

    /** A signer's name and title as the contract prints them. */
    record Signer(String name, String title) { }

    /**
     * The seller's signer: the one named on the contract, else its legal representative. The seller's authorised signer
     * is a person the contract names, so the certificate page prints the same one.
     */
    static Signer sellerSigner(SalesContract contract) {
        var named = contract.agreement().seller();
        return new Signer(named.name() != null && !named.name().isBlank() ? named.name()
                : contract.seller().representative(),
                named.title() != null && !named.title().isBlank() ? named.title()
                        : contract.seller().representativeTitle());
    }

    static Signer buyerSigner(SalesContract contract) {
        var named = contract.agreement().buyer();
        return new Signer(named.name() != null && !named.name().isBlank() ? named.name()
                : contract.buyer().representative(),
                named.title() != null && !named.title().isBlank() ? named.title()
                        : contract.buyer().representativeTitle());
    }

    /** What inspecting a file found: the inspector's own answer, the view of it, and the JSON that is stored. */
    record Inspected(Inspection raw, InspectionView view, String json) { }

    /**
     * Inspects a signed file against the sent document it must be the contract of. Every file that comes in - the
     * customer's, the company's signed outside, the one staff record - is held to the strict comparison
     * ({@link SignedPdfInspector.Additions#NONE}): the pages of the expected document, unchanged, and nothing added but
     * signatures. (The company's own file adds the certificate page, but against the PDF it was handed - which already
     * has that page - it adds nothing either.)
     *
     * @param sentDocument the PDF that was sent (or, for the company's upload, the PDF it was handed to sign); null to
     *                     inspect without comparing
     */
    Inspected inspect(byte[] file, byte[] sentDocument) {
        return sentDocument == null ? inspected(inspector.inspect(file))
                : inspect(file, sha256(sentDocument), sentDocument.length);
    }

    /** {@link #inspect(byte[], byte[])} against a document known by its SHA-256 and length (the PDF a signer was handed). */
    Inspected inspect(byte[] file, byte[] expectedSha256, long expectedLength) {
        return inspected(inspector.inspect(file, expectedSha256, expectedLength,
                SignedPdfInspector.Additions.NONE));
    }

    private Inspected inspected(Inspection found) {
        InspectionView view = new InspectionView(found.kind().name(), found.valid(), found.coversWholeDocument(),
                found.matchesSentDocument(), found.chainChecked(), found.signers().stream()
                .map(s -> new InspectedSigner(s.subject(), s.issuer(), s.serial(), s.notBefore(), s.notAfter(),
                        s.signedAt())).toList(), found.reason());
        try {
            return new Inspected(found, view, mapper.writeValueAsString(view));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("An inspection could not be written as JSON", ex);
        }
    }

    /** The bytes of a stored file. */
    byte[] read(String key) {
        try (InputStream in = storage.download(key)) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new StorageException("Could not read " + key, ex);
        }
    }

    static byte[] sha256(byte[] bytes) {
        return Checksums.sha256().digest(bytes);
    }

    static String sha256Hex(byte[] bytes) {
        return Checksums.sha256Hex(bytes);
    }

    // ---- the signature view ----------------------------------------------------------------------------------------

    /**
     * The evidence of one revision as the plan's "Signature view".
     *
     * @param withIp whether the customer's address is in it: the back office's, never the portal's
     */
    SignatureView view(UUID contractId, int revision, boolean withIp) {
        List<ContractSignatures.Signature> rows = signatures.of(contractId, revision);
        String code = signatures.codeOf(contractId).orElse(null);
        if (rows.isEmpty()) {
            return new SignatureView(null, null, null, code);
        }
        ContractSignatures.Signature customer = rows.stream().filter(s -> s.party() == Party.CUSTOMER).findFirst()
                .orElse(null);
        ContractSignatures.Signature company = rows.stream().filter(s -> s.party() == Party.COMPANY).findFirst()
                .orElse(null);
        Map<UUID, String> names = company == null ? Map.of() : identity.displayNames(List.of(company.accountUserId()));
        return new SignatureView(customer == null ? null : customerView(customer, withIp),
                company == null ? null : companyView(company, names.get(company.accountUserId())),
                (company != null ? company : customer).documentSha256(), code);
    }

    private CustomerSignatureView customerView(ContractSignatures.Signature s, boolean withIp) {
        return new CustomerSignatureView(s.method().name(), s.signerName(), s.signerTitle(), s.email(), s.signedAt(),
                withIp ? s.ip() : null, s.method() == Method.OTP, inspectionOf(s));
    }

    private CompanySignatureView companyView(ContractSignatures.Signature s, String byName) {
        return new CompanySignatureView(s.method().name(), s.signerName(), s.signerTitle(), s.signedAt(), byName,
                s.certificateSubject(), s.certificateIssuer(), s.testCertificate(), inspectionOf(s));
    }

    private InspectionView inspectionOf(ContractSignatures.Signature s) {
        if (s.inspectionJson() == null) {
            return null;
        }
        try {
            return mapper.readValue(s.inspectionJson(), InspectionView.class);
        } catch (JsonProcessingException ex) {
            // A row this code wrote and cannot read back: the view shows the signature without it, the log says why.
            log.error("The stored inspection of a signature of contract {} is unreadable", s.contractId(), ex);
            return null;
        }
    }

    // ---- the verification code -------------------------------------------------------------------------------------

    /** A code no contract has yet. */
    String newUnusedCode() {
        for (int i = 0; i < CODE_TRIES; i++) {
            String code = VerificationCodes.newCode();
            if (!signatures.codeExists(code)) {
                return code;
            }
        }
        throw new IllegalStateException("No unused verification code in " + CODE_TRIES + " tries");
    }

    /** The contract's code, made and kept now when it has none (a contract signed on paper has one too). */
    String ensureCode(UUID contractId) {
        Optional<String> kept = signatures.codeOf(contractId);
        if (kept.isPresent()) {
            return kept.get();
        }
        String code = newUnusedCode();
        signatures.sealWithCode(contractId, code);
        return code;
    }

    // ---- staff recording a signed paper ----------------------------------------------------------------------------

    /**
     * Staff recorded the contract signed by both sides with a file: the evidence of both, method STAFF_RECORDED, and the
     * file inspected, never refused for being a scan. Written in the transaction that makes the contract SIGNED. Only a
     * SENT contract is recorded so ({@code SalesContract#requireSignable}); the customer's row is still written only
     * when there is none, so a row kept from elsewhere is never duplicated against the unique key.
     *
     * @param sentSha256 the SHA-256 of the PDF that was sent
     */
    void recordStaffSigned(SalesContract contract, UUID staff, String fileKey, String fileSha256, Inspected inspected,
                           Instant at, String sentSha256) {
        if (signatures.find(contract.id(), contract.revision(), Party.CUSTOMER).isEmpty()) {
            Signer buyer = buyerSigner(contract);
            signatures.record(new ContractSignatures.Signature(contract.id(), contract.revision(), Party.CUSTOMER,
                    Method.STAFF_RECORDED, buyer.name(), buyer.title(), null, staff, at, null, null, sentSha256,
                    fileKey, inspected.json(), null, null, null, null, false));
        }
        Signer seller = sellerSigner(contract);
        signatures.record(new ContractSignatures.Signature(contract.id(), contract.revision(), Party.COMPANY,
                Method.STAFF_RECORDED, seller.name(), seller.title(), null, staff, at, null, null, fileSha256, fileKey,
                inspected.json(), null, null, null, null, false));
        ensureCode(contract.id());
    }
}
