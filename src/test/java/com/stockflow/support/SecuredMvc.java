package com.stockflow.support;

import com.stockflow.common.security.Role;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

/**
 * A {@link MockMvc} that signs requests in as a role, for tests with {@code stockflow.security.enabled=true}.
 *
 * <pre>
 * &#64;SecuredIntegrationTest
 * class MyTest {
 *     &#64;Autowired MockMvc mvc;
 *     &#64;MockitoBean JwtDecoder decoder;
 *
 *     &#64;Test void x() throws Exception {
 *         SecuredMvc http = new SecuredMvc(mvc, decoder);
 *         http.asRole(Role.SALES_STAFF, userId, get("/api/v1/orders")).andExpect(status().isOk());
 *     }
 * }
 * </pre>
 *
 * @see TestTokens for the token itself
 */
public final class SecuredMvc {

    private final MockMvc mvc;
    private final JwtDecoder decoder;

    public SecuredMvc(MockMvc mvc, JwtDecoder decoder) {
        this.mvc = mvc;
        this.decoder = decoder;
    }

    /** The {@code Authorization} header value for this account and role. */
    public String token(Role role, UUID user) {
        return TestTokens.bearer(decoder, user, role);
    }

    /** Performs the request signed in as {@code role}. */
    public ResultActions asRole(Role role, UUID user, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", token(role, user)));
    }

    /** Performs the request signed in as a CUSTOMER account. */
    public ResultActions asCustomer(UUID user, MockHttpServletRequestBuilder request) throws Exception {
        return asRole(Role.CUSTOMER, user, request);
    }

    /** Performs the request with no token at all. */
    public ResultActions anonymous(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request);
    }
}
