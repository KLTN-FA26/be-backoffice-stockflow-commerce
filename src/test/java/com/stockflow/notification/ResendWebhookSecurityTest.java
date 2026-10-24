package com.stockflow.notification;

import com.stockflow.support.SecuredIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Resend webhook with the real security chain (SCRUM-352): Resend holds no token, so the chain lets an anonymous
 * POST through to the endpoint, whose signature check is the authentication - and lets nothing else through.
 *
 * <p>In the shared secured context no signing secret is configured, so a POST that reaches the endpoint is answered
 * 503 {@code WEBHOOK_UNAVAILABLE} by it: that code, rather than the chain's 401 {@code UNAUTHORIZED}, is the proof the
 * request got past the chain.</p>
 */
@SecuredIntegrationTest
class ResendWebhookSecurityTest {

    private static final String WEBHOOK = "/api/v1/notifications/webhooks/resend";

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;

    @Test
    @DisplayName("an anonymous POST reaches the endpoint; with no secret configured it is refused there, 503")
    void anonymousPostReachesTheEndpoint() throws Exception {
        mvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"email.delivered\"}")
                        .header("svix-id", "msg_1").header("svix-timestamp", "1").header("svix-signature", "v1,AAAA"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("WEBHOOK_UNAVAILABLE"));
    }

    @Test
    @DisplayName("only POST is open: any other method on the path still needs a token")
    void onlyPostIsOpen() throws Exception {
        mvc.perform(get(WEBHOOK)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }
}
