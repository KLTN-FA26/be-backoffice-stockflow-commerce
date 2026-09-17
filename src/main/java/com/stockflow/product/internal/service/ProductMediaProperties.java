package com.stockflow.product.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;

/** Public delivery configuration; storage remains private behind CloudFront Origin Access Control. */
@ConfigurationProperties(prefix = "stockflow.product-media")
public record ProductMediaProperties(@DefaultValue("") String cloudFrontBaseUrl) {
    public ProductMediaProperties {
        cloudFrontBaseUrl = cloudFrontBaseUrl == null ? "" : cloudFrontBaseUrl.strip();
        if (!cloudFrontBaseUrl.isEmpty()) {
            URI uri = URI.create(cloudFrontBaseUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath())) {
                throw new IllegalArgumentException("stockflow.product-media.cloud-front-base-url must be an HTTPS origin without a path");
            }
            cloudFrontBaseUrl = cloudFrontBaseUrl.replaceAll("/+$", "");
        }
    }

    public String publicUrl(String storageKey) {
        if (cloudFrontBaseUrl.isBlank()) {
            throw new com.stockflow.common.storage.StorageException("CloudFront public media domain is not configured");
        }
        return cloudFrontBaseUrl + "/" + com.stockflow.common.storage.StorageKeys.requireValid(storageKey);
    }
}
