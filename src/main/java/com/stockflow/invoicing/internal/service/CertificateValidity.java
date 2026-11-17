package com.stockflow.invoicing.internal.service;

import java.security.cert.X509Certificate;
import java.time.Instant;

/**
 * When an X.509 certificate is in force, as {@link Instant}s. The JDK's certificate API answers with
 * {@code java.util.Date} and has no {@code java.time} alternative; this is the one place that touches it, and it turns
 * it into an instant on the spot (the exception {@code ArchitectureTest.onlyJavaTimeIsUsed} names).
 */
record CertificateValidity(Instant from, Instant to) {

    static CertificateValidity of(X509Certificate certificate) {
        return new CertificateValidity(certificate.getNotBefore().toInstant(), certificate.getNotAfter().toInstant());
    }

    boolean covers(Instant moment) {
        return !moment.isBefore(from) && !moment.isAfter(to);
    }
}
