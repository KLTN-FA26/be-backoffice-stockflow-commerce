package com.stockflow.order.internal.service.signing;

import com.stockflow.common.config.SellerProperties;
import com.stockflow.common.security.SecretCipher;
import com.stockflow.common.security.SecretCipherProperties;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * The company's signer for this project: a certificate nobody vouches for. Either a PKCS#12 the deployment supplies
 * ({@code CONTRACT_SIGNING_KEYSTORE}, base64, with its password), or - with none - an RSA 3072 key and a certificate
 * generated once and kept in {@code ordering.contract_signing_key}, the private key sealed with a key derived from
 * {@code CONTRACT_SIGNING_SECRET}, so every instance and every restart signs as the same certificate.
 *
 * <p>The generated certificate is self-issued and says so in its name ({@code OU=STOCKFLOW TEST CA ...}): a reader of
 * a signed contract sees on the certificate itself, not only on our pages, that no certification authority stands
 * behind it. A configured PKCS#12 counts as a test certificate too while it is self-issued (subject equals issuer),
 * since a certificate nobody else issued is just as unvouched for. It is generated at the first use, not at startup:
 * generating an RSA 3072 key takes a second or more, and most application starts (and every test context) never sign.
 * A keystore that cannot be read fails at startup instead - a deployment that configured one wants to know at once.</p>
 *
 * <p>Nothing here logs a key, a password or a secret; the certificate's subject, serial and expiry are logged once.</p>
 */
@Component
public class TestCertificateSigner implements ContractSigner {

    private static final Logger log = LoggerFactory.getLogger(TestCertificateSigner.class);
    private static final String PURPOSE = "TEST_CERT";
    private static final String SEAL_CONTEXT = "contract_signing_key:" + PURPOSE;
    private static final int KEY_BITS = 3072;
    /** Long enough that no one has to remember it: a test certificate that lapses mid-demo is a worse failure. */
    private static final Duration VALIDITY = Duration.ofDays(3650);
    private static final int COMMON_NAME_MAX = 64;

    /** The key and the certificates it signs as. */
    private record Material(PrivateKey key, List<X509Certificate> chain, boolean test) {
        SignerIdentity identity() {
            X509Certificate leaf = chain.get(0);
            return new SignerIdentity(leaf.getSubjectX500Principal().getName(X500Principal.RFC2253),
                    leaf.getIssuerX500Principal().getName(X500Principal.RFC2253), leaf.getSerialNumber(),
                    CertificateDates.notBefore(leaf), CertificateDates.notAfter(leaf), test, chain);
        }
    }

    private final ContractSigningProperties properties;
    private final SellerProperties seller;
    private final SigningSecrets secrets;
    private final ContractSigningKeys store;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private volatile Material material;
    private volatile SignerIdentity identity;

    TestCertificateSigner(ContractSigningProperties properties, SellerProperties seller, SigningSecrets secrets,
                          ContractSigningKeys store, Clock clock) {
        this.properties = properties;
        this.seller = seller;
        this.secrets = secrets;
        this.store = store;
        this.clock = clock;
        if (properties.hasKeystore()) {
            adopt(fromKeystore(properties));
        }
    }

    @Override
    public SignerIdentity identity() {
        material();
        return identity;
    }

    @Override
    public byte[] signDigest(byte[] sha256) {
        if (sha256 == null || sha256.length != 32) {
            throw new IllegalArgumentException("A SHA-256 digest of 32 bytes is required");
        }
        Material current = material();
        return CmsSignatures.detached(sha256, current.key(), current.chain());
    }

    private Material material() {
        Material current = material;
        if (current == null) {
            synchronized (this) {
                current = material;
                if (current == null) {
                    adopt(fromDatabase());
                    current = material;
                }
            }
        }
        return current;
    }

    private void adopt(Material loaded) {
        SignerIdentity facts = loaded.identity();
        log.info("Contract signing certificate ready: subject={}, serial={}, notAfter={}, test={}", facts.subject(),
                facts.serial().toString(16), facts.notAfter(), facts.testCertificate());
        this.identity = facts;
        this.material = loaded;
    }

    // ---- a PKCS#12 the deployment supplies

    private static Material fromKeystore(ContractSigningProperties properties) {
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(properties.keystore().strip());
            char[] password = properties.keystorePassword() == null ? new char[0]
                    : properties.keystorePassword().toCharArray();
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            keystore.load(new ByteArrayInputStream(bytes), password);
            for (Enumeration<String> aliases = keystore.aliases(); aliases.hasMoreElements(); ) {
                String alias = aliases.nextElement();
                if (!keystore.isKeyEntry(alias)) {
                    continue;
                }
                var key = keystore.getKey(alias, password);
                Certificate[] chain = keystore.getCertificateChain(alias);
                if (!(key instanceof PrivateKey privateKey) || chain == null || chain.length == 0) {
                    continue;
                }
                List<X509Certificate> certificates = new ArrayList<>();
                for (Certificate certificate : chain) {
                    certificates.add((X509Certificate) certificate);
                }
                X509Certificate leaf = certificates.get(0);
                boolean selfIssued = leaf.getSubjectX500Principal().equals(leaf.getIssuerX500Principal());
                return new Material(privateKey, certificates, selfIssued);
            }
        } catch (IOException | GeneralSecurityException | RuntimeException ex) {
            throw new IllegalStateException("stockflow.order.contract-signing.keystore (CONTRACT_SIGNING_KEYSTORE) is "
                    + "not a base64 PKCS#12 that opens with the configured password", ex);
        }
        throw new IllegalStateException("stockflow.order.contract-signing.keystore holds no private key with a "
                + "certificate chain");
    }

    // ---- the generated one

    private Material fromDatabase() {
        var stored = store.loadOrCreate(PURPOSE, this::generate);
        try {
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(stored.certificate()));
            byte[] pkcs8 = Base64.getDecoder().decode(cipher().open(stored.privateKeySealed(), SEAL_CONTEXT));
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            return new Material(key, List.of(certificate), true);
        } catch (GeneralSecurityException | IllegalStateException ex) {
            // The usual cause: CONTRACT_SIGNING_SECRET changed after the key was sealed. Not repaired silently - a new
            // certificate would make every signed contract's signer different from the one the old pages name.
            throw new IllegalStateException("The stored contract signing key cannot be opened; it was sealed with "
                    + "another CONTRACT_SIGNING_SECRET", ex);
        }
    }

    private ContractSigningKeys.Stored generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_BITS, random);
            KeyPair pair = generator.generateKeyPair();
            X500Name name = subject();
            Instant now = clock.instant();
            JcaX509v3CertificateBuilder builder = CertificateDates.builder(name, new BigInteger(127, random).add(
                    BigInteger.ONE), now.minus(Duration.ofDays(1)), now.plus(VALIDITY), name, pair.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
            X509CertificateHolder holder = builder.build(new JcaContentSignerBuilder("SHA256withRSA")
                    .build(pair.getPrivate()));
            X509Certificate certificate = new JcaX509CertificateConverter().setProvider(new BouncyCastleProvider())
                    .getCertificate(holder);
            String sealed = cipher().seal(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                    SEAL_CONTEXT);
            return new ContractSigningKeys.Stored(certificate.getEncoded(), sealed);
        } catch (GeneralSecurityException | org.bouncycastle.operator.OperatorCreationException
                 | IOException ex) {
            throw new IllegalStateException("The contract signing certificate could not be generated", ex);
        }
    }

    /** CN and O the seller's name, OU the notice that no authority issued it (the Vietnamese text of the bundle). */
    private X500Name subject() {
        String company = seller.name() == null || seller.name().isBlank() ? "StockFlow" : seller.name().strip();
        String notice = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"))
                .getString("contract.signing.testCa");
        return new X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.CN, company.length() > COMMON_NAME_MAX ? company.substring(0, COMMON_NAME_MAX) : company)
                .addRDN(BCStyle.O, company)
                .addRDN(BCStyle.OU, notice)
                .addRDN(BCStyle.C, "VN")
                .build();
    }

    private SecretCipher cipher() {
        String key = Base64.getEncoder().encodeToString(secrets.derive("key-seal"));
        return new SecretCipher(new SecretCipherProperties(key));
    }
}
