package com.stockflow.identity.internal.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Set;

/**
 * Where the RSA key that signs staff and customer tokens comes from (see {@link JwtKeysConfig}).
 *
 * <p>Three sources, first match wins:</p>
 * <ol>
 *   <li><b>PEM content</b> ({@code JWT_PRIVATE_KEY}): a PKCS#8 {@code -----BEGIN PRIVATE KEY-----} block, for a
 *       platform that injects secrets as environment variables. Literal {@code \n} sequences are accepted for line
 *       breaks, since most secret stores flatten the value to one line.</li>
 *   <li><b>A key file</b> ({@code JWT_PRIVATE_KEY_PATH}): read when it exists. When it does not, a key is generated
 *       once and written there - owner-only permissions, made under a temporary name and linked into place, so a
 *       crash never leaves half a key and two instances starting together end up with the same one. This is the dev
 *       server's setup: a persistent host directory mounted into the container.</li>
 *   <li><b>Nothing configured</b>: a fresh key in memory, as before - right for local development and tests, where a
 *       restart signing everyone out costs nothing. Logged as a warning so a deployment that forgot the setting
 *       shows it.</li>
 * </ol>
 *
 * <p>A configured key that cannot be used - unreadable, not PKCS#8, not RSA, shorter than 2048 bits - stops the
 * application at startup with the reason. Falling back to a generated key there would sign everyone out on every
 * restart again, silently, which is exactly the defect this class exists to remove.</p>
 *
 * <p>The key id ({@code kid}) is the key's RFC 7638 thumbprint: the same key always has the same id, so a token
 * issued before a restart names a key the JWK set still publishes.</p>
 */
final class JwtSigningKey {

    private static final Logger log = LoggerFactory.getLogger(JwtSigningKey.class);

    static final int MIN_KEY_BITS = 2048;
    private static final String PEM_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_END = "-----END PRIVATE KEY-----";
    private static final Set<PosixFilePermission> OWNER_ONLY_FILE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_ONLY_DIR = PosixFilePermissions.fromString("rwx------");

    private JwtSigningKey() {
    }

    /**
     * @param pem     PKCS#8 PEM content; blank when not configured
     * @param keyPath the key file; null or blank when not configured
     * @throws IllegalStateException when a configured key cannot be used, naming why (never the key itself)
     */
    static RSAKey resolve(String pem, String keyPath) {
        if (pem != null && !pem.isBlank()) {
            RSAKey key = fromPem(pem, "JWT_PRIVATE_KEY");
            log.info("JWT signing key loaded from JWT_PRIVATE_KEY (kid {})", key.getKeyID());
            return key;
        }
        if (keyPath != null && !keyPath.isBlank()) {
            return fromFile(Path.of(keyPath.trim()));
        }
        RSAKey key = toJwk(generate());
        log.warn("No JWT signing key configured (JWT_PRIVATE_KEY_PATH / JWT_PRIVATE_KEY): using a key generated for "
                + "this process only - every token is invalid after a restart. Fine for local development, not for a "
                + "deployment.");
        return key;
    }

    /** Reads the key at {@code path}, or makes one there when there is none yet. */
    static RSAKey fromFile(Path path) {
        if (Files.exists(path)) {
            RSAKey key = read(path);
            log.info("JWT signing key loaded from {} (kid {})", path, key.getKeyID());
            return key;
        }
        RSAKey made = toJwk(generate());
        if (write(path, made)) {
            log.info("JWT signing key generated and stored at {} (kid {}); tokens now survive restarts", path,
                    made.getKeyID());
            return made;
        }
        // Another instance stored its key first: use that one, so both sign with the same key.
        RSAKey key = read(path);
        log.info("JWT signing key loaded from {} (kid {}), stored there by another instance", path, key.getKeyID());
        return key;
    }

    private static RSAKey read(Path path) {
        String content;
        try {
            content = Files.readString(path, StandardCharsets.US_ASCII);
        } catch (AccessDeniedException ex) {
            throw new IllegalStateException("JWT signing key file " + path + " is not readable by this process "
                    + "(check its owner and permissions; the application runs as an unprivileged user)", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("JWT signing key file " + path + " cannot be read: " + ex.getMessage(),
                    ex);
        }
        return fromPem(content, "JWT signing key file " + path);
    }

    /**
     * Writes {@code key} to {@code path} if nothing is there yet.
     *
     * @return false when another process created the file first (its key is the one to use)
     */
    private static boolean write(Path path, RSAKey key) {
        Path target = path.toAbsolutePath();
        Path dir = target.getParent();
        boolean posix = dir != null && dir.getFileSystem().supportedFileAttributeViews().contains("posix");
        Path temp = null;
        try {
            if (dir != null && !Files.isDirectory(dir)) {
                if (posix) {
                    Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR));
                } else {
                    Files.createDirectories(dir);
                }
            }
            FileAttribute<?>[] attrs = posix
                    ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE)}
                    : new FileAttribute<?>[0];
            temp = Files.createTempFile(dir, ".jwt-signing-key-", ".tmp", attrs);
            Files.writeString(temp, toPem(key), StandardCharsets.US_ASCII);
            try {
                // A hard link creates the name only if it is free, atomically: of two instances starting together
                // exactly one wins, and the loser reads the winner's key instead of replacing it. No fallback to a
                // rename when links are unsupported: a rename replaces a key another instance just stored.
                Files.createLink(target, temp);
            } catch (FileAlreadyExistsException ex) {
                return false;
            } catch (UnsupportedOperationException ex) {
                throw new IllegalStateException("Cannot create the JWT signing key at " + path + ": the file system "
                        + "does not support hard links; generate the key yourself (openssl genpkey -algorithm RSA "
                        + "-pkeyopt rsa_keygen_bits:2048) and put it there, or use JWT_PRIVATE_KEY", ex);
            }
            return true;
        } catch (AccessDeniedException ex) {
            throw new IllegalStateException("Cannot create the JWT signing key at " + path + ": the directory is not "
                    + "writable by this process (on the dev server, deploy.sh gives it to the container user)", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create the JWT signing key at " + path + ": " + ex.getMessage(),
                    ex);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // a stray temporary file holds a key nobody uses; nothing to recover
                }
            }
        }
    }

    /** Parses PKCS#8 PEM; {@code source} names where it came from in the error, never its content. */
    static RSAKey fromPem(String pem, String source) {
        String text = pem.replace("\\n", "\n").trim();
        int begin = text.indexOf(PEM_BEGIN);
        int end = text.indexOf(PEM_END);
        if (begin < 0 || end < begin) {
            String hint = text.contains("BEGIN RSA PRIVATE KEY")
                    ? " It is PKCS#1 (BEGIN RSA PRIVATE KEY); convert it with: openssl pkcs8 -topk8 -nocrypt -in old.pem"
                    : text.contains("ENCRYPTED") ? " It is encrypted; store it unencrypted (owner-only file permissions)."
                    : "";
            throw new IllegalStateException(source + " is not a PKCS#8 PEM private key (expected " + PEM_BEGIN
                    + ")." + hint + " Generate one with: openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048");
        }
        String body = text.substring(begin + PEM_BEGIN.length(), end).replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(body);
            var privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
                throw new IllegalStateException(source + " has no public exponent (not an RSA CRT key)");
            }
            if (crt.getModulus().bitLength() < MIN_KEY_BITS) {
                throw new IllegalStateException(source + " is a " + crt.getModulus().bitLength()
                        + "-bit RSA key; at least " + MIN_KEY_BITS + " bits are required");
            }
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            return toJwk(new KeyPair(publicKey, crt));
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            throw new IllegalStateException(source + " is not a valid RSA private key (PKCS#8 PEM expected): "
                    + ex.getClass().getSimpleName(), ex);
        }
    }

    static String toPem(RSAKey key) {
        try {
            byte[] der = key.toRSAPrivateKey().getEncoded();
            String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
            return PEM_BEGIN + "\n" + body + "\n" + PEM_END + "\n";
        } catch (JOSEException ex) {
            throw new IllegalStateException("JWT signing key cannot be encoded", ex);
        }
    }

    private static RSAKey toJwk(KeyPair pair) {
        try {
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey(pair.getPrivate())
                    .keyIDFromThumbprint()
                    .build();
        } catch (JOSEException ex) {
            throw new IllegalStateException("JWT signing key thumbprint cannot be computed", ex);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(MIN_KEY_BITS);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException ex) {
            // RSA is a mandatory algorithm for every JCE provider - this cannot happen in practice.
            throw new IllegalStateException("JVM does not support RSA key generation", ex);
        }
    }

    /** For tests: the modulus, to compare two keys without exposing the private half. */
    static BigInteger modulusOf(RSAKey key) {
        return key.getModulus().decodeToBigInteger();
    }
}
