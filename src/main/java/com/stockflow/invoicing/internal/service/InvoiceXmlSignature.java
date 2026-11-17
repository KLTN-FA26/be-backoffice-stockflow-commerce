package com.stockflow.invoicing.internal.service;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.xml.XMLConstants;
import javax.xml.crypto.KeySelector;
import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;

/**
 * Checks the seller's digital signature on an e-invoice's XML (TT 78 / Tổng cục Thuế's format: the signatures are in
 * {@code HDon / DSCKS}, the seller's under {@code NBan}, signing {@code DLHDon} by its {@code Id}) with the JDK's XML
 * Digital Signature API, and says who signed and when.
 *
 * <p>What "valid" means here: the content is what was signed (every reference's digest matches), and the signature
 * value is the one the certificate inside the signature makes. It does <b>not</b> say the certificate is one a
 * licensed Vietnamese CA issued and has not revoked - that needs the CAs' roots and revocation lists, which the
 * application does not hold - so a check answers {@code chainChecked = false}, and the tax authority's code on the
 * invoice is what says the tax authority accepted it.</p>
 *
 * <p>The XML may come from a person (the check of an uploaded file), so it is parsed with no DTD and no external
 * entity of any kind, and the signature is validated in the JDK's secure mode (no XSLT, bounded references, no weak
 * algorithms).</p>
 */
public final class InvoiceXmlSignature {

    /** The largest XML checked: an invoice of hundreds of lines is a few hundred kilobytes. */
    static final int MAX_BYTES = 5 * 1024 * 1024;

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String SELLER = "NBan";
    private static final String BUYER = "NMua";
    /** The attribute RFC 4519 calls UID, where Vietnamese CAs put the holder's tax code ("MST:0312345678"). */
    private static final String TAX_CODE_PREFIX = "MST:";

    private InvoiceXmlSignature() {
    }

    /** What a check found. */
    public enum Status {
        /** The content is what was signed, and the signature is the certificate's. */
        VALID,
        /** The signature is the certificate's, but the content changed after it was signed. */
        CONTENT_CHANGED,
        /** The signature does not match the certificate it carries. */
        SIGNATURE_INVALID,
        /** The XML has no seller's signature. */
        NOT_SIGNED,
        /** Not an XML document, or not one that can be checked. */
        UNREADABLE
    }

    /**
     * @param signer                    the certificate's common name; null unless the signature carries one
     * @param signerTaxCode             the tax code in the certificate ({@code UID=MST:...}); null when it has none
     * @param issuer                    the common name of the CA that issued the certificate
     * @param signingTime               when the signer's software says it signed; null when the XML does not say
     * @param certificateValidAtSigning whether {@code signingTime} is inside the certificate's validity; null without a
     *                                  signing time
     * @param buyerSigned               whether the buyer signed too (not required between businesses)
     * @param chainChecked              always false: see the class comment
     */
    public record Check(Status status, String signer, String signerTaxCode, String signerOrganization, String issuer,
                 Instant signingTime, Instant certificateValidFrom, Instant certificateValidTo,
                 Boolean certificateValidAtSigning, boolean buyerSigned, boolean chainChecked) {

        static Check of(Status status) {
            return new Check(status, null, null, null, null, null, null, null, null, false, false);
        }

        public boolean valid() {
            return status == Status.VALID;
        }
    }

    static Check check(byte[] xml) {
        if (xml == null || xml.length == 0 || xml.length > MAX_BYTES) {
            return Check.of(Status.UNREADABLE);
        }
        Document document;
        try {
            document = parse(xml);
        } catch (Exception notXml) {
            return Check.of(Status.UNREADABLE);
        }
        registerIds(document.getDocumentElement());
        Element signature = signatureUnder(document, SELLER);
        if (signature == null) {
            return Check.of(Status.NOT_SIGNED);
        }
        boolean buyerSigned = signatureUnder(document, BUYER) != null;
        X509Certificate certificate = certificate(signature);
        if (certificate == null) {
            return new Check(Status.SIGNATURE_INVALID, null, null, null, null, signingTime(signature), null, null,
                    null, buyerSigned, false);
        }
        Status status = validate(signature, certificate);
        Instant signedAt = signingTime(signature);
        CertificateValidity validity = CertificateValidity.of(certificate);
        Instant from = validity.from();
        Instant to = validity.to();
        Boolean inValidity = signedAt == null ? null : validity.covers(signedAt);
        return new Check(status, attribute(certificate.getSubjectX500Principal().getName(), "CN"),
                taxCode(certificate.getSubjectX500Principal().getName()),
                attribute(certificate.getSubjectX500Principal().getName(), "O"),
                attribute(certificate.getIssuerX500Principal().getName(), "CN"), signedAt, from, to, inValidity,
                buyerSigned, false);
    }

    private static Status validate(Element signature, X509Certificate certificate) {
        try {
            DOMValidateContext context = new DOMValidateContext(
                    KeySelector.singletonKeySelector(certificate.getPublicKey()), signature);
            context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
            XMLSignature parsed = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);
            if (parsed.validate(context)) {
                return Status.VALID;
            }
            if (!parsed.getSignatureValue().validate(context)) {
                return Status.SIGNATURE_INVALID;
            }
            for (Object reference : parsed.getSignedInfo().getReferences()) {
                if (!((Reference) reference).validate(context)) {
                    return Status.CONTENT_CHANGED;
                }
            }
            return Status.SIGNATURE_INVALID;
        } catch (MarshalException | XMLSignatureException | RuntimeException cannotCheck) {
            return Status.SIGNATURE_INVALID;
        }
    }

    /** No DTD, no external entity, no XInclude: the document may be anyone's. */
    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder;
        try {
            builder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException unsupported) {
            throw new IllegalStateException(unsupported);
        }
        builder.setErrorHandler(null);
        return builder.parse(new ByteArrayInputStream(xml));
    }

    /** The format names what is signed by an attribute called {@code Id}, which no schema declares as an ID here. */
    private static void registerIds(Element element) {
        if (element.hasAttribute("Id")) {
            element.setIdAttribute("Id", true);
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                registerIds(child);
            }
        }
    }

    /** The first signature directly inside {@code DSCKS / <party>}. */
    private static Element signatureUnder(Document document, String party) {
        NodeList signatures = document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        for (int i = 0; i < signatures.getLength(); i++) {
            Node parent = signatures.item(i).getParentNode();
            if (parent != null && party.equals(localName(parent))) {
                return (Element) signatures.item(i);
            }
        }
        return null;
    }

    private static X509Certificate certificate(Element signature) {
        NodeList certificates = signature.getElementsByTagNameNS(XMLSignature.XMLNS, "X509Certificate");
        if (certificates.getLength() == 0) {
            return null;
        }
        try {
            byte[] der = Base64.getMimeDecoder().decode(certificates.item(0).getTextContent().strip());
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (CertificateException | IllegalArgumentException | ClassCastException notACertificate) {
            return null;
        }
    }

    /** {@code SigningTime} in the signature's properties: with an offset, or Viet Nam's local time without one. */
    private static Instant signingTime(Element signature) {
        NodeList all = signature.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            if ("SigningTime".equals(localName(all.item(i)))) {
                String value = all.item(i).getTextContent().strip();
                try {
                    return OffsetDateTime.parse(value).toInstant();
                } catch (DateTimeParseException noOffset) {
                    try {
                        return LocalDateTime.parse(value).atZone(ZONE).toInstant();
                    } catch (DateTimeParseException unreadable) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static String localName(Node node) {
        String name = node.getLocalName() != null ? node.getLocalName() : node.getNodeName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static String attribute(String distinguishedName, String type) {
        for (Rdn rdn : rdns(distinguishedName)) {
            if (type.equalsIgnoreCase(rdn.getType())) {
                return rdn.getValue().toString();
            }
        }
        return null;
    }

    private static String taxCode(String distinguishedName) {
        for (Rdn rdn : rdns(distinguishedName)) {
            String value = rdn.getValue().toString();
            if (("UID".equalsIgnoreCase(rdn.getType()) || "0.9.2342.19200300.100.1.1".equals(rdn.getType()))
                    && value.startsWith(TAX_CODE_PREFIX)) {
                return value.substring(TAX_CODE_PREFIX.length()).strip();
            }
        }
        return null;
    }

    private static List<Rdn> rdns(String distinguishedName) {
        try {
            return new LdapName(distinguishedName).getRdns();
        } catch (InvalidNameException unreadable) {
            return List.of();
        }
    }
}
