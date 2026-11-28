package com.stockflow.order.internal.service.signing;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.SignerInformation;

import java.math.BigInteger;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Instant;

/**
 * Where the signing code meets {@code java.util.Date}: the JDK's X.509 API and BouncyCastle's CMS and certificate
 * builders speak nothing else. Every other class here keeps to {@link Instant}; this one is the single exemption of
 * {@code ArchitectureTest.onlyJavaTimeIsUsed} for this package, as {@code CertificateValidity} is for the e-invoice's.
 */
final class CertificateDates {

    private CertificateDates() {
    }

    static Instant notBefore(X509Certificate certificate) {
        return certificate.getNotBefore().toInstant();
    }

    static Instant notAfter(X509Certificate certificate) {
        return certificate.getNotAfter().toInstant();
    }

    static Instant notBefore(X509CertificateHolder certificate) {
        return certificate.getNotBefore().toInstant();
    }

    static Instant notAfter(X509CertificateHolder certificate) {
        return certificate.getNotAfter().toInstant();
    }

    /** A builder for a certificate valid between the two instants. */
    static JcaX509v3CertificateBuilder builder(X500Name issuer, BigInteger serial, Instant from, Instant to,
                                               X500Name subject, PublicKey key) {
        return new JcaX509v3CertificateBuilder(issuer, serial, java.util.Date.from(from), java.util.Date.from(to),
                subject, key);
    }

    /** The signing-time attribute the signer put in its CMS, or null when it has none. */
    static Instant signingTime(SignerInformation signer) {
        var table = signer.getSignedAttributes();
        if (table == null) {
            return null;
        }
        var attribute = table.get(org.bouncycastle.asn1.cms.CMSAttributes.signingTime);
        if (attribute == null || attribute.getAttrValues().size() != 1) {
            return null;
        }
        try {
            return org.bouncycastle.asn1.cms.Time.getInstance(attribute.getAttrValues().getObjectAt(0)).getDate()
                    .toInstant();
        } catch (RuntimeException notATime) {
            return null;
        }
    }
}
