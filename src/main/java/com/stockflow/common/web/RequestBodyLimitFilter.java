package com.stockflow.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Role;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Caps the body of the routes listed in {@link RequestBodyLimitProperties}, before anything reads it.
 *
 * <p>Three checks. A capped route takes JSON only: a form or multipart body is refused with 415, because neither is read
 * through {@link HttpServletRequest#getInputStream} - Spring's form-content filter copies a form body into memory whole,
 * and the container parses multipart parts from the raw stream - so neither could be capped. A declared
 * {@code Content-Length} over the limit is answered 413 at once, without reading a byte. Otherwise the body is wrapped
 * so that reading past the limit throws {@link RequestBodyTooLargeException}: a chunked body is caught as it streams,
 * not after it has been buffered. The idempotency filter, Jackson and a handler reading the stream itself all meet that
 * exception, and each is answered 413.</p>
 *
 * <p>{@code @Order(-9950)}: after the correlation id filter (so a refusal carries one), before Spring's form-content
 * filter (-9900) and Spring Security (-100), and so before anything that could read the body. A refused request never
 * reaches the request log (order 10), so this filter logs it.</p>
 */
@Component
@Order(-9950)
// ROLE_INFRASTRUCTURE + final: see IdempotencyFilter's javadoc - a servlet filter must not be proxied.
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public final class RequestBodyLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestBodyLimitFilter.class);

    private record Route(String method, PathPattern pattern, long maxBytes) {
    }

    private final List<Route> routes;
    private final ObjectMapper objectMapper;
    private final Messages messages;

    public RequestBodyLimitFilter(RequestBodyLimitProperties properties, ObjectMapper objectMapper,
                                  Messages messages) {
        this.routes = properties.bodyLimits().stream()
                .map(limit -> new Route(limit.method(), PathPatternParser.defaultInstance.parse(limit.pattern()),
                        limit.maxBytes()))
                .toList();
        this.objectMapper = objectMapper;
        this.messages = messages;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return routes.isEmpty() || limitOf(request) < 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long limit = limitOf(request);
        if (!isJson(request.getContentType())) {
            refuse(request, response, ErrorCode.UNSUPPORTED_MEDIA_TYPE);
            return;
        }
        if (request.getContentLengthLong() > limit) {
            refuse(request, response, ErrorCode.REQUEST_BODY_TOO_LARGE);
            return;
        }
        chain.doFilter(new CappedRequest(request, limit), response);
    }

    private long limitOf(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return -1;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        PathContainer container = PathContainer.parsePath(path);
        for (Route route : routes) {
            if (route.method().equals(request.getMethod()) && route.pattern().matches(container)) {
                return route.maxBytes();
            }
        }
        return -1;
    }

    /** {@code application/json} or a {@code +json} type, parameters aside. */
    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return type.equals("application/json") || (type.startsWith("application/") && type.endsWith("+json"));
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, ErrorCode code) throws IOException {
        log.warn("{} {} refused before its body was read: {}", request.getMethod(), request.getRequestURI(),
                code.name());
        response.setStatus(code.httpStatus());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(code.name(), messages.forCode(code, request.getLocale()))));
    }

    /** The request with its body readable up to {@code limit} bytes; one more throws. */
    private static final class CappedRequest extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;
        private BufferedReader reader;

        CappedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CappedStream(super.getInputStream(), limit);
            }
            return stream;
        }

        /** One reader for the request, as the servlet contract has it; an unknown charset reads as UTF-8. */
        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                Charset charset = StandardCharsets.UTF_8;
                String encoding = getCharacterEncoding();
                if (encoding != null) {
                    try {
                        charset = Charset.forName(encoding);
                    } catch (IllegalArgumentException unknown) {
                        charset = StandardCharsets.UTF_8;
                    }
                }
                reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return reader;
        }
    }

    private static final class CappedStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long read;

        CappedStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws RequestBodyTooLargeException {
            read += n;
            if (read > limit) {
                throw new RequestBodyTooLargeException(limit);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
