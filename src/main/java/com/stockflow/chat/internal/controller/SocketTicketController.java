package com.stockflow.chat.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.websocket.SocketTickets;
import com.stockflow.common.websocket.SocketTickets.SocketTicket;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tickets for the chat's STOMP socket ({@code /ws}), for a client whose script cannot read its own access token: the
 * back office, whose token lives in an HttpOnly cookie behind its BFF. The ticket goes in the CONNECT frame's
 * {@code ticket} header, opens one socket within 30 seconds, and carries what the token that asked for it carries,
 * no more and for no longer ({@link SocketTickets}).
 *
 * <p>Guarded by {@code chat-conversations:READ}, what the socket exists for. The CUSTOMER role holds it too; a ticket
 * gives a portal account exactly what its own bearer token already opens, so it is not refused here. 200, as login
 * answers with a token: nothing is created that the client could address afterwards. {@code no-store}: a credential,
 * however short-lived, is not for a cache. Rate-limited per user, so a reconnect loop cannot fill Redis with
 * tickets.</p>
 */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Sales chat (staff)")
class SocketTicketController {

    private final SocketTickets tickets;

    SocketTicketController(SocketTickets tickets) {
        this.tickets = tickets;
    }

    @PostMapping("/socket-tickets")
    @Operation(summary = "A single-use ticket, valid 30 seconds, to open the STOMP socket with (CONNECT header "
            + "'ticket')")
    @RequiresPermission(resource = ChatResources.CONVERSATIONS, action = Action.READ)
    @RateLimit(limit = 30, perSeconds = 60, key = RateLimit.Key.USER, bucket = "chat-socket-tickets")
    public ResponseEntity<ApiResponse<SocketTicket>> issue(@AuthenticationPrincipal Jwt token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(tickets.issue(token)));
    }
}
