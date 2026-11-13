package com.stockflow.chat.internal.service;

import com.stockflow.chat.api.ChatContextRef;
import com.stockflow.chat.api.ChatConversationRef;
import com.stockflow.chat.api.ChatService;
import com.stockflow.chat.api.PostCardCommand;
import com.stockflow.chat.api.PostedCard;
import com.stockflow.chat.api.UpdateCardCommand;
import com.stockflow.chat.internal.domain.CardKind;
import com.stockflow.chat.internal.domain.CardState;
import com.stockflow.chat.internal.domain.ContextKind;
import com.stockflow.chat.internal.domain.ContextRef;
import com.stockflow.chat.internal.domain.ConversationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The only implementation of {@link ChatService}: the cards other modules put into conversations, and the
 * conversations about a document. A card is drawn by the system from a document (16 BR-06, BR-10, BR-11), never typed;
 * its action is the owning module's own operation (BR-03). The chat's own listeners ({@link DocumentCards}) go through
 * the same {@link ChatCards}, so a card posted through this api lands, is logged and is pushed exactly as theirs.
 */
@Service
@Transactional
class ChatServiceImpl implements ChatService {

    private static final int ABOUT_LIMIT = 50;

    private final ConversationRepository conversations;
    private final ChatCards cards;

    ChatServiceImpl(ConversationRepository conversations, ChatCards cards) {
        this.conversations = conversations;
        this.cards = cards;
    }

    @Override
    public PostedCard postCard(PostCardCommand command) {
        CardKind kind = CardKind.valueOf(command.kind().name());
        return cards.post(new ChatCards.NewCard(command.customerId(), ref(command.about()), List.of(), kind,
                cards.readPayload(command.payloadJson()), command.fallbackText(), command.sentBy(),
                kind == CardKind.QUOTE_REQUEST));
    }

    @Override
    public void updateCard(UpdateCardCommand command) {
        String action = command.action() == null || command.action().isBlank() ? null : command.action().strip();
        cards.update(command.messageId(), CardState.valueOf(command.state().name()),
                command.payloadJson() == null ? null : cards.readPayload(command.payloadJson()),
                cards.actorOf(command.actedBy()), command.actedBy() == null ? null : action,
                "{\"state\":\"" + command.state().name() + "\"}");
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatConversationRef> conversationsAbout(ChatContextRef about) {
        return conversations.about(ref(about), ABOUT_LIMIT).stream().map(c -> new ChatConversationRef(c.id(),
                c.customerId(), c.subject(), c.status().name(), c.assignedAgentId(), c.createdAt(),
                c.lastMessageAt())).toList();
    }

    private static ContextRef ref(ChatContextRef about) {
        return new ContextRef(ContextKind.valueOf(about.kind().name()), about.id());
    }
}
