package com.stockflow.order.internal.service.signing;

import java.math.BigInteger;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;

/**
 * Who a {@link ContractSigner} signs as: the certificate's facts, for the signature view and the certificate page.
 *
 * @param testCertificate true while the certificate is the system's own self-issued one, which no certification
 *                        authority vouches for: every document that shows the signer says so
 * @param chain           the signing certificate first, then its issuers (the self-issued one is its own and only)
 */
public record SignerIdentity(String subject, String issuer, BigInteger serial, Instant notBefore, Instant notAfter,
                             boolean testCertificate, List<X509Certificate> chain) {

    public SignerIdentity {
        chain = List.copyOf(chain);
    }

    public X509Certificate certificate() {
        return chain.get(0);
    }
}
