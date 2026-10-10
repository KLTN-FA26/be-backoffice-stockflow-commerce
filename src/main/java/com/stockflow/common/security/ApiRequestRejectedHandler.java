package com.stockflow.common.security;

import com.stockflow.common.logging.LogSafe;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Answers a URL that Spring Security's firewall refuses — {@code //}, an encoded {@code ..}, a
 * {@code ;} — with a 400 in the platform's envelope.
 *
 * <h2>The 401 this replaces</h2>
 *
 * <p>The default handler calls {@code response.sendError(400)}. That makes the container dispatch
 * to {@code /error}, which goes through the security chain again as a request of its own: no bearer
 * token is read on an error dispatch, {@code /error} is not public, and the entry point answers
 * {@code 401 UNAUTHORIZED} — without a correlation id, because the MDC belongs to the original
 * dispatch. A client that built a path from an empty id got told to sign in again.</p>
 *
 * <p>So the body is written here with {@code setStatus}, never {@code sendError}: there is no second
 * dispatch, and the status the client sees is the one the firewall decided on. Spring Security picks
 * up a single bean of this type for both chains, so the answer is the same whether security is on
 * or off.</p>
 *
 * <p>The firewall's message names the character it rejected. That goes to the log, not to the
 * caller.</p>
 */
@Component
public class ApiRequestRejectedHandler implements RequestRejectedHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiRequestRejectedHandler.class);

    private final ObjectMapper objectMapper;
    private final Messages messages;

    public ApiRequestRejectedHandler(ObjectMapper objectMapper, Messages messages) {
        this.objectMapper = objectMapper;
        this.messages = messages;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       RequestRejectedException rejected) throws IOException {
        log.warn("Rejected request {} {}: {}",
                LogSafe.text(request.getMethod()), LogSafe.text(request.getRequestURI()),
                LogSafe.text(rejected.getMessage()));
        response.setStatus(ErrorCode.REQUEST_REJECTED.httpStatus());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(
                        ErrorCode.REQUEST_REJECTED.name(),
                        messages.forCode(ErrorCode.REQUEST_REJECTED),
                        MDC.get(CorrelationIdFilter.MDC_KEY))));
    }
}
