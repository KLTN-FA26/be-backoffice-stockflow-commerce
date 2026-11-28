package com.stockflow.assistant.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rules a conversation and its turns keep without a database: questions, titles, the answering lease. */
class ConversationTest {

    private static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");

    @Test
    @DisplayName("a question is trimmed, and must be 1 to 4000 characters")
    void question() {
        assertThat(Conversation.question("  Mã nào sắp hết?  ")).isEqualTo("Mã nào sắp hết?");
        assertThat(Conversation.question("x".repeat(4000))).hasSize(4000);
        for (String bad : new String[] {null, "", "   ", "x".repeat(4001)}) {
            assertThatThrownBy(() -> Conversation.question(bad))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }

    @Test
    @DisplayName("the title is the first question on one line, cut with an ellipsis to 120 characters")
    void title() {
        assertThat(Conversation.titleFor("Đơn SO-1\n  đang   ở đâu?")).isEqualTo("Đơn SO-1 đang ở đâu?");
        String title = Conversation.titleFor("a".repeat(300));
        assertThat(title).hasSize(120).endsWith("…");
    }

    @Test
    @DisplayName("a claim blocks a second question only while it is younger than the lease")
    void answeringLease() {
        Duration lease = Duration.ofMinutes(15);
        Conversation free = conversation(null);
        Conversation fresh = conversation(NOW.minusSeconds(60));
        Conversation abandoned = conversation(NOW.minus(lease).minusSeconds(1));

        assertThat(free.isAnswering(NOW, lease)).isFalse();
        assertThat(fresh.isAnswering(NOW, lease)).isTrue();
        assertThat(abandoned.isAnswering(NOW, lease)).as("a killed process must not lock it forever").isFalse();
    }

    @Test
    @DisplayName("a request must end on the user's side: a question or tool results")
    void requestEndsWithTheUser() {
        Turn.ModelTurn model = new Turn.ModelTurn("{}", "hi", List.of());
        assertThatThrownBy(() -> new LanguageModel.Request("p", List.of(), List.of(model)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new LanguageModel.Request("p", List.of(), List.of(new Turn.Question("q", "c"))).history())
                .hasSize(1);
    }

    @Test
    @DisplayName("an ordinary request lets the model call tools and carries no note; a note is never blank")
    void requestToolsAndNote() {
        List<Turn> history = List.of(new Turn.Question("q", "c"));
        LanguageModel.Request ordinary = new LanguageModel.Request("p", List.of(), history);
        assertThat(ordinary.toolsAllowed()).isTrue();
        assertThat(ordinary.note()).isNull();
        assertThat(new LanguageModel.Request("p", List.of(), history, false, "answer now").toolsAllowed()).isFalse();
        assertThatThrownBy(() -> new LanguageModel.Request("p", List.of(), history, false, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a closing turn is the application's: its model name, no usage, and its text made plain to read")
    void closingTurns() {
        PendingMessage closing = PendingMessage.closing(new Turn.ModelTurn("{}", "Lô [A] còn 5.\n\nHết lượt.",
                List.of()));
        assertThat(closing.model()).isEqualTo(PendingMessage.CLOSING_MODEL);
        assertThat(closing.usage()).isEqualTo(LanguageModel.Usage.NONE);
        assertThat(closing.stopReason()).isEqualTo(LanguageModel.StopReason.OTHER);
        assertThat(closing.text()).isEqualTo("Lô \\[A\\] còn 5.\n\nHết lượt.");
        assertThat(closing.role()).isEqualTo(MessageRole.ASSISTANT);
    }

    @Test
    @DisplayName("only a model turn carries a model, a stop reason and tool executions")
    void pendingMessages() {
        assertThatThrownBy(() -> new PendingMessage(new Turn.Question("q", "c"), "q", "claude",
                LanguageModel.StopReason.END_TURN, LanguageModel.Usage.NONE, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PendingMessage.question(new Turn.Question("q", "c")).role()).isEqualTo(MessageRole.USER);
    }

    @Test
    @DisplayName("a tool result longer than the stored limit is refused, not silently cut")
    void resultLimit() {
        ToolCall call = new ToolCall("1", "findOrder", "{}");
        assertThatThrownBy(() -> new ToolExecution(call, ToolOutcome.OK, "x".repeat(20_001), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ToolExecution(call, ToolOutcome.DENIED, "NOT_PERMITTED", 1).toResult().error()).isTrue();
    }

    private static Conversation conversation(Instant answeringSince) {
        return new Conversation(UUID.randomUUID(), UUID.randomUUID(), "t", 2, NOW, answeringSince, NOW);
    }
}
