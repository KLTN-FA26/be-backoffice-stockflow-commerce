package com.stockflow.common.security;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The forced-password-change gate (SCRUM-474 FIRST-LOGIN): while the flag is set only four calls pass, every
 * other one is 403 PASSWORD_CHANGE_REQUIRED in the API envelope; with the flag clear, or no caller, nothing changes.
 */
class PasswordChangeGateFilterTest {

    private final UUID user = UUID.randomUUID();
    private final PasswordChangeRequirement requirement = mock(PasswordChangeRequirement.class);
    private final AuthenticationEntryPoint unavailable = mock(AuthenticationEntryPoint.class);
    private final Messages messages = mock(Messages.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final PasswordChangeGateFilter filter =
            new PasswordChangeGateFilter(requirement, unavailable,
                    com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build(), messages);

    PasswordChangeGateFilterTest() {
        when(messages.forCode(ErrorCode.PASSWORD_CHANGE_REQUIRED)).thenReturn("Change your password first");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void signedIn() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject(user.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(), user.toString()));
    }

    private MockHttpServletResponse call(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/api/v1/identity/me",
            "GET,/api/v1/identity/me/permissions",
            "PUT,/api/v1/identity/auth/password",
            "POST,/api/v1/identity/auth/logout"})
    void theFourCallsAForcedUserNeedsAreLetThrough(String method, String path) throws Exception {
        signedIn();
        when(requirement.isRequired(user)).thenReturn(true);

        MockHttpServletResponse response = call(method, path);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/api/v1/orders",
            "POST,/api/v1/orders",
            "GET,/api/v1/identity/users",
            "GET,/api/v1/identity/auth/sessions",
            "POST,/api/v1/identity/auth/logout-others",
            "GET,/api/v1/identity/auth/password",
            "POST,/api/v1/identity/auth/password",
            "PUT,/api/v1/identity/me",
            "GET,/api/v1/identity/me/permissions/extra",
            "GET,/api/v1/identity/auth/logout",
            "GET,/actuator/health"})
    void everythingElseIsRefusedWith403AndTheEnvelope(String method, String path) throws Exception {
        signedIn();
        when(requirement.isRequired(user)).thenReturn(true);

        MockHttpServletResponse response = call(method, path);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("\"PASSWORD_CHANGE_REQUIRED\"")
                .contains("Change your password first");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void aUserWhoAlreadyChangedTheirPasswordIsNotGated() throws Exception {
        signedIn();
        when(requirement.isRequired(user)).thenReturn(false);

        MockHttpServletResponse response = call("GET", "/api/v1/orders");

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void anAnonymousRequestIsNotTheGatesBusiness() throws Exception {
        MockHttpServletResponse response = call("GET", "/api/v1/orders");

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
        verify(requirement, never()).isRequired(any());
    }

    @Test
    void aRealPreflightIsLetThroughWithoutAskingAnything() throws Exception {
        signedIn();
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/orders");
        request.addHeader("Access-Control-Request-Method", "GET");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        verify(requirement, never()).isRequired(any());
    }

    @Test
    void whenTheFlagCannotBeReadTheRequestIsRefusedAsUnavailableNeverLetThrough() throws Exception {
        signedIn();
        when(requirement.isRequired(user)).thenThrow(new IllegalStateException("db down"));

        call("GET", "/api/v1/orders");

        verify(unavailable).commence(any(), any(), any(AuthenticationServiceException.class));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void anOptionsCallThatIsNotAPreflightIsGatedLikeAnyOther() throws Exception {
        signedIn();
        when(requirement.isRequired(user)).thenReturn(true);

        MockHttpServletResponse response = call("OPTIONS", "/api/v1/orders");

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void aTokenWhoseSubjectIsNotAnAccountIdFailsClosed() throws Exception {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("not-a-uuid").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(), "not-a-uuid"));

        call("GET", "/api/v1/orders");

        verify(unavailable).commence(any(), any(), any(AuthenticationServiceException.class));
        verify(chain, never()).doFilter(any(), any());
        verify(requirement, never()).isRequired(any());
    }
}
