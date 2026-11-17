package com.stockflow.chat.internal.service;

import com.stockflow.chat.internal.domain.Actor;
import com.stockflow.chat.internal.domain.Audience;
import com.stockflow.chat.internal.domain.ChatMessage;
import com.stockflow.chat.internal.domain.ChatMessageRepository;
import com.stockflow.chat.internal.domain.Conversation;
import com.stockflow.chat.internal.domain.ReadMarkers;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Reading a conversation, for either side: pages of messages by cursor, and the read marker. The side is the
 * {@link Audience} the caller passes in, which the repository applies in its query.
 */
@Component
public class ChatReading {

    private static final int DEFAULT_PAGE = 30;

    private final ChatMessageRepository messages;
    private final ReadMarkers markers;
    private final ChatViews views;
    private final ChatBroadcaster broadcaster;
    private final ChatProperties properties;
    private final Clock clock;

    public ChatReading(ChatMessageRepository messages, ReadMarkers markers, ChatViews views,
                       ChatBroadcaster broadcaster, ChatProperties properties, Clock clock) {
        this.messages = messages;
        this.markers = markers;
        this.views = views;
        this.broadcaster = broadcaster;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * A page of messages: the newest first before {@code before} (the newest of all when null), or - when
     * {@code since} is given - the oldest first after it, which is how a client that lost its socket catches up.
     */
    public ChatMessagePage page(Conversation c, Audience audience, Long before, Long since, Integer limit) {
        int size = properties.pageSize(limit, DEFAULT_PAGE);
        List<ChatMessage> found = since != null ? messages.after(c.id(), audience, Math.max(0, since), size + 1)
                : messages.before(c.id(), audience, before, size + 1);
        boolean more = found.size() > size;
        List<ChatMessage> page = more ? found.subList(0, size) : found;
        Long cursor = more ? page.get(page.size() - 1).seq() : null;
        return new ChatMessagePage(views.messages(page), since == null ? cursor : null, since != null ? cursor : null,
                lastSeq(c, audience));
    }

    /**
     * Moves the reader's marker to {@code seq} (the newest message when null), never back, and tells the other side.
     */
    public ChatReadMarkerView markRead(Conversation c, Actor reader, Long seq) {
        long newest = c.lastMessageSeq();
        long target = seq == null ? newest : Math.min(seq, newest);
        ReadMarkers.Marker marker = target < 1
                ? new ReadMarkers.Marker(reader.userId(), reader.type(), 0, null)
                : markers.advance(c.id(), reader.userId(), reader.type(), target, clock.instant());
        ChatReadMarkerView view = new ChatReadMarkerView(c.id(), marker.userId(), marker.readerType(), marker.seq(),
                marker.at());
        if (marker.seq() > 0) {
            broadcaster.conversation(c.id(), new ChatEvent(ChatEvent.MESSAGE_READ, c.id(), view, clock.instant()));
        }
        return view;
    }

    private long lastSeq(Conversation c, Audience audience) {
        if (audience == Audience.STAFF) {
            return c.lastMessageSeq();
        }
        Map<java.util.UUID, ChatMessage> last = messages.lastOf(List.of(c.id()), audience);
        ChatMessage newest = last.get(c.id());
        return newest == null ? 0 : newest.seq();
    }
}
