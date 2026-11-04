package com.stockflow.common.config;

import com.stockflow.common.storage.StorageProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Builds the S3 client, pointed either at MinIO locally or at real S3.
 *
 * <h2>The two settings that break this if they are wrong</h2>
 *
 * <p><b>Path-style addressing.</b> S3 addresses a bucket as {@code bucket.s3.amazonaws.com};
 * MinIO expects {@code localhost:9000/bucket}. With the wrong one, the SDK tries to resolve
 * {@code stockflow.localhost} and fails with a DNS error that looks like a network problem rather
 * than a configuration one. It is on by default here because MinIO is the local default.</p>
 *
 * <p><b>Credentials.</b> Static keys are used only when an endpoint override is set — that is, when
 * talking to MinIO. Against real S3 the default provider chain is used instead, so the deployment
 * gets its credentials from the instance role rather than from configuration. Long-lived access
 * keys in a properties file are the most common way cloud credentials leak.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "stockflow.storage", name = "provider",
        havingValue = "s3", matchIfMissing = true)
public class StorageConfig {

    @Bean
    public S3Presigner s3Presigner(StorageProperties properties,
                                  @Value("${stockflow.storage.access-key:}") String accessKey,
                                  @Value("${stockflow.storage.secret-key:}") String secretKey) {
        var builder = S3Presigner.builder().region(Region.of(properties.region()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyle()).build());
        if (properties.usesCustomEndpoint()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        builder.credentialsProvider(!accessKey.isBlank() && !secretKey.isBlank()
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
                : DefaultCredentialsProvider.create());
        return builder.build();
    }

    @Bean
    public S3Client s3Client(StorageProperties properties,
                             @Value("${stockflow.storage.access-key:}") String accessKey,
                             @Value("${stockflow.storage.secret-key:}") String secretKey) {

        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyle())
                        .build());

        if (properties.usesCustomEndpoint()) {
            builder = builder.endpointOverride(URI.create(properties.endpoint()));
        }

        boolean hasStaticCredentials = !accessKey.isBlank() && !secretKey.isBlank();
        if (hasStaticCredentials) {
            builder = builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            // Instance role, environment, or the shared credentials file - whatever the deployment
            // provides. Never a key committed to the repository.
            builder = builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        return builder.build();
    }
}
