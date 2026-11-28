package com.stockflow.order.internal.service.signing;

/**
 * Signs a contract PDF's digest on behalf of the company.
 *
 * <p>A port with the shape remote-signing providers (VNPT, Viettel, FPT, DocuSign) all share - a hash goes in, a
 * signature comes out - so that the self-issued test certificate ({@link TestCertificateSigner}) can be replaced by a
 * provider's without the contract rules or {@link PadesSigner} changing (decision QĐ-6 of the plan). The document never
 * leaves the server: only its SHA-256 does.</p>
 */
public interface ContractSigner {

    /** The certificate the signatures are made with. */
    SignerIdentity identity();

    /**
     * @param sha256 the SHA-256 of the bytes being signed (for a PDF, the two ranges its ByteRange names)
     * @return a CMS (PKCS#7) {@code SignedData}, DER encoded, detached: signed attributes carrying {@code sha256} as
     *         the message digest and the signing certificate (ESS signing-certificate-v2), the certificate chain
     *         embedded, no content
     */
    byte[] signDigest(byte[] sha256);
}
