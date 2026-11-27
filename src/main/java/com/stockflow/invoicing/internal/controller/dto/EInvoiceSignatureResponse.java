package com.stockflow.invoicing.internal.controller.dto;

import java.time.Instant;

/**
 * The seller's digital signature on an e-invoice's XML, as checked by the system.
 *
 * @param status                    VALID (the content is what was signed and the signature is the certificate's);
 *                                  CONTENT_CHANGED (signed, then changed); SIGNATURE_INVALID; NOT_SIGNED (no seller's
 *                                  signature - the built-in sandbox provider signs nothing); UNREADABLE (not an XML);
 *                                  SHA1_UNCHECKED (signed with SHA-1, which this system's JDK does not check: neither
 *                                  valid nor invalid - the tax authority's code is what says it was accepted; the buyer
 *                                  is still sent the invoice, WF-05 BE-56)
 * @param valid                     {@code status == VALID}
 * @param signer                    the certificate's holder (its common name)
 * @param signerTaxCode             the tax code in the certificate
 * @param issuer                    the certificate authority that issued the certificate
 * @param signingTime               when the seller signed
 * @param certificateValidAtSigning whether the certificate was in force when it signed; null without a signing time
 * @param buyerSigned               whether the buyer signed too (not required between businesses)
 * @param chainChecked              false: the certificate is not checked against the licensed CAs' roots and
 *                                  revocation lists; the tax authority's code says the tax authority accepted it
 */
public record EInvoiceSignatureResponse(String status, boolean valid, String signer, String signerTaxCode,
                                        String signerOrganization, String issuer, Instant signingTime,
                                        Instant certificateValidFrom, Instant certificateValidTo,
                                        Boolean certificateValidAtSigning, boolean buyerSigned,
                                        boolean chainChecked) {
}
