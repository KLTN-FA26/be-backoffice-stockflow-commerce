package com.stockflow.order;

import com.stockflow.common.storage.DownloadDisposition;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.notification.internal.domain.Email;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.service.ProductionOrderDispatch;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A producer's links (4A-3, kltn-docs 17 §4.3 step 7) through the real HTTP chain with security ON: the routes are
 * reached with no token at all, the signed link being the only credential, and the answer is what a mail client sees.
 * {@link ProductionOrderDispatchIntegrationTest} calls the service with security off; this is the part it cannot
 * show - the permitAll matchers (GET and HEAD), the error codes as HTTP statuses, the redirect's headers.
 *
 * <p>The test profile's local store signs no browser URL, so the store is a spy whose presigner answers an https URL:
 * presigning is really reached, after every check passed, and the redirect carries its answer.</p>
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import({PostgresContainer.class, RedisContainer.class})
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class ProductionOrderFileLinksSecurityIntegrationTest extends ProductionOrderDispatchFixtures {

    private static final String PRESIGNED = "https://s3.test.example/";

    @Autowired MockMvc mvc;
    @MockitoSpyBean FileStorage presigner;
    @MockitoBean JwtDecoder decoder;

    @BeforeEach
    void presignedUrls() {
        doAnswer(call -> PRESIGNED + call.getArgument(0) + "?X-Amz-Signature=test").when(presigner)
                .presignedDownloadUrl(anyString(), any(Duration.class), nullable(DownloadDisposition.class));
    }

    private static MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder request, Link link) {
        return request.param("expires", Long.toString(link.expires())).param("signature", link.signature());
    }

    @Test
    @DisplayName("a valid link, no token: 302 to the presigned URL, not stored, no referrer - GET and HEAD alike")
    void validLinkRedirects() throws Exception {
        ProductionOrder sent = approved(producer);
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
        verify(presigner, atLeastOnce()).presignedDownloadUrl(org.mockito.ArgumentMatchers.eq(slipKey(sent.id())),
                any(Duration.class), nullable(DownloadDisposition.class));
        verify(presigner, atLeastOnce()).presignedDownloadUrl(org.mockito.ArgumentMatchers.eq(sku.printFileKey()),
                any(Duration.class), nullable(DownloadDisposition.class));
    }

    @Test
    @DisplayName("a tampered link: 403 PRODUCTION_ORDER_LINK_INVALID; an expired one: 410 - and nothing presigned")
    void badLinksAreRefused() throws Exception {
        ProductionOrder sent = approved(producer);
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

        verify(presigner, org.mockito.Mockito.never()).presignedDownloadUrl(
                org.mockito.ArgumentMatchers.eq(slipKey(sent.id())), any(Duration.class),
                nullable(DownloadDisposition.class));
    }

    @Test
    @DisplayName("the staff routes next to them still need a token: only the signed routes are open")
    void staffRoutesStayClosed() throws Exception {
        ProductionOrder sent = approved(producer);

        mvc.perform(get("/api/v1/production-orders/" + sent.id() + "/slip")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/production-orders/" + sent.id() + "/dispatch")).andExpect(status().isUnauthorized());
        assertThat(sent.status().name()).isEqualTo("SENT");
    }
}
