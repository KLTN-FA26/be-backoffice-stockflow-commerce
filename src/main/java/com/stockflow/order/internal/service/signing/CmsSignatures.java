package com.stockflow.order.internal.service.signing;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSAbsentContent;
import org.bouncycastle.cms.CMSAttributeTableGenerator;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.SignerInfoGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

/**
 * Builds the CMS a PAdES signature holds from a digest the caller already has: the shape a remote-signing provider's
 * API has too, so {@link ContractSigner} keeps it.
 *
 * <p>The signed attributes are the content type, the message digest (the caller's SHA-256, not one BouncyCastle
 * computes over the empty content it is given) and the ESS signing-certificate-v2 that PAdES baseline B-B requires,
 * which binds the signature to one certificate. No signing-time attribute: for PAdES the time is the signature
 * dictionary's {@code M}, and a time the signer's own clock wrote into the CMS would be a second, unrelated claim.</p>
 */
final class CmsSignatures {

    private static final Provider BC = new BouncyCastleProvider();

    private CmsSignatures() {
    }

    static byte[] detached(byte[] sha256, PrivateKey key, List<X509Certificate> chain) {
        try {
            X509Certificate signing = chain.get(0);
            String algorithm = "SHA256with" + ("EC".equals(key.getAlgorithm()) ? "ECDSA" : "RSA");
            ContentSigner contentSigner = new JcaContentSignerBuilder(algorithm).build(key);
            SignerInfoGenerator signerInfo = new JcaSignerInfoGeneratorBuilder(
                    new JcaDigestCalculatorProviderBuilder().setProvider(BC).build())
                    .setSignedAttributeGenerator(new GivenDigestAttributes(sha256, signing))
                    .build(contentSigner, signing);
            CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
            generator.addSignerInfoGenerator(signerInfo);
            generator.addCertificates(new JcaCertStore(chain));
            CMSSignedData signed = generator.generate(new CMSAbsentContent(), false);
            return signed.getEncoded("DER");
        } catch (GeneralSecurityException | OperatorCreationException | CMSException | IOException ex) {
            throw new IllegalStateException("The contract could not be signed", ex);
        }
    }

    /** The attributes above, with the digest fixed to the one handed in. */
    private record GivenDigestAttributes(byte[] sha256, X509Certificate signing) implements CMSAttributeTableGenerator {

        @Override
        public AttributeTable getAttributes(Map parameters) {
            try {
                byte[] certificateHash = MessageDigest.getInstance("SHA-256").digest(signing.getEncoded());
                ASN1EncodableVector attributes = new ASN1EncodableVector();
                attributes.add(new Attribute(CMSAttributes.contentType, new DERSet(PKCSObjectIdentifiers.data)));
                attributes.add(new Attribute(CMSAttributes.messageDigest, new DERSet(new DEROctetString(sha256))));
                attributes.add(new Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2,
                        new DERSet(new SigningCertificateV2(new ESSCertIDv2(certificateHash)))));
                return new AttributeTable(attributes);
            } catch (GeneralSecurityException impossible) {
                throw new IllegalStateException("SHA-256 or the certificate encoding is unavailable", impossible);
            }
        }
    }
}
