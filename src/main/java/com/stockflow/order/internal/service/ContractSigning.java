package com.stockflow.order.internal.service;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.storage.DownloadLink;
import com.stockflow.order.internal.service.SalesContracts.ContractView;
import com.stockflow.order.internal.service.SalesContracts.SignatureView;
import com.stockflow.order.internal.service.signing.ContractSignOtps;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Signing a contract with e-signatures (contract e-signing plan of 2026-11-28): the customer signs on the portal, with a
 * one-time code mailed to their account or by sending back a file they signed; then the company's authorised signer signs
 * in the back office, with the system's certificate or by sending back a file signed outside. The contract is in force
 * when the company signs (QĐ-1). Each step keeps its evidence - who, how, when, from where, over which bytes - in the
 * transaction that changes the contract's state.
 *
 * <p>The work that takes time (drawing the certificate page, signing, reading a stored PDF, inspecting an upload) is done
 * before any row lock is taken, and what it assumed - the contract's version, that it is still signable - is asked again
 * under the lock by {@link SalesContracts}.</p>
 */
public interface ContractSigning {

    /** Where a request came from, for the evidence. */
    record Origin(String ip, String userAgent) { }

    /** The signing certificate the company signs with, for the dialog that asks the signer to confirm. */
    record CompanyCertificate(String subject, String issuer, String serial, Instant notBefore, Instant notAfter,
                              boolean testCertificate) { }

    /** What the public verification page shows: no amounts and no person, only the company names and the facts. */
    record Verification(String contractNumber, int revision, String sellerName, String buyerName, String status,
                        Instant customerSignedAt, Instant companySignedAt, String documentSha256,
                        boolean testCertificate) { }

    // ---- the customer ----------------------------------------------------------------------------------------------

    /**
     * Mails a one-time code to the signed-in customer's own e-mail. 409 {@code CONTRACT_NOT_SIGNABLE} /
     * {@code CONTRACT_SIGN_DEADLINE_PASSED}; 429 {@code CONTRACT_OTP_TOO_MANY}.
     */
    ContractSignOtps.Issued requestOtp(CurrentUser user, UUID orderId, String ip);

    /**
     * Signs with the code: SENT → CUSTOMER_SIGNED. 422 {@code CONTRACT_OTP_INVALID} / {@code CONTRACT_OTP_EXPIRED}.
     *
     * @return the contract as the portal shows it
     */
    ContractView customerSignWithOtp(CurrentUser user, UUID orderId, long version, String otp, String signerName,
                                     String signerTitle, boolean consent, Origin origin);

    /**
     * Signs with a file the customer signed: a scan is accepted and marked as having no digital signature; a digital
     * signature must hold and be over exactly the contract that was sent. 422 {@code CONTRACT_SIGNED_FILE_REJECTED}.
     */
    ContractView customerSignWithFile(CurrentUser user, UUID orderId, long version, String signerName,
                                      String signerTitle, String fileName, byte[] file, Origin origin);

    /** A link to the one-page certificate of a signed contract of the customer's own. */
    DownloadLink customerCertificatePage(CurrentUser user, UUID orderId);

    // ---- the company -----------------------------------------------------------------------------------------------

    CompanyCertificate companyCertificate();

    /**
     * Signs for the company with the system's certificate: CUSTOMER_SIGNED → SIGNED, the signed PDF kept. 409
     * {@code CONTRACT_SIGNER_IS_AUTHOR} for whoever drew the contract up or sent it.
     *
     * @param signerName  who signs for the company; null for the contract's own seller signer
     * @param signerTitle likewise
     */
    ContractView companySign(CurrentUser user, UUID orderId, long version, String signerName, String signerTitle,
                             Origin origin);

    /**
     * A link to the PDF to sign outside the system, made once: the sent contract with its certificate page added as an
     * incremental update (so it begins with the sent bytes), the page prepared before anyone signs - no company signer,
     * account or time on it, the verification code fixed from here on.
     */
    DownloadLink companyDocumentToSign(CurrentUser user, UUID orderId);

    /**
     * Signs for the company with the file that came back from signing {@link #companyDocumentToSign} outside: a digital
     * signature that holds, over the whole of exactly that PDF. 422 {@code CONTRACT_SIGNED_FILE_REJECTED}.
     */
    ContractView companySignWithFile(CurrentUser user, UUID orderId, long version, String signerName,
                                     String signerTitle, String fileName, byte[] file, Origin origin);

    // ---- reading ---------------------------------------------------------------------------------------------------

    /** The evidence of the contract's current revision, as the back office reads it (the customer's address included). */
    SignatureView signatures(UUID orderId);

    /** Public: what is known of the contract behind a verification code; empty when the code is not one. */
    Optional<Verification> verification(String code);

    /** Public: whether {@code file} is, byte for byte, the signed contract behind the code; empty when it is not one. */
    Optional<Boolean> matches(String code, byte[] file);
}
