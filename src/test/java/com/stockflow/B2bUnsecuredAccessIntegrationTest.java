package com.stockflow;

import com.stockflow.common.idempotency.IdempotencyKeys;
import com.stockflow.common.idempotency.IdempotencyStore;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Retiring B2C must also defeat cached responses in the local security-off configuration. */
@IntegrationTest
@AutoConfigureMockMvc
@Import({PostgresContainer.class, RedisContainer.class})
@TestPropertySource(properties = {"stockflow.security.enabled=false", "stockflow.idempotency.enabled=true",
        "stockflow.checkout.guest-enabled=true"})
class B2bUnsecuredAccessIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired IdempotencyStore idempotency;
    @Autowired Clock clock;

    @Test
    void retiredRoutesCannotReplayOldSuccessWhenLocalSecurityIsDisabled() throws Exception {
        for (String path : List.of("/api/v1/customers/registrations", "/api/v1/orders/guest-checkout")) {
            String key = "local-before-b2b-" + UUID.randomUUID();
            var now = clock.instant();
            String fingerprint = IdempotencyKeys.fingerprint("POST", path, "{}".getBytes(StandardCharsets.UTF_8));
            assertThat(idempotency.beginIfAbsent(key, "anonymous", fingerprint, now, now.plusSeconds(3600)))
                    .isEmpty();
            idempotency.complete(key, "anonymous", 201, "{\"success\":true}", now);

            mvc.perform(post(path).header("Idempotency-Key", key).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Idempotency-Replayed"));
        }
    }
}
