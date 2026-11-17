package com.stockflow.chat.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Catalog declaration of {@code chat-supervision} (V20261124000300, kltn-docs 16 §2 "Người giữ quyền giám sát chat"):
 * assigning a conversation from the queue and taking an escalated one further. No endpoint of its own - the chat's
 * endpoints ask the service, which reads {@code chat-supervision:UPDATE} off the caller - so it is declared here.
 * Held by the Sales Manager and the administrator; ticking it for a Sales Staff member makes them a supervisor.
 */
@PermissionResource(code = ChatResources.SUPERVISION, group = "Sales chat", label = "Chat supervision",
        route = "/chat/supervision", apiPath = "/api/v1/chat/conversations",
        actions = {Action.VIEW_PAGE, Action.READ, Action.UPDATE})
final class ChatSupervisionResourceDeclaration {
    private ChatSupervisionResourceDeclaration() { }
}
