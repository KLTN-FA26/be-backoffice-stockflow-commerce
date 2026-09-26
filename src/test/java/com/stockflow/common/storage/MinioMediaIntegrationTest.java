package com.stockflow.common.storage;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MinioMediaIntegrationTest {
    // Keep aligned with docker-compose.yml; the original upstream image is no longer pullable.
    @Container static final GenericContainer<?> MINIO = new GenericContainer<>(
            "ghcr.io/coollabsio/minio:RELEASE.2025-04-22T22-12-26Z@sha256:a4938f37f1be1841b8e7b627ad0207b265345fd0d063e42d7410c78af0e63e68")
            .withEnv("MINIO_ROOT_USER", "stockflow-test").withEnv("MINIO_ROOT_PASSWORD", "stockflow-test-secret")
            .withCommand("server /data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    @Test void uploadedBytesAndBrowserPresignedDownloadMatch() throws Exception {
        URI endpoint = URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("stockflow-test", "stockflow-test-secret"));
        var configuration = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        try (var client = S3Client.builder().endpointOverride(endpoint).region(Region.AP_SOUTHEAST_1)
                .credentialsProvider(credentials).serviceConfiguration(configuration).build();
             var signer = S3Presigner.builder().endpointOverride(endpoint).region(Region.AP_SOUTHEAST_1)
                .credentialsProvider(credentials).serviceConfiguration(configuration).build()) {
            client.createBucket(b -> b.bucket("media-test"));
            var properties = new StorageProperties("media-test", endpoint.toString(), "ap-southeast-1", true, ".unused", Duration.ofMinutes(5));
            var storage = new S3FileStorage(client, properties, Clock.systemUTC(), signer);
            byte[] bytes = "%PDF-1.7\nTest artifact".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var file = storage.store(FileCategory.DESIGN_RENDER, "test.pdf", "application/pdf", bytes.length, new ByteArrayInputStream(bytes));
            try (var input = storage.download(file.key())) { assertThat(input.readAllBytes()).isEqualTo(bytes); }
            try (var browser = HttpClient.newHttpClient()) {
                var response = browser.send(HttpRequest.newBuilder(URI.create(storage.presignedDownloadUrl(file.key(), Duration.ofMinutes(1))))
                        .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).isEqualTo(bytes);
            }
            storage.delete(file.key());
            assertThat(storage.exists(file.key())).isFalse();
        }
    }
}
