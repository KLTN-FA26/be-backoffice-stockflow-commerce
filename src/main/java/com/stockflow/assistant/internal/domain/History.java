package com.stockflow.assistant.internal.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * What of a conversation goes to the model with a new question (design §3.1): the question being answered with
 * every turn it has produced, and before it the last {@value #EARLIER_QUESTIONS} questions, each with only the answer
 * the user saw.
 *
 * <p><b>Earlier tool calls and results are not sent again.</b> Resending them made every question cost more than the
 * one before, without bound, until the history no longer fit the model and the conversation broke for good. They
 * were also rows the user may no longer be allowed to see: a manager moved to another warehouse, or a role that lost
 * a permission, would get them back by asking "list what you found earlier". The answer text was shown to the user
 * already; the rows behind it are fetched again, under today's permissions, if the new question needs them.</p>
 *
 * <p>The stored history is untouched: the transcript, the tool-call audit and the retention job all read every
 * turn.</p>
 */
public final class History {

    /** Earlier questions sent with a new one; older ones are left out. */
    public static final int EARLIER_QUESTIONS = 10;

    /** The longest earlier answer sent; a longer one is cut and marked. */
    public static final int EARLIER_ANSWER_LENGTH = 4000;

    static final String NOT_ANSWERED = "(No answer was given to this question.)";

    private History() {
    }

    /**
     * @param turns    the stored turns, oldest first, ending with the question being answered and the turns already
     *                 produced for it
     * @param textTurn a model turn that holds only text ({@link LanguageModel#closingTurn})
     */
    public static List<Turn> forModel(List<Turn> turns, Function<String, Turn.ModelTurn> textTurn) {
        List<List<Turn>> exchanges = new ArrayList<>();
        for (Turn turn : turns) {
            if (turn instanceof Turn.Question) {
                exchanges.add(new ArrayList<>());
            }
            if (!exchanges.isEmpty()) {
                exchanges.getLast().add(turn);
            }
        }
        if (exchanges.isEmpty()) {
            return List.copyOf(turns);
        }
        List<Turn> sent = new ArrayList<>();
        List<List<Turn>> earlier = exchanges.subList(0, exchanges.size() - 1);
        for (List<Turn> exchange : earlier.subList(Math.max(0, earlier.size() - EARLIER_QUESTIONS), earlier.size())) {
            sent.add(exchange.getFirst());
            sent.add(textTurn.apply(answerOf(exchange)));
        }
        sent.addAll(exchanges.getLast());
        return List.copyOf(sent);
    }

    /** The last text the model wrote for the exchange, cut to {@value #EARLIER_ANSWER_LENGTH} characters. */
    private static String answerOf(List<Turn> exchange) {
        for (int i = exchange.size() - 1; i > 0; i--) {
            if (exchange.get(i) instanceof Turn.ModelTurn model && model.text() != null && !model.text().isBlank()) {
                String text = model.text().strip();
                return text.length() > EARLIER_ANSWER_LENGTH ? text.substring(0, EARLIER_ANSWER_LENGTH) + " […]" : text;
            }
        }
        return NOT_ANSWERED;
    }
}
