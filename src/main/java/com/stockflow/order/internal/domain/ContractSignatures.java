package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port: the evidence of who signed a contract and how, the verification code of a signed contract, and the PDF the
 * company's signer is handed to sign outside the system (contract e-signing plan of 2026-11-28).
 *
 * <p>A signature row is written once, in the transaction that changes the contract's state, and never changed: the
 * database refuses an UPDATE or a DELETE, because evidence that could be edited proves nothing. There is one row per
 * party and revision.</p>
 */
public interface ContractSignatures {

    enum Party { CUSTOMER, COMPANY }

    /** How a party signed: the customer by OTP, UPLOAD or STAFF_RECORDED, the company by SYSTEM, UPLOAD or STAFF_RECORDED. */
    enum Method { OTP, UPLOAD, SYSTEM, STAFF_RECORDED }

    /**
     * @param documentSha256 lower-case hex: the PDF that was sent for the customer, the signed file for the company
     * @param fileKey        the stored file the party sent, or the signed file the system made; null for a code
     * @param inspectionJson what inspecting the file found, as JSON; null when no file came in from outside
     * @param ip             the address the request came from; null when staff recorded it
     * @param certificatePageKey the certificate page as its own one-page PDF; null when there is none
     */
    record Signature(UUID contractId, int revision, Party party, Method method, String signerName, String signerTitle,
                     String email, UUID accountUserId, Instant signedAt, String ip, String userAgent,
                     String documentSha256, String fileKey, String inspectionJson, String certificateSubject,
                     String certificateIssuer, String certificateSerial, String certificatePageKey,
                     boolean testCertificate) {
    }

    /** What the public verification page may say of a signed contract: no amounts, no person. */
    record Verified(String contractNumber, int revision, String sellerName, String buyerName, String status,
                    Instant customerSignedAt, Instant companySignedAt, String documentSha256,
                    boolean testCertificate) {
    }

    /** The PDF to sign outside: where it is stored, its SHA-256 (lower-case hex), its length, and its certificate page alone. */
    record OutsideDocument(String key, String sha256, long length, String pageKey) {
    }

    /** Inserts the evidence; a second row for the same party and revision is refused by the database. */
    void record(Signature signature);

    /** The evidence of one revision, the customer's first. */
    List<Signature> of(UUID contractId, int revision);

    Optional<Signature> find(UUID contractId, int revision, Party party);

    /** Sets the contract's verification code, once. */
    void sealWithCode(UUID contractId, String code);

    boolean codeExists(String code);

    /** The code of a contract, or empty before it is signed. */
    Optional<String> codeOf(UUID contractId);

    Optional<Verified> findByCode(String code);

    /** The key of the signed file of the company's signature, to compare an upload with; empty for an unknown code. */
    Optional<String> companyFileKeyByCode(String code);

    Optional<OutsideDocument> outsideDocument(UUID contractId);

    /** Keeps the PDF to sign outside, once; false when one is kept already (the caller then uses that one). */
    boolean recordOutsideDocument(UUID contractId, OutsideDocument document);
}
