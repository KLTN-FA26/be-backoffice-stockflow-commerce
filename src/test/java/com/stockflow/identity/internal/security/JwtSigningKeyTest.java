package com.stockflow.identity.internal.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * E2E QA 2026-10-21: the signing key was regenerated on every start, so every deploy signed every user out. These pin
 * the persisted key: two application contexts on one key path (a restart) accept each other's tokens; a missing file
 * is created owner-only; a configured key that is unusable stops startup with the reason.
 */
class JwtSigningKeyTest {

    @TempDir
    Path dir;

    private ApplicationContextRunner contextWith(String... properties) {
        return new ApplicationContextRunner().withUserConfiguration(JwtKeysConfig.class).withPropertyValues(properties);
    }

    @Test
    @DisplayName("two contexts on one key path (a restart): a token issued by the first is valid for the second, "
            + "same kid, and the kid is the key's thumbprint")
    void tokensSurviveARestart() {
        String path = dir.resolve("jwt/signing-key.pem").toString();
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> firstKid = new AtomicReference<>();
        contextWith("stockflow.security.jwt.key-path=" + path).run(first -> {
            assertThat(first).hasNotFailed();
            token.set(issue(first.getBean(JwtEncoder.class)));
            RSAKey key = first.getBean(RSAKey.class);
            firstKid.set(key.getKeyID());
            assertThat(key.getKeyID()).isEqualTo(key.computeThumbprint().toString());
        });
        contextWith("stockflow.security.jwt.key-path=" + path).run(second -> {
            assertThat(second).hasNotFailed();
            assertThat(second.getBean(RSAKey.class).getKeyID()).isEqualTo(firstKid.get());
            @SuppressWarnings("unchecked")
            JWKSource<SecurityContext> keys = second.getBean(JWKSource.class);
            JWTClaimsSet claims = verify(token.get(), keys);
            assertThat(claims.getSubject()).isEqualTo("user-1");
            // and the other way round: the second instance's token is the first one's too
            String back = issue(second.getBean(JwtEncoder.class));
            contextWith("stockflow.security.jwt.key-path=" + path).run(third -> {
                @SuppressWarnings("unchecked")
                JWKSource<SecurityContext> thirdKeys = third.getBean(JWKSource.class);
                assertThat(verify(back, thirdKeys).getSubject()).isEqualTo("user-1");
            });
        });
    }

    @Test
    @DisplayName("nothing configured: a per-process key, so two contexts do NOT share tokens (local dev, as before)")
    void unconfiguredKeyIsPerProcess() {
        AtomicReference<String> kid = new AtomicReference<>();
        contextWith().run(first -> kid.set(first.getBean(RSAKey.class).getKeyID()));
        contextWith().run(second -> assertThat(second.getBean(RSAKey.class).getKeyID()).isNotEqualTo(kid.get()));
    }

    @Test
    @DisplayName("a missing file is created (with its directory), owner-only, as PKCS#8 PEM that loads back the same key")
    void missingFileIsCreatedOwnerOnly() throws Exception {
        Path file = dir.resolve("nested/keys/signing-key.pem");
        RSAKey made = JwtSigningKey.fromFile(file);
        assertThat(file).exists();
        String pem = Files.readString(file);
        assertThat(pem).startsWith("-----BEGIN PRIVATE KEY-----\n").endsWith("-----END PRIVATE KEY-----\n");
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())))
                    .isEqualTo("rwx------");
        }
        try (var leftovers = Files.list(file.getParent())) {
            assertThat(leftovers.map(p -> p.getFileName().toString())).containsExactly("signing-key.pem");
        }
        RSAKey again = JwtSigningKey.fromFile(file);
        assertThat(again.getKeyID()).isEqualTo(made.getKeyID());
        assertThat(JwtSigningKey.modulusOf(again)).isEqualTo(JwtSigningKey.modulusOf(made));
    }

    @Test
    @DisplayName("PEM content (JWT_PRIVATE_KEY, also with literal \\n) wins over the path and matches the file's key")
    void pemContentIsUsed() {
        Path file = dir.resolve("signing-key.pem");
        RSAKey stored = JwtSigningKey.fromFile(file);
        String oneLine = JwtSigningKey.toPem(stored).replace("\n", "\\n");
        contextWith("stockflow.security.jwt.private-key=" + oneLine,
                "stockflow.security.jwt.key-path=" + dir.resolve("other.pem")).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(RSAKey.class).getKeyID()).isEqualTo(stored.getKeyID());
        });
        assertThat(dir.resolve("other.pem")).doesNotExist();
    }

    @Test
    @DisplayName("an invalid key file fails startup naming the file and the expected format, never falling back")
    void invalidFileFailsFast() throws Exception {
        Path file = dir.resolve("signing-key.pem");
        Files.writeString(file, "not a key");
        contextWith("stockflow.security.jwt.key-path=" + file).run(ctx -> assertThat(ctx).hasFailed()
                .getFailure().rootCause().hasMessageContaining(file.toString()).hasMessageContaining("PKCS#8"));

        Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n@@@@\n-----END PRIVATE KEY-----\n");
        assertThatThrownBy(() -> JwtSigningKey.fromFile(file)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a valid RSA private key");

        Files.writeString(file, "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");
        assertThatThrownBy(() -> JwtSigningKey.fromFile(file)).hasMessageContaining("PKCS#1");
    }

    @Test
    @DisplayName("a key shorter than 2048 bits is refused")
    void weakKeyRefused() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        Path file = dir.resolve("weak.pem");
        Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n");
        assertThatThrownBy(() -> JwtSigningKey.fromFile(file)).hasMessageContaining("1024-bit")
                .hasMessageContaining("2048");
    }

    @Test
    @DisplayName("an unreadable key file fails startup saying it is not readable (owner / permissions)")
    void unreadableFileFailsFast() throws Exception {
        Path file = dir.resolve("signing-key.pem");
        JwtSigningKey.fromFile(file);
        assumeThat(file.getFileSystem().supportedFileAttributeViews()).contains("posix");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        try {
            assumeThat(Files.isReadable(file)).as("running as root reads anything").isFalse();
            assertThatThrownBy(() -> JwtSigningKey.fromFile(file)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(file.toString()).hasMessageContaining("not readable");
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    @DisplayName("a directory the process cannot write fails startup with the reason, instead of an in-memory key")
    void unwritableDirectoryFailsFast() throws Exception {
        assumeThat(dir.getFileSystem().supportedFileAttributeViews()).contains("posix");
        Path locked = Files.createDirectory(dir.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"));
        try {
            assumeThat(Files.isWritable(locked)).as("running as root writes anything").isFalse();
            assertThatThrownBy(() -> JwtSigningKey.fromFile(locked.resolve("signing-key.pem")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("not writable");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private static String issue(JwtEncoder encoder) {
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("user-1").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    private static JWTClaimsSet verify(String token, JWKSource<SecurityContext> keys) throws Exception {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        return processor.process(token, null);
    }
}
