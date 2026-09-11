package com.stockflow.common.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * A request whose body can be read more than once.
 *
 * <h2>Why this is hand-written rather than Spring's {@code ContentCachingRequestWrapper}</h2>
 *
 * <p>The name of Spring's class suggests it does this. It does not. It records bytes <i>as they
 * are consumed</i> so they can be inspected <b>afterwards</b> — which is what request logging
 * needs — but it does not replay them. Read the body in a filter to fingerprint it, and the
 * controller downstream receives an empty stream and fails to deserialise, or worse, binds a
 * partially-read body.</p>
 *
 * <p>That mistake is easy to make and hard to see: it only shows up on requests that actually have
 * a body, which in a filter-ordering test is often none of them. So the body is buffered here,
 * once, and every {@code getInputStream()} returns a fresh stream over the same bytes.</p>
 *
 * <h2>The cost, stated plainly</h2>
 *
 * <p>The whole body is held in heap for the duration of the request. That is fine for JSON and
 * completely wrong for a file upload, so {@link IdempotencyFilter} never wraps a multipart request
 * — see its {@code shouldNotFilter}. If a future endpoint needs idempotent uploads, the key has to
 * be fingerprinted from headers rather than content.</p>
 */
final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    private CachedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    /** Reads the body once and returns a request that can hand it out repeatedly. */
    static CachedBodyRequest wrap(HttpServletRequest request) throws IOException {
        return new CachedBodyRequest(request, readFully(request));
    }

    /** The buffered body. Never null; an empty body is a zero-length array. */
    byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return source.read();
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                return source.read(buffer, offset, length);
            }

            @Override
            public int available() {
                return source.available();
            }

            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // Async reads are meaningless over an in-memory buffer: the data is already here.
                // Throwing rather than ignoring, so a future async endpoint fails loudly instead of
                // silently never being called back.
                throw new UnsupportedOperationException(
                        "CachedBodyRequest is fully buffered; async reads are not supported");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset()));
    }

    private Charset charset() {
        String encoding = getRequest().getCharacterEncoding();
        if (encoding == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (RuntimeException ex) {
            return StandardCharsets.UTF_8;
        }
    }

    private static byte[] readFully(HttpServletRequest request) throws IOException {
        try (ServletInputStream in = request.getInputStream()) {
            return in.readAllBytes();
        }
    }
}
