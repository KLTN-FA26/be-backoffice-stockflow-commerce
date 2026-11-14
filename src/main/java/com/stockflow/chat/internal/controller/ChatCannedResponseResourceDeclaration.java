package com.stockflow.chat.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.PermissionResource;

/**
 * Catalog declaration of {@code chat-canned-responses} (V20261124000300): Sales' saved replies with {variables},
 * personal or shared ({@code chat.canned_response}). Package 3 adds the endpoints; the grants are already in place.
 */
@PermissionResource(code = ChatResources.CANNED_RESPONSES, group = "Sales chat", label = "Canned responses",
        route = "/chat/canned-responses", apiPath = "/api/v1/chat/canned-responses",
        actions = {Action.VIEW_PAGE, Action.READ, Action.CREATE, Action.UPDATE, Action.DELETE})
final class ChatCannedResponseResourceDeclaration {
    private ChatCannedResponseResourceDeclaration() { }
}
