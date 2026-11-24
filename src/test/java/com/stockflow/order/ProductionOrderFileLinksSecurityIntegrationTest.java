package com.stockflow.order;

import com.stockflow.common.security.Role;
import com.stockflow.common.storage.DownloadDisposition;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.ObjectLockProperties;
import com.stockflow.common.storage.ObjectStat;
import com.stockflow.common.storage.PresignedUpload;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.common.storage.StoredObject;
import com.stockflow.notification.internal.domain.Email;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.service.ProductionOrderDispatch;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.SecuredMvc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A producer's links (4A-3, kltn-docs 17 §4.3 step 7) through the real HTTP chain with security ON: the routes are
 * reached with no token at all, the signed link being the only credential, and the answer is what a mail client sees.
 * {@link ProductionOrderDispatchIntegrationTest} calls the service with security off; this is the part it cannot
 * show - the permitAll matchers (GET and HEAD), the error codes as HTTP statuses, the redirect's headers. It also
 * holds the staff route that sends a production order (2026-11-20: the coordinator sends, nobody approves), because
 * this is where the real filter chain and the permission aspect run.
 *
 * <p>The test profile's local store signs no browser URL, so the store is wrapped in {@link PresigningStorage}, whose
 * presigner answers an https URL and remembers the key: presigning is really reached, after every check passed, and the
 * redirect carries its answer.</p>
 *
 * <p>It used to be a Mockito spy stubbed in a {@code @BeforeEach}. Sending a production order starts asynchronous
 * listeners (the producer's e-mail reads the slip from this store), and a test that does not wait for its mail leaves
 * them running into the next test, where they called the spy while it was being stubbed - and reset. Mockito does not
 * allow that: the stubbing failed with {@code UnfinishedStubbingException} in an unrelated test, now and then (CI, PR
 * #416). A plain decorator has no stubbing to interrupt.</p>
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import({PostgresContainer.class, RedisContainer.class,
        ProductionOrderFileLinksSecurityIntegrationTest.PresigningStorageConfig.class})
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class ProductionOrderFileLinksSecurityIntegrationTest extends ProductionOrderDispatchFixtures {

    private static final String PRESIGNED = "https://s3.test.example/";

    @Autowired MockMvc mvc;
    @Autowired PresigningStorage presigner;
    @MockitoBean JwtDecoder decoder;

    private static MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder request, Link link) {
        return request.param("expires", Long.toString(link.expires())).param("signature", link.signature());
    }

    @Test
    @DisplayName("a valid link, no token: 302 to the presigned URL, not stored, no referrer - GET and HEAD alike")
    void validLinkRedirects() throws Exception {
        ProductionOrder sent = sent(producer);
        Email email = awaitMail(sent.code());
        Link slip = link(email.text(), "/slip");
        Link print = link(email.text(), "/print-files/" + sku.snapshotId());

        for (Link link : new Link[] {slip, print}) {
            mvc.perform(signed(get(link.path()), link))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(PRESIGNED)))
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"));
            mvc.perform(signed(head(link.path()), link))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(PRESIGNED)));
        }
        assertThat(presigner.presignedKeys()).contains(slipKey(sent.id()), sku.printFileKey());
    }

    @Test
    @DisplayName("a tampered link: 403 PRODUCTION_ORDER_LINK_INVALID; an expired one: 410 - and nothing presigned")
    void badLinksAreRefused() throws Exception {
        ProductionOrder sent = sent(producer);
        Email email = awaitMail(sent.code());
        Link slip = link(email.text(), "/slip");
        Link print = link(email.text(), "/print-files/" + sku.snapshotId());

        mvc.perform(signed(get(slip.path()), new Link(slip.path(), slip.expires(), slip.signature() + "x")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("PRODUCTION_ORDER_LINK_INVALID"));
        mvc.perform(signed(get(print.path()), new Link(print.path(), print.expires() + 86_400, print.signature())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("PRODUCTION_ORDER_LINK_INVALID"));
        Link expired = link(links.url(ProductionOrderDispatch.slipPath(sent.id()), Instant.now().minusSeconds(1)),
                "/slip");
        mvc.perform(signed(get(expired.path()), expired))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("PRODUCTION_ORDER_LINK_EXPIRED"));
        mvc.perform(get(slip.path())).andExpect(status().is4xxClientError()); // no signature at all

        assertThat(presigner.presignedKeys()).doesNotContain(slipKey(sent.id()));
    }

    @Test
    @DisplayName("the staff routes next to them still need a token: only the signed routes are open")
    void staffRoutesStayClosed() throws Exception {
        ProductionOrder sent = sent(producer);

        mvc.perform(get("/api/v1/production-orders/" + sent.id() + "/slip")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/production-orders/" + sent.id() + "/dispatch")).andExpect(status().isUnauthorized());
        assertThat(sent.status().name()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("2026-11-20: the coordinator sends a draft over HTTP; the approval, submission and return routes are "
            + "gone (404), and a Sales Manager cannot send (403)")
    void theCoordinatorSendsAndTheApprovalRoutesAreGone() throws Exception {
        SecuredMvc http = new SecuredMvc(mvc, decoder);
        ProductionOrder draft = drafted(producer);
        String base = "/api/v1/production-orders/" + draft.id();
        String version = "{\"version\": " + draft.version() + "}";
        java.util.UUID manager = tx.execute(s -> com.stockflow.support.ReferenceRows.user(entityManager));

        for (String gone : new String[] {"/approval", "/submission", "/return"}) {
            http.asRole(Role.SALES_MANAGER, manager, post(base + gone).contentType(MediaType.APPLICATION_JSON)
                            .content(version))
                    .andExpect(status().isNotFound());
            http.asRole(Role.PRODUCTION_COORDINATOR, coordinator, post(base + gone)
                            .contentType(MediaType.APPLICATION_JSON).content(version))
                    .andExpect(status().isNotFound());
        }
        http.asRole(Role.SALES_MANAGER, manager, post(base + "/sending").contentType(MediaType.APPLICATION_JSON)
                        .content(version))
                .andExpect(status().isForbidden());
        assertThat(reread(draft.id()).status().name()).as("nothing moved yet").isEqualTo("DRAFT");

        http.asRole(Role.PRODUCTION_COORDINATOR, coordinator, post(base + "/sending")
                        .contentType(MediaType.APPLICATION_JSON).content(version))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SENT"))
                .andExpect(jsonPath("$.data.sentBy").value(coordinator.toString()))
                .andExpect(jsonPath("$.data.drawnUpBy").value(coordinator.toString()))
                .andExpect(jsonPath("$.data.approvedBy").doesNotExist());
    }

    @Test
    @DisplayName("17 §4.3 step 8: the acceptance note is stored with the acceptance and returned with the order")
    void theAcceptanceNoteIsReturnedWithTheOrder() throws Exception {
        SecuredMvc http = new SecuredMvc(mvc, decoder);
        ProductionOrder sent = sent(producer);
        String base = "/api/v1/production-orders/" + sent.id();
        String day = java.time.LocalDate.now().plusDays(10).toString();

        http.asRole(Role.PRODUCTION_COORDINATOR, coordinator, post(base + "/acceptance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": " + sent.version() + ", \"expectedCompletionDate\": \"" + day
                                + "\", \"note\": \"  anh Hung, Zalo  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.acceptanceNote").value("anh Hung, Zalo"));
        http.asRole(Role.PRODUCTION_COORDINATOR, coordinator, get(base))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptanceNote").value("anh Hung, Zalo"));
    }

    @Test
    @DisplayName("an acceptance note over 500 characters is 400, nothing accepted")
    void anOverlongAcceptanceNoteIsRefused() throws Exception {
        SecuredMvc http = new SecuredMvc(mvc, decoder);
        ProductionOrder sent = sent(producer);
        String day = java.time.LocalDate.now().plusDays(10).toString();

        http.asRole(Role.PRODUCTION_COORDINATOR, coordinator, post("/api/v1/production-orders/" + sent.id()
                        + "/acceptance").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": " + sent.version() + ", \"expectedCompletionDate\": \"" + day
                                + "\", \"note\": \"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(reread(sent.id()).status().name()).isEqualTo("SENT");
    }

    /** Puts {@link PresigningStorage} around the application's store, before anything is injected with it. */
    @TestConfiguration(proxyBeanMethods = false)
    static class PresigningStorageConfig {

        @Bean
        static BeanPostProcessor presigningStorage() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof FileStorage local && !(bean instanceof PresigningStorage)
                            ? new PresigningStorage(local) : bean;
                }
            };
        }
    }

    /**
     * The application's store, except that a download URL is signed the way S3 would sign it, and every key signed is
     * remembered. Keys are never cleared: each test asks about its own production order's keys, and a listener still
     * running from an earlier test can only add keys of another order.
     */
    static final class PresigningStorage implements FileStorage {

        private final FileStorage local;
        private final Queue<String> presigned = new ConcurrentLinkedQueue<>();

        PresigningStorage(FileStorage local) {
            this.local = local;
        }

        List<String> presignedKeys() {
            return List.copyOf(presigned);
        }

        @Override
        public String presignedDownloadUrl(String key, Duration validFor, DownloadDisposition disposition) {
            presigned.add(key);
            return PRESIGNED + key + "?X-Amz-Signature=test";
        }

        @Override
        public StoredFile store(FileCategory category, String originalName, String contentType, long sizeBytes,
                                InputStream content, String sha256) {
            return local.store(category, originalName, contentType, sizeBytes, content, sha256);
        }

        @Override
        public ObjectStat stat(String key) {
            return local.stat(key);
        }

        @Override
        public void retain(String key, Instant until) {
            local.retain(key, until);
        }

        @Override
        public void retain(String key, Instant until, ObjectLockProperties.Mode mode) {
            local.retain(key, until, mode);
        }

        @Override
        public void storeDerived(String key, String contentType, byte[] bytes) {
            local.storeDerived(key, contentType, bytes);
        }

        @Override
        public List<StoredObject> list(String prefix, String startAfter, int max) {
            return local.list(prefix, startAfter, max);
        }

        @Override
        public PresignedUpload presignedUploadUrl(String incomingKey, String contentType, long sizeBytes, String sha256,
                                                  Duration validFor) {
            return local.presignedUploadUrl(incomingKey, contentType, sizeBytes, sha256, validFor);
        }

        @Override
        public InputStream download(String key) {
            return local.download(key);
        }

        @Override
        public void delete(String key) {
            local.delete(key);
        }

        @Override
        public boolean exists(String key) {
            return local.exists(key);
        }

        @Override
        public void copy(String sourceKey, String targetKey) {
            local.copy(sourceKey, targetKey);
        }
    }
}
