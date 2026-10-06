package com.stockflow.product.internal.controller;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.storage.StorageException;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.product.internal.domain.ProductImage;
import com.stockflow.common.web.GlobalExceptionHandler;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionChecker;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.PermissionDeniedException;
import com.stockflow.common.security.RequiresPermissionAspect;
import com.stockflow.product.internal.service.ProductImageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.support.StaticMessageSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Optional;
import java.util.Set;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/** Real embedded Tomcat: MockMvc multipart requests bypass the servlet size limit. */
@SpringBootTest(classes = ProductImageHttpTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.servlet.multipart.max-file-size=1KB", "spring.servlet.multipart.max-request-size=2KB"})
class ProductImageHttpTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
            MultipartAutoConfiguration.class, JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
    @Import({ProductImageController.class, ProductImageWebMapper.class, GlobalExceptionHandler.class,
            Messages.class, RequiresPermissionAspect.class})
    static class Config {
        @Bean ProductImageService productImageService() { return mock(ProductImageService.class); }
        @Bean StaticMessageSource messageSource() { return new StaticMessageSource(); }
        @Bean PermissionChecker permissionChecker() { return mock(PermissionChecker.class); }
        @Bean CurrentUserProvider currentUserProvider() { return mock(CurrentUserProvider.class); }
    }

    @LocalServerPort int port;
    @Autowired ProductImageService images;
    @Autowired PermissionChecker permissions;
    @Autowired CurrentUserProvider users;

    @BeforeEach void resetMocks() {
        reset(images, permissions, users);
        when(users.current()).thenReturn(Optional.of(new CurrentUser(UUID.randomUUID(), "staff",
                Set.of(), Set.of(), DataScope.ALL, Set.of())));
    }

    private HttpResponse<String> upload(int bytes) throws Exception {
        String boundary = "scrum43boundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n" + "x".repeat(bytes) + "\r\n--" + boundary + "--\r\n";
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + "/api/v1/products/" + UUID.randomUUID() + "/images"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test void servletOversizeReturns413BeforeControllerRuns() throws Exception {
        var response = upload(1500);
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("PAYLOAD_TOO_LARGE").doesNotContain("INTERNAL_ERROR");
        verifyNoInteractions(images);
    }

    @Test void successfulUploadReturns201AndMetadataWithoutExposingStorageKey() throws Exception {
        var id = UUID.randomUUID();
        when(images.upload(any(), any())).thenReturn(new ProductImage(id, null, 0,
                new StoredFile("product-images/private-key.png", "x.png", "image/png", 12, Instant.EPOCH)));
        var response = upload(12);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains(id.toString(), "image/png", "sizeBytes")
                .doesNotContain("private-key", "storageKey");
    }

    @Test void policyOversizeReturns413() throws Exception {
        when(images.upload(any(), any())).thenThrow(new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE));
        var response = upload(12);
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("PAYLOAD_TOO_LARGE");
    }

    @Test void unsupportedTypeReturns415() throws Exception {
        when(images.upload(any(), any())).thenThrow(new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
        var response = upload(12);
        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(response.body()).contains("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test void outageReturns503WithoutLeakingBackendDetails() throws Exception {
        when(images.upload(any(), any())).thenThrow(new StorageException("secret-server/private-path"));
        var response = upload(12);
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("STORAGE_ERROR").doesNotContain("secret-server", "private-path");
    }

    @Test void deniesMissingUpdatePermissionBeforeCallingService() throws Exception {
        var permission = PermissionCode.of(ProductResources.PRODUCTS, Action.UPDATE);
        doThrow(new PermissionDeniedException(permission)).when(permissions).require(permission);
        var response = upload(12);
        assertThat(response.statusCode()).isEqualTo(403);
        verifyNoInteractions(images);
    }

    @Test void wrongRequestMediaTypeUses415Code() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + "/api/v1/products/" + UUID.randomUUID() + "/images"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(415);
            assertThat(response.body()).contains("UNSUPPORTED_MEDIA_TYPE");
        }
    }
}
