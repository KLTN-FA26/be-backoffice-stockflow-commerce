package com.stockflow.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.i18n.Messages;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RequestBodyLimitFilter} answers a declared oversize body with 413 before reading it, caps an undeclared one as
 * it streams, and leaves every other route alone.
 */
class RequestBodyLimitFilterTest {

    private static final String DOCUMENT = "/api/v1/designs/0192f0a0-0000-7000-8000-000000000001/document";

    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(
            new RequestBodyLimitProperties(List.of(
                    new RequestBodyLimitProperties.Limit("put", "/api/v1/designs/{id}/document", 10))),
            new ObjectMapper().findAndRegisterModules(), new Messages(new StaticMessageSource()));

    @Test
    void aDeclaredLengthOverTheLimitIsRefusedWithoutReadingTheBody() throws Exception {
        var request = request("PUT", DOCUMENT, "01234567890");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("REQUEST_BODY_TOO_LARGE");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void anUndeclaredLengthIsCappedAsTheBodyStreams() throws Exception {
        // A chunked body: no Content-Length, so only reading it shows its size.
        var chunked = new MockHttpServletRequest("PUT", DOCUMENT) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        chunked.setContentType("application/json");
        chunked.setContent("01234567890".getBytes(StandardCharsets.UTF_8));
        var chain = new MockFilterChain();

        filter.doFilter(chunked, new MockHttpServletResponse(), chain);

        HttpServletRequest passed = (HttpServletRequest) chain.getRequest();
        assertThatThrownBy(() -> passed.getInputStream().readAllBytes())
                .isInstanceOf(RequestBodyTooLargeException.class);
    }

    @Test
    void aBodyWithinTheLimitIsPassedOnWhole() throws Exception {
        var chain = new MockFilterChain();

        filter.doFilter(request("PUT", DOCUMENT, "0123456789"), new MockHttpServletResponse(), chain);

        HttpServletRequest passed = (HttpServletRequest) chain.getRequest();
        assertThat(new String(passed.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("0123456789");
    }

    @Test
    void aCappedRouteTakesJsonOnlyBecauseNoOtherBodyCouldBeCapped() throws Exception {
        for (String type : new String[] {"application/x-www-form-urlencoded", "multipart/form-data; boundary=x",
                "text/plain"}) {
            var request = request("PUT", DOCUMENT, "a=1");
            request.setContentType(type);
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).as(type).isEqualTo(415);
            assertThat(chain.getRequest()).as(type).isNull();
        }
        var chain = new MockFilterChain();
        var vendorJson = request("PUT", DOCUMENT, "{}");
        vendorJson.setContentType("application/vnd.stockflow+json; charset=UTF-8");
        filter.doFilter(vendorJson, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void theReaderIsOneReaderAndAnUnknownCharsetReadsAsUtf8() throws Exception {
        var request = request("PUT", DOCUMENT, "{\"a\":1}");
        request.setCharacterEncoding("no-such-charset");
        var chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        HttpServletRequest passed = (HttpServletRequest) chain.getRequest();
        assertThat(passed.getReader()).isSameAs(passed.getReader());
        assertThat(passed.getReader().readLine()).isEqualTo("{\"a\":1}");
    }

    @Test
    void aHandlerReadingTheStreamItselfIsAnsweredTooLargeNotAServerError() {
        var source = new StaticMessageSource();
        var handler = new GlobalExceptionHandler(new Messages(source), source);

        var response = handler.handleBodyTooLarge(new RequestBodyTooLargeException(10));

        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody().errorCode()).isEqualTo("REQUEST_BODY_TOO_LARGE");
    }

    @Test
    void otherMethodsAndRoutesAreNotCapped() throws Exception {
        var otherMethod = new MockFilterChain();
        var post = request("POST", DOCUMENT, "01234567890123");
        filter.doFilter(post, new MockHttpServletResponse(), otherMethod);
        assertThat(otherMethod.getRequest()).isSameAs(post);

        var otherRoute = new MockFilterChain();
        var name = request("PUT", "/api/v1/designs/0192f0a0-0000-7000-8000-000000000001/name", "01234567890123");
        filter.doFilter(name, new MockHttpServletResponse(), otherRoute);
        assertThat(otherRoute.getRequest()).isSameAs(name);
    }

    @Test
    void theOverflowIsRecognisedThroughTheCausesAWrapperAdds() {
        var wrapped = new IllegalStateException(new RuntimeException(new RequestBodyTooLargeException(10)));

        assertThat(RequestBodyTooLargeException.causedBy(wrapped)).isTrue();
        assertThat(RequestBodyTooLargeException.causedBy(new IllegalStateException("other"))).isFalse();
    }

    @Test
    void jacksonRunningIntoTheCapIsAnsweredTooLargeNotMalformed() {
        var source = new StaticMessageSource();
        var handler = new GlobalExceptionHandler(new Messages(source), source);
        var input = new org.springframework.mock.http.MockHttpInputMessage(new byte[0]);

        var tooLarge = handler.handleUnreadable(new org.springframework.http.converter.HttpMessageNotReadableException(
                "I/O error while reading input message", new RequestBodyTooLargeException(10), input));
        var malformed = handler.handleUnreadable(
                new org.springframework.http.converter.HttpMessageNotReadableException("JSON parse error", input));

        assertThat(tooLarge.getStatusCode().value()).isEqualTo(413);
        assertThat(tooLarge.getBody().errorCode()).isEqualTo("REQUEST_BODY_TOO_LARGE");
        assertThat(malformed.getStatusCode().value()).isEqualTo(400);
    }

    private static MockHttpServletRequest request(String method, String path, String body) {
        var request = new MockHttpServletRequest(method, path);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
